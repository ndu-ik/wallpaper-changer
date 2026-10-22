package com.example.wallpaperchanger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.WallpaperManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WallpaperService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val folder = intent?.getStringExtra(EXTRA_FOLDER)
        val intervalSeconds = intent?.getLongExtra(EXTRA_INTERVAL_SECONDS, 60L) ?: 60L
        if (folder == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )

        job?.cancel()
        job = scope.launch {
            var lastIndex = -1
            while (isActive) {
                val images = listImages(Uri.parse(folder))
                if (images.isNotEmpty()) {
                    var index = images.indices.random()
                    if (images.size > 1) {
                        while (index == lastIndex) index = images.indices.random()
                    }
                    lastIndex = index
                    setWallpaper(images[index])
                } else {
                    Log.w(TAG, "No images found in folder")
                }
                delay(intervalSeconds * 1000L)
            }
        }
        return START_NOT_STICKY
    }

    private fun listImages(treeUri: Uri): List<DocumentFile> {
        val dir = DocumentFile.fromTreeUri(this, treeUri) ?: return emptyList()
        return dir.listFiles().filter { file ->
            file.isFile && (file.type?.startsWith("image/") == true ||
                    file.name?.substringAfterLast('.', "")?.lowercase() in EXTENSIONS)
        }
    }

    private fun setWallpaper(file: DocumentFile) {
        try {
            val metrics = resources.displayMetrics
            val source = ImageDecoder.createSource(contentResolver, file.uri)
            val bitmap: Bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                // Downscale very large images so we don't run out of memory.
                val maxW = metrics.widthPixels * 2
                val maxH = metrics.heightPixels * 2
                val scale = minOf(
                    1f,
                    maxW.toFloat() / info.size.width,
                    maxH.toFloat() / info.size.height
                )
                if (scale < 1f) {
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1)
                    )
                }
            }
            WallpaperManager.getInstance(this).setBitmap(bitmap)
            bitmap.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set wallpaper from ${file.name}", e)
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Wallpaper changer", NotificationManager.IMPORTANCE_LOW)
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Wallpaper Changer")
            .setContentText("Changing wallpaper on a timer")
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FOLDER = "folder"
        const val EXTRA_INTERVAL_SECONDS = "interval_seconds"
        private const val CHANNEL_ID = "wallpaper_changer"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "WallpaperService"
        private val EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif"
        )
    }
}
