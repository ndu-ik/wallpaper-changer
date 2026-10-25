package com.example.wallpaperchanger

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
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

// ---------------------------------------------------------------------------
// Crop shape
// ---------------------------------------------------------------------------

/** Which shape the wallpaper crop uses. */
enum class CropMode { PORTRAIT, LANDSCAPE, AUTO }

const val KEY_CROP_MODE = "crop_mode"

fun readCropMode(prefs: SharedPreferences): CropMode =
    CropMode.values().firstOrNull { it.name == prefs.getString(KEY_CROP_MODE, null) }
        ?: CropMode.PORTRAIT

private fun screenSize(ctx: Context): Pair<Int, Int> =
    if (Build.VERSION.SDK_INT >= 30) {
        val b = ctx.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        Pair(b.width(), b.height())
    } else {
        val m = ctx.resources.displayMetrics
        Pair(m.widthPixels, m.heightPixels)
    }

/** Resolves AUTO to PORTRAIT or LANDSCAPE based on how the phone is held right now. */
fun effectiveCropMode(ctx: Context, mode: CropMode): CropMode = when (mode) {
    CropMode.AUTO -> {
        val (w, h) = screenSize(ctx)
        if (w > h) CropMode.LANDSCAPE else CropMode.PORTRAIT
    }
    else -> mode
}

/**
 * Width/height ratio of the crop window for a PORTRAIT or LANDSCAPE mode.
 * Portrait is the screen's short side / long side (720 / 1640 on your phone).
 * Landscape is the same shape turned sideways.
 */
fun targetRatio(ctx: Context, mode: CropMode): Float {
    val (w, h) = screenSize(ctx)
    val portrait = min(w, h).toFloat() / max(w, h).toFloat()
    return if (mode == CropMode.LANDSCAPE) 1f / portrait else portrait
}

private fun nearestEven(v: Float): Int = (v / 2f).roundToInt() * 2

/**
 * The crop window in pixels for a w x h photo. The side that limits the shape is kept whole:
 *  - photo wider than the window: full height kept, width = height * ratio (portrait cut)
 *  - photo narrower than the window: full width kept, height = width / ratio (landscape cut)
 * [pos] (0..1) moves the window along the free side. Sizes are rounded to the nearest even number.
 * Returns null if the whole photo already fits the shape.
 */
fun cropRect(w: Int, h: Int, ratio: Float, pos: Float): Rect? {
    val p = pos.coerceIn(0f, 1f)
    val imgRatio = w.toFloat() / h
    return when {
        imgRatio > ratio -> {
            val cw = nearestEven(h * ratio).coerceIn(1, w)
            val left = ((w - cw) * p).roundToInt()
            Rect(left, 0, left + cw, h)
        }
        imgRatio < ratio -> {
            val ch = nearestEven(w / ratio).coerceIn(1, h)
            val top = ((h - ch) * p).roundToInt()
            Rect(0, top, w, top + ch)
        }
        else -> null
    }
}

/** Crops an already-decoded bitmap (used for the on-screen preview). */
fun applyCrop(src: Bitmap, ratio: Float, pos: Float): Bitmap {
    val r = cropRect(src.width, src.height, ratio, pos) ?: return src
    return Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height())
}

// ---------------------------------------------------------------------------
// Full-resolution cut from the original photo
// ---------------------------------------------------------------------------

/**
 * Size and rotation of a photo.
 * rotation = degrees the stored pixels must be turned to display the photo (0, 90, 180, 270),
 * or -1 if the size came from a decoder that already applied the rotation.
 */
data class PhotoInfo(
    val displayW: Int, val displayH: Int,
    val rawW: Int, val rawH: Int,
    val rotation: Int
)

fun readPhotoInfo(ctx: Context, uri: Uri): PhotoInfo? {
    val cr = ctx.contentResolver
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching { cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } }

    if (opts.outWidth > 0 && opts.outHeight > 0) {
        val orientation = runCatching {
            cr.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val rot = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        val rawW = opts.outWidth
        val rawH = opts.outHeight
        val swap = rot == 90 || rot == 270
        return PhotoInfo(
            displayW = if (swap) rawH else rawW,
            displayH = if (swap) rawW else rawH,
            rawW = rawW, rawH = rawH, rotation = rot
        )
    }

    // Formats BitmapFactory can't read: ask ImageDecoder for the size instead.
    var w = 0
    var h = 0
    runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, uri)) { d, info, _ ->
            w = info.size.width
            h = info.size.height
            d.setTargetSize(1, 1)
        }.recycle()
    }
    if (w <= 0 || h <= 0) return null
    return PhotoInfo(displayW = w, displayH = h, rawW = w, rawH = h, rotation = -1)
}

/** Converts a rectangle in the displayed photo into the stored (unrotated) pixel coordinates. */
private fun displayToRaw(r: Rect, info: PhotoInfo): Rect = when (info.rotation) {
    90 -> Rect(r.top, info.rawH - r.right, r.bottom, info.rawH - r.left)
    180 -> Rect(info.rawW - r.right, info.rawH - r.bottom, info.rawW - r.left, info.rawH - r.top)
    270 -> Rect(info.rawW - r.bottom, r.left, info.rawW - r.top, r.right)
    else -> Rect(r)
}

private fun decodeRegion(ctx: Context, uri: Uri, raw: Rect): Bitmap? = try {
    ctx.contentResolver.openInputStream(uri)?.use { stream ->
        val dec = BitmapRegionDecoder.newInstance(stream, false) ?: return@use null
        try {
            dec.decodeRegion(raw, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } finally {
            dec.recycle()
        }
    }
} catch (e: Exception) {
    null
}

private fun rotate(b: Bitmap, deg: Int): Bitmap {
    if (deg == 0) return b
    val m = Matrix().apply { postRotate(deg.toFloat()) }
    val out = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, false)
    b.recycle()
    return out
}

/**
 * Cuts [rect] (in displayed-photo coordinates) out of the photo at full resolution, without
 * shrinking anything. Only the cut is read from the file. Falls back to a full decode for
 * formats the region reader can't handle.
 */
fun cutPhoto(ctx: Context, uri: Uri, info: PhotoInfo, rect: Rect): Bitmap? {
    if (info.rotation >= 0) {
        val region = decodeRegion(ctx, uri, displayToRaw(rect, info))
        if (region != null) return rotate(region, info.rotation)
    }
    return try {
        val full = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, _, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val out = Bitmap.createBitmap(full, rect.left, rect.top, rect.width(), rect.height())
        if (out !== full) full.recycle()
        out
    } catch (e: Exception) {
        null
    }
}

/** Decodes an image downscaled so its longest side is at most [maxPx]. Used for previews only. */
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

// ---------------------------------------------------------------------------
// Non-repeating order
// ---------------------------------------------------------------------------

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

// ---------------------------------------------------------------------------
// Saved crop positions
// ---------------------------------------------------------------------------

/**
 * Saved crop positions, kept per photo and per shape at filesDir/config/crops.json:
 *   { "IMG_1234.jpg": { "portrait": 0.25, "landscape": 0.5 } }
 * Keys are file names. A missing entry means the center (0.5).
 * Older files with a plain number are read as the portrait position.
 */
object CropStore {
    fun file(ctx: Context): File = File(ctx.filesDir, "config/crops.json")

    private fun key(mode: CropMode): String =
        if (mode == CropMode.LANDSCAPE) "landscape" else "portrait"

    private fun load(ctx: Context): JSONObject = try {
        val f = file(ctx)
        if (f.exists()) JSONObject(f.readText()) else JSONObject()
    } catch (e: Exception) {
        JSONObject()
    }

    fun get(ctx: Context, name: String, mode: CropMode): Float? {
        return when (val v = load(ctx).opt(name)) {
            is Number -> if (mode == CropMode.LANDSCAPE) null else v.toFloat()
            is JSONObject -> if (v.has(key(mode))) v.getDouble(key(mode)).toFloat() else null
            else -> null
        }
    }

    /** Pass null for [x] to remove the saved position for this photo and shape. */
    fun set(ctx: Context, name: String, mode: CropMode, x: Float?) {
        val obj = load(ctx)
        val entry = when (val v = obj.opt(name)) {
            is JSONObject -> v
            is Number -> JSONObject().put("portrait", v.toDouble())
            else -> JSONObject()
        }
        if (x == null) entry.remove(key(mode)) else entry.put(key(mode), x.toDouble())
        if (entry.length() == 0) obj.remove(name) else obj.put(name, entry)

        val f = file(ctx)
        f.parentFile?.mkdirs()
        f.writeText(obj.toString(2))
    }
}
