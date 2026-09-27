package com.alliot.osmo.demo.protocol.duml

/**
 * Reliable BLE GATT wake sequence for a sleeping Osmo camera.
 *
 * Protocol reference: osmosis MEDIA_PROTOCOL.md "Waking a sleeping camera" (§21).
 * A sleeping camera keeps advertising ADV_IND; the wake is a DUML command sequence
 * over GATT 0xFFF5 addressed at the session endpoints 0xF0 / 0x1C. Addressing the
 * wake at 0x01 answers 0xE0 and the camera keeps sleeping.
 */
object GattWakeSequence {
    const val PAIRING_TOKEN_OSMO: String = "osmo"

    /** 0x07/0x45 response status: camera already remembers this identifier. */
    const val PAIR_STATUS_ALREADY_PAIRED: Int = 0x01

    /** 0x07/0x45 response status: user must confirm pairing on the camera. */
    const val PAIR_STATUS_APPROVAL_REQUIRED: Int = 0x02

    /**
     * App device-info blob answering the camera's inbound 0x00/0x81 request,
     * so it accepts us as a live app (mirrors osmosis' APP_DEVICE_INFO).
     */
    fun appDeviceInfoPayload(): ByteArray =
        byteArrayOf(0x00, 0x41, 0x50, 0x50) + ByteArray(37) +
            byteArrayOf(0x02) + ByteArray(8) + byteArrayOf(0x02, 0x08) + ByteArray(10)

    fun sessionOpenPayload(): ByteArray = byteArrayOf(0x04, 0x00)

    fun sessionKeepalivePayload(): ByteArray = byteArrayOf(0x01, 0x01)

    fun wakeCameraPayload(): ByteArray = byteArrayOf(0x00, 0x00, 0x00, 0x00)

    fun isWakeReplyPayload(payload: ByteArray): Boolean =
        payload.size == 4 &&
            payload[0] == 0x01.toByte() &&
            payload[1] == 0x00.toByte() &&
            payload[2] == 0x00.toByte() &&
            payload[3] == 0x00.toByte()

    fun buildSessionOpenFrame(messageId: Int): DumlFrame = DumlFrame.request(
        target = DumlTargets.APP_TO_SESSION,
        messageId = messageId,
        cmdSet = DumlCmdSet.GENERAL,
        cmdId = DumlGeneralCmd.SESSION_WAKE_KEEPALIVE,
        payload = sessionOpenPayload(),
    )

    fun buildSessionKeepaliveFrame(messageId: Int): DumlFrame = DumlFrame.request(
        target = DumlTargets.APP_TO_SESSION,
        messageId = messageId,
        cmdSet = DumlCmdSet.GENERAL,
        cmdId = DumlGeneralCmd.SESSION_WAKE_KEEPALIVE,
        payload = sessionKeepalivePayload(),
    )

    fun buildSetPairingPinFrame(
        messageId: Int,
        identifier: String,
        token: String = PAIRING_TOKEN_OSMO,
    ): DumlFrame = DumlFrame.request(
        target = DumlTargets.APP_TO_WIFI,
        messageId = messageId,
        cmdSet = DumlCmdSet.WIFI,
        cmdId = DumlWifiCmd.SET_PAIRING_PIN,
        payload = DumlStringCodec.pack(identifier) + DumlStringCodec.pack(token),
    )

    fun buildWakeCameraFrame(messageId: Int): DumlFrame = DumlFrame.request(
        target = DumlTargets.APP_TO_WAKE,
        messageId = messageId,
        cmdSet = DumlCmdSet.WAKE,
        cmdId = DumlWakeCmd.WAKE_CAMERA,
        payload = wakeCameraPayload(),
    )

    fun isWakeReply(frame: DumlDecodedFrame): Boolean =
        frame.cmdSet == DumlCmdSet.WAKE &&
            frame.cmdId == DumlWakeCmd.WAKE_CAMERA &&
            isWakeReplyPayload(frame.payload)

    /** 0x07/0x45 SetPairingPIN response carrying the pairing status byte. */
    fun isPairingStatusFrame(frame: DumlDecodedFrame): Boolean =
        frame.cmdSet == DumlCmdSet.WIFI &&
            frame.cmdId == DumlWifiCmd.SET_PAIRING_PIN &&
            frame.flags != DumlFlags.REQUEST &&
            frame.payload.size >= 2

    /** Pairing status from a [isPairingStatusFrame]: 0x01 already paired, 0x02 approval required. */
    fun pairingStatus(frame: DumlDecodedFrame): Int =
        frame.payload[1].toInt() and 0xFF

    /**
     * 0x07/0x46 pairing approval. The camera signals first-time approval as a
     * REQUEST (flags 0x40); ACK it and treat pairing as approved.
     */
    fun isPairingApprovalFrame(frame: DumlDecodedFrame): Boolean =
        frame.cmdSet == DumlCmdSet.WIFI &&
            frame.cmdId == DumlWifiCmd.PAIRING_APPROVED

    /** 0x00/0x81 device-info request from the camera. */
    fun isDeviceInfoRequest(frame: DumlDecodedFrame): Boolean =
        frame.cmdSet == DumlCmdSet.GENERAL &&
            frame.cmdId == 0x81 &&
            frame.flags == DumlFlags.REQUEST

    /**
     * Builds the ACK for an incoming REQUEST frame: matching response
     * (flags 0xC0), swapped target, echoed message id. The camera drops the
     * link (~6s) when its requests go unanswered.
     */
    fun buildAckFrame(request: DumlDecodedFrame): DumlFrame {
        val swappedTarget = ((request.target and 0xFF) shl 8) or ((request.target ushr 8) and 0xFF)
        val payload = if (isDeviceInfoRequest(request)) appDeviceInfoPayload() else byteArrayOf(0x00)
        return DumlFrame(
            target = swappedTarget,
            messageId = request.messageId,
            flags = DumlFlags.RESPONSE,
            cmdSet = request.cmdSet,
            cmdId = request.cmdId,
            payload = payload,
        )
    }
}
