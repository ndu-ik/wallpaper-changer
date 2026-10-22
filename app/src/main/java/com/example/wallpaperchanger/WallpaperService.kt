package com.example.wallpaperchanger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.WallpaperManager
import android.content.Context
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
import kotlin.math.roundToInt

class WallpaperService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Wallpaper changer", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val folder = intent?.getStringExtra(EXTRA_FOLDER)
        val intervalSeconds = intent?.getLongExtra(EXTRA_INTERVAL_SECONDS, 60L) ?: 60L
        if (folder == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(
            NOTIFICATION_ID,
            buildNotification("Starting..."),
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
                    val file = images[index]
                    if (setWallpaper(file)) {
                        val name = file.name ?: "unknown"
                        getSharedPreferences("prefs", Context.MODE_PRIVATE)
                            .edit().putString(KEY_CURRENT, name).apply()
                        updateNotification("Now showing: $name")
                    }
                } else {
                    Log.w(TAG, "No images found in folder")
                    updateNotification("No images found in folder")
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

    /** Returns true if the wallpaper was set. */
    private fun setWallpaper(file: DocumentFile): Boolean {
        return try {
            val wm = WallpaperManager.getInstance(this)
            val metrics = resources.displayMetrics

            // Aspect ratio (width / height) of the wallpaper the launcher expects.
            val dw = wm.desiredMinimumWidth
            val dh = wm.desiredMinimumHeight
            val targetRatio = if (dw > 0 && dh > 0) {
                dw.toFloat() / dh
            } else {
                metrics.widthPixels.toFloat() / metrics.heightPixels
            }

            val source = ImageDecoder.createSource(contentResolver, file.uri)
            val decoded: Bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
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

            val cropped = cropCenterToRatio(decoded, targetRatio)
            wm.setBitmap(cropped)
            if (cropped !== decoded) cropped.recycle()
            decoded.recycle()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set wallpaper from ${file.name}", e)
            false
        }
    }

    /**
     * Cuts a window out of the middle of [src] with the given width/height ratio.
     * The window always uses the full image height (so it "zooms out" to fit),
     * and is centered horizontally. Images already narrower than the ratio are returned unchanged.
     */
    private fun cropCenterToRatio(src: Bitmap, targetRatio: Float): Bitmap {
        val w = src.width
        val h = src.height
        if (w.toFloat() / h <= targetRatio) return src

        val cropW = (h * targetRatio).roundToInt().coerceIn(1, w)
        val x = (w - cropW) / 2
        return Bitmap.createBitmap(src, x, 0, cropW, h)
    }

    private fun buildNotification(text: String): Notification {
        // Tapping the notification opens the app.
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Wallpaper Changer")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FOLDER = "folder"
        const val EXTRA_INTERVAL_SECONDS = "interval_seconds"
        const val KEY_CURRENT = "current"
        private const val CHANNEL_ID = "wallpaper_changer"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "WallpaperService"
        private val EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif"
        )
    }
}
