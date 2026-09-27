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
     * Reads the connected camera's own AP SSID + passphrase over the open BLE link
     * (0x07/0x07, 0x07/0x0e), so the media flow can auto-join without manual entry.
     * Null when not connected/awake or the camera withholds them.
     */
    suspend fun fetchWifiCredentials(): WifiCredentials?
    suspend fun setHandshakeVerifyMode(mode: Int)
}
