package com.alliot.osmo.demo.session.real

import com.alliot.osmo.demo.ble.BleClient
import com.alliot.osmo.demo.ble.BleEvent
import com.alliot.osmo.demo.ble.BleScanResult
import com.alliot.osmo.demo.ble.WakeAdvertisingPayload
import com.alliot.osmo.demo.protocol.payload.AckPayload
import com.alliot.osmo.demo.protocol.payload.CameraConnectionConfirmationPayload
import com.alliot.osmo.demo.protocol.duml.DumlConstants
import com.alliot.osmo.demo.protocol.duml.DumlFlags
import com.alliot.osmo.demo.protocol.duml.DumlFrame
import com.alliot.osmo.demo.protocol.duml.DumlFrameCodec
import com.alliot.osmo.demo.protocol.duml.GattWakeSequence
import com.alliot.osmo.demo.protocol.frame.DjiFrame
import com.alliot.osmo.demo.protocol.frame.DjiFrameCodec
import com.alliot.osmo.demo.protocol.payload.CameraModeSwitchPayload
import com.alliot.osmo.demo.protocol.payload.CameraStatusSubscriptionPayload
import com.alliot.osmo.demo.protocol.payload.CameraStatusPayload
import com.alliot.osmo.demo.protocol.payload.CommandIds
import com.alliot.osmo.demo.protocol.payload.ConnectionRequestPayload
import com.alliot.osmo.demo.protocol.payload.ConnectionResponsePayload
import com.alliot.osmo.demo.protocol.payload.EmptyPayload
import com.alliot.osmo.demo.protocol.payload.GpsDataPayload
import com.alliot.osmo.demo.protocol.payload.KeyReportPayload
import com.alliot.osmo.demo.protocol.payload.NewCameraStatusPayload
import com.alliot.osmo.demo.protocol.payload.PayloadCodec
import com.alliot.osmo.demo.protocol.payload.PowerModePayload
import com.alliot.osmo.demo.protocol.payload.RebootPayload
import com.alliot.osmo.demo.protocol.payload.RebootResponsePayload
import com.alliot.osmo.demo.protocol.payload.RecordControlPayload
import com.alliot.osmo.demo.protocol.payload.VersionResponsePayload
import com.alliot.osmo.demo.protocol.util.ByteOrder
import com.alliot.osmo.demo.session.SessionController
import com.alliot.osmo.demo.session.log.SessionLogEntry
import com.alliot.osmo.demo.session.model.CameraStatusSnapshot
import com.alliot.osmo.demo.session.model.HandshakeStage
import com.alliot.osmo.demo.session.model.LogCategory
import com.alliot.osmo.demo.session.model.SessionGpsPoint
import com.alliot.osmo.demo.session.model.SessionDevice
import com.alliot.osmo.demo.session.model.SessionStatus
import com.alliot.osmo.demo.session.model.WifiCredentials
import com.alliot.osmo.demo.session.model.SessionTransportMode
import com.alliot.osmo.demo.session.model.inferSessionDevice
import com.alliot.osmo.demo.session.model.resolveConnectedProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.OffsetDateTime
import kotlin.math.cos
import kotlin.math.sin

class BleSessionController(
    private val bleClient: BleClient,
    private val controllerDeviceId: Long = DEFAULT_CONTROLLER_DEVICE_ID,
    private val fallbackControllerMac: ByteArray = DEFAULT_CONTROLLER_MAC,
    private val verifyCodeProvider: () -> Int = { DEFAULT_VERIFY_CODE },
    private val isKnownPairedDevice: (String) -> Boolean = { false },
    private val onDevicePaired: (String) -> Unit = {},
    private val gpsPointProvider: (() -> SessionGpsPoint?)? = null,
    private val gpsRequestIntervalUpdater: ((Long) -> Unit)? = null,
    private val statusWatchdogPollIntervalMs: Long = STATUS_WATCHDOG_POLL_INTERVAL_MS,
    private val statusPushIdleTimeoutMs: Long = STATUS_PUSH_IDLE_TIMEOUT_MS,
    private val statusProbeTimeoutMs: Long = STATUS_PROBE_TIMEOUT_MS,
    private val pairingIdentifierProvider: () -> String = { defaultPairingIdentifier(controllerDeviceId) },
    private val wakeWriteSpacingMs: Long = GATT_WAKE_WRITE_SPACING_MS,
    private val wakeReplyTimeoutMs: Long = GATT_WAKE_REPLY_TIMEOUT_MS,
    private val pairingTimeoutMs: Long = GATT_WAKE_PAIRING_TIMEOUT_MS,
    private val wakeMaxAttempts: Int = GATT_WAKE_MAX_ATTEMPTS,
    private val wakeBackoffBaseMs: Long = GATT_WAKE_BACKOFF_BASE_MS,
) : SessionController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _devices = MutableStateFlow(emptyList<SessionDevice>())
    override val devices: StateFlow<List<SessionDevice>> = _devices.asStateFlow()

    private val _status = MutableStateFlow(
        SessionStatus(
            mode = SessionTransportMode.REAL,
            controllerDeviceId = controllerDeviceId,
        ),
    )
    override val status: StateFlow<SessionStatus> = _status.asStateFlow()

    private val _cameraStatus = MutableStateFlow(CameraStatusSnapshot(detail = "Waiting for device"))
    override val cameraStatus: StateFlow<CameraStatusSnapshot> = _cameraStatus.asStateFlow()

    private val _logs = MutableStateFlow(emptyList<SessionLogEntry>())
    override val logs: StateFlow<List<SessionLogEntry>> = _logs.asStateFlow()

    private var sequence = 100
    private var manualDisconnectRequested = false
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    private var wakeObservationJob: Job? = null
    private var autoGpsJob: Job? = null
    private var postConnectBootstrapStarted = false
    private val pendingCommands = linkedMapOf<CommandKey, PendingCommand>()
    private var oa5NoiseHintShown = false
    private var protocolRxBuffer = ByteArray(0)
    private var dumlRxBuffer = ByteArray(0)
    private var dumlMessageId = 0
    private var wakeReplyDeferred: CompletableDeferred<Unit>? = null
    private var pairingResultDeferred: CompletableDeferred<Int>? = null
    private var wifiSsidDeferred: CompletableDeferred<String?>? = null
    private var wifiPasswordDeferred: CompletableDeferred<String?>? = null
    private var localControllerMac = DEFAULT_CONTROLLER_MAC
    private val rejectedDevicesInCurrentScan = mutableSetOf<String>()
    private var preserveRejectedStateOnDisconnect = false
    private var lastGpsPoint: SessionGpsPoint? = null
    private var gpsAutoPushEnabled = true
    private var gpsAutoPushHz = DEFAULT_AUTO_GPS_HZ
    private var gpsLocationRequestHz = DEFAULT_LOCATION_REQUEST_HZ
    private var recordToggleByKeyInFlight = false
    private var lastRecordToggleTargetStart = true
    private var handshakeVerifyModeManuallySelected = false
    private var lastTransportConnected = false
    private var disconnectRecoveryInProgress = false
    private var statusWatchdogJob: Job? = null
    private var lastCameraStatusPushAtMs: Long? = null
    private var statusProbeSentAtMs: Long? = null
    private var staleStatusRecoveryPending = false
    private var staleStatusRecoveryReason: String? = null

    init {
        gpsRequestIntervalUpdater?.invoke(locationRequestIntervalMs(gpsLocationRequestHz))
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            bleClient.events.collect { event ->
                handleBleEvent(event)
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            bleClient.scanResults.collect {
                refreshDevices()
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            bleClient.connectionState.collect { connectionState ->
                val transportJustDisconnected = lastTransportConnected && !connectionState.isConnected
                lastTransportConnected = connectionState.isConnected
                localControllerMac = parseMacForward(connectionState.localAdapterAddress)
                    .takeIf(::isUsableControllerMac)
                    ?: fallbackControllerMac.copyOf()
                _status.value = _status.value.copy(
                    bluetoothEnabled = connectionState.isBluetoothEnabled,
                    wakeAdvertisingSupported = connectionState.supportsWakeAdvertising,
                    controllerDeviceId = controllerDeviceId,
                    controllerMacAddress = formatMac(localControllerMac),
                )
                if (transportJustDisconnected) {
                    handleTransportDisconnected(
                        reason = "BLE link state lost",
                        fallbackMacAddress = connectionState.connectedAddress,
                    )
                }
            }
        }
    }

    override suspend fun startScan() {
        rejectedDevicesInCurrentScan.clear()
        _status.value = _status.value.copy(scanning = true, latestError = null)
        bleClient.startScan()
        refreshDevices()
        appendLog(LogCategory.BLE, "BLE scan started.")
    }

    override suspend fun stopScan() {
        bleClient.stopScan()
        _status.value = _status.value.copy(scanning = false)
        refreshDevices()
        appendLog(LogCategory.BLE, "BLE scan stopped.")
    }

    override suspend fun connect(device: SessionDevice) {
        if (rejectedDevicesInCurrentScan.contains(device.macAddress)) {
            _status.value = _status.value.copy(
                latestError = "Camera rejected pairing in current scan; skip reconnect for ${device.macAddress}",
            )
            appendLog(LogCategory.ERROR, "Connect skipped for rejected camera ${device.macAddress}")
            return
        }
        val verifyCode = verifyCodeProvider().coerceIn(0, 9999)
        val verifyMode = effectiveHandshakeVerifyMode(device.macAddress)
        manualDisconnectRequested = false
        preserveRejectedStateOnDisconnect = false
        disconnectRecoveryInProgress = false
        staleStatusRecoveryPending = false
        staleStatusRecoveryReason = null
        reconnectJob?.cancel()
        clearPendingCommands()
        stopStatusWatchdog()
        stopAutoGpsPush()
        postConnectBootstrapStarted = false
        _status.value = _status.value.copy(
            connectedDevice = device,
            connectedProfile = resolveConnectedProfile(device),
            handshakeStage = HandshakeStage.REQUEST_SENT,
            handshakeVerifyMode = verifyMode,
            handshakeVerifyCode = verifyCode,
            protocolReady = false,
            sleeping = false,
            gpsPushActive = false,
            controllerDeviceId = controllerDeviceId,
            controllerMacAddress = formatMac(localControllerMac),
            latestError = null,
        )
        bleClient.connect(device.macAddress)
        // connect() returning means the transport is up (the real client suspends
        // until GATT connects); record it directly so a fast drop can't slip past
        // the state collector through StateFlow conflation.
        lastTransportConnected = true
        appendLog(
            LogCategory.STATE,
            "Handshake identity device_id=0x${controllerDeviceId.toString(16)} mac=${formatMac(localControllerMac)} verify_mode=${_status.value.handshakeVerifyMode} verify_code=${verifyCode.toString().padStart(4, '0')}",
        )
        sendProtocol(
            CommandIds.CMD_SET_COMMON,
            CommandIds.CONNECTION,
            ConnectionRequestPayload(
                deviceId = controllerDeviceId,
                macAddress = localControllerMac,
                verifyMode = verifyMode,
                verifyData = verifyCode,
            ),
            cmdType = CMD_WAIT_RESULT,
        )
        _cameraStatus.value = _cameraStatus.value.copy(detail = "Awaiting camera confirmation")
    }

    override suspend fun disconnect() {
        manualDisconnectRequested = true
        preserveRejectedStateOnDisconnect = false
        disconnectRecoveryInProgress = false
        staleStatusRecoveryPending = false
        staleStatusRecoveryReason = null
        reconnectJob?.cancel()
        reconnectAttempts = 0
        clearPendingCommands()
        stopStatusWatchdog()
        stopAutoGpsPush()
        postConnectBootstrapStarted = false
        bleClient.disconnect()
        lastTransportConnected = false
        _status.value = SessionStatus(
            mode = SessionTransportMode.REAL,
            controllerDeviceId = controllerDeviceId,
            controllerMacAddress = formatMac(localControllerMac),
            handshakeVerifyMode = _status.value.handshakeVerifyMode,
            gpsAutoPushEnabled = gpsAutoPushEnabled,
            gpsAutoPushHz = gpsAutoPushHz,
            gpsLocationRequestHz = gpsLocationRequestHz,
        )
        _cameraStatus.value = CameraStatusSnapshot(detail = "Disconnected")
        appendLog(LogCategory.STATE, "Real session disconnected.")
    }

    override suspend fun requestVersion() {
        if (!canSendProtocol(CommandIds.CMD_SET_COMMON, CommandIds.VERSION_QUERY)) return
        registerPending(
            cmdSet = CommandIds.CMD_SET_COMMON,
            cmdId = CommandIds.VERSION_QUERY,
            label = "Version query",
            onTimeout = {
                _status.value = _status.value.copy(
                    latestVersion = null,
                    latestError = "Version query timed out",
                    lastVersionResult = "Timeout",
                )
            },
        )
        sendProtocol(
            CommandIds.CMD_SET_COMMON,
            CommandIds.VERSION_QUERY,
            EmptyPayload,
            cmdType = CMD_WAIT_RESULT,
        )
        _status.value = _status.value.copy(
            latestVersion = "Pending real response",
            lastVersionResult = "Pending...",
        )
    }

    override suspend fun rebootCamera() {
        if (!canSendProtocol(CommandIds.CMD_SET_COMMON, CommandIds.REBOOT)) return
        registerPending(
            cmdSet = CommandIds.CMD_SET_COMMON,
            cmdId = CommandIds.REBOOT,
            label = "Camera reboot",
            onTimeout = {
                _status.value = _status.value.copy(
                    latestError = "Reboot command timed out",
                    lastRebootResult = "Timeout",
                )
            },
        )
        sendProtocol(
            CommandIds.CMD_SET_COMMON,
            CommandIds.REBOOT,
            RebootPayload(deviceId = connectedDeviceId()),
            cmdType = CMD_WAIT_RESULT,
        )
        _status.value = _status.value.copy(lastRebootResult = "Pending...")
    }

    override suspend fun toggleRecording() {
        if (!canSendProtocol(CommandIds.CMD_SET_COMMON, CommandIds.KEY_REPORT)) return
        val target = !_cameraStatus.value.recording
        recordToggleByKeyInFlight = true
        lastRecordToggleTargetStart = target
        registerPending(
            cmdSet = CommandIds.CMD_SET_COMMON,
            cmdId = CommandIds.KEY_REPORT,
            label = if (target) "Record key start" else "Record key stop",
            onTimeout = {
                scope.launch {
                    handleRecordToggleKeyFailure(reason = "0011 timeout fallback", failureLabel = "Record key report timed out")
                }
            },
        )
        sendProtocol(
            CommandIds.CMD_SET_COMMON,
            CommandIds.KEY_REPORT,
            KeyReportPayload(
                keyCode = KEY_CODE_RECORD,
                mode = KEY_REPORT_MODE_EVENT,
                keyValue = KEY_REPORT_VALUE_SINGLE_CLICK,
            ),
            cmdType = CMD_WAIT_RESULT,
        )
        _status.value = _status.value.copy(
            lastRecordResult = if (target) "Pending start via key click..." else "Pending stop via key click...",
            lastKeyReportResult = "Pending...",
        )
        _cameraStatus.value = _cameraStatus.value.copy(detail = if (target) "Record key start requested" else "Record key stop requested")
    }

    override suspend fun switchMode(mode: Int) {
        if (!canSendProtocol(CommandIds.CMD_SET_CAMERA, CommandIds.CAMERA_MODE_SWITCH)) return
        registerPending(
            cmdSet = CommandIds.CMD_SET_CAMERA,
            cmdId = CommandIds.CAMERA_MODE_SWITCH,
            label = "Switch mode 0x${mode.toString(16)}",
            onTimeout = {
                _cameraStatus.value = _cameraStatus.value.copy(detail = "Mode switch timed out")
                _status.value = _status.value.copy(
                    latestError = "Mode switch timed out",
                    lastModeSwitchResult = "Timeout",
                )
            },
        )
        sendProtocol(CommandIds.CMD_SET_CAMERA, CommandIds.CAMERA_MODE_SWITCH, CameraModeSwitchPayload(connectedDeviceId(), mode))
        _status.value = _status.value.copy(lastModeSwitchResult = "Pending 0x${mode.toString(16)}...")
        _cameraStatus.value = _cameraStatus.value.copy(detail = "Mode request 0x${mode.toString(16)}")
    }

    override suspend fun subscribeStatus() {
        if (!canSendProtocol(CommandIds.CMD_SET_CAMERA, CommandIds.CAMERA_STATUS_SUBSCRIPTION)) return
        registerPending(
            cmdSet = CommandIds.CMD_SET_CAMERA,
            cmdId = CommandIds.CAMERA_STATUS_SUBSCRIPTION,
            label = "Status subscription",
            onTimeout = {
                _status.value = _status.value.copy(
                    latestError = "Status subscription timed out",
                    lastSubscribeResult = "Timeout",
                )
            },
        )
        sendProtocol(CommandIds.CMD_SET_CAMERA, CommandIds.CAMERA_STATUS_SUBSCRIPTION, CameraStatusSubscriptionPayload(3, 20))
        _status.value = _status.value.copy(lastSubscribeResult = "Pending...")
        appendLog(LogCategory.STATE, "Status subscription requested.")
    }

    override suspend fun pushGps(latitude: Double, longitude: Double, altitudeMeters: Double) {
        lastGpsPoint = SessionGpsPoint(
            latitude = latitude,
            longitude = longitude,
            altitudeMeters = altitudeMeters,
            provider = "manual",
            fixTimeMillis = System.currentTimeMillis(),
        )
        sendGpsPayload(source = "manual")
    }

    override suspend fun setGpsAutoPushEnabled(enabled: Boolean) {
        gpsAutoPushEnabled = enabled
        _status.value = _status.value.copy(
            gpsAutoPushEnabled = enabled,
            gpsPushActive = if (enabled) _status.value.gpsPushActive else false,
            lastGpsResult = if (enabled) "GPS auto push enabled" else "GPS auto push disabled",
        )
        if (!enabled) {
            stopAutoGpsPush()
            return
        }
        if (_status.value.protocolReady && !_status.value.sleeping) {
            startAutoGpsPush()
        }
    }

    override suspend fun setGpsAutoPushFrequencyHz(hz: Int) {
        val normalizedHz = normalizeGpsFrequency(hz)
        gpsAutoPushHz = normalizedHz
        _status.value = _status.value.copy(
            gpsAutoPushHz = normalizedHz,
            lastGpsResult = "GPS frequency set to ${normalizedHz}Hz",
        )
        if (gpsAutoPushEnabled && _status.value.protocolReady && !_status.value.sleeping) {
            startAutoGpsPush()
        }
    }

    override suspend fun setGpsLocationRequestFrequencyHz(hz: Int) {
        val normalizedHz = normalizeLocationRequestFrequency(hz)
        gpsLocationRequestHz = normalizedHz
        gpsRequestIntervalUpdater?.invoke(locationRequestIntervalMs(normalizedHz))
        _status.value = _status.value.copy(
            gpsLocationRequestHz = normalizedHz,
            lastGpsResult = "GPS location request set to ${normalizedHz}Hz",
        )
    }

    override suspend fun sleep() {
        wakeObservationJob?.cancel()
        stopAutoGpsPush()
        sendProtocol(
            CommandIds.CMD_SET_COMMON,
            CommandIds.POWER_MODE,
            PowerModePayload(3),
            cmdType = CMD_RESPONSE_OR_NOT,
        )
        _status.value = _status.value.copy(
            sleeping = true,
            gpsPushActive = false,
            lastSleepResult = "Sent (001A may not ack before sleep)",
            lastWakeResult = null,
            latestError = null,
        )
        _cameraStatus.value = _cameraStatus.value.copy(
            powerMode = 3,
            powerModeLabel = "Sleeping",
            detail = "Sleeping",
        )
    }

    override suspend fun wake() {
        wakeObservationJob?.cancel()
        val payload = WakeAdvertisingPayload.build(parseMac(_status.value.connectedDevice?.macAddress ?: ""))
        bleClient.startWakeAdvertising(payload.copyOfRange(5, 11))
        appendLog(LogCategory.BLE, "Wake payload built.", payload.joinToString(" ") { "%02X".format(it) })
        _status.value = _status.value.copy(
            lastWakeResult = "Advertising sent; waiting for wake event",
            latestError = null,
        )
        wakeObservationJob = scope.launch {
            // The wake protocol has no explicit ACK. We infer success only from follow-up BLE/session signals.
            // On some Android ROMs, BLE advertising is platform-limited, so wake may remain best-effort.
            delay(WAKE_OBSERVATION_TIMEOUT_MS)
            if (_status.value.lastWakeResult == WAKE_PENDING_RESULT) {
                _status.value = _status.value.copy(
                    lastWakeResult = "No wake observed after 5s; Android wake advertising may be platform-limited",
                )
                appendLog(LogCategory.ERROR, "Wake timeout: no disconnect/reconnect or awake status observed.")
            }
        }
    }

    override suspend fun wakeViaGatt() {
        wakeObservationJob?.cancel()
        val device = _status.value.connectedDevice
        if (device == null) {
            val message = "GATT wake needs a known camera; scan and pick a device first."
            _status.value = _status.value.copy(latestError = message)
            appendLog(LogCategory.ERROR, message)
            return
        }
        _status.value = _status.value.copy(
            lastWakeResult = GATT_WAKE_PENDING_RESULT,
            latestError = null,
        )
        appendLog(LogCategory.BLE, "GATT wake started for ${device.macAddress}.")

        var attempt = 0
        var woke = false
        while (attempt < wakeMaxAttempts && !woke) {
            attempt += 1
            woke = runGattWakeAttempt(device, attempt)
            if (!woke && attempt < wakeMaxAttempts) {
                val backoffMs = wakeBackoffBaseMs * (1L shl (attempt - 1))
                appendLog(LogCategory.BLE, "GATT wake attempt $attempt/$wakeMaxAttempts failed; retrying in ${backoffMs}ms.")
                delay(backoffMs)
            }
        }

        if (woke) {
            _status.value = _status.value.copy(
                sleeping = false,
                lastWakeResult = "GATT wake OK: camera answered the wake command",
            )
            _cameraStatus.value = _cameraStatus.value.copy(
                powerMode = 0,
                powerModeLabel = "Awake",
                detail = "Awake via GATT wake",
            )
            appendLog(LogCategory.STATE, "GATT wake succeeded on attempt $attempt/$wakeMaxAttempts.")
        } else {
            _status.value = _status.value.copy(
                lastWakeResult = "GATT wake failed after $wakeMaxAttempts attempts; falling back to advertising wake",
            )
            appendLog(LogCategory.ERROR, "GATT wake failed; falling back to advertising wake.")
            wake()
        }
    }

    private suspend fun runGattWakeAttempt(device: SessionDevice, attempt: Int): Boolean {
        if (!bleClient.connectionState.value.isConnected) {
            appendLog(LogCategory.BLE, "GATT wake attempt $attempt: connecting to ${device.macAddress}.")
            val connected = runCatching { bleClient.connect(device.macAddress) }.isSuccess &&
                bleClient.connectionState.value.isConnected
            if (!connected) {
                appendLog(LogCategory.ERROR, "GATT wake attempt $attempt: GATT connect failed.")
                return false
            }
            lastTransportConnected = true
        }
        val pairingWaiter = CompletableDeferred<Int>()
        pairingResultDeferred = pairingWaiter
        val waiter = CompletableDeferred<Unit>()
        wakeReplyDeferred = waiter
        return try {
            // Mimo order (HCI snoop): session open first, pairing a beat later.
            writeDuml(GattWakeSequence.buildSessionOpenFrame(nextDumlMessageId()))
            delay(wakeWriteSpacingMs)
            val pairingIdentifier = pairingIdentifierProvider()
            suspend fun sendPairingPin() {
                writeDuml(GattWakeSequence.buildSetPairingPinFrame(nextDumlMessageId(), pairingIdentifier))
            }
            sendPairingPin()
            appendLog(LogCategory.BLE, "GATT wake attempt $attempt: pairing sent; waiting for camera.")
            // osmosis resends SetPairingPIN at +2.5s/+5s when the sleeping camera
            // hasn't answered yet; a single shot is often missed while it wakes.
            val pairingRetryJob = scope.launch {
                repeat(2) { index ->
                    delay(2_500L)
                    if (pairingWaiter.isCompleted) return@launch
                    appendLog(LogCategory.BLE, "GATT wake attempt $attempt: no pairing reply; resending SetPairingPIN (${index + 1}/2).")
                    runCatching { sendPairingPin() }
                }
            }
            val pairStatus = try {
                withTimeoutOrNull(pairingTimeoutMs) {
                    pairingWaiter.await()
                }
            } finally {
                pairingRetryJob.cancel()
            }
            if (pairStatus == null) {
                appendLog(LogCategory.ERROR, "GATT wake attempt $attempt: pairing timed out (no 0x07/0x45 reply).")
                return false
            }
            if (pairStatus != GattWakeSequence.PAIR_STATUS_ALREADY_PAIRED) {
                appendLog(LogCategory.ERROR, "GATT wake attempt $attempt: pairing not approved (status=0x${pairStatus.toString(16)}).")
                return false
            }
            // Pairing confirmed — start the ~1Hz keepalive, then send the wake.
            val keepaliveJob = scope.launch {
                while (isActive) {
                    delay(GATT_WAKE_KEEPALIVE_INTERVAL_MS)
                    runCatching { writeDuml(GattWakeSequence.buildSessionKeepaliveFrame(nextDumlMessageId())) }
                }
            }
            try {
                delay(wakeWriteSpacingMs)
                writeDuml(GattWakeSequence.buildWakeCameraFrame(nextDumlMessageId()))
                appendLog(LogCategory.BLE, "GATT wake attempt $attempt: wake sent; waiting for wake reply.")
                withTimeoutOrNull(wakeReplyTimeoutMs) {
                    waiter.await()
                    true
                } ?: false
            } finally {
                keepaliveJob.cancel()
            }
        } finally {
            if (pairingResultDeferred === pairingWaiter) {
                pairingResultDeferred = null
            }
            if (wakeReplyDeferred === waiter) {
                wakeReplyDeferred = null
            }
        }
    }

    private suspend fun writeDuml(frame: DumlFrame) {
        val bytes = DumlFrameCodec.encode(frame)
        bleClient.write(bytes)
        appendLog(
            LogCategory.TX,
            "DUML 0x${frame.cmdSet.toString(16)}:0x${frame.cmdId.toString(16)} sent.",
            bytes.joinToString(" ") { "%02X".format(it) },
        )
    }

    private fun nextDumlMessageId(): Int {
        dumlMessageId = (dumlMessageId + 1) and 0xFFFF
        return dumlMessageId
    }

    override suspend fun fetchWifiCredentials(): WifiCredentials? {
        if (!_status.value.protocolReady || _status.value.sleeping) {
            appendLog(LogCategory.BLE, "Wi-Fi creds: camera not connected/awake.")
            return null
        }
        val ssidWaiter = CompletableDeferred<String?>()
        val passWaiter = CompletableDeferred<String?>()
        wifiSsidDeferred = ssidWaiter
        wifiPasswordDeferred = passWaiter
        return try {
            writeDuml(GattWakeSequence.buildGetWifiSsidFrame(nextDumlMessageId()))
            delay(wakeWriteSpacingMs)
            writeDuml(GattWakeSequence.buildGetWifiPasswordFrame(nextDumlMessageId()))
            val deviceName = _status.value.connectedDevice?.name
            val ssid = withTimeoutOrNull(WIFI_CREDENTIALS_TIMEOUT_MS) { ssidWaiter.await() }
                ?.takeIf { it.isNotBlank() }
                ?: deviceName
            val password = withTimeoutOrNull(WIFI_CREDENTIALS_TIMEOUT_MS) { passWaiter.await() }
            if (ssid.isNullOrBlank() || password.isNullOrBlank()) {
                appendLog(
                    LogCategory.BLE,
                    "Wi-Fi creds incomplete (ssid=${ssid ?: "?"}, password=${if (password != null) "ok" else "?"}).",
                )
                return null
            }
            val wpa3 = ssid.contains("360", ignoreCase = true) ||
                (deviceName?.contains("360", ignoreCase = true) == true)
            appendLog(LogCategory.BLE, "Wi-Fi creds retrieved over BLE for \"$ssid\" (wpa3=$wpa3).")
            WifiCredentials(ssid = ssid, password = password, wpa3 = wpa3)
        } catch (e: Exception) {
            appendLog(LogCategory.ERROR, "Wi-Fi creds fetch failed: ${e.message}")
            null
        } finally {
            if (wifiSsidDeferred === ssidWaiter) wifiSsidDeferred = null
            if (wifiPasswordDeferred === passWaiter) wifiPasswordDeferred = null
        }
    }

    override suspend fun wakeAndSnapshot() {
        if (_status.value.protocolReady && !_status.value.sleeping) {
            reportRecordKeyClick()
            _status.value = _status.value.copy(lastWakeResult = "Capture sent without wake (already awake)")
            return
        }
        wake()
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < SNAPSHOT_FLOW_TIMEOUT_MS) {
            if (_status.value.protocolReady && !_status.value.sleeping) {
                reportSnapshotKeyClick()
                _status.value = _status.value.copy(lastWakeResult = "Wake+Snapshot flow completed")
                return
            }
            delay(100)
        }
        _status.value = _status.value.copy(
            latestError = "Wake+Snapshot flow timed out waiting reconnect",
            lastWakeResult = "Wake+Snapshot timeout",
        )
        appendLog(LogCategory.ERROR, "Wake+Snapshot flow timeout.")
    }

    override suspend fun sendManualCommand(hex: String) {
        if (_status.value.sleeping) {
            val message = "Camera is sleeping; blocking manual write until wake/reconnect."
            _status.value = _status.value.copy(latestError = message)
            appendLog(LogCategory.ERROR, message)
            return
        }
        bleClient.write(hexToBytes(hex))
        appendLog(LogCategory.TX, "Manual bytes written.", hex)
    }

    override suspend fun reportRecordKeyClick() {
        sendKeyReport(
            keyCode = KEY_CODE_RECORD,
            label = "Record key click",
        )
    }

    override suspend fun reportQsKeyClick() {
        sendKeyReport(
            keyCode = KEY_CODE_QS,
            label = "QS key click",
        )
    }

    override suspend fun reportSnapshotKeyClick() {
        sendKeyReport(
            keyCode = KEY_CODE_SNAPSHOT,
            label = "Snapshot key click",
        )
    }

    override suspend fun setHandshakeVerifyMode(mode: Int) {
        val normalizedMode = if (mode == 0) 0 else 1
        handshakeVerifyModeManuallySelected = true
        _status.value = _status.value.copy(handshakeVerifyMode = normalizedMode)
        appendLog(LogCategory.STATE, "Handshake verify_mode set to $normalizedMode")
    }

    private suspend fun sendKeyReport(keyCode: Int, label: String) {
        if (!canSendProtocol(CommandIds.CMD_SET_COMMON, CommandIds.KEY_REPORT)) return
        registerPending(
            cmdSet = CommandIds.CMD_SET_COMMON,
            cmdId = CommandIds.KEY_REPORT,
            label = label,
            onTimeout = {
                _status.value = _status.value.copy(
                    latestError = "$label timed out",
                    lastKeyReportResult = "Timeout",
                )
            },
        )
        sendProtocol(
            CommandIds.CMD_SET_COMMON,
            CommandIds.KEY_REPORT,
            KeyReportPayload(
                keyCode = keyCode,
                mode = KEY_REPORT_MODE_EVENT,
                keyValue = KEY_REPORT_VALUE_SINGLE_CLICK,
            ),
            cmdType = CMD_WAIT_RESULT,
        )
        _status.value = _status.value.copy(lastKeyReportResult = "Pending...")
    }

    private suspend fun sendRecordControlFallback(start: Boolean, reason: String) {
        recordToggleByKeyInFlight = false
        if (!canSendProtocol(CommandIds.CMD_SET_CAMERA, CommandIds.RECORD_CONTROL)) return
        registerPending(
            cmdSet = CommandIds.CMD_SET_CAMERA,
            cmdId = CommandIds.RECORD_CONTROL,
            label = if (start) "Start recording fallback" else "Stop recording fallback",
            onTimeout = {
                _cameraStatus.value = _cameraStatus.value.copy(detail = "Record fallback timed out")
                _status.value = _status.value.copy(
                    latestError = "Record fallback timed out",
                    lastRecordResult = "Timeout ($reason)",
                )
            },
        )
        sendProtocol(
            CommandIds.CMD_SET_CAMERA,
            CommandIds.RECORD_CONTROL,
            RecordControlPayload(connectedDeviceId(), if (start) 0 else 1),
        )
        _status.value = _status.value.copy(
            lastRecordResult = if (start) "Fallback start sent ($reason)" else "Fallback stop sent ($reason)",
        )
        _cameraStatus.value = _cameraStatus.value.copy(detail = if (start) "Fallback start requested" else "Fallback stop requested")
    }

    private suspend fun handleRecordToggleKeyFailure(reason: String, failureLabel: String) {
        if (isPhotoLikeCaptureMode(_cameraStatus.value.mode)) {
            recordToggleByKeyInFlight = false
            val message = "$failureLabel; skipping 1D03 fallback in photo mode to avoid duplicate capture."
            _status.value = _status.value.copy(
                latestError = message,
                lastRecordResult = "Key click failed ($reason)",
                lastKeyReportResult = if ("timeout" in failureLabel.lowercase()) "Timeout" else "Error",
            )
            _cameraStatus.value = _cameraStatus.value.copy(detail = "Capture key failed")
            appendLog(LogCategory.ERROR, message)
            return
        }
        appendLog(LogCategory.ERROR, "$failureLabel; fallback to 1D03.")
        sendRecordControlFallback(lastRecordToggleTargetStart, reason = reason)
    }

    private suspend fun sendGpsPayload(source: String) {
        if (!canSendProtocol(CommandIds.CMD_SET_COMMON, CommandIds.GPS_PUSH)) return
        val currentPoint = gpsPointProvider?.invoke()
        if (currentPoint != null) lastGpsPoint = currentPoint
        val point = lastGpsPoint
        if (point == null) {
            _status.value = _status.value.copy(
                gpsPushActive = false,
                gpsSignalLocked = false,
                lastGpsResult = "Skipped ($source, no usable location fix)",
                latestError = "No usable GPS fix available on the phone",
            )
            return
        }
        val signalLocked = currentPoint != null
        val coordinateText = "${"%.6f".format(point.latitude)}, ${"%.6f".format(point.longitude)}"
        val sentAtMs = System.currentTimeMillis()
        val nowUtc = OffsetDateTime.now(java.time.ZoneOffset.UTC)
        val (speedNorthCmps, speedEastCmps) = gpsSpeedComponents(point)
        sendProtocol(
            CommandIds.CMD_SET_COMMON,
            CommandIds.GPS_PUSH,
            GpsDataPayload(
                yearMonthDay = nowUtc.year * 10_000 + nowUtc.monthValue * 100 + nowUtc.dayOfMonth,
                hourMinuteSecond = (nowUtc.hour + 8) * 10_000 + nowUtc.minute * 100 + nowUtc.second,
                longitudeE7 = (point.longitude * 10_000_000).toInt(),
                latitudeE7 = (point.latitude * 10_000_000).toInt(),
                heightMm = (point.altitudeMeters * 1000).toInt(),
                speedNorthCmps = speedNorthCmps,
                speedEastCmps = speedEastCmps,
                speedDownCmps = 0f,
                verticalAccuracyMm = 800,
                horizontalAccuracyMm = 600,
                speedAccuracyCmps = 40,
                satelliteCount = 12,
            ),
            cmdType = CMD_NO_RESPONSE,
        )
        _status.value = _status.value.copy(
            gpsPushActive = true,
            gpsSignalLocked = signalLocked,
            lastGpsCoordinate = coordinateText,
            lastGpsAltitudeMeters = point.altitudeMeters,
            lastGpsSpeedMps = point.speedMps,
            lastGpsBearingDegrees = point.bearingDegrees,
            lastGpsAccuracyMeters = point.horizontalAccuracyMeters,
            lastGpsProvider = point.provider,
            lastGpsSource = source,
            lastGpsSentAtMs = sentAtMs,
            lastGpsResult = if (signalLocked) {
                "Sent ($source, live phone fix)"
            } else {
                "Sent ($source, cached phone fix)"
            },
            latestError = null,
        )
    }

    private fun normalizeGpsFrequency(hz: Int): Int {
        return when (hz) {
            1, 2, 5, 10 -> hz
            else -> DEFAULT_AUTO_GPS_HZ
        }
    }

    private fun gpsSpeedComponents(point: SessionGpsPoint): Pair<Float, Float> {
        val speedMps = point.speedMps ?: return 0f to 0f
        val bearingDegrees = point.bearingDegrees ?: return 0f to 0f
        val bearingRadians = Math.toRadians(bearingDegrees.toDouble())
        val speedCmps = speedMps * 100f
        val north = (speedCmps * cos(bearingRadians)).toFloat()
        val east = (speedCmps * sin(bearingRadians)).toFloat()
        return north to east
    }

    private fun normalizeLocationRequestFrequency(hz: Int): Int {
        return when (hz) {
            1, 2, 5 -> hz
            else -> DEFAULT_LOCATION_REQUEST_HZ
        }
    }

    private fun gpsPushIntervalMs(hz: Int): Long {
        return 1_000L / normalizeGpsFrequency(hz)
    }

    private fun locationRequestIntervalMs(hz: Int): Long {
        return 1_000L / normalizeLocationRequestFrequency(hz)
    }

    private fun canSendProtocol(cmdSet: Int, cmdId: Int): Boolean {
        if (!_status.value.sleeping) return true
        val allowDuringSleep = cmdSet == CommandIds.CMD_SET_COMMON && cmdId == CommandIds.POWER_MODE
        if (allowDuringSleep) return true
        val message = "Camera is sleeping; protocol command ${cmdSet.toString(16)}:${cmdId.toString(16)} blocked."
        _status.value = _status.value.copy(latestError = message)
        appendLog(LogCategory.ERROR, message)
        return false
    }

    private suspend fun sendProtocol(
        cmdSet: Int,
        cmdId: Int,
        payload: com.alliot.osmo.demo.protocol.payload.ProtocolPayload,
        cmdType: Int = CMD_RESPONSE_OR_NOT,
        seqOverride: Int? = null,
    ) {
        val frame = DjiFrame(
            cmdType = cmdType,
            seq = seqOverride ?: sequence++,
            cmdSet = cmdSet,
            cmdId = cmdId,
            payload = PayloadCodec.encode(cmdSet, cmdId, payload).drop(2).toByteArray(),
        )
        val bytes = DjiFrameCodec.encode(frame)
        bleClient.write(bytes)
        appendLog(LogCategory.TX, "Protocol bytes sent.", bytes.joinToString(" ") { "%02X".format(it) })
    }

    private suspend fun handleBleEvent(event: BleEvent) {
        when (event) {
            is BleEvent.Connected -> {
                appendLog(LogCategory.BLE, "Connected to ${event.macAddress}")
                _status.value = _status.value.copy(
                    latestError = null,
                    lastWakeResult = if (_status.value.lastWakeResult == WAKE_PENDING_RESULT || _status.value.lastWakeResult == WAKE_DISCONNECT_RESULT) {
                        "Wake observed: BLE connected"
                    } else {
                        _status.value.lastWakeResult
                    },
                )
            }
            is BleEvent.Disconnected -> {
                appendLog(LogCategory.BLE, "Disconnected from ${event.macAddress ?: "unknown"}${event.reason?.let { " ($it)" } ?: ""}")
                handleTransportDisconnected(reason = event.reason, fallbackMacAddress = event.macAddress)
            }
            is BleEvent.Error -> {
                appendLog(LogCategory.ERROR, event.message)
                _status.value = _status.value.copy(latestError = event.message)
            }
            is BleEvent.Notification -> {
                handleNotification(event.bytes)
            }
            is BleEvent.ScanStarted -> appendLog(LogCategory.BLE, "Scan started")
            is BleEvent.ScanStopped -> appendLog(LogCategory.BLE, "Scan stopped")
            is BleEvent.Write -> appendLog(LogCategory.TX, "Transport write.", event.bytes.joinToString(" ") { "%02X".format(it) })
            is BleEvent.BringUp -> appendLog(LogCategory.BLE, "Bring-up: ${event.stage}")
        }
    }

    private suspend fun handleNotification(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        if (bytes[0] == 0x55.toByte() || dumlRxBuffer.isNotEmpty()) {
            // A pending DUML fragment means this chunk is a continuation
            // even when it no longer starts with 0x55.
            if (bytes[0] == 0x55.toByte()) protocolRxBuffer = ByteArray(0)
            handleDumlNotification(bytes)
            return
        }
        if (bytes[0] != 0xAA.toByte() && protocolRxBuffer.isEmpty()) {
            appendLog(LogCategory.ERROR, "Unexpected notification prefix.", bytes.joinToString(" ") { "%02X".format(it) })
            return
        }
        protocolRxBuffer += bytes
        drainProtocolFrames()
    }

    private suspend fun handleDumlNotification(bytes: ByteArray) {
        dumlRxBuffer += bytes
        while (dumlRxBuffer.size >= DumlConstants.MIN_FRAME_SIZE) {
            if (dumlRxBuffer[0] != 0x55.toByte()) {
                dumlRxBuffer = dumlRxBuffer.drop(1).toByteArray()
                continue
            }
            val totalLength = (dumlRxBuffer[1].toInt() and 0xFF) or ((dumlRxBuffer[2].toInt() and 0x03) shl 8)
            if (totalLength < DumlConstants.MIN_FRAME_SIZE) {
                dumlRxBuffer = dumlRxBuffer.drop(1).toByteArray()
                continue
            }
            if (dumlRxBuffer.size < totalLength) return
            val frameBytes = dumlRxBuffer.copyOfRange(0, totalLength)
            dumlRxBuffer = dumlRxBuffer.copyOfRange(totalLength, dumlRxBuffer.size)
            val decoded = runCatching { DumlFrameCodec.decode(frameBytes) }
            if (decoded.isFailure) {
                if (!oa5NoiseHintShown) {
                    oa5NoiseHintShown = true
                    appendLog(LogCategory.BLE, "OA5 background notify detected; undecodable 0x55 frames will be ignored.")
                }
                continue
            }
            val frame = decoded.getOrThrow()
            appendLog(
                LogCategory.RX,
                "DUML frame 0x${frame.cmdSet.toString(16)}:0x${frame.cmdId.toString(16)} received.",
                frameBytes.joinToString(" ") { "%02X".format(it) },
            )
            if (GattWakeSequence.isWakeReply(frame)) {
                wakeReplyDeferred?.complete(Unit)
            }
            if (GattWakeSequence.isPairingStatusFrame(frame)) {
                val status = GattWakeSequence.pairingStatus(frame)
                val meaning = when (status) {
                    GattWakeSequence.PAIR_STATUS_ALREADY_PAIRED -> "already paired"
                    GattWakeSequence.PAIR_STATUS_APPROVAL_REQUIRED -> "approval required — confirm pairing on the camera"
                    else -> "status=0x${status.toString(16)}"
                }
                appendLog(LogCategory.BLE, "Pairing status: $meaning.")
                if (status == GattWakeSequence.PAIR_STATUS_APPROVAL_REQUIRED) {
                    // Approval arrives later as a 0x07/0x46 request; keep waiting for it.
                    _status.value = _status.value.copy(
                        lastWakeResult = "请在相机屏幕上确认配对",
                    )
                } else {
                    pairingResultDeferred?.complete(status)
                }
            }
            if (GattWakeSequence.isPairingApprovalFrame(frame)) {
                appendLog(LogCategory.BLE, "Pairing approved by camera.")
                pairingResultDeferred?.complete(GattWakeSequence.PAIR_STATUS_ALREADY_PAIRED)
            }
            if (GattWakeSequence.isWifiSsidReply(frame)) {
                val ssid = GattWakeSequence.wifiCredentialValue(frame)
                appendLog(LogCategory.BLE, "Wi-Fi SSID reply: ${ssid ?: "<none>"}.")
                wifiSsidDeferred?.complete(ssid)
            }
            if (GattWakeSequence.isWifiPasswordReply(frame)) {
                val password = GattWakeSequence.wifiCredentialValue(frame)
                appendLog(
                    LogCategory.BLE,
                    "Wi-Fi password reply: ${if (password != null) "${password.length} chars" else "<none>"}.",
                )
                wifiPasswordDeferred?.complete(password)
            }
            if (frame.flags == DumlFlags.REQUEST) {
                // The camera drops the link (~6s) when its requests go unanswered.
                runCatching {
                    val ack = GattWakeSequence.buildAckFrame(frame)
                    val bytes = DumlFrameCodec.encode(ack)
                    bleClient.write(bytes)
                    appendLog(
                        LogCategory.TX,
                        "ACK 0x${frame.cmdSet.toString(16)}:0x${frame.cmdId.toString(16)} sent.",
                        bytes.joinToString(" ") { "%02X".format(it) },
                    )
                }.onFailure { error ->
                    appendLog(LogCategory.ERROR, "Failed to ACK 0x${frame.cmdSet.toString(16)}:0x${frame.cmdId.toString(16)}: ${error.message}")
                }
            }
        }
    }

    private suspend fun drainProtocolFrames() {
        while (protocolRxBuffer.isNotEmpty()) {
            if (protocolRxBuffer[0] != 0xAA.toByte()) {
                val nextFrameIndex = protocolRxBuffer.indexOfFirst { it == 0xAA.toByte() }
                if (nextFrameIndex < 0) {
                    appendLog(LogCategory.ERROR, "Dropped non-protocol fragment while awaiting frame completion.")
                    protocolRxBuffer = ByteArray(0)
                    return
                }
                appendLog(LogCategory.ERROR, "Discarded $nextFrameIndex stray byte(s) before next protocol frame.")
                protocolRxBuffer = protocolRxBuffer.copyOfRange(nextFrameIndex, protocolRxBuffer.size)
            }
            if (protocolRxBuffer.size < MIN_PROTOCOL_HEADER_BYTES) {
                return
            }
            val expectedLength = ByteOrder.u16(protocolRxBuffer, 1) and 0x03FF
            if (expectedLength < MIN_PROTOCOL_FRAME_BYTES) {
                appendLog(LogCategory.ERROR, "Invalid protocol frame length: $expectedLength")
                protocolRxBuffer = protocolRxBuffer.drop(1).toByteArray()
                continue
            }
            if (protocolRxBuffer.size < expectedLength) {
                appendLog(
                    LogCategory.RX,
                    "Protocol fragment received (${protocolRxBuffer.size}/$expectedLength).",
                    protocolRxBuffer.joinToString(" ") { "%02X".format(it) },
                )
                return
            }
            val frameBytes = protocolRxBuffer.copyOfRange(0, expectedLength)
            protocolRxBuffer = protocolRxBuffer.copyOfRange(expectedLength, protocolRxBuffer.size)
            appendLog(LogCategory.RX, "Protocol frame assembled.", frameBytes.joinToString(" ") { "%02X".format(it) })
            val decodedFrameResult = runCatching { DjiFrameCodec.decode(frameBytes) }
            if (decodedFrameResult.isFailure) {
                appendLog(LogCategory.ERROR, "Frame decode failed: ${decodedFrameResult.exceptionOrNull()?.message}")
                continue
            }
            val decodedFrame = decodedFrameResult.getOrThrow()
            handleDecodedFrame(decodedFrame)
        }
    }

    private suspend fun handleDecodedFrame(decodedFrame: com.alliot.osmo.demo.protocol.frame.DecodedDjiFrame) {
        val payload = PayloadCodec.decode(
            decodedFrame.frame.cmdSet,
            decodedFrame.frame.cmdId,
            decodedFrame.frame.payload,
            isCommandFrame = (decodedFrame.frame.cmdType and 0x20) == 0,
        )
        when (payload) {
            is CameraConnectionConfirmationPayload -> handleCameraConnectionConfirmation(decodedFrame.frame.seq, payload)
            is ConnectionResponsePayload -> handleConnectionResponse(payload)
            is CameraStatusPayload -> {
                resolvePending(CommandIds.CMD_SET_CAMERA, CommandIds.CAMERA_STATUS_SUBSCRIPTION, "Status stream active")
                resolvePending(CommandIds.CMD_SET_CAMERA, CommandIds.RECORD_CONTROL, "Record state confirmed")
                resolvePending(CommandIds.CMD_SET_CAMERA, CommandIds.CAMERA_MODE_SWITCH, "Mode switch confirmed")
                resolvePending(CommandIds.CMD_SET_COMMON, CommandIds.POWER_MODE, "Power mode confirmed")
                applyCameraStatus(payload)
            }
            is NewCameraStatusPayload -> {
                resolvePending(CommandIds.CMD_SET_CAMERA, CommandIds.CAMERA_MODE_SWITCH, "Mode metadata updated")
                _cameraStatus.value = _cameraStatus.value.copy(
                    modeName = payload.modeName,
                    modeParameters = payload.modeParameters,
                    detail = payload.modeName.ifBlank { _cameraStatus.value.detail },
                    lastPushCommandId = "1D06",
                    lastPushSummary = buildModeMetadataSummary(payload),
                )
            }
            is VersionResponsePayload -> {
                resolvePending(CommandIds.CMD_SET_COMMON, CommandIds.VERSION_QUERY, "Version response received")
                _status.value = _status.value.copy(
                    latestVersion = listOf(payload.productId, payload.sdkVersion).filter { it.isNotBlank() }.joinToString(" / "),
                    lastVersionResult = "OK: ack=${payload.ackResult}",
                    connectedProfile = resolveConnectedProfile(
                        device = _status.value.connectedDevice,
                        handshakeDeviceId = _status.value.connectedDevice?.deviceId,
                        productId = payload.productId,
                    ),
                )
            }
            is RebootResponsePayload -> {
                resolvePending(
                    CommandIds.CMD_SET_COMMON,
                    CommandIds.REBOOT,
                    successMessage = if (payload.retCode == 0) "Reboot accepted" else null,
                    failureMessage = if (payload.retCode != 0) "Reboot rejected ${payload.retCode}" else null,
                )
                _status.value = _status.value.copy(
                    lastRebootResult = if (payload.retCode == 0) {
                        "OK: reboot accepted"
                    } else {
                        "Error: reboot ret=${payload.retCode}"
                    },
                )
            }
            is AckPayload -> applyAck(decodedFrame.frame.cmdSet, decodedFrame.frame.cmdId, payload)
            else -> appendLog(LogCategory.STATE, "Unhandled payload: $payload")
        }
    }

    private suspend fun handleCameraConnectionConfirmation(incomingSeq: Int, payload: CameraConnectionConfirmationPayload) {
        if (!_status.value.protocolReady) {
            _status.value = _status.value.copy(handshakeStage = HandshakeStage.CAMERA_CONFIRMATION_RECEIVED)
        }
        appendLog(LogCategory.STATE, "Camera confirmation verify_mode=${payload.verifyMode} verify_data=${payload.verifyData}")
        if (payload.verifyMode == 2 && payload.verifyData == 0) {
            if (_status.value.protocolReady) {
                appendLog(LogCategory.STATE, "Duplicate camera confirmation ignored after protocol ready.")
                return
            }
            reconnectAttempts = 0
            reconnectJob?.cancel()
            clearPendingCommands()
            disconnectRecoveryInProgress = false
            sendProtocol(
                CommandIds.CMD_SET_COMMON,
                CommandIds.CONNECTION,
                ConnectionResponsePayload(deviceId = controllerDeviceId, retCode = 0, cameraIndex = 0),
                cmdType = ACK_NO_RESPONSE,
                seqOverride = incomingSeq,
            )
            _status.value = _status.value.copy(
                connectedDevice = _status.value.connectedDevice?.copy(deviceId = payload.deviceId),
                connectedProfile = resolveConnectedProfile(
                    device = _status.value.connectedDevice?.copy(deviceId = payload.deviceId),
                    handshakeDeviceId = payload.deviceId,
                    productId = _status.value.connectedProfile?.productId,
                ),
                handshakeStage = HandshakeStage.COMPLETED,
                protocolReady = true,
                lastWakeResult = if (_status.value.lastWakeResult == WAKE_PENDING_RESULT || _status.value.lastWakeResult == WAKE_DISCONNECT_RESULT || _status.value.lastWakeResult == "Wake observed: BLE connected") {
                    "Wake observed: protocol connected"
                } else {
                    _status.value.lastWakeResult
                },
                latestError = null,
            )
            _status.value.connectedDevice?.macAddress?.let(onDevicePaired)
            _cameraStatus.value = _cameraStatus.value.copy(detail = "Protocol connected")
            startStatusWatchdog()
            startPostConnectBootstrap()
        } else if (payload.verifyMode == 2 && payload.verifyData != 0) {
            _status.value.connectedDevice?.macAddress?.let { rejectedDevicesInCurrentScan += it }
            stopStatusWatchdog()
            stopAutoGpsPush()
            _status.value = _status.value.copy(
                handshakeStage = HandshakeStage.REJECTED,
                protocolReady = false,
                latestError = "Camera rejected pairing",
                gpsPushActive = false,
            )
            _cameraStatus.value = _cameraStatus.value.copy(detail = "Camera rejected pairing")
            preserveRejectedStateOnDisconnect = true
            manualDisconnectRequested = true
            runCatching { bleClient.disconnect() }
                .onFailure { error ->
                    appendLog(LogCategory.ERROR, "Failed to disconnect after rejection: ${error.message}")
                }
        }
    }

    private fun handleTransportDisconnected(reason: String?, fallbackMacAddress: String?) {
        if (disconnectRecoveryInProgress) return
        val currentDevice = _status.value.connectedDevice
        val effectiveReason = staleStatusRecoveryReason ?: reason
        staleStatusRecoveryPending = false
        staleStatusRecoveryReason = null
        disconnectRecoveryInProgress = true
        if (manualDisconnectRequested || currentDevice == null) {
            manualDisconnectRequested = false
            reconnectAttempts = 0
            reconnectJob?.cancel()
            clearPendingCommands()
            stopStatusWatchdog()
            stopAutoGpsPush()
            if (preserveRejectedStateOnDisconnect) {
                preserveRejectedStateOnDisconnect = false
                _status.value = _status.value.copy(
                    connectedDevice = null,
                    connectedProfile = null,
                    latestError = _status.value.latestError ?: effectiveReason,
                )
                _cameraStatus.value = _cameraStatus.value.copy(detail = "Pairing rejected")
            } else {
                _status.value = SessionStatus(
                    mode = SessionTransportMode.REAL,
                    controllerDeviceId = controllerDeviceId,
                    controllerMacAddress = formatMac(localControllerMac),
                    handshakeVerifyMode = _status.value.handshakeVerifyMode,
                    gpsAutoPushEnabled = gpsAutoPushEnabled,
                    gpsAutoPushHz = gpsAutoPushHz,
                    gpsLocationRequestHz = gpsLocationRequestHz,
                    latestError = effectiveReason,
                )
                _cameraStatus.value = CameraStatusSnapshot(detail = "Disconnected")
            }
            return
        }
        if (_status.value.lastWakeResult == WAKE_PENDING_RESULT) {
            _status.value = _status.value.copy(lastWakeResult = WAKE_DISCONNECT_RESULT)
            appendLog(LogCategory.STATE, "Wake observed: BLE disconnected, waiting to reconnect.")
        }
        val deviceForRecovery = fallbackMacAddress
            ?.takeIf { currentDevice.macAddress != it }
            ?.let { currentDevice.copy(macAddress = it) }
            ?: currentDevice
        handleUnexpectedDisconnect(deviceForRecovery, effectiveReason)
    }

    private fun startPostConnectBootstrap() {
        if (postConnectBootstrapStarted) return
        postConnectBootstrapStarted = true
        scope.launch {
            appendLog(LogCategory.STATE, "Post-connect bootstrap started.")
            runCatching { requestVersion() }
                .onFailure { error ->
                    appendLog(LogCategory.ERROR, "Auto version query failed: ${error.message}")
                }
            runCatching { subscribeStatus() }
                .onFailure { error ->
                    appendLog(LogCategory.ERROR, "Auto status subscription failed: ${error.message}")
                }
            if (gpsAutoPushEnabled) {
                runCatching { startAutoGpsPush() }
                    .onFailure { error ->
                        appendLog(LogCategory.ERROR, "Auto GPS push bootstrap failed: ${error.message}")
                    }
            }
        }
    }

    private fun handleConnectionResponse(payload: ConnectionResponsePayload) {
        if (payload.retCode != 0) {
            _status.value = _status.value.copy(latestError = "Connection request rejected with code ${payload.retCode}")
        }
    }

    private fun handleUnexpectedDisconnect(device: SessionDevice, reason: String?) {
        reconnectJob?.cancel()
        clearPendingCommands()
        stopStatusWatchdog()
        stopAutoGpsPush()
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            _status.value = SessionStatus(
                mode = SessionTransportMode.REAL,
                connectedDevice = device,
                controllerDeviceId = controllerDeviceId,
                controllerMacAddress = formatMac(localControllerMac),
                handshakeVerifyMode = _status.value.handshakeVerifyMode,
                gpsAutoPushEnabled = gpsAutoPushEnabled,
                gpsAutoPushHz = gpsAutoPushHz,
                gpsLocationRequestHz = gpsLocationRequestHz,
                latestError = "Reconnect failed${reason?.let { ": $it" } ?: ""}",
            )
            _cameraStatus.value = CameraStatusSnapshot(detail = "Reconnect failed")
            appendLog(LogCategory.ERROR, "Reconnect budget exhausted for ${device.macAddress}")
            return
        }
        reconnectAttempts += 1
        _status.value = _status.value.copy(
            connectedDevice = device,
            connectedProfile = _status.value.connectedProfile ?: resolveConnectedProfile(device),
            protocolReady = false,
            sleeping = false,
            handshakeStage = HandshakeStage.IDLE,
            controllerDeviceId = controllerDeviceId,
            controllerMacAddress = formatMac(localControllerMac),
            latestError = "Reconnecting (${reconnectAttempts}/$MAX_RECONNECT_ATTEMPTS)${reason?.let { ": $it" } ?: ""}",
        )
        _cameraStatus.value = _cameraStatus.value.copy(detail = "Reconnecting to ${device.name}")
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            runCatching { connect(device) }
                .onFailure { error ->
                    appendLog(LogCategory.ERROR, "Reconnect attempt failed: ${error.message}")
                    handleUnexpectedDisconnect(device, error.message)
                }
        }
    }

    private fun effectiveHandshakeVerifyMode(macAddress: String): Int {
        if (handshakeVerifyModeManuallySelected) {
            return _status.value.handshakeVerifyMode
        }
        return if (isKnownPairedDevice(macAddress)) 0 else 1
    }

    private fun startAutoGpsPush() {
        if (!gpsAutoPushEnabled) return
        autoGpsJob?.cancel()
        autoGpsJob = scope.launch {
            while (_status.value.protocolReady && !_status.value.sleeping) {
                sendGpsPayload(source = "auto-${gpsAutoPushHz}Hz")
                delay(gpsPushIntervalMs(gpsAutoPushHz))
            }
        }
    }

    private fun stopAutoGpsPush() {
        autoGpsJob?.cancel()
        autoGpsJob = null
        if (_status.value.gpsPushActive) {
            _status.value = _status.value.copy(gpsPushActive = false)
        }
    }

    private fun startStatusWatchdog() {
        statusWatchdogJob?.cancel()
        lastCameraStatusPushAtMs = System.currentTimeMillis()
        statusProbeSentAtMs = null
        staleStatusRecoveryPending = false
        staleStatusRecoveryReason = null
        statusWatchdogJob = scope.launch {
            while (true) {
                delay(statusWatchdogPollIntervalMs)
                val snapshot = _status.value
                if (!snapshot.protocolReady || snapshot.sleeping || snapshot.connectedDevice == null) {
                    statusProbeSentAtMs = null
                    continue
                }
                val now = System.currentTimeMillis()
                val lastStatusAt = lastCameraStatusPushAtMs ?: now.also { lastCameraStatusPushAtMs = it }
                val probeSentAt = statusProbeSentAtMs
                if (probeSentAt == null) {
                    if (now - lastStatusAt >= statusPushIdleTimeoutMs) {
                        sendStatusProbe()
                    }
                } else if (now - probeSentAt >= statusProbeTimeoutMs) {
                    requestStaleStatusRecovery("Camera status stream timed out")
                }
            }
        }
    }

    private fun stopStatusWatchdog() {
        statusWatchdogJob?.cancel()
        statusWatchdogJob = null
        lastCameraStatusPushAtMs = null
        statusProbeSentAtMs = null
    }

    private suspend fun sendStatusProbe() {
        if (statusProbeSentAtMs != null || staleStatusRecoveryPending) return
        statusProbeSentAtMs = System.currentTimeMillis()
        appendLog(LogCategory.STATE, "Camera status watchdog probing stream.")
        runCatching {
            sendProtocol(
                CommandIds.CMD_SET_CAMERA,
                CommandIds.CAMERA_STATUS_SUBSCRIPTION,
                CameraStatusSubscriptionPayload(pushMode = 3, pushFreq = 20),
            )
        }.onFailure { error ->
            requestStaleStatusRecovery("Camera status probe failed: ${error.message}")
        }
    }

    private fun requestStaleStatusRecovery(reason: String) {
        if (staleStatusRecoveryPending || disconnectRecoveryInProgress || _status.value.connectedDevice == null) return
        staleStatusRecoveryPending = true
        staleStatusRecoveryReason = reason
        appendLog(LogCategory.ERROR, reason)
        stopStatusWatchdog()
        scope.launch {
            runCatching { bleClient.disconnect() }
                .onFailure { error ->
                    staleStatusRecoveryPending = false
                    staleStatusRecoveryReason = null
                    appendLog(LogCategory.ERROR, "Failed to force BLE disconnect after stale status: ${error.message}")
                    handleTransportDisconnected(
                        reason = reason,
                        fallbackMacAddress = _status.value.connectedDevice?.macAddress,
                    )
                }
        }
    }

    private fun applyCameraStatus(payload: CameraStatusPayload) {
        lastCameraStatusPushAtMs = System.currentTimeMillis()
        statusProbeSentAtMs = null
        staleStatusRecoveryPending = false
        staleStatusRecoveryReason = null
        val modeLabel = cameraModeLabel(payload.cameraMode)
        val stateLabel = cameraStateLabel(payload.cameraStatus)
        val powerModeLabel = if (payload.powerMode == 3) "Sleeping" else "Awake"
        _cameraStatus.value = _cameraStatus.value.copy(
            mode = payload.cameraMode,
            state = payload.cameraStatus,
            modeLabel = modeLabel,
            stateLabel = stateLabel,
            recording = payload.cameraStatus == 0x03 || payload.cameraStatus == 0x05,
            videoResolution = payload.videoResolution,
            fpsIndex = payload.fpsIndex,
            eisMode = payload.eisMode,
            recordTimeSeconds = payload.recordTimeSeconds,
            photoRatio = payload.photoRatio,
            realTimeCountdownSeconds = payload.realTimeCountdownSeconds,
            timelapseIntervalDeciSeconds = payload.timelapseIntervalDeciSeconds,
            timelapseDurationSeconds = payload.timelapseDurationSeconds,
            powerMode = payload.powerMode,
            powerModeLabel = powerModeLabel,
            batteryPercent = payload.batteryPercent,
            remainCapacityMb = payload.remainCapacityMb,
            remainPhotoCount = payload.remainPhotoCount,
            remainTimeSeconds = payload.remainTimeSeconds,
            userMode = payload.userMode,
            cameraModeNextFlag = payload.cameraModeNextFlag,
            temperatureState = payload.temperatureState,
            photoCountdownMilliseconds = payload.photoCountdownMilliseconds,
            loopRecordSeconds = payload.loopRecordSeconds,
            detail = "$modeLabel / $stateLabel",
            lastPushCommandId = "1D02",
            lastPushSummary = buildCameraStatusSummary(payload),
        )
        _status.value = _status.value.copy(
            sleeping = payload.powerMode == 3,
            gpsAutoPushEnabled = gpsAutoPushEnabled,
            gpsAutoPushHz = gpsAutoPushHz,
            gpsLocationRequestHz = gpsLocationRequestHz,
            lastWakeResult = if (payload.powerMode == 0 && (_status.value.lastWakeResult == WAKE_PENDING_RESULT || _status.value.lastWakeResult == WAKE_DISCONNECT_RESULT || _status.value.lastWakeResult == "Wake observed: BLE connected")) {
                "Wake observed: camera reported awake"
            } else {
                _status.value.lastWakeResult
            },
        )
        if (payload.powerMode == 3) {
            stopAutoGpsPush()
        } else if (_status.value.protocolReady && autoGpsJob == null && gpsAutoPushEnabled) {
            startAutoGpsPush()
        }
    }

    private fun applyAck(cmdSet: Int, cmdId: Int, payload: AckPayload) {
        if (payload.retCode == 0) {
            when {
                cmdSet == CommandIds.CMD_SET_COMMON && cmdId == CommandIds.POWER_MODE -> {
                    _status.value = _status.value.copy(
                        sleeping = true,
                        lastSleepResult = "OK: sleep acknowledged",
                    )
                    _cameraStatus.value = _cameraStatus.value.copy(
                        powerMode = 3,
                        powerModeLabel = "Sleeping",
                        detail = "Sleeping",
                    )
                    stopAutoGpsPush()
                }
                cmdSet == CommandIds.CMD_SET_COMMON && cmdId == CommandIds.KEY_REPORT -> {
                    _status.value = _status.value.copy(
                        lastKeyReportResult = "OK: key report acknowledged",
                        lastRecordResult = if (recordToggleByKeyInFlight) "OK: record toggled via key click" else _status.value.lastRecordResult,
                    )
                    recordToggleByKeyInFlight = false
                }
            }
        } else if (cmdSet == CommandIds.CMD_SET_COMMON && cmdId == CommandIds.KEY_REPORT && recordToggleByKeyInFlight) {
            scope.launch {
                handleRecordToggleKeyFailure(
                    reason = "0011 ack error ${payload.retCode}",
                    failureLabel = "Record key report ack failed",
                )
            }
        }
        resolvePending(
            cmdSet = cmdSet,
            cmdId = cmdId,
            successMessage = if (payload.retCode == 0) "Ack ok" else null,
            failureMessage = if (payload.retCode != 0) "Ack error ${payload.retCode}" else null,
        )
        val message = if (payload.retCode == 0) {
            "Ack ok for ${cmdSet.toString(16)}:${cmdId.toString(16)}"
        } else {
            "Ack error ${payload.retCode} for ${cmdSet.toString(16)}:${cmdId.toString(16)}"
        }
        appendLog(if (payload.retCode == 0) LogCategory.STATE else LogCategory.ERROR, message)
    }

    private fun refreshDevices() {
        _devices.value = bleClient.scanResults.value
            .filter { scan -> !rejectedDevicesInCurrentScan.contains(scan.macAddress) }
            .filter(::isSupportedCamera)
            .map { inferSessionDevice(it.name, it.macAddress, it.manufacturerData) }
    }

    private fun connectedDeviceId(): Long = _status.value.connectedDevice?.deviceId?.takeIf { it != 0L } ?: 0xFF44

    private fun appendLog(category: LogCategory, message: String, hex: String? = null) {
        _logs.value = (_logs.value + SessionLogEntry(category, message, hex)).takeLast(200)
    }

    private fun parseMac(value: String): ByteArray {
        return value.split(":")
            .mapNotNull { part -> part.toIntOrNull(16)?.toByte() }
            .reversed()
            .toByteArray()
    }

    private fun parseMacForward(value: String?): ByteArray {
        if (value.isNullOrBlank()) return ByteArray(0)
        return value.split(":")
            .mapNotNull { part -> part.toIntOrNull(16)?.toByte() }
            .toByteArray()
    }

    private fun isUsableControllerMac(bytes: ByteArray): Boolean {
        return bytes.size == 6 &&
            !bytes.contentEquals(PLACEHOLDER_CONTROLLER_MAC) &&
            bytes.any { it.toInt() != 0 }
    }

    private fun formatMac(bytes: ByteArray): String? {
        if (bytes.size != 6) return null
        return bytes.joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xFF) }
    }

    private fun isSupportedCamera(scanResult: BleScanResult): Boolean {
        val manufacturerData = scanResult.manufacturerData
        if (
            manufacturerData != null &&
            manufacturerData.size >= 5 &&
            (manufacturerData[0].toInt() and 0xFF) == 0xAA &&
            (manufacturerData[1].toInt() and 0xFF) == 0x08 &&
            (manufacturerData[4].toInt() and 0xFF) == 0xFA
        ) {
            return true
        }
        val normalizedName = scanResult.name.lowercase()
        if (normalizedName.contains("osmo") || normalizedName.contains("action")) {
            return true
        }
        val parsedMac = parseMac(scanResult.macAddress)
        if (parsedMac.size != 6) return false
        return true
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "")
        if (clean.length % 2 != 0) return ByteArray(0)
        return clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun registerPending(
        cmdSet: Int,
        cmdId: Int,
        label: String,
        timeoutMs: Long = COMMAND_TIMEOUT_MS,
        onTimeout: () -> Unit,
    ) {
        val key = CommandKey(cmdSet, cmdId)
        pendingCommands.remove(key)?.timeoutJob?.cancel()
        val timeoutJob = scope.launch {
            delay(timeoutMs)
            if (pendingCommands.remove(key) != null) {
                appendLog(LogCategory.ERROR, "$label timed out")
                onTimeout()
            }
        }
        pendingCommands[key] = PendingCommand(label, timeoutJob)
    }

    private fun resolvePending(
        cmdSet: Int,
        cmdId: Int,
        successMessage: String? = null,
        failureMessage: String? = null,
    ) {
        val key = CommandKey(cmdSet, cmdId)
        val pending = pendingCommands.remove(key) ?: return
        pending.timeoutJob.cancel()
        when {
            failureMessage != null -> {
                appendLog(LogCategory.ERROR, "${pending.label}: $failureMessage")
                _status.value = _status.value.copy(
                    latestError = "${pending.label}: $failureMessage",
                ).withCommandResult(cmdSet, cmdId, "Error: $failureMessage")
            }
            successMessage != null -> {
                appendLog(LogCategory.STATE, "${pending.label}: $successMessage")
                _status.value = _status.value.withCommandResult(cmdSet, cmdId, successMessage)
            }
        }
    }

    private fun SessionStatus.withCommandResult(cmdSet: Int, cmdId: Int, result: String): SessionStatus {
        return when {
            cmdSet == CommandIds.CMD_SET_COMMON && cmdId == CommandIds.VERSION_QUERY -> copy(lastVersionResult = result)
            cmdSet == CommandIds.CMD_SET_CAMERA && cmdId == CommandIds.CAMERA_STATUS_SUBSCRIPTION -> copy(lastSubscribeResult = result)
            cmdSet == CommandIds.CMD_SET_CAMERA && cmdId == CommandIds.CAMERA_MODE_SWITCH -> copy(lastModeSwitchResult = result)
            cmdSet == CommandIds.CMD_SET_CAMERA && cmdId == CommandIds.RECORD_CONTROL -> copy(lastRecordResult = result)
            cmdSet == CommandIds.CMD_SET_COMMON && cmdId == CommandIds.POWER_MODE -> copy(lastSleepResult = result)
            cmdSet == CommandIds.CMD_SET_COMMON && cmdId == CommandIds.GPS_PUSH -> copy(lastGpsResult = result)
            cmdSet == CommandIds.CMD_SET_COMMON && cmdId == CommandIds.KEY_REPORT -> copy(lastKeyReportResult = result)
            cmdSet == CommandIds.CMD_SET_COMMON && cmdId == CommandIds.REBOOT -> copy(lastRebootResult = result)
            else -> this
        }
    }

    private fun buildCameraStatusSummary(payload: CameraStatusPayload): String {
        val resolution = cameraResolutionLabel(payload.videoResolution)
        val fps = fpsLabel(payload.fpsIndex)
        val loop = if (payload.loopRecordSeconds > 0) " loop=${loopRecordLabel(payload.loopRecordSeconds)}" else ""
        return "mode=${cameraModeLabel(payload.cameraMode)} state=${cameraStateLabel(payload.cameraStatus)} res=$resolution fps=$fps bat=${payload.batteryPercent}% remain=${payload.remainTimeSeconds}s$loop"
    }

    private fun buildModeMetadataSummary(payload: NewCameraStatusPayload): String {
        val mode = payload.modeName.ifBlank { "-" }
        val parameters = payload.modeParameters.ifBlank { "-" }
        return "mode=$mode params=$parameters"
    }

    private fun cameraModeLabel(mode: Int): String {
        return when (mode) {
            0x00 -> "Slow Motion"
            0x01 -> "Video"
            0x02 -> "Timelapse (Static)"
            0x05 -> "Photo"
            0x0A -> "Hyperlapse"
            0x1A -> "Live"
            0x23 -> "UVC Live"
            0x28 -> "Low Light Video"
            0x34 -> "Subject Tracking"
            0x38 -> "360 Panorama Video"
            0x3A -> "360 Hyperlapse"
            0x3C -> "360 Selfie"
            0x3F -> "360 Panorama Photo"
            0x41 -> "360 Ultra Wide Video"
            0x43 -> "360 Freeze"
            0x44 -> "360 Super Night"
            0x4A -> "Single Lens Super Night"
            else -> "Mode 0x${mode.toString(16)}"
        }
    }

    private fun cameraStateLabel(state: Int): String {
        return when (state) {
            0x00 -> "Screen Off"
            0x01 -> "Preview/Live"
            0x02 -> "Playback"
            0x03 -> "Shooting/Recording"
            0x05 -> "Pre-record"
            else -> "State 0x${state.toString(16)}"
        }
    }

    private fun cameraResolutionLabel(code: Int): String {
        return when (code) {
            10 -> "1080P"
            16 -> "4K 16:9"
            45 -> "2.7K 16:9"
            66 -> "1080P 9:16"
            67 -> "2.7K 9:16"
            95 -> "2.7K 4:3"
            103 -> "4K 4:3"
            109 -> "4K 9:16"
            4 -> "L / Ultra Wide 30MP"
            3 -> "M / Wide 20MP"
            2 -> "Standard 12MP"
            else -> "Res $code"
        }
    }

    private fun fpsLabel(index: Int): String {
        return when (index) {
            1 -> "24"
            2 -> "25"
            3 -> "30"
            4 -> "48"
            5 -> "50"
            6 -> "60"
            7 -> "120"
            8 -> "240"
            10 -> "100"
            19 -> "200"
            else -> index.toString()
        }
    }

    private fun loopRecordLabel(seconds: Int): String {
        return when (seconds) {
            0 -> "off"
            65535 -> "max"
            else -> {
                if (seconds % 3600 == 0) {
                    "${seconds / 3600}h"
                } else if (seconds % 60 == 0) {
                    "${seconds / 60}m"
                } else {
                    "${seconds}s"
                }
            }
        }
    }

    private fun clearPendingCommands() {
        pendingCommands.values.forEach { it.timeoutJob.cancel() }
        pendingCommands.clear()
    }

    private fun isPhotoLikeCaptureMode(mode: Int): Boolean {
        return mode == 0x05 || mode == 0x3F
    }

    private companion object {
        private const val MIN_PROTOCOL_HEADER_BYTES = 3
        private const val MIN_PROTOCOL_FRAME_BYTES = 16
        private const val CMD_NO_RESPONSE = 0x00
        private const val CMD_RESPONSE_OR_NOT = 0x01
        private const val CMD_WAIT_RESULT = 0x02
        private const val ACK_NO_RESPONSE = 0x20
        private const val DEFAULT_CONTROLLER_DEVICE_ID = 0x12345678L
        private const val DEFAULT_VERIFY_CODE = 2468
        private const val WAKE_OBSERVATION_TIMEOUT_MS = 5_000L
        private const val SNAPSHOT_FLOW_TIMEOUT_MS = 15_000L
        private const val DEFAULT_AUTO_GPS_HZ = 10
        private const val DEFAULT_LOCATION_REQUEST_HZ = 1
        private const val STATUS_WATCHDOG_POLL_INTERVAL_MS = 500L
        private const val STATUS_PUSH_IDLE_TIMEOUT_MS = 4_000L
        private const val STATUS_PROBE_TIMEOUT_MS = 1_500L
        private const val WAKE_PENDING_RESULT = "Advertising sent; waiting for wake event"
        private const val WAKE_DISCONNECT_RESULT = "Wake observed: BLE disconnected, waiting to reconnect"
        private const val GATT_WAKE_WRITE_SPACING_MS = 200L
        private const val GATT_WAKE_KEEPALIVE_INTERVAL_MS = 1_000L
        private const val GATT_WAKE_REPLY_TIMEOUT_MS = 2_500L
        private const val GATT_WAKE_PAIRING_TIMEOUT_MS = 30_000L
        private const val GATT_WAKE_MAX_ATTEMPTS = 3
        private const val GATT_WAKE_BACKOFF_BASE_MS = 500L
        private const val GATT_WAKE_PENDING_RESULT = "GATT wake sent; waiting for camera reply"

        private fun defaultPairingIdentifier(deviceId: Long): String =
            deviceId.toString(16).padStart(8, '0').repeat(4)
        private const val KEY_REPORT_MODE_EVENT = 0x01
        private const val KEY_REPORT_VALUE_SINGLE_CLICK = 0x00
        private const val KEY_CODE_RECORD = 0x01
        private const val KEY_CODE_QS = 0x02
        private const val KEY_CODE_SNAPSHOT = 0x03
        private val PLACEHOLDER_CONTROLLER_MAC = byteArrayOf(
            0x02,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
        )
        private val DEFAULT_CONTROLLER_MAC = byteArrayOf(
            0x38,
            0x34,
            0x56,
            0x78,
            0x9A.toByte(),
            0xBC.toByte(),
        )
        private const val RECONNECT_DELAY_MS = 1_000L
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val COMMAND_TIMEOUT_MS = 3_000L
        private const val WIFI_CREDENTIALS_TIMEOUT_MS = 3_000L
    }

    private data class CommandKey(val cmdSet: Int, val cmdId: Int)

    private data class PendingCommand(
        val label: String,
        val timeoutJob: Job,
    )
}
