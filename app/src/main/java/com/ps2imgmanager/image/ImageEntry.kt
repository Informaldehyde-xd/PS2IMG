package com.ps2imgmanager.image

/**
 * A node in the virtual image tree: either a directory (with children)
 * or a file (with a lazy [FileSource] providing its bytes).
 */
class ImageEntry(
    var name: String,
    val isDirectory: Boolean,
    val source: FileSource? = null,
    val children: MutableList<ImageEntry> = mutableListOf()
) {
    /** Set automatically by [addChild]. Used for "Up" navigation in the UI. Root's parent is null. */
    var parent: ImageEntry? = null
        private set

    /** Adds a child and keeps its parent pointer in sync — always use this instead of children.add(). */
    fun addChild(child: ImageEntry) {
        children.add(child)
        child.parent = this
    }

    companion object {
        fun newRoot(): ImageEntry = ImageEntry("", true)
        fun newDir(name: String): ImageEntry = ImageEntry(name, true)
        fun newFile(name: String, source: FileSource): ImageEntry = ImageEntry(name, false, source)
    }
}
