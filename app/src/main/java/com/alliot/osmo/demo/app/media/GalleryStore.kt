package com.alliot.osmo.demo.app.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File

/**
 * Publishes a downloaded camera file into the shared MediaStore so it shows up in the phone's
 * Gallery/Photos — videos under `Movies/OsmoControl`, stills under `Pictures/OsmoControl`. The
 * download itself streams to app-private storage first (for resume + SHA-256 verification); this
 * copies the verified result out and the caller deletes the temp file.
 *
 * Scoped-storage (API 29+) only; on API 28 the caller keeps the app-private copy.
 */
object GalleryStore {

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    @RequiresApi(Build.VERSION_CODES.Q)
    fun publish(context: Context, file: File, displayName: String, isVideo: Boolean): Uri? {
        val resolver = context.contentResolver
        val collection = if (isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/mp4" else "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (isVideo) "Movies/OsmoControl" else "Pictures/OsmoControl")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return null
        val ok = runCatching {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } != null
        }.getOrDefault(false)
        if (!ok) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }
}
