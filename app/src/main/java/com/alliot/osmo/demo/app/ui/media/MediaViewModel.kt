package com.alliot.osmo.demo.app.ui.media

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.alliot.osmo.demo.app.di.AppContainer
import com.alliot.osmo.demo.media.datalink.DatalinkTransport
import com.alliot.osmo.demo.media.download.DownloadManager
import com.alliot.osmo.demo.media.download.FileHistoryStore
import com.alliot.osmo.demo.media.download.HistoryStore
import com.alliot.osmo.demo.media.download.UrlConnectionHttpClient
import com.alliot.osmo.demo.media.model.MediaItem
import com.alliot.osmo.demo.media.model.MediaStore
import com.alliot.osmo.demo.media.repo.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

enum class MediaConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    FAILED,
}

/** One manifest record plus the local facts the UI needs. */
data class MediaRow(
    val item: MediaItem,
    /** Downloaded before and SHA-256-verified against history. */
    val downloaded: Boolean,
    /** Safe to offer "delete from camera" right now. */
    val canDelete: Boolean,
)

data class MediaUiState(
    val cameraIp: String = MediaViewModel.DEFAULT_CAMERA_IP,
    val connection: MediaConnectionState = MediaConnectionState.DISCONNECTED,
    val connectionError: String? = null,
    val isLoading: Boolean = false,
    val sdItems: List<MediaRow> = emptyList(),
    val internalItems: List<MediaRow> = emptyList(),
    /** path -> 0..1 while a download is running. */
    val downloadProgress: Map<String, Float> = emptyMap(),
    val status: String? = null,
    val deleteCandidate: MediaRow? = null,
)

/** One page per store from [MediaRepository.listNewest]. */
private data class LoadedPages(
    val sd: MediaRepository.MediaPage,
    val internal: MediaRepository.MediaPage,
)

/**
 * Media browsing / download / delete over the camera's Wi-Fi datalink.
 *
 * Bring-up: the phone must already be on the camera's Wi-Fi AP; then
 * [connectAndLoad] opens the UDP datalink ([DatalinkTransport]), lists
 * both stores ([MediaRepository]) and downloads go over HTTP
 * ([DownloadManager]). All blocking calls run on Dispatchers.IO.
 */
class MediaViewModel(
    appContext: Context,
    private val pairingIdentifier: String,
) : ViewModel() {

    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val filesDir: File = appContext.filesDir
    private val history: HistoryStore =
        FileHistoryStore(File(filesDir, HISTORY_DIR).apply { mkdirs() })
    private val workDir: File = File(filesDir, WORK_DIR).apply { mkdirs() }

    private val stackMutex = Mutex()
    private var transport: DatalinkTransport? = null
    private var repository: MediaRepository? = null
    private var downloader: DownloadManager? = null

    private val _state = MutableStateFlow(
        MediaUiState(cameraIp = prefs.getString(KEY_CAMERA_IP, DEFAULT_CAMERA_IP) ?: DEFAULT_CAMERA_IP),
    )
    val state: StateFlow<MediaUiState> = _state.asStateFlow()

    fun updateCameraIp(ip: String) {
        _state.update { it.copy(cameraIp = ip, status = null) }
        prefs.edit().putString(KEY_CAMERA_IP, ip).apply()
    }

    fun connectAndLoad() {
        val ip = _state.value.cameraIp.trim()
        if (ip.isEmpty()) {
            _state.update { it.copy(status = "请先填写相机 IP。") }
            return
        }
        if (_state.value.connection == MediaConnectionState.CONNECTING ||
            _state.value.isLoading
        ) {
            return
        }
        _state.update {
            it.copy(
                connection = MediaConnectionState.CONNECTING,
                connectionError = null,
                isLoading = true,
                status = "正在连接 $ip …",
            )
        }
        viewModelScope.launch {
            val outcome: Result<LoadedPages> = withContext(Dispatchers.IO) {
                try {
                    stackMutex.withLock {
                        transport?.close()
                        val t = DatalinkTransport()
                        check(t.open(ip, pairingIdentifier)) { "UDP datalink 握手失败" }
                        t.registerApp()
                        val repo = MediaRepository(t)
                        val dl = DownloadManager(
                            http = UrlConnectionHttpClient(ip),
                            history = history,
                            workDir = workDir,
                        )
                        transport = t
                        repository = repo
                        downloader = dl
                        Result.success(
                            LoadedPages(
                                sd = repo.listNewest(MediaStore.SD_CARD),
                                internal = repo.listNewest(MediaStore.INTERNAL),
                            ),
                        )
                    }
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            outcome.fold(
                onSuccess = { pages ->
                    val sdPage = pages.sd
                    val internalPage = pages.internal
                    val cameraId = cameraIdFor(ip)
                    val sdRows = sdPage.items.map { it.toRow(cameraId) }
                    val internalRows = internalPage.items.map { it.toRow(cameraId) }
                    val hint = when {
                        sdPage.tooEarly || internalPage.tooEarly ->
                            "相机刚进入回放模式（d8），请等几秒后重新加载。"
                        sdRows.isEmpty() && internalRows.isEmpty() ->
                            "已连接，但没有读到媒体条目。"
                        else -> null
                    }
                    _state.update {
                        it.copy(
                            connection = MediaConnectionState.CONNECTED,
                            isLoading = false,
                            sdItems = sdRows,
                            internalItems = internalRows,
                            status = hint
                                ?: "已加载：SD 卡 ${sdRows.size} 项，机身内存 ${internalRows.size} 项。",
                        )
                    }
                },
                onFailure = { e ->
                    withContext(Dispatchers.IO) {
                        stackMutex.withLock {
                            transport?.close()
                            transport = null
                            repository = null
                            downloader = null
                        }
                    }
                    _state.update {
                        it.copy(
                            connection = MediaConnectionState.FAILED,
                            connectionError = e.message ?: "连接失败",
                            isLoading = false,
                            status = "连接失败：${e.message ?: "未知错误"}。请确认手机已连上相机 Wi-Fi，且 IP 正确。",
                        )
                    }
                },
            )
        }
    }

    fun download(row: MediaRow) {
        val path = row.item.path
        if (_state.value.downloadProgress.containsKey(path)) return
        val dl = downloader
        if (dl == null) {
            _state.update { it.copy(status = "请先连接相机。") }
            return
        }
        _state.update {
            it.copy(
                downloadProgress = it.downloadProgress + (path to 0f),
                status = "开始下载 ${row.item.name} …",
            )
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    dl.download(
                        cameraId = cameraIdFor(_state.value.cameraIp.trim()),
                        item = row.item,
                    ) { soFar, total ->
                        if (total != null && total > 0) {
                            val fraction = (soFar.toFloat() / total).coerceIn(0f, 1f)
                            _state.update {
                                it.copy(downloadProgress = it.downloadProgress + (path to fraction))
                            }
                        }
                    }
                }
            }
            result.fold(
                onSuccess = { r ->
                    val cameraId = cameraIdFor(_state.value.cameraIp.trim())
                    _state.update { s ->
                        val refresh: (List<MediaRow>) -> List<MediaRow> = { rows ->
                            rows.map { existing ->
                                if (existing.item.path == path) existing.item.toRow(cameraId)
                                else existing
                            }
                        }
                        s.copy(
                            downloadProgress = s.downloadProgress - path,
                            sdItems = refresh(s.sdItems),
                            internalItems = refresh(s.internalItems),
                            status = if (r.skippedAsDuplicate) {
                                "${row.item.name} 已下载过，跳过。"
                            } else {
                                "下载完成：${row.item.name}（${formatBytes(r.bytesWritten)}，SHA-256 已校验）。"
                            },
                        )
                    }
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(
                            downloadProgress = it.downloadProgress - path,
                            status = "下载失败：${e.message ?: "未知错误"}",
                        )
                    }
                },
            )
        }
    }

    fun requestDelete(row: MediaRow) {
        _state.update { it.copy(deleteCandidate = row) }
    }

    fun cancelDelete() {
        _state.update { it.copy(deleteCandidate = null) }
    }

    fun confirmDelete() {
        val row = _state.value.deleteCandidate ?: return
        val repo = repository
        if (repo == null) {
            _state.update { it.copy(deleteCandidate = null, status = "请先连接相机。") }
            return
        }
        _state.update { it.copy(deleteCandidate = null, status = "正在从相机删除 ${row.item.name} …") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repo.delete(listOf(row.item)) }
            }
            result.fold(
                onSuccess = { statusWord ->
                    if (statusWord == 0) {
                        val cameraId = cameraIdFor(_state.value.cameraIp.trim())
                        history.markDeletedFromCamera(cameraId, row.item.path)
                        _state.update { s ->
                            s.copy(
                                sdItems = s.sdItems.filterNot { it.item.path == row.item.path },
                                internalItems = s.internalItems.filterNot { it.item.path == row.item.path },
                                status = "已从相机删除 ${row.item.name}。",
                            )
                        }
                    } else {
                        _state.update {
                            it.copy(status = "相机拒绝删除（status=${statusWord?.let { "0x%04x".format(it) } ?: "无应答"}）。")
                        }
                    }
                },
                onFailure = { e ->
                    _state.update { it.copy(status = "删除失败：${e.message ?: "未知错误"}") }
                },
            )
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                stackMutex.withLock {
                    transport?.close()
                    transport = null
                    repository = null
                    downloader = null
                }
            }
            _state.update {
                it.copy(
                    connection = MediaConnectionState.DISCONNECTED,
                    connectionError = null,
                    sdItems = emptyList(),
                    internalItems = emptyList(),
                    downloadProgress = emptyMap(),
                    status = "已断开。",
                )
            }
        }
    }

    fun clearStatus() {
        _state.update { it.copy(status = null) }
    }

    override fun onCleared() {
        transport?.close()
        super.onCleared()
    }

    private fun MediaItem.toRow(cameraId: String): MediaRow {
        val downloaded = sizeBytes > 0 &&
            history.isFullyDownloaded(cameraId, path, sizeBytes)
        return MediaRow(
            item = this,
            downloaded = downloaded,
            canDelete = deletable && history.canDeleteFromCamera(cameraId, path, sizeBytes),
        )
    }

    private fun cameraIdFor(ip: String): String = "camera@$ip"

    companion object {
        const val DEFAULT_CAMERA_IP = "192.168.2.1"
        private const val PREFS_NAME = "media_prefs"
        private const val KEY_CAMERA_IP = "camera_ip"
        private const val HISTORY_DIR = "media-history"
        private const val WORK_DIR = "media"
    }
}

class MediaViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return MediaViewModel(
            appContext = container.appContext,
            pairingIdentifier = container.controllerDeviceId.toString(),
        ) as T
    }
}
