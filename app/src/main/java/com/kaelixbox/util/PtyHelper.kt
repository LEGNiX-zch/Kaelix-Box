package com.kaelixbox.util

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 伪终端（PTY）辅助。
 *
 * bash 在没有真实 tty 的情况下会以非交互模式运行，且 stdout 被全缓冲
 * （4KB+），导致用户输入命令后看不到任何输出。分配一个 pty slave
 * 作为 proot/bash 的 stdin/stdout/stderr，即可恢复交互行为与行缓冲。
 *
 * master 端由 APP 读写，slave 端交给子进程。
 *
 * Android 公开 API 不提供 ioctl，这里通过反射调用 libcore.io.Posix.ioctl
 * 完成 unlockpt / ptsname；反射失败时返回 null，调用方回退到管道模式。
 */
object PtyHelper {

    data class PtyPair(
        val masterFd: FileDescriptor,
        val slavePath: String
    ) {
        val masterIn: FileInputStream get() = FileInputStream(masterFd)
        val masterOut: FileOutputStream get() = FileOutputStream(masterFd)
        fun close() { runCatching { Os.close(masterFd) } }
    }

    /** ioctl 常量（ARM64 / x86 一致）。0x80045430 超出 Int 上限，需显式 toInt。 */
    private const val TIOCGPTN: Int = 0x80045430.toInt()  // get pty number
    private const val TIOCSPTLCK: Int = 0x40045431  // set/unset pty lock

    private var ioctlMethod: java.lang.reflect.Method? = null

    init {
        ioctlMethod = try {
            val libcore = Class.forName("libcore.io.Libcore")
            val osField = libcore.getDeclaredField("os").apply { isAccessible = true }
            val os = osField.get(null)
            // Posix.ioctl(FileDescriptor, int, Object) 可同时处理 int 与 int[]
            os.javaClass.getMethod(
                "ioctl",
                FileDescriptor::class.java,
                Int::class.javaPrimitiveType,
                Any::class.java
            )
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 打开 /dev/ptmx，解锁并获取 slave 路径。
     * @return pty 句柄，失败返回 null（调用方回退到管道模式）。
     */
    fun open(): PtyPair? {
        val master = try {
            Os.open("/dev/ptmx", OsConstants.O_RDWR, 0)
        } catch (_: Throwable) {
            return null
        }
        return try {
            // unlockpt: 传入 Integer(0) 表示解锁
            ioctl(master, TIOCSPTLCK, Integer.valueOf(0))
            // ptsname: 取出 pty 编号
            val arg = IntArray(1)
            ioctl(master, TIOCGPTN, arg)
            val slave = "/dev/pts/${arg[0]}"
            // 确保 slave 可被子进程访问（最佳努力）
            runCatching { File(slave).setReadable(true, false) }
            runCatching { File(slave).setWritable(true, false) }
            PtyPair(master, slave)
        } catch (_: Throwable) {
            runCatching { Os.close(master) }
            null
        }
    }

    private fun ioctl(fd: FileDescriptor, request: Int, arg: Any) {
        val m = ioctlMethod ?: return
        try {
            m.invoke(posixInstance(), fd, request, arg)
        } catch (_: Throwable) {
            // 反射失败或被系统拦截（hidden API），由调用方决定是否回退
        }
    }

    private var cachedPosix: Any? = null
    private fun posixInstance(): Any? {
        cachedPosix?.let { return it }
        return try {
            val libcore = Class.forName("libcore.io.Libcore")
            val osField = libcore.getDeclaredField("os").apply { isAccessible = true }
            osField.get(null).also { cachedPosix = it }
        } catch (_: Throwable) {
            null
        }
    }
}
