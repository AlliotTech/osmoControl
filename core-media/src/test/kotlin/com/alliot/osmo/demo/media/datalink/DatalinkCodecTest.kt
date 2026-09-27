package com.alliot.osmo.demo.media.datalink

import com.alliot.osmo.demo.protocol.duml.DumlFrame
import com.alliot.osmo.demo.protocol.duml.DumlFrameCodec
import com.alliot.osmo.demo.protocol.duml.DumlStringCodec
import com.alliot.osmo.demo.protocol.duml.DumlV4Codec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DatalinkCodecTest {

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun udp_header_golden_vector() {
        // pktType=0x05, sessionId=0x1234, seq=0x0008, totalLen=20.
        // w0 = 0x8014; xor = 14^80^34^12^08^00^05 = BF.
        val header = DatalinkCodec.udpHeader(
            pktType = 0x05,
            sessionId = 0x1234,
            seq = 0x0008,
            totalLen = 20,
        )
        assertArrayEquals(hex("14803412" + "0800" + "05bf"), header)
    }

    @Test
    fun parse_udp_header_round_trip_and_rejects_corruption() {
        val header = DatalinkCodec.udpHeader(0x05, 0xABCD, 0x1234, 64)
        val parsed = DatalinkCodec.parseUdpHeader(header + ByteArray(56))
        assertEquals(64, parsed?.datagramLen)
        assertEquals(0xABCD, parsed?.sessionId)
        assertEquals(0x1234, parsed?.seq)
        assertEquals(0x05, parsed?.pktType)

        val badXor = (header + ByteArray(56)).copyOf().also { it[7] = (it[7] + 1).toByte() }
        assertNull(DatalinkCodec.parseUdpHeader(badXor))
        val badLen = (header + ByteArray(55)).copyOf()
        assertNull(DatalinkCodec.parseUdpHeader(badLen))
    }

    @Test
    fun routing_header_golden_vector() {
        // ownSeq=0x1008 -> ackSeq=0x1000; counter=3.
        val routing = DatalinkCodec.routingHeader(ownSeq = 0x1008, cmdCounter = 3)
        assertArrayEquals(hex("001008100000000003010000"), routing)
    }

    @Test
    fun handshake_body_stamps_base_seq_le() {
        val body = DatalinkCodec.handshakeBody(0xB887)
        assertEquals(40, body.size)
        assertEquals(0x87, body[0].toInt() and 0xFF)
        assertEquals(0xB8, body[1].toInt() and 0xFF)
        val other = DatalinkCodec.handshakeBody(0x1000)
        // Only the first two bytes differ.
        assertArrayEquals(body.copyOfRange(2, 40), other.copyOfRange(2, 40))
    }

    @Test
    fun window_ack_layout() {
        val ack = DatalinkCodec.buildWindowAck(
            sessionId = 0x1234,
            videoCursor = 0x1111,
            downloadCursor = 0x2222,
            controlCursor = 0x3333,
        )
        assertEquals(8 + 12 + 34, ack.size)
        val header = DatalinkCodec.parseUdpHeader(ack)!!
        assertEquals(DatalinkCodec.PKT_TYPE_WINDOW_ACK, header.pktType)
        // Body groups at 20, 32, 44: [u16 start][u16 end][u32 zero].
        assertEquals("11111111" + "00000000", ack.copyOfRange(20, 28).toHex())
        assertEquals("22222222" + "00000000", ack.copyOfRange(32, 40).toHex())
        assertEquals("33333333" + "00000000", ack.copyOfRange(44, 52).toHex())
        assertEquals("0000", ack.copyOfRange(52, 54).toHex())
    }

    @Test
    fun command_packet_round_trip_extracts_duml() {
        val duml = DumlV4Codec.encode(
            DumlV4Codec.V4Frame(0x0102, 7, 2, 0x00, 0x26, byteArrayOf(0x4A)),
        )
        val packet = DatalinkCodec.buildCommandPacket(0x1234, 0x1008, 3, duml)
        val extracted = DatalinkCodec.extractCommandDuml(packet)
        assertArrayEquals(duml, extracted)
        // Routing header inside the packet matches the golden layout.
        assertArrayEquals(
            hex("001008100000000003010000"),
            packet.copyOfRange(8, 20),
        )
    }

    @Test
    fun parse_status_cursors_reads_absolute_offsets() {
        val body = ByteArray(26)
        body[2] = 0x11; body[3] = 0x11   // video cursor @ datagram[10:12]
        body[10] = 0x22; body[11] = 0x22 // download cursor @ datagram[18:20]
        val header = DatalinkCodec.udpHeader(DatalinkCodec.PKT_TYPE_STATUS, 0x1234, 0x0001, 34)
        val (video, download) = DatalinkCodec.parseStatusCursors(header + body)!!
        assertEquals(0x1111, video)
        assertEquals(0x2222, download)
    }

    @Test
    fun scan_v1_frames_finds_frame_in_noise_and_skips_corrupt() {
        val frame = DumlFrameCodec.encode(
            DumlFrame.request(
                target = 0x0102,
                messageId = 9,
                cmdSet = 0x00,
                cmdId = 0x27,
                payload = ByteArray(300) { it.toByte() }, // >255 exercises the 10-bit length
            ),
        )
        val corrupt = frame.copyOf().also { it[it.size - 3] = (it[it.size - 3] + 1).toByte() }
        val raw = byteArrayOf(0x00, 0x55.toByte()) + corrupt + byteArrayOf(0x7F) + frame
        val found = DatalinkCodec.scanV1Frames(raw)
        assertEquals(1, found.size)
        assertEquals(0x00, found[0].cmdSet)
        assertEquals(0x27, found[0].cmdId)
        assertEquals(300, found[0].payload.size)
        assertTrue(DatalinkCodec.scanV1Frames(byteArrayOf(1, 2, 3)).isEmpty())
    }

    @Test
    fun tcp_poke_frame_matches_set_pairing_pin_shape() {
        val frame = TcpPoke.setPairingPinFrame("test-identifier")
        val decoded = DumlV4Codec.decode(frame)
        assertEquals(0x0702, decoded.target)
        assertEquals(0x8092, decoded.id)
        assertEquals(0x07, decoded.cmdSet)
        assertEquals(0x45, decoded.cmdId)
        assertArrayEquals(
            DumlStringCodec.pack("test-identifier") + DumlStringCodec.pack("osmo"),
            decoded.payload,
        )
    }
}
