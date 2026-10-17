package com.transcribe.app.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Shared audio arrives as a content:// URI whose read permission only lasts while our window
 * is open. We copy it into the app cache right away and work from the copy, which also lets
 * the upload stream from disk instead of holding the whole file in memory.
 */
object AudioCache {
    private const val DIR_NAME = "shared_audio"
    private const val MAX_AGE_MS = 60L * 60L * 1000L

    class CachedAudio(
        val file: File,
        /** The name the sender gave the audio (may contain any characters). */
        val displayName: String,
        /** Same name made safe for the upload; keeps the file extension. */
        val uploadName: String,
        val mimeType: String?
    )

    suspend fun copy(context: Context, uri: Uri): CachedAudio = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val resolver = appContext.contentResolver
        val name = displayName(appContext, uri)

        val dir = File(appContext.cacheDir, DIR_NAME)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create the cache folder")
        removeOldFiles(dir)

        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        val target = File(dir, "${System.nanoTime()}_$safeName")
        try {
            val input = resolver.openInputStream(uri)
                ?: throw IOException("Could not open the shared audio")
            input.use { source ->
                target.outputStream().use { sink -> source.copyTo(sink) }
            }
            // If the window was closed during the copy, throw here so the catch deletes the file.
            ensureActive()
            if (target.length() == 0L) throw IOException("The shared audio is empty")
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        CachedAudio(target, name, safeName, resolver.getType(uri))
    }

    fun displayName(context: Context, uri: Uri): String {
        var name: String? = null
        try {
            val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) name = cursor.getString(index)
                }
            }
        } catch (ignored: Exception) {
            // Some providers refuse the query; fall back to the URI below.
        }
        val resolved = name
        return if (!resolved.isNullOrBlank()) resolved else (uri.lastPathSegment ?: "audio")
    }

    /** Removes leftovers from runs that were killed before they could clean up. */
    private fun removeOldFiles(dir: File) {
        val files = dir.listFiles() ?: return
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        for (file in files) {
            if (file.lastModified() < cutoff) file.delete()
        }
    }
}
