package com.alliot.osmo.demo.media.datalink

import com.alliot.osmo.demo.protocol.duml.DumlDecodedFrame
import com.alliot.osmo.demo.protocol.duml.DumlStringCodec
import com.alliot.osmo.demo.protocol.duml.DumlTargets
import com.alliot.osmo.demo.protocol.duml.DumlV4Codec
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.random.Random

/**
 * Blocking DJI Wi-Fi UDP datalink session (camera AP gateway, UDP 9004).
 *
 * Bring-up order, mirroring osmosis `DumlSession.openDatalink` /
 * `CameraSession.openAndRegister`:
 *
 * 1. TCP poke to port 7001 (`setPairingPin("osmo")` frame, ~400 ms) —
 *    wakes the camera's datalink listener. Best-effort.
 * 2. pktType `0x00` handshake with a random 8-aligned base sequence until
 *    the peer answers with its own `0x00`.
 * 3. Drain heartbeats, latch the peer's sequence channel and window
 *    cursors, then `ownSeq = peerChannel + 8`.
 * 4. App registration: `0x00/0x81` device info + `0x00/0x88` presence
 *    (best-effort — whether the media query needs it is still
 *    real-device TBD).
 *
 * Commands go out in v4 (`DjiMessage`) framing; the camera answers in
 * classic v1 framing, so replies are scanned with the v1 frame codec.
 * While a manifest streams, the caller must keep calling [sendAck] —
 * the camera stalls any window it does not see acknowledged.
 *
 * Blocking: call off the UI thread. Reference: osmosis
 * `net/DumlTransport.kt`, `net/DumlSession.kt`, `camera/CameraSession.kt`.
 */
class DatalinkTransport(
    private val log: (String) -> Unit = {},
) {

    private var socket: DatagramSocket? = null
    private var peer: InetAddress? = null

    var sessionId: Int = 0
        private set
    var baseSeq: Int = 0
        private set

    /** The peer's sequence space, learned from its packets (bytes 8-9). */
    var camChannel: Int = 0
        private set
    var peerVideoCursor: Int = 0
        private set
    var peerDownloadCursor: Int = 0
        private set

    private var udpSeq: Int = 0
    private var cmdCounter: Int = 0
    private var dumlId: Int = 0

    val isOpen: Boolean get() = socket != null

    // ---- bring-up ----

    fun open(cameraIp: String, pairingIdentifier: String, tcpPoke: Boolean = true): Boolean {
        close()
        if (tcpPoke) runCatching { tcpPoke(cameraIp, pairingIdentifier) }
        val sock = DatagramSocket()
        sock.soTimeout = 200
        socket = sock
        peer = InetAddress.getByName(cameraIp)
        sessionId = Random.nextInt(0x1000, 0xFFFE)
        baseSeq = Random.nextInt(0x1000, 0xF000) and 0xFFF8
        camChannel = baseSeq
        udpSeq = baseSeq
        peerVideoCursor = 0
        peerDownloadCursor = 0
        cmdCounter = 0
        dumlId = 0

        if (!handshake()) {
            log("datalink: handshake FAILED on udp/${DatalinkCodec.UDP_PORT}")
            close()
            return false
        }
        log("datalink: handshake OK on udp/${DatalinkCodec.UDP_PORT}")

        // Drain heartbeats, learn the peer's channel, set our seq start.
        repeat(5) {
            recvAll(400)
            sendAck()
        }
        udpSeq = (camChannel + 8) and 0xFFFF
        log(
            "datalink: session=0x%04x base=0x%04x channel=0x%04x".format(
                sessionId, baseSeq, camChannel,
            ),
        )
        return true
    }

    /** App registration (`0x00/0x81` + `0x00/0x88`). Fire-and-forget, like osmosis. */
    fun registerApp() {
        sendDuml(
            0x00, 0x81, appDeviceInfo(),
            target = targetFor(receiverType = 0x08, receiverId = 2), cmdType = 4,
        )
        recvAll(400)
        sendAck()
        sendDuml(
            0x00, 0x88, APP_PRESENCE,
            target = targetFor(receiverType = 0x08, receiverId = 1), cmdType = 4,
        )
        recvAll(400)
        sendAck()
        log("datalink: registerApp sent")
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
        peer = null
    }

    // ---- send ----

    /**
     * Sends one DUML command in v4 framing and waits for the first
     * non-empty reply frame matching `(set, cmd)`. The empty-payload
     * transport ACK the camera sends first is skipped. Null on timeout.
     */
    fun query(
        set: Int,
        cmd: Int,
        payload: ByteArray,
        target: Int = DumlTargets.APP_TO_CAMERA,
        cmdType: Int = 2,
        timeoutMs: Long = 3000,
    ): DumlDecodedFrame? {
        sendDuml(set, cmd, payload, target, cmdType)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            for (datagram in recvAll(200)) {
                findReplyFrame(datagram, set, cmd)?.let { return it }
            }
            sendAck()
        }
        return null
    }

    /**
     * Fire-and-forget command. Used for streaming queries (`0x00/0x26`),
     * whose answer arrives as many datagrams the caller drains itself.
     */
    fun sendDuml(
        set: Int,
        cmd: Int,
        payload: ByteArray,
        target: Int = DumlTargets.APP_TO_CAMERA,
        cmdType: Int = 2,
    ) {
        cmdCounter++
        dumlId = (dumlId + 1) and 0xFFFF
        val v4 = DumlV4Codec.encode(
            DumlV4Codec.V4Frame(
                target = target,
                id = dumlId,
                cmdType = cmdType,
                cmdSet = set,
                cmdId = cmd,
                payload = payload,
            ),
        )
        val packet = DatalinkCodec.buildCommandPacket(sessionId, udpSeq, cmdCounter, v4)
        if (sendPacket(packet)) advance()
    }

    /** Echo the peer's window cursors back so it keeps streaming. */
    fun sendAck() {
        val packet = DatalinkCodec.buildWindowAck(
            sessionId = sessionId,
            videoCursor = peerVideoCursor,
            downloadCursor = peerDownloadCursor,
            controlCursor = baseSeq,
        )
        sendPacket(packet)
    }

    // ---- receive ----

    /**
     * Drains the socket for [durationMs], latching the peer's channel and
     * window cursors from every datagram. Returns the raw datagrams.
     */
    fun recvAll(durationMs: Long): List<ByteArray> {
        val sock = socket ?: return emptyList()
        val out = ArrayList<ByteArray>()
        val deadline = System.nanoTime() + durationMs * 1_000_000
        val buf = ByteArray(65536)
        while (System.nanoTime() < deadline) {
            try {
                val p = DatagramPacket(buf, buf.size)
                sock.receive(p)
                val data = p.data.copyOf(p.length)
                out.add(data)
                latchPeerState(data)
            } catch (_: java.net.SocketTimeoutException) {
                // no more packets this round
            } catch (_: Exception) {
                break
            }
        }
        return out
    }

    // ---- internals ----

    /**
     * TCP poke to port 7001: a `SetPairingPin("osmo")` v4 frame
     * (`0x07/0x45`, id `0x8092`), then ~400 ms for the camera's datalink
     * listener to come up. Payload is `PackString(identifier) +
     * PackString(pin)` — the identifier must be the install-persisted
     * pairing identity, not a shared constant. Best-effort.
     *
     * Reference: osmosis `OsmoCommands.setPairingPin` /
     * `DjiPairMessagePayload`.
     */
    fun tcpPoke(cameraIp: String, pairingIdentifier: String) {
        Socket().use { s ->
            s.connect(InetSocketAddress(cameraIp, DatalinkCodec.TCP_POKE_PORT), 1200)
            s.getOutputStream().write(TcpPoke.setPairingPinFrame(pairingIdentifier))
            s.getOutputStream().flush()
            Thread.sleep(400)
        }
    }

    private fun handshake(): Boolean {
        val body = DatalinkCodec.handshakeBody(baseSeq)
        repeat(20) {
            sendRawPacket(DatalinkCodec.PKT_TYPE_HANDSHAKE, body)
            for (r in recvAll(350)) {
                val h = DatalinkCodec.parseUdpHeader(r) ?: continue
                if (h.sessionId == sessionId &&
                    h.pktType == DatalinkCodec.PKT_TYPE_HANDSHAKE
                ) {
                    return true
                }
            }
        }
        return false
    }

    private fun latchPeerState(data: ByteArray) {
        if (data.size >= 10) {
            val ch = (data[8].toInt() and 0xFF) or ((data[9].toInt() and 0xFF) shl 8)
            if (ch != 0) camChannel = ch
        }
        DatalinkCodec.parseStatusCursors(data)?.let { (video, download) ->
            peerVideoCursor = video
            peerDownloadCursor = download
        }
    }

    private fun sendRawPacket(pktType: Int, payload: ByteArray) {
        val header = DatalinkCodec.udpHeader(pktType, sessionId, udpSeq, 8 + payload.size)
        if (sendPacket(header + payload)) advance()
    }

    private fun sendPacket(packet: ByteArray): Boolean {
        val sock = socket ?: return false
        val peerAddr = peer ?: return false
        return runCatching {
            sock.send(DatagramPacket(packet, packet.size, peerAddr, DatalinkCodec.UDP_PORT))
        }.isSuccess
    }

    private fun advance() {
        udpSeq = (udpSeq + 8) and 0xFFFF
    }

    companion object {
        /**
         * DUML target word: sender `0x02` (app) + receiver type/id.
         * osmosis: `0x02 or (((receiverId shl 5) or receiverType) shl 8)`.
         */
        fun targetFor(receiverType: Int, receiverId: Int): Int =
            0x02 or (((receiverId shl 5) or receiverType) shl 8)

        /** `"\\x00APP" + 37*00 + 02 + 8*00 + 02 08 + 10*00` (62 bytes), mirrors osmosis. */
        fun appDeviceInfo(): ByteArray {
            val b = ByteArray(62)
            b[1] = 'A'.code.toByte()
            b[2] = 'P'.code.toByte()
            b[3] = 'P'.code.toByte()
            b[41] = 0x02
            b[50] = 0x02
            b[51] = 0x08
            return b
        }

        private val APP_PRESENCE: ByteArray = hexToBytes("170046237c415050000000000002")

        private fun hexToBytes(hex: String): ByteArray =
            ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }

        /**
         * First CRC-valid v1 reply frame in [datagram] with the given
         * cmdset/cmd and a non-empty payload — the empty transport ACK is
         * skipped. Mirrors osmosis `DumlTransport.findReply`.
         */
        fun findReplyFrame(datagram: ByteArray, set: Int, cmd: Int): DumlDecodedFrame? {
            for (frame in DatalinkCodec.scanV1Frames(datagram)) {
                if (frame.cmdSet == set && frame.cmdId == cmd && frame.payload.isNotEmpty()) {
                    return frame
                }
            }
            return null
        }
    }
}

/**
 * The TCP poke frame osmosis writes to port 7001 before the UDP handshake:
 * `SetPairingPin("osmo")` — v4 framing, `0x07/0x45`, message id `0x8092`,
 * payload `PackString(identifier) + PackString("osmo")`.
 */
internal object TcpPoke {
    fun setPairingPinFrame(pairingIdentifier: String): ByteArray {
        val payload = DumlStringCodec.pack(pairingIdentifier) +
            DumlStringCodec.pack("osmo")
        return DumlV4Codec.encode(
            DumlV4Codec.V4Frame(
                target = DumlTargets.APP_TO_WIFI,
                id = 0x8092,
                cmdType = 2,
                cmdSet = 0x07,
                cmdId = 0x45,
                payload = payload,
            ),
        )
    }
}
