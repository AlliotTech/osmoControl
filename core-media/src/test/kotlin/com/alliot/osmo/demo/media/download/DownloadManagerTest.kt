package com.alliot.osmo.demo.media.download

import com.alliot.osmo.demo.media.model.MediaItem
import com.alliot.osmo.demo.media.model.MediaStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest

class DownloadManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val content = ByteArray(10_000) { (it * 31).toByte() }

    private fun item() = MediaItem(
        path = "DCIM/DJI_001/DJI_20240101120000_0001_D.MP4",
        thumbPath = "MISC/THM/DJI_001/DJI_20240101120000_0001_D.scr",
        store = MediaStore.SD_CARD,
    )

    private inner class FakeHttp(
        private val honorRange: Boolean = true,
    ) : HttpFileClient {
        var requests = 0
        override fun headSize(url: String): Long? = content.size.toLong()
        override fun get(url: String, rangeStart: Long, sink: OutputStream): HttpFileClient.GetResult {
            requests++
            return when {
                rangeStart >= content.size -> HttpFileClient.GetResult(416, null)
                rangeStart > 0 && honorRange -> {
                    sink.write(content, rangeStart.toInt(), content.size - rangeStart.toInt())
                    HttpFileClient.GetResult(206, (content.size - rangeStart).toLong())
                }
                else -> {
                    // Range ignored (or fresh): whole body, 200.
                    sink.write(content)
                    HttpFileClient.GetResult(200, content.size.toLong())
                }
            }
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    @Test
    fun full_download_verifies_sha256_and_records_history() {
        val history = FileHistoryStore(tmp.newFolder("hist"))
        val http = FakeHttp()
        val mgr = DownloadManager(http, history, tmp.newFolder("work"))
        val result = mgr.download("cam1", item())

        assertFalse(result.resumed)
        assertFalse(result.skippedAsDuplicate)
        assertEquals(content.size.toLong(), result.bytesWritten)
        assertEquals(sha256Hex(content), result.sha256Hex)
        assertArrayEquals(content, result.file.readBytes())
        assertTrue(
            history.isDownloaded("cam1", item().path, content.size.toLong(), result.sha256Hex),
        )
    }

    @Test
    fun interrupted_download_resumes_from_part() {
        val work = tmp.newFolder("work")
        val destDir = File(work, "cam1").apply { mkdirs() }
        val part = File(destDir, "DJI_20240101120000_0001_D.MP4.part")
        part.writeBytes(content.copyOfRange(0, 4000))

        val history = FileHistoryStore(tmp.newFolder("hist"))
        val mgr = DownloadManager(FakeHttp(), history, work)
        val result = mgr.download("cam1", item())

        assertTrue(result.resumed)
        assertEquals(sha256Hex(content), result.sha256Hex)
        assertArrayEquals(content, result.file.readBytes())
        assertFalse(part.exists())
    }

    @Test
    fun range_ignored_restarts_download() {
        val work = tmp.newFolder("work")
        val destDir = File(work, "cam1").apply { mkdirs() }
        File(destDir, "DJI_20240101120000_0001_D.MP4.part")
            .writeBytes("stale-prefix".toByteArray())

        val history = FileHistoryStore(tmp.newFolder("hist"))
        val mgr = DownloadManager(FakeHttp(honorRange = false), history, work)
        val result = mgr.download("cam1", item())

        assertFalse(result.resumed)
        assertEquals(sha256Hex(content), result.sha256Hex)
        assertArrayEquals(content, result.file.readBytes())
    }

    @Test
    fun range_past_eof_finalizes_existing_part() {
        val work = tmp.newFolder("work")
        val destDir = File(work, "cam1").apply { mkdirs() }
        // .part already holds the whole file (e.g. previous run died at 416).
        File(destDir, "DJI_20240101120000_0001_D.MP4.part").writeBytes(content)

        val history = FileHistoryStore(tmp.newFolder("hist"))
        val mgr = DownloadManager(FakeHttp(), history, work)
        val result = mgr.download("cam1", item())

        assertEquals(sha256Hex(content), result.sha256Hex)
        assertArrayEquals(content, result.file.readBytes())
    }

    @Test
    fun second_download_skips_as_duplicate() {
        val history = FileHistoryStore(tmp.newFolder("hist"))
        val http = FakeHttp()
        val mgr = DownloadManager(http, history, tmp.newFolder("work"))

        val first = mgr.download("cam1", item())
        assertFalse(first.skippedAsDuplicate)
        val requestsAfterFirst = http.requests

        val second = mgr.download("cam1", item())
        assertTrue(second.skippedAsDuplicate)
        assertEquals(requestsAfterFirst, http.requests) // no HTTP at all
        assertEquals(first.sha256Hex, second.sha256Hex)
    }

    @Test
    fun history_store_round_trip_and_delete_gate() {
        val dir = tmp.newFolder("hist")
        val history = FileHistoryStore(dir)
        val hash = sha256Hex(content)

        assertFalse(history.isDownloaded("cam1", item().path, content.size.toLong(), hash))
        assertFalse(history.canDeleteFromCamera("cam1", item().path, content.size.toLong()))

        history.recordDownload("cam1", item().path, content.size.toLong(), hash)
        assertTrue(history.isDownloaded("cam1", item().path, content.size.toLong(), hash))
        // Wrong hash or size does not count.
        assertFalse(history.isDownloaded("cam1", item().path, content.size.toLong(), "00"))
        assertFalse(history.isDownloaded("cam1", item().path, 1L, hash))
        assertTrue(history.canDeleteFromCamera("cam1", item().path, content.size.toLong()))

        // Persistence across instances.
        val reloaded = FileHistoryStore(dir)
        assertTrue(reloaded.isDownloaded("cam1", item().path, content.size.toLong(), hash))

        reloaded.markDeletedFromCamera("cam1", item().path)
        assertTrue(reloaded.wasDeletedFromCamera("cam1", item().path))
        assertFalse(reloaded.canDeleteFromCamera("cam1", item().path, content.size.toLong()))
    }

    @Test
    fun isFullyDownloaded_ignores_deleted_flag_but_requires_size_match() {
        val dir = tmp.newFolder("hist2")
        val history = FileHistoryStore(dir)
        val hash = sha256Hex(content)
        val size = content.size.toLong()

        assertFalse(history.isFullyDownloaded("cam1", item().path, size))
        history.recordDownload("cam1", item().path, size, hash)
        assertTrue(history.isFullyDownloaded("cam1", item().path, size))
        assertFalse(history.isFullyDownloaded("cam1", item().path, size + 1))

        // Still "fully downloaded" after the camera copy was deleted.
        history.markDeletedFromCamera("cam1", item().path)
        assertTrue(history.isFullyDownloaded("cam1", item().path, size))
    }
}
