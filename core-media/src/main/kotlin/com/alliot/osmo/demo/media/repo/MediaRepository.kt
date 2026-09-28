package com.alliot.osmo.demo.media.repo

import com.alliot.osmo.demo.media.datalink.DatalinkCodec
import com.alliot.osmo.demo.media.datalink.DatalinkTransport
import com.alliot.osmo.demo.media.model.MediaItem
import com.alliot.osmo.demo.media.model.MediaStore
import com.alliot.osmo.demo.protocol.duml.DumlCmdSet
import com.alliot.osmo.demo.protocol.duml.DumlFileSystemCmd
import com.alliot.osmo.demo.protocol.duml.DumlGeneralCmd
import com.alliot.osmo.demo.protocol.duml.DumlPayloadCodec
import com.alliot.osmo.demo.protocol.duml.MediaListCodec
import com.alliot.osmo.demo.protocol.duml.StoresStatusPayload

/**
 * Media listing and deletion over an open [DatalinkTransport].
 *
 * - Each store is paged independently with its own query counter and
 *   cursor (SD `0x00000001`, internal `0x40000001` for the newest page;
 *   osmosis uses counters 1 and 2).
 * - While a page streams, `0x04` window ACKs keep flowing — the camera
 *   stalls any window it does not see acknowledged, which is how a
 *   two-store page used to lose its tail.
 * - A `d8` answer means "too early" (right after entering playback):
 *   the caller should wait and re-query instead of treating the store as
 *   empty.
 * - Delete is `0x00/0x28` by handle; only items whose [MediaItem] is
 *   [MediaItem.deletable] are accepted, and the camera's status word
 *   (`0x0000` = OK) decides success.
 *
 * Blocking; call off the UI thread.
 */
class MediaRepository(
    private val transport: DatalinkTransport,
    private val log: (String) -> Unit = {},
) {

    data class MediaPage(val items: List<MediaItem>, val tooEarly: Boolean)

    private var sdCounter = 1
    private var internalCounter = 2
    private var deleteCounter = 1

    // Lazy pagination: one cursor per store (the next older page's start), seeded by [listNewest].
    // A store is "done" once a page yields no older handle or comes back short. osmosis parity:
    // CameraSession.stepPagination / oldestHandle — the next cursor is the oldest (smallest, non-zero)
    // delete handle on the page, strictly below the current cursor.
    private var sdCursor = MediaListCodec.SD_CARD_CURSOR_NEWEST
    private var internalCursor = MediaListCodec.INTERNAL_STORAGE_CURSOR_NEWEST
    private var sdDone = false
    private var internalDone = false

    /**
     * Enter playback so the camera serves a complete list (some bodies otherwise return only the
     * oldest few records). Best-effort; the transport keepalive re-asserts it. Call once per session
     * before the first list.
     */
    fun enterPlayback() = transport.enterPlayback()

    /** True while [store] may still have an older page to fetch via [nextPage]. */
    fun hasMore(store: MediaStore): Boolean = when (store) {
        MediaStore.SD_CARD -> !sdDone
        MediaStore.INTERNAL -> !internalDone
    }

    /**
     * The next older page of [store], or null when the store is exhausted. Advances that store's
     * cursor to the oldest handle strictly below the current one; a short or handle-less page ends it.
     */
    fun nextPage(store: MediaStore): MediaPage? {
        if (!hasMore(store)) return null
        val counter = when (store) {
            MediaStore.SD_CARD -> sdCounter++
            MediaStore.INTERNAL -> internalCounter++
        }
        val cursor = when (store) {
            MediaStore.SD_CARD -> sdCursor
            MediaStore.INTERNAL -> internalCursor
        }
        val page = listPage(store, counter, cursor)
        advance(store, cursor, page)
        return page
    }

    /** Newest-first page of one store; also seeds this store's pagination cursor. */
    fun listNewest(store: MediaStore): MediaPage {
        val counter = when (store) {
            MediaStore.SD_CARD -> sdCounter++
            MediaStore.INTERNAL -> internalCounter++
        }
        val newest = when (store) {
            MediaStore.SD_CARD -> MediaListCodec.SD_CARD_CURSOR_NEWEST
            MediaStore.INTERNAL -> MediaListCodec.INTERNAL_STORAGE_CURSOR_NEWEST
        }
        when (store) {
            MediaStore.SD_CARD -> { sdCursor = newest; sdDone = false }
            MediaStore.INTERNAL -> { internalCursor = newest; internalDone = false }
        }
        val page = listPage(store, counter, newest)
        advance(store, newest, page)
        return page
    }

    /**
     * Advance [store]'s cursor after a page: next = oldest non-zero handle strictly below [from].
     * No such handle, or a short page (< [MediaListCodec.PAGE_SIZE]), means the store is exhausted.
     */
    private fun advance(store: MediaStore, from: Long, page: MediaPage) {
        val next = page.items.map { it.handle }.filter { it != 0L && it < from }.minOrNull()
        val done = next == null || page.items.size < MediaListCodec.PAGE_SIZE
        when (store) {
            MediaStore.SD_CARD -> { if (next != null) sdCursor = next; sdDone = done }
            MediaStore.INTERNAL -> { if (next != null) internalCursor = next; internalDone = done }
        }
    }

    /**
     * One page of [store] starting at [cursor]. Newest-page cursors are
     * [MediaListCodec.SD_CARD_CURSOR_NEWEST] /
     * [MediaListCodec.INTERNAL_STORAGE_CURSOR_NEWEST]; older pages need
     * the previous page's tail cursor (real-device TBD).
     */
    fun listPage(store: MediaStore, counter: Int, cursor: Long): MediaPage = transport.exclusive {
        val payload = MediaListCodec.buildListQueryPayload(counter, cursor)
        transport.sendDuml(DumlCmdSet.GENERAL, DumlGeneralCmd.MEDIA_LIST_QUERY, payload)

        val assembler = MediaListCodec.ChunkAssembler()
        var tooEarly = false
        var sawData = false
        var lastProgressAt = System.currentTimeMillis()
        val deadline = lastProgressAt + PAGE_TIMEOUT_MS

        while (System.currentTimeMillis() < deadline) {
            for (datagram in transport.recvAll(200)) {
                for (frame in DatalinkCodec.scanV1Frames(datagram)) {
                    if (MediaListCodec.isTooEarlyResponse(frame)) {
                        tooEarly = true
                        continue
                    }
                    when (val event = assembler.accept(frame)) {
                        is MediaListCodec.ChunkEvent.PageStarted,
                        is MediaListCodec.ChunkEvent.DataAppended,
                        -> {
                            sawData = true
                            lastProgressAt = System.currentTimeMillis()
                        }
                        is MediaListCodec.ChunkEvent.PageComplete -> {
                            val items = MediaManifestParser.decodeManifest(event.manifest, store)
                            log("media: ${store.name} page complete: ${items.size} records")
                            return@exclusive MediaPage(items, tooEarly = false)
                        }
                        null -> {}
                    }
                }
            }
            transport.sendAck()
            if (sawData && System.currentTimeMillis() - lastProgressAt > QUIET_CLOSE_MS) {
                val manifest = assembler.takePending()
                if (manifest != null) {
                    val items = MediaManifestParser.decodeManifest(manifest, store)
                    log("media: ${store.name} page quiet-closed: ${items.size} records")
                    return@exclusive MediaPage(items, tooEarly = false)
                }
                break
            }
        }
        if (tooEarly) {
            log("media: ${store.name} answered d8 (too early) — wait and re-query")
            return@exclusive MediaPage(emptyList(), tooEarly = true)
        }
        log("media: ${store.name} page timed out")
        MediaPage(emptyList(), tooEarly = false)
    }

    /**
     * Deletes [items] from the camera by handle. Irreversible — every
     * item must be [MediaItem.deletable]. Returns the camera's status
     * word (`0x0000` = OK), or null when no reply arrived (in which case
     * nothing may be assumed deleted).
     */
    fun delete(items: List<MediaItem>): Int? {
        require(items.isNotEmpty()) { "Nothing to delete." }
        require(items.all { it.deletable }) {
            "Refusing: some items have no safe delete handle."
        }
        val payload = MediaListCodec.buildDeletePayload(
            handles = items.map { it.handle },
            counter = deleteCounter++,
        )
        return transport.exclusive {
            val deadline = System.currentTimeMillis() + DELETE_TIMEOUT_MS
            transport.sendDuml(DumlCmdSet.GENERAL, DumlGeneralCmd.MEDIA_DELETE, payload)
            while (System.currentTimeMillis() < deadline) {
                for (datagram in transport.recvAll(200)) {
                    for (frame in DatalinkCodec.scanV1Frames(datagram)) {
                        if (frame.cmdSet == DumlCmdSet.GENERAL &&
                            frame.cmdId == DumlGeneralCmd.MEDIA_DELETE &&
                            frame.payload.isNotEmpty()
                        ) {
                            // The empty transport ACK is skipped — only the
                            // real status word counts.
                            val status = when {
                                frame.payload.size >= 2 ->
                                    (frame.payload[0].toInt() and 0xFF) or
                                        ((frame.payload[1].toInt() and 0xFF) shl 8)
                                else -> frame.payload[0].toInt() and 0xFF
                            }
                            log("media: delete status=0x%04x".format(status))
                            return@exclusive status
                        }
                    }
                }
                transport.sendAck()
            }
            log("media: delete — no reply")
            null
        }
    }

    /**
     * Latches the camera's per-store capacity from the unprompted
     * `0x02/0xDC` push (SD + built-in total/free, MiB). The camera emits
     * it spontaneously once the datalink is up, so we just drain for one
     * and ACK to keep the stream flowing. Null when none arrives within
     * [timeoutMs] — some bodies (e.g. the Nano) never send it.
     */
    fun readStoresStatus(timeoutMs: Long = STORES_STATUS_TIMEOUT_MS): StoresStatusPayload? =
        transport.exclusive {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                for (datagram in transport.recvAll(200)) {
                    val frame = DatalinkTransport.findReplyFrame(
                        datagram,
                        DumlCmdSet.FILE_SYSTEM,
                        DumlFileSystemCmd.STORES_STATUS,
                    ) ?: continue
                    val decoded = runCatching {
                        DumlPayloadCodec.decode(frame.cmdSet, frame.cmdId, frame.flags, frame.payload)
                    }.getOrNull()
                    if (decoded is StoresStatusPayload) {
                        log("media: stores status sd=${decoded.sdFreeMb}/${decoded.sdTotalMb}MB " +
                            "internal=${decoded.internalFreeMb}/${decoded.internalTotalMb}MB")
                        return@exclusive decoded
                    }
                }
                transport.sendAck()
            }
            log("media: no 0x02/0xdc stores status within ${timeoutMs}ms")
            null
        }

    companion object {
        private const val PAGE_TIMEOUT_MS = 20_000L
        private const val QUIET_CLOSE_MS = 3_000L
        private const val DELETE_TIMEOUT_MS = 10_000L
        private const val STORES_STATUS_TIMEOUT_MS = 1_500L
    }
}
