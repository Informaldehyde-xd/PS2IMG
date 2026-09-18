package com.ps2imgmanager

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ps2imgmanager.image.ImageEntry

/**
 * Shows the direct children of ONE folder at a time (real drill-down navigation, like a
 * normal file manager) rather than the whole tree flattened — so "where am I / where will
 * this land" is never ambiguous. Tapping a folder row asks the activity to navigate into it;
 * the breadcrumb above the list (owned by the activity) shows the current path.
 */
class EntryAdapter(
    private val selected: MutableSet<ImageEntry>,
    private val onFolderClick: (ImageEntry) -> Unit
) : RecyclerView.Adapter<EntryAdapter.VH>() {

    private val rows = mutableListOf<ImageEntry>()
    var onCountChanged: ((Int) -> Unit)? = null

    /** Shows [dir]'s direct children only. */
    fun submit(dir: ImageEntry) {
        rows.clear()
        rows.addAll(dir.children.sortedWith(compareBy({ !it.isDirectory }, { it.name.uppercase() })))
        notifyDataSetChanged()
        onCountChanged?.invoke(rows.size)
    }

    class VH(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val checkbox: android.widget.CheckBox = view.findViewById(R.id.checkbox)
        val iconText: android.widget.TextView = view.findViewById(R.id.iconText)
        val nameText: android.widget.TextView = view.findViewById(R.id.nameText)
        val sizeText: android.widget.TextView = view.findViewById(R.id.sizeText)
        val chevronText: android.widget.TextView = view.findViewById(R.id.chevronText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_entry, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = rows[position]
        holder.iconText.text = if (entry.isDirectory) "\uD83D\uDCC1" else "\uD83D\uDCC4"
        holder.nameText.text = entry.name
        holder.sizeText.text = if (entry.isDirectory) "" else formatSize(entry.source?.size ?: 0)
        holder.chevronText.visibility = if (entry.isDirectory) android.view.View.VISIBLE else android.view.View.INVISIBLE

        holder.checkbox.setOnCheckedChangeListener(null)
        holder.checkbox.isChecked = selected.contains(entry)
        holder.checkbox.setOnCheckedChangeListener { _, checked ->
            if (checked) selected.add(entry) else selected.remove(entry)
        }

        // Tapping the row (anywhere except the checkbox) navigates into a folder.
        holder.itemView.setOnClickListener {
            if (entry.isDirectory) onFolderClick(entry)
        }
    }

    override fun getItemCount(): Int = rows.size

    fun entryAt(position: Int): ImageEntry = rows[position]

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }
}
