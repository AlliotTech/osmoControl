package com.alliot.osmo.demo.media.repo

import com.alliot.osmo.demo.media.model.MediaItem
import com.alliot.osmo.demo.media.model.MediaStore

/**
 * Structural decoder for DJI's CompositePack media manifest — the record
 * format the delete path `0x00/0x28` trusts. Ported from osmosis
 * `CameraSession.decodeComposite` / `resolveRecord`, trimmed to the fields
 * osmoControl needs.
 *
 * Every field is length-delimited, so decoding is tag → length → value,
 * never a filename-pattern grep: custom Folder/File name prefixes decode
 * identically to stock ones.
 *
 * Anchor — the media-path field, the most self-identifying thing in a
 * record:
 * ```text
 * 1a [total:u8] 00 00 00 01  <ascii "DCIM/…", total-6 bytes>   media path (no extension)
 * 1a [total:u8] 00 00 00 02  <ascii "MISC/…">                  thumbnail path
 * 0d [len:u8]                <ascii "<base>.<ext>">            filename — read only for its ext
 * ```
 * The delete handle (`u32-LE` at the marker head) and the video byte size
 * (`u32-LE` at head-4) hang off a marker `[03|00] [ff|fe] 19 06` at
 * `head+8`, present on video records; it is read opportunistically and
 * bounded at the record's own path field so a photo is never handed its
 * neighbour's handle. Photos read their size off the fixed `19 06` tag
 * seven bytes before their path field.
 *
 * When the camera concatenates two per-store lists (card in: SD first,
 * then internal), the leading `[u32-LE count]` names the first list's
 * record count; anything past it belongs to the second list.
 */
object MediaManifestParser {

    private val VIDEO_EXTS = setOf("MP4", "MOV")

    /** Favourite flag as the Action family writes it: `00`/`01` after a fixed 12-byte signature. */
    private val STAR_SIG = byteArrayOf(
        0x1b, 0x0a, 0x00, 0x00, 0x00, 0x02, 0x02, 0x01, 0x14, 0x02, 0x15, 0x03,
    )

    fun decodeManifest(bytes: ByteArray, store: MediaStore): List<MediaItem> {
        data class Media(val pos: Int, val end: Int, val path: String)
        val medias = ArrayList<Media>()
        var i = 0
        while (i < bytes.size) {
            val f = readPathField(bytes, i, sub = 1, prefix = "DCIM/")
            if (f != null) {
                medias.add(Media(i, f.end, f.value))
                i = f.end
            } else {
                i++
            }
        }
        if (medias.isEmpty()) return emptyList()

        val boundary = listBoundary(bytes, medias.size)
        val byPath = LinkedHashMap<String, MediaItem>() // the list can page-repeat records
        for (k in medias.indices) {
            val m = medias[k]
            val lo = if (k > 0) medias[k - 1].end else 0
            val hi = if (k + 1 < medias.size) medias[k + 1].pos else bytes.size
            byPath.putIfAbsent(m.path, resolveRecord(bytes, m.path, lo, hi, m.pos, store))
        }
        return flagHandleCollisions(byPath.values.toList())
    }

    private class TlvField(val value: String, val end: Int)

    private fun readPathField(bytes: ByteArray, i: Int, sub: Int, prefix: String): TlvField? {
        if (i + 6 > bytes.size || bytes[i] != 0x1A.toByte()) return null
        if (bytes[i + 2] != 0.toByte() || bytes[i + 3] != 0.toByte() || bytes[i + 4] != 0.toByte()) {
            return null
        }
        if ((bytes[i + 5].toInt() and 0xFF) != sub) return null
        val slen = (bytes[i + 1].toInt() and 0xFF) - 6
        if (slen < prefix.length || i + 6 + slen > bytes.size) return null
        val s = String(bytes, i + 6, slen, Charsets.ISO_8859_1)
        return if (s.startsWith(prefix) && s.all { it.code in 0x20..0x7E }) {
            TlvField(s, i + 6 + slen)
        } else {
            null
        }
    }

    private fun listBoundary(bytes: ByteArray, records: Int): Int {
        if (bytes.size < 4) return -1
        val declared = u32le(bytes, 0).toInt()
        return if (declared in 1 until records) declared else -1
    }

    private fun resolveRecord(
        bytes: ByteArray,
        mediaDir: String,
        lo: Int,
        hi: Int,
        selfPos: Int,
        store: MediaStore,
    ): MediaItem {
        val base = mediaDir.substringAfterLast('/')

        var thumb: String? = null
        var t = lo
        while (t < hi) {
            val f = readPathField(bytes, t, sub = 2, prefix = "MISC/")
            if (f != null && f.value.endsWith(base)) {
                thumb = f.value
                break
            }
            t++
        }

        var ext = ""
        var proxyExt: String? = null
        var n = lo
        while (n < hi - 2) {
            if (bytes[n] == 0x0D.toByte()) {
                val len = bytes[n + 1].toInt() and 0xFF
                if (len > base.length && n + 2 + len <= bytes.size) {
                    val v = String(bytes, n + 2, len, Charsets.ISO_8859_1)
                    if (v.length > base.length + 1 && v.startsWith(base) && v[base.length] == '.') {
                        val e = v.substring(base.length + 1).uppercase()
                        if (e in VIDEO_EXTS || e in setOf("JPG", "JPEG", "DNG", "HEIC")) ext = e
                        else if (e in setOf("LRF", "LRV", "XRF")) proxyExt = e
                    }
                }
            }
            n++
        }

        // Delete handle: scan for [03|00][ff|fe] 19 06, bounded at this
        // record's OWN path field so a photo never borrows the next
        // record's marker.
        var head = -1
        var m = lo
        val markerEnd = if (selfPos in (lo + 1)..hi) selfPos else hi
        while (m < markerEnd - 4) {
            val kind = bytes[m].toInt() and 0xFF
            val star = bytes[m + 1].toInt() and 0xFF
            if ((kind == 0x03 || kind == 0x00) && (star == 0xFF || star == 0xFE) &&
                bytes[m + 2] == 0x19.toByte() && bytes[m + 3] == 0x06.toByte() && m >= 8
            ) {
                head = m - 8
                break
            }
            m++
        }
        val hasMarker = head >= 0
        val isVideo = ext in VIDEO_EXTS

        // Photo size hangs off the fixed `19 06` tag seven bytes before
        // the record's own path field: size @ tag-14.
        var photoSize = 0L
        val fixed = selfPos - 7
        if (!isVideo && selfPos >= 21 && fixed + 1 < bytes.size &&
            bytes[fixed] == 0x19.toByte() && bytes[fixed + 1] == 0x06.toByte()
        ) {
            photoSize = u32le(bytes, fixed - 14)
        }

        val path = if (ext.isNotEmpty()) "$mediaDir.$ext" else mediaDir
        val thumbPath = (thumb ?: mediaDir.replaceFirst("DCIM/", "MISC/THM/")) + ".scr"
        val handle = if (hasMarker) u32le(bytes, head) else 0L
        val size = if (isVideo && hasMarker && head >= 4) u32le(bytes, head - 4) else photoSize
        val durationSec = if (isVideo && hasMarker && head + 6 <= bytes.size) {
            (bytes[head + 4].toInt() and 0xFF) or ((bytes[head + 5].toInt() and 0xFF) shl 8)
        } else {
            0
        }
        val mediaType = if (selfPos >= 9 && selfPos <= bytes.size &&
            bytes[selfPos - 7] == 0x19.toByte() && bytes[selfPos - 6] == 0x06.toByte()
        ) {
            bytes[selfPos - 9].toInt() and 0xFF
        } else {
            -1
        }
        val starred = starFlagBySignature(bytes, selfPos, hi) ?: starFlag(bytes, lo, hi)

        return MediaItem(
            path = path,
            thumbPath = thumbPath,
            store = store,
            handle = handle,
            sizeBytes = size,
            durationSec = durationSec,
            mediaType = mediaType,
            isVideo = isVideo,
            starred = starred,
            proxyPath = proxyExt?.let { "$mediaDir.$it" },
        )
    }

    /** Favourite flag: the byte after the fixed signature, strictly 0/1. */
    private fun starFlagBySignature(bytes: ByteArray, selfPos: Int, hi: Int): Boolean? {
        val end = minOf(hi, bytes.size - STAR_SIG.size - 1)
        var q = selfPos
        while (q <= end) {
            if (bytes[q] == STAR_SIG[0] && bytes.copyOfRange(q, q + STAR_SIG.size).contentEquals(STAR_SIG)) {
                return when ((bytes[q + STAR_SIG.size].toInt() and 0xFF)) {
                    1 -> true
                    0 -> false
                    else -> null
                }
            }
            q++
        }
        return null
    }

    /** Fallback favourite read: the byte 9 past the record's `[ff|fe] 19 06` marker, strictly 1. */
    private fun starFlag(bytes: ByteArray, lo: Int, hi: Int): Boolean {
        var q = lo
        while (q < hi - 9) {
            if ((bytes[q] == 0xFF.toByte() || bytes[q] == 0xFE.toByte()) &&
                bytes[q + 1] == 0x19.toByte() && bytes[q + 2] == 0x06.toByte()
            ) {
                return (bytes[q + 9].toInt() and 0xFF) == 1
            }
            q++
        }
        return false
    }

    /**
     * Two records claiming one handle: neither may be deleted (a wrong
     * handle deletes the wrong file — recoverable loss of delete beats
     * unrecoverable loss of media).
     */
    private fun flagHandleCollisions(files: List<MediaItem>): List<MediaItem> {
        val dupes = files.filter { it.handle != 0L }.groupBy { it.handle }
            .filter { it.value.size > 1 }.keys
        if (dupes.isEmpty()) return files
        return files.map { if (it.handle in dupes) it.copy(handleShared = true) else it }
    }

    private fun u32le(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
}
