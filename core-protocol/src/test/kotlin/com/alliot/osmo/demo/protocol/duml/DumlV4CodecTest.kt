package com.alliot.osmo.demo.protocol.duml

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DumlV4CodecTest {

    @Test
    fun encode_layout_matches_dji_message_framing() {
        val encoded = DumlV4Codec.encode(
            DumlV4Codec.V4Frame(
                target = 0x0102,
                id = 0x0001,
                cmdType = 2,
                cmdSet = 0x00,
                cmdId = 0x26,
                payload = byteArrayOf(0x4A),
            ),
        )
        // 55 len 04 crc8 | target LE | id LE | type u24 LE | payload | crc16 LE
        assertEquals(0x55, encoded[0].toInt() and 0xFF)
        assertEquals(14, encoded[1].toInt() and 0xFF)
        assertEquals(0x04, encoded[2].toInt() and 0xFF)
        assertEquals(0x02, encoded[4].toInt() and 0xFF)
        assertEquals(0x01, encoded[5].toInt() and 0xFF)
        assertEquals(0x01, encoded[6].toInt() and 0xFF)
        assertEquals(0x00, encoded[7].toInt() and 0xFF)
        // type = (2 shl 5) or (0x00 shl 8) or (0x26 shl 16) = 0x260040
        assertEquals(0x40, encoded[8].toInt() and 0xFF)
        assertEquals(0x00, encoded[9].toInt() and 0xFF)
        assertEquals(0x26, encoded[10].toInt() and 0xFF)
        assertEquals(0x4A, encoded[11].toInt() and 0xFF)
        assertEquals(14, encoded.size)
    }

    @Test
    fun round_trip_preserves_fields() {
        val frame = DumlV4Codec.V4Frame(
            target = 0x4802,
            id = 0x8092,
            cmdType = 4,
            cmdSet = 0x00,
            cmdId = 0x81,
            payload = byteArrayOf(0x01, 0x02, 0x03),
        )
        val decoded = DumlV4Codec.decode(DumlV4Codec.encode(frame))
        assertEquals(frame.target, decoded.target)
        assertEquals(frame.id, decoded.id)
        assertEquals(frame.cmdType, decoded.cmdType)
        assertEquals(frame.cmdSet, decoded.cmdSet)
        assertEquals(frame.cmdId, decoded.cmdId)
        assertArrayEquals(frame.payload, decoded.payload)
    }

    @Test
    fun decode_rejects_corrupted_crc() {
        val good = DumlV4Codec.encode(
            DumlV4Codec.V4Frame(0x0102, 1, 2, 0x00, 0x26, byteArrayOf(0x11, 0x22)),
        )
        val badCrc16 = good.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val badCrc8 = good.copyOf().also { it[3] = (it[3] + 1).toByte() }
        assertTrue(runCatching { DumlV4Codec.decode(badCrc16) }.isFailure)
        assertTrue(runCatching { DumlV4Codec.decode(badCrc8) }.isFailure)
    }
}
