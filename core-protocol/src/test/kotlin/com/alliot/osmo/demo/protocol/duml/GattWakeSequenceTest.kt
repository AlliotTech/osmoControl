package com.alliot.osmo.demo.protocol.duml

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GattWakeSequenceTest {
    @Test
    fun session_open_frame_matches_osmosis_vector() {
        assertArrayEquals(
            hex("550f04a202f01bcb40002b04009ab9"),
            DumlFrameCodec.encode(GattWakeSequence.buildSessionOpenFrame(messageId = 0x1bcb)),
        )
    }

    @Test
    fun session_keepalive_frame_matches_osmosis_vector() {
        assertArrayEquals(
            hex("550f04a202f01bcb40002b0101abd6"),
            DumlFrameCodec.encode(GattWakeSequence.buildSessionKeepaliveFrame(messageId = 0x1bcb)),
        )
    }

    @Test
    fun wake_frames_address_session_endpoints() {
        val open = GattWakeSequence.buildSessionOpenFrame(messageId = 1)
        assertEquals(DumlTargets.APP_TO_SESSION, open.target)
        assertEquals(0xF0, open.target ushr 8 and 0xFF)

        val wake = GattWakeSequence.buildWakeCameraFrame(messageId = 2)
        assertEquals(DumlTargets.APP_TO_WAKE, wake.target)
        assertEquals(0x1C, wake.target ushr 8 and 0xFF)
        assertEquals(DumlCmdSet.WAKE, wake.cmdSet)
        assertEquals(DumlWakeCmd.WAKE_CAMERA, wake.cmdId)
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x00, 0x00), wake.payload)
    }

    @Test
    fun pairing_pin_frame_packs_identifier_and_token() {
        val frame = GattWakeSequence.buildSetPairingPinFrame(
            messageId = 3,
            identifier = "0123456789abcdef0123456789abcdef",
            token = "osmo",
        )
        assertEquals(DumlTargets.APP_TO_WIFI, frame.target)
        assertEquals(DumlCmdSet.WIFI, frame.cmdSet)
        assertEquals(DumlWifiCmd.SET_PAIRING_PIN, frame.cmdId)
        val expected = DumlStringCodec.pack("0123456789abcdef0123456789abcdef") +
            DumlStringCodec.pack("osmo")
        assertArrayEquals(expected, frame.payload)
    }

    @Test
    fun wake_reply_detection() {
        val reply = DumlFrame(
            target = 0x021C,
            messageId = 7,
            flags = DumlFlags.RESPONSE,
            cmdSet = DumlCmdSet.WAKE,
            cmdId = DumlWakeCmd.WAKE_CAMERA,
            payload = byteArrayOf(0x01, 0x00, 0x00, 0x00),
        )
        assertTrue(GattWakeSequence.isWakeReply(DumlFrameCodec.decode(DumlFrameCodec.encode(reply))))

        val wrongPayload = reply.copy(payload = byteArrayOf(0x00, 0x00, 0x00, 0x00))
        assertFalse(GattWakeSequence.isWakeReply(DumlFrameCodec.decode(DumlFrameCodec.encode(wrongPayload))))

        val wrongCommand = reply.copy(cmdSet = DumlCmdSet.GENERAL, cmdId = DumlGeneralCmd.SESSION_WAKE_KEEPALIVE)
        assertFalse(GattWakeSequence.isWakeReply(DumlFrameCodec.decode(DumlFrameCodec.encode(wrongCommand))))
    }

    @Test
    fun pairing_status_detection() {
        val paired = DumlFrame(
            target = 0x0207,
            messageId = 9,
            flags = DumlFlags.RESPONSE,
            cmdSet = DumlCmdSet.WIFI,
            cmdId = DumlWifiCmd.SET_PAIRING_PIN,
            payload = byteArrayOf(0x00, 0x01),
        )
        val decoded = DumlFrameCodec.decode(DumlFrameCodec.encode(paired))
        assertTrue(GattWakeSequence.isPairingStatusFrame(decoded))
        assertEquals(GattWakeSequence.PAIR_STATUS_ALREADY_PAIRED, GattWakeSequence.pairingStatus(decoded))

        val approvalNeeded = paired.copy(payload = byteArrayOf(0x00, 0x02))
        val decodedApproval = DumlFrameCodec.decode(DumlFrameCodec.encode(approvalNeeded))
        assertTrue(GattWakeSequence.isPairingStatusFrame(decodedApproval))
        assertEquals(GattWakeSequence.PAIR_STATUS_APPROVAL_REQUIRED, GattWakeSequence.pairingStatus(decodedApproval))

        // A 0x07/0x45 *request* is not a status reply.
        val request = paired.copy(flags = DumlFlags.REQUEST)
        assertFalse(GattWakeSequence.isPairingStatusFrame(DumlFrameCodec.decode(DumlFrameCodec.encode(request))))
    }

    @Test
    fun pairing_approval_detection() {
        val approvalRequest = DumlFrame(
            target = 0x02F0,
            messageId = 11,
            flags = DumlFlags.REQUEST,
            cmdSet = DumlCmdSet.WIFI,
            cmdId = DumlWifiCmd.PAIRING_APPROVED,
            payload = byteArrayOf(0x01),
        )
        assertTrue(GattWakeSequence.isPairingApprovalFrame(DumlFrameCodec.decode(DumlFrameCodec.encode(approvalRequest))))
    }

    @Test
    fun ack_frame_swaps_target_and_echoes_id() {
        val request = DumlFrame(
            target = 0x02F0, // camera(0xF0) -> app(0x02)
            messageId = 0x1234,
            flags = DumlFlags.REQUEST,
            cmdSet = DumlCmdSet.WIFI,
            cmdId = DumlWifiCmd.PAIRING_APPROVED,
            payload = byteArrayOf(0x01),
        )
        val ack = GattWakeSequence.buildAckFrame(DumlFrameCodec.decode(DumlFrameCodec.encode(request)))
        assertEquals(0xF002, ack.target) // app -> camera session endpoint
        assertEquals(0x1234, ack.messageId)
        assertEquals(DumlFlags.RESPONSE, ack.flags)
        assertEquals(DumlCmdSet.WIFI, ack.cmdSet)
        assertEquals(DumlWifiCmd.PAIRING_APPROVED, ack.cmdId)
        assertArrayEquals(byteArrayOf(0x00), ack.payload)
    }

    @Test
    fun ack_frame_answers_device_info_request() {
        val request = DumlFrame(
            target = 0x02F0,
            messageId = 0x2222,
            flags = DumlFlags.REQUEST,
            cmdSet = DumlCmdSet.GENERAL,
            cmdId = 0x81,
            payload = ByteArray(0),
        )
        val decoded = DumlFrameCodec.decode(DumlFrameCodec.encode(request))
        assertTrue(GattWakeSequence.isDeviceInfoRequest(decoded))
        val ack = GattWakeSequence.buildAckFrame(decoded)
        assertEquals(DumlFlags.RESPONSE, ack.flags)
        val info = GattWakeSequence.appDeviceInfoPayload()
        assertEquals(62, info.size)
        assertArrayEquals(byteArrayOf(0x00, 0x41, 0x50, 0x50), info.copyOfRange(0, 4))
        assertArrayEquals(info, ack.payload)
    }

    private fun hex(value: String): ByteArray {
        require(value.length % 2 == 0) { "Hex string must have even length." }
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
