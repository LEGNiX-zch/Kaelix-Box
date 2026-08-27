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

    fun prootBin(context: Context): File = File(prootDir(context), "proot")

    fun containersRoot(context: Context): File =
        File(appFilesRoot(context), "containers").apply { mkdirs() }

    fun containerRoot(context: Context, containerId: String): File =
        File(containersRoot(context), containerId).apply { mkdirs() }

    fun rootfsDir(context: Context, containerId: String): File =
        File(containerRoot(context, containerId), "rootfs")

    fun downloadCacheDir(context: Context): File =
        File(context.cacheDir, "downloads").apply { mkdirs() }

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
