package com.alliot.osmo.demo.app.ui.media

import com.alliot.osmo.demo.media.model.MediaItem
import com.alliot.osmo.demo.media.model.MediaStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaUiTextTest {

    @Test
    fun `formatBytes uses binary units with one decimal`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("2.0 MB", formatBytes(2L * 1024 * 1024))
        assertEquals("1.2 GB", formatBytes((1.2 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `formatDurationSec renders mm_ss`() {
        assertEquals("", formatDurationSec(0))
        assertEquals("01:23", formatDurationSec(83))
        assertEquals("10:00", formatDurationSec(600))
    }

    @Test
    fun `mediaMetaLine combines size kind and badges`() {
        val row = MediaRow(
            item = MediaItem(
                path = "/DCIM/DJI_0001.MP4",
                thumbPath = "",
                store = MediaStore.SD_CARD,
                handle = 42L,
                sizeBytes = 2L * 1024 * 1024,
                durationSec = 83,
                isVideo = true,
            ),
            downloaded = true,
            canDelete = true,
        )
        val line = mediaMetaLine(row)
        assertTrue(line.contains("2.0 MB"))
        assertTrue(line.contains("视频"))
        assertTrue(line.contains("01:23"))
        assertTrue(line.contains("已下载"))
        assertTrue(line.contains("可删除"))
    }

    @Test
    fun `mediaMetaLine for photo without badges`() {
        val row = MediaRow(
            item = MediaItem(
                path = "/DCIM/DJI_0002.JPG",
                thumbPath = "",
                store = MediaStore.SD_CARD,
                sizeBytes = 1536,
            ),
            downloaded = false,
            canDelete = false,
        )
        assertEquals("1.5 KB · JPG", mediaMetaLine(row))
    }
}
