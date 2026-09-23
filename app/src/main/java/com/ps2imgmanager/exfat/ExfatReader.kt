package com.ps2imgmanager.exfat

import com.ps2imgmanager.image.ExtentFileSource
import com.ps2imgmanager.image.ImageEntry
import com.ps2imgmanager.image.ImageRegionSource
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * Parses an MBR + exFAT image (as produced by [ExfatWriter], or any other standard-conforming
 * single-partition exFAT drive) back into an [ImageEntry] tree so it can be edited and re-saved.
 *
 * Follows real FAT chains where present (needed for the root directory, and for any file a
 * *different* tool may have written fragmented) rather than assuming everything is contiguous,
 * so this isn't limited to images this app itself produced.
 */
class ExfatReader(private val imagePath: String) {

    companion object {
        private const val SECTOR = ExfatWriter.SECTOR
        private const val MAX_CHAIN_LENGTH = 50_000_000 // sanity cap against a corrupt/looping FAT
    }

    private fun getU16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun getU32(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = v or ((b[off + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    private fun getU64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((b[off + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    fun read(): ImageEntry {
        RandomAccessFile(imagePath, "r").use { raf ->
            val mbr = ByteArray(SECTOR)
            raf.seek(0); raf.readFully(mbr)
            if (getU16(mbr, 510) != 0xAA55) throw IOException("Not a recognized .img (missing MBR signature)")
            val partOff = 446
            val partType = mbr[partOff + 4].toInt() and 0xFF
            if (partType != 0x07) throw IOException("Partition type is 0x%02x, expected 0x07 (exFAT)".format(partType))
            val partLba = getU32(mbr, partOff + 8)
            val base = partLba * SECTOR

            val boot = ByteArray(SECTOR)
            raf.seek(base); raf.readFully(boot)
            val sig = String(boot, 3, 8, StandardCharsets.US_ASCII)
            if (sig != "EXFAT   ") throw IOException("Not an exFAT partition (bad filesystem signature)")

            val fatOffset = getU32(boot, 80)
            val fatLength = getU32(boot, 84)
            val heapOffset = getU32(boot, 88)
            val rootCluster = getU32(boot, 96)
            val bpsShift = boot[108].toInt() and 0xFF
            val spcShift = boot[109].toInt() and 0xFF
            val bytesPerSector = 1L shl bpsShift
            val sectorsPerCluster = 1L shl spcShift
            val clusterSize = bytesPerSector * sectorsPerCluster

            val fat = ByteArray((fatLength * SECTOR).toInt())
            raf.seek(base + fatOffset * SECTOR); raf.readFully(fat)
            fun fatEntry(c: Long): Long = getU32(fat, (c * 4).toInt())

            val heapBase = base + heapOffset * SECTOR

            fun followChain(firstCluster: Long): List<Long> {
                val chain = mutableListOf<Long>()
                val seen = HashSet<Long>()
                var c = firstCluster
                while (true) {
                    if (!seen.add(c)) throw IOException("FAT chain loop detected at cluster $c")
                    chain.add(c)
                    if (chain.size > MAX_CHAIN_LENGTH) throw IOException("FAT chain implausibly long (corrupt image?)")
                    val v = fatEntry(c)
                    if (v == 0xFFFFFFFFL) break
                    if (v == 0L || v == 0xFFFFFFF7L) throw IOException("Unexpected FAT terminator at cluster $c")
                    c = v
                }
                return chain
            }

            /** Compresses a cluster-index chain into (offset, length) byte extents, merging
             *  consecutive clusters, and trims the final extent to [dataLen] bytes total. */
            fun chainToExtents(chain: List<Long>, dataLen: Long): List<Pair<Long, Long>> {
                val extents = mutableListOf<Pair<Long, Long>>()
                var runStart = chain[0]
                var runLen = 1L
                for (i in 1 until chain.size) {
                    if (chain[i] == chain[i - 1] + 1) {
                        runLen++
                    } else {
                        extents.add((heapBase + (runStart - 2) * clusterSize) to (runLen * clusterSize))
                        runStart = chain[i]; runLen = 1
                    }
                }
                extents.add((heapBase + (runStart - 2) * clusterSize) to (runLen * clusterSize))
                var remaining = dataLen
                val trimmed = mutableListOf<Pair<Long, Long>>()
                for ((off, len) in extents) {
                    if (remaining <= 0) break
                    val take = minOf(len, remaining)
                    trimmed.add(off to take)
                    remaining -= take
                }
                return trimmed
            }

            fun readClusterRun(firstCluster: Long, nClusters: Long): ByteArray {
                val buf = ByteArray((nClusters * clusterSize).toInt())
                raf.seek(heapBase + (firstCluster - 2) * clusterSize)
                raf.readFully(buf)
                return buf
            }

            fun clustersFor(dataLen: Long): Long =
                if (dataLen > 0) (dataLen + clusterSize - 1) / clusterSize else 0

            fun sourceFor(firstCluster: Long, dataLen: Long, noFatChain: Boolean): com.ps2imgmanager.image.FileSource {
                if (dataLen <= 0L) {
                    return ImageRegionSource(imagePath, heapBase, 0L)
                }
                return if (noFatChain) {
                    ImageRegionSource(imagePath, heapBase + (firstCluster - 2) * clusterSize, dataLen)
                } else {
                    val chain = followChain(firstCluster)
                    val extents = chainToExtents(chain, dataLen)
                    ExtentFileSource(imagePath, extents, dataLen)
                }
            }

            fun parseEntrySetsInto(data: ByteArray, parent: ImageEntry) {
                var pos = 0
                while (pos + 32 <= data.size) {
                    val etype = data[pos].toInt() and 0xFF
                    if (etype == 0x00) break
                    if (etype == 0x85) {
                        val secondaryCount = data[pos + 1].toInt() and 0xFF
                        val setLen = 32 * (1 + secondaryCount)
                        if (pos + setLen > data.size) break
                        val attrs = getU16(data, pos + 4)
                        val isDir = (attrs and 0x10) != 0
                        val streamOff = pos + 32
                        val gflags = data[streamOff + 1].toInt() and 0xFF
                        val noFatChain = (gflags and 0x02) != 0
                        val nameLen = data[streamOff + 3].toInt() and 0xFF
                        val firstCluster = getU32(data, streamOff + 20)
                        val dataLen = getU64(data, streamOff + 24)
                        val nameBytes = ByteArray(nameLen * 2)
                        var copied = 0
                        for (k in 0 until secondaryCount - 1) {
                            val neOff = pos + 32 * (2 + k)
                            val chunkLen = minOf(30, nameBytes.size - copied)
                            if (chunkLen > 0) {
                                System.arraycopy(data, neOff + 2, nameBytes, copied, chunkLen)
                                copied += chunkLen
                            }
                        }
                        val name = String(nameBytes, StandardCharsets.UTF_16LE)

                        if (isDir) {
                            val dirEntry = ImageEntry.newDir(name)
                            parent.addChild(dirEntry)
                            val nClusters = clustersFor(dataLen)
                            if (nClusters > 0) {
                                val subData = if (noFatChain) {
                                    readClusterRun(firstCluster, nClusters)
                                } else {
                                    val chain = followChain(firstCluster)
                                    val buf = ByteArray((chain.size * clusterSize).toInt())
                                    var bpos = 0
                                    for (cl in chain) {
                                        raf.seek(heapBase + (cl - 2) * clusterSize)
                                        raf.readFully(buf, bpos, clusterSize.toInt())
                                        bpos += clusterSize.toInt()
                                    }
                                    buf
                                }
                                parseEntrySetsInto(subData, dirEntry)
                            }
                        } else {
                            val src = sourceFor(firstCluster, dataLen, noFatChain)
                            parent.addChild(ImageEntry.newFile(name, src))
                        }
                        pos += setLen
                    } else {
                        pos += 32
                    }
                }
            }

            val root = ImageEntry.newRoot()
            val rootChain = followChain(rootCluster)
            val rootData = ByteArray((rootChain.size * clusterSize).toInt())
            var bpos = 0
            for (cl in rootChain) {
                raf.seek(heapBase + (cl - 2) * clusterSize)
                raf.readFully(rootData, bpos, clusterSize.toInt())
                bpos += clusterSize.toInt()
            }
            parseEntrySetsInto(rootData, root)
            return root
        }
    }
}
