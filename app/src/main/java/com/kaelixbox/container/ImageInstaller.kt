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
        onStage: ((String) -> Unit)? = null,
        onExtractEntry: ((String) -> Unit)? = null
    ): Result = withContext(Dispatchers.IO) {
        cache.parentFile?.mkdirs()
        onStage?.invoke("download")
        try {
            // 0. 检查本地是否已有完整缓存：若 SHA256 匹配则跳过下载直接解压。
            //    这实现了「下载进度持久化」——进程被杀后重启可复用已下载文件。
            if (cache.exists() && expectedSha256.isNotBlank()) {
                val cachedSha = FileUtils.sha256File(cache)
                if (cachedSha != null && cachedSha.equals(expectedSha256, ignoreCase = true)) {
                    log("检测到本地已存在完整镜像缓存，跳过下载", false)
                    onStage?.invoke("verify")
                    log("SHA256 校验通过", false)
                    onStage?.invoke("extract")
                    val extractRes = extractTo(destRootfs, cache, onExtractProgress, onExtractEntry)
                    cache.delete()
                    return@withContext extractRes
                }
                log("本地缓存 SHA256 不匹配，将尝试续传下载", false)
            }

            // 1. 先尝试加速镜像；下载完成后校验文件魔数，
            //    若不是有效 xz（例如短链失效返回了 HTML 错误页），则视为下载失败并回退。
            //    注意：下载失败时不删除缓存文件，保留部分下载内容供下次续传。
            var res = downloadResumable(mirrorUrl, cache, onProgress)
            if (res !is Result.Ok || !isValidXzArchive(cache)) {
                log("加速镜像下载失败或内容无效(${(res as? Result.Network)?.reason ?: res.javaClass.simpleName})，回退 GitHub 直连…", true)
                // 内容无效（如 HTML 错误页）时才删除缓存；网络错误保留部分文件供续传
                if (cache.exists() && !isValidXzArchive(cache)) {
                    cache.delete()
                }
                res = downloadResumable(fallbackUrl, cache, onProgress)
                if (res is Result.Ok && !isValidXzArchive(cache)) {
                    log("GitHub 直连下载内容无效，可能镜像已损坏", true)
                    cache.delete()
                    return@withContext Result.Corrupt("invalid archive content")
                }
            }
            if (res !is Result.Ok) {
                // 网络错误等：保留已下载的部分文件，下次启动可续传
                log("下载未完成，已保留部分缓存供下次续传", false)
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
            val extractRes = extractTo(destRootfs, cache, onExtractProgress, onExtractEntry)
            cache.delete()
            extractRes
        } catch (e: Throwable) {
            // 异常时也保留缓存文件，供下次续传
            log("镜像下载/解压异常: ${e.message ?: e.javaClass.simpleName}", true)
            Result.Failed(e.message ?: "unknown")
        }
    }

    /**
     * 校验下载文件是否为有效的 xz 压缩包（魔数 FD 37 7A 58 5A 00）。
     * 用于在 SHA256 校验之前快速拦截短链失效导致的 HTML 错误页等无效内容。
     */
    private fun isValidXzArchive(file: File): Boolean {
        return try {
            java.io.FileInputStream(file).use { fis ->
                val head = ByteArray(6)
                val n = fis.read(head)
                n >= 6 &&
                    (head[0].toInt() and 0xFF) == 0xFD &&
                    (head[1].toInt() and 0xFF) == 0x37 &&
                    (head[2].toInt() and 0xFF) == 0x7A &&
                    (head[3].toInt() and 0xFF) == 0x58 &&
                    (head[4].toInt() and 0xFF) == 0x5A &&
                    (head[5].toInt() and 0xFF) == 0x00
            }
        } catch (_: Throwable) {
            false
        }
    }

    /** 解压用户导入的本地 tar.xz / tar.zst / tar 到 rootfs 目录。 */
    suspend fun installFromFile(
        archive: File,
        destRootfs: File,
        onExtractProgress: ((processed: Long, total: Long) -> Unit)? = null,
        onExtractEntry: ((String) -> Unit)? = null
    ): Result = withContext(Dispatchers.IO) {
        // 注意：不调用 cleanDownloadCache，避免误删当前正在解压的本地导入归档文件。
        val res = extractLocal(destRootfs, archive, onExtractProgress, onExtractEntry)
        archive.delete()
        res
    }

    /** 在线下载分支：默认镜像为 tar.xz，固定走 XZ 解压。 */
    private fun extractTo(
        destRootfs: File,
        archive: File,
        onExtractProgress: ((processed: Long, total: Long) -> Unit)? = null,
        onExtractEntry: ((String) -> Unit)? = null
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
            onEntry = { name, _ ->
                lastEntry = name
                onExtractEntry?.invoke(name)
            },
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
        onExtractProgress: ((processed: Long, total: Long) -> Unit)? = null,
        onExtractEntry: ((String) -> Unit)? = null
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
                onEntry = { name, _ ->
                    lastEntry = name
                    onExtractEntry?.invoke(name)
                },
                onProgress = onExtractProgress
            )
        } else {
            XZExtractor.extract(archive, destRootfs,
                onEntry = { name, _ ->
                    lastEntry = name
                    onExtractEntry?.invoke(name)
                },
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
            // 仅当服务器返回 206 Partial Content 时才续传追加；
            // 200 表示服务器返回完整内容，此时必须覆盖写入，否则会拼接到旧文件后面导致损坏。
            val append = code == 206
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
