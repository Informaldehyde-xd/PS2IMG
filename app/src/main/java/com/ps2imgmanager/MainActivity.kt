package com.ps2imgmanager

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.GridLayoutManager
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
    private var volumeLabel: String = "PS2GAME"
    private val selected = mutableSetOf<ImageEntry>()
    private lateinit var adapter: EntryAdapter
    private lateinit var statusText: TextView
    private lateinit var breadcrumbRow: LinearLayout
    private lateinit var navRows: List<View>

    // The folder currently being browsed — also where "Add Files" / "New Folder" land, since
    // this is drill-down navigation (like a normal file manager): tap a folder tile to enter
    // it, tap a breadcrumb segment (or "Up"/"Home") to go back. Defaults to root.
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

        statusText = findViewById(R.id.statusText)
        breadcrumbRow = findViewById(R.id.breadcrumbRow)

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        adapter = EntryAdapter(selected) { dir -> enterFolder(dir) }
        val gridLayoutManager = GridLayoutManager(this, 2)
        gridLayoutManager.spanSizeLookup = adapter.spanSizeLookup
        recyclerView.layoutManager = gridLayoutManager
        recyclerView.adapter = adapter
        val emptyText = findViewById<TextView>(R.id.emptyText)
        adapter.onCountChanged = { count ->
            emptyText.visibility = if (count == 0) View.VISIBLE else View.GONE
        }

        setupSidebarRow(R.id.navHome, "🏠", "Home") { goToRoot() }
        setupSidebarRow(R.id.navNewImage, "🆕", "New Image") { newImage() }
        setupSidebarRow(R.id.navOpenImage, "📂", "Open Image") { openImageLauncher.launch(arrayOf("*/*")) }
        setupSidebarRow(R.id.navNewFolder, "➕", "New Folder") { promptNewFolder() }
        setupSidebarRow(R.id.navDelete, "🗑", "Delete Selected") { deleteSelected() }
        setupSidebarRow(R.id.navSettings, "⚙️", "Settings") { showSettingsDialog() }
        navRows = listOf(
            findViewById(R.id.navHome), findViewById(R.id.navNewImage), findViewById(R.id.navOpenImage),
            findViewById(R.id.navNewFolder), findViewById(R.id.navDelete), findViewById(R.id.navSettings)
        )

        findViewById<View>(R.id.bottomHome).setOnClickListener { goToRoot() }
        findViewById<View>(R.id.bottomAdd).setOnClickListener { addFilesLauncher.launch(arrayOf("*/*")) }
        findViewById<View>(R.id.bottomUp).setOnClickListener { goUp() }
        findViewById<View>(R.id.bottomSave).setOnClickListener { saveAsLauncher.launch("$currentImageName.img") }

        findViewById<EditText>(R.id.searchBox).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.setFilter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        refresh()
    }

    /** Wires one <include layout="item_nav"> instance: sets its icon/label and click action. */
    private fun setupSidebarRow(containerId: Int, icon: String, label: String, onClick: () -> Unit) {
        val row = findViewById<View>(containerId)
        row.findViewById<TextView>(R.id.navIcon).text = icon
        row.findViewById<TextView>(R.id.navLabel).text = label
        row.setOnClickListener { onClick() }
    }

    private fun setBusy(busy: Boolean) {
        for (r in navRows) r.isEnabled = !busy
        for (id in listOf(R.id.bottomHome, R.id.bottomAdd, R.id.bottomUp, R.id.bottomSave)) {
            findViewById<View>(id).isEnabled = !busy
        }
    }

    private fun newImage() {
        currentRoot = ImageEntry.newRoot()
        currentDir = currentRoot
        selected.clear()
        currentImageName = "GAME"
        volumeLabel = "PS2GAME"
        statusText.text = "New empty exFAT image. Add files, then Save."
        refresh()
    }

    /**
     * Opening an existing image copies its bytes into cache and parses the whole
     * directory tree. For a multi-GB image this can take a while, so it must run
     * off the main thread or Android will treat the UI as frozen (ANR).
     */
    private fun openImage(uri: Uri) {
        setBusy(true)
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
                    statusText.text = "Opened image. Add/delete entries, then Save."
                    setBusy(false)
                    refresh()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Failed to open image: ${e.message}", Toast.LENGTH_LONG).show()
                    statusText.text = "Failed to open image."
                    setBusy(false)
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

    private fun showSettingsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val label = TextView(this).apply { text = "Volume label (shown when the image is mounted):" }
        val input = EditText(this).apply { setText(volumeLabel) }
        container.addView(label)
        container.addView(input)

        val itemCount = countAll(currentRoot)
        val info = TextView(this).apply {
            setPadding(0, 32, 0, 0)
            text = "Format: exFAT\nTotal items in image: $itemCount\nCurrent folder: ${fullPath(currentDir)}"
            setTextColor(0xFF888888.toInt())
        }
        container.addView(info)

        AlertDialog.Builder(this)
            .setTitle("Image settings")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                volumeLabel = input.text.toString().trim().take(11)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun countAll(dir: ImageEntry): Int {
        var n = 0
        for (c in dir.children) {
            n++
            if (c.isDirectory) n += countAll(c)
        }
        return n
    }

    /** Tap a folder tile to navigate into it — this becomes both the view and the target. */
    private fun enterFolder(dir: ImageEntry) {
        currentDir = dir
        selected.clear()
        refresh()
    }

    private fun goToRoot() {
        currentDir = currentRoot
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
     * Writing the final exFAT image copies every file's bytes — for a real image
     * library this is gigabytes of I/O. Must run off the main thread, same reason
     * as openImage(). Snapshot the tree before starting since the user could in
     * theory touch the UI again once controls are re-enabled.
     */
    private fun saveImage(uri: Uri) {
        if (!hasAnyFiles(currentRoot)) {
            Toast.makeText(this, "Image is empty — add files first", Toast.LENGTH_LONG).show()
            return
        }
        setBusy(true)
        statusText.text = "Saving image… this can take a while for large images, please wait."

        val rootSnapshot = currentRoot
        val labelSnapshot = volumeLabel

        Thread {
            try {
                contentResolver.openOutputStream(uri)?.let { raw ->
                    BufferedOutputStream(raw, 1 shl 20).use { out ->
                        ExfatWriter(rootSnapshot, labelSnapshot).write(out) { written, total ->
                            if (total > 0) {
                                val pct = (written * 100 / total)
                                runOnUiThread { statusText.text = "Saving image… $pct%" }
                            }
                        }
                    }
                } ?: throw IOException("Could not open output stream")

                runOnUiThread {
                    statusText.text = "Saved successfully."
                    Toast.makeText(this, "Image saved", Toast.LENGTH_SHORT).show()
                    setBusy(false)
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Failed to save image: ${e.message}", Toast.LENGTH_LONG).show()
                    statusText.text = "Save failed: ${e.message}"
                    setBusy(false)
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

    /** Ancestor chain from root down to (and including) [dir]. */
    private fun ancestorChain(dir: ImageEntry): List<ImageEntry> {
        val chain = mutableListOf<ImageEntry>()
        var cur: ImageEntry? = dir
        while (cur != null) {
            chain.add(0, cur)
            cur = cur.parent
        }
        return chain
    }

    /** Rebuilds the clickable "Home > folder > subfolder" breadcrumb row. */
    private fun renderBreadcrumb() {
        breadcrumbRow.removeAllViews()
        val chain = ancestorChain(currentDir)
        for ((index, node) in chain.withIndex()) {
            val label = if (node === currentRoot) "Home" else node.name
            val tv = TextView(this).apply {
                text = label
                textSize = 14f
                setTextColor(if (node === currentDir) 0xFF000000.toInt() else 0xFF4A6FA5.toInt())
                setPadding(4, 8, 4, 8)
                setOnClickListener {
                    currentDir = node
                    selected.clear()
                    refresh()
                }
            }
            breadcrumbRow.addView(tv)
            if (index != chain.lastIndex) {
                breadcrumbRow.addView(TextView(this).apply {
                    text = "  >  "
                    textSize = 14f
                    setTextColor(0xFFBBBBBB.toInt())
                })
            }
        }
    }

    private fun refresh() {
        adapter.submit(currentDir)
        renderBreadcrumb()
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
