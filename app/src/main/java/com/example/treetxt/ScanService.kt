package com.example.treetxt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs the folder scan as a foreground service so it keeps going when the
 * user minimizes the app or the Activity is recreated. Progress is pushed
 * to a Listener (the Activity, when bound) and mirrored in a notification.
 */
class ScanService : Service() {

    inner class LocalBinder : Binder() {
        fun service(): ScanService = this@ScanService
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    companion object {
        private const val CHANNEL_ID = "scan_channel"
        private const val NOTIF_ID = 1
        const val PREFS = "tree_txt_prefs"
        const val KEY_LAST_RESULT = "last_result_path"
    }

    interface Listener {
        fun onProgress(entries: Long, dirs: Long, files: Long)
        fun onFinished(entries: Long, dirs: Long, files: Long, cancelled: Boolean)
        fun onError(message: String)
    }

    var listener: Listener? = null

    var isRunning = false; private set
    var resultFile: File? = null; private set
    var lastEntries = 0L; private set
    var lastDirs = 0L; private set
    var lastFiles = 0L; private set
    var startTime = 0L; private set

    private val cancelled = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private var worker: Thread? = null

    override fun onCreate() {
        super.onCreate()
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Tree scan progress", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    fun startScan(root: Uri) {
        if (isRunning) return
        cancelled.set(false)
        isRunning = true
        lastEntries = 0; lastDirs = 0; lastFiles = 0
        startTime = System.currentTimeMillis()

        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification("Starting scan…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        worker = Thread {
            try {
                val out = File(cacheDir, "tree_${System.currentTimeMillis()}.txt")
                BufferedWriter(
                    OutputStreamWriter(out.outputStream().buffered(1 shl 20), Charsets.UTF_8),
                    1 shl 20
                ).use { w ->
                    w.write((displayName(root) ?: "folder") + "/\n")
                    scan(root, "", w)
                }
                if (cancelled.get()) {
                    out.delete()
                    finish(cancelledFlag = true)
                } else {
                    resultFile = out
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString(KEY_LAST_RESULT, out.absolutePath).apply()
                    finish(cancelledFlag = false)
                }
            } catch (e: Exception) {
                isRunning = false
                handler.post { listener?.onError(e.message ?: "Unknown error") }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        worker?.start()
    }

    fun cancelScan() {
        cancelled.set(true)
    }

    private fun finish(cancelledFlag: Boolean) {
        isRunning = false
        val e = lastEntries; val d = lastDirs; val f = lastFiles
        handler.post { listener?.onFinished(e, d, f, cancelledFlag) }
        val text = if (cancelledFlag) "Scan cancelled" else "Scan complete: $e entries"
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIF_ID, buildNotification(text))
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Tree TXT")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setOngoing(isRunning)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, isRunning)
            .build()

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (_: Exception) { null }

    private data class Node(val uri: Uri, val name: String, val dir: Boolean)
    private data class Frame(val nodes: MutableList<Node>, var index: Int, val prefix: String)

    // Root URI from ACTION_OPEN_DOCUMENT_TREE is a *tree* URI, not a document
    // URI, so DocumentsContract.getDocumentId() throws on it. Fall back to
    // getTreeDocumentId() for that case; children come back as proper
    // document URIs so getDocumentId() works fine on every level after that.
    private fun docIdOf(uri: Uri): String = try {
        DocumentsContract.getDocumentId(uri)
    } catch (_: Exception) {
        DocumentsContract.getTreeDocumentId(uri)
    }

    private fun children(uri: Uri): MutableList<Node> {
        val result = mutableListOf<Node>()
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, docIdOf(uri))
        contentResolver.query(
            childUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val name = c.getString(1) ?: "(unnamed)"
                val dir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                result.add(Node(DocumentsContract.buildDocumentUriUsingTree(uri, id), name, dir))
            }
        }
        result.sortWith(compareByDescending<Node> { it.dir }.thenBy { it.name.lowercase() })
        return result
    }

    // Iterative depth-first traversal: no recursion depth limit, safe for
    // very deep trees. Notification + listener updates are throttled by
    // both an entry-count step and a minimum time gap, keeping Binder /
    // main-thread traffic low on huge trees for better throughput.
    private fun scan(root: Uri, prefix: String, w: BufferedWriter) {
        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame(children(root), 0, prefix))
        var lastNotifyAt = 0L

        while (stack.isNotEmpty() && !cancelled.get()) {
            val frame = stack.peekLast()
            if (frame.index >= frame.nodes.size) {
                stack.removeLast()
                continue
            }
            val node = frame.nodes[frame.index++]
            val last = frame.index == frame.nodes.size

            w.write(frame.prefix)
            w.write(if (last) "└── " else "├── ")
            w.write(node.name)
            w.newLine()

            lastEntries++
            if (node.dir) lastDirs++ else lastFiles++

            if (node.dir) {
                stack.addLast(
                    Frame(children(node.uri), 0, frame.prefix + if (last) "    " else "│   ")
                )
            }

            if (lastEntries % 250L == 0L) {
                w.flush()
                val now = System.currentTimeMillis()
                if (now - lastNotifyAt > 400) {
                    lastNotifyAt = now
                    val e = lastEntries; val d = lastDirs; val f = lastFiles
                    handler.post { listener?.onProgress(e, d, f) }
                    val mgr = getSystemService(NotificationManager::class.java)
                    mgr.notify(NOTIF_ID, buildNotification("Scanning… $e entries"))
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        worker?.interrupt()
    }
}
