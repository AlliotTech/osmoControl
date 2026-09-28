package com.alliot.osmo.demo.media.download

import com.alliot.osmo.demo.media.model.MediaItem
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Minimal HTTP surface the [DownloadManager] needs. The default
 * implementation talks to the camera AP gateway over plain HTTP.
 */
interface HttpFileClient {
    data class GetResult(val statusCode: Int, val contentLength: Long?)

    fun headSize(url: String): Long?
    fun get(url: String, rangeStart: Long, sink: OutputStream): GetResult
}

/** [HttpFileClient] over `HttpURLConnection`, rooted at `http://[host]`. */
class UrlConnectionHttpClient(private val host: String) : HttpFileClient {
    override fun headSize(url: String): Long? {
        val conn = open(url)
        return try {
            conn.requestMethod = "HEAD"
            conn.connect()
            if (conn.responseCode in 200..299) conn.getHeaderFieldLong("Content-Length", -1)
                .takeIf { it >= 0 } else null
        } finally {
            conn.disconnect()
        }
    }

    override fun get(url: String, rangeStart: Long, sink: OutputStream): HttpFileClient.GetResult {
        val conn = open(url)
        try {
            if (rangeStart > 0) conn.setRequestProperty("Range", "bytes=$rangeStart-")
            conn.connect()
            val code = conn.responseCode
            val length = conn.getHeaderFieldLong("Content-Length", -1).takeIf { it >= 0 }
            if (code == HttpURLConnection.HTTP_PARTIAL || code == HttpURLConnection.HTTP_OK) {
                conn.inputStream.use { input -> input.copyTo(sink) }
            }
            return HttpFileClient.GetResult(code, length)
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL("http://$host$url").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
}

data class DownloadResult(
    val file: File,
    val bytesWritten: Long,
    val sha256Hex: String,
    /** True when an existing `.part` prefix was resumed instead of restarted. */
    val resumed: Boolean,
    /** True when history already had this exact file — nothing was fetched. */
    val skippedAsDuplicate: Boolean,
)

/**
 * Downloads camera media over HTTP with resume and integrity:
 *
 * - URL shape `/v2?storage=<index>&path=<url-encoded path>` (osmosis
 *   `PathAddressing`); the storage mount is confirmed with one HEAD,
 *   falling back to the other mount when the guess 404s (osmosis
 *   `probe_storage`).
 * - Interrupted downloads resume from a `.part` file via
 *   `Range: bytes=<pos>-`; the SHA-256 is re-seeded by hashing the
 *   existing prefix first (OsmoOffload's downloader pattern).
 * - A server that ignores Range (200 instead of 206) restarts the file;
 *   416 (range past EOF) finalizes the existing `.part`.
 * - On completion the file is SHA-256-hashed, atomically promoted, and
 *   recorded in the [HistoryStore]; a later call for the same
 *   path+size+hash returns [DownloadResult.skippedAsDuplicate].
 *
 * Blocking; call off the UI thread. [onProgress] receives
 * (bytesSoFar, totalBytesOrNull).
 */
class DownloadManager(
    private val http: HttpFileClient,
    private val history: HistoryStore,
    private val workDir: File,
    private val log: (String) -> Unit = {},
) {

    // The camera's /v2 endpoint matches the `path` param literally (osmosis PathAddressing) — it does
    // NOT %-decode, so the DCF path is passed raw. URL-encoding the slashes made every HEAD 404 and
    // surfaced as "No HTTP mount serves".
    fun mediaUrl(item: MediaItem, storage: Int = item.store.index): String =
        "/v2?storage=$storage&path=${item.path}"

    /**
     * Finds the mount serving [item] and its size: tries the manifest's
     * mount first, then the other one. Returns `(storage, sizeBytes?)`.
     */
    fun probe(item: MediaItem): Pair<Int, Long?>? {
        val candidates = listOf(item.store.index, 1 - item.store.index).distinct()
        for (storage in candidates) {
            val size = http.headSize(mediaUrl(item, storage))
            if (size != null) return storage to size
        }
        return null
    }

    fun download(
        cameraId: String,
        item: MediaItem,
        onProgress: (bytesSoFar: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): DownloadResult {
        workDir.mkdirs()
        val destDir = File(workDir, sanitize(cameraId)).apply { mkdirs() }
        val dest = File(destDir, sanitize(item.name))
        val part = File(destDir, sanitize(item.name) + PART_SUFFIX)

        val (storage, remoteSize) = probe(item)
            ?: throw java.io.IOException("No HTTP mount serves ${item.path}")
        val url = mediaUrl(item, storage)
        val expectedSize = remoteSize ?: item.sizeBytes.takeIf { it > 0 }

        // History short-circuit: same path + size + hash already verified.
        val existingHash = if (dest.isFile && expectedSize != null && dest.length() == expectedSize) {
            sha256HexOf(dest).takeIf { history.isDownloaded(cameraId, item.path, expectedSize, it) }
        } else null
        if (existingHash != null) {
            log("download: skip duplicate ${item.name}")
            return DownloadResult(dest, dest.length(), existingHash, resumed = false, skippedAsDuplicate = true)
        }

        var pos = if (part.isFile) part.length() else 0L
        var resumed = pos > 0
        val digest = MessageDigest.getInstance("SHA-256")
        if (pos > 0) {
            // Re-seed the hash with the bytes we already have.
            part.inputStream().use { input ->
                val buf = ByteArray(8192)
                var n: Int
                while (input.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
            }
        }

        log("download: ${item.name} -> $dest (resume from $pos)")
        fun fetch(rangeStart: Long, append: Boolean): HttpFileClient.GetResult =
            PartSink(part, append, digest, onProgress, expectedSize).use { sink ->
                http.get(url, rangeStart, sink)
            }
        val result = fetch(pos, pos > 0)
        when (result.statusCode) {
            206 -> { /* appended after pos */ }
            200 -> if (pos > 0) {
                // Server ignored Range — start over.
                log("download: Range ignored, restarting ${item.name}")
                pos = 0
                resumed = false
                digest.reset()
                part.delete()
                val restart = fetch(0, false)
                check(restart.statusCode == 200 || restart.statusCode == 206) {
                    "Restart failed: HTTP ${restart.statusCode}"
                }
            } /* else: fresh 200, the normal case */
            416 -> {
                // Range past EOF: the .part is already complete (or over-complete).
                log("download: 416, treating .part as complete for ${item.name}")
            }
            else -> throw java.io.IOException("GET $url -> HTTP ${result.statusCode}")
        }

        if (expectedSize != null && part.length() != expectedSize) {
            throw java.io.IOException(
                "Size mismatch for ${item.name}: got ${part.length()}, expected $expectedSize",
            )
        }
        val hash = digest.digest().toHex()
        if (!part.renameTo(dest)) {
            dest.delete()
            check(part.renameTo(dest)) { "Could not promote ${part.name}" }
        }
        history.recordDownload(cameraId, item.path, dest.length(), hash)
        log("download: done ${item.name} sha256=${hash.take(16)}…")
        return DownloadResult(dest, dest.length(), hash, resumed, skippedAsDuplicate = false)
    }

    private inner class PartSink(
        private val part: File,
        append: Boolean,
        private val digest: MessageDigest,
        private val onProgress: (Long, Long?) -> Unit,
        private val total: Long?,
    ) : OutputStream() {
        private val out = FileOutputStream(part, append)
        private var written = if (append) part.length() else 0L

        override fun write(b: Int) {
            out.write(b)
            digest.update(b.toByte())
            written++
            onProgress(written, total)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            digest.update(b, off, len)
            written += len
            onProgress(written, total)
        }

        override fun flush() = out.flush()
        override fun close() = out.close()
    }

    companion object {
        const val PART_SUFFIX = ".part"

        private fun sanitize(name: String): String =
            name.replace(Regex("[^A-Za-z0-9._-]"), "_")

        private fun ByteArray.toHex(): String =
            joinToString("") { "%02x".format(it) }

        internal fun sha256HexOf(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(8192)
                var n: Int
                while (input.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
            }
            return digest.digest().toHex()
        }
    }
}
