package com.kaelixbox.container

import android.content.Context
import com.kaelixbox.util.DiskSpace
import com.kaelixbox.util.FileUtils
import com.kaelixbox.util.ZstdExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads & extracts the prebuilt Debian13 Trixie ARM64 rootfs.
 *
 * Robustness requirements honoured:
 *  - resumable download via HTTP Range (broken download temp is deleted on
 *    failure so we never feed a corrupted prefix to zstd),
 *  - streaming chunked zstd + tar extraction (never loads the whole archive
 *    into memory) via [ZstdExtractor],
 *  - disk-space check BEFORE extraction; we abort & emit a [Result.DiskFull]
 *    so the UI can warn rather than crash,
 *  - all IO happens off the main thread via Dispatchers.IO.
 */
class ImageInstaller(
    private val context: Context,
    private val log: (String, Boolean) -> Unit
) {

    sealed class Result {
        object Ok : Result()
        data class DiskFull(val required: Long, val available: Long) : Result()
        data class Corrupt(val reason: String) : Result()
        data class Network(val reason: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    suspend fun installFromUrl(
        url: String,
        destRootfs: File,
        cache: File,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        cache.parentFile?.mkdirs()
        try {
            val res = downloadResumable(url, cache, onProgress)
            if (res !is Result.Ok) {
                // Delete corrupt/partial temp so we never resume from junk.
                cache.delete()
                return@withContext res
            }
            extractTo(destRootfs, cache)
        } catch (e: Throwable) {
            cache.delete()
            log("镜像下载/解压异常: ${e.message ?: e.javaClass.simpleName}", true)
            Result.Failed(e.message ?: "unknown")
        }
    }

    /** Extract a user-imported local tar / tar.zst into a rootfs dir. */
    suspend fun installFromFile(
        archive: File,
        destRootfs: File
    ): Result = withContext(Dispatchers.IO) {
        extractTo(destRootfs, archive)
    }

    private fun extractTo(destRootfs: File, archive: File): Result {
        destRootfs.mkdirs()
        val required = DiskSpace.estimateDecompressedRequired(archive.length())
        if (!DiskSpace.hasEnough(destRootfs, required)) {
            log("磁盘空间不足，拒绝解压 (需要~${required / 1024 / 1024}MiB)", true)
            return Result.DiskFull(required, DiskSpace.availableBytes(destRootfs))
        }
        log("开始流式解压 ${archive.name} → ${destRootfs.name} …", false)
        var lastEntry = ""
        val r = ZstdExtractor.extract(archive, destRootfs) { name, size ->
            lastEntry = name
        }
        return when (r) {
            ZstdExtractor.Result.Ok -> {
                log("解压完成，最后一项: $lastEntry", false)
                Result.Ok
            }
            is ZstdExtractor.Result.DiskFull -> Result.DiskFull(r.required, r.available)
            is ZstdExtractor.Result.Corrupt -> {
                log("镜像损坏或格式不支持: ${r.reason}", true)
                Result.Corrupt(r.reason)
            }
            is ZstdExtractor.Result.Failed -> Result.Failed(r.reason)
        }
    }

    private fun downloadResumable(
        url: String,
        cache: File,
        onProgress: (Long, Long) -> Unit
    ): Result {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 60_000
                useCaches = false
                requestMethod = "GET"
            }
            val existing = if (cache.exists()) cache.length() else 0L
            if (existing > 0) {
                conn.setRequestProperty("Range", "bytes=$existing-")
            }
            val code = conn.responseCode
            // 416 Range Not Satisfiable → server doesn't support range; restart.
            if (code == 416) {
                cache.delete()
                return downloadResumable(url, cache, onProgress)
            }
            if (code !in 200..299) {
                return Result.Network("HTTP $code")
            }
            val total = (conn.contentLengthLong.takeIf { it > 0 } ?: -1L)
                .let { if (existing > 0 && it > 0) it + existing else it }
            val append = (code == 206 || existing > 0) && existing > 0
            log("下载 ${url.substringAfterLast('/')} (已存在 $existing 字节, 续传=$append)", false)
            FileOutputStream(cache, append).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var downloaded = if (append) existing else 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        onProgress(downloaded, total)
                    }
                }
            }
            return Result.Ok
        } catch (e: IOException) {
            return Result.Network(e.message ?: "io error")
        } catch (e: Throwable) {
            return Result.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }
}
