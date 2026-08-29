package com.example.treetxt

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.view.ViewGroup
import android.widget.*
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var generate: Button
    private lateinit var save: Button
    private var root: Uri? = null
    private var tempFile: java.io.File? = null
    private val cancelled = AtomicBoolean(false)
    private val PICK = 10
    private val SAVE = 11
    private var entries = 0L
    private var dirs = 0L
    private var files = 0L

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24,24,24,24)
        }
        val title = TextView(this).apply { text = "Tree TXT"; textSize = 25f }
        val info = TextView(this).apply {
            text = "Generate a Linux tree-style text file from huge folders."
            setPadding(0,10,0,16)
        }
        val pick = Button(this).apply { text = "1. Select folder" }
        generate = Button(this).apply { text = "2. Generate tree.txt"; isEnabled = false }
        save = Button(this).apply { text = "3. Save tree.txt"; isEnabled = false }
        val cancel = Button(this).apply { text = "Cancel"; isEnabled = false }
        status = TextView(this).apply { text = "No folder selected."; setPadding(0,14,0,0) }
        listOf(title,info,pick,generate,save,cancel,status).forEach {
            box.addView(it, ViewGroup.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(box)

        pick.setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }, PICK)
        }

        generate.setOnClickListener {
            val selected = root ?: return@setOnClickListener
            cancelled.set(false); generate.isEnabled=false; save.isEnabled=false; cancel.isEnabled=true
            Thread {
                try {
                    entries=0; dirs=0; files=0
                    val out = java.io.File(cacheDir, "tree.txt.part")
                    tempFile=out
                    BufferedWriter(OutputStreamWriter(out.outputStream().buffered(), Charsets.UTF_8), 1024*1024).use { w ->
                        w.write((displayName(selected) ?: "folder") + "/\n")
                        scan(selected, "", w)
                    }
                    if (cancelled.get()) {
                        out.delete()
                        runOnUiThread { status.text="Cancelled."; generate.isEnabled=true; cancel.isEnabled=false }
                    } else {
                        runOnUiThread {
                            status.text="Finished: $entries entries ($dirs folders, $files files)."
                            save.isEnabled=true; generate.isEnabled=true; cancel.isEnabled=false
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread { status.text="Error: ${e.message}"; generate.isEnabled=true; cancel.isEnabled=false }
                }
            }.start()
        }

        cancel.setOnClickListener { cancelled.set(true); status.text="Cancelling…" }

        save.setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type="text/plain"
                putExtra(Intent.EXTRA_TITLE, "tree.txt")
                addCategory(Intent.CATEGORY_OPENABLE)
            }, SAVE)
        }
    }

    override fun onActivityResult(requestCode:Int, resultCode:Int, data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if (resultCode != RESULT_OK || data?.data == null) return
        if (requestCode == PICK) {
            root=data.data
            try {
                contentResolver.takePersistableUriPermission(root!!,
                    data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_:Exception) {}
            status.text="Selected: ${displayName(root!!)}"
            generate.isEnabled=true
        } else if (requestCode == SAVE) {
            try {
                contentResolver.openOutputStream(data.data!!)?.use { dst ->
                    tempFile?.inputStream()?.use { src -> src.copyTo(dst, 1024*1024) }
                }
                status.text="tree.txt saved successfully."
            } catch(e:Exception) { status.text="Save failed: ${e.message}" }
        }
    }

    private fun displayName(uri:Uri):String? = try {
        contentResolver.query(uri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use {
            if(it.moveToFirst()) it.getString(0) else null
        }
    } catch(_:Exception){null}

    private data class Node(val uri:Uri,val name:String,val dir:Boolean)

    private fun children(uri:Uri):MutableList<Node> {
        val result=mutableListOf<Node>()
        val childUri=DocumentsContract.buildChildDocumentsUriUsingTree(
            uri, DocumentsContract.getDocumentId(uri))
        contentResolver.query(childUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE),
            null,null,null)?.use { c ->
            while(c.moveToNext()) {
                val id=c.getString(0)
                val name=c.getString(1) ?: "(unnamed)"
                val dir=c.getString(2)==DocumentsContract.Document.MIME_TYPE_DIR
                result.add(Node(
                    DocumentsContract.buildDocumentUriUsingTree(uri,id), name, dir))
            }
        }
        result.sortWith(compareByDescending<Node>{it.dir}.thenBy{it.name.lowercase()})
        return result
    }

    private data class Frame(val nodes:MutableList<Node>, var index:Int, val prefix:String)

    // Iterative depth-first traversal: no recursion depth limit.
    private fun scan(root:Uri, prefix:String, w:BufferedWriter) {
        val stack=ArrayDeque<Frame>()
        stack.addLast(Frame(children(root),0,prefix))
        while(stack.isNotEmpty() && !cancelled.get()) {
            val frame=stack.peekLast()
            if(frame.index>=frame.nodes.size) { stack.removeLast(); continue }
            val node=frame.nodes[frame.index++]
            val last=frame.index==frame.nodes.size
            w.write(frame.prefix)
            w.write(if(last) "└── " else "├── ")
            w.write(node.name)
            w.newLine()
            entries++
            if(node.dir) dirs++ else files++
            if(node.dir) {
                stack.addLast(Frame(children(node.uri),0,
                    frame.prefix + if(last) "    " else "│   "))
            }
            if(entries % 500L == 0L) {
                w.flush()
                val e=entries; val d=dirs; val f=files
                runOnUiThread { status.text="Scanning… $e entries ($d folders, $f files)" }
            }
        }
    }
}
