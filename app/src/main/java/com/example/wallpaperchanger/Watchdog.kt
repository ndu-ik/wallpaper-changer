package com.example.wallpaperchanger

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Runs every N minutes, even if the app is closed or the phone was rebooted.
 * If the wallpaper should be running but the service is gone, it starts it again.
 */
class WatchdogWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(
            WallpaperService.PREFS, Context.MODE_PRIVATE
        )
        val shouldRun = prefs.getBoolean(WallpaperService.KEY_RUNNING, false)
        if (!shouldRun || WallpaperService.isRunning) return Result.success()

        val folder = prefs.getString(KEY_FOLDER, null) ?: return Result.success()
        val seconds = prefs.getLong(KEY_INTERVAL, 1800L)
        Log.i(TAG, "Wallpaper service not running, restarting it.")

        val intent = Intent(applicationContext, WallpaperService::class.java).apply {
            putExtra(WallpaperService.EXTRA_FOLDER, folder)
            putExtra(WallpaperService.EXTRA_INTERVAL_SECONDS, seconds)
        }
        try {
            ContextCompat.startForegroundService(applicationContext, intent)
        } catch (e: Exception) {
            // Android can refuse starting a foreground service from the background.
            // If that happens, the user needs to open the app and press Start once.
            Log.e(TAG, "Watchdog could not restart the service", e)
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "Watchdog"
        const val KEY_FOLDER = "folder"
        const val KEY_INTERVAL = "interval_sec"
    }
}

object Watchdog {
    private const val WORK_NAME = "wallpaper_watchdog"

    /** WorkManager does not allow periodic checks more often than this. */
    const val MIN_MINUTES = 15L

    /** Returns the interval actually used (raised to [MIN_MINUTES] if smaller). */
    fun enable(ctx: Context, minutes: Long): Long {
        val used = maxOf(minutes, MIN_MINUTES)
        val request = PeriodicWorkRequestBuilder<WatchdogWorker>(used, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        return used
    }

    fun disable(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
    }
}
