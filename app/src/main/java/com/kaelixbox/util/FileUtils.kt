package com.kaelixbox.util

import android.content.Context
import android.os.StatFs
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

object FileUtils {

    fun appFilesRoot(context: Context): File =
        File(context.filesDir, "kaelix").apply { mkdirs() }

    fun prootDir(context: Context): File =
        File(appFilesRoot(context), "bin").apply { mkdirs() }

    /**
     * proot 二进制路径。
     *
     * proot 不再从 assets 释放到 filesDir（filesDir 默认 noexec，Android 13+
     * SELinux 会拒绝执行任意二进制）。改为以 libproot.so 名义打包进 jniLibs，
     * 运行时直接使用 nativeLibraryDir 下的 .so 路径执行 —— 系统认可该路径
     * 可执行，规避 W^X / noexec 限制（与 Termux、tiny_computer 等 rootless
     * 方案一致）。
     */
    fun prootNativeLib(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libproot.so")

    fun prootBin(context: Context): File = prootNativeLib(context)

    fun containersRoot(context: Context): File =
        File(appFilesRoot(context), "containers").apply { mkdirs() }

    fun containerRoot(context: Context, containerId: String): File =
        File(containersRoot(context), containerId).apply { mkdirs() }

    fun rootfsDir(context: Context, containerId: String): File =
        File(containerRoot(context, containerId), "rootfs")

    /**
     * 下载压缩包缓存目录（APP私有 cacheDir）。
     *
     * 使用 context.cacheDir 而非 externalFilesDir / 公共 Download，
     * 确保大文件写入 APP 沙盒私有目录，不污染公共存储。
     * 解压完成后由调用方清理。
     */
    fun downloadCacheDir(context: Context): File =
        File(context.cacheDir, "downloads").apply { mkdirs() }

    /** Remove stale partial/cached archives before a fresh download/import. */
    fun cleanDownloadCache(context: Context) {
        val dir = downloadCacheDir(context)
        dir.listFiles()?.forEach { it.delete() }
    }

    fun avatarTarget(context: Context): File = File(context.filesDir, "avatar.png")

    /**
     * Stream-copy with an 8KiB buffer. Used for asset extraction & imports.
     */
    fun copyTo(input: InputStream, output: OutputStream, onProgress: ((Long) -> Unit)? = null) {
        val buf = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buf)
            if (read <= 0) break
            output.write(buf, 0, read)
            total += read
            onProgress?.invoke(total)
        }
        output.flush()
    }

    fun copyAssetTo(context: Context, assetName: String, target: File): Boolean {
        return try {
            context.assets.open(assetName).use { input ->
                target.outputStream().use { output -> copyTo(input, output) }
            }
            target.setExecutable(true, true)
            true
        } catch (e: IOException) {
            false
        }
    }

    /** Recursively delete a directory tree. */
    fun deleteRecursive(file: File): Boolean {
        if (!file.exists()) return true
        if (file.isDirectory) file.listFiles()?.forEach { deleteRecursive(it) }
        return file.delete()
    }

    fun freeSpaceBytes(path: File): Long {
        return try {
            val stat = StatFs(path.absolutePath)
            stat.availableBytes
        } catch (e: Exception) {
            Long.MAX_VALUE
        }
    }

    fun sha1(text: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** 计算文件 SHA-256，用于下载镜像完整性校验。 */
    fun sha256File(file: File): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = fis.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        null
    }

    /** Best-effort chmod +x for the proot binary copied out of assets. */
    fun ensureExecutable(file: File): Boolean {
        if (!file.exists()) return false
        try {
            val ok = file.setExecutable(true, true)
            if (!ok) {
                // Fallback: shell chmod; may fail on no-exec mounts, that's fine — caller
                // reports SELinux/W^X errors at runtime instead of crashing.
                @Suppress("DEPRECATION")
                val proc = Runtime.getRuntime().exec(arrayOf("/system/bin/chmod", "755", file.absolutePath))
                proc.waitFor()
            }
            return file.canExecute()
        } catch (e: Exception) {
            return file.canExecute()
        }
    }
}
