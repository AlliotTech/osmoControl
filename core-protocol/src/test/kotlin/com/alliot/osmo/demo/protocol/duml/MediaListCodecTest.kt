package com.alliot.osmo.demo.protocol.duml

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaListCodecTest {
    @Test
    fun list_query_matches_documented_baseline() {
        val payload = MediaListCodec.buildListQueryPayload(
            counter = 0x01,
            cursor = MediaListCodec.SD_CARD_CURSOR_NEWEST,
        )
        assertArrayEquals(
            hex("4a002a10010000000000010000002d000d0100ffffffffffffffff000100000000000000000000000000"),
            payload,
        )
    }

    @Test
    fun list_query_patches_counter_and_cursor() {
        val payload = MediaListCodec.buildListQueryPayload(
            counter = 0x07,
            cursor = MediaListCodec.INTERNAL_STORAGE_CURSOR_NEWEST,
        )
        assertEquals(0x07, payload[4].toInt() and 0xFF)
        assertArrayEquals(
            byteArrayOf(0x01, 0x00, 0x00, 0x40.toByte()),
            payload.copyOfRange(10, 14),
        )
    }

    @Test
    fun list_query_frame_targets_camera_endpoint() {
        val frame = MediaListCodec.buildListQueryFrame(counter = 1, cursor = 1, messageId = 9)
        assertEquals(DumlTargets.APP_TO_CAMERA, frame.target)
        assertEquals(DumlCmdSet.GENERAL, frame.cmdSet)
        assertEquals(DumlGeneralCmd.MEDIA_LIST_QUERY, frame.cmdId)
        assertEquals(9, frame.messageId)
    }

    @Test
    fun chunk_assembler_concatenates_data_chunks_in_arrival_order() {
        val assembler = MediaListCodec.ChunkAssembler()
        val counter = 0x05

        val start = assembler.accept(responseFrame(subtype = 0x04, counter = counter, body = ByteArray(0)))
        assertTrue(start is MediaListCodec.ChunkEvent.PageStarted)

        val first = assembler.accept(responseFrame(subtype = 0x01, counter = counter, body = byteArrayOf(0x11, 0x22)))
        assertTrue(first is MediaListCodec.ChunkEvent.DataAppended)
        assertEquals(2, (first as MediaListCodec.ChunkEvent.DataAppended).totalBytes)

        val second = assembler.accept(responseFrame(subtype = 0x01, counter = counter, body = byteArrayOf(0x33)))
        assertEquals(3, (second as MediaListCodec.ChunkEvent.DataAppended).totalBytes)

        val end = assembler.accept(responseFrame(subtype = 0x03, counter = counter, body = ByteArray(0)))
        assertTrue(end is MediaListCodec.ChunkEvent.PageComplete)
        assertArrayEquals(
            byteArrayOf(0x11, 0x22, 0x33),
            (end as MediaListCodec.ChunkEvent.PageComplete).manifest,
        )
    }

    @Test
    fun chunk_assembler_ignores_wrong_command_and_stale_counters() {
        val assembler = MediaListCodec.ChunkAssembler()

        val wrongCommand = DumlFrameCodec.decode(
            DumlFrameCodec.encode(
                DumlFrame(
                    target = 0x0201,
                    messageId = 1,
                    flags = DumlFlags.RESPONSE,
                    cmdSet = DumlCmdSet.GENERAL,
                    cmdId = DumlGeneralCmd.MEDIA_LIST_QUERY,
                    payload = byteArrayOf(0x4A, 0x01, 0x00, 0x00, 0x01, 0x00, 0x06, 0x00, 0x00, 0x00),
                ),
            ),
        )
        assertEquals(null, assembler.accept(wrongCommand))

        assembler.accept(responseFrame(subtype = 0x04, counter = 0x02, body = ByteArray(0)))
        val stale = assembler.accept(responseFrame(subtype = 0x01, counter = 0x09, body = byteArrayOf(0x44)))
        assertEquals(null, stale)
    }

    @Test
    fun delete_payload_layout_matches_protocol() {
        val payload = MediaListCodec.buildDeletePayload(
            handles = listOf(0x1234_5678L),
            counter = 0x09,
        )
        assertArrayEquals(
            byteArrayOf(
                0x01,
                0x78, 0x56, 0x34, 0x12,
                0x09, 0x00, 0x00, 0x00,
                0x00,
                0x01, 0x00, 0x00, 0x00,
                0x01, 0x01, 0x00, 0x00,
            ),
            payload,
        )
    }

    @Test
    fun delete_frame_targets_media_delete_command() {
        val frame = MediaListCodec.buildDeleteFrame(
            handles = listOf(0x1L, 0x2L),
            counter = 3,
            messageId = 4,
        )
        assertEquals(DumlCmdSet.GENERAL, frame.cmdSet)
        assertEquals(DumlGeneralCmd.MEDIA_DELETE, frame.cmdId)
        assertEquals(DumlTargets.APP_TO_CAMERA, frame.target)
    }

    @Test
    fun too_early_response_detection() {
        val tooEarly = DumlFrameCodec.decode(
            DumlFrameCodec.encode(
                DumlFrame(
                    target = 0x0201,
                    messageId = 1,
                    flags = DumlFlags.RESPONSE,
                    cmdSet = DumlCmdSet.GENERAL,
                    cmdId = DumlGeneralCmd.MEDIA_LIST_QUERY,
                    payload = byteArrayOf(0xD8.toByte()),
                ),
            ),
        )
        assertTrue(MediaListCodec.isTooEarlyResponse(tooEarly))

        val emptyStore = DumlFrameCodec.decode(
            DumlFrameCodec.encode(
                DumlFrame(
                    target = 0x0201,
                    messageId = 1,
                    flags = DumlFlags.RESPONSE,
                    cmdSet = DumlCmdSet.GENERAL,
                    cmdId = DumlGeneralCmd.MEDIA_LIST_QUERY,
                    payload = byteArrayOf(0x00),
                ),
            ),
        )
        assertFalse(MediaListCodec.isTooEarlyResponse(emptyStore))
    }

    @Test
    fun wifi_credential_decode() {
        val payload = DumlPayloadCodec.decode(
            DumlCmdSet.WIFI,
            DumlWifiCmd.GET_SSID,
            DumlFlags.RESPONSE,
            byteArrayOf(0x00) + DumlStringCodec.pack("Osmo_5Pro"),
        )
        assertTrue(payload is WifiCredentialPayload)
        assertEquals(0, (payload as WifiCredentialPayload).status)
        assertEquals("Osmo_5Pro", payload.value)
    }

    @Test
    fun wifi_connect_encode() {
        val bytes = DumlPayloadCodec.encode(
            DumlCmdSet.WIFI,
            DumlWifiCmd.WIFI_CONNECT,
            WifiConnectPayload(ssid = "ssid", password = "pw"),
        )
        assertArrayEquals(
            DumlStringCodec.pack("ssid") + DumlStringCodec.pack("pw"),
            bytes,
        )
    }

    @Test
    fun active_store_status_decode_0x02_0x80() {
        // flags u32LE @0 (bit 30 = in playback), total MiB u32LE @5, free MiB @9.
        val payload = u32Le(0x40000000L) + byteArrayOf(0x00) + u32Le(121785L) + u32Le(109748L)
        val decoded = DumlPayloadCodec.decode(
            DumlCmdSet.FILE_SYSTEM,
            DumlFileSystemCmd.ACTIVE_STORE_STATUS,
            DumlFlags.NOTIFY,
            payload,
        )
        assertTrue(decoded is ActiveStoreStatusPayload)
        decoded as ActiveStoreStatusPayload
        assertEquals(0x40000000L, decoded.flags)
        assertTrue(decoded.inPlayback)
        assertEquals(121785L, decoded.totalMb)
        assertEquals(109748L, decoded.freeMb)
    }

    @Test
    fun stores_status_decode_0x02_0xdc() {
        // 40-byte two-store body: store count @2, first block @6/@10, built-in @24/@28.
        val payload = ByteArray(40)
        payload[2] = 0x02
        u32Le(121785L).copyInto(payload, 6)
        u32Le(109748L).copyInto(payload, 10)
        u32Le(48980L).copyInto(payload, 24)
        u32Le(40000L).copyInto(payload, 28)
        val decoded = DumlPayloadCodec.decode(
            DumlCmdSet.FILE_SYSTEM,
            DumlFileSystemCmd.STORES_STATUS,
            DumlFlags.NOTIFY,
            payload,
        )
        assertTrue(decoded is StoresStatusPayload)
        decoded as StoresStatusPayload
        assertEquals(2, decoded.storeCount)
        assertEquals(121785L, decoded.sdTotalMb)
        assertEquals(109748L, decoded.sdFreeMb)
        assertEquals(48980L, decoded.internalTotalMb)
        assertEquals(40000L, decoded.internalFreeMb)
    }

    @Test
    fun stores_status_single_store_body_has_no_internal() {
        val payload = ByteArray(22)
        payload[2] = 0x01
        u32Le(30500L).copyInto(payload, 6)
        u32Le(29000L).copyInto(payload, 10)
        val decoded = DumlPayloadCodec.decode(
            DumlCmdSet.FILE_SYSTEM,
            DumlFileSystemCmd.STORES_STATUS,
            DumlFlags.NOTIFY,
            payload,
        ) as StoresStatusPayload
        assertEquals(30500L, decoded.sdTotalMb)
        assertEquals(null, decoded.internalTotalMb)
    }

    @Test
    fun battery_push_0x0d_0x02_stays_raw() {
        // 0x0d/0x02 is the battery/dock push (NOT storage) — never decode it.
        val decoded = DumlPayloadCodec.decode(
            DumlCmdSet.POWER,
            DumlBatteryCmd.BATTERY_PUSH,
            DumlFlags.NOTIFY,
            byteArrayOf(0x01, 0x02, 0x03),
        )
        assertTrue(decoded is RawDumlPayload)
    }

    private fun responseFrame(subtype: Int, counter: Int, body: ByteArray): DumlDecodedFrame {
        val subHeader = byteArrayOf(
            0x4A.toByte(), subtype.toByte(), 0x00, 0x00,
            counter.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        return DumlFrameCodec.decode(
            DumlFrameCodec.encode(
                DumlFrame(
                    target = 0x0201,
                    messageId = 1,
                    flags = DumlFlags.NOTIFY,
                    cmdSet = DumlCmdSet.GENERAL,
                    cmdId = DumlGeneralCmd.MEDIA_LIST_RESPONSE,
                    payload = subHeader + body,
                ),
            ),
        )
    }

    private fun u32Le(value: Long): ByteArray {
        return ByteArray(4) { index -> ((value ushr (index * 8)) and 0xFF).toByte() }
    }

    private fun u64Le(value: Long): ByteArray {
        return ByteArray(8) { index -> ((value ushr (index * 8)) and 0xFF).toByte() }
    }

    private fun hex(value: String): ByteArray {
        require(value.length % 2 == 0) { "Hex string must have even length." }
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
