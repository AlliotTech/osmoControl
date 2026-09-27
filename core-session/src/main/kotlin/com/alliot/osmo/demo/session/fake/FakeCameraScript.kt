package com.alliot.osmo.demo.session.fake

import com.alliot.osmo.demo.protocol.duml.DumlFrameCodec
import com.alliot.osmo.demo.protocol.duml.GattWakeSequence
import com.alliot.osmo.demo.session.model.CameraStatusSnapshot
import com.alliot.osmo.demo.session.model.SessionDevice

object FakeCameraScript {
    val devices = listOf(
        SessionDevice(name = "Osmo Action 5 Pro", macAddress = "AA:BB:CC:DD:EE:01", deviceId = 0xFF44),
        SessionDevice(name = "Osmo 360", macAddress = "AA:BB:CC:DD:EE:02", deviceId = 0xFF66),
    )

    fun initialStatus(device: SessionDevice): CameraStatusSnapshot {
        return CameraStatusSnapshot(
            mode = 0x01,
            state = 0x01,
            recording = false,
            powerMode = 0,
            batteryPercent = 87,
            remainTimeSeconds = 3_600,
            detail = "${device.name} ready",
            lastPushCommandId = "1D02",
            lastPushSummary = "Fake status initialized",
        )
    }

    /** Scripted DUML GATT wake sequence the fake camera pretends to receive. */
    fun gattWakeScriptFrames(pairingIdentifier: String = "fake-pairing-identifier-00000001"): List<ByteArray> {
        var messageId = 0
        fun nextId(): Int {
            messageId += 1
            return messageId
        }
        return listOf(
            DumlFrameCodec.encode(GattWakeSequence.buildSessionOpenFrame(nextId())),
            DumlFrameCodec.encode(GattWakeSequence.buildSetPairingPinFrame(nextId(), pairingIdentifier)),
            DumlFrameCodec.encode(GattWakeSequence.buildSessionKeepaliveFrame(nextId())),
            DumlFrameCodec.encode(GattWakeSequence.buildWakeCameraFrame(nextId())),
        )
    }

    /** Camera-side wake reply: 0x53/0x10 payload 01 00 00 00. */
    val gattWakeReplyPayload: ByteArray = byteArrayOf(0x01, 0x00, 0x00, 0x00)
}
