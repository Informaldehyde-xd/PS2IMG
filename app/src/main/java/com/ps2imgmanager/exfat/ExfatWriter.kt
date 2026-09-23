package com.ps2imgmanager.exfat

import com.ps2imgmanager.image.ImageEntry
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque

/**
 * Builds a single-partition exFAT filesystem image, wrapped in an MBR, from an [ImageEntry]
 * tree. This is the format Neutrino's `-bsd=udpbd` backing store expects: a browsable
 * multi-file "virtual USB drive" (as opposed to OPL's native UDPBD mode, which streams one
 * raw ISO9660 disc image directly).
 *
 * Every directory and file is allocated as one contiguous run of clusters and marked
 * "NoFatChain" in its Stream Extension entry, EXCEPT the root directory, which has no
 * Stream Extension anywhere (it's referenced only via the boot sector's
 * FirstClusterOfRootDirectory field) and so must use a real, walkable FAT chain -- as must
 * the allocation bitmap and up-case table, which are likewise not File entries.
 *
 * The on-disk layout and every checksum/hash algorithm here were validated against an
 * independent from-scratch parser (byte-for-byte, including boot checksum, entry-set
 * checksum, name hash, and up-case table checksum) before being ported from a Python
 * prototype -- see the project's dev notes. This has NOT been verified by mounting on a
 * real OS or PS2, since neither is available in the build/dev environment.
 */
class ExfatWriter(private val root: ImageEntry, private val volumeLabel: String = "") {

    companion object {
        const val SECTOR = 512
        const val SECTORS_PER_CLUSTER = 256 // 128 KiB clusters
        const val CLUSTER = SECTOR * SECTORS_PER_CLUSTER
        const val PARTITION_START_SECTOR = 2048L // 1 MiB alignment
    }

    // ---- per-entry layout bookkeeping (kept out of ImageEntry itself) ----
    private class Layout(var firstCluster: Long = 0, var nClusters: Long = 0, var sizeBytes: Long = 0)

    private val layout = java.util.IdentityHashMap<ImageEntry, Layout>()
    private fun L(e: ImageEntry): Layout = layout.getOrPut(e) { Layout() }

    private fun sortedChildren(e: ImageEntry): List<ImageEntry> =
        e.children.sortedBy { it.name.uppercase() }

    // ================= name handling =================

    /** exFAT allows long Unicode names; just strip the handful of reserved characters. */
    private fun sanitizeName(raw: String, isDir: Boolean): String {
        var n = raw.replace(Regex("[\"*/:<>?\\\\|]"), "_").trim()
        if (n.isEmpty()) n = if (isDir) "FOLDER" else "FILE"
        if (n.length > 255) n = n.substring(0, 255)
        return n
    }

    private fun upcaseChar(c: Char): Char =
        if (c in 'a'..'z') (c.code - 0x20).toChar() else c

    private fun upcaseString(s: String): String = s.map { upcaseChar(it) }.joinToString("")

    // ================= checksums (see prototype for the exact algorithms) =================

    private fun bootChecksum(data: ByteArray): Long {
        var chk = 0L
        for (i in data.indices) {
            if (i == 106 || i == 107 || i == 112) continue
            chk = ((chk shl 31) or (chk ushr 1)) and 0xFFFFFFFFL
            chk = (chk + (data[i].toInt() and 0xFF)) and 0xFFFFFFFFL
        }
        return chk
    }

    private fun tableChecksum32(data: ByteArray): Long {
        var chk = 0L
        for (b in data) {
            chk = ((chk shl 31) or (chk ushr 1)) and 0xFFFFFFFFL
            chk = (chk + (b.toInt() and 0xFF)) and 0xFFFFFFFFL
        }
        return chk
    }

    private fun entrySetChecksum(data: ByteArray): Int {
        var chk = 0
        for (i in data.indices) {
            if (i == 2 || i == 3) continue
            chk = ((chk shl 15) or (chk ushr 1)) and 0xFFFF
            chk = (chk + (data[i].toInt() and 0xFF)) and 0xFFFF
        }
        return chk
    }

    private fun nameHash(upperUtf16le: ByteArray): Int {
        var chk = 0
        for (b in upperUtf16le) {
            chk = ((chk shl 15) or (chk ushr 1)) and 0xFFFF
            chk = (chk + (b.toInt() and 0xFF)) and 0xFFFF
        }
        return chk
    }

    // ================= little-endian byte helpers =================

    private fun putU16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte(); b[off + 1] = ((v shr 8) and 0xFF).toByte()
    }
    private fun putU32(b: ByteArray, off: Int, v: Long) {
        b[off] = (v and 0xFF).toByte(); b[off + 1] = ((v shr 8) and 0xFF).toByte()
        b[off + 2] = ((v shr 16) and 0xFF).toByte(); b[off + 3] = ((v shr 24) and 0xFF).toByte()
    }
    private fun putU64(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) b[off + i] = ((v shr (8 * i)) and 0xFF).toByte()
    }
    private fun getU32(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = v or ((b[off + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    // ================= up-case table =================

    /** Simple ASCII-only up-case table (full 65536-entry uncompressed form; compression is
     *  optional per spec). Non-ASCII characters map to themselves. */
    private fun buildUpcaseTable(): ByteArray {
        val tbl = ByteArray(65536 * 2)
        for (c in 0 until 65536) {
            val u = if (c in 0x61..0x7A) c - 0x20 else c
            putU16(tbl, c * 2, u)
        }
        return tbl
    }

    // ================= directory entry builders =================

    private fun buildFileEntrySet(entry: ImageEntry, name: String, firstCluster: Long, size: Long): ByteArray {
        val nameU16 = name.toByteArray(StandardCharsets.UTF_16LE)
        val nameLen = name.length
        val nNameEntries = maxOf(1, (nameLen + 14) / 15)
        val secondaryCount = 1 + nNameEntries

        val primary = ByteArray(32)
        primary[0] = 0x85.toByte()
        primary[1] = secondaryCount.toByte()
        val attrs = if (entry.isDirectory) 0x10 else 0x20
        putU16(primary, 4, attrs)

        val stream = ByteArray(32)
        stream[0] = 0xC0.toByte()
        val hasClusters = firstCluster > 0
        var gflags = 0
        if (hasClusters) gflags = gflags or 0x01
        if (hasClusters) gflags = gflags or 0x02 // NoFatChain: contiguous allocation
        stream[1] = gflags.toByte()
        stream[3] = nameLen.toByte()
        val upName = upcaseString(name)
        val upBytes = upName.toByteArray(StandardCharsets.UTF_16LE)
        putU16(stream, 4, nameHash(upBytes))
        putU64(stream, 8, size)
        putU32(stream, 20, if (hasClusters) firstCluster else 0)
        putU64(stream, 24, size)

        val nameEntries = ByteArray(nNameEntries * 32)
        for (i in 0 until nNameEntries) {
            val off = i * 32
            nameEntries[off] = 0xC1.toByte()
            val start = i * 30
            val end = minOf(start + 30, nameU16.size)
            System.arraycopy(nameU16, start, nameEntries, off + 2, end - start)
        }

        val whole = primary + stream + nameEntries
        val cksum = entrySetChecksum(whole)
        putU16(whole, 2, cksum)
        return whole
    }

    private fun buildBitmapEntry(firstCluster: Long, dataLen: Long): ByteArray {
        val e = ByteArray(32)
        e[0] = 0x81.toByte()
        putU32(e, 20, firstCluster)
        putU64(e, 24, dataLen)
        return e
    }

    private fun buildUpcaseEntry(firstCluster: Long, dataLen: Long, checksum: Long): ByteArray {
        val e = ByteArray(32)
        e[0] = 0x82.toByte()
        putU32(e, 4, checksum)
        putU32(e, 20, firstCluster)
        putU64(e, 24, dataLen)
        return e
    }

    /** Volume Label directory entry (type 0x83). Only emitted when [volumeLabel] is non-empty —
     *  an absent entry is the valid, spec-defined way to say "no label". Label is capped at
     *  11 UTF-16 characters per spec. */
    private fun buildVolumeLabelEntry(label: String): ByteArray {
        val truncated = label.take(11)
        val e = ByteArray(32)
        e[0] = 0x83.toByte()
        e[1] = truncated.length.toByte()
        val bytes = truncated.toByteArray(Charsets.UTF_16LE)
        System.arraycopy(bytes, 0, e, 2, bytes.size)
        return e
    }

    // ================= main layout + write =================

    /** Everything needed to stream the image out, computed up front (no file bytes held). */
    private class Plan(
        val allDirs: List<ImageEntry>,
        val volumeLengthSectors: Long,
        val fatOffsetSectors: Long,
        val fatLengthSectors: Long,
        val heapOffsetSectors: Long,
        val totalClusters: Long,
        val fat: ByteArray,
        val bootMain: ByteArray,
        val dirBytes: Map<ImageEntry, ByteArray>,
        val bitmapBytes: ByteArray,
        val bitmapClusters: Long,
        val upcaseBytes: ByteArray,
        val upcaseClusters: Long,
        val rootFirstCluster: Long
    )

    private fun plan(): Plan {
        // 1. BFS-order directory list.
        val allDirs = mutableListOf<ImageEntry>()
        val queue = ArrayDeque<ImageEntry>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val d = queue.removeFirst()
            allDirs.add(d)
            for (c in sortedChildren(d)) if (c.isDirectory) queue.add(c)
        }

        val upcaseBytes = buildUpcaseTable()
        val upcaseClusters = ((upcaseBytes.size + CLUSTER - 1) / CLUSTER).toLong()

        fun dirEntriesBytesSized(d: ImageEntry): Int {
            var n = 0
            if (d === root) n += 32 + 32 + (if (volumeLabel.isNotEmpty()) 32 else 0)
            for (c in sortedChildren(d)) {
                val name = sanitizeName(c.name, c.isDirectory)
                val nNameEntries = maxOf(1, (name.length + 14) / 15)
                n += 32 * (1 + nNameEntries)
            }
            return n
        }
        fun dirClustersNeeded(d: ImageEntry): Long =
            maxOf(1L, ((dirEntriesBytesSized(d) + CLUSTER - 1) / CLUSTER).toLong())

        val allFiles = mutableListOf<ImageEntry>()
        for (d in allDirs) for (c in sortedChildren(d)) if (!c.isDirectory) allFiles.add(c)

        val baseClusters = upcaseClusters +
            allDirs.sumOf { dirClustersNeeded(it) } +
            allFiles.sumOf { f -> ((f.source?.size ?: 0L) + CLUSTER - 1) / CLUSTER }

        var bitmapClusters = 1L
        for (iter in 0 until 5) {
            val totalGuess = baseClusters + bitmapClusters
            val neededBitmapBytes = (totalGuess + 7) / 8
            val newBitmapClusters = maxOf(1L, (neededBitmapBytes + CLUSTER - 1) / CLUSTER)
            if (newBitmapClusters == bitmapClusters) break
            bitmapClusters = newBitmapClusters
        }

        // 2. Assign first-cluster indices: bitmap, upcase, dirs (BFS), files (per-dir order).
        var cursor = 2L
        val bitmapFirstCluster = cursor; cursor += bitmapClusters
        val upcaseFirstCluster = cursor; cursor += upcaseClusters

        for (d in allDirs) {
            val n = dirClustersNeeded(d)
            L(d).firstCluster = cursor
            L(d).nClusters = n
            L(d).sizeBytes = n * CLUSTER
            cursor += n
        }
        for (d in allDirs) {
            for (c in sortedChildren(d)) {
                if (!c.isDirectory) {
                    val sz = c.source?.size ?: 0L
                    val n = if (sz > 0) (sz + CLUSTER - 1) / CLUSTER else 0L
                    L(c).firstCluster = if (n > 0) cursor else 0
                    L(c).nClusters = n
                    L(c).sizeBytes = sz
                    cursor += n
                }
            }
        }

        val totalClusters = cursor - 2
        val bitmapBytesLen = (totalClusters + 7) / 8

        // 3. Allocation bitmap.
        val bitmap = ByteArray((((bitmapBytesLen + CLUSTER - 1) / CLUSTER) * CLUSTER).toInt())
        fun markUsed(firstCluster: Long, n: Long) {
            for (i in 0 until n) {
                val bit = (firstCluster + i - 2).toInt()
                bitmap[bit / 8] = (bitmap[bit / 8].toInt() or (1 shl (bit % 8))).toByte()
            }
        }
        markUsed(bitmapFirstCluster, bitmapClusters)
        markUsed(upcaseFirstCluster, upcaseClusters)
        for (d in allDirs) markUsed(L(d).firstCluster, L(d).nClusters)
        for (d in allDirs) for (c in sortedChildren(d)) if (!c.isDirectory && L(c).nClusters > 0) markUsed(L(c).firstCluster, L(c).nClusters)

        // 4. FAT: real chains only for bitmap, up-case table, and the root directory.
        val fatEntries = totalClusters + 2
        val fat = ByteArray((fatEntries * 4).toInt())
        putU32(fat, 0, 0xFFFFFFF8L)
        putU32(fat, 4, 0xFFFFFFFFL)
        fun writeChain(firstCluster: Long, n: Long) {
            for (i in 0 until n) {
                val c = firstCluster + i
                val v = if (i == n - 1) 0xFFFFFFFFL else (c + 1)
                putU32(fat, (c * 4).toInt(), v)
            }
        }
        writeChain(bitmapFirstCluster, bitmapClusters)
        writeChain(upcaseFirstCluster, upcaseClusters)
        writeChain(L(root).firstCluster, L(root).nClusters)

        // 5. Final directory-entry bytes, now that every first_cluster is known.
        val upcaseChecksum = tableChecksum32(upcaseBytes)
        val dirBytesMap = HashMap<ImageEntry, ByteArray>()
        for (d in allDirs) {
            val parts = mutableListOf<ByteArray>()
            if (d === root) {
                if (volumeLabel.isNotEmpty()) parts.add(buildVolumeLabelEntry(volumeLabel))
                parts.add(buildBitmapEntry(bitmapFirstCluster, bitmapBytesLen))
                parts.add(buildUpcaseEntry(upcaseFirstCluster, upcaseBytes.size.toLong(), upcaseChecksum))
            }
            for (c in sortedChildren(d)) {
                val name = sanitizeName(c.name, c.isDirectory)
                parts.add(buildFileEntrySet(c, name, L(c).firstCluster, L(c).sizeBytes))
            }
            val padTo = (L(d).nClusters * CLUSTER).toInt()
            val out = ByteArray(padTo)
            var pos = 0
            for (p in parts) { System.arraycopy(p, 0, out, pos, p.size); pos += p.size }
            dirBytesMap[d] = out
        }

        // 6. Region layout (partition-relative sectors).
        val fatOffsetSectors = 24L
        val fatLengthSectors = ((fat.size + SECTOR - 1) / SECTOR).toLong()
        val heapOffsetSectors = fatOffsetSectors + fatLengthSectors
        val volumeLengthSectors = heapOffsetSectors + totalClusters * SECTORS_PER_CLUSTER

        // 7. Boot sector + extended boot sectors + OEM/reserved + checksum, main and backup.
        val boot = ByteArray(SECTOR)
        boot[0] = 0xEB.toByte(); boot[1] = 0x76; boot[2] = 0x90.toByte()
        "EXFAT   ".toByteArray(StandardCharsets.US_ASCII).copyInto(boot, 3)
        putU64(boot, 64, PARTITION_START_SECTOR)
        putU64(boot, 72, volumeLengthSectors)
        putU32(boot, 80, fatOffsetSectors)
        putU32(boot, 84, fatLengthSectors)
        putU32(boot, 88, heapOffsetSectors)
        putU32(boot, 92, totalClusters)
        putU32(boot, 96, L(root).firstCluster)
        putU32(boot, 100, 0x12345678L) // volume serial
        putU16(boot, 104, 0x0100) // FS revision 1.00
        putU16(boot, 106, 0x0000) // volume flags
        boot[108] = 9 // bytes/sector shift -> 512
        boot[109] = 8 // sectors/cluster shift -> 256 (128 KiB)
        boot[110] = 1 // number of FATs
        boot[111] = 0x80.toByte()
        boot[112] = 0xFF.toByte() // percent in use: unknown
        putU16(boot, 510, 0xAA55)

        val extBoot = ByteArray(SECTOR)
        putU32(extBoot, 508, 0xAA550000L)

        val oemParam = ByteArray(SECTOR) // all-zero: "no OEM parameters"
        val reserved = ByteArray(SECTOR)

        val first11 = ByteArray(SECTOR * 11)
        boot.copyInto(first11, 0)
        for (i in 0 until 8) extBoot.copyInto(first11, SECTOR * (1 + i))
        oemParam.copyInto(first11, SECTOR * 9)
        reserved.copyInto(first11, SECTOR * 10)

        val cksum = bootChecksum(first11)
        val checksumSector = ByteArray(SECTOR)
        var off = 0
        while (off < SECTOR) { putU32(checksumSector, off, cksum); off += 4 }

        val bootMain = ByteArray(SECTOR * 12)
        first11.copyInto(bootMain, 0)
        checksumSector.copyInto(bootMain, SECTOR * 11)

        return Plan(
            allDirs, volumeLengthSectors, fatOffsetSectors, fatLengthSectors, heapOffsetSectors,
            totalClusters, fat, bootMain, dirBytesMap, bitmap, bitmapClusters, upcaseBytes,
            upcaseClusters, L(root).firstCluster
        )
    }

    /** Streams the finished image to [out]. Safe to call with a large tree: only directory
     *  metadata is held in memory: file bytes are streamed straight from each entry's
     *  [com.ps2imgmanager.image.FileSource]. */
    fun write(out: OutputStream, onProgress: ((Long, Long) -> Unit)? = null) {
        val p = plan()

        // MBR
        val mbr = ByteArray(SECTOR)
        val po = 446
        mbr[po] = 0x00
        mbr[po + 1] = 0xFE.toByte(); mbr[po + 2] = 0xFF.toByte(); mbr[po + 3] = 0xFF.toByte()
        mbr[po + 4] = 0x07 // exFAT/NTFS partition type
        mbr[po + 5] = 0xFE.toByte(); mbr[po + 6] = 0xFF.toByte(); mbr[po + 7] = 0xFF.toByte()
        putU32(mbr, po + 8, PARTITION_START_SECTOR)
        putU32(mbr, po + 12, p.volumeLengthSectors)
        putU16(mbr, 510, 0xAA55)
        out.write(mbr)

        // gap up to the partition start
        writeZeros(out, (PARTITION_START_SECTOR - 1) * SECTOR)

        // main + backup boot regions
        out.write(p.bootMain)
        out.write(p.bootMain)

        // FAT, padded to its full sector length
        out.write(p.fat)
        writeZeros(out, p.fatLengthSectors * SECTOR - p.fat.size)

        // cluster heap: bitmap, upcase table, directories (BFS order), then file data
        out.write(p.bitmapBytes)
        writeZeros(out, p.bitmapClusters * CLUSTER - p.bitmapBytes.size)

        out.write(p.upcaseBytes)
        writeZeros(out, p.upcaseClusters * CLUSTER - p.upcaseBytes.size)

        for (d in p.allDirs) out.write(p.dirBytes.getValue(d))

        val totalBytes = p.volumeLengthSectors * SECTOR
        var written = SECTOR.toLong() + (PARTITION_START_SECTOR - 1) * SECTOR +
            p.bootMain.size * 2 + p.fatLengthSectors * SECTOR +
            p.bitmapClusters * CLUSTER + p.upcaseClusters * CLUSTER +
            p.allDirs.sumOf { L(it).nClusters * CLUSTER }
        onProgress?.invoke(written, totalBytes)

        for (d in p.allDirs) {
            for (c in sortedChildren(d)) {
                if (!c.isDirectory) {
                    val n = L(c).nClusters
                    if (n > 0) {
                        val sz = c.source!!.size
                        c.source.openStream().use { input ->
                            copyExactly(input, out, sz)
                        }
                        writeZeros(out, n * CLUSTER - sz)
                        written += n * CLUSTER
                        onProgress?.invoke(written, totalBytes)
                    }
                }
            }
        }
    }

    private fun copyExactly(input: java.io.InputStream, out: OutputStream, count: Long) {
        val buf = ByteArray(1 shl 20)
        var remaining = count
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) throw java.io.IOException("Unexpected end of file while copying (${remaining} bytes short)")
            out.write(buf, 0, n)
            remaining -= n
        }
    }

    private fun writeZeros(out: OutputStream, count: Long) {
        if (count <= 0) return
        val buf = ByteArray(minOf(count, (1 shl 20).toLong()).toInt())
        var remaining = count
        while (remaining > 0) {
            val n = minOf(remaining, buf.size.toLong()).toInt()
            out.write(buf, 0, n)
            remaining -= n
        }
    }
}
