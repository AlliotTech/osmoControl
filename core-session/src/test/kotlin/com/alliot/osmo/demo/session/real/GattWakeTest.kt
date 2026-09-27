package com.alliot.osmo.demo.session.real

import com.alliot.osmo.demo.protocol.duml.DumlCmdSet
import com.alliot.osmo.demo.protocol.duml.DumlDecodedFrame
import com.alliot.osmo.demo.protocol.duml.DumlFlags
import com.alliot.osmo.demo.protocol.duml.DumlFrame
import com.alliot.osmo.demo.protocol.duml.DumlFrameCodec
import com.alliot.osmo.demo.protocol.duml.DumlWakeCmd
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
