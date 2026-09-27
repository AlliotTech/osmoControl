package com.alliot.osmo.demo.session.real

import com.alliot.osmo.demo.ble.BleClient
import com.alliot.osmo.demo.ble.BleConnectionState
import com.alliot.osmo.demo.ble.BleEvent
import com.alliot.osmo.demo.ble.BleScanResult
import com.alliot.osmo.demo.protocol.frame.DjiFrame
import com.alliot.osmo.demo.protocol.frame.DjiFrameCodec
import com.alliot.osmo.demo.protocol.payload.CameraConnectionConfirmationPayload
import com.alliot.osmo.demo.protocol.payload.CameraStatusPayload
import com.alliot.osmo.demo.protocol.payload.CommandIds
import com.alliot.osmo.demo.protocol.payload.EmptyPayload
import com.alliot.osmo.demo.protocol.payload.PayloadCodec
import com.alliot.osmo.demo.protocol.util.ByteOrder
import com.alliot.osmo.demo.session.model.CameraFamily
import com.alliot.osmo.demo.session.model.HandshakeStage
import com.alliot.osmo.demo.session.model.ProtocolFamily
import com.alliot.osmo.demo.session.model.SessionDevice
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

class BleSessionControllerTest {
    @Test
    fun connection_confirmation_promotes_protocol_ready() = runBlocking {
        val bleClient = FakeBleClient()
        val controller = BleSessionController(bleClient)
        val device = SessionDevice("Osmo Action 5 Pro", "AA:BB:CC:DD:EE:01", 0xFF44)

        controller.connect(device)
        bleClient.emitNotification(
            frameFor(
                cmdSet = CommandIds.CMD_SET_COMMON,
                cmdId = CommandIds.CONNECTION,
                cmdType = 0x00,
                payload = CameraConnectionConfirmationPayload(
                    deviceId = 0xFF44,
                    macAddress = byteArrayOf(0x01, 0xEE.toByte(), 0xDD.toByte(), 0xCC.toByte(), 0xBB.toByte(), 0xAA.toByte()),
                    firmwareVersion = 0,
                    verifyMode = 2,
                    verifyData = 0,
                ),
            ),
        )

        waitUntil { controller.status.value.handshakeStage == HandshakeStage.COMPLETED }
        assertEquals(HandshakeStage.COMPLETED, controller.status.value.handshakeStage)
        assertTrue(controller.status.value.protocolReady)
        assertTrue(bleClient.writes.isNotEmpty())
    }

    @Test
    fun camera_status_notification_updates_snapshot() = runBlocking {
        val bleClient = FakeBleClient()
        val controller = BleSessionController(bleClient)

        bleClient.emitNotification(
            frameFor(
                cmdSet = CommandIds.CMD_SET_CAMERA,
                cmdId = CommandIds.CAMERA_STATUS_PUSH,
                cmdType = 0x00,
                payload = CameraStatusPayload(
                    cameraMode = 0x05,
                    cameraStatus = 0x03,
                    videoResolution = 16,
                    fpsIndex = 3,
                    eisMode = 1,
                    recordTimeSeconds = 10,
                    remainCapacityMb = 4096,
                    remainPhotoCount = 99,
                    remainTimeSeconds = 600,
                    userMode = 0,
                    powerMode = 0,
                    batteryPercent = 84,
                ),
            ),
        )

        waitUntil { controller.cameraStatus.value.recording }
        assertTrue(controller.cameraStatus.value.recording)
        assertEquals(0x05, controller.cameraStatus.value.mode)
        assertEquals(84, controller.cameraStatus.value.batteryPercent)
    }

    @Test
    fun unexpected_disconnect_triggers_reconnect_attempt() = runBlocking {
        val bleClient = FakeBleClient()
        val controller = BleSessionController(bleClient)
        val device = SessionDevice("Osmo Action 5 Pro", "AA:BB:CC:DD:EE:01", 0xFF44)

        controller.connect(device)
        waitUntil { bleClient.writes.size == 1 }

        bleClient.emitDisconnected(device.macAddress, "status=8")

        waitUntil(timeoutMs = 2_500) { bleClient.writes.size >= 2 }
        assertEquals(device.macAddress, controller.status.value.connectedDevice?.macAddress)
        assertEquals(HandshakeStage.REQUEST_SENT, controller.status.value.handshakeStage)
        assertTrue(!controller.status.value.protocolReady)
    }

    @Test
    fun lost_transport_state_triggers_reconnect_even_without_disconnect_event() = runBlocking {
        val bleClient = FakeBleClient()
        val controller = BleSessionController(bleClient)
        val device = SessionDevice("Osmo Action 5 Pro", "AA:BB:CC:DD:EE:01", 0xFF44)

        controller.connect(device)
        waitUntil { bleClient.writes.size == 1 }

        bleClient.dropConnectionStateWithoutEvent(device.macAddress)

        waitUntil(timeoutMs = 2_500) { bleClient.writes.size >= 2 }
        assertEquals(device.macAddress, controller.status.value.connectedDevice?.macAddress)
        assertEquals(HandshakeStage.REQUEST_SENT, controller.status.value.handshakeStage)
        assertTrue(!controller.status.value.protocolReady)
    }

    @Test
    fun connection_request_uses_local_controller_identity_from_adapter() = runBlocking {
        val localAdapterAddress = randomMacAddress()
        val expectedMacBytes = localAdapterAddress.split(":").map { it.toInt(16) }
        val bleClient = FakeBleClient(localAdapterAddress = localAdapterAddress)
        val controller = BleSessionController(
            bleClient = bleClient,
            controllerDeviceId = 0xA1B2C3D4L,
            verifyCodeProvider = { 4321 },
        )
        val device = SessionDevice("Osmo Action 5 Pro", "AA:BB:CC:DD:EE:01", 0xFF44)

        controller.connect(device)

        waitUntil { bleClient.writes.isNotEmpty() }
        val requestFrame = DjiFrameCodec.decode(bleClient.writes.first()).frame
        assertEquals(CommandIds.CMD_SET_COMMON, requestFrame.cmdSet)
        assertEquals(CommandIds.CONNECTION, requestFrame.cmdId)
        assertEquals(0x02, requestFrame.cmdType)
        assertEquals(0xA1B2C3D4L, ByteOrder.u32(requestFrame.payload, 0))
        assertEquals(0x06, requestFrame.payload[4].toInt() and 0xFF)
        expectedMacBytes.forEachIndexed { index, expected ->
            assertEquals(expected, requestFrame.payload[5 + index].toInt() and 0xFF)
        }
        assertEquals(0x01, requestFrame.payload[26].toInt() and 0xFF)
        assertEquals(4321, ByteOrder.u16(requestFrame.payload, 27))
    }

    @Test
    fun remembered_camera_uses_verify_mode_zero_by_default() = runBlocking {
        val bleClient = FakeBleClient()
        val controller = BleSessionController(
            bleClient = bleClient,
            isKnownPairedDevice = { mac -> mac == "AA:BB:CC:DD:EE:01" },
        )
        val device = SessionDevice("Osmo Action 5 Pro", "AA:BB:CC:DD:EE:01", 0xFF44)

        controller.connect(device)

        waitUntil { bleClient.writes.isNotEmpty() }
        val requestFrame = DjiFrameCodec.decode(bleClient.writes.first()).frame
        assertEquals(0x00, requestFrame.payload[26].toInt() and 0xFF)
    }

    @Test
    fun explicit_verify_mode_one_is_used_when_requested() = runBlocking {
        val bleClient = FakeBleClient()
        val controller = BleSessionController(
            bleClient = bleClient,
            isKnownPairedDevice = { true },
        )
        val device = SessionDevice("Osmo Action 5 Pro", "AA:BB:CC:DD:EE:01", 0xFF44)

        controller.setHandshakeVerifyMode(1)
        controller.connect(device)

        waitUntil { bleClient.writes.isNotEmpty() }
        val requestFrame = DjiFrameCodec.decode(bleClient.writes.first()).frame
        assertEquals(0x01, requestFrame.payload[26].toInt() and 0xFF)
    }

    @Test
    fun successful_handshake_marks_camera_as_paired() = runBlocking {
        val bleClient = FakeBleClient()
        val pairedMacs = mutableListOf<String>()
        val controller = BleSessionController(
            bleClient = bleClient,
            onDevicePaired = pairedMacs::add,
        )
        val device = SessionDevice("Osmo Action 5 Pro", "AA:BB:CC:DD:EE:01", 0xFF44)

        controller.connect(device)
        bleClient.emitNotification(
            frameFor(
                cmdSet = CommandIds.CMD_SET_COMMON,
                cmdId = CommandIds.CONNECTION,
                cmdType = 0x00,
                payload = CameraConnectionConfirmationPayload(
                    deviceId = 0xFF44,
                    macAddress = byteArrayOf(0x01, 0xEE.toByte(), 0xDD.toByte(), 0xCC.toByte(), 0xBB.toByte(), 0xAA.toByte()),
                    firmwareVersion = 0,
                    verifyMode = 2,
                    verifyData = 0,
                ),
            ),
        )

        waitUntil { pairedMacs.isNotEmpty() }
        assertEquals(listOf(device.macAddress), pairedMacs)
    }

    @Test
    fun placeholder_adapter_mac_falls_back_to_persistent_controller_mac() = runBlocking {
        val bleClient = FakeBleClient(localAdapterAddress = "02:00:00:00:00:00")
        val controller = BleSessionController(
            bleClient = bleClient,
            controllerDeviceId = 0x11223344L,
            fallbackControllerMac = byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x9A.toByte(), 0xBC.toByte()),
            verifyCodeProvider = { 1357 },
        )
        val device = SessionDevice("Osmo Action 5 Pro", "AA:BB:CC:DD:EE:01", 0xFF44)

        controller.connect(device)

        waitUntil { bleClient.writes.isNotEmpty() }
        val requestFrame = DjiFrameCodec.decode(bleClient.writes.first()).frame
        assertEquals(0x12, requestFrame.payload[5].toInt() and 0xFF)
        assertEquals(0x34, requestFrame.payload[6].toInt() and 0xFF)
        assertEquals(0x56, requestFrame.payload[7].toInt() and 0xFF)
        assertEquals(0x78, requestFrame.payload[8].toInt() and 0xFF)
        assertEquals(0x9A, requestFrame.payload[9].toInt() and 0xFF)
        assertEquals(0xBC, requestFrame.payload[10].toInt() and 0xFF)
    }

    @Test
    fun connection_confirmation_response_uses_controller_device_id() = runBlocking {
        val bleClient = FakeBleClient()
        val controller = BleSessionController(
            bleClient = bleClient,
            controllerDeviceId = 0x0BADBEEFL,
            verifyCodeProvider = { 2468 },
        )
        val device = SessionDevice("Osmo Action 5 Pro", "AA:BB:CC:DD:EE:01", 0xFF44)

        controller.connect(device)
        bleClient.emitNotification(
            frameFor(
                cmdSet = CommandIds.CMD_SET_COMMON,
                cmdId = CommandIds.CONNECTION,
                cmdType = 0x00,
                seq = 0x1234,
                payload = CameraConnectionConfirmationPayload(
                    deviceId = 0xFF44,
                    macAddress = byteArrayOf(0x01, 0xEE.toByte(), 0xDD.toByte(), 0xCC.toByte(), 0xBB.toByte(), 0xAA.toByte()),
                    firmwareVersion = 0,
                    verifyMode = 2,
                    verifyData = 0,
                ),
            ),
        )

        waitUntil { bleClient.writes.size >= 2 }
        val responseFrame = DjiFrameCodec.decode(bleClient.writes[1]).frame
        assertEquals(CommandIds.CMD_SET_COMMON, responseFrame.cmdSet)
        assertEquals(CommandIds.CONNECTION, responseFrame.cmdId)
        assertEquals(0x20, responseFrame.cmdType)
        assertEquals(0x0BADBEEFL, ByteOrder.u32(responseFrame.payload, 0))
        assertEquals(0x1234, responseFrame.seq)
    }

    @Test
    fun version_query_waits_for_response_payload() = runBlocking {
        val bleClient = FakeBleClient()
        val controller = BleSessionController(bleClient)

        controller.requestVersion()

        assertEquals("Pending real response", controller.status.value.latestVersion)
        val requestFrame = DjiFrameCodec.decode(bleClient.writes.first()).frame
        assertEquals(CommandIds.CMD_SET_COMMON, requestFrame.cmdSet)
        assertEquals(CommandIds.VERSION_QUERY, requestFrame.cmdId)
        assertEquals(0x02, requestFrame.cmdType)

        bleClient.emitNotification(
            rawFrameFor(
                cmdSet = CommandIds.CMD_SET_COMMON,
                cmdId = CommandIds.VERSION_QUERY,
                cmdType = 0x20,
                payload = versionPayload("OA5P", "01.02.03"),
            ),
        )

        waitUntil { controller.status.value.latestVersion?.contains("OA5P") == true }
        assertTrue(controller.status.value.latestVersion?.contains("01.02.03") == true)
    }

    @Test
    fun reboot_camera_sends_0016_command() = runBlocking {
        val bleClient = FakeBleClient()
        val controller = BleSessionController(bleClient)

        controller.rebootCamera()

        val frame = DjiFrameCodec.decode(bleClient.writes.first()).frame
        assertEquals(CommandIds.CMD_SET_COMMON, frame.cmdSet)
        assertEquals(CommandIds.REBOOT, frame.cmdId)
        assertEquals(0x02, frame.cmdType)
    }

    @Test
    fun toggle_recording_prefers_key_report_path() = runBlocking {
        val bleClient = FakeBleClient()
        val controller = BleSessionController(bleClient)

        controller.toggleRecording()

        assertTrue(!controller.cameraStatus.value.recording)
        assertTrue(controller.cameraStatus.value.detail.contains("requested"))
        val keyFrame = DjiFrameCodec.decode(bleClient.writes.first()).frame
        assertEquals(CommandIds.CMD_SET_COMMON, keyFrame.cmdSet)
        assertEquals(CommandIds.KEY_REPORT, keyFrame.cmdId)
        assertEquals(0x01, keyFrame.payload[0].toInt() and 0xFF)

        bleClient.emitNotification(
            frameFor(
                cmdSet = CommandIds.CMD_SET_COMMON,
                cmdId = CommandIds.KEY_REPORT,
                cmdType = 0x20,
                payload = com.alliot.osmo.demo.protocol.payload.AckPayload(0),
            ),
        )
        bleClient.emitNotification(
            frameFor(
                cmdSet = CommandIds.CMD_SET_CAMERA,
                cmdId = CommandIds.CAMERA_STATUS_PUSH,
                cmdType = 0x00,
                payload = CameraStatusPayload(
                    cameraMode = 0x01,
                    cameraStatus = 0x03,
                    videoResolution = 16,
                    fpsIndex = 3,
                    eisMode = 1,
                    recordTimeSeconds = 1,
                    remainCapacityMb = 4096,
                    remainPhotoCount = 99,
                    remainTimeSeconds = 600,
                    userMode = 0,
                    powerMode = 0,
                    batteryPercent = 84,
                ),
            ),
        )

        waitUntil { controller.cameraStatus.value.recording }
        assertTrue(controller.cameraStatus.value.recording)
    }

    @Test
    fun photo_mode_toggle_recording_does_not_fallback_to_1d03_after_key_report_timeout() = runBlocking {
        val bleClient = FakeBleClient()
        val controller = BleSessionController(bleClient)

        bleClient.emitNotification(
            frameFor(
                cmdSet = CommandIds.CMD_SET_CAMERA,
                cmdId = CommandIds.CAMERA_STATUS_PUSH,
                cmdType = 0x00,
                payload = CameraStatusPayload(
                    cameraMode = 0x05,
                    cameraStatus = 0x01,
                    videoResolution = 16,
                    fpsIndex = 3,
                    eisMode = 1,
                    recordTimeSeconds = 0,
                    remainCapacityMb = 4096,
                    remainPhotoCount = 99,
                    remainTimeSeconds = 600,
                    userMode = 0,
                    powerMode = 0,
                    batteryPercent = 84,
                ),
            ),
        )
        waitUntil { controller.cameraStatus.value.mode == 0x05 }

        controller.toggleRecording()
        assertEquals(1, bleClient.writes.size)

        delay(3_200)

        assertEquals(1, bleClient.writes.size)
        val frame = DjiFrameCodec.decode(bleClient.writes.single()).frame
        assertEquals(CommandIds.CMD_SET_COMMON, frame.cmdSet)
        assertEquals(CommandIds.KEY_REPORT, frame.cmdId)
        assertEquals(0x01, frame.payload[0].toInt() and 0xFF)
    }

    @Test
    fun wake_and_snapshot_uses_record_key_when_awake_and_ready() = runBlocking {
        val bleClient = FakeBleClient()
        val controller = BleSessionController(bleClient)
        val device = SessionDevice("Osmo Action 5 Pro", "AA:BB:CC:DD:EE:01", 0xFF44)

        controller.connect(device)
        bleClient.emitNotification(
            frameFor(
                cmdSet = CommandIds.CMD_SET_COMMON,
                cmdId = CommandIds.CONNECTION,
                cmdType = 0x00,
                payload = CameraConnectionConfirmationPayload(
                    deviceId = 0xFF44,
                    macAddress = byteArrayOf(0x01, 0xEE.toByte(), 0xDD.toByte(), 0xCC.toByte(), 0xBB.toByte(), 0xAA.toByte()),
                    firmwareVersion = 0,
                    verifyMode = 2,
                    verifyData = 0,
                ),
            ),
        )
        waitUntil { controller.status.value.protocolReady }
        bleClient.writes.clear()

        controller.wakeAndSnapshot()

        waitUntil { bleClient.writes.isNotEmpty() }
        val frame = DjiFrameCodec.decode(bleClient.writes.last()).frame
        assertEquals(CommandIds.CMD_SET_COMMON, frame.cmdSet)
        assertEquals(CommandIds.KEY_REPORT, frame.cmdId)
        assertEquals(0x01, frame.payload[0].toInt() and 0xFF)
    }

    @Test
    fun gps_push_uses_no_response_command_type() = runBlocking {
        val bleClient = FakeBleClient()
        val controller = BleSessionController(bleClient)

        controller.pushGps(31.2304, 121.4737, 15.0)

        val frame = DjiFrameCodec.decode(bleClient.writes.first()).frame
        assertEquals(CommandIds.CMD_SET_COMMON, frame.cmdSet)
        assertEquals(CommandIds.GPS_PUSH, frame.cmdId)
        assertEquals(0x00, frame.cmdType)
    }

    @Test
    fun gps_push_uses_point_speed_and_bearing_for_payload_and_status() = runBlocking {
        val bleClient = FakeBleClient()
        val controller = BleSessionController(
            bleClient = bleClient,
            gpsPointProvider = {
                com.alliot.osmo.demo.session.model.SessionGpsPoint(
                    latitude = 31.2304,
                    longitude = 121.4737,
                    altitudeMeters = 18.5,
                    speedMps = 10f,
                    bearingDegrees = 90f,
                )
            },
        )

        controller.pushGps(31.0, 121.0, 15.0)

        val frame = DjiFrameCodec.decode(bleClient.writes.first()).frame
        val payload = ByteBuffer.wrap(frame.payload).order(LITTLE_ENDIAN)
        assertEquals(18500, payload.getInt(16))
        assertEquals(0f, payload.getFloat(20), 0.5f)
        assertEquals(1000f, payload.getFloat(24), 0.5f)
        assertEquals(18.5, controller.status.value.lastGpsAltitudeMeters ?: 0.0, 0.001)
        assertEquals(10f, controller.status.value.lastGpsSpeedMps ?: 0f, 0.001f)
        assertEquals(90f, controller.status.value.lastGpsBearingDegrees ?: 0f, 0.001f)
    }

    @Test
    fun sleep_uses_response_or_not_command_type() = runBlocking {
        val bleClient = FakeBleClient()
        val controller = BleSessionController(bleClient)

        controller.sleep()

        val frame = DjiFrameCodec.decode(bleClient.writes.first()).frame
        assertEquals(CommandIds.CMD_SET_COMMON, frame.cmdSet)
        assertEquals(CommandIds.POWER_MODE, frame.cmdId)
        assertEquals(0x01, frame.cmdType)
    }

    @Test
    fun sleep_immediately_updates_local_camera_status_to_sleeping() = runBlocking {
        val bleClient = FakeBleClient()
        val controller = BleSessionController(bleClient)

        controller.sleep()

        assertTrue(controller.status.value.sleeping)
        assertEquals(3, controller.cameraStatus.value.powerMode)
        assertEquals("Sleeping", controller.cameraStatus.value.powerModeLabel)
        assertEquals("Sleeping", controller.cameraStatus.value.detail)
    }

    @Test
    fun pairing_rejected_disconnects_link() = runBlocking {
        val bleClient = FakeBleClient()
        val controller = BleSessionController(bleClient)
        val device = SessionDevice("Osmo Action 5 Pro", "AA:BB:CC:DD:EE:01", 0xFF44)

        controller.connect(device)
        bleClient.emitNotification(
            frameFor(
                cmdSet = CommandIds.CMD_SET_COMMON,
                cmdId = CommandIds.CONNECTION,
                cmdType = 0x00,
                payload = CameraConnectionConfirmationPayload(
                    deviceId = 0xFF44,
                    macAddress = byteArrayOf(0x01, 0xEE.toByte(), 0xDD.toByte(), 0xCC.toByte(), 0xBB.toByte(), 0xAA.toByte()),
                    firmwareVersion = 0,
                    verifyMode = 2,
                    verifyData = 1,
                ),
            ),
        )

        waitUntil { controller.status.value.handshakeStage == HandshakeStage.REJECTED }
        assertEquals(1, bleClient.disconnectCalls)
    }

    @Test
    fun pairing_rejected_disconnect_clears_connected_profile() = runBlocking {
        val bleClient = FakeBleClient()
        val controller = BleSessionController(bleClient)
        val device = SessionDevice("Osmo Action 5 Pro", "AA:BB:CC:DD:EE:01", 0xFF44)

        controller.connect(device)
        waitUntil { controller.status.value.connectedProfile != null }

        bleClient.emitNotification(
            frameFor(
                cmdSet = CommandIds.CMD_SET_COMMON,
                cmdId = CommandIds.CONNECTION,
                cmdType = 0x00,
                payload = CameraConnectionConfirmationPayload(
                    deviceId = 0xFF44,
                    macAddress = byteArrayOf(0x01, 0xEE.toByte(), 0xDD.toByte(), 0xCC.toByte(), 0xBB.toByte(), 0xAA.toByte()),
                    firmwareVersion = 0,
                    verifyMode = 2,
                    verifyData = 1,
                ),
            ),
        )

        waitUntil { controller.status.value.connectedDevice == null }
        assertEquals(HandshakeStage.REJECTED, controller.status.value.handshakeStage)
        assertEquals(null, controller.status.value.connectedProfile)
    }

    @Test
    fun sleeping_state_blocks_protocol_commands_until_wake() = runBlocking {
        val bleClient = FakeBleClient()
        val controller = BleSessionController(bleClient)

        controller.sleep()
        assertEquals(1, bleClient.writes.size)

        controller.requestVersion()
        assertEquals(1, bleClient.writes.size)
        assertTrue(controller.status.value.latestError?.contains("sleep", ignoreCase = true) == true)
    }

    @Test
    fun protocol_ready_starts_auto_gps_push_stream() = runBlocking {
        val bleClient = FakeBleClient()
        val controller = BleSessionController(
            bleClient = bleClient,
            gpsPointProvider = {
                com.alliot.osmo.demo.session.model.SessionGpsPoint(
                    latitude = 31.2304,
                    longitude = 121.4737,
                    altitudeMeters = 18.5,
                )
            },
        )
        val device = SessionDevice("Osmo Action 5 Pro", "AA:BB:CC:DD:EE:01", 0xFF44)

        controller.connect(device)
        bleClient.emitNotification(
            frameFor(
                cmdSet = CommandIds.CMD_SET_COMMON,
                cmdId = CommandIds.CONNECTION,
                cmdType = 0x00,
                payload = CameraConnectionConfirmationPayload(
                    deviceId = 0xFF44,
                    macAddress = byteArrayOf(0x01, 0xEE.toByte(), 0xDD.toByte(), 0xCC.toByte(), 0xBB.toByte(), 0xAA.toByte()),
                    firmwareVersion = 0,
                    verifyMode = 2,
                    verifyData = 0,
                ),
            ),
        )

        waitUntil(timeoutMs = 1_500) {
            bleClient.writes.any { frame ->
                val decoded = DjiFrameCodec.decode(frame).frame
                decoded.cmdSet == CommandIds.CMD_SET_COMMON && decoded.cmdId == CommandIds.GPS_PUSH
            }
        }
        waitUntil(timeoutMs = 1_500) { controller.status.value.gpsPushActive }
    }

    @Test
    fun missing_camera_status_pushes_force_disconnect_and_reconnect() = runBlocking {
        val bleClient = FakeBleClient()
        val controller = BleSessionController(
            bleClient = bleClient,
            statusWatchdogPollIntervalMs = 20L,
            statusPushIdleTimeoutMs = 60L,
            statusProbeTimeoutMs = 60L,
        )
        val device = SessionDevice("Osmo Action 5 Pro", "AA:BB:CC:DD:EE:01", 0xFF44)

        controller.setGpsAutoPushEnabled(false)
        controller.connect(device)
        bleClient.emitNotification(
            frameFor(
                cmdSet = CommandIds.CMD_SET_COMMON,
                cmdId = CommandIds.CONNECTION,
                cmdType = 0x00,
                payload = CameraConnectionConfirmationPayload(
                    deviceId = 0xFF44,
                    macAddress = byteArrayOf(0x01, 0xEE.toByte(), 0xDD.toByte(), 0xCC.toByte(), 0xBB.toByte(), 0xAA.toByte()),
                    firmwareVersion = 0,
                    verifyMode = 2,
                    verifyData = 0,
                ),
            ),
        )
        waitUntil { controller.status.value.protocolReady }
        bleClient.writes.clear()

        waitUntil(timeoutMs = 1_500) { bleClient.disconnectCalls >= 1 }
        waitUntil(timeoutMs = 2_500) {
            bleClient.writes.any { frame ->
                val decoded = DjiFrameCodec.decode(frame).frame
                decoded.cmdSet == CommandIds.CMD_SET_COMMON && decoded.cmdId == CommandIds.CONNECTION
            }
        }
        assertEquals(HandshakeStage.REQUEST_SENT, controller.status.value.handshakeStage)
        assertTrue(!controller.status.value.protocolReady)
    }

    @Test
    fun refresh_devices_preserves_scan_result_order_for_stable_ui() = runBlocking {
        val bleClient = FakeBleClient()
        val controller = BleSessionController(bleClient)

        bleClient.setScanResults(
            listOf(
                BleScanResult("Pocket 3", "AA:BB:CC:DD:EE:02", -90),
                BleScanResult("Osmo Action 5 Pro", "AA:BB:CC:DD:EE:01", -35),
            ),
        )

        waitUntil { controller.devices.value.size == 2 }
        assertEquals(
            listOf("AA:BB:CC:DD:EE:02", "AA:BB:CC:DD:EE:01"),
            controller.devices.value.map(SessionDevice::macAddress),
        )
    }

    @Test
    fun refresh_devices_infers_protocol_family_and_workbench_support() = runBlocking {
        val bleClient = FakeBleClient()
        val controller = BleSessionController(bleClient)

        bleClient.setScanResults(
            listOf(
                BleScanResult("Pocket 3", "AA:BB:CC:DD:EE:02", -90),
                BleScanResult("Osmo Action 5 Pro", "AA:BB:CC:DD:EE:01", -35),
            ),
        )

        waitUntil { controller.devices.value.size == 2 }
        val pocket = controller.devices.value.first { it.name == "Pocket 3" }
        val action = controller.devices.value.first { it.name == "Osmo Action 5 Pro" }

        assertEquals(0L, pocket.deviceId)
        assertEquals(ProtocolFamily.POCKET3_DUML, pocket.inferredProtocolFamily)
        assertEquals(CameraFamily.POCKET_3, pocket.inferredCameraFamily)
        assertFalse(pocket.workbenchSupported)

        assertEquals(ProtocolFamily.DJI_RSDK_ACTION, action.inferredProtocolFamily)
        assertEquals(CameraFamily.ACTION_5_PRO, action.inferredCameraFamily)
        assertTrue(action.workbenchSupported)
    }

    @Test
    fun version_response_refines_connected_profile_product_id() = runBlocking {
        val bleClient = FakeBleClient()
        val controller = BleSessionController(bleClient)
        val device = SessionDevice("Osmo Action 5 Pro", "AA:BB:CC:DD:EE:01", 0xFF44)

        controller.connect(device)
        bleClient.emitNotification(
            frameFor(
                cmdSet = CommandIds.CMD_SET_COMMON,
                cmdId = CommandIds.CONNECTION,
                cmdType = 0x00,
                payload = CameraConnectionConfirmationPayload(
                    deviceId = 0xFF44,
                    macAddress = byteArrayOf(0x01, 0xEE.toByte(), 0xDD.toByte(), 0xCC.toByte(), 0xBB.toByte(), 0xAA.toByte()),
                    firmwareVersion = 0,
                    verifyMode = 2,
                    verifyData = 0,
                ),
            ),
        )
        waitUntil { controller.status.value.connectedProfile != null }

        bleClient.emitNotification(
            rawFrameFor(
                cmdSet = CommandIds.CMD_SET_COMMON,
                cmdId = CommandIds.VERSION_QUERY,
                cmdType = 0x20,
                payload = versionPayload("OA5P", "01.02.03"),
            ),
        )

        waitUntil { controller.status.value.connectedProfile?.productId == "OA5P" }
        val profile = controller.status.value.connectedProfile
        assertEquals(CameraFamily.ACTION_5_PRO, profile?.cameraFamily)
        assertEquals("OA5P", profile?.productId)
        assertTrue(profile?.capabilities?.supportsWorkbench == true)
    }

    @Test
    fun stop_scan_keeps_last_visible_devices() = runBlocking {
        val bleClient = FakeBleClient()
        val controller = BleSessionController(bleClient)
        bleClient.setScanResults(
            listOf(
                BleScanResult("Osmo Action 5 Pro", "AA:BB:CC:DD:EE:01", -40),
                BleScanResult("Pocket 3", "AA:BB:CC:DD:EE:02", -50),
            ),
        )
        waitUntil { controller.devices.value.size == 2 }

        controller.stopScan()

        assertEquals(2, controller.devices.value.size)
        assertEquals(
            listOf("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02"),
            controller.devices.value.map(SessionDevice::macAddress),
        )
    }

    private fun frameFor(
        cmdSet: Int,
        cmdId: Int,
        cmdType: Int,
        seq: Int = 1,
        payload: com.alliot.osmo.demo.protocol.payload.ProtocolPayload,
    ): ByteArray {
        val encodedPayload = PayloadCodec.encode(cmdSet, cmdId, payload).drop(2).toByteArray()
        return DjiFrameCodec.encode(
            DjiFrame(
                cmdType = cmdType,
                seq = seq,
                cmdSet = cmdSet,
                cmdId = cmdId,
                payload = encodedPayload,
            ),
        )
    }

    private suspend fun waitUntil(timeoutMs: Long = 1_000, condition: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) {
                delay(10)
            }
        }
    }

    private fun rawFrameFor(
        cmdSet: Int,
        cmdId: Int,
        cmdType: Int,
        payload: ByteArray,
    ): ByteArray {
        return DjiFrameCodec.encode(
            DjiFrame(
                cmdType = cmdType,
                seq = 1,
                cmdSet = cmdSet,
                cmdId = cmdId,
                payload = payload,
            ),
        )
    }

    private fun versionPayload(productId: String, sdkVersion: String): ByteArray {
        val productBytes = productId.toByteArray().copyOf(16)
        val versionBytes = sdkVersion.toByteArray()
        return byteArrayOf(0, 0) + productBytes + versionBytes
    }
}

internal class FakeBleClient(
    private val localAdapterAddress: String = randomMacAddress(),
) : BleClient {
    private val _scanResults = MutableStateFlow(
        listOf(BleScanResult("Osmo Action 5 Pro", "AA:BB:CC:DD:EE:01", -40)),
    )
    override val scanResults = _scanResults.asStateFlow()

    private val _connectionState = MutableStateFlow(
        BleConnectionState(
            isBluetoothEnabled = true,
            isConnected = false,
            localAdapterAddress = localAdapterAddress,
        ),
    )
    override val connectionState = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<BleEvent>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<BleEvent> = _events.asSharedFlow()

    val writes = CopyOnWriteArrayList<ByteArray>()
    var disconnectCalls: Int = 0

    override suspend fun startScan() {
        _events.emit(BleEvent.ScanStarted)
    }

    override suspend fun stopScan() {
        _events.emit(BleEvent.ScanStopped)
    }

    override suspend fun connect(macAddress: String) {
        _connectionState.value = BleConnectionState(
            isBluetoothEnabled = true,
            isConnected = true,
            connectedAddress = macAddress,
            localAdapterAddress = localAdapterAddress,
        )
        _events.emit(BleEvent.Connected(macAddress))
    }

    override suspend fun disconnect() {
        disconnectCalls += 1
        _connectionState.value = BleConnectionState(
            isBluetoothEnabled = true,
            isConnected = false,
            localAdapterAddress = localAdapterAddress,
        )
        _events.emit(BleEvent.Disconnected(null))
    }

    override suspend fun write(bytes: ByteArray) {
        writes += bytes
        _events.emit(BleEvent.Write(bytes))
    }

    override suspend fun startWakeAdvertising(reversedMac: ByteArray) {
        _events.emit(BleEvent.Write(reversedMac))
    }

    fun setScanResults(results: List<BleScanResult>) {
        _scanResults.value = results
    }

    suspend fun emitNotification(bytes: ByteArray) {
        _events.emit(BleEvent.Notification(bytes))
    }

    suspend fun emitDisconnected(macAddress: String, reason: String? = null) {
        _connectionState.value = BleConnectionState(
            isBluetoothEnabled = true,
            isConnected = false,
            localAdapterAddress = localAdapterAddress,
        )
        _events.emit(BleEvent.Disconnected(macAddress, reason))
    }

    fun dropConnectionStateWithoutEvent(macAddress: String) {
        _connectionState.value = BleConnectionState(
            isBluetoothEnabled = true,
            isConnected = false,
            connectedAddress = macAddress,
            localAdapterAddress = localAdapterAddress,
        )
    }
}

private fun randomMacAddress(): String {
    val bytes = ByteArray(6).also(Random::nextBytes)
    bytes[0] = (((bytes[0].toInt() and 0xFC) or 0x02) and 0xFF).toByte()
    return bytes.joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xFF) }
}
