package com.alliot.osmo.demo.app.media

import android.content.Context
import com.alliot.osmo.demo.media.download.DownloadManager
import com.alliot.osmo.demo.media.model.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Snapshot of the background download queue, consumed by both the UI and the foreground notification. */
data class DownloadQueueState(
    /** path -> 0..1 for the file currently transferring (at most one, since transfers are serial). */
    val progress: Map<String, Float> = emptyMap(),
    /** Paths finished this run — lets the grid show a ✓ without re-reading history. */
    val completed: Set<String> = emptySet(),
    val running: Boolean = false,
    val currentName: String? = null,
    val currentProgress: Float = 0f,
    val doneCount: Int = 0,
    val pending: Int = 0,
) {
    /** Total in this batch, for an "n / m" label. */
    val total: Int get() = doneCount + pending + if (running) 1 else 0
}

/**
 * Process-scoped, serial download queue that outlives the media screen's ViewModel.
 *
 * Downloads run in an application-scoped coroutine (not `viewModelScope`) so they survive the Activity
 * being backgrounded or destroyed; a [DownloadService] foreground notification keeps the process — and
 * thus the bound camera Wi-Fi network — alive for the duration. One transfer at a time over the single
 * camera-AP link. Held by [com.alliot.osmo.demo.app.OsmoDemoApplication] so the service and every
 * ViewModel share one instance.
 */
class DownloadCoordinator(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = ArrayDeque<MediaItem>()
    private val lock = Any()
    private var worker: Job? = null

    @Volatile private var downloader: DownloadManager? = null
    @Volatile private var cameraId: String = ""

    private val _state = MutableStateFlow(DownloadQueueState())
    val state: StateFlow<DownloadQueueState> = _state.asStateFlow()

    val isRunning: Boolean get() = _state.value.running

    /** Point the queue at the live datalink's downloader. Call on every (re)connect. */
    fun configure(downloader: DownloadManager, cameraIp: String) {
        this.downloader = downloader
        this.cameraId = "camera@$cameraIp"
    }

    /** Add [items] (skipping any already queued or transferring) and make sure the worker is running. */
    fun enqueue(items: List<MediaItem>) {
        if (downloader == null || items.isEmpty()) return
        synchronized(lock) {
            val known = queue.mapTo(HashSet()) { it.path }
            val active = _state.value.progress.keys
            for (item in items) if (item.path !in known && item.path !in active) queue.add(item)
        }
        _state.update { it.copy(pending = queueSize()) }
        ensureWorker()
    }

    /** Clear the queue and stop after the current file (a blocking transfer isn't force-killed). */
    fun cancelAll() {
        synchronized(lock) { queue.clear() }
        worker?.cancel()
        worker = null
        _state.value = DownloadQueueState()
        DownloadService.stop(appContext)
    }

    private fun queueSize(): Int = synchronized(lock) { queue.size }

    private fun ensureWorker() {
        synchronized(lock) {
            if (worker?.isActive == true) return
            worker = scope.launch { runQueue() }
        }
    }

    private suspend fun runQueue() {
        _state.update { it.copy(running = true) }
        DownloadService.start(appContext)
        try {
            while (true) {
                val item = synchronized(lock) { queue.removeFirstOrNull() } ?: break
                val dl = downloader ?: break
                val path = item.path
                _state.update {
                    it.copy(currentName = item.name, currentProgress = 0f, progress = it.progress + (path to 0f), pending = queueSize())
                }
                val result = runCatching {
                    dl.download(cameraId = cameraId, item = item) { soFar, total ->
                        if (total != null && total > 0) {
                            val f = (soFar.toFloat() / total).coerceIn(0f, 1f)
                            _state.update { it.copy(progress = it.progress + (path to f), currentProgress = f) }
                        }
                    }
                }.getOrNull()

                if (result != null && !result.skippedAsDuplicate && GalleryStore.isSupported()) {
                    runCatching {
                        val uri = GalleryStore.publish(appContext, result.file, item.name, item.isVideo)
                        if (uri != null) result.file.delete()
                    }
                }
                _state.update {
                    val done = if (result != null) it.doneCount + 1 else it.doneCount
                    it.copy(
                        progress = it.progress - path,
                        completed = if (result != null) it.completed + path else it.completed,
                        doneCount = done,
                        pending = queueSize(),
                    )
                }
            }
        } finally {
            _state.update { it.copy(running = false, currentName = null, currentProgress = 0f, pending = 0) }
            DownloadService.stop(appContext)
            synchronized(lock) { worker = null }
        }
    }
}
