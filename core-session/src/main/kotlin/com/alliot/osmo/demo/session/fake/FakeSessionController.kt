package com.alliot.osmo.demo.session.fake

import com.alliot.osmo.demo.protocol.frame.DjiFrame
import com.alliot.osmo.demo.protocol.frame.DjiFrameCodec
import com.alliot.osmo.demo.protocol.payload.CameraModeSwitchPayload
import com.alliot.osmo.demo.protocol.payload.CameraStatusSubscriptionPayload
import com.alliot.osmo.demo.protocol.payload.CommandIds
import com.alliot.osmo.demo.protocol.payload.ConnectionRequestPayload
import com.alliot.osmo.demo.protocol.payload.EmptyPayload
import com.alliot.osmo.demo.protocol.payload.GpsDataPayload
import com.alliot.osmo.demo.protocol.payload.KeyReportPayload
import com.alliot.osmo.demo.protocol.payload.PayloadCodec
import com.alliot.osmo.demo.protocol.payload.PowerModePayload
import com.alliot.osmo.demo.protocol.payload.RebootPayload
import com.alliot.osmo.demo.protocol.payload.RecordControlPayload
import com.alliot.osmo.demo.session.SessionController
import com.alliot.osmo.demo.session.log.SessionLogEntry
import com.alliot.osmo.demo.session.model.CameraStatusSnapshot
import com.alliot.osmo.demo.session.model.HandshakeStage
import com.alliot.osmo.demo.session.model.LogCategory
import com.alliot.osmo.demo.session.model.SessionDevice
import com.alliot.osmo.demo.session.model.SessionStatus
import com.alliot.osmo.demo.session.model.SessionTransportMode
import com.alliot.osmo.demo.session.model.resolveConnectedProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.OffsetDateTime

class FakeSessionController : SessionController {
    private val _devices = MutableStateFlow(emptyList<SessionDevice>())
    override val devices: StateFlow<List<SessionDevice>> = _devices.asStateFlow()

    private val _status = MutableStateFlow(SessionStatus(mode = SessionTransportMode.FAKE))
    override val status: StateFlow<SessionStatus> = _status.asStateFlow()

    private val _cameraStatus = MutableStateFlow(CameraStatusSnapshot())
    override val cameraStatus: StateFlow<CameraStatusSnapshot> = _cameraStatus.asStateFlow()

    private val _logs = MutableStateFlow(emptyList<SessionLogEntry>())
    override val logs: StateFlow<List<SessionLogEntry>> = _logs.asStateFlow()

    private var sequence = 1

    override suspend fun startScan() {
        _status.value = _status.value.copy(scanning = true, latestError = null)
        _devices.value = FakeCameraScript.devices
        appendLog(LogCategory.STATE, "Fake scan started.")
    }

    override suspend fun stopScan() {
        _status.value = _status.value.copy(scanning = false)
        appendLog(LogCategory.STATE, "Fake scan stopped.")
    }

    override suspend fun connect(device: SessionDevice) {
        _status.value = _status.value.copy(
            connectedDevice = device,
            connectedProfile = resolveConnectedProfile(device),
            handshakeStage = HandshakeStage.REQUEST_SENT,
            latestError = null,
        )
        appendLog(LogCategory.STATE, "Connecting fake device ${device.name}.")
        encodeAndLog(CommandIds.CMD_SET_COMMON, CommandIds.CONNECTION, ConnectionRequestPayload(device.deviceId, macAddress = byteArrayOf(0x01, 0x23, 0x45, 0x67, 0x11, 0x22), verifyMode = 1, verifyData = 4321))
        _status.value = _status.value.copy(handshakeStage = HandshakeStage.CAMERA_CONFIRMATION_RECEIVED)
        _status.value = _status.value.copy(handshakeStage = HandshakeStage.COMPLETED, protocolReady = true)
        _cameraStatus.value = FakeCameraScript.initialStatus(device)
        appendLog(LogCategory.STATE, "Fake handshake completed.")
    }

    override suspend fun disconnect() {
        appendLog(LogCategory.STATE, "Disconnected.")
        val current = _status.value
        _status.value = SessionStatus(mode = SessionTransportMode.FAKE)
            .copy(
                gpsAutoPushEnabled = current.gpsAutoPushEnabled,
                gpsAutoPushHz = current.gpsAutoPushHz,
                gpsLocationRequestHz = current.gpsLocationRequestHz,
            )
        _cameraStatus.value = CameraStatusSnapshot()
        _devices.value = emptyList()
    }

    override suspend fun requestVersion() {
        encodeAndLog(CommandIds.CMD_SET_COMMON, CommandIds.VERSION_QUERY, EmptyPayload)
        _status.value = _status.value.copy(
            latestVersion = "SDK-V1.1 / Fake Camera / 01.00.00.01",
            lastVersionResult = "OK: fake version response",
            connectedProfile = resolveConnectedProfile(
                device = _status.value.connectedDevice,
                handshakeDeviceId = _status.value.connectedDevice?.deviceId,
                productId = "Fake Camera",
            ),
        )
        appendLog(LogCategory.RX, "Version response ready.")
    }

    override suspend fun rebootCamera() {
        encodeAndLog(
            CommandIds.CMD_SET_COMMON,
            CommandIds.REBOOT,
            RebootPayload(deviceId = connectedDeviceId()),
        )
        _status.value = _status.value.copy(lastRebootResult = "OK: fake reboot accepted")
        appendLog(LogCategory.STATE, "Fake reboot command accepted.")
    }

    override suspend fun toggleRecording() {
        val target = !_cameraStatus.value.recording
        encodeAndLog(
            CommandIds.CMD_SET_CAMERA,
            CommandIds.RECORD_CONTROL,
            RecordControlPayload(deviceId = connectedDeviceId(), recordControl = if (target) 0 else 1),
        )
        _cameraStatus.value = _cameraStatus.value.copy(
            recording = target,
            state = if (target) 0x03 else 0x01,
            detail = if (target) "Recording" else "Preview",
            lastPushCommandId = "1D02",
            lastPushSummary = if (target) "Fake record state -> recording" else "Fake record state -> preview",
        )
        _status.value = _status.value.copy(
            lastRecordResult = if (target) "OK: recording started" else "OK: recording stopped",
        )
        appendLog(LogCategory.STATE, "Recording toggled to $target.")
    }

    override suspend fun switchMode(mode: Int) {
        encodeAndLog(CommandIds.CMD_SET_CAMERA, CommandIds.CAMERA_MODE_SWITCH, CameraModeSwitchPayload(connectedDeviceId(), mode))
        _cameraStatus.value = _cameraStatus.value.copy(
            mode = mode,
            detail = "Mode switched to 0x${mode.toString(16)}",
            lastPushCommandId = "1D06",
            lastPushSummary = "Fake mode metadata -> 0x${mode.toString(16)}",
        )
        _status.value = _status.value.copy(lastModeSwitchResult = "OK: mode 0x${mode.toString(16)}")
        appendLog(LogCategory.STATE, "Camera mode switched to 0x${mode.toString(16)}.")
    }

    override suspend fun subscribeStatus() {
        encodeAndLog(CommandIds.CMD_SET_CAMERA, CommandIds.CAMERA_STATUS_SUBSCRIPTION, CameraStatusSubscriptionPayload(pushMode = 3, pushFreq = 20))
        _status.value = _status.value.copy(lastSubscribeResult = "OK: fake 1D02 stream active")
        _cameraStatus.value = _cameraStatus.value.copy(
            lastPushCommandId = "1D02",
            lastPushSummary = "Fake subscription acknowledged",
        )
        appendLog(LogCategory.RX, "Status subscription active.")
    }

    override suspend fun pushGps(latitude: Double, longitude: Double, altitudeMeters: Double) {
        val sentAtMs = System.currentTimeMillis()
        val now = OffsetDateTime.now()
        encodeAndLog(
            CommandIds.CMD_SET_COMMON,
            CommandIds.GPS_PUSH,
            GpsDataPayload(
                yearMonthDay = now.year * 10_000 + now.monthValue * 100 + now.dayOfMonth,
                hourMinuteSecond = (now.hour + 8) * 10_000 + now.minute * 100 + now.second,
                longitudeE7 = (longitude * 10_000_000).toInt(),
                latitudeE7 = (latitude * 10_000_000).toInt(),
                heightMm = (altitudeMeters * 1000).toInt(),
                speedNorthCmps = 0f,
                speedEastCmps = 0f,
                speedDownCmps = 0f,
                verticalAccuracyMm = 800,
                horizontalAccuracyMm = 600,
                speedAccuracyCmps = 50,
                satelliteCount = 14,
            ),
        )
        _status.value = _status.value.copy(
            gpsPushActive = true,
            lastGpsResult = "OK: fake GPS accepted",
            gpsSignalLocked = true,
            lastGpsCoordinate = "${"%.6f".format(latitude)}, ${"%.6f".format(longitude)}",
            lastGpsAltitudeMeters = altitudeMeters,
            lastGpsSpeedMps = 0f,
            lastGpsBearingDegrees = 0f,
            lastGpsAccuracyMeters = 3f,
            lastGpsProvider = "manual-fake",
            lastGpsSource = "manual-fake",
            lastGpsSentAtMs = sentAtMs,
        )
        appendLog(LogCategory.STATE, "GPS pushed: $latitude,$longitude")
    }

    override suspend fun setGpsAutoPushEnabled(enabled: Boolean) {
        _status.value = _status.value.copy(
            gpsAutoPushEnabled = enabled,
            gpsPushActive = if (enabled) _status.value.gpsPushActive else false,
            lastGpsResult = if (enabled) "GPS auto push enabled (fake)" else "GPS auto push disabled (fake)",
        )
        appendLog(LogCategory.STATE, "Fake GPS auto push enabled=$enabled")
    }

    override suspend fun setGpsAutoPushFrequencyHz(hz: Int) {
        val normalizedHz = normalizeGpsFrequency(hz)
        _status.value = _status.value.copy(
            gpsAutoPushHz = normalizedHz,
            lastGpsResult = "GPS frequency set to ${normalizedHz}Hz (fake)",
        )
        appendLog(LogCategory.STATE, "Fake GPS frequency set to ${normalizedHz}Hz")
    }

    override suspend fun setGpsLocationRequestFrequencyHz(hz: Int) {
        val normalizedHz = normalizeLocationRequestFrequency(hz)
        _status.value = _status.value.copy(
            gpsLocationRequestHz = normalizedHz,
            lastGpsResult = "GPS location request set to ${normalizedHz}Hz (fake)",
        )
        appendLog(LogCategory.STATE, "Fake GPS location request frequency=${normalizedHz}Hz")
    }

    override suspend fun sleep() {
        encodeAndLog(CommandIds.CMD_SET_COMMON, CommandIds.POWER_MODE, PowerModePayload(3))
        _status.value = _status.value.copy(
            sleeping = true,
            lastSleepResult = "OK: fake sleep accepted",
            lastWakeResult = null,
        )
        _cameraStatus.value = _cameraStatus.value.copy(powerMode = 3, detail = "Sleeping")
        appendLog(LogCategory.STATE, "Camera is sleeping.")
    }

    override suspend fun wake() {
        appendLog(LogCategory.BLE, "Fake wake advertising sent.")
        _status.value = _status.value.copy(
            sleeping = false,
            lastWakeResult = "OK: fake wake completed",
        )
        _cameraStatus.value = _cameraStatus.value.copy(powerMode = 0, detail = "Awake")
    }

    override suspend fun wakeViaGatt() {
        FakeCameraScript.gattWakeScriptFrames().forEach { frameBytes ->
            appendLog(LogCategory.TX, "Fake GATT wake frame sent.", frameBytes.joinToString(" ") { "%02X".format(it) })
        }
        appendLog(
            LogCategory.RX,
            "Fake GATT wake reply received.",
            FakeCameraScript.gattWakeReplyPayload.joinToString(" ") { "%02X".format(it) },
        )
        _status.value = _status.value.copy(
            sleeping = false,
            lastWakeResult = "OK: fake GATT wake completed",
        )
        _cameraStatus.value = _cameraStatus.value.copy(powerMode = 0, detail = "Awake via GATT wake")
    }

    override suspend fun wakeAndSnapshot() {
        if (_status.value.protocolReady && !_status.value.sleeping) {
            reportRecordKeyClick()
            _status.value = _status.value.copy(lastWakeResult = "OK: fake capture sent without wake")
            return
        }
        wake()
        reportSnapshotKeyClick()
        _status.value = _status.value.copy(lastWakeResult = "OK: fake wake+snapshot completed")
    }

    override suspend fun reportRecordKeyClick() {
        encodeAndLog(
            CommandIds.CMD_SET_COMMON,
            CommandIds.KEY_REPORT,
            KeyReportPayload(
                keyCode = 0x01,
                mode = 0x01,
                keyValue = 0x00,
            ),
        )
        _status.value = _status.value.copy(lastKeyReportResult = "OK: fake key report record click")
        appendLog(LogCategory.STATE, "Fake key report: record click")
    }

    override suspend fun reportQsKeyClick() {
        encodeAndLog(
            CommandIds.CMD_SET_COMMON,
            CommandIds.KEY_REPORT,
            KeyReportPayload(
                keyCode = 0x02,
                mode = 0x01,
                keyValue = 0x00,
            ),
        )
        _status.value = _status.value.copy(lastKeyReportResult = "OK: fake key report QS click")
        appendLog(LogCategory.STATE, "Fake key report: QS click")
    }

    override suspend fun reportSnapshotKeyClick() {
        encodeAndLog(
            CommandIds.CMD_SET_COMMON,
            CommandIds.KEY_REPORT,
            KeyReportPayload(
                keyCode = 0x03,
                mode = 0x01,
                keyValue = 0x00,
            ),
        )
        _status.value = _status.value.copy(lastKeyReportResult = "OK: fake key report snapshot click")
        appendLog(LogCategory.STATE, "Fake key report: snapshot click")
    }

    override suspend fun sendManualCommand(hex: String) {
        appendLog(LogCategory.TX, "Manual command sent.", hex)
    }

    override suspend fun fetchWifiCredentials(preferredMac: String?): com.alliot.osmo.demo.session.model.WifiCredentials? {
        // The fake device has no real AP; the media flow falls back to manual entry.
        appendLog(LogCategory.STATE, "Fake controller has no Wi-Fi credentials.")
        return null
    }

    override suspend fun releaseMediaLink() {
        appendLog(LogCategory.STATE, "Fake controller: media link released.")
    }

    override suspend fun setHandshakeVerifyMode(mode: Int) {
        _status.value = _status.value.copy(handshakeVerifyMode = mode)
        appendLog(LogCategory.STATE, "Fake handshake verify_mode set to $mode")
    }

    private fun connectedDeviceId(): Long = _status.value.connectedDevice?.deviceId ?: 0xFF44

    private fun normalizeGpsFrequency(hz: Int): Int {
        return when (hz) {
            1, 2, 5, 10 -> hz
            else -> 10
        }
    }

    private fun normalizeLocationRequestFrequency(hz: Int): Int {
        return when (hz) {
            1, 2, 5 -> hz
            else -> 1
        }
    }

    private fun encodeAndLog(cmdSet: Int, cmdId: Int, payload: com.alliot.osmo.demo.protocol.payload.ProtocolPayload) {
        val frame = DjiFrame(
            cmdType = 0x01,
            seq = sequence++,
            cmdSet = cmdSet,
            cmdId = cmdId,
            payload = PayloadCodec.encode(cmdSet, cmdId, payload).drop(2).toByteArray(),
        )
        val bytes = DjiFrameCodec.encode(frame)
        appendLog(LogCategory.TX, "Encoded command ${cmdSet.toString(16)}:${cmdId.toString(16)}", bytes.toHexString())
    }

    private fun appendLog(category: LogCategory, message: String, hex: String? = null) {
        _logs.value = (_logs.value + SessionLogEntry(category, message, hex)).takeLast(200)
    }

    private fun ByteArray.toHexString(): String = joinToString(" ") { "%02X".format(it) }
}
