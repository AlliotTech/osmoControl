package com.alliot.osmo.demo.protocol.duml

import com.alliot.osmo.demo.protocol.util.ByteOrder

object DumlPayloadCodec {
    fun encode(cmdSet: Int, cmdId: Int, payload: DumlPayload): ByteArray {
        return when {
            cmdSet == DumlCmdSet.WIFI && cmdId == DumlWifiCmd.SET_PAIRING_PIN && payload is SetPairingPinPayload ->
                DumlStringCodec.pack(payload.identifier) + DumlStringCodec.pack(payload.pin)

            cmdSet == DumlCmdSet.WIFI && cmdId == DumlWifiCmd.WIFI_CONNECT && payload is WifiConnectPayload ->
                DumlStringCodec.pack(payload.ssid) + DumlStringCodec.pack(payload.password)

            payload is RawDumlPayload -> payload.bytes
            else -> error("Unsupported DUML payload encoder for cmdSet=0x${cmdSet.toString(16)} cmdId=0x${cmdId.toString(16)}")
        }
    }

    fun decode(cmdSet: Int, cmdId: Int, flags: Int, bytes: ByteArray): DumlPayload {
        return when {
            cmdSet == DumlCmdSet.WIFI && cmdId == DumlWifiCmd.SET_PAIRING_PIN && flags == DumlFlags.RESPONSE ->
                decodePairingStatus(bytes)

            cmdSet == DumlCmdSet.WIFI && cmdId == DumlWifiCmd.PAIRING_APPROVED ->
                decodePairingApproved(bytes)

            cmdSet == DumlCmdSet.WIFI && (cmdId == DumlWifiCmd.GET_SSID || cmdId == DumlWifiCmd.GET_PASSWORD) ->
                decodeWifiCredential(bytes)

            cmdSet == DumlCmdSet.STORAGE && cmdId == DumlStorageCmd.GET_STATUS ->
                decodeStorageStatus(bytes)

            cmdSet == DumlCmdSet.GIMBAL && cmdId == DumlGimbalCmd.POSITION_TELEMETRY ->
                decodeGimbalTelemetry(bytes)

            else -> RawDumlPayload(bytes)
        }
    }

    private fun decodePairingStatus(bytes: ByteArray): PairingStatusPayload {
        val prefix = bytes.getOrNull(0)?.toInt()?.and(0xFF) ?: 0
        val code = bytes.getOrNull(1)?.toInt()?.and(0xFF) ?: prefix
        val status = when (code) {
            0x01 -> PairingStatus.ALREADY_PAIRED
            0x02 -> PairingStatus.APPROVAL_REQUIRED
            else -> PairingStatus.UNKNOWN
        }
        val tail = if (bytes.size > 2) bytes.copyOfRange(2, bytes.size) else ByteArray(0)
        return PairingStatusPayload(
            rawStatusPrefix = prefix,
            statusCode = code,
            status = status,
            tail = tail,
        )
    }

    private fun decodePairingApproved(bytes: ByteArray): PairingApprovedPayload {
        val rawValue = bytes.firstOrNull()?.toInt()?.and(0xFF) ?: 0
        val tail = if (bytes.size > 1) bytes.copyOfRange(1, bytes.size) else ByteArray(0)
        return PairingApprovedPayload(
            approved = rawValue == 0x01,
            rawValue = rawValue,
            tail = tail,
        )
    }

    private fun decodeWifiCredential(bytes: ByteArray): WifiCredentialPayload {
        val status = bytes.getOrNull(0)?.toInt()?.and(0xFF) ?: 0
        val value = runCatching { DumlStringCodec.unpack(bytes, 1).value }.getOrDefault("")
        return WifiCredentialPayload(status = status, value = value)
    }

    private fun decodeStorageStatus(bytes: ByteArray): StorageStatusPayload {
        require(bytes.size >= 3) { "Storage status payload too short" }
        val storageIndex = bytes[0].toInt() and 0xFF
        val storageType = bytes[1].toInt() and 0xFF
        val nameLength = bytes[2].toInt() and 0xFF
        require(bytes.size >= 3 + nameLength + 17) { "Storage status payload too short for name and sizes" }
        val storageName = bytes.copyOfRange(3, 3 + nameLength).toString(Charsets.UTF_8)
        val sizesOffset = 3 + nameLength
        val totalSizeBytes = u64Le(bytes, sizesOffset)
        val usedSizeBytes = u64Le(bytes, sizesOffset + 8)
        val isInserted = (bytes[sizesOffset + 16].toInt() and 0xFF) != 0
        return StorageStatusPayload(
            storageIndex = storageIndex,
            storageType = storageType,
            storageName = storageName,
            totalSizeBytes = totalSizeBytes,
            usedSizeBytes = usedSizeBytes,
            isInserted = isInserted,
        )
    }

    private fun u64Le(bytes: ByteArray, offset: Int): Long {
        val low = ByteOrder.u32(bytes, offset)
        val high = ByteOrder.u32(bytes, offset + 4)
        return low or (high shl 32)
    }

    private fun decodeGimbalTelemetry(bytes: ByteArray): GimbalTelemetryPayload {
        require(bytes.size >= 8) { "Gimbal telemetry payload too short" }
        return GimbalTelemetryPayload(
            pitchTenths = signed16(bytes, 0),
            rollTenths = signed16(bytes, 2),
            yawTenths = signed16(bytes, 4),
            modeFlags = bytes[6].toInt() and 0xFF,
            rollAdjust = bytes[7].toInt() and 0xFF,
            tail = if (bytes.size > 8) bytes.copyOfRange(8, bytes.size) else ByteArray(0),
        )
    }

    private fun signed16(bytes: ByteArray, offset: Int): Int {
        return ByteOrder.u16(bytes, offset).toShort().toInt()
    }
}
