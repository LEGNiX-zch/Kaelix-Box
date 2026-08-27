package com.kaelixbox.util

import android.os.StatFs
import java.io.File

/**
 * Disk-space guard used before any (potentially large) decompression.
 * Returns false and surfaces a human reason when the target volume has less
 * free space than the requested threshold; the caller must abort & warn rather
 * than silently proceeding (prevents OOM-during-decompress + ENOSPC crashes).
 */
object DiskSpace {

    fun availableBytes(path: File): Long = try {
        StatFs(path.absolutePath).availableBytes
    } catch (e: Exception) {
        Long.MAX_VALUE
    }

    fun hasEnough(path: File, requiredBytes: Long): Boolean = availableBytes(path) >= requiredBytes

    /** Heuristic lower bound: compressed size * 4 (zstd + tar doubles up). */
    fun estimateDecompressedRequired(compressedSize: Long): Long {
        val est = (compressedSize * 4)
        // never estimate less than 200 MiB free as a safety floor.
        return maxOf(est, 200L * 1024 * 1024)
    }
}
