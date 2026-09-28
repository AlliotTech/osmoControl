package com.alliot.osmo.demo.app.ui.home

import com.alliot.osmo.demo.session.SessionController
import com.alliot.osmo.demo.session.log.SessionLogEntry
import com.alliot.osmo.demo.session.model.CameraStatusSnapshot
import com.alliot.osmo.demo.session.model.HandshakeStage
import com.alliot.osmo.demo.session.model.LogCategory
import com.alliot.osmo.demo.session.model.SessionDevice
import com.alliot.osmo.demo.session.model.SessionStatus
import com.alliot.osmo.demo.session.model.SessionTransportMode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DebugHomeViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `default destination is workbench`() = runTest {
        val vm = createViewModel()

        assertEquals(HomeDestination.WORKBENCH, vm.state.value.destination)
    }

    @Test
    fun `default transport mode is real`() = runTest {
        val vm = createViewModel()

        assertEquals(SessionTransportMode.REAL, vm.state.value.selectedMode)
        assertEquals(SessionTransportMode.REAL, vm.state.value.sessionStatus.mode)
    }

    @Test
    fun `switching destination preserves current session-derived models`() = runTest {
        val provider = TestControllerProvider()
        val vm = createViewModel(provider)

        provider.real.setLogs(listOf(SessionLogEntry(LogCategory.STATE, "Connected", timestampMillis = 1L)))
        val updated = vm.state.first { it.logs.isNotEmpty() }
        assertEquals("设备已连接", updated.workbenchUiModel.recentEvents.first().message)

        vm.selectDestination(HomeDestination.DEBUG_CONSOLE)
        advanceUntilIdle()

        assertEquals(HomeDestination.DEBUG_CONSOLE, vm.state.value.destination)
        assertEquals("设备已连接", vm.state.value.workbenchUiModel.recentEvents.first().message)
    }

    @Test
    fun `opening gps sheet updates ui state only`() = runTest {
        val provider = TestControllerProvider()
        val vm = createViewModel(provider)

        vm.openSheet(HomeSheet.GPS)
        advanceUntilIdle()

        assertEquals(HomeSheet.GPS, vm.state.value.openSheet)
        assertTrue(provider.fake.recordedCalls.isEmpty())
    }

    @Test
    fun `closing sheet clears ui state only`() = runTest {
        val provider = TestControllerProvider()
        val vm = createViewModel(provider)

        vm.openSheet(HomeSheet.GPS)
        vm.dismissSheet()
        advanceUntilIdle()

        assertEquals(null, vm.state.value.openSheet)
        assertTrue(provider.fake.recordedCalls.isEmpty())
    }

    @Test
    fun `opening connection sheet updates ui state only`() = runTest {
        val provider = TestControllerProvider()
        val vm = createViewModel(provider)

        vm.openConnectionSheet()
        advanceUntilIdle()

        assertEquals(HomeSheet.CONNECTION, vm.state.value.openSheet)
        assertTrue(provider.fake.recordedCalls.isEmpty())
    }

    @Test
    fun `selecting scanned device stores candidate without connecting yet`() = runTest {
        val provider = TestControllerProvider()
        val vm = createViewModel(provider)
        val device = SessionDevice(name = "Candidate", macAddress = "AA:BB:CC:DD", deviceId = 99L)

        provider.fake.setDevices(listOf(device))
        advanceUntilIdle()

        vm.selectConnectionDevice(device.deviceId)
        advanceUntilIdle()

        assertEquals(device.deviceId, vm.state.value.selectedConnectionDeviceId)
        assertTrue(provider.fake.recordedCalls.none { it.startsWith("connect:") })
    }

    @Test
    fun `confirm connection uses selected device`() = runTest {
        val provider = TestControllerProvider()
        val vm = createViewModel(provider)
        val device = SessionDevice(name = "Confirm", macAddress = "AA:BB:CC:EE", deviceId = 42L)

        provider.real.setDevices(listOf(device))
        advanceUntilIdle()

        vm.selectConnectionDevice(device.deviceId)
        vm.confirmConnection()
        advanceUntilIdle()

        assertEquals(listOf("connect:${device.name}"), provider.real.recordedCalls)
    }

    @Test
    fun `retry connection scan starts controller scan`() = runTest {
        val provider = TestControllerProvider()
        val vm = createViewModel(provider)

        vm.retryConnectionScan()
        advanceUntilIdle()

        assertEquals(listOf("startScan"), provider.real.recordedCalls)
    }

    @Test
    fun `device filter query updates and clears on mode switch`() = runTest {
        val provider = TestControllerProvider()
        val vm = createViewModel(provider)

        vm.updateDeviceFilterQuery("osmo")
        advanceUntilIdle()
        assertEquals("osmo", vm.state.value.deviceFilterQuery)

        vm.selectMode(SessionTransportMode.REAL)
        advanceUntilIdle()
        assertEquals("", vm.state.value.deviceFilterQuery)
    }

    @Test
    fun `busyAction remains until overlapping launches complete`() = runTest {
        val provider = TestControllerProvider()
        val vm = createViewModel(provider)
        val device = SessionDevice(name = "Busy", macAddress = "AA:BB:CC:44", deviceId = 15L)

        provider.real.holdStartScan()
        vm.startScan()
        advanceUntilIdle()
        assertEquals("Starting scan", vm.state.value.busyAction)

        provider.real.setDevices(listOf(device))
        advanceUntilIdle()

        provider.real.holdConnect()
        vm.connect(device)
        advanceUntilIdle()
        assertEquals("Connecting ${device.name}", vm.state.value.busyAction)

        provider.real.releaseConnect()
        advanceUntilIdle()
        assertEquals("Starting scan", vm.state.value.busyAction)

        provider.real.releaseStartScan()
        advanceUntilIdle()
        assertNull(vm.state.value.busyAction)
    }

    @Test
    fun `mode switch clears pending busy actions from previous controller`() = runTest {
        val provider = TestControllerProvider()
        val vm = createViewModel(provider)

        provider.real.holdStartScan()
        vm.startScan()
        advanceUntilIdle()
        assertEquals("Starting scan", vm.state.value.busyAction)

        vm.selectMode(SessionTransportMode.FAKE)
        advanceUntilIdle()
        assertNull(vm.state.value.busyAction)

        provider.real.releaseStartScan()
        advanceUntilIdle()
        assertNull(vm.state.value.busyAction)
    }

    @Test
    fun `disconnect clears selected candidate`() = runTest {
        val provider = TestControllerProvider()
        val vm = createViewModel(provider)
        val device = SessionDevice(name = "Disconnect", macAddress = "AA:BB:CC:FF", deviceId = 81L)

        provider.real.setDevices(listOf(device))
        advanceUntilIdle()

        vm.selectConnectionDevice(device.deviceId)
        advanceUntilIdle()

        vm.disconnect()
        advanceUntilIdle()

        assertNull(vm.state.value.selectedConnectionDeviceId)
    }

    @Test
    fun `transport mode change clears selected candidate`() = runTest {
        val provider = TestControllerProvider()
        val vm = createViewModel(provider)
        val device = SessionDevice(name = "ModeChange", macAddress = "AA:BB:CC:11", deviceId = 88L)

        vm.selectMode(SessionTransportMode.FAKE)
        advanceUntilIdle()
        provider.fake.setDevices(listOf(device))
        advanceUntilIdle()

        vm.selectConnectionDevice(device.deviceId)
        advanceUntilIdle()

        vm.selectMode(SessionTransportMode.REAL)
        advanceUntilIdle()

        assertNull(vm.state.value.selectedConnectionDeviceId)
    }

    @Test
    fun `ready device clears selected candidate`() = runTest {
        val provider = TestControllerProvider()
        val vm = createViewModel(provider)
        val device = SessionDevice(name = "Ready", macAddress = "AA:BB:CC:22", deviceId = 77L)

        provider.real.setDevices(listOf(device))
        advanceUntilIdle()

        vm.selectConnectionDevice(device.deviceId)
        advanceUntilIdle()

        provider.real.setStatus(
            SessionStatus(
                mode = SessionTransportMode.REAL,
                connectedDevice = device,
                protocolReady = true,
            ),
        )
        advanceUntilIdle()

        assertNull(vm.state.value.selectedConnectionDeviceId)
    }

    @Test
    fun `selected device disappearing clears candidate`() = runTest {
        val provider = TestControllerProvider()
        val vm = createViewModel(provider)
        val device = SessionDevice(name = "Disappear", macAddress = "AA:BB:CC:33", deviceId = 66L)

        provider.real.setDevices(listOf(device))
        advanceUntilIdle()

        vm.selectConnectionDevice(device.deviceId)
        advanceUntilIdle()

        provider.real.setDevices(emptyList())
        advanceUntilIdle()

        assertNull(vm.state.value.selectedConnectionDeviceId)
    }

    @Test
    fun `session disconnect clears selected candidate`() = runTest {
        val provider = TestControllerProvider()
        val vm = createViewModel(provider)
        val device = SessionDevice(name = "DisconnectStatus", macAddress = "AA:BB:CC:44", deviceId = 55L)

        provider.real.setDevices(listOf(device))
        advanceUntilIdle()

        vm.selectConnectionDevice(device.deviceId)
        advanceUntilIdle()

        provider.real.setStatus(
            SessionStatus(
                mode = SessionTransportMode.REAL,
                connectedDevice = device,
                handshakeStage = HandshakeStage.REQUEST_SENT,
            ),
        )
        advanceUntilIdle()

        assertEquals(device.deviceId, vm.state.value.selectedConnectionDeviceId)

        provider.real.setStatus(SessionStatus(mode = SessionTransportMode.REAL))
        advanceUntilIdle()

        assertNull(vm.state.value.selectedConnectionDeviceId)
    }

    @Test
    fun `selection realigns when controller connects to different device`() = runTest {
        val provider = TestControllerProvider()
        val vm = createViewModel(provider)
        val first = SessionDevice(name = "Candidate", macAddress = "AA:BB:CC:99", deviceId = 11L)
        val second = SessionDevice(name = "Other", macAddress = "AA:BB:CC:88", deviceId = 22L)

        provider.real.setDevices(listOf(first, second))
        advanceUntilIdle()

        vm.selectConnectionDevice(first.deviceId)
        advanceUntilIdle()

        provider.real.setStatus(
            SessionStatus(
                mode = SessionTransportMode.REAL,
                connectedDevice = second,
                handshakeStage = HandshakeStage.REQUEST_SENT,
            ),
        )
        advanceUntilIdle()

        assertEquals(second.deviceId, vm.state.value.selectedConnectionDeviceId)
    }

    @Test
    fun `selection survives device id rewrite when mac matches`() = runTest {
        val provider = TestControllerProvider()
        val vm = createViewModel(provider)
        val candidate = SessionDevice(name = "Candidate", macAddress = "AA:BB:CC:99", deviceId = 11L)

        provider.real.setDevices(listOf(candidate))
        advanceUntilIdle()

        vm.selectConnectionDevice(candidate.deviceId)
        advanceUntilIdle()

        provider.real.setStatus(
            SessionStatus(
                mode = SessionTransportMode.REAL,
                connectedDevice = SessionDevice(name = "Candidate", macAddress = candidate.macAddress, deviceId = 1234567L),
                handshakeStage = HandshakeStage.REQUEST_SENT,
            ),
        )
        advanceUntilIdle()

        assertEquals(candidate.deviceId, vm.state.value.selectedConnectionDeviceId)

        provider.real.setStatus(
            SessionStatus(
                mode = SessionTransportMode.REAL,
                connectedDevice = SessionDevice(name = "Candidate", macAddress = candidate.macAddress, deviceId = 1234567L),
                handshakeStage = HandshakeStage.COMPLETED,
                protocolReady = true,
            ),
        )
        advanceUntilIdle()

        assertNull(vm.state.value.selectedConnectionDeviceId)
    }

    @Test
    fun `recording state prevents mode switch action from firing`() = runTest {
        val provider = TestControllerProvider()
        val vm = createViewModel(provider)

        provider.real.setCameraStatus(CameraStatusSnapshot(recording = true))
        vm.state.first { it.cameraStatus.recording }

        vm.switchMode(0x05)
        advanceUntilIdle()

        assertTrue(provider.real.switchModeCalls.isEmpty())
    }

    @Test
    fun `mode switch calls controller when not recording`() = runTest {
        val provider = TestControllerProvider()
        val vm = createViewModel(provider)

        vm.switchMode(0x05)
        advanceUntilIdle()

        assertEquals(listOf(0x05), provider.real.switchModeCalls)
    }

    @Test
    fun `workbench primary action sends record key in photo mode`() = runTest {
        val provider = TestControllerProvider()
        val vm = createViewModel(provider)

        vm.selectMode(SessionTransportMode.REAL)
        provider.real.setCameraStatus(CameraStatusSnapshot(mode = 0x05, modeLabel = "Photo"))
        advanceUntilIdle()

        vm.performWorkbenchPrimaryAction()
        advanceUntilIdle()

        assertEquals(listOf("reportRecordKeyClick"), provider.real.recordedCalls)
    }

    @Test
    fun `workbench primary action toggles record in video mode`() = runTest {
        val provider = TestControllerProvider()
        val vm = createViewModel(provider)

        vm.selectMode(SessionTransportMode.REAL)
        provider.real.setCameraStatus(CameraStatusSnapshot(mode = 0x01, modeLabel = "Video"))
        advanceUntilIdle()

        vm.performWorkbenchPrimaryAction()
        advanceUntilIdle()

        assertEquals(listOf("toggleRecording"), provider.real.recordedCalls)
    }

    @Test
    fun `permission action becomes request or settings based on denial mode`() = runTest {
        val provider = TestControllerProvider()
        val vm = createViewModel(provider)

        vm.selectMode(SessionTransportMode.REAL)
        vm.updatePrerequisites(
            RealModePrerequisites(
                bluetoothPermissionsGranted = false,
                locationPermissionGranted = false,
                requiresSettingsAction = false,
            ),
        )
        val requestAction = async(start = CoroutineStart.UNDISPATCHED) { vm.actions.first() }
        vm.performPermissionAction()
        assertEquals(DebugHomeAction.RequestPermissions, requestAction.await())

        vm.updatePrerequisites(
            RealModePrerequisites(
                bluetoothPermissionsGranted = false,
                locationPermissionGranted = false,
                requiresSettingsAction = true,
            ),
        )
        val settingsAction = async(start = CoroutineStart.UNDISPATCHED) { vm.actions.first() }
        vm.performPermissionAction()
        assertEquals(DebugHomeAction.OpenSettings, settingsAction.await())
    }

    @Test
    fun `permission action is no-op when no CTA is available`() = runTest {
        val provider = TestControllerProvider()
        val vm = createViewModel(provider)

        vm.updatePrerequisites(
            RealModePrerequisites(
                bluetoothPermissionsGranted = true,
                locationPermissionGranted = true,
            ),
        )
        val action = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(1) { vm.actions.first() }
        }
        vm.performPermissionAction()
        advanceUntilIdle()

        assertNull(action.await())
    }

    @Test
    fun `opening recent events routes to debug console logs`() = runTest {
        val vm = createViewModel()

        vm.openRecentEvents()
        advanceUntilIdle()

        assertEquals(HomeDestination.DEBUG_CONSOLE, vm.state.value.destination)
        assertEquals(1, vm.state.value.debugConsoleLogsRequest)
    }

    @Test
    fun `reset app state disconnects controllers and emits reset action`() = runTest {
        val provider = TestControllerProvider()
        val vm = createViewModel(provider)

        val action = async(start = CoroutineStart.UNDISPATCHED) { vm.actions.first() }
        vm.resetAppState()
        advanceUntilIdle()

        assertEquals(DebugHomeAction.ResetAppState, action.await())
        assertTrue("disconnect" in provider.fake.recordedCalls)
        assertTrue("disconnect" in provider.real.recordedCalls)
    }

    private fun createViewModel(provider: TestControllerProvider = TestControllerProvider()): DebugHomeViewModel {
        return DebugHomeViewModel(controllers = provider)
    }

    private class TestControllerProvider(
        val fake: TrackingController = TrackingController(SessionTransportMode.FAKE),
        val real: TrackingController = TrackingController(SessionTransportMode.REAL),
    ) : ControllerProvider {
        override val fakeController: SessionController = fake
        override val realController: SessionController = real
    }

    private class TrackingController(private val mode: SessionTransportMode) : SessionController {
        val recordedCalls = mutableListOf<String>()
        val switchModeCalls = mutableListOf<Int>()

        private var startScanGate = CompletableDeferred<Unit>().apply { complete(Unit) }
        private var connectGate = CompletableDeferred<Unit>().apply { complete(Unit) }

        fun holdStartScan() {
            startScanGate = CompletableDeferred()
        }

        fun releaseStartScan() {
            if (!startScanGate.isCompleted) startScanGate.complete(Unit)
            startScanGate = CompletableDeferred<Unit>().also { it.complete(Unit) }
        }

        fun holdConnect() {
            connectGate = CompletableDeferred()
        }

        fun releaseConnect() {
            if (!connectGate.isCompleted) connectGate.complete(Unit)
            connectGate = CompletableDeferred<Unit>().also { it.complete(Unit) }
        }

        private val _devices = MutableStateFlow<List<SessionDevice>>(emptyList())
        override val devices: StateFlow<List<SessionDevice>> = _devices

        private val _status = MutableStateFlow(SessionStatus(mode = mode))
        override val status: StateFlow<SessionStatus> = _status

        private val _cameraStatus = MutableStateFlow(CameraStatusSnapshot())
        override val cameraStatus: StateFlow<CameraStatusSnapshot> = _cameraStatus

        private val _logs = MutableStateFlow<List<SessionLogEntry>>(emptyList())
        override val logs: StateFlow<List<SessionLogEntry>> = _logs

        override suspend fun startScan() {
            recordedCalls += "startScan"
            startScanGate.await()
        }

        override suspend fun stopScan() {
            recordedCalls += "stopScan"
        }

        override suspend fun connect(device: SessionDevice) {
            recordedCalls += "connect:${device.name}"
            connectGate.await()
        }

        override suspend fun disconnect() {
            recordedCalls += "disconnect"
        }

        override suspend fun fetchWifiCredentials(): com.alliot.osmo.demo.session.model.WifiCredentials? {
            recordedCalls += "fetchWifiCredentials"
            return null
        }

        override suspend fun releaseMediaLink() {
            recordedCalls += "releaseMediaLink"
        }

        override suspend fun wakeViaGatt() {
            recordedCalls += "wakeViaGatt"
        }

        override suspend fun requestVersion() {
            recordedCalls += "requestVersion"
        }

        override suspend fun rebootCamera() {
            recordedCalls += "rebootCamera"
        }

        override suspend fun toggleRecording() {
            recordedCalls += "toggleRecording"
        }

        override suspend fun switchMode(mode: Int) {
            switchModeCalls += mode
        }

        override suspend fun subscribeStatus() {
            recordedCalls += "subscribeStatus"
        }

        override suspend fun pushGps(latitude: Double, longitude: Double, altitudeMeters: Double) {
            recordedCalls += "pushGps"
        }

        override suspend fun setGpsAutoPushEnabled(enabled: Boolean) {
            recordedCalls += "setGpsAutoPushEnabled:$enabled"
        }

        override suspend fun setGpsAutoPushFrequencyHz(hz: Int) {
            recordedCalls += "setGpsAutoPushFrequencyHz:$hz"
        }

        override suspend fun setGpsLocationRequestFrequencyHz(hz: Int) {
            recordedCalls += "setGpsLocationRequestFrequencyHz:$hz"
        }

        override suspend fun sleep() {
            recordedCalls += "sleep"
        }

        override suspend fun wake() {
            recordedCalls += "wake"
        }

        override suspend fun wakeAndSnapshot() {
            recordedCalls += "wakeAndSnapshot"
        }

        override suspend fun reportRecordKeyClick() {
            recordedCalls += "reportRecordKeyClick"
        }

        override suspend fun reportQsKeyClick() {
            recordedCalls += "reportQsKeyClick"
        }

        override suspend fun reportSnapshotKeyClick() {
            recordedCalls += "reportSnapshotKeyClick"
        }

        override suspend fun sendManualCommand(hex: String) {
            recordedCalls += "sendManualCommand:$hex"
        }

        override suspend fun setHandshakeVerifyMode(mode: Int) {
            recordedCalls += "setHandshakeVerifyMode:$mode"
        }

        fun setCameraStatus(snapshot: CameraStatusSnapshot) {
            _cameraStatus.value = snapshot
        }

        fun setStatus(status: SessionStatus) {
            _status.value = status
        }

        fun setLogs(entries: List<SessionLogEntry>) {
            _logs.value = entries
        }

        fun setDevices(devices: List<SessionDevice>) {
            _devices.value = devices
        }
    }
}
