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
 * 下载并解压预构建 Debian13 Trixie ARM64 rootfs。
 *
 * 流程：
 *  1. 先尝试加速镜像地址，失败回退原始 GitHub Release 直链
 *  2. 下载完成后校验 SHA256，不匹配则拒绝解压
 *  3. 解压前检测 APP 沙盒私有目录可用空间，不足返回 DiskFull
 *  4. 流式 zstd+tar 解压，不将整个压缩包载入内存
 *  5. 压缩包存 APP 私有 cacheDir，解压输出到 filesDir rootfs
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

    /**
     * 从远程 URL 下载并安装镜像。
     * 先尝试 [mirrorUrl]，失败后回退 [fallbackUrl]。
     * 下载完成后若 [expectedSha256] 非空则校验完整性。
     */
    suspend fun installFromUrl(
        mirrorUrl: String,
        fallbackUrl: String,
        expectedSha256: String,
        destRootfs: File,
        cache: File,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        cache.parentFile?.mkdirs()
        FileUtils.cleanDownloadCache(context)
        try {
            // 1. 先尝试加速镜像
            var res = downloadResumable(mirrorUrl, cache, onProgress)
            if (res !is Result.Ok) {
                log("加速镜像下载失败(${(res as? Result.Network)?.reason ?: res.javaClass.simpleName})，回退 GitHub 直连…", false)
                cache.delete()
                res = downloadResumable(fallbackUrl, cache, onProgress)
            }
            if (res !is Result.Ok) {
                cache.delete()
                return@withContext res
            }

            // 2. SHA256 校验
            if (expectedSha256.isNotBlank()) {
                val actual = FileUtils.sha256File(cache)
                if (actual == null || !actual.equals(expectedSha256, ignoreCase = true)) {
                    log("SHA256 校验失败，镜像文件可能已损坏，拒绝解压", true)
                    cache.delete()
                    return@withContext Result.Corrupt("SHA256 mismatch")
                }
                log("SHA256 校验通过", false)
            }

            // 3. 解压
            val extractRes = extractTo(destRootfs, cache)
            cache.delete()
            extractRes
        } catch (e: Throwable) {
            cache.delete()
            log("镜像下载/解压异常: ${e.message ?: e.javaClass.simpleName}", true)
            Result.Failed(e.message ?: "unknown")
        }
    }

    /** 解压用户导入的本地 tar / tar.zst 到 rootfs 目录。 */
    suspend fun installFromFile(
        archive: File,
        destRootfs: File
    ): Result = withContext(Dispatchers.IO) {
        FileUtils.cleanDownloadCache(context)
        val res = extractTo(destRootfs, archive)
        archive.delete()
        res
    }

    private fun extractTo(destRootfs: File, archive: File): Result {
        destRootfs.mkdirs()
        val required = DiskSpace.estimateDecompressedRequired(archive.length())
        val available = DiskSpace.availableBytes(destRootfs)
        if (available < required) {
            log("APP私有存储空间不足：需要~${required / 1024 / 1024}MiB，可用~${available / 1024 / 1024}MiB", true)
            return Result.DiskFull(required, available)
        }
        log("开始流式解压 ${archive.name} → ${destRootfs.name} …", false)
        var lastEntry = ""
        val r = ZstdExtractor.extract(archive, destRootfs) { name, _ ->
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
