package com.ps2imgmanager

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ps2imgmanager.exfat.ExfatReader
import com.ps2imgmanager.exfat.ExfatWriter
import com.ps2imgmanager.image.ImageEntry
import com.ps2imgmanager.image.UriFileSource
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private var currentRoot: ImageEntry = ImageEntry.newRoot()
    private var currentImageName: String = "GAME"
    private val selected = mutableSetOf<ImageEntry>()
    private lateinit var adapter: EntryAdapter
    private lateinit var statusText: TextView
    private lateinit var targetDirText: TextView
    private lateinit var actionButtons: List<Button>

    // The folder currently being browsed — also where "Add Files" / "New Folder" land, since
    // this is drill-down navigation (like a normal file manager): tap a folder row to enter
    // it, or "Up" to go back. Defaults to root.
    private var currentDir: ImageEntry = currentRoot

    private val openImageLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { openImage(it) }
    }

    private val addFilesLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) addFiles(uris)
    }

    private val saveAsLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { saveImage(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        statusText = findViewById(R.id.statusText)
        targetDirText = findViewById(R.id.targetDirText)

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        adapter = EntryAdapter(selected) { dir -> enterFolder(dir) }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        val emptyText = findViewById<TextView>(R.id.emptyText)
        adapter.onCountChanged = { count ->
            emptyText.visibility = if (count == 0) View.VISIBLE else View.GONE
        }

        val btnNew = findViewById<Button>(R.id.btnNew)
        val btnOpen = findViewById<Button>(R.id.btnOpen)
        val btnAddFiles = findViewById<Button>(R.id.btnAddFiles)
        val btnNewFolder = findViewById<Button>(R.id.btnNewFolder)
        val btnDelete = findViewById<Button>(R.id.btnDelete)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnUp = findViewById<Button>(R.id.btnUp)
        actionButtons = listOf(btnNew, btnOpen, btnAddFiles, btnNewFolder, btnDelete, btnSave, btnUp)

        btnNew.setOnClickListener { newImage() }
        btnOpen.setOnClickListener { openImageLauncher.launch(arrayOf("*/*")) }
        btnAddFiles.setOnClickListener { addFilesLauncher.launch(arrayOf("*/*")) }
        btnNewFolder.setOnClickListener { promptNewFolder() }
        btnDelete.setOnClickListener { deleteSelected() }
        btnSave.setOnClickListener { saveAsLauncher.launch("$currentImageName.img") }
        btnUp.setOnClickListener { goUp() }

        refresh()
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        for (b in actionButtons) b.isEnabled = enabled
    }

    private fun newImage() {
        currentRoot = ImageEntry.newRoot()
        currentDir = currentRoot
        selected.clear()
        currentImageName = "GAME"
        statusText.text = "New empty image. Add files, then Save As .img."
        refresh()
    }

    /**
     * Opening an existing image copies its bytes into cache and parses the whole
     * directory tree. For a multi-GB PS2 dump this can take a while, so it must
     * run off the main thread or Android will treat the UI as frozen (ANR).
     */
    private fun openImage(uri: Uri) {
        setButtonsEnabled(false)
        statusText.text = "Opening image… please wait, this can take a while for large images."
        Thread {
            try {
                val tempFile = File(cacheDir, "opened_image.img")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output -> input.copyTo(output, 1 shl 20) }
                } ?: throw IOException("Could not open selected file")

                val tree = ExfatReader(tempFile.absolutePath).read()
                val name = queryDisplayName(uri)?.substringBeforeLast('.') ?: "GAME"

                runOnUiThread {
                    currentRoot = tree
                    currentDir = currentRoot
                    selected.clear()
                    currentImageName = name
                    statusText.text = "Opened image. Add/delete entries, then Save As .img."
                    setButtonsEnabled(true)
                    refresh()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Failed to open image: ${e.message}", Toast.LENGTH_LONG).show()
                    statusText.text = "Failed to open image."
                    setButtonsEnabled(true)
                }
            }
        }.start()
    }

    private fun addFiles(uris: List<Uri>) {
        var count = 0
        for (uri in uris) {
            val name = queryDisplayName(uri) ?: continue
            val size = querySize(uri) ?: continue
            val source = UriFileSource(contentResolver, uri, size)
            // Replace an existing entry with the same name in the active dir, else add new.
            currentDir.children.removeAll { !it.isDirectory && it.name.equals(name, ignoreCase = true) }
            currentDir.addChild(ImageEntry.newFile(name, source))
            count++
        }
        statusText.text = "Added $count file(s) to ${fullPath(currentDir)}"
        refresh()
    }

    private fun promptNewFolder() {
        val input = EditText(this)
        AlertDialog.Builder(this)
            .setTitle("New folder in ${fullPath(currentDir)}")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    currentDir.addChild(ImageEntry.newDir(name))
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Tap a folder row in the list to navigate into it — this becomes both the view and the target. */
    private fun enterFolder(dir: ImageEntry) {
        currentDir = dir
        selected.clear()
        refresh()
    }

    /** Goes to the parent of the current folder; does nothing if already at root. */
    private fun goUp() {
        val p = currentDir.parent
        if (p == null) {
            Toast.makeText(this, "Already at root", Toast.LENGTH_SHORT).show()
            return
        }
        currentDir = p
        selected.clear()
        refresh()
    }

    private fun deleteSelected() {
        if (selected.isEmpty()) {
            Toast.makeText(this, "Nothing selected", Toast.LENGTH_SHORT).show()
            return
        }
        // The list only ever shows currentDir's direct children, so a selection can only
        // contain entries from currentDir — no need to search the whole tree.
        val n = selected.size
        currentDir.children.removeAll { it in selected }
        selected.clear()
        statusText.text = "Deleted $n entr${if (n == 1) "y" else "ies"}."
        refresh()
    }

    /**
     * Writing the final exFAT image copies every file's bytes — for a real PS2 game
     * library this is gigabytes of I/O. Must run off the main thread, same reason
     * as openImage(). Snapshot the tree before starting since the user could in
     * theory touch the UI again once buttons are re-enabled.
     */
    private fun saveImage(uri: Uri) {
        if (!hasAnyFiles(currentRoot)) {
            Toast.makeText(this, "Image is empty — add files first", Toast.LENGTH_LONG).show()
            return
        }
        setButtonsEnabled(false)
        statusText.text = "Saving image… this can take a while for large images, please wait."

        val rootSnapshot = currentRoot

        Thread {
            try {
                contentResolver.openOutputStream(uri)?.let { raw ->
                    BufferedOutputStream(raw, 1 shl 20).use { out ->
                        ExfatWriter(rootSnapshot).write(out)
                    }
                } ?: throw IOException("Could not open output stream")

                runOnUiThread {
                    statusText.text = "Saved successfully."
                    Toast.makeText(this, "Image saved", Toast.LENGTH_SHORT).show()
                    setButtonsEnabled(true)
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Failed to save image: ${e.message}", Toast.LENGTH_LONG).show()
                    statusText.text = "Save failed: ${e.message}"
                    setButtonsEnabled(true)
                }
            }
        }.start()
    }

    private fun hasAnyFiles(dir: ImageEntry): Boolean {
        for (c in dir.children) {
            if (!c.isDirectory) return true
            if (hasAnyFiles(c)) return true
        }
        return false
    }

    /** Builds a display path like "/FOLDER/SUBFOLDER" by walking parent pointers up to root. */
    private fun fullPath(dir: ImageEntry): String {
        val names = mutableListOf<String>()
        var cur: ImageEntry? = dir
        while (cur != null && cur !== currentRoot) {
            names.add(0, cur.name)
            cur = cur.parent
        }
        return if (names.isEmpty()) "/" else "/" + names.joinToString("/")
    }

    private fun updateTargetDirText() {
        val n = currentDir.children.size
        targetDirText.text = "📁 ${fullPath(currentDir)}  ·  $n item${if (n == 1) "" else "s"}"
    }

    private fun refresh() {
        adapter.submit(currentDir)
        updateTargetDirText()
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
        }
        return uri.lastPathSegment
    }

    private fun querySize(uri: Uri): Long? {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) return c.getLong(idx)
        }
        return null
    }
}
