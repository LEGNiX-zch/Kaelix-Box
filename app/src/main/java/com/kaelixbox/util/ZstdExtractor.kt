package com.kaelixbox.util

import com.github.luben.zstd.ZstdInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Streaming decompression + extraction of `.tar.zst` (and plain `.tar`).
 *
 * Hard requirement: never load the whole compressed file into memory — we
 * stream chunk-by-chunk through zstd-jni's [ZstdInputStream] straight into
 * commons-compress's [TarArchiveInputStream], writing each entry to disk.
 * OOM / corruption exceptions are caught and surfaced to the caller; the
 * caller must report them on the terminal instead of crashing the app.
 */
object ZstdExtractor {

    sealed class Result {
        object Ok : Result()
        data class DiskFull(val required: Long, val available: Long) : Result()
        data class Corrupt(val reason: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    private const val BUF = 64 * 1024

    fun extract(archive: File, destDir: File, onEntry: ((String, Long) -> Unit)? = null): Result {
        if (!archive.exists()) return Result.Failed("archive missing: ${archive.absolutePath}")
        destDir.mkdirs()

        val required = DiskSpace.estimateDecompressedRequired(archive.length())
        if (!DiskSpace.hasEnough(destDir, required)) {
            return Result.DiskFull(required, DiskSpace.availableBytes(destDir))
        }

        val raw: InputStream = try {
            BufferedInputStream(FileInputStream(archive), BUF)
        } catch (e: IOException) {
            return Result.Failed("open archive: ${e.message ?: "io error"}")
        }

        // Detect zstd magic 28 b5 2f fd. If present, wrap; else treat as plain tar.
        val pushedBack = PushbackStream(raw, 8)
        val head = ByteArray(4)
        val n = pushedBack.read(head)
        if (n > 0) pushedBack.unread(head, 0, n)

        val tarStream: TarArchiveInputStream = try {
            val maybeZstd: InputStream = if (n >= 4 &&
                (head[0].toInt() and 0xFF) == 0x28 &&
                (head[1].toInt() and 0xFF) == 0xB5 &&
                (head[2].toInt() and 0xFF) == 0x2F &&
                (head[3].toInt() and 0xFF) == 0xFD
            ) {
                ZstdInputStream(pushedBack)
            } else {
                pushedBack
            }
            TarArchiveInputStream(maybeZstd, BUF, "UTF-8")
        } catch (e: Exception) {
            return Result.Corrupt("invalid archive header: ${e.message ?: "unknown"}")
        }

        val buf = ByteArray(BUF)
        return try {
            var entry: TarArchiveEntry? = tarStream.nextTarEntry
            while (entry != null) {
                val name = entry.name
                if (name.contains("..") || name.startsWith("/")) {
                    // skip path-traversal / absolute entries for safety
                    entry = tarStream.nextTarEntry
                    continue
                }
                val target = File(destDir, name).normalize()
                val base = destDir.canonicalFile
                if (!target.canonicalPath.startsWith(base.canonicalPath + File.separator) &&
                    target.canonicalPath != base.canonicalPath
                ) {
                    entry = tarStream.nextTarEntry
                    continue
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    // Devices / symlinks: skip content; on Android fs symlinks are
                    // supported, but we only persist regular files here.
                    if (entry.isSymbolicLink || entry.isLink) {
                        try {
                            val os = OsSymlink.link(target, entry.linkName)
                            // no content body for links
                        } catch (_: Throwable) { /* ignore link failures */ }
                    } else {
                        target.outputStream().use { out ->
                            while (true) {
                                val r = tarStream.read(buf)
                                if (r <= 0) break
                                out.write(buf, 0, r)
                            }
                        }
                        if (entry.mode and 0b001_000_000 != 0) {
                            target.setExecutable(true, false)
                        }
                    }
                    onEntry?.invoke(name, entry.size)
                }
                entry = tarStream.nextTarEntry
            }
            Result.Ok
        } catch (e: OutOfMemoryError) {
            Result.Corrupt("out-of-memory during decompression: ${e.message ?: "oom"}")
        } catch (e: org.apache.commons.compress.archivers.ArchiveException) {
            Result.Corrupt("archive corrupted: ${e.message ?: "unknown"}")
        } catch (e: IOException) {
            Result.Corrupt("io error: ${e.message ?: "unknown"}")
        } catch (e: Throwable) {
            Result.Failed("unexpected: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            try { tarStream.close() } catch (_: Throwable) {}
        }
    }

    private class PushbackStream(src: InputStream, size: Int) :
        java.io.PushbackInputStream(src, size)

    /** Minimal symlink helper — falls back to creating an empty file when link() unavailable. */
    private object OsSymlink {
        fun link(target: File, linkName: String) {
            try {
                val path = java.nio.file.Paths.get(target.absolutePath)
                try {
                    java.nio.file.Files.createSymbolicLink(path,
                        java.nio.file.Paths.get(linkName))
                    return
                } catch (_: java.nio.file.FileSystemException) {
                    // fall through
                }
            } catch (_: Throwable) { }
            // Best-effort fallback: copy linkName as a plain file is wrong; instead
            // create an empty regular file so tar extraction can proceed without
            // aborting the whole rootfs.
            target.createNewFile()
        }
    }
}
