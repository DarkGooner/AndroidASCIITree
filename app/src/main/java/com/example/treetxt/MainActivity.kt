package com.example.treetxt

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.DocumentsContract
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : Activity(), ScanService.Listener {

    private lateinit var status: TextView
    private lateinit var stats: TextView
    private lateinit var progress: ProgressBar
    private lateinit var pickBtn: Button
    private lateinit var generateBtn: Button
    private lateinit var saveBtn: Button
    private lateinit var cancelBtn: Button

    private var root: Uri? = null
    private var pendingRoot: Uri? = null
    private var scanService: ScanService? = null
    private var bound = false
    private var scanStartMillis = 0L

    private val PICK = 10
    private val SAVE = 11
    private val NOTIF_PERM = 12

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            scanService = (service as ScanService.LocalBinder).service()
            scanService?.listener = this@MainActivity
            bound = true

            pendingRoot?.let { r -> scanService?.startScan(r); pendingRoot = null }

            if (scanService?.isRunning == true) {
                scanStartMillis = scanService!!.startTime
                setScanningUi(true)
                onProgress(scanService!!.lastEntries, scanService!!.lastDirs, scanService!!.lastFiles)
            } else if (scanService?.resultFile != null || lastSavedResultPath() != null) {
                saveBtn.isEnabled = true
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) { bound = false }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIF_PERM)
        }

        val accent = ContextCompat.getColor(this, R.color.accent)
        val danger = ContextCompat.getColor(this, R.color.danger)
        val buttonText = ContextCompat.getColor(this, R.color.button_text)
        val textPrimary = ContextCompat.getColor(this, R.color.text_primary)
        val textSecondary = ContextCompat.getColor(this, R.color.text_secondary)
        val textStats = ContextCompat.getColor(this, R.color.text_stats)

        fun styledButton(text: String, color: Int = accent) = Button(this).apply {
            this.text = text
            setTextColor(buttonText)
            isAllCaps = false
            textSize = 16f
            setPadding(24, 30, 24, 30)
            background = GradientDrawable().apply {
                cornerRadius = 22f
                setColor(color)
            }
        }

        fun spacer(h: Int) = View(this).apply { layoutParams = ViewGroup.LayoutParams(-1, h) }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 60)
        }
        val title = TextView(this).apply {
            text = "Tree TXT"; textSize = 30f; setTextColor(textPrimary)
        }
        val info = TextView(this).apply {
            text = "Generate a Linux tree-style text file from huge folders. " +
                "Scanning keeps running even if you switch apps."
            setPadding(0, 12, 0, 8)
            setTextColor(textSecondary)
        }

        pickBtn = styledButton("1. Select folder")
        generateBtn = styledButton("2. Generate tree.txt").apply { isEnabled = false }
        saveBtn = styledButton("3. Save tree.txt").apply { isEnabled = false }
        cancelBtn = styledButton("Cancel", danger).apply { isEnabled = false }

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        stats = TextView(this).apply {
            setPadding(0, 14, 0, 0)
            setTextColor(textStats)
        }
        status = TextView(this).apply {
            text = "No folder selected."
            setPadding(0, 10, 0, 0)
            setTextColor(textPrimary)
            textSize = 15f
        }

        val wrapLp = ViewGroup.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)
        box.addView(title, wrapLp)
        box.addView(info, wrapLp)
        box.addView(spacer(20))
        box.addView(pickBtn, wrapLp)
        box.addView(spacer(16))
        box.addView(generateBtn, wrapLp)
        box.addView(spacer(16))
        box.addView(saveBtn, wrapLp)
        box.addView(spacer(16))
        box.addView(cancelBtn, wrapLp)
        box.addView(spacer(24))
        box.addView(progress, wrapLp)
        box.addView(stats, wrapLp)
        box.addView(status, wrapLp)

        setContentView(ScrollView(this).apply { addView(box) })

        pickBtn.setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }, PICK)
        }

        generateBtn.setOnClickListener {
            val selected = root ?: return@setOnClickListener
            startService(Intent(this, ScanService::class.java))
            scanStartMillis = System.currentTimeMillis()
            setScanningUi(true)
            if (bound) scanService?.startScan(selected) else pendingRoot = selected
        }

        cancelBtn.setOnClickListener {
            scanService?.cancelScan()
            status.text = "Cancelling…"
        }

        saveBtn.setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "tree.txt")
                addCategory(Intent.CATEGORY_OPENABLE)
            }, SAVE)
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, ScanService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (bound) { unbindService(connection); bound = false }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return

        if (requestCode == PICK) {
            root = data.data
            try {
                contentResolver.takePersistableUriPermission(
                    root!!, data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            status.text = "Selected: ${displayName(root!!)}"
            generateBtn.isEnabled = true
        } else if (requestCode == SAVE) {
            val path = scanService?.resultFile?.absolutePath ?: lastSavedResultPath()
            val file = path?.let { File(it) }
            if (file == null || !file.exists()) {
                status.text = "No result file found. Generate tree.txt first."
                return
            }
            try {
                contentResolver.openOutputStream(data.data!!)?.use { dst ->
                    file.inputStream().use { src -> src.copyTo(dst, 1 shl 20) }
                }
                status.text = "tree.txt saved successfully."
            } catch (e: Exception) {
                status.text = "Save failed: ${e.message}"
            }
        }
    }

    private fun lastSavedResultPath(): String? =
        getSharedPreferences(ScanService.PREFS, MODE_PRIVATE).getString(ScanService.KEY_LAST_RESULT, null)

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (_: Exception) { null }

    private fun setScanningUi(running: Boolean) {
        generateBtn.isEnabled = !running
        pickBtn.isEnabled = !running
        cancelBtn.isEnabled = running
        progress.visibility = if (running) View.VISIBLE else View.GONE
        if (running) status.text = "Scanning…"
    }

    override fun onProgress(entries: Long, dirs: Long, files: Long) {
        val elapsedSec = ((System.currentTimeMillis() - scanStartMillis) / 1000.0).coerceAtLeast(0.5)
        val rate = (entries / elapsedSec).toInt()
        status.text = "Scanning… $entries entries ($dirs folders, $files files)"
        stats.text = "Elapsed ${elapsedSec.toInt()}s · ~$rate entries/sec"
    }

    override fun onFinished(entries: Long, dirs: Long, files: Long, cancelled: Boolean) {
        setScanningUi(false)
        status.text = if (cancelled) "Cancelled."
            else "Finished: $entries entries ($dirs folders, $files files)."
        saveBtn.isEnabled = !cancelled
    }

    override fun onError(message: String) {
        setScanningUi(false)
        status.text = "Error: $message"
    }
}
