package com.alliot.osmo.demo.protocol.duml

import com.alliot.osmo.demo.protocol.util.ByteOrder

/**
 * Media list / delete helpers for the DJI DUML media protocol.
 *
 * Reference: osmosis MEDIA_PROTOCOL.md §1 (media list), "Waking a sleeping
 * camera" delete section, and the datalink transport notes.
 *
 * The camera answers a 0x00/0x26 list query with 0x00/0x27 chunks. Each chunk
 * carries a 10-byte sub-header; only data chunks (subtype 0x01) are
 * concatenated, in arrival order, into the composite manifest. Chunk routing
 * is done on the DUML command (0x00/0x27), never on a 4A 01 byte prefix.
 */
object MediaListCodec {
    const val SD_CARD_CURSOR_NEWEST: Long = 0x0000_0001L
    const val INTERNAL_STORAGE_CURSOR_NEWEST: Long = 0x4000_0001L

    /** Records requested per page (template byte 14 = 0x2d); a full page means more may follow. */
    const val PAGE_SIZE: Int = 45

    private const val COUNTER_OFFSET = 4
    private const val CURSOR_OFFSET = 10
    private const val SUB_HEADER_SIZE = 10
    private const val SUB_HEADER_MAGIC: Int = 0x4A
    private const val SUBTYPE_START: Int = 0x04
    private const val SUBTYPE_DATA: Int = 0x01
    private const val SUBTYPE_END: Int = 0x03

    private val LIST_QUERY_TEMPLATE: ByteArray = hexToBytes(
        "4a002a10010000000000010000002d000d0100ffffffffffffffff000100000000000000000000000000",
    )

    fun buildListQueryPayload(counter: Int, cursor: Long): ByteArray {
        val payload = LIST_QUERY_TEMPLATE.copyOf()
        payload[COUNTER_OFFSET] = (counter and 0xFF).toByte()
        ByteOrder.writeI32(cursor.toInt(), payload, CURSOR_OFFSET)
        return payload
    }

    fun buildListQueryFrame(counter: Int, cursor: Long, messageId: Int): DumlFrame {
        return DumlFrame.request(
            target = DumlTargets.APP_TO_CAMERA,
            messageId = messageId,
            cmdSet = DumlCmdSet.GENERAL,
            cmdId = DumlGeneralCmd.MEDIA_LIST_QUERY,
            payload = buildListQueryPayload(counter, cursor),
        )
    }

    /**
     * Delete payload (`0x00/0x28`), reverse-engineered from a Mimo<->Nano
     * pcap (osmosis `CameraSession.deletePayload`):
     * ```text
     * [count:u8] [handle:u32 LE]... [counter:u32 LE] 00 [count:u32 LE] 01 01 00 00
     * ```
     * The camera answers with a status word (`u16-LE` at the reply payload
     * start, `0x0000` = OK).
     */
    fun buildDeletePayload(handles: List<Long>, counter: Int): ByteArray {
        require(handles.isNotEmpty()) { "Delete needs at least one media handle." }
        val out = java.io.ByteArrayOutputStream()
        out.write(handles.size and 0xFF)
        for (handle in handles) {
            val value = (handle and 0xFFFF_FFFF).toInt()
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
            out.write((value ushr 16) and 0xFF)
            out.write((value ushr 24) and 0xFF)
        }
        writeU32Le(out, counter)
        out.write(0x00)
        writeU32Le(out, handles.size)
        out.write(byteArrayOf(0x01, 0x01, 0x00, 0x00))
        return out.toByteArray()
    }

    private fun writeU32Le(out: java.io.ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }

    fun buildDeleteFrame(handles: List<Long>, counter: Int, messageId: Int): DumlFrame {
        return DumlFrame.request(
            target = DumlTargets.APP_TO_CAMERA,
            messageId = messageId,
            cmdSet = DumlCmdSet.GENERAL,
            cmdId = DumlGeneralCmd.MEDIA_DELETE,
            payload = buildDeletePayload(handles, counter),
        )
    }

    /**
     * The camera answers a too-early list query (right after entering
     * playback) with a bare 0xD8 payload and an empty stream. The caller
     * should wait and re-query instead of treating it as an empty store.
     */
    fun isTooEarlyResponse(frame: DumlDecodedFrame): Boolean {
        return frame.cmdSet == DumlCmdSet.GENERAL &&
            frame.cmdId == DumlGeneralCmd.MEDIA_LIST_QUERY &&
            frame.payload.size == 1 &&
            (frame.payload[0].toInt() and 0xFF) == 0xD8
    }

    sealed interface ChunkEvent {
        data object PageStarted : ChunkEvent
        data class DataAppended(val totalBytes: Int) : ChunkEvent
        data class PageComplete(val manifest: ByteArray) : ChunkEvent
    }

    class ChunkAssembler {
        private val buffer = mutableListOf<Byte>()
        private var activeCounter: Int? = null

        /**
         * Feeds one decoded DUML frame. Returns null for frames that are not
         * part of a media list response.
         */
        fun accept(frame: DumlDecodedFrame): ChunkEvent? {
            if (frame.cmdSet != DumlCmdSet.GENERAL || frame.cmdId != DumlGeneralCmd.MEDIA_LIST_RESPONSE) {
                return null
            }
            val payload = frame.payload
            if (payload.size < SUB_HEADER_SIZE || (payload[0].toInt() and 0xFF) != SUB_HEADER_MAGIC) {
                return null
            }
            val subtype = payload[1].toInt() and 0xFF
            val counter = payload[4].toInt() and 0xFF
            return when (subtype) {
                SUBTYPE_START -> {
                    buffer.clear()
                    activeCounter = counter
                    ChunkEvent.PageStarted
                }
                SUBTYPE_DATA -> {
                    if (activeCounter != null && activeCounter != counter) {
                        return null
                    }
                    activeCounter = counter
                    payload.copyOfRange(SUB_HEADER_SIZE, payload.size).forEach(buffer::add)
                    ChunkEvent.DataAppended(buffer.size)
                }
                SUBTYPE_END -> {
                    if (activeCounter != null && activeCounter != counter) {
                        return null
                    }
                    val manifest = buffer.toByteArray()
                    buffer.clear()
                    activeCounter = null
                    ChunkEvent.PageComplete(manifest)
                }
                else -> null
            }
        }

        fun reset() {
            buffer.clear()
            activeCounter = null
        }

        /**
         * Takes whatever manifest bytes have been buffered so far (or null
         * when nothing was). Lets the caller close a page on a quiet
         * period when the camera never sends the `4A 03` end frame.
         */
        fun takePending(): ByteArray? {
            if (buffer.isEmpty()) return null
            val manifest = buffer.toByteArray()
            reset()
            return manifest
        }
    }

    private fun hexToBytes(value: String): ByteArray {
        require(value.length % 2 == 0) { "Hex string must have even length." }
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
