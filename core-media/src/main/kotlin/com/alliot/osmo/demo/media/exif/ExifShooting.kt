package com.alliot.osmo.demo.media.exif

/**
 * What the camera was set to when a still was shot, read from its EXIF `APP1` block. Every field is
 * nullable: a body may write some and not others, and a truncated head yields whatever fit.
 */
data class ShootingParams(
    val isoSensitivity: Int? = null,
    val exposureTimeSec: Double? = null,
    val fNumber: Double? = null,
    val focalLengthMm: Double? = null,
    val exposureBiasEv: Double? = null,
) {
    val isEmpty: Boolean
        get() = isoSensitivity == null && exposureTimeSec == null &&
            fNumber == null && focalLengthMm == null && exposureBiasEv == null
}

/**
 * Minimal TIFF/EXIF reader for the exposure tags Mimo's playback screen shows. Pure JVM (no Android
 * `ExifInterface`, which this module cannot depend on), and scoped to the `APP1` segment exactly like
 * [EmbeddedJpeg] so it never scans entropy-coded image data.
 *
 * Reads only IFD0's Exif sub-IFD (tag `0x8769`), where ISO / exposure / aperture / focal / EV live.
 * Reference: EXIF 2.3, and osmosis ROADMAP #16 ("the same APP1 block carries ISO, exposure time,
 * aperture and focal length").
 */
object ExifShooting {

    private const val TAG_EXIF_IFD = 0x8769
    private const val TAG_EXPOSURE_TIME = 0x829A
    private const val TAG_F_NUMBER = 0x829D
    private const val TAG_ISO = 0x8827
    private const val TAG_EXPOSURE_BIAS = 0x9204
    private const val TAG_FOCAL_LENGTH = 0x920A

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16be(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)

    /** Shooting parameters from [head] (the first bytes of a JPEG), or null when there's no EXIF. */
    fun parse(head: ByteArray): ShootingParams? {
        if (head.size < 4 || u8(head, 0) != 0xFF || u8(head, 1) != 0xD8) return null
        var i = 2
        while (i + 4 <= head.size) {
            if (u8(head, i) != 0xFF) { i++; continue }
            when (val marker = u8(head, i + 1)) {
                0xD8, 0x01, in 0xD0..0xD7 -> i += 2
                0xDA, 0xD9 -> return null
                else -> {
                    val len = u16be(head, i + 2)
                    if (len < 2) return null
                    if (marker == 0xE1) {
                        val app1 = i + 4
                        if (app1 + 6 <= head.size && isExifHeader(head, app1)) {
                            val end = minOf(i + 2 + len, head.size)
                            return Tiff(head, app1 + 6, end).readShooting()
                        }
                    }
                    i += 2 + len
                }
            }
        }
        return null
    }

    /** `Exif\0\0` */
    private fun isExifHeader(b: ByteArray, o: Int) =
        u8(b, o) == 0x45 && u8(b, o + 1) == 0x78 && u8(b, o + 2) == 0x69 &&
            u8(b, o + 3) == 0x66 && u8(b, o + 4) == 0x00 && u8(b, o + 5) == 0x00

    /**
     * A TIFF block starting at [base] (the `II`/`MM` byte), bounded by [end]. All entry offsets in a
     * TIFF are relative to [base].
     */
    private class Tiff(val b: ByteArray, val base: Int, val end: Int) {
        private var little: Boolean = false

        private fun u8(i: Int) = b[i].toInt() and 0xFF
        private fun u16(o: Int): Int =
            if (little) u8(o) or (u8(o + 1) shl 8) else (u8(o) shl 8) or u8(o + 1)

        private fun u32(o: Int): Long =
            if (little) {
                (u8(o).toLong()) or (u8(o + 1).toLong() shl 8) or
                    (u8(o + 2).toLong() shl 16) or (u8(o + 3).toLong() shl 24)
            } else {
                (u8(o).toLong() shl 24) or (u8(o + 1).toLong() shl 16) or
                    (u8(o + 2).toLong() shl 8) or u8(o + 3).toLong()
            }

        private fun s32(o: Int): Int = u32(o).toInt()

        fun readShooting(): ShootingParams? {
            if (base + 8 > end) return null
            little = when {
                u8(base) == 0x49 && u8(base + 1) == 0x49 -> true   // "II" little-endian
                u8(base) == 0x4D && u8(base + 1) == 0x4D -> false  // "MM" big-endian
                else -> return null
            }
            if (u16(base + 2) != 0x002A) return null               // TIFF magic
            val ifd0Rel = u32(base + 4)
            if (ifd0Rel <= 0 || base + ifd0Rel + 2 > end) return null
            val ifd0 = base + ifd0Rel.toInt()
            val exifPtr = tag(ifd0, TAG_EXIF_IFD)?.let { base + it.valueAsLong().toInt() }
                ?: return null
            if (exifPtr + 2 > end || exifPtr < base) return null

            val exposureTime = rational(exifPtr, TAG_EXPOSURE_TIME)
            val fNumber = rational(exifPtr, TAG_F_NUMBER)
            val focal = rational(exifPtr, TAG_FOCAL_LENGTH)
            val bias = srational(exifPtr, TAG_EXPOSURE_BIAS)
            val iso = tag(exifPtr, TAG_ISO)?.valueAsLong()?.toInt()

            val params = ShootingParams(
                isoSensitivity = iso?.takeIf { it > 0 },
                exposureTimeSec = exposureTime?.takeIf { it > 0.0 },
                fNumber = fNumber?.takeIf { it > 0.0 },
                focalLengthMm = focal?.takeIf { it > 0.0 },
                exposureBiasEv = bias,
            )
            return if (params.isEmpty) null else params
        }

        /** One 12-byte IFD entry. */
        private inner class Entry(val entryOff: Int) {
            val type: Int get() = u16(entryOff + 2)
            val count: Long get() = u32(entryOff + 4)
            val valueOff: Int get() = entryOff + 8

            /** SHORT/LONG scalar (inline in the value field). */
            fun valueAsLong(): Long = when (type) {
                3 -> u16(valueOff).toLong()          // SHORT
                4 -> u32(valueOff)                    // LONG
                else -> u16(valueOff).toLong()
            }
        }

        /** Find a tag in the IFD at [ifd], or null. Offsets/bounds are validated. */
        private fun tag(ifd: Int, wanted: Int): Entry? {
            if (ifd + 2 > end) return null
            val count = u16(ifd)
            var e = ifd + 2
            repeat(count) {
                if (e + 12 > end) return null
                if (u16(e) == wanted) return Entry(e)
                e += 12
            }
            return null
        }

        /** RATIONAL (two u32) pointed at by [tag]'s value offset (RATIONAL never fits inline). */
        private fun rational(ifd: Int, wanted: Int): Double? {
            val entry = tag(ifd, wanted) ?: return null
            if (entry.type != 5) return null
            val at = base + u32(entry.valueOff).toInt()
            if (at + 8 > end || at < base) return null
            val num = u32(at)
            val den = u32(at + 4)
            return if (den == 0L) null else num.toDouble() / den.toDouble()
        }

        private fun srational(ifd: Int, wanted: Int): Double? {
            val entry = tag(ifd, wanted) ?: return null
            if (entry.type != 10) return null
            val at = base + u32(entry.valueOff).toInt()
            if (at + 8 > end || at < base) return null
            val num = s32(at)
            val den = s32(at + 4)
            return if (den == 0) null else num.toDouble() / den.toDouble()
        }
    }
}
