package com.alliot.osmo.demo.media.model

/**
 * Which physical store a media record lives on. The camera's HTTP server
 * addresses them as `/v2?storage=<index>&path=...`; index 0 is the SD
 * card, 1 the internal storage (osmosis `StorageRules`).
 */
enum class MediaStore(val index: Int) {
    SD_CARD(0),
    INTERNAL(1),
}

/**
 * One media record decoded from the camera's CompositePack manifest.
 *
 * [handle] is the delete handle from `0x00/0x28`'s namespace (`0` when
 * the record exposes none — photos on some bodies). [handleShared]
 * marks a handle two records claim, which must never be deleted.
 */
data class MediaItem(
    val path: String,
    val thumbPath: String,
    val store: MediaStore,
    val handle: Long = 0L,
    val handleShared: Boolean = false,
    val sizeBytes: Long = 0L,
    val durationSec: Int = 0,
    val mediaType: Int = -1,
    val isVideo: Boolean = false,
    val starred: Boolean = false,
    val proxyPath: String? = null,
) {
    val name: String get() = path.substringAfterLast('/')
    val extension: String get() = name.substringAfterLast('.', "")

    /**
     * Safe to offer "delete from camera": a known-good handle that no
     * other record shares. The download side additionally requires the
     * file to be downloaded and SHA-256-verified first
     * ([HistoryStore.canDeleteFromCamera][com.alliot.osmo.demo.media.download.HistoryStore.canDeleteFromCamera]).
     */
    val deletable: Boolean get() = handle != 0L && !handleShared
}
