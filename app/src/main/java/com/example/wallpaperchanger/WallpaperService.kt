package com.example.wallpaperchanger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.ImageDecoder
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
import kotlin.math.max
import kotlin.math.min

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
        isRunning = true
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, true).apply()

        job?.cancel()
        job = scope.launch {
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val queue = ImageQueue(prefs)
            while (isActive) {
                val docs = listImages(this@WallpaperService, folder)
                if (docs.isNotEmpty()) {
                    val byUri = docs.associateBy { it.uri.toString() }
                    // Non-repeating: every image once per round, never the same one twice in a row.
                    val file = queue.next(docs.map { it.uri.toString() })?.let { byUri[it] }
                    if (file != null && setWallpaper(file)) {
                        val name = file.name ?: "unknown"
                        prefs.edit().putString(KEY_CURRENT, name).apply()
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

    /** Returns true if the wallpaper was set. */
    private fun setWallpaper(file: DocumentFile): Boolean {
        return try {
            val wm = WallpaperManager.getInstance(this)
            val metrics = resources.displayMetrics
            val ratio = targetRatio(this)

            val source = ImageDecoder.createSource(contentResolver, file.uri)
            val decoded: Bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                // Downscale very large images so we don't run out of memory.
                val maxW = metrics.widthPixels * 2
                val maxH = metrics.heightPixels * 2
                val scale = min(
                    1f,
                    min(maxW.toFloat() / info.size.width, maxH.toFloat() / info.size.height)
                )
                if (scale < 1f) {
                    decoder.setTargetSize(
                        max(1, (info.size.width * scale).toInt()),
                        max(1, (info.size.height * scale).toInt())
                    )
                }
            }

            // Saved crop for this image, or the center crop if none was saved.
            val x = CropStore.get(this, file.name ?: "") ?: 0.5f
            val cropped = applyCrop(decoded, ratio, x)
            wm.setBitmap(cropped)
            if (cropped !== decoded) cropped.recycle()
            decoded.recycle()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set wallpaper from ${file.name}", e)
            false
        }
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
        // KEY_RUNNING is deliberately left as-is: if the system killed us, the watchdog
        // sees "should be running but isn't" and restarts us. Stop sets it to false.
        isRunning = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FOLDER = "folder"
        const val EXTRA_INTERVAL_SECONDS = "interval_seconds"
        const val PREFS = "prefs"
        const val KEY_CURRENT = "current"
        const val KEY_RUNNING = "running"

        /** True while this process's service is alive. Resets to false if the process dies. */
        @Volatile
        var isRunning = false
        private const val CHANNEL_ID = "wallpaper_changer"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "WallpaperService"
    }
}
