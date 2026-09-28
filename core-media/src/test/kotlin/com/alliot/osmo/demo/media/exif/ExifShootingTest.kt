package com.alliot.osmo.demo.media.exif

import java.io.ByteArrayOutputStream
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feeds [ExifShooting] a hand-built little-endian EXIF `APP1` laid out per the EXIF/TIFF spec (not
 * per the parser), so a wrong offset/endian/type read fails rather than round-trips.
 */
class ExifShootingTest {

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun le32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
    )

    /** tag, type, count=1, and a 4-byte value/offset field. */
    private fun entry(tag: Int, type: Int, valueOrOffset: ByteArray): ByteArray {
        require(valueOrOffset.size == 4)
        return le16(tag) + le16(type) + le32(1) + valueOrOffset
    }

    private fun sampleJpegHead(): ByteArray {
        // ---- TIFF (offsets are relative to this block's first byte) ----
        val tiff = ByteArrayOutputStream()
        tiff.write(byteArrayOf(0x49, 0x49))     // "II"
        tiff.write(le16(0x2A))                  // magic
        tiff.write(le32(8))                     // IFD0 at +8

        // IFD0: one entry, the Exif sub-IFD pointer at +26
        tiff.write(le16(1))
        tiff.write(entry(0x8769, 4, le32(26)))  // ExifIFD (LONG)
        tiff.write(le32(0))                     // no next IFD

        // Exif sub-IFD at +26: five exposure tags
        tiff.write(le16(5))
        tiff.write(entry(0x829A, 5, le32(92)))  // ExposureTime  (RATIONAL @92)
        tiff.write(entry(0x829D, 5, le32(100))) // FNumber       (RATIONAL @100)
        tiff.write(entry(0x8827, 3, le32(100))) // ISO           (SHORT inline = 100)
        tiff.write(entry(0x9204, 10, le32(108)))// ExposureBias  (SRATIONAL @108)
        tiff.write(entry(0x920A, 5, le32(116))) // FocalLength   (RATIONAL @116)
        tiff.write(le32(0))                     // no next IFD

        // rational data area
        tiff.write(le32(1)); tiff.write(le32(120))   // @92  1/120 s
        tiff.write(le32(28)); tiff.write(le32(10))   // @100 f/2.8
        tiff.write(le32(3)); tiff.write(le32(10))    // @108 +0.3 EV
        tiff.write(le32(240)); tiff.write(le32(10))  // @116 24.0 mm
        val tiffBytes = tiff.toByteArray()

        // ---- JPEG SOI + APP1(Exif) ----
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))          // SOI
        out.write(byteArrayOf(0xFF.toByte(), 0xE1.toByte()))          // APP1
        val app1Len = 2 + 6 + tiffBytes.size
        out.write(byteArrayOf(((app1Len shr 8) and 0xFF).toByte(), (app1Len and 0xFF).toByte())) // BE len
        out.write("Exif".toByteArray(Charsets.US_ASCII)); out.write(byteArrayOf(0, 0))
        out.write(tiffBytes)
        return out.toByteArray()
    }

    @Test
    fun parses_all_exposure_tags() {
        val p = ExifShooting.parse(sampleJpegHead())
        assertNotNull(p); p!!
        assertEquals(100, p.isoSensitivity!!.toInt())
        assertEquals(2.8, p.fNumber!!, 1e-6)
        assertEquals(24.0, p.focalLengthMm!!, 1e-6)
        assertEquals(0.3, p.exposureBiasEv!!, 1e-6)
        assertTrue(abs(p.exposureTimeSec!! - 1.0 / 120.0) < 1e-9)
    }

    @Test
    fun non_jpeg_is_null() {
        assertNull(ExifShooting.parse(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun truncated_head_does_not_throw() {
        val full = sampleJpegHead()
        // Any prefix length must return null or a partial, never crash.
        for (len in 0..full.size) {
            ExifShooting.parse(full.copyOf(len))
        }
    }
}
