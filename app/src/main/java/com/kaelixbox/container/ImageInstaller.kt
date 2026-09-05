package com.kaelixbox.container

import android.content.Context
import com.kaelixbox.util.DiskSpace
import com.kaelixbox.util.FileUtils
import com.kaelixbox.util.XZExtractor
import com.kaelixbox.util.ZstdExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 下载并解压默认 Debian13 XFCE ARM64 rootfs（tar.xz）。
 *
 * 流程：
 *  1. 先尝试加速镜像地址，失败回退原始 GitHub Release 直链
 *  2. 下载完成后校验 SHA256，不匹配则拒绝解压
 *  3. 解压前检测 APP 沙盒私有目录可用空间，不足返回 DiskFull
 *  4. 在线分支固定走 XZ 流式解压；本地导入自动识别 xz/zst 选择对应解压器
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
        onProgress: (downloaded: Long, total: Long) -> Unit,
        onExtractProgress: ((processed: Long, total: Long) -> Unit)? = null,
        onStage: ((String) -> Unit)? = null
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
            onStage?.invoke("verify")
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
            onStage?.invoke("extract")
            val extractRes = extractTo(destRootfs, cache, onExtractProgress)
            cache.delete()
            extractRes
        } catch (e: Throwable) {
            cache.delete()
            log("镜像下载/解压异常: ${e.message ?: e.javaClass.simpleName}", true)
            Result.Failed(e.message ?: "unknown")
        }
    }

    /** 解压用户导入的本地 tar.xz / tar.zst / tar 到 rootfs 目录。 */
    suspend fun installFromFile(
        archive: File,
        destRootfs: File,
        onExtractProgress: ((processed: Long, total: Long) -> Unit)? = null
    ): Result = withContext(Dispatchers.IO) {
        FileUtils.cleanDownloadCache(context)
        val res = extractLocal(destRootfs, archive, onExtractProgress)
        archive.delete()
        res
    }

    /** 在线下载分支：默认镜像为 tar.xz，固定走 XZ 解压。 */
    private fun extractTo(
        destRootfs: File,
        archive: File,
        onExtractProgress: ((processed: Long, total: Long) -> Unit)? = null
    ): Result {
        destRootfs.mkdirs()
        val required = DiskSpace.estimateDecompressedRequired(archive.length())
        val available = DiskSpace.availableBytes(destRootfs)
        if (available < required) {
            log("APP私有存储空间不足：需要~${required / 1024 / 1024}MiB，可用~${available / 1024 / 1024}MiB", true)
            return Result.DiskFull(required, available)
        }
        log("开始流式解压 ${archive.name} → ${destRootfs.name} …", false)
        var lastEntry = ""
        val r = XZExtractor.extract(archive, destRootfs,
            onEntry = { name, _ -> lastEntry = name },
            onProgress = onExtractProgress
        )
        return mapResult(r, lastEntry)
    }

    /**
     * 本地导入分支：自动识别压缩格式。
     *  - tar.xz → XZExtractor
     *  - tar.zst → ZstdExtractor
     *  - 其他/纯 tar → 按 xz 路径兜底（XZExtractor 支持纯 tar）
     */
    private fun extractLocal(
        destRootfs: File,
        archive: File,
        onExtractProgress: ((processed: Long, total: Long) -> Unit)? = null
    ): Result {
        destRootfs.mkdirs()
        val required = DiskSpace.estimateDecompressedRequired(archive.length())
        val available = DiskSpace.availableBytes(destRootfs)
        if (available < required) {
            log("APP私有存储空间不足：需要~${required / 1024 / 1024}MiB，可用~${available / 1024 / 1024}MiB", true)
            return Result.DiskFull(required, available)
        }
        val isZst = isZstdArchive(archive)
        log("开始流式解压 ${archive.name} (${if (isZst) "zstd" else "xz/tar"}) → ${destRootfs.name} …", false)
        var lastEntry = ""
        val r = if (isZst) {
            ZstdExtractor.extract(archive, destRootfs,
                onEntry = { name, _ -> lastEntry = name },
                onProgress = onExtractProgress
            )
        } else {
            XZExtractor.extract(archive, destRootfs,
                onEntry = { name, _ -> lastEntry = name },
                onProgress = onExtractProgress
            )
        }
        return mapResult(r, lastEntry)
    }

    /** 读取压缩包头部魔数判断是否为 zstd。 */
    private fun isZstdArchive(archive: File): Boolean {
        return try {
            java.io.FileInputStream(archive).use { fis ->
                val head = ByteArray(4)
                val n = fis.read(head)
                n >= 4 &&
                    (head[0].toInt() and 0xFF) == 0x28 &&
                    (head[1].toInt() and 0xFF) == 0xB5 &&
                    (head[2].toInt() and 0xFF) == 0x2F &&
                    (head[3].toInt() and 0xFF) == 0xFD
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun mapResult(r: Any, lastEntry: String): Result {
        return when (r) {
            is XZExtractor.Result.Ok, is ZstdExtractor.Result.Ok -> {
                log("解压完成，最后一项: $lastEntry", false)
                Result.Ok
            }
            is XZExtractor.Result.DiskFull -> Result.DiskFull(r.required, r.available)
            is ZstdExtractor.Result.DiskFull -> Result.DiskFull(r.required, r.available)
            is XZExtractor.Result.Corrupt -> {
                log("镜像损坏或格式不支持: ${r.reason}", true)
                Result.Corrupt(r.reason)
            }
            is ZstdExtractor.Result.Corrupt -> {
                log("镜像损坏或格式不支持: ${r.reason}", true)
                Result.Corrupt(r.reason)
            }
            is XZExtractor.Result.Failed -> Result.Failed(r.reason)
            is ZstdExtractor.Result.Failed -> Result.Failed(r.reason)
            else -> Result.Failed("unknown extractor result")
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
