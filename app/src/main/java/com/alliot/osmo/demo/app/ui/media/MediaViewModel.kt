package com.alliot.osmo.demo.app.ui.media

import android.content.Context
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.alliot.osmo.demo.app.di.AppContainer
import com.alliot.osmo.demo.app.net.CameraApJoiner
import com.alliot.osmo.demo.app.media.CameraFrameCapture
import com.alliot.osmo.demo.app.media.CameraTrimmedDownloader
import com.alliot.osmo.demo.app.media.TrimRange
import com.alliot.osmo.demo.media.datalink.DatalinkTransport
import com.alliot.osmo.demo.media.download.DownloadManager
import com.alliot.osmo.demo.media.download.FileHistoryStore
import com.alliot.osmo.demo.media.download.HistoryStore
import com.alliot.osmo.demo.media.download.UrlConnectionHttpClient
import com.alliot.osmo.demo.media.model.MediaItem
import com.alliot.osmo.demo.media.exif.EmbeddedJpeg
import com.alliot.osmo.demo.media.model.MediaStore
import com.alliot.osmo.demo.media.repo.MediaRepository
import com.alliot.osmo.demo.session.SessionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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
    /** Camera AP SSID; blank means "phone is already on the AP, skip auto-join". */
    val wifiSsid: String = "",
    /** Camera AP passphrase. Held in memory only, never persisted. */
    val wifiPassword: String = "",
    /** The Osmo 360 AP is WPA3-SAE; every other body is WPA2-PSK. */
    val wifiWpa3: Boolean = false,
    /** Row awaiting a frame-capture offset from the dialog, or null. */
    val frameCaptureTarget: MediaRow? = null,
    /** Row awaiting a trim in/out from the dialog, or null. */
    val trimTarget: MediaRow? = null,
    /** Paths with a running capture/trim job (buttons disabled while present). */
    val processing: Set<String> = emptySet(),
    /**
     * path -> embedded/served thumbnail JPEG bytes; a null value means "fetched, none
     * available". A missing key means "not yet requested".
     */
    val thumbnails: Map<String, ByteArray?> = emptyMap(),
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
 * Bring-up: either supply the camera AP SSID/passphrase so [connectAndLoad]
 * auto-joins it (Android 10+, via [CameraApJoiner]) and binds the process to
 * that network, or leave the SSID blank and put the phone on the AP yourself.
 * Then [connectAndLoad] opens the UDP datalink ([DatalinkTransport]), lists
 * both stores ([MediaRepository]) and downloads go over HTTP
 * ([DownloadManager]). All blocking calls run on Dispatchers.IO.
 */
class MediaViewModel(
    appContext: Context,
    private val pairingIdentifier: String,
    /** The live BLE session, used to read the camera's Wi-Fi credentials for auto-join. */
    private val sessionController: SessionController? = null,
) : ViewModel() {

    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val filesDir: File = appContext.filesDir
    private val history: HistoryStore =
        FileHistoryStore(File(filesDir, HISTORY_DIR).apply { mkdirs() })
    private val workDir: File = File(filesDir, WORK_DIR).apply { mkdirs() }
    private val app = appContext.applicationContext

    private val stackMutex = Mutex()
    private var transport: DatalinkTransport? = null
    private var repository: MediaRepository? = null
    private var downloader: DownloadManager? = null
    private var apJoiner: CameraApJoiner? = null

    private val _state = MutableStateFlow(
        MediaUiState(
            cameraIp = prefs.getString(KEY_CAMERA_IP, DEFAULT_CAMERA_IP) ?: DEFAULT_CAMERA_IP,
            wifiSsid = prefs.getString(KEY_WIFI_SSID, "") ?: "",
        ),
    )
    val state: StateFlow<MediaUiState> = _state.asStateFlow()

    fun updateCameraIp(ip: String) {
        _state.update { it.copy(cameraIp = ip, status = null) }
        prefs.edit().putString(KEY_CAMERA_IP, ip).apply()
    }

    fun updateWifiSsid(ssid: String) {
        _state.update { it.copy(wifiSsid = ssid, status = null) }
        prefs.edit().putString(KEY_WIFI_SSID, ssid).apply()
    }

    fun updateWifiPassword(password: String) {
        _state.update { it.copy(wifiPassword = password, status = null) }
    }

    fun updateWifiWpa3(wpa3: Boolean) {
        _state.update { it.copy(wifiWpa3 = wpa3, status = null) }
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
                        joinWifiIfNeeded()
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

    /**
     * Bring the phone onto the camera AP before any socket opens. Credentials come, in
     * order of preference, from (1) a manually entered SSID/password, else (2) the
     * connected camera itself over BLE ([SessionController.fetchWifiCredentials] —
     * 0x07/0x07 SSID, 0x07/0x0e password, so no manual entry is normally needed), else
     * (3) nothing, meaning the phone is assumed already on the AP. Throws with a
     * user-facing message on join failure so the caller's failure path surfaces it.
     */
    private suspend fun joinWifiIfNeeded() {
        var ssid = _state.value.wifiSsid.trim()
        var password = _state.value.wifiPassword
        var wpa3 = _state.value.wifiWpa3

        if (ssid.isEmpty()) {
            val creds = runCatching { sessionController?.fetchWifiCredentials() }.getOrNull()
            if (creds != null) {
                ssid = creds.ssid
                password = creds.password
                wpa3 = creds.wpa3
                _state.update {
                    it.copy(
                        wifiSsid = creds.ssid,
                        wifiWpa3 = creds.wpa3,
                        status = "已从相机读取 Wi-Fi 凭据，正在入网「${creds.ssid}」…",
                    )
                }
            }
        }

        if (ssid.isEmpty()) return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IllegalStateException("自动入网需要 Android 10 及以上；请手动连上相机 Wi-Fi 后重试。")
        }
        apJoiner?.release()
        val joined = CompletableDeferred<Result<Unit>>()
        val joiner = CameraApJoiner(
            app,
            object : CameraApJoiner.Listener {
                override fun onLog(s: String) {}
                override fun onNetwork(network: Network, link: LinkProperties?) {
                    joined.complete(Result.success(Unit))
                }
                override fun onFailed(reason: String) {
                    joined.complete(Result.failure(IllegalStateException(reason)))
                }
                override fun onLost() = onWifiLost()
            },
        )
        apJoiner = joiner
        joiner.join(ssid, password, wpa3)
        val result = withTimeoutOrNull(WIFI_JOIN_TIMEOUT_MS) { joined.await() }
        if (result == null) {
            joiner.release()
            apJoiner = null
            throw IllegalStateException("加入 Wi-Fi「$ssid」超时；请检查密码或相机热点是否开启。")
        }
        result.getOrElse {
            joiner.release()
            apJoiner = null
            throw IllegalStateException(it.message ?: "加入 Wi-Fi 失败")
        }
    }

    /** The camera AP dropped mid-session: tear the datalink down so the UI reflects reality. */
    private fun onWifiLost() {
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
                    isLoading = false,
                    status = "相机 Wi-Fi 已断开，请重新连接。",
                )
            }
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

    // ---- remote frame capture (one full-res frame, no full download) ----

    fun requestFrameCapture(row: MediaRow) {
        _state.update { it.copy(frameCaptureTarget = row) }
    }

    fun cancelFrameCapture() {
        _state.update { it.copy(frameCaptureTarget = null) }
    }

    /** Decode the frame at [offsetMs] of the pending target video and save it to Pictures/Osmosis. */
    fun confirmFrameCapture(offsetMs: Long) {
        val row = _state.value.frameCaptureTarget ?: return
        val dl = downloader
        val ip = _state.value.cameraIp.trim()
        if (dl == null) {
            _state.update { it.copy(frameCaptureTarget = null, status = "请先连接相机。") }
            return
        }
        val path = row.item.path
        _state.update {
            it.copy(
                frameCaptureTarget = null,
                processing = it.processing + path,
                status = "正在从 ${row.item.name} 抽帧 …",
            )
        }
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) {
                runCatching {
                    val storage = dl.probe(row.item)?.first ?: row.item.store.index
                    val mediaPath = dl.mediaUrl(row.item, storage)
                    CameraFrameCapture(app, { p -> "http://$ip$p" }, {})
                        .capture(mediaPath, row.item.name.substringBeforeLast('.'), offsetMs)
                }.getOrNull()
            }
            _state.update {
                it.copy(
                    processing = it.processing - path,
                    status = if (uri != null) "已保存抽帧到相册（Pictures/Osmosis）。"
                    else "抽帧失败：该帧无法解码。",
                )
            }
        }
    }

    // ---- trimmed download (keyframe-aligned stream copy, fetches only the window) ----

    fun requestTrim(row: MediaRow) {
        _state.update { it.copy(trimTarget = row) }
    }

    fun cancelTrim() {
        _state.update { it.copy(trimTarget = null) }
    }

    /** Re-mux only [startMs, endMs] of the pending target video into Movies/Osmosis. */
    fun confirmTrim(startMs: Long, endMs: Long) {
        val row = _state.value.trimTarget ?: return
        val dl = downloader
        val ip = _state.value.cameraIp.trim()
        if (dl == null) {
            _state.update { it.copy(trimTarget = null, status = "请先连接相机。") }
            return
        }
        val range = TrimRange(startMs, endMs)
        if (!range.isValid) {
            _state.update { it.copy(trimTarget = null, status = "无效区间：结束时间需大于开始时间。") }
            return
        }
        val path = row.item.path
        _state.update {
            it.copy(
                trimTarget = null,
                processing = it.processing + path,
                status = "正在裁剪下载 ${row.item.name} …",
            )
        }
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) {
                runCatching {
                    val storage = dl.probe(row.item)?.first ?: row.item.store.index
                    val mediaUrl = "http://$ip" + dl.mediaUrl(row.item, storage)
                    CameraTrimmedDownloader(app, {})
                        .trim(mediaUrl, row.item.name.substringBeforeLast('.'), range)
                }.getOrNull()
            }
            _state.update {
                it.copy(
                    processing = it.processing - path,
                    status = if (uri != null) "裁剪完成，已保存到相册（Movies/Osmosis）。"
                    else "裁剪失败。",
                )
            }
        }
    }

    // ---- thumbnails (served rendition, else EXIF-embedded for stills) ----

    /**
     * Lazily fetch [row]'s thumbnail once: a served `thumbPath` rendition when the record
     * has one, otherwise the EXIF-embedded thumbnail lifted from the first 64 KB of a still
     * ([EmbeddedJpeg]). Idempotent per path.
     */
    fun loadThumbnail(row: MediaRow) {
        val path = row.item.path
        if (_state.value.thumbnails.containsKey(path)) return
        val ip = _state.value.cameraIp.trim()
        val item = row.item
        _state.update { it.copy(thumbnails = it.thumbnails + (path to null)) }
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { fetchThumbnail(ip, item) }.getOrNull()
            }
            if (bytes != null) {
                _state.update { it.copy(thumbnails = it.thumbnails + (path to bytes)) }
            }
        }
    }

    private fun fetchThumbnail(ip: String, item: MediaItem): ByteArray? {
        val dl = downloader ?: return null
        val storage = dl.probe(item)?.first ?: item.store.index
        if (item.thumbPath.isNotBlank()) {
            val url = "/v2?storage=$storage&path=" + URLEncoder.encode(item.thumbPath, Charsets.UTF_8)
            httpGetCapped(ip, url, Long.MAX_VALUE)?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        if (!item.isVideo) {
            val head = httpGetCapped(ip, dl.mediaUrl(item, storage), EmbeddedJpeg.HEAD_BYTES.toLong())
                ?: return null
            return EmbeddedJpeg.fromHeader(head)
        }
        return null
    }

    /** GET at most [maxBytes] of a camera path (Range-capped), or null on any error. */
    private fun httpGetCapped(ip: String, path: String, maxBytes: Long): ByteArray? {
        val conn = (URL("http://$ip$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 15_000
            if (maxBytes in 1 until Long.MAX_VALUE) setRequestProperty("Range", "bytes=0-${maxBytes - 1}")
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8_192)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    if (maxBytes != Long.MAX_VALUE && total >= maxBytes) break
                }
                out.toByteArray()
            }
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
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
                    apJoiner?.release()
                    apJoiner = null
                }
            }
            _state.update {
                it.copy(
                    connection = MediaConnectionState.DISCONNECTED,
                    connectionError = null,
                    sdItems = emptyList(),
                    internalItems = emptyList(),
                    downloadProgress = emptyMap(),
                    thumbnails = emptyMap(),
                    processing = emptySet(),
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
        apJoiner?.release()
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
        private const val KEY_WIFI_SSID = "wifi_ssid"
        private const val WIFI_JOIN_TIMEOUT_MS = 30_000L
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
            sessionController = container.realController,
        ) as T
    }
}
