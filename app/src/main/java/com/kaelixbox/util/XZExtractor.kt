package com.kaelixbox.util

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * 流式解压 + 提取 `.tar.xz`（以及纯 `.tar`）。
 *
 * 用于默认在线镜像 debian-xfce.tar.xz 的解压，以及本地导入的 tar.xz 镜像。
 * 流式逐块写入磁盘，不将整个压缩包载入内存。
 * ENOSPC 等 IO 异常返回 DiskFull 而非 Corrupt，避免日志刷屏。
 */
object XZExtractor {

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

        // 检测 xz 魔数 FD 37 7A 58 5A 00，存在则用 XZInputStream，否则按纯 tar 处理。
        val pushedBack = PushbackStream(raw, 8)
        val head = ByteArray(6)
        val n = pushedBack.read(head)
        if (n > 0) pushedBack.unread(head, 0, n)

        val tarStream: TarArchiveInputStream = try {
            val maybeXz: InputStream = if (n >= 6 &&
                (head[0].toInt() and 0xFF) == 0xFD &&
                (head[1].toInt() and 0xFF) == 0x37 &&
                (head[2].toInt() and 0xFF) == 0x7A &&
                (head[3].toInt() and 0xFF) == 0x58 &&
                (head[4].toInt() and 0xFF) == 0x5A &&
                (head[5].toInt() and 0xFF) == 0x00
            ) {
                XZInputStream(pushedBack)
            } else {
                pushedBack
            }
            TarArchiveInputStream(maybeXz, BUF, "UTF-8")
        } catch (e: Exception) {
            return Result.Corrupt("invalid archive header: ${e.message ?: "unknown"}")
        }

        val buf = ByteArray(BUF)
        return try {
            var entry: TarArchiveEntry? = tarStream.nextTarEntry
            while (entry != null) {
                val name = entry.name
                if (name.contains("..") || name.startsWith("/")) {
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
                    if (entry.isSymbolicLink || entry.isLink) {
                        try {
                            OsSymlink.link(target, entry.linkName)
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
            // ENOSPC during write → DiskFull, not Corrupt; prevents log spam.
            val msg = e.message ?: ""
            if (msg.contains("ENOSPC", ignoreCase = true) ||
                msg.contains("No space left", ignoreCase = true)
            ) {
                Result.DiskFull(
                    DiskSpace.estimateDecompressedRequired(archive.length()),
                    DiskSpace.availableBytes(destDir)
                )
            } else {
                Result.Corrupt("io error: $msg")
            }
        } catch (e: Throwable) {
            Result.Failed("unexpected: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            try { tarStream.close() } catch (_: Throwable) {}
        }
    }

    private class PushbackStream(src: InputStream, size: Int) :
        java.io.PushbackInputStream(src, size)

    /** 符号链接辅助：不可用时回退为空文件，不中断解压。 */
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
            target.createNewFile()
        }
    }
}
