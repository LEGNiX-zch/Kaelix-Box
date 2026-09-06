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
 * 流式解压 + 提取 `.tar.zst`（以及纯 `.tar`）。
 *
 * 仅用于用户【本地导入】的 tar.zst 镜像。默认在线镜像为 tar.xz，走 XZExtractor。
 * 流式逐块写入磁盘，不将整个压缩包载入内存。
 * ENOSPC 等 IO 异常返回 DiskFull 而非 Corrupt，避免日志刷屏。
 */
object ZstdExtractor {

    sealed class Result {
        object Ok : Result()
        data class DiskFull(val required: Long, val available: Long) : Result()
        data class Corrupt(val reason: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    private const val BUF = 64 * 1024

    /**
     * 每处理 N 个 tar 条目后短暂让出 CPU/IO，避免解压大量小文件（如图标）时
     * 占满整机资源导致 Android 系统 UI 无响应。3ms ≈ 让出约 1 个时间片。
     */
    private const val THROTTLE_EVERY = 50
    private const val THROTTLE_MS = 3L

    fun extract(
        archive: File,
        destDir: File,
        onEntry: ((String, Long) -> Unit)? = null,
        onProgress: ((processed: Long, total: Long) -> Unit)? = null
    ): Result {
        if (!archive.exists()) return Result.Failed("archive missing: ${archive.absolutePath}")
        destDir.mkdirs()

        val total = archive.length()
        val required = DiskSpace.estimateDecompressedRequired(total)
        if (!DiskSpace.hasEnough(destDir, required)) {
            return Result.DiskFull(required, DiskSpace.availableBytes(destDir))
        }

        val counter = CountingInputStream { read -> onProgress?.invoke(read, total) }
        val raw: InputStream = try {
            BufferedInputStream(counter.wrap(FileInputStream(archive)), BUF)
        } catch (e: IOException) {
            return Result.Failed("open archive: ${e.message ?: "io error"}")
        }

        // 检测 zstd 魔数 28 b5 2f fd，存在则用 ZstdInputStream，否则按纯 tar 处理。
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
            var entryCount = 0
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
                // 分片节流：每处理一定数量的条目后短暂休眠，
                // 避免解压大量小文件时占满 CPU/IO 导致整机卡死。
                entryCount++
                if (entryCount % THROTTLE_EVERY == 0) {
                    Thread.sleep(THROTTLE_MS)
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

    /** 包装输入流，累计读取字节数并回调。用于解压进度估算（按压缩包已读比例）。 */
    private class CountingInputStream(private val onRead: (Long) -> Unit) {
        private var read = 0L
        fun wrap(src: InputStream): InputStream = object : InputStream() {
            override fun read(): Int {
                val b = src.read()
                if (b >= 0) { read++; onRead(read) }
                return b
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = src.read(b, off, len)
                if (n > 0) { read += n; onRead(read) }
                return n
            }
            override fun available() = src.available()
            override fun close() = src.close()
        }
    }

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
