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
}
