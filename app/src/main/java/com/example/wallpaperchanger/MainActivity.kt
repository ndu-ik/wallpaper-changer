package com.example.wallpaperchanger

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var tvFolder: TextView
    private lateinit var tvStatus: TextView
    private lateinit var etDuration: EditText
    private lateinit var spUnit: Spinner

    private var folderUri: Uri? = null
    private val units = listOf("s", "m", "h")
    private val unitSeconds = listOf(1L, 60L, 3600L)

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                folderUri = uri
                tvFolder.text = uri.toString()
                prefs().edit().putString(KEY_FOLDER, uri.toString()).apply()
            }
        }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvFolder = findViewById(R.id.tvFolder)
        tvStatus = findViewById(R.id.tvStatus)
        etDuration = findViewById(R.id.etDuration)
        spUnit = findViewById(R.id.spUnit)

        spUnit.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, units
        )
        spUnit.setSelection(1) // default: minutes

        prefs().getString(KEY_FOLDER, null)?.let {
            folderUri = Uri.parse(it)
            tvFolder.text = it
        }

        findViewById<Button>(R.id.btnPickFolder).setOnClickListener { pickFolder.launch(null) }
        findViewById<Button>(R.id.btnStart).setOnClickListener { start() }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, WallpaperService::class.java))
            tvStatus.text = "Stopped"
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun start() {
        val uri = folderUri
        if (uri == null) {
            Toast.makeText(this, "Choose a folder first", Toast.LENGTH_SHORT).show()
            return
        }
        val amount = etDuration.text.toString().toLongOrNull()
        if (amount == null || amount <= 0) {
            Toast.makeText(this, "Enter a valid duration", Toast.LENGTH_SHORT).show()
            return
        }
        val seconds = amount * unitSeconds[spUnit.selectedItemPosition]

        val intent = Intent(this, WallpaperService::class.java).apply {
            putExtra(WallpaperService.EXTRA_FOLDER, uri.toString())
            putExtra(WallpaperService.EXTRA_INTERVAL_SECONDS, seconds)
        }
        ContextCompat.startForegroundService(this, intent)
        tvStatus.text = "Running: every $amount${units[spUnit.selectedItemPosition]}"
    }

    private fun prefs() = getSharedPreferences("prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_FOLDER = "folder"
    }
}
