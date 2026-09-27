package com.alliot.osmo.demo.media.repo

import com.alliot.osmo.demo.media.model.MediaStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaManifestParserTest {

    private val videoBase = "DJI_20240101120000_0001_D"
    private val photoBase = "DJI_20240101120001_0002_D"

    private fun u32le(v: Long): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte(),
    )

    private fun pathField(sub: Int, path: String): ByteArray {
        val ascii = path.toByteArray(Charsets.ISO_8859_1)
        return byteArrayOf(0x1A, (6 + ascii.size).toByte(), 0, 0, 0, sub.toByte()) + ascii
    }

    private fun nameField(name: String): ByteArray {
        val ascii = name.toByteArray(Charsets.ISO_8859_1)
        return byteArrayOf(0x0D, ascii.size.toByte()) + ascii
    }

    /**
     * Layout before the path field (selfPos = start of `1a`):
     * `[P-9: mediaType][P-8: filler][P-7: 19][P-6: 06][P-5..P-1: pad]`.
     */
    private fun prePathTrailer(mediaType: Int): ByteArray =
        byteArrayOf(mediaType.toByte(), 0x00, 0x19, 0x06, 0x00, 0x00, 0x00, 0x00, 0x00)

    private fun videoRecord(
        base: String = videoBase,
        handle: Long = 0x00041234L,
        size: Long = 12_345_678L,
    ): ByteArray {
        val mediaDir = "DCIM/DJI_001/$base"
        val out = ArrayList<Byte>()
        // head-4: size u32LE; head: handle u32LE; head+4: duration u16LE; head+6: fps/res.
        out.addAll(u32le(size).toList())
        out.addAll(u32le(handle).toList())
        val dur = 75
        out.add((dur and 0xFF).toByte()); out.add(((dur ushr 8) and 0xFF).toByte())
        out.add(60); out.add(103)
        out.addAll(byteArrayOf(0x03, 0xFF.toByte(), 0x19, 0x06).toList()) // marker @12, head=4
        out.addAll(nameField("$base.MP4").toList())
        out.addAll(byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0xAA.toByte(), 0xAA.toByte()).toList())
        out.addAll(prePathTrailer(0x03).toList())
        out.addAll(pathField(1, mediaDir).toList())
        out.addAll(pathField(2, "MISC/THM/DJI_001/$base").toList())
        return out.toByteArray()
    }

    // Explicit offsets: size u32LE @ selfPos-21, trailer 9 bytes right before the path field.
    private fun photoRecord(): ByteArray {
        val mediaDir = "DCIM/DJI_001/$photoBase"
        val out = ArrayList<Byte>()
        out.addAll(nameField("$photoBase.JPG").toList())
        val sizePos = out.size
        out.addAll(u32le(8_765_432L).toList()) // @ selfPos-21
        out.addAll(ByteArray(8).toList())     // @ selfPos-17..selfPos-10
        out.addAll(prePathTrailer(0x00).toList()) // 9 bytes ending right before the path field
        val selfPos = out.size
        assertEquals(sizePos, selfPos - 21)
        out.addAll(pathField(1, mediaDir).toList())
        out.addAll(pathField(2, "MISC/THM/DJI_001/$photoBase").toList())
        return out.toByteArray()
    }

    @Test
    fun decodes_video_and_photo_records() {
        val manifest = videoRecord() + photoRecord()
        val items = MediaManifestParser.decodeManifest(manifest, MediaStore.SD_CARD)
        assertEquals(2, items.size)

        val video = items[0]
        assertEquals("DCIM/DJI_001/$videoBase.MP4", video.path)
        assertEquals(0x00041234L, video.handle)
        assertEquals(12_345_678L, video.sizeBytes)
        assertEquals(75, video.durationSec)
        assertEquals(3, video.mediaType)
        assertTrue(video.isVideo)
        assertEquals("MISC/THM/DJI_001/$videoBase.scr", video.thumbPath)
        assertTrue(video.deletable)
        assertFalse(video.starred)
        assertEquals(MediaStore.SD_CARD, video.store)

        val photo = items[1]
        assertEquals("DCIM/DJI_001/$photoBase.JPG", photo.path)
        assertEquals(0L, photo.handle)
        assertEquals(8_765_432L, photo.sizeBytes)
        assertEquals(0, photo.mediaType)
        assertFalse(photo.isVideo)
        assertFalse(photo.deletable) // no handle -> never deletable
    }

    @Test
    fun duplicate_paths_dedupe() {
        val manifest = videoRecord() + videoRecord()
        val items = MediaManifestParser.decodeManifest(manifest, MediaStore.INTERNAL)
        assertEquals(1, items.size)
        assertEquals(MediaStore.INTERNAL, items[0].store)
    }

    @Test
    fun shared_handle_flags_both_not_deletable() {
        val otherBase = "DJI_20240101120000_0009_D"
        val manifest = videoRecord() + videoRecord(base = otherBase)
        val items = MediaManifestParser.decodeManifest(manifest, MediaStore.SD_CARD)
        assertEquals(2, items.size)
        assertTrue(items.all { it.handleShared })
        assertTrue(items.none { it.deletable })
    }

    @Test
    fun empty_manifest_decodes_to_empty() {
        assertTrue(MediaManifestParser.decodeManifest(ByteArray(0), MediaStore.SD_CARD).isEmpty())
        assertTrue(
            MediaManifestParser.decodeManifest("no records here".toByteArray(), MediaStore.SD_CARD)
                .isEmpty(),
        )
    }
}
