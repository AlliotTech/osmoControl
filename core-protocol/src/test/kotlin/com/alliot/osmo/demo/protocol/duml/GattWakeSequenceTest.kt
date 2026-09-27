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

    private fun hex(value: String): ByteArray {
        require(value.length % 2 == 0) { "Hex string must have even length." }
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
