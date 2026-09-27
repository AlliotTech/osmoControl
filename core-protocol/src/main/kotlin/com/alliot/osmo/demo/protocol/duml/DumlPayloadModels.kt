package com.alliot.osmo.demo.protocol.duml

sealed interface DumlPayload

data class SetPairingPinPayload(
    val identifier: String,
    val pin: String,
) : DumlPayload

enum class PairingStatus {
    ALREADY_PAIRED,
    APPROVAL_REQUIRED,
    UNKNOWN,
}

data class PairingStatusPayload(
    val rawStatusPrefix: Int,
    val statusCode: Int,
    val status: PairingStatus,
    val tail: ByteArray = ByteArray(0),
) : DumlPayload

data class PairingApprovedPayload(
    val approved: Boolean,
    val rawValue: Int,
    val tail: ByteArray = ByteArray(0),
) : DumlPayload

data class GimbalTelemetryPayload(
    val pitchTenths: Int,
    val rollTenths: Int,
    val yawTenths: Int,
    val modeFlags: Int,
    val rollAdjust: Int,
    val tail: ByteArray = ByteArray(0),
) : DumlPayload

data class WifiCredentialPayload(
    val status: Int,
    val value: String,
) : DumlPayload

data class WifiConnectPayload(
    val ssid: String,
    val password: String,
) : DumlPayload

/** Active-store status from the unprompted `0x02/0x80` push (all values MiB). */
data class ActiveStoreStatusPayload(
    val flags: Long,
    val inPlayback: Boolean,
    val totalMb: Long,
    val freeMb: Long,
) : DumlPayload

/** Per-store capacities from the unprompted `0x02/0xDC` push (all values MiB). */
data class StoresStatusPayload(
    val storeCount: Int,
    val sdTotalMb: Long,
    val sdFreeMb: Long,
    val internalTotalMb: Long?,
    val internalFreeMb: Long?,
) : DumlPayload

data class RawDumlPayload(
    val bytes: ByteArray,
) : DumlPayload
