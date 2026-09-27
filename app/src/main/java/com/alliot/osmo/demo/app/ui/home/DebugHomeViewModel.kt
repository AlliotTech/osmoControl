package com.alliot.osmo.demo.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.alliot.osmo.demo.app.di.AppContainer
import com.alliot.osmo.demo.session.SessionController
import com.alliot.osmo.demo.session.model.SessionDevice
import com.alliot.osmo.demo.session.model.SessionStatus
import com.alliot.osmo.demo.session.model.SessionTransportMode
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

class DebugHomeViewModel internal constructor(
    private val controllers: ControllerProvider,
) : ViewModel() {

    constructor(container: AppContainer) : this(
        controllers = object : ControllerProvider {
            override val fakeController: SessionController = container.fakeController
            override val realController: SessionController = container.realController
        },
    )

    private val _state = MutableStateFlow(DebugHomeState())
    val state: StateFlow<DebugHomeState> = _state.asStateFlow()

    private val _actions = Channel<DebugHomeAction>(capacity = Channel.BUFFERED)
    internal val actions = _actions.receiveAsFlow()

    private var activeController: SessionController = controllers.realController
    private var observationJobs: List<Job> = emptyList()
    private var lastLoggedHandshakeSummary: String? = null
    private var lastLoggedLogCount: Int = 0

    init {
        observeController()
    }

    fun setDestination(destination: HomeDestination) {
        _state.update { it.copy(destination = destination) }
    }

    fun selectDestination(destination: HomeDestination) {
        setDestination(destination)
    }

    fun openRecentEvents() {
        _state.update { it.copy(destination = HomeDestination.DEBUG_CONSOLE, debugConsoleLogsRequest = it.debugConsoleLogsRequest + 1) }
    }

    fun openSheet(sheet: HomeSheet) {
        _state.update { it.copy(openSheet = sheet) }
    }

    fun closeSheet() {
        _state.update { it.copy(openSheet = null) }
    }

    fun dismissSheet() {
        closeSheet()
    }

    fun openConnectionSheet() {
        openSheet(HomeSheet.CONNECTION)
    }

    fun selectConnectionDevice(deviceId: Long) {
        _state.update { it.copy(selectedConnectionDeviceId = deviceId) }
    }

    fun updateDeviceFilterQuery(query: String) {
        _state.update { it.copy(deviceFilterQuery = query) }
    }

    fun confirmConnection() {
        val candidate = _state.value.discoveredDevices.firstOrNull { it.deviceId == _state.value.selectedConnectionDeviceId } ?: return
        connect(candidate)
    }

    fun retryConnectionScan() = launch("Retrying scan") { activeController.startScan() }

    fun selectMode(mode: SessionTransportMode) {
        activeController = when (mode) {
            SessionTransportMode.FAKE -> controllers.fakeController
            SessionTransportMode.REAL -> controllers.realController
        }
        clearBusyActions()
        _state.value = _state.value.copy(
            selectedMode = mode,
            lastUiError = null,
            busyAction = null,
            selectedConnectionDeviceId = null,
            deviceFilterQuery = "",
        )
        lastLoggedHandshakeSummary = null
        lastLoggedLogCount = 0
        safeLog("Selected mode=${mode.name}")
        observeController()
    }

    fun updateManualHex(value: String) {
        _state.value = _state.value.copy(manualHex = value)
    }

    fun updatePrerequisites(prerequisites: RealModePrerequisites) {
        _state.value = _state.value.copy(prerequisites = prerequisites)
    }

    fun updateHandshakeVerifyMode(mode: Int) = launch("Updating verify_mode=$mode") {
        safeLog("Requested handshake verify_mode=$mode")
        activeController.setHandshakeVerifyMode(mode)
    }

    fun startScan() = launch("Starting scan") { activeController.startScan() }
    fun stopScan() = launch("Stopping scan") { activeController.stopScan() }
    fun connect(device: SessionDevice) = launch("Connecting ${device.name}") { activeController.connect(device) }
    fun disconnect() = launch("Disconnecting") {
        activeController.disconnect()
        _state.update { it.copy(selectedConnectionDeviceId = null) }
    }
    fun requestVersion() = launch("Requesting version") { activeController.requestVersion() }
    fun rebootCamera() = launch("Rebooting camera") { activeController.rebootCamera() }
    fun performWorkbenchPrimaryAction() {
        val snapshot = _state.value.cameraStatus
        val label = if (isPhotoCaptureMode(snapshot)) "Triggering capture" else "Toggling record"
        launch(label) {
            if (isPhotoCaptureMode(snapshot)) {
                activeController.reportRecordKeyClick()
            } else {
                activeController.toggleRecording()
            }
        }
    }
    fun toggleRecording() = launch("Toggling record") { activeController.toggleRecording() }
    fun switchMode(mode: Int) {
        if (_state.value.cameraStatus.recording) {
            _state.update { it.copy(lastUiError = "录制中，暂不可切换模式") }
            return
        }
        launch("Switching mode 0x${mode.toString(16)}") { activeController.switchMode(mode) }
    }
    fun subscribeStatus() = launch("Subscribing status") { activeController.subscribeStatus() }
    fun pushSampleGps() = launch("Pushing sample GPS") { activeController.pushGps(31.2304, 121.4737, 15.0) }
    fun setGpsAutoPushEnabled(enabled: Boolean) = launch(if (enabled) "Enabling GPS sync" else "Disabling GPS sync") {
        activeController.setGpsAutoPushEnabled(enabled)
    }
    fun setGpsAutoPushFrequencyHz(hz: Int) = launch("Setting GPS frequency ${hz}Hz") {
        activeController.setGpsAutoPushFrequencyHz(hz)
    }
    fun setGpsLocationRequestFrequencyHz(hz: Int) = launch("Setting location request ${hz}Hz") {
        activeController.setGpsLocationRequestFrequencyHz(hz)
    }
    fun sleep() = launch("Sending sleep") { activeController.sleep() }
    fun wake() = launch("Sending wake") { activeController.wake() }

    fun wakeViaGatt() = launch("Sending GATT wake") { activeController.wakeViaGatt() }
    fun wakeAndSnapshot() = launch("Wake and snapshot") { activeController.wakeAndSnapshot() }
    fun sendRecordKeyClick() = launch("Sending record key click") { activeController.reportRecordKeyClick() }
    fun sendQsKeyClick() = launch("Sending QS key click") { activeController.reportQsKeyClick() }
    fun sendSnapshotKeyClick() = launch("Sending snapshot key click") { activeController.reportSnapshotKeyClick() }
    fun sendManual() = launch("Sending manual command") { activeController.sendManualCommand(_state.value.manualHex) }
    fun resetAppState() = launch("Resetting app state") {
        runCatching { controllers.realController.disconnect() }
        runCatching { controllers.fakeController.disconnect() }
        _actions.send(DebugHomeAction.ResetAppState)
    }

    fun performPermissionAction() {
        val action = when (_state.value.permissionCta?.action) {
            PermissionAction.REQUEST -> DebugHomeAction.RequestPermissions
            PermissionAction.OPEN_SETTINGS -> DebugHomeAction.OpenSettings
            null -> null
        }
        if (action != null) {
            viewModelScope.launch { _actions.send(action) }
        }
    }

    private data class BusyActionToken(val id: Long, val label: String)

    private val busyActionId = AtomicLong()
    private val activeBusyActions = mutableListOf<BusyActionToken>()

    private fun launch(label: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            val token = pushBusyAction(label)
            runCatching { block() }
                .onFailure { error ->
                    _state.update { state ->
                        state.copy(lastUiError = error.message ?: error::class.simpleName ?: "Unknown error")
                    }
                }
            popBusyAction(token)
        }
    }

    private fun pushBusyAction(label: String): BusyActionToken {
        val token = BusyActionToken(busyActionId.getAndIncrement(), label)
        synchronized(activeBusyActions) {
            activeBusyActions.add(token)
            _state.update { it.copy(busyAction = activeBusyActions.lastOrNull()?.label, lastUiError = null) }
        }
        return token
    }

    private fun popBusyAction(token: BusyActionToken) {
        synchronized(activeBusyActions) {
            activeBusyActions.remove(token)
            _state.update { it.copy(busyAction = activeBusyActions.lastOrNull()?.label) }
        }
    }

    private fun clearBusyActions() {
        synchronized(activeBusyActions) {
            activeBusyActions.clear()
            _state.update { it.copy(busyAction = null) }
        }
    }

    private fun observeController() {
        observationJobs.forEach(Job::cancel)
        observationJobs = listOf(
            viewModelScope.launch {
            activeController.status.collectLatest { status ->
                val handshakeSummary = "mode=${status.mode.name} verify=${status.handshakeVerifyMode} code=${status.handshakeVerifyCode?.toString()?.padStart(4, '0') ?: "-"} stage=${status.handshakeStage} ready=${status.protocolReady} controller=0x${status.controllerDeviceId.toString(16)}@${status.controllerMacAddress ?: "-"} device=${status.connectedDevice?.macAddress ?: "-"} error=${status.latestError ?: "-"}"
                if (handshakeSummary != lastLoggedHandshakeSummary) {
                    lastLoggedHandshakeSummary = handshakeSummary
                    safeLog("SessionStatus $handshakeSummary")
                }
                _state.update { state -> state.copy(sessionStatus = status).withHonestSelection(previousStatus = state.sessionStatus) }
            }
        },
            viewModelScope.launch {
            activeController.cameraStatus.collectLatest { status ->
                _state.value = _state.value.copy(cameraStatus = status)
            }
        },
            viewModelScope.launch {
            activeController.devices.collectLatest { devices ->
                _state.update { it.copy(discoveredDevices = devices).withHonestSelection() }
            }
        },
            viewModelScope.launch {
            activeController.logs.collectLatest { logs ->
                if (logs.size < lastLoggedLogCount) {
                    lastLoggedLogCount = 0
                }
                logs.drop(lastLoggedLogCount).forEach { entry ->
                    safeLog("SessionLog [${entry.category.name}] ${entry.message}${entry.hex?.let { " | $it" } ?: ""}")
                }
                lastLoggedLogCount = logs.size
                _state.value = _state.value.copy(logs = logs)
            }
        },
        )
    }

    private companion object {
        private const val TAG = "DebugHomeVM"

        private fun safeLog(message: String) {
            runCatching { Log.d(TAG, message) }
        }
    }

    private fun DebugHomeState.withHonestSelection(previousStatus: SessionStatus? = null): DebugHomeState {
        val selectedId = selectedConnectionDeviceId ?: return this
        val candidateMac = discoveredDevices.firstOrNull { it.deviceId == selectedId }?.macAddress
        val connectedDevice = sessionStatus.connectedDevice
        val connectedDeviceId = connectedDevice?.deviceId
        val connectedMac = connectedDevice?.macAddress
        val samePhysicalDevice = candidateMac != null && connectedMac != null && candidateMac == connectedMac

        if (connectedDevice != null && sessionStatus.protocolReady && (connectedDeviceId == selectedId || samePhysicalDevice)) {
            return copy(selectedConnectionDeviceId = null)
        }

        if (connectedDevice != null && connectedDeviceId != null && connectedDeviceId != selectedId && !samePhysicalDevice) {
            return copy(selectedConnectionDeviceId = connectedDeviceId)
        }

        val previouslyConnectedDevice = previousStatus?.connectedDevice
        if (connectedDevice == null && previouslyConnectedDevice != null) {
            val previousMac = previouslyConnectedDevice.macAddress
            val matchesPrevious = previouslyConnectedDevice.deviceId == selectedId ||
                (candidateMac != null && candidateMac == previousMac)
            if (matchesPrevious) {
                return copy(selectedConnectionDeviceId = null)
            }
        }

        val stillVisible = discoveredDevices.any { it.deviceId == selectedId || (candidateMac != null && it.macAddress == candidateMac) }
        return if (stillVisible) this else copy(selectedConnectionDeviceId = null)
    }
}

internal sealed interface DebugHomeAction {
    data object RequestPermissions : DebugHomeAction
    data object OpenSettings : DebugHomeAction
    data object ResetAppState : DebugHomeAction
}

internal interface ControllerProvider {
    val fakeController: SessionController
    val realController: SessionController
}

class DebugHomeViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return DebugHomeViewModel(container) as T
    }
}
