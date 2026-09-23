package com.ps2imgmanager.image

import android.content.ContentResolver
import android.net.Uri
import java.io.InputStream
import java.io.RandomAccessFile

/** Something that can produce file bytes lazily, plus a known size. */
interface FileSource {
    val size: Long
    fun openStream(): InputStream
}

/** File bytes coming from an Android content Uri (e.g. picked via SAF). */
class UriFileSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val size: Long
) : FileSource {
    override fun openStream(): InputStream =
        resolver.openInputStream(uri) ?: throw java.io.IOException("Cannot open $uri")
}

/** File bytes that are a single byte-range region of an already-existing .img file on disk. */
class ImageRegionSource(
    imagePath: String,
    offset: Long,
    override val size: Long
) : FileSource {
    private val extents = listOf(offset to size)
    private val path = imagePath
    override fun openStream(): InputStream = ExtentInputStream(path, extents)
}

/**
 * File bytes assembled from one or more non-contiguous byte-range extents of an existing
 * .img file on disk. Used when reading back a file whose cluster allocation is fragmented
 * (a real FAT chain rather than a single contiguous run) -- this writer never fragments its
 * own output, but "Open .img" may be pointed at an image built by another tool.
 */
class ExtentFileSource(
    private val imagePath: String,
    private val extents: List<Pair<Long, Long>>, // (offset, length) pairs, in order
    override val size: Long
) : FileSource {
    override fun openStream(): InputStream = ExtentInputStream(imagePath, extents)
}

/** Concatenates a sequence of byte-range extents from one file into a single InputStream. */
private class ExtentInputStream(
    private val path: String,
    private val extents: List<Pair<Long, Long>>
) : InputStream() {
    private val raf = RandomAccessFile(path, "r")
    private var extentIndex = 0
    private var remainingInExtent = 0L

    init {
        positionAtExtent(0)
    }

    private fun positionAtExtent(index: Int) {
        if (index < extents.size) {
            val (offset, length) = extents[index]
            raf.seek(offset)
            remainingInExtent = length
        } else {
            remainingInExtent = 0
        }
    }

    private fun advanceIfNeeded() {
        while (remainingInExtent <= 0 && extentIndex < extents.size - 1) {
            extentIndex++
            positionAtExtent(extentIndex)
        }
    }

    override fun read(): Int {
        advanceIfNeeded()
        if (remainingInExtent <= 0) return -1
        val b = raf.read()
        if (b >= 0) remainingInExtent--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        advanceIfNeeded()
        if (remainingInExtent <= 0) return -1
        val toRead = minOf(len.toLong(), remainingInExtent).toInt()
        val n = raf.read(b, off, toRead)
        if (n > 0) remainingInExtent -= n
        return n
    }

    override fun close() {
        raf.close()
    }
}
