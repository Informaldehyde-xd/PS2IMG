package com.ps2imgmanager

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ps2imgmanager.image.ImageEntry

/**
 * Shows the direct children of ONE folder at a time (drill-down navigation, like a normal
 * file manager). Folders render as square grid cells (2 per row); files render as full-width
 * rows below them — matching the target UI. A [GridLayoutManager] with spanCount=2 plus
 * [spanSizeLookup] makes this work: folders span 1 column, files span both.
 *
 * A live search filter (see [setFilter]) narrows [dir]'s children by name without leaving
 * the folder.
 */
class EntryAdapter(
    private val selected: MutableSet<ImageEntry>,
    private val onFolderClick: (ImageEntry) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_FOLDER = 0
        private const val TYPE_FILE = 1
    }

    private var currentDir: ImageEntry? = null
    private var filterText: String = ""
    private val rows = mutableListOf<ImageEntry>()
    var onCountChanged: ((Int) -> Unit)? = null

    val spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
        override fun getSpanSize(position: Int): Int =
            if (getItemViewType(position) == TYPE_FOLDER) 1 else 2
    }

    /** Shows [dir]'s direct children only, applying the current search filter if any. */
    fun submit(dir: ImageEntry) {
        currentDir = dir
        applyFilter()
    }

    /** Re-filters the currently shown folder by [text] (case-insensitive substring on name). */
    fun setFilter(text: String) {
        filterText = text
        applyFilter()
    }

    private fun applyFilter() {
        val dir = currentDir ?: return
        val q = filterText.trim().lowercase()
        val matching = if (q.isEmpty()) dir.children else dir.children.filter { it.name.lowercase().contains(q) }
        rows.clear()
        rows.addAll(matching.sortedWith(compareBy({ !it.isDirectory }, { it.name.uppercase() })))
        notifyDataSetChanged()
        onCountChanged?.invoke(rows.size)
    }

    override fun getItemViewType(position: Int): Int =
        if (rows[position].isDirectory) TYPE_FOLDER else TYPE_FILE

    class FolderVH(view: View) : RecyclerView.ViewHolder(view) {
        val checkbox: android.widget.CheckBox = view.findViewById(R.id.checkbox)
        val icon: android.widget.TextView = view.findViewById(R.id.folderIcon)
        val name: android.widget.TextView = view.findViewById(R.id.folderName)
    }

    class FileVH(view: View) : RecyclerView.ViewHolder(view) {
        val checkbox: android.widget.CheckBox = view.findViewById(R.id.checkbox)
        val icon: android.widget.TextView = view.findViewById(R.id.fileIcon)
        val name: android.widget.TextView = view.findViewById(R.id.fileName)
        val subtitle: android.widget.TextView = view.findViewById(R.id.fileSubtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_FOLDER) {
            FolderVH(inflater.inflate(R.layout.item_folder_grid, parent, false))
        } else {
            FileVH(inflater.inflate(R.layout.item_file_row, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val entry = rows[position]
        when (holder) {
            is FolderVH -> {
                holder.icon.text = "📁"
                holder.name.text = entry.name
                bindCheckbox(holder.checkbox, entry)
                holder.itemView.setOnClickListener { onFolderClick(entry) }
            }
            is FileVH -> {
                holder.icon.text = "📄"
                holder.name.text = entry.name
                holder.subtitle.text = "Size: ${formatSize(entry.source?.size ?: 0)}"
                bindCheckbox(holder.checkbox, entry)
                holder.itemView.setOnClickListener {
                    holder.checkbox.isChecked = !holder.checkbox.isChecked
                }
            }
        }
    }

    private fun bindCheckbox(checkbox: android.widget.CheckBox, entry: ImageEntry) {
        checkbox.setOnCheckedChangeListener(null)
        checkbox.isChecked = selected.contains(entry)
        checkbox.setOnCheckedChangeListener { _, checked ->
            if (checked) selected.add(entry) else selected.remove(entry)
        }
    }

    override fun getItemCount(): Int = rows.size

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }
}
