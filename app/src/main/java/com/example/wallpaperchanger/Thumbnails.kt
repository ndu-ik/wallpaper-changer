package com.example.wallpaperchanger

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.security.MessageDigest

/**
 * Small JPEG copy of an image, kept in the app's cache folder.
 * The first call decodes the original and saves the thumbnail. Later calls, including after
 * the app restarts, load the small file instead. The key includes the file's last-modified
 * time, so an edited image gets a fresh thumbnail.
 */
fun thumbnail(ctx: Context, doc: DocumentFile, maxPx: Int = THUMB_MAX_PX): Bitmap? {
    val dir = File(ctx.cacheDir, "thumbs").apply { mkdirs() }
    val raw = "${doc.uri}|${doc.lastModified()}|$maxPx"
    val key = MessageDigest.getInstance("MD5").digest(raw.toByteArray())
        .joinToString("") { "%02x".format(it) }
    val file = File(dir, "$key.jpg")

    if (file.exists() && file.length() > 0) {
        BitmapFactory.decodeFile(file.absolutePath)?.let { return it }
    }

    val bmp = decodeScaled(ctx, doc.uri, maxPx) ?: return null
    try {
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    } catch (e: Exception) {
        file.delete()
    }
    return bmp
}

const val THUMB_MAX_PX = 640
