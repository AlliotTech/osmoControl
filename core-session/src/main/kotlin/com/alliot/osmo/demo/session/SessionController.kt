package com.alliot.osmo.demo.session

import com.alliot.osmo.demo.session.log.SessionLogEntry
import com.alliot.osmo.demo.session.model.CameraStatusSnapshot
import com.alliot.osmo.demo.session.model.SessionDevice
import com.alliot.osmo.demo.session.model.WifiCredentials
import com.alliot.osmo.demo.session.model.SessionStatus
import kotlinx.coroutines.flow.StateFlow

interface SessionController {
    val devices: StateFlow<List<SessionDevice>>
    val status: StateFlow<SessionStatus>
    val cameraStatus: StateFlow<CameraStatusSnapshot>
    val logs: StateFlow<List<SessionLogEntry>>

    suspend fun startScan()
    suspend fun stopScan()
    suspend fun connect(device: SessionDevice)
    suspend fun disconnect()
    suspend fun requestVersion()
    suspend fun rebootCamera()
    suspend fun toggleRecording()
    suspend fun switchMode(mode: Int)
    suspend fun subscribeStatus()
    suspend fun pushGps(latitude: Double, longitude: Double, altitudeMeters: Double)
    suspend fun setGpsAutoPushEnabled(enabled: Boolean)
    suspend fun setGpsAutoPushFrequencyHz(hz: Int)
    suspend fun setGpsLocationRequestFrequencyHz(hz: Int)
    suspend fun sleep()
    suspend fun wake()
    suspend fun wakeViaGatt()
    suspend fun wakeAndSnapshot()
    suspend fun reportRecordKeyClick()
    suspend fun reportQsKeyClick()
    suspend fun reportSnapshotKeyClick()
    suspend fun sendManualCommand(hex: String)
    /**
     * Opens a clean, pure-DUML BLE link (never the 0xAA R-SDK ConnectionRequest) to
     * [preferredMac] — or the nearest supported camera when null — and reads that camera's own AP
     * SSID + passphrase (0x07/0x07, 0x07/0x0e), so the media flow can auto-join without manual
     * entry. Null when no camera is reachable or the camera withholds them. Never pairs as an
     * R-SDK remote controller: an Osmo withholds Wi-Fi credentials while a controller session owns
     * the link.
     */
    suspend fun fetchWifiCredentials(preferredMac: String? = null): WifiCredentials?

    /**
     * Stops the media keepalive and drops the media BLE link. Called when the media flow
     * disconnects, so the persistent 0x00/0x2b keepalive doesn't outlive the offload.
     */
    suspend fun releaseMediaLink()
    suspend fun setHandshakeVerifyMode(mode: Int)
}
