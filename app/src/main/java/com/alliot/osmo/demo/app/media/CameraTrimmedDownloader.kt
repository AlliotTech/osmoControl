package com.alliot.osmo.demo.app.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import java.nio.ByteBuffer

/**
 * Re-muxes a time window off a full-res clip that is still **on the camera** and saves it into
 * `Movies/OsmoControl`, without re-encoding.
 *
 * A keyframe-aligned stream copy with [MediaExtractor] → [MediaMuxer]: because [MediaExtractor] speaks
 * HTTP and range-requests, pointing it at the full-res `.MP4` fetches the `moov` and then only the
 * samples inside the window (plus the keyframe before it). All A/V tracks are copied; PTS is rebased so
 * the trimmed clip starts at zero, and the source rotation is carried over as an orientation hint.
 *
 * osmoControl adaptation: instead of osmosis' `HttpClient` + `CameraFile`, the caller supplies the
 * absolute camera media URL and a display base-name directly to [trim]. The result is written to a
 * pending `Movies/OsmoControl` MediaStore item, so the class is self-contained.
 */
class CameraTrimmedDownloader(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    /**
     * Copy the window [trim] off the full-res video at [mediaUrl] (an absolute
     * `http://$ip/v2?storage=&path=` URL) and save it as `<baseName>_<startS>-<endS>s.mp4` into
     * `Movies/OsmoControl`. [baseName] is the display base-name of the clip (without extension). Returns the
     * saved item's Uri, or null when the trim failed. Blocking — call off the main thread.
     */
    fun trim(mediaUrl: String, baseName: String, trim: TrimRange): Uri? {
        if (!trim.isValid) { log("bad trim range: $baseName"); return null }
        val resolver = context.contentResolver
        val name = trimmedName(baseName, trim)
        val uri = createPending(name) ?: run { log("insert failed: $name"); return null }
        val pfd = runCatching { resolver.openFileDescriptor(uri, "rw") }.getOrNull()
            ?: run { log("open failed: $name"); runCatching { resolver.delete(uri, null, null) }; return null }

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(mediaUrl)
            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackMap = HashMap<Int, Int>()
            var bufCap = 4 shl 20 // 4 MB floor — a 4K keyframe can be several MB
            for (t in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(t)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                extractor.selectTrack(t)
                trackMap[t] = muxer.addTrack(fmt)
                if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                    bufCap = maxOf(bufCap, fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                if (mime.startsWith("video/") && fmt.containsKey(MediaFormat.KEY_ROTATION))
                    muxer.setOrientationHint(fmt.getInteger(MediaFormat.KEY_ROTATION))
            }
            if (trackMap.isEmpty()) { log("no A/V tracks: $name"); resolver.delete(uri, null, null); return null }

            val endUs = trim.endMs * 1000
            muxer.start()
            extractor.seekTo(trim.startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val buf = ByteBuffer.allocate(bufCap)
            val info = MediaCodec.BufferInfo()
            var firstPts = -1L
            var written = 0L
            while (true) {
                val tIdx = extractor.sampleTrackIndex
                if (tIdx < 0) break
                val pts = extractor.sampleTime
                if (pts > endUs) break
                val outTrack = trackMap[tIdx]
                if (outTrack == null) { extractor.advance(); continue }
                val size = extractor.readSampleData(buf, 0)
                if (size < 0) break
                if (firstPts < 0) firstPts = pts
                info.offset = 0
                info.size = size
                info.presentationTimeUs = (pts - firstPts).coerceAtLeast(0)
                info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                    MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(outTrack, buf, info)
                written += size
                extractor.advance()
            }
            muxer.stop()
            markComplete(uri)
            log("trimmed $baseName → $name (${written / 1_000_000} MB of ${trim.durationMs} ms)")
            return uri
        } catch (e: Exception) {
            log("trim FAILED $baseName: ${e.javaClass.simpleName} ${e.message}")
            runCatching { resolver.delete(uri, null, null) }
            return null
        } finally {
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
            runCatching { pfd.close() }
        }
    }

    private fun trimmedName(baseName: String, trim: TrimRange): String =
        "${baseName}_${trim.startMs / 1000}-${trim.endMs / 1000}s.mp4"

    // ---- MediaStore plumbing ------------------------------------------------

    /** Insert a pending Movies/OsmoControl video item. */
    private fun createPending(displayName: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, MOVIES_DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
    }

    private fun markComplete(uri: Uri) {
        context.contentResolver.update(
            uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null
        )
    }

    companion object {
        /** Same folder the downloader writes videos to. */
        private const val MOVIES_DIR = "Movies/OsmoControl"
    }
}
