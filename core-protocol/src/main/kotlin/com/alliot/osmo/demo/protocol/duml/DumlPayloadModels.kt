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

data class StorageStatusPayload(
    val storageIndex: Int,
    val storageType: Int,
    val storageName: String,
    val totalSizeBytes: Long,
    val usedSizeBytes: Long,
    val isInserted: Boolean,
) : DumlPayload

data class RawDumlPayload(
    val bytes: ByteArray,
) : DumlPayload
