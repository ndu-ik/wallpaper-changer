package com.example.wallpaperchanger

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.view.WindowManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val IMAGE_EXTS = setOf(
    "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif"
)

/** Images in the chosen folder. */
fun listImages(ctx: Context, treeUri: String?): List<DocumentFile> {
    if (treeUri == null) return emptyList()
    val dir = DocumentFile.fromTreeUri(ctx, Uri.parse(treeUri)) ?: return emptyList()
    return dir.listFiles().filter { f ->
        f.isFile && (f.type?.startsWith("image/") == true ||
                f.name?.substringAfterLast('.', "")?.lowercase() in IMAGE_EXTS)
    }
}

/** Which shape the wallpaper crop window uses. */
enum class CropMode { PORTRAIT, LANDSCAPE, AUTO }

const val KEY_CROP_MODE = "crop_mode"

fun readCropMode(prefs: SharedPreferences): CropMode =
    CropMode.values().firstOrNull { it.name == prefs.getString(KEY_CROP_MODE, null) }
        ?: CropMode.PORTRAIT

/**
 * Width/height ratio of the crop window.
 *  - PORTRAIT: the screen's tall shape (short side / long side), e.g. 720/1640 = 0.44
 *  - LANDSCAPE: the same shape turned sideways (long / short), about 2.28
 *  - AUTO: landscape while the phone is turned sideways right now, otherwise portrait
 * Uses the screen's own size, not WallpaperManager's desired size, which can report a wrong
 * value shortly after the app opens.
 */
fun targetRatio(ctx: Context, mode: CropMode): Float {
    val w: Int
    val h: Int
    if (Build.VERSION.SDK_INT >= 30) {
        val bounds = ctx.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        w = bounds.width()
        h = bounds.height()
    } else {
        val m = ctx.resources.displayMetrics
        w = m.widthPixels
        h = m.heightPixels
    }
    val portrait = min(w, h).toFloat() / max(w, h).toFloat()
    val sideways = w > h
    return when (mode) {
        CropMode.PORTRAIT -> portrait
        CropMode.LANDSCAPE -> 1f / portrait
        CropMode.AUTO -> if (sideways) 1f / portrait else portrait
    }
}

/**
 * Crop window in source pixels. Full height; width = height * ratio.
 * [x] is the horizontal position: 0 = left edge, 0.5 = centered, 1 = right edge.
 * Returns null if the image is already narrow enough that no crop is needed.
 */
fun cropWindow(srcW: Int, srcH: Int, ratio: Float, x: Float): Rect? {
    if (srcW.toFloat() / srcH <= ratio) return null
    val cw = (srcH * ratio).roundToInt().coerceIn(1, srcW)
    val left = ((srcW - cw) * x.coerceIn(0f, 1f)).roundToInt()
    return Rect(left, 0, left + cw, srcH)
}

fun applyCrop(src: Bitmap, ratio: Float, x: Float): Bitmap {
    val r = cropWindow(src.width, src.height, ratio, x) ?: return src
    return Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height())
}

/** Decodes an image downscaled so its longest side is at most [maxPx]. */
fun decodeScaled(ctx: Context, uri: Uri, maxPx: Int): Bitmap? = try {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val s = min(1f, maxPx.toFloat() / max(info.size.width, info.size.height))
        if (s < 1f) {
            d.setTargetSize(
                (info.size.width * s).toInt().coerceAtLeast(1),
                (info.size.height * s).toInt().coerceAtLeast(1)
            )
        }
    }
} catch (e: Exception) {
    null
}

/**
 * Non-repeating random order. Every image is used once before any repeats.
 * The queue and last-shown image are saved in prefs so this survives restarts.
 * Used by both the wallpaper service and the app's preview, so they always agree.
 */
class ImageQueue(private val prefs: SharedPreferences) {

    private fun loadQueue(): MutableList<String> =
        prefs.getString(KEY_QUEUE, "").orEmpty().lines().filter { it.isNotEmpty() }.toMutableList()

    private fun ensure(all: List<String>): MutableList<String> {
        val valid = all.toSet()
        val q = loadQueue().filter { it in valid }.toMutableList()
        if (q.isEmpty() && all.isNotEmpty()) {
            val last = prefs.getString(KEY_LAST, null)
            var bag = all.shuffled()
            // Don't start a new round with the image that was just shown.
            if (bag.size > 1 && bag.first() == last) bag = bag.drop(1) + bag.first()
            q.addAll(bag)
        }
        prefs.edit().putString(KEY_QUEUE, q.joinToString("\n")).apply()
        return q
    }

    /** The image that will be shown next. Does not consume it. */
    fun peek(all: List<String>): String? = ensure(all).firstOrNull()

    /** Takes the next image and marks it as shown. The old current image becomes previous. */
    fun next(all: List<String>): String? {
        val q = ensure(all)
        val head = q.firstOrNull() ?: return null
        q.removeAt(0)
        prefs.edit()
            .putString(KEY_QUEUE, q.joinToString("\n"))
            .putString(KEY_PREV, prefs.getString(KEY_LAST, null))
            .putString(KEY_LAST, head)
            .apply()
        return head
    }

    /** The image currently on screen (URI string), or null if none yet. */
    fun current(): String? = prefs.getString(KEY_LAST, null)

    /** The image shown before the current one (URI string), or null. */
    fun previous(): String? = prefs.getString(KEY_PREV, null)

    companion object {
        private const val KEY_QUEUE = "queue"
        private const val KEY_LAST = "last_shown"
        private const val KEY_PREV = "prev_shown"
    }
}

/**
 * Per-image crop positions, saved as JSON at filesDir/config/crops.json:
 *   { "IMG_1234.jpg": 0.25, "other.png": 0.8 }
 * Keys are file names. Images without an entry use the center crop (0.5).
 */
object CropStore {
    fun file(ctx: Context): File = File(ctx.filesDir, "config/crops.json")

    fun get(ctx: Context, name: String): Float? = try {
        val f = file(ctx)
        if (!f.exists()) {
            null
        } else {
            val obj = JSONObject(f.readText())
            if (obj.has(name)) obj.getDouble(name).toFloat() else null
        }
    } catch (e: Exception) {
        null
    }

    /** Pass null to remove the saved crop for [name]. */
    fun set(ctx: Context, name: String, x: Float?) {
        val f = file(ctx)
        f.parentFile?.mkdirs()
        val obj = try {
            if (f.exists()) JSONObject(f.readText()) else JSONObject()
        } catch (e: Exception) {
            JSONObject()
        }
        if (x == null) obj.remove(name) else obj.put(name, x.toDouble())
        f.writeText(obj.toString(2))
    }
}
