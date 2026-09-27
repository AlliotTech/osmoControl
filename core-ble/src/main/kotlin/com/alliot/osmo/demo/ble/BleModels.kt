package com.alliot.osmo.demo.ble

data class BleScanResult(
    val name: String,
    val macAddress: String,
    val rssi: Int,
    val manufacturerData: ByteArray? = null,
)

data class BleConnectionState(
    val isBluetoothEnabled: Boolean,
    val isConnected: Boolean,
    val connectedAddress: String? = null,
    val localAdapterAddress: String? = null,
    val supportsWakeAdvertising: Boolean = false,
)

sealed interface BleEvent {
    data object ScanStarted : BleEvent
    data object ScanStopped : BleEvent
    data class Connected(val macAddress: String) : BleEvent
    data class Disconnected(val macAddress: String?, val reason: String? = null) : BleEvent
    data class Notification(val bytes: ByteArray) : BleEvent
    data class Write(val bytes: ByteArray) : BleEvent
    data class Error(val message: String) : BleEvent
    data class BringUp(val stage: String) : BleEvent
}

object WakeAdvertisingPayload {
    private val prefix = byteArrayOf(
        10,
        0xFF.toByte(),
        'W'.code.toByte(),
        'K'.code.toByte(),
        'P'.code.toByte(),
    )

    fun build(reversedMac: ByteArray): ByteArray {
        require(reversedMac.size == 6) { "Wake advertising requires 6 MAC bytes in reverse order." }
        return prefix + reversedMac
    }
}
