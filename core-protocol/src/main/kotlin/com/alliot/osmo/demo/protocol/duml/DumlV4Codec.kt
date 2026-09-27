package com.alliot.osmo.demo.protocol.duml

import com.alliot.osmo.demo.protocol.util.ByteOrder

/**
 * DJI message framing **version 0x04** (osmosis `DjiMessage`).
 *
 * This is the framing osmosis sends **commands** in over the UDP datalink
 * (`DumlTransport.sendDuml`): the camera answers in classic v1 framing, so
 * replies keep going through [DumlFrameCodec]. The two framings are
 * wire-identical for frames under 256 bytes except for bytes 6-8:
 *
 * ```text
 * 55 [len:u8] 04 [crc8] [target:u16 LE] [id:u16 LE] [type:u24 LE] [payload] [crc16 LE]
 * ```
 *
 * where `type = (cmdType shl 5) or (cmdSet shl 8) or (cmdId shl 16)`.
 * osmosis uses `cmdType = 4` for `0x00/0x81` app registration and `2`
 * for everything else (media, Wi-Fi).
 *
 * Reference: osmosis `duml/DjiMessage.kt`, `net/DumlTransport.kt`
 * (`sendDuml`).
 */
object DumlV4Codec {

    const val VERSION: Int = 0x04
    private const val HEADER_SIZE: Int = 11 // 55 len ver crc8 target(2) id(2) type(3)

    data class V4Frame(
        val target: Int,
        val id: Int,
        val cmdType: Int,
        val cmdSet: Int,
        val cmdId: Int,
        val payload: ByteArray = ByteArray(0),
    )

    data class DecodedV4Frame(
        val target: Int,
        val id: Int,
        val cmdType: Int,
        val cmdSet: Int,
        val cmdId: Int,
        val payload: ByteArray,
    )

    fun encode(frame: V4Frame): ByteArray {
        val totalLength = HEADER_SIZE + frame.payload.size + DumlConstants.CRC16_SIZE
        require(totalLength <= 0xFF) { "v4 frames carry a single length byte" }
        val buffer = ByteArray(totalLength)
        buffer[0] = DumlConstants.SOF.toByte()
        buffer[1] = (totalLength and 0xFF).toByte()
        buffer[2] = VERSION.toByte()
        buffer[3] = DumlCrc.crc8(buffer.copyOfRange(0, 3)).toByte()
        ByteOrder.writeU16(frame.target, buffer, 4)
        ByteOrder.writeU16(frame.id, buffer, 6)
        val type = ((frame.cmdType and 0xFF) shl 5) or
            ((frame.cmdSet and 0xFF) shl 8) or
            ((frame.cmdId and 0xFF) shl 16)
        buffer[8] = (type and 0xFF).toByte()
        buffer[9] = ((type ushr 8) and 0xFF).toByte()
        buffer[10] = ((type ushr 16) and 0xFF).toByte()
        frame.payload.copyInto(buffer, destinationOffset = HEADER_SIZE)
        val crcOffset = totalLength - DumlConstants.CRC16_SIZE
        ByteOrder.writeU16(DumlCrc.crc16(buffer.copyOfRange(0, crcOffset)), buffer, crcOffset)
        return buffer
    }

    fun decode(bytes: ByteArray): DecodedV4Frame {
        require(bytes.size >= HEADER_SIZE + DumlConstants.CRC16_SIZE) { "Frame too short" }
        require((bytes[0].toInt() and 0xFF) == DumlConstants.SOF) { "Invalid SOF" }
        val totalLength = bytes[1].toInt() and 0xFF
        require(totalLength == bytes.size) { "Frame length mismatch" }
        require((bytes[2].toInt() and 0xFF) == VERSION) { "Not a v4 frame" }
        val crc8 = bytes[3].toInt() and 0xFF
        require(crc8 == DumlCrc.crc8(bytes.copyOfRange(0, 3))) { "CRC8 mismatch" }
        val crc16 = ByteOrder.u16(bytes, bytes.size - 2)
        require(crc16 == DumlCrc.crc16(bytes.copyOfRange(0, bytes.size - 2))) { "CRC16 mismatch" }
        val type = (bytes[8].toInt() and 0xFF) or
            ((bytes[9].toInt() and 0xFF) shl 8) or
            ((bytes[10].toInt() and 0xFF) shl 16)
        return DecodedV4Frame(
            target = ByteOrder.u16(bytes, 4),
            id = ByteOrder.u16(bytes, 6),
            cmdType = (type ushr 5) and 0x07,
            cmdSet = (type ushr 8) and 0xFF,
            cmdId = (type ushr 16) and 0xFF,
            payload = bytes.copyOfRange(HEADER_SIZE, bytes.size - DumlConstants.CRC16_SIZE),
        )
    }
}
