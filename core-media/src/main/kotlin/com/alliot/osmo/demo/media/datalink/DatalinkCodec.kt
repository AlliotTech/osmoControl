package com.alliot.osmo.demo.media.datalink

import com.alliot.osmo.demo.protocol.duml.DumlConstants
import com.alliot.osmo.demo.protocol.duml.DumlDecodedFrame
import com.alliot.osmo.demo.protocol.duml.DumlFrameCodec

/**
 * DJI Wi-Fi UDP datalink framing (port 9004), reverse-engineered ground
 * truth from osmosis `net/DumlTransport.kt`.
 *
 * ```text
 * UDP datagram:
 *   [len|0x8000:u16 LE][sessionId:u16 LE][seq:u16 LE][pktType:u8][xor:u8]
 *   [routing: 12 bytes]
 *   [body...]
 *
 * routing (12 bytes):
 *   [ackSeq:u16 LE][ownSeq:u16 LE] 00 00 00 00 [cmdCounter:u8] 01 00 00
 * ```
 *
 * - `len` is the whole datagram length with bit 15 set; the trailing xor
 *   byte is the XOR of the 7 bytes before it.
 * - For camera commands `ackSeq = (ownSeq - 8) & 0xFFFF`; `ownSeq`
 *   advances by 8 after every successful send; `cmdCounter` advances by 1
 *   per command.
 * - pktType: `0x00` handshake, `0x01` status/heartbeat, `0x04` sliding
 *   window ACK, `0x05` DUML command.
 */
object DatalinkCodec {

    const val UDP_PORT: Int = 9004
    const val TCP_POKE_PORT: Int = 7001

    const val PKT_TYPE_HANDSHAKE: Int = 0x00
    const val PKT_TYPE_STATUS: Int = 0x01
    const val PKT_TYPE_WINDOW_ACK: Int = 0x04
    const val PKT_TYPE_COMMAND: Int = 0x05

    const val UDP_HEADER_SIZE: Int = 8
    const val ROUTING_HEADER_SIZE: Int = 12
    const val WINDOW_ACK_BODY_SIZE: Int = 34

    /**
     * Handshake body osmosis sends on connect: 40 bytes, with the random
     * 8-aligned base sequence stamped little-endian at bytes 0-1.
     */
    private const val HANDSHAKE_HEX =
        "000064006400c005140000640000019001c005140000640014006400c00514000064000101040102"

    data class UdpHeader(
        val datagramLen: Int,
        val sessionId: Int,
        val seq: Int,
        val pktType: Int,
    )

    fun udpHeader(pktType: Int, sessionId: Int, seq: Int, totalLen: Int): ByteArray {
        val out = ByteArray(UDP_HEADER_SIZE)
        writeU16Le(0x8000 or (totalLen and 0x3FFF), out, 0)
        writeU16Le(sessionId, out, 2)
        writeU16Le(seq, out, 4)
        out[6] = (pktType and 0xFF).toByte()
        out[7] = xorOf(out, 0, 7).toByte()
        return out
    }

    /** Parses and validates a UDP header; null when the datagram is not a datalink frame. */
    fun parseUdpHeader(packet: ByteArray): UdpHeader? {
        if (packet.size < UDP_HEADER_SIZE) return null
        val w0 = u16Le(packet, 0)
        if (w0 and 0x8000 == 0) return null
        if ((w0 and 0x3FFF) != packet.size) return null
        if (xorOf(packet, 0, 7) != (packet[7].toInt() and 0xFF)) return null
        return UdpHeader(
            datagramLen = w0 and 0x3FFF,
            sessionId = u16Le(packet, 2),
            seq = u16Le(packet, 4),
            pktType = packet[6].toInt() and 0xFF,
        )
    }

    fun routingHeader(ownSeq: Int, cmdCounter: Int): ByteArray {
        val out = ByteArray(ROUTING_HEADER_SIZE)
        writeU16Le((ownSeq - 8) and 0xFFFF, out, 0)
        writeU16Le(ownSeq and 0xFFFF, out, 2)
        // bytes 4..7 stay zero
        out[8] = (cmdCounter and 0xFF).toByte()
        out[9] = 0x01
        // bytes 10..11 stay zero
        return out
    }

    fun buildCommandPacket(sessionId: Int, ownSeq: Int, cmdCounter: Int, duml: ByteArray): ByteArray {
        val routing = routingHeader(ownSeq, cmdCounter)
        val total = UDP_HEADER_SIZE + ROUTING_HEADER_SIZE + duml.size
        return udpHeader(PKT_TYPE_COMMAND, sessionId, ownSeq, total) + routing + duml
    }

    /**
     * pktType `0x04` window ACK: 34 bytes = three
     * `[u16 start][u16 end][u32 zero]` groups (**video**, **download**,
     * **control**) plus a trailing `[u16 0]`.
     *
     * The camera runs each as a sliding reliable window and will not keep
     * streaming a window it does not see acknowledged — the download window
     * is the one the media manifest rides, so the caller must echo the
     * peer's download cursor (latched from its `0x01` status frames) roughly
     * once per received chunk. The control group deliberately stays at the
     * base sequence (copying our fast-moving send seq there stalls the
     * peer's window).
     *
     * Reference: osmosis `DumlTransport.sendAck` and its KDoc.
     */
    fun buildWindowAck(
        sessionId: Int,
        videoCursor: Int,
        downloadCursor: Int,
        controlCursor: Int,
    ): ByteArray {
        val body = ByteArray(WINDOW_ACK_BODY_SIZE)
        fun group(offset: Int, v: Int) {
            writeU16Le(v, body, offset)
            writeU16Le(v, body, offset + 2)
            // bytes offset+4..offset+7 stay zero
        }
        group(0, videoCursor)
        group(12, downloadCursor)
        group(24, controlCursor)
        // bytes 32..33 stay zero
        val total = UDP_HEADER_SIZE + ROUTING_HEADER_SIZE + body.size
        // ACKs go out with seq 0, like in osmosis.
        return udpHeader(PKT_TYPE_WINDOW_ACK, sessionId, 0, total) +
            routingHeader(0, 0) + body
    }

    fun handshakeBody(baseSeq: Int): ByteArray {
        val body = hexToBytes(HANDSHAKE_HEX)
        writeU16Le(baseSeq and 0xFFFF, body, 0)
        return body
    }

    /** Strips UDP + routing headers from a `0x05` command datagram. Null for anything else. */
    fun extractCommandDuml(packet: ByteArray): ByteArray? {
        val header = parseUdpHeader(packet) ?: return null
        if (header.pktType != PKT_TYPE_COMMAND) return null
        if (packet.size < UDP_HEADER_SIZE + ROUTING_HEADER_SIZE) return null
        return packet.copyOfRange(UDP_HEADER_SIZE + ROUTING_HEADER_SIZE, packet.size)
    }

    /**
     * The peer's reliable-window cursors ride in its 34-byte pktType-`0x01`
     * status frames: video window end at datagram `[10:12]`, download window
     * end at `[18:20]`. (These frames carry no 12-byte routing header.)
     * Returns `(videoCursor, downloadCursor)`, or null when the datagram is
     * not such a frame.
     */
    fun parseStatusCursors(packet: ByteArray): Pair<Int, Int>? {
        val header = parseUdpHeader(packet) ?: return null
        if (header.pktType != PKT_TYPE_STATUS) return null
        if (packet.size != UDP_HEADER_SIZE + 26) return null
        return u16Le(packet, 10) to u16Le(packet, 18)
    }

    /**
     * Every CRC-valid classic (v1) DUML frame in [raw], scanned byte by
     * byte. Length is 10 bits + a 6-bit version packed LE into bytes 1-2,
     * so frames over 255 bytes are found too. Both CRCs are verified,
     * which is what makes byte-at-a-time scanning safe from false
     * positives. Mirrors osmosis `DumlTransport.scanFrames`.
     */
    fun scanV1Frames(raw: ByteArray): List<DumlDecodedFrame> {
        val out = ArrayList<DumlDecodedFrame>()
        var i = 0
        while (i + DumlConstants.MIN_FRAME_SIZE <= raw.size) {
            if ((raw[i].toInt() and 0xFF) != DumlConstants.SOF) {
                i++
                continue
            }
            val len = ((raw[i + 1].toInt() and 0xFF) or
                ((raw[i + 2].toInt() and 0xFF) shl 8)) and 0x3FF
            val ver = (raw[i + 2].toInt() and 0xFF) ushr 2
            if (ver != 1 || len < DumlConstants.MIN_FRAME_SIZE || i + len > raw.size) {
                i++
                continue
            }
            runCatching {
                out.add(DumlFrameCodec.decode(raw.copyOfRange(i, i + len)))
            }
            i++
        }
        return out
    }

    internal fun writeU16Le(value: Int, dest: ByteArray, offset: Int) {
        dest[offset] = (value and 0xFF).toByte()
        dest[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    internal fun u16Le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun xorOf(bytes: ByteArray, from: Int, until: Int): Int {
        var x = 0
        for (i in from until until) x = x xor (bytes[i].toInt() and 0xFF)
        return x
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Odd-length hex" }
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
