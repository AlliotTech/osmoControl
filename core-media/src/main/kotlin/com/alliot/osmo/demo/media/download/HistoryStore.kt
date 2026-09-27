package com.alliot.osmo.demo.media.download

import java.io.File
import java.util.Properties

/**
 * Remembers which camera files have been downloaded and hash-verified,
 * so re-browsing never re-downloads them and delete-from-camera can
 * require proof the bytes are safely on disk first.
 */
interface HistoryStore {

    /** True when this exact file (path + size + SHA-256) was downloaded and verified before. */
    fun isDownloaded(cameraId: String, path: String, sizeBytes: Long, sha256Hex: String): Boolean

    fun recordDownload(cameraId: String, path: String, sizeBytes: Long, sha256Hex: String)

    fun markDeletedFromCamera(cameraId: String, path: String)

    fun wasDeletedFromCamera(cameraId: String, path: String): Boolean

    /**
     * Safe to delete from the camera: downloaded, SHA-256-verified, and
     * not already deleted. The caller additionally requires a
     * known-good, unshared delete handle on the [MediaItem][com.alliot.osmo.demo.media.model.MediaItem].
     */
    fun canDeleteFromCamera(cameraId: String, path: String, sizeBytes: Long): Boolean
}

/**
 * [Properties]-file-backed [HistoryStore]. One file per [dir]
 * (`history.properties`); each entry is
 * `cameraId|path -> sizeBytes|sha256Hex|deletedFlag`.
 */
class FileHistoryStore(dir: File) : HistoryStore {

    private val file = File(dir, "history.properties")
    private val props = Properties()
    private val lock = Any()

    init {
        dir.mkdirs()
        if (file.isFile) {
            file.inputStream().use { props.load(it) }
        }
    }

    override fun isDownloaded(
        cameraId: String,
        path: String,
        sizeBytes: Long,
        sha256Hex: String,
    ): Boolean = synchronized(lock) {
        val rec = read(key(cameraId, path)) ?: return false
        return rec.sizeBytes == sizeBytes &&
            rec.sha256Hex.equals(sha256Hex, ignoreCase = true) &&
            !rec.deleted
    }

    override fun recordDownload(
        cameraId: String,
        path: String,
        sizeBytes: Long,
        sha256Hex: String,
    ): Unit = synchronized(lock) {
        write(key(cameraId, path), Record(sizeBytes, sha256Hex, deleted = false))
    }

    override fun markDeletedFromCamera(cameraId: String, path: String): Unit = synchronized(lock) {
        val k = key(cameraId, path)
        val rec = read(k) ?: Record(0L, "", deleted = true)
        write(k, rec.copy(deleted = true))
    }

    override fun wasDeletedFromCamera(cameraId: String, path: String): Boolean = synchronized(lock) {
        read(key(cameraId, path))?.deleted == true
    }

    override fun canDeleteFromCamera(cameraId: String, path: String, sizeBytes: Long): Boolean =
        synchronized(lock) {
            val rec = read(key(cameraId, path)) ?: return false
            return rec.sizeBytes == sizeBytes && rec.sha256Hex.isNotEmpty() && !rec.deleted
        }

    private data class Record(val sizeBytes: Long, val sha256Hex: String, val deleted: Boolean)

    private fun key(cameraId: String, path: String) = "$cameraId|$path"

    private fun read(key: String): Record? {
        val v = props.getProperty(key) ?: return null
        val parts = v.split('|')
        if (parts.size != 3) return null
        return Record(
            sizeBytes = parts[0].toLongOrNull() ?: return null,
            sha256Hex = parts[1],
            deleted = parts[2] == "1",
        )
    }

    private fun write(key: String, record: Record) {
        props.setProperty(
            key,
            "${record.sizeBytes}|${record.sha256Hex}|${if (record.deleted) "1" else "0"}",
        )
        save()
    }

    private fun save() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { props.store(it, null) }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
