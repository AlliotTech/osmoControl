package com.alliot.osmo.demo.session.real

import com.alliot.osmo.demo.protocol.duml.DumlCmdSet
import com.alliot.osmo.demo.protocol.duml.DumlDecodedFrame
import com.alliot.osmo.demo.protocol.duml.DumlFlags
import com.alliot.osmo.demo.protocol.duml.DumlFrame
import com.alliot.osmo.demo.protocol.duml.DumlFrameCodec
import com.alliot.osmo.demo.protocol.duml.DumlWakeCmd
import com.alliot.osmo.demo.protocol.duml.DumlWifiCmd
import com.alliot.osmo.demo.session.model.SessionDevice
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GattWakeTest {
    @Test
    fun gatt_wake_succeeds_on_wake_reply() = runBlocking {
        val bleClient = FakeBleClient()
        val controller = BleSessionController(
            bleClient,
            wakeWriteSpacingMs = 10,
            wakeReplyTimeoutMs = 2_000,
            wakeBackoffBaseMs = 10,
        )
        val device = SessionDevice("Osmo Action 5 Pro", "AA:BB:CC:DD:EE:01", 0xFF44)

        controller.connect(device)
        waitUntil { bleClient.writes.isNotEmpty() }

        val wakeJob = launch { controller.wakeViaGatt() }
        waitUntil(timeoutMs = 2_000) { wakeCommandWrites(bleClient).isNotEmpty() }
        bleClient.emitNotification(wakeReplyFrame())
        wakeJob.join()

        assertTrue(
            "expected GATT wake success, got: ${controller.status.value.lastWakeResult}",
            controller.status.value.lastWakeResult?.contains("GATT wake OK") == true,
        )
    }

    @Test
    fun gatt_wake_retries_three_times_then_falls_back_to_advertising() = runBlocking {
        val bleClient = FakeBleClient()
        val controller = BleSessionController(
            bleClient,
            wakeWriteSpacingMs = 10,
            wakeReplyTimeoutMs = 300,
            wakeBackoffBaseMs = 10,
        )
        val device = SessionDevice("Osmo Action 5 Pro", "AA:BB:CC:DD:EE:01", 0xFF44)

        controller.connect(device)
        waitUntil { bleClient.writes.isNotEmpty() }
        val writesBeforeWake = bleClient.writes.size

        controller.wakeViaGatt()

        val wakeWrites = wakeCommandWrites(bleClient)
        assertEquals(3, wakeWrites.size)
        // Falls back to the legacy advertising wake after the last attempt.
        assertEquals(
            "Advertising sent; waiting for wake event",
            controller.status.value.lastWakeResult,
        )
        assertTrue(bleClient.writes.size > writesBeforeWake)
    }

    @Test
    fun gatt_wake_waits_for_camera_approval_then_succeeds() = runBlocking {
        val bleClient = FakeBleClient(autoPairingStatus = 0x02)
        val controller = BleSessionController(
            bleClient,
            wakeWriteSpacingMs = 10,
            wakeReplyTimeoutMs = 2_000,
            pairingTimeoutMs = 5_000,
            wakeBackoffBaseMs = 10,
        )
        val device = SessionDevice("Osmo Action 5 Pro", "AA:BB:CC:DD:EE:01", 0xFF44)

        controller.connect(device)
        val wakeJob = launch { controller.wakeViaGatt() }

        // Camera asks for on-camera approval; the wake command must wait for it.
        waitUntil(timeoutMs = 2_000) {
            controller.status.value.lastWakeResult?.contains("请在相机屏幕上确认配对") == true
        }
        assertTrue(wakeCommandWrites(bleClient).isEmpty())

        // User approves on the camera: 0x07/0x46 request, then the wake reply.
        bleClient.emitNotification(pairingApprovalFrame())
        waitUntil(timeoutMs = 2_000) { wakeCommandWrites(bleClient).isNotEmpty() }
        assertTrue(
            "expected the 0x07/0x46 approval request to be ACKed",
            bleClient.writes.any { bytes ->
                runCatching { DumlFrameCodec.decode(bytes) }
                    .map { decoded: DumlDecodedFrame ->
                        decoded.cmdSet == DumlCmdSet.WIFI &&
                            decoded.cmdId == DumlWifiCmd.PAIRING_APPROVED &&
                            decoded.flags == DumlFlags.RESPONSE &&
                            decoded.payload.contentEquals(byteArrayOf(0x00))
                    }
                    .getOrDefault(false)
            },
        )
        bleClient.emitNotification(wakeReplyFrame())
        wakeJob.join()

        assertTrue(
            "expected GATT wake success, got: ${controller.status.value.lastWakeResult}",
            controller.status.value.lastWakeResult?.contains("GATT wake OK") == true,
        )
    }

    @Test
    fun gatt_wake_fails_when_pairing_never_answers() = runBlocking {
        val bleClient = FakeBleClient(autoPairingStatus = null)
        val controller = BleSessionController(
            bleClient,
            wakeWriteSpacingMs = 10,
            wakeReplyTimeoutMs = 300,
            pairingTimeoutMs = 300,
            wakeBackoffBaseMs = 10,
            wakeMaxAttempts = 1,
        )
        val device = SessionDevice("Osmo Action 5 Pro", "AA:BB:CC:DD:EE:01", 0xFF44)

        controller.connect(device)
        controller.wakeViaGatt()

        assertTrue(wakeCommandWrites(bleClient).isEmpty())
        assertEquals(
            "Advertising sent; waiting for wake event",
            controller.status.value.lastWakeResult,
        )
    }

    @Test
    fun gatt_wake_requires_known_device() = runBlocking {
        val bleClient = FakeBleClient()
        val controller = BleSessionController(bleClient)

        controller.wakeViaGatt()

        assertEquals(
            "GATT wake needs a known camera; scan and pick a device first.",
            controller.status.value.latestError,
        )
        assertTrue(bleClient.writes.isEmpty())
    }

    private fun wakeCommandWrites(bleClient: FakeBleClient): List<ByteArray> {
        return bleClient.writes.filter { bytes ->
            bytes.isNotEmpty() && bytes[0] == 0x55.toByte() &&
                runCatching { DumlFrameCodec.decode(bytes) }
                    .map { decoded: DumlDecodedFrame ->
                        decoded.cmdSet == DumlCmdSet.WAKE && decoded.cmdId == DumlWakeCmd.WAKE_CAMERA
                    }
                    .getOrDefault(false)
        }
    }

    private fun pairingApprovalFrame(): ByteArray {
        return DumlFrameCodec.encode(
            DumlFrame(
                target = 0x02F0,
                messageId = 2,
                flags = DumlFlags.REQUEST,
                cmdSet = DumlCmdSet.WIFI,
                cmdId = DumlWifiCmd.PAIRING_APPROVED,
                payload = byteArrayOf(0x01),
            ),
        )
    }

    private fun wakeReplyFrame(): ByteArray {
        return DumlFrameCodec.encode(
            DumlFrame(
                target = 0x021C,
                messageId = 1,
                flags = DumlFlags.RESPONSE,
                cmdSet = DumlCmdSet.WAKE,
                cmdId = DumlWakeCmd.WAKE_CAMERA,
                payload = byteArrayOf(0x01, 0x00, 0x00, 0x00),
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
}
