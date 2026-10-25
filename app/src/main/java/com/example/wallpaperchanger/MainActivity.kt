package com.example.wallpaperchanger

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.util.LruCache
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private enum class Pick { PREVIOUS, CURRENT, NEXT }

    private lateinit var tvFolder: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvCurrent: TextView
    private lateinit var tvNextName: TextView
    private lateinit var tvCropInfo: TextView
    private lateinit var tvConfigPath: TextView
    private lateinit var etDuration: EditText
    private lateinit var etWatchMinutes: EditText
    private lateinit var cbWatchdog: CheckBox
    private lateinit var spUnit: Spinner
    private lateinit var rgPreview: RadioGroup
    private lateinit var pbPreview: ProgressBar
    private lateinit var cropView: CropPreviewView
    private lateinit var ivResult: ImageView

    private val units = listOf("s", "m", "h")
    private val unitSeconds = listOf(1L, 60L, 3600L)

    private val prefs by lazy { getSharedPreferences(WallpaperService.PREFS, Context.MODE_PRIVATE) }

    // Loads the visible preview. Prefetch warms the other two without blocking it.
    private val loader = Executors.newSingleThreadExecutor()
    private val prefetcher = Executors.newSingleThreadExecutor()

    // Thumbnails, keyed by image URI, so switching tabs is usually instant.
    private val thumbCache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    // Folder listing, reused when switching tabs (only re-read on open or folder change).
    private var cachedFolder: String? = null
    private var cachedDocs: List<DocumentFile> = emptyList()

    // Bumped on every refresh so slow, outdated loads are ignored.
    private var loadGen = 0

    // State for the preview
    private var pick = Pick.NEXT
    private var previewName: String? = null
    private var previewBitmap: Bitmap? = null
    private var ratio = 0.5f
    private var savedX: Float? = null
    private var cropX = 0.5f

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == WallpaperService.KEY_CURRENT) {
                runOnUiThread { showCurrent(p) }
            }
        }

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                prefs.edit().putString(KEY_FOLDER, uri.toString()).apply()
                tvFolder.text = folderName(uri.toString())
                refreshPreview(relist = true)
            }
        }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvFolder = findViewById(R.id.tvFolder)
        tvStatus = findViewById(R.id.tvStatus)
        tvCurrent = findViewById(R.id.tvCurrent)
        tvNextName = findViewById(R.id.tvNextName)
        tvCropInfo = findViewById(R.id.tvCropInfo)
        tvConfigPath = findViewById(R.id.tvConfigPath)
        etDuration = findViewById(R.id.etDuration)
        etWatchMinutes = findViewById(R.id.etWatchMinutes)
        cbWatchdog = findViewById(R.id.cbWatchdog)
        spUnit = findViewById(R.id.spUnit)
        rgPreview = findViewById(R.id.rgPreview)
        pbPreview = findViewById(R.id.pbPreview)
        cropView = findViewById(R.id.cropPreview)
        ivResult = findViewById(R.id.ivResult)

        spUnit.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, units
        )
        spUnit.setSelection(1) // default: minutes

        prefs.getString(KEY_FOLDER, null)?.let { tvFolder.text = folderName(it) }
        cbWatchdog.isChecked = prefs.getBoolean(KEY_WATCH_ON, false)
        etWatchMinutes.setText(prefs.getLong(KEY_WATCH_MIN, 15L).toString())
        showCurrent(prefs)
        tvConfigPath.text = "Crops saved to: " + CropStore.file(this).absolutePath

        rgPreview.setOnCheckedChangeListener { _, id ->
            pick = when (id) {
                R.id.rbPrev -> Pick.PREVIOUS
                R.id.rbCur -> Pick.CURRENT
                else -> Pick.NEXT
            }
            refreshPreview(relist = false)
        }

        cropView.onCropChanged = { x ->
            cropX = x
            updateResult()
            updateCropInfo()
        }

        findViewById<Button>(R.id.btnPickFolder).setOnClickListener { pickFolder.launch(null) }
        findViewById<Button>(R.id.btnStart).setOnClickListener { start() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stop() }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { requestBatteryExemption() }
        findViewById<Button>(R.id.btnSaveCrop).setOnClickListener { saveCrop() }
        findViewById<Button>(R.id.btnResetCrop).setOnClickListener { resetCrop() }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        showCurrent(prefs)
    }

    override fun onResume() {
        super.onResume()
        refreshPreview(relist = true)
    }

    override fun onStop() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onStop()
    }

    override fun onDestroy() {
        loader.shutdownNow()
        prefetcher.shutdownNow()
        super.onDestroy()
    }

    private fun start() {
        val folder = prefs.getString(KEY_FOLDER, null)
        if (folder == null) {
            toast("Choose a folder first")
            return
        }
        val amount = etDuration.text.toString().toLongOrNull()
        if (amount == null || amount <= 0) {
            toast("Enter a valid duration")
            return
        }
        val seconds = amount * unitSeconds[spUnit.selectedItemPosition]

        prefs.edit().putLong(WatchdogWorker.KEY_INTERVAL, seconds).apply()
        val intent = Intent(this, WallpaperService::class.java).apply {
            putExtra(WallpaperService.EXTRA_FOLDER, folder)
            putExtra(WallpaperService.EXTRA_INTERVAL_SECONDS, seconds)
        }
        ContextCompat.startForegroundService(this, intent)
        tvStatus.text = "Running: every $amount${units[spUnit.selectedItemPosition]}"
        applyWatchdog()
    }

    private fun stop() {
        // Stop means "don't run": the watchdog will not restart the service after this.
        prefs.edit().putBoolean(WallpaperService.KEY_RUNNING, false).apply()
        stopService(Intent(this, WallpaperService::class.java))
        tvStatus.text = "Stopped"
    }

    /** Turns the watchdog on or off from the checkbox and minutes box. */
    private fun applyWatchdog() {
        val on = cbWatchdog.isChecked
        val requested = etWatchMinutes.text.toString().toLongOrNull()
        if (requested == null || requested <= 0) {
            toast("Enter the check interval in minutes")
            return
        }
        prefs.edit()
            .putBoolean(KEY_WATCH_ON, on)
            .putLong(KEY_WATCH_MIN, requested)
            .apply()
        if (on) {
            val used = Watchdog.enable(this, requested)
            if (used != requested) {
                toast("Android allows checks every ${Watchdog.MIN_MINUTES} min at most often. Using ${used} min.")
                etWatchMinutes.setText(used.toString())
            }
        } else {
            Watchdog.disable(this)
        }
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("Already allowed to run in the background")
            return
        }
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName"))
        )
    }

    /**
     * Loads the picked image (previous, current or next) and its saved crop.
     * [relist] re-reads the folder; otherwise the cached listing is used.
     */
    private fun refreshPreview(relist: Boolean) {
        val gen = ++loadGen
        val folder = prefs.getString(KEY_FOLDER, null)
        val ctx = applicationContext
        val mode = pick
        showLoading(mode)

        loader.execute {
            try {
                if (gen != loadGen) return@execute
                if (relist || folder != cachedFolder) {
                    cachedDocs = listImages(ctx, folder)
                    cachedFolder = folder
                }
                val docs = cachedDocs
                val byUri = docs.associateBy { it.uri.toString() }

                val queue = ImageQueue(prefs)
                val nextUri = queue.peek(docs.map { it.uri.toString() })
                val currentUri = queue.current()
                val previousUri = queue.previous()
                val uri = when (mode) {
                    Pick.NEXT -> nextUri
                    Pick.CURRENT -> currentUri
                    Pick.PREVIOUS -> previousUri
                }

                val doc: DocumentFile? = uri?.let { byUri[it] }
                val bmp = doc?.let { loadThumb(ctx, it) }
                if (gen != loadGen) return@execute

                val r = targetRatio(ctx)
                val name = doc?.name
                val saved = name?.let { CropStore.get(ctx, it) }
                val fallback = when {
                    folder == null -> "choose a folder"
                    uri == null && mode == Pick.NEXT -> "no images in folder"
                    uri == null -> "nothing shown yet"
                    else -> "no longer in folder"
                }

                runOnUiThread {
                    if (gen != loadGen) return@runOnUiThread
                    previewName = name
                    previewBitmap = bmp
                    ratio = r
                    savedX = saved
                    cropX = saved ?: 0.5f
                    tvNextName.text = "${labelOf(mode)}: " + (name ?: fallback)
                    if (doc != null && bmp == null) {
                        cropView.showMessage("Couldn't load this image")
                    } else {
                        cropView.setImage(bmp, r, cropX)
                    }
                    updateResult()
                    updateCropInfo()
                    pbPreview.visibility = View.GONE
                }

                // Warm the cache for the other images so switching tabs is instant.
                val warm = listOfNotNull(nextUri, currentUri, previousUri).filter { it != uri }.distinct()
                prefetcher.execute {
                    for (u in warm) {
                        byUri[u]?.let { loadThumb(ctx, it) }
                    }
                }
            } catch (t: Throwable) {
                // Without this the spinner would stay on forever.
                Log.e(TAG, "Preview failed", t)
                runOnUiThread {
                    if (gen != loadGen) return@runOnUiThread
                    showError("Couldn't load preview (${t.javaClass.simpleName})")
                }
            }
        }
    }

    /** Clears the old preview at once and shows a spinner while the new one loads. */
    private fun showLoading(mode: Pick) {
        previewName = null
        previewBitmap = null
        savedX = null
        cropX = 0.5f
        cropView.setImage(null, ratio, 0.5f)
        ivResult.setImageDrawable(null)
        tvCropInfo.text = ""
        tvNextName.text = "${labelOf(mode)}: loading…"
        pbPreview.visibility = View.VISIBLE
    }

    private fun showError(text: String) {
        previewName = null
        previewBitmap = null
        cropView.showMessage(text)
        ivResult.setImageDrawable(null)
        tvCropInfo.text = ""
        pbPreview.visibility = View.GONE
    }

    private fun labelOf(mode: Pick): String = when (mode) {
        Pick.PREVIOUS -> "Previous"
        Pick.CURRENT -> "Current"
        Pick.NEXT -> "Next"
    }

    /** Cached thumbnail of an image. Safe to call from background threads. */
    private fun loadThumb(ctx: Context, doc: DocumentFile): Bitmap? {
        val key = doc.uri.toString()
        thumbCache.get(key)?.let { return it }
        val b = thumbnail(ctx, doc) ?: return null
        thumbCache.put(key, b)
        return b
    }

    /** Shows what the wallpaper will look like with the current crop position. */
    private fun updateResult() {
        val bmp = previewBitmap
        if (bmp == null) {
            ivResult.setImageDrawable(null)
            return
        }
        ivResult.setImageBitmap(applyCrop(bmp, ratio, cropX))
    }

    private fun updateCropInfo() {
        val s = savedX
        val status = when {
            previewName == null -> ""
            s == null && cropX == 0.5f -> "Using center crop (nothing saved)."
            s == null -> "Position %.2f, not saved.".format(cropX)
            cropX != s -> "Saved %.2f, now %.2f, not saved.".format(s, cropX)
            else -> "Saved crop: %.2f.".format(s)
        }
        // Current and previous images are already on screen or were, so a saved crop
        // only takes effect the next time that image comes up. Nothing is re-set now.
        val hint = if (pick != Pick.NEXT && previewName != null) {
            " Applies the next time this image comes up."
        } else {
            ""
        }
        // Debug line: screen shape vs image shape. Remove once the crop bug is confirmed fixed.
        val img = previewBitmap
        val debug = if (img != null) {
            "\n[debug: screen %.2f, image %.2f]".format(ratio, img.width.toFloat() / img.height)
        } else {
            ""
        }
        tvCropInfo.text = status + hint + debug
    }

    private fun saveCrop() {
        val name = previewName
        if (name == null) {
            toast("No image to save a crop for")
            return
        }
        CropStore.set(this, name, cropX)
        savedX = cropX
        updateCropInfo()
        toast("Crop saved")
    }

    private fun resetCrop() {
        val name = previewName ?: return
        cropX = 0.5f
        cropView.setPosition(0.5f)
        CropStore.set(this, name, null)
        savedX = null
        updateResult()
        updateCropInfo()
        toast("Reset to center crop")
    }

    private fun showCurrent(p: SharedPreferences) {
        tvCurrent.text = "Current: " + (p.getString(WallpaperService.KEY_CURRENT, null) ?: "none yet")
    }

    private fun folderName(uriStr: String): String =
        DocumentFile.fromTreeUri(this, Uri.parse(uriStr))?.name ?: uriStr

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = "MainActivity"
        private const val KEY_FOLDER = "folder"
        private const val KEY_WATCH_ON = "watch_on"
        private const val KEY_WATCH_MIN = "watch_min"
        private const val CACHE_BYTES = 24 * 1024 * 1024
    }
}
