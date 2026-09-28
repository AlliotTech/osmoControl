package com.alliot.osmo.demo.app.ui.media

import android.content.Context
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.alliot.osmo.demo.app.di.AppContainer
import com.alliot.osmo.demo.app.net.CameraApJoiner
import com.alliot.osmo.demo.app.media.CameraFrameCapture
import com.alliot.osmo.demo.app.media.GalleryStore
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
import com.alliot.osmo.demo.protocol.duml.StoresStatusPayload
import com.alliot.osmo.demo.session.SessionController
import com.alliot.osmo.demo.session.model.SessionDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

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
    val connection: MediaConnectionState = MediaConnectionState.DISCONNECTED,
    val connectionError: String? = null,
    val isLoading: Boolean = false,
    /** True once the camera is connected over BLE (prerequisite for the media Wi-Fi flow). */
    val cameraConnected: Boolean = false,
    val sdItems: List<MediaRow> = emptyList(),
    val internalItems: List<MediaRow> = emptyList(),
    /** path -> 0..1 while a download is running. */
    val downloadProgress: Map<String, Float> = emptyMap(),
    val status: String? = null,
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
    /** path -> full-resolution still JPEG bytes for the viewer; null = fetching, missing = not requested. */
    val fullImages: Map<String, ByteArray?> = emptyMap(),
    val deleteCandidate: MediaRow? = null,
    /** True while a BLE scan for cameras is running and the picker is shown. */
    val scanning: Boolean = false,
    /** Cameras discovered in the current scan, for the user to pick from. */
    val scannedDevices: List<SessionDevice> = emptyList(),
    /** Per-store capacity from the camera's 0x02/0xDC push; null = not reported. */
    val storesStatus: StoresStatusPayload? = null,
)

/** One page per store from [MediaRepository.listNewest]. */
private data class LoadedPages(
    val sd: MediaRepository.MediaPage,
    val internal: MediaRepository.MediaPage,
    val stores: StoresStatusPayload?,
)

/**
 * Media browsing / download / delete over the camera's Wi-Fi datalink.
 *
 * Bring-up mirrors osmosis: once the camera is paired over BLE, [connectAndLoad]
 * reads the camera's own AP credentials over that link
 * ([SessionController.fetchWifiCredentials]), joins the AP and binds the process
 * ([CameraApJoiner]), opens the UDP datalink ([DatalinkTransport]) to the fixed
 * camera gateway [DEFAULT_CAMERA_IP], lists both stores ([MediaRepository]) and
 * downloads over HTTP ([DownloadManager]). No manual Wi-Fi / IP entry. All
 * blocking calls run on Dispatchers.IO.
 */
class MediaViewModel(
    appContext: Context,
    private val pairingIdentifier: String,
    /** The live BLE session: source of the camera's Wi-Fi credentials and connection state. */
    private val sessionController: SessionController? = null,
) : ViewModel() {

    private val filesDir: File = appContext.filesDir
    private val history: HistoryStore =
        FileHistoryStore(File(filesDir, HISTORY_DIR).apply { mkdirs() })
    private val workDir: File = File(filesDir, WORK_DIR).apply { mkdirs() }
    private val app = appContext.applicationContext

    /** DJI Osmo cameras always serve their AP gateway here (osmosis hardcodes the same). */
    private val cameraIp: String = DEFAULT_CAMERA_IP

    private val stackMutex = Mutex()
    private var transport: DatalinkTransport? = null
    private var repository: MediaRepository? = null
    private var downloader: DownloadManager? = null
    private var apJoiner: CameraApJoiner? = null
    private var scanJob: Job? = null
    private var wifiRejoins = 0
    private val pendingResumePaths = mutableSetOf<String>()

    private val _state = MutableStateFlow(MediaUiState())
    val state: StateFlow<MediaUiState> = _state.asStateFlow()

    init {
        sessionController?.let { controller ->
            viewModelScope.launch {
                controller.status.collect { s ->
                    _state.update { it.copy(cameraConnected = s.protocolReady && !s.sleeping) }
                }
            }
        }
    }

    /**
     * Entry from the connect button. Reuses a live workbench link; otherwise starts a BLE scan and
     * lets the user pick which camera to connect ([connectDevice]).
     */
    fun connectAndLoad() {
        if (_state.value.connection == MediaConnectionState.CONNECTING || _state.value.isLoading) return
        if (sessionController?.status?.value?.protocolReady == true) {
            runConnect(chosen = null)
        } else {
            beginScan()
        }
    }

    /** Start scanning and stream discovered cameras into the UI for the user to choose from. */
    private fun beginScan() {
        val controller = sessionController ?: run {
            _state.update {
                it.copy(connection = MediaConnectionState.FAILED, status = "当前为模拟模式，媒体功能需要真实相机。")
            }
            return
        }
        scanJob?.cancel()
        _state.update {
            it.copy(
                scanning = true,
                scannedDevices = emptyList(),
                connectionError = null,
                status = "正在扫描相机蓝牙，请从下方选择要连接的相机 …",
            )
        }
        scanJob = viewModelScope.launch {
            runCatching { controller.startScan() }
            controller.devices.collect { list -> _state.update { it.copy(scannedDevices = list) } }
        }
    }

    /** User picked a camera from the scan list: stop scanning and run the full connect + load. */
    fun connectDevice(device: SessionDevice) {
        scanJob?.cancel()
        scanJob = null
        viewModelScope.launch { runCatching { sessionController?.stopScan() } }
        _state.update { it.copy(scanning = false, scannedDevices = emptyList()) }
        runConnect(chosen = device)
    }

    /** Abort the scan without connecting. */
    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        viewModelScope.launch { runCatching { sessionController?.stopScan() } }
        _state.update { it.copy(scanning = false, scannedDevices = emptyList(), status = "已取消扫描。") }
    }

    private fun runConnect(chosen: SessionDevice?) {
        wifiRejoins = 0
        pendingResumePaths.clear()
        _state.update {
            it.copy(
                connection = MediaConnectionState.CONNECTING,
                connectionError = null,
                isLoading = true,
                status = "正在连接相机 …",
            )
        }
        viewModelScope.launch {
            val outcome: Result<LoadedPages> = withContext(Dispatchers.IO) {
                try {
                    stackMutex.withLock {
                        joinWifiIfNeeded(chosen)
                        transport?.close()
                        val t = DatalinkTransport(log = { Log.d(LOG_TAG, "datalink: $it") })
                        check(t.open(cameraIp, pairingIdentifier)) { "UDP datalink 握手失败" }
                        t.registerApp()
                        val repo = MediaRepository(t)
                        val dl = DownloadManager(
                            http = UrlConnectionHttpClient(cameraIp),
                            history = history,
                            workDir = workDir,
                            log = { Log.d(LOG_TAG, "download: $it") },
                        )
                        transport = t
                        repository = repo
                        downloader = dl
                        Result.success(
                            LoadedPages(
                                sd = repo.listNewest(MediaStore.SD_CARD),
                                internal = repo.listNewest(MediaStore.INTERNAL),
                                stores = repo.readStoresStatus(),
                            ),
                        )
                    }
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "connectAndLoad failed", e)
                    Result.failure(e)
                }
            }
            outcome.fold(
                onSuccess = { pages ->
                    val sdPage = pages.sd
                    val internalPage = pages.internal
                    val cameraId = cameraIdFor(cameraIp)
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
                            storesStatus = pages.stores,
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
                            status = "连接失败：${e.message ?: "未知错误"}。",
                        )
                    }
                },
            )
        }
    }

    /**
     * Ensures a live BLE/DUML session to the camera so [joinWifiIfNeeded] can read its Wi-Fi
     * credentials - reusing a live workbench link, otherwise connecting the user-[chosen] camera
     * (picked from the scan list) and waking it, all from this screen (no workbench trip).
     */
    private suspend fun ensureCameraSession(chosen: SessionDevice?) {
        val controller = sessionController
            ?: throw IllegalStateException("当前为模拟模式，媒体功能需要真实相机。")
        val now = controller.status.value
        if (now.protocolReady && !now.sleeping) return
        if (now.protocolReady && now.sleeping) {
            _state.update { it.copy(status = "相机休眠中，正在唤醒 …") }
            controller.wake()
            awaitReadyAwake(controller, "唤醒相机超时，请重试。")
            return
        }
        val device = chosen ?: throw IllegalStateException("请先选择要连接的相机。")
        _state.update { it.copy(status = "正在连接「${device.name}」蓝牙 …") }
        controller.connect(device)
        awaitReadyAwake(controller, "相机连接超时，请重试或在工作台确认配对。")
    }

    /** Waits until the session is protocol-ready and awake, waking once if it comes up sleeping. */
    private suspend fun awaitReadyAwake(controller: SessionController, timeoutMessage: String) {
        val ready = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            controller.status.first { it.protocolReady }
            true
        } ?: false
        if (!ready) throw IllegalStateException(timeoutMessage)
        if (controller.status.value.sleeping) {
            controller.wake()
            val awake = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                controller.status.first { it.protocolReady && !it.sleeping }
                true
            } ?: false
            if (!awake) throw IllegalStateException("相机已连接但唤醒失败，请重试。")
        }
    }

    /**
     * Brings up the camera's Wi-Fi and binds the process without leaving this screen:
     * ensures a BLE/DUML link exists ([ensureCameraSession] — scans + connects + wakes on its
     * own if the workbench hasn't), reads the camera's own AP credentials over that link
     * ([SessionController.fetchWifiCredentials] — 0x07/0x07 SSID, 0x07/0x0e password), then joins
     * that AP and binds the process ([CameraApJoiner]) before any socket opens. No manual entry;
     * throws with a user-facing message when a step fails, surfaced on the caller's failure path.
     */
    private suspend fun joinWifiIfNeeded(chosen: SessionDevice?) {
        ensureCameraSession(chosen)
        val creds = runCatching { sessionController?.fetchWifiCredentials() }
            .onFailure { Log.w(LOG_TAG, "fetchWifiCredentials threw", it) }
            .getOrNull()
            ?: throw IllegalStateException(
                "已连上相机蓝牙，但没能读到 Wi-Fi 凭据，请重试或稍后再试。",
            )
        Log.d(LOG_TAG, "joinWifi: creds ssid='${creds.ssid}' wpa3=${creds.wpa3}")
        val ssid = creds.ssid
        val password = creds.password
        val wpa3 = creds.wpa3
        _state.update { it.copy(status = "已从相机读取 Wi-Fi 凭据，正在入网「$ssid」…") }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IllegalStateException("自动入网需要 Android 10 及以上。")
        }
        apJoiner?.release()
        val joined = CompletableDeferred<Result<Unit>>()
        val joiner = CameraApJoiner(
            app,
            object : CameraApJoiner.Listener {
                override fun onLog(s: String) { Log.d(LOG_TAG, "apjoiner: $s") }
                override fun onNetwork(network: Network, link: LinkProperties?) {
                    if (!joined.isCompleted) joined.complete(Result.success(Unit))
                    else onWifiRejoined()
                }
                override fun onFailed(reason: String) {
                    joined.complete(Result.failure(IllegalStateException(reason)))
                }
                override fun onLost() = onWifiLost()
            },
        )
        apJoiner = joiner
        Log.d(LOG_TAG, "joinWifi: requesting AP '$ssid' wpa3=$wpa3")
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

    /**
     * The camera AP dropped mid-session. WifiNetworkSpecifier does not reconnect on its
     * own, so retry up to [MAX_WIFI_REJOINS] times via [CameraApJoiner.rejoin]; any
     * in-flight downloads are remembered and resumed once the AP is back
     * ([onWifiRejoined]). Only after the retries are exhausted is the datalink torn down.
     */
    private fun onWifiLost() {
        viewModelScope.launch {
            pendingResumePaths.addAll(_state.value.downloadProgress.keys)
            val canRejoin = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                apJoiner != null && wifiRejoins < MAX_WIFI_REJOINS
            if (canRejoin) {
                wifiRejoins++
                _state.update {
                    it.copy(status = "相机 Wi-Fi 掉线，正在重连（$wifiRejoins/$MAX_WIFI_REJOINS）…")
                }
                if (apJoiner?.rejoin() != true) {
                    _state.update { it.copy(status = "无法重连相机 Wi-Fi。") }
                }
                return@launch
            }
            withContext(Dispatchers.IO) {
                stackMutex.withLock {
                    transport?.close()
                    transport = null
                    repository = null
                    downloader = null
                }
            }
            apJoiner?.release()
            apJoiner = null
            runCatching { sessionController?.releaseMediaLink() }
            _state.update {
                it.copy(
                    connection = MediaConnectionState.DISCONNECTED,
                    isLoading = false,
                    downloadProgress = emptyMap(),
                    status = "相机 Wi-Fi 掉线，重连 $MAX_WIFI_REJOINS 次仍失败，请重新连接。",
                )
            }
            pendingResumePaths.clear()
        }
    }

    /**
     * A rejoin succeeded (a second [CameraApJoiner] onNetwork): the process is rebound to
     * the camera network, so HTTP works again. Keep the loaded grid and resume any
     * downloads that were in flight when the AP dropped — each picks up from its `.part`.
     */
    private fun onWifiRejoined() {
        viewModelScope.launch {
            val resume = pendingResumePaths.toList()
            pendingResumePaths.clear()
            _state.update {
                it.copy(
                    status = if (resume.isEmpty()) "相机 Wi-Fi 已重连。"
                    else "相机 Wi-Fi 已重连，正在恢复 ${resume.size} 个下载 …",
                )
            }
            if (resume.isNotEmpty()) {
                val rows = _state.value.sdItems + _state.value.internalItems
                resume.forEach { path ->
                    rows.firstOrNull { it.item.path == path }?.let { download(it) }
                }
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
                        cameraId = cameraIdFor(cameraIp),
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
                    val cameraId = cameraIdFor(cameraIp)
                    val savedToGallery = if (!r.skippedAsDuplicate &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ) {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                val uri = GalleryStore.publish(app, r.file, row.item.name, row.item.isVideo)
                                if (uri != null) r.file.delete()
                                uri != null
                            }.getOrDefault(false)
                        }
                    } else {
                        false
                    }
                    _state.update { s ->
                        val refresh: (List<MediaRow>) -> List<MediaRow> = { rows ->
                            rows.map { existing ->
                                if (existing.item.path == path) existing.item.toRow(cameraId) else existing
                            }
                        }
                        s.copy(
                            downloadProgress = s.downloadProgress - path,
                            sdItems = refresh(s.sdItems),
                            internalItems = refresh(s.internalItems),
                            status = when {
                                r.skippedAsDuplicate -> "${row.item.name} 已下载过，跳过。"
                                savedToGallery ->
                                    "下载完成：${row.item.name}（${formatBytes(r.bytesWritten)}），已保存到系统相册。"
                                else ->
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

    /** Decode the frame at [offsetMs] of the pending target video and save it to Pictures/OsmoControl. */
    fun confirmFrameCapture(offsetMs: Long) {
        val row = _state.value.frameCaptureTarget ?: return
        val dl = downloader
        val ip = cameraIp
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
                    status = if (uri != null) "已保存抽帧到相册（Pictures/OsmoControl）。"
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

    /** Re-mux only [startMs, endMs] of the pending target video into Movies/OsmoControl. */
    fun confirmTrim(startMs: Long, endMs: Long) {
        val row = _state.value.trimTarget ?: return
        val dl = downloader
        val ip = cameraIp
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
                    status = if (uri != null) "裁剪完成，已保存到相册（Movies/OsmoControl）。"
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
        val ip = cameraIp
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
            val url = "/v2?storage=$storage&path=${item.thumbPath}"
            httpGetCapped(ip, url, Long.MAX_VALUE)?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        if (!item.isVideo) {
            val head = httpGetCapped(ip, dl.mediaUrl(item, storage), EmbeddedJpeg.HEAD_BYTES.toLong())
                ?: return null
            return EmbeddedJpeg.fromHeader(head)
        }
        return null
    }

    /**
     * HTTP URL to stream [row] straight off the camera - full-res video for the player, or the
     * full still. Uses the record's own store; playback falls back to nothing if that mount 404s.
     */
    fun playbackUrl(row: MediaRow): String =
        "http://$cameraIp/v2?storage=${row.item.store.index}&path=${row.item.path}"

    /** Lazily fetch a still's full-resolution JPEG for the viewer. No-op for videos. Idempotent. */
    fun loadFullImage(row: MediaRow) {
        val item = row.item
        if (item.isVideo) return
        val path = item.path
        if (_state.value.fullImages.containsKey(path)) return
        val ip = cameraIp
        _state.update { it.copy(fullImages = it.fullImages + (path to null)) }
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    val storage = downloader?.probe(item)?.first ?: item.store.index
                    httpGetCapped(ip, "/v2?storage=$storage&path=${item.path}", Long.MAX_VALUE)
                }.getOrNull()
            }?.takeIf { it.isNotEmpty() }
            if (bytes != null) {
                _state.update { it.copy(fullImages = it.fullImages + (path to bytes)) }
            }
        }
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
        Log.d(LOG_TAG, "requestDelete: ${row.item.name} canDelete=${row.canDelete} handle=0x${row.item.handle.toString(16)}")
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
        Log.d(
            LOG_TAG,
            "delete: ${row.item.name} handle=0x${row.item.handle.toString(16)} shared=${row.item.handleShared} " +
                "store=${row.item.store.index}",
        )
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repo.delete(listOf(row.item)) }
            }
            result.fold(
                onSuccess = { statusWord ->
                    Log.d(LOG_TAG, "delete result status=${statusWord?.let { "0x%04x".format(it) } ?: "null"}")
                    // The camera performs the delete even when it answers with a non-zero/absent status
                    // word (observed on an Action 6 Pro), so remove the row from the grid whenever the
                    // call returned without throwing, and reconcile on the next reload.
                    val cameraId = cameraIdFor(cameraIp)
                    history.markDeletedFromCamera(cameraId, row.item.path)
                    _state.update { s ->
                        s.copy(
                            sdItems = s.sdItems.filterNot { it.item.path == row.item.path },
                            internalItems = s.internalItems.filterNot { it.item.path == row.item.path },
                            status = when (statusWord) {
                                0 -> "已从相机删除 ${row.item.name}。"
                                null -> "已删除 ${row.item.name}（相机未回状态，已从列表移除）。"
                                else -> "已删除 ${row.item.name}（相机返回 0x%04x）。".format(statusWord)
                            },
                        )
                    }
                    reloadCurrentLists()
                },
                onFailure = { e ->
                    Log.w(LOG_TAG, "delete failed", e)
                    _state.update { it.copy(status = "删除失败：${e.message ?: "未知错误"}") }
                },
            )
        }
    }

    /** Re-list both stores from the camera to reconcile the grid with reality (e.g. after a delete). */
    private fun reloadCurrentLists() {
        val repo = repository ?: return
        viewModelScope.launch {
            val pages = withContext(Dispatchers.IO) {
                runCatching {
                    LoadedPages(
                        sd = repo.listNewest(MediaStore.SD_CARD),
                        internal = repo.listNewest(MediaStore.INTERNAL),
                        stores = repo.readStoresStatus(),
                    )
                }.getOrNull()
            } ?: return@launch
            val cameraId = cameraIdFor(cameraIp)
            _state.update {
                it.copy(
                    sdItems = pages.sd.items.map { row -> row.toRow(cameraId) },
                    internalItems = pages.internal.items.map { row -> row.toRow(cameraId) },
                    storesStatus = pages.stores ?: it.storesStatus,
                )
            }
        }
    }

    fun disconnect() {
        scanJob?.cancel()
        scanJob = null
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                stackMutex.withLock {
                    transport?.close()
                    transport = null
                    repository = null
                    downloader = null
                    apJoiner?.release()
                    apJoiner = null
                    runCatching { sessionController?.releaseMediaLink() }
                    wifiRejoins = 0
                    pendingResumePaths.clear()
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
                    fullImages = emptyMap(),
                    processing = emptySet(),
                    scanning = false,
                    scannedDevices = emptyList(),
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
        private const val HISTORY_DIR = "media-history"
        private const val WORK_DIR = "media"
        private const val WIFI_JOIN_TIMEOUT_MS = 30_000L
        /** How long to wait for the BLE handshake to reach protocol-ready. */
        private const val CONNECT_TIMEOUT_MS = 25_000L
        /** AP rejoin attempts allowed per session before giving up (osmosis MAX_WIFI_REJOINS). */
        private const val MAX_WIFI_REJOINS = 3
        private const val LOG_TAG = "OsmoMedia"
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
