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

            cmdSet == DumlCmdSet.FILE_SYSTEM && cmdId == DumlFileSystemCmd.ACTIVE_STORE_STATUS ->
                decodeActiveStoreStatus(bytes)

            cmdSet == DumlCmdSet.FILE_SYSTEM && cmdId == DumlFileSystemCmd.STORES_STATUS ->
                decodeStoresStatus(bytes)

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

    /**
     * `0x02/0x80`: flags word u32LE @0 (bit 30 = the camera's own
     * "am I in playback"), active-store total MiB u32LE @5, free MiB @9.
     * (Ground truth: KonradIT/osmosis DumlSession.applyStatusFrame.)
     */
    private fun decodeActiveStoreStatus(bytes: ByteArray): ActiveStoreStatusPayload {
        require(bytes.size >= 13) { "0x02/0x80 payload too short" }
        val flags = ByteOrder.u32(bytes, 0)
        val totalMb = ByteOrder.u32(bytes, 5)
        val freeMb = ByteOrder.u32(bytes, 9)
        return ActiveStoreStatusPayload(
            flags = flags,
            inPlayback = (flags and 0x40000000L) != 0L,
            totalMb = totalMb,
            freeMb = freeMb,
        )
    }

    /**
     * `0x02/0xDC`: one [total][free] u32LE MiB block per store. Byte 2 =
     * store count; first block @6/@10, built-in store @24/@28 when the
     * body is >= 32 B (a 22 B single-store body carries only the first).
     */
    private fun decodeStoresStatus(bytes: ByteArray): StoresStatusPayload {
        require(bytes.size >= 22) { "0x02/0xDC payload too short" }
        val storeCount = bytes[2].toInt() and 0xFF
        val hasInternal = bytes.size >= 32
        return StoresStatusPayload(
            storeCount = storeCount,
            sdTotalMb = ByteOrder.u32(bytes, 6),
            sdFreeMb = ByteOrder.u32(bytes, 10),
            internalTotalMb = if (hasInternal) ByteOrder.u32(bytes, 24) else null,
            internalFreeMb = if (hasInternal) ByteOrder.u32(bytes, 28) else null,
        )
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
