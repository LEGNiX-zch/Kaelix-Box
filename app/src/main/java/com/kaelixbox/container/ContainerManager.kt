package com.kaelixbox.container

import android.content.Context
import com.kaelixbox.App
import com.kaelixbox.prefs.AppPrefs
import com.kaelixbox.util.FileUtils
import com.kaelixbox.util.PtyHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Central runtime controller for the proot container.
 *
 * Responsibilities:
 *  - persist/load the container list & current selection (SharedPreferences),
 *  - spawn proot as a child process with stdout/stderr streamed into
 *    [TerminalBus] and stdin wired to a sink the terminal can write to,
 *  - stop the container & its vncserver child cleanly,
 *  - forcibly kill every descendant process on full app exit (see [App]).
 */
object ContainerManager {

    private val running = AtomicBoolean(false)
    private val procRef = AtomicReference<Process?>(null)
    private val stdinRef = AtomicReference<Writer?>(null)
    private val ptyRef = AtomicReference<PtyHelper.PtyPair?>(null)
    @Volatile var currentConfig: ContainerConfig? = null
        private set
    @Volatile var onDied: (() -> Unit)? = null
    @Volatile var onStateChanged: ((Boolean) -> Unit)? = null

    // ---------- container list persistence ----------

    fun listContainers(context: Context): List<ContainerConfig> {
        val prefs = AppPrefs.get(context)
        val arr = JSONArray(prefs.containersJson)
        val out = ArrayList<ContainerConfig>(arr.length())
        for (i in 0 until arr.length()) {
            out += ContainerConfig.fromJson(arr.getJSONObject(i))
        }
        return out
    }

    fun saveContainers(context: Context, list: List<ContainerConfig>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        AppPrefs.get(context).containersJson = arr.toString()
    }

    fun addContainer(context: Context, cfg: ContainerConfig): Boolean {
        val list = listContainers(context).toMutableList()
        if (list.any { it.id == cfg.id }) return false
        list.add(cfg)
        saveContainers(context, list)
        if (AppPrefs.get(context).currentContainerId.isEmpty()) {
            AppPrefs.get(context).currentContainerId = cfg.id
        }
        return true
    }

    fun removeContainer(context: Context, id: String): Boolean {
        val list = listContainers(context).toMutableList()
        val removed = list.removeAll { it.id == id }
        if (!removed) return false
        saveContainers(context, list)
        FileUtils.deleteRecursive(FileUtils.containerRoot(context, id))
        if (AppPrefs.get(context).currentContainerId == id) {
            AppPrefs.get(context).currentContainerId = list.firstOrNull()?.id ?: ""
        }
        return true
    }

    fun current(context: Context): ContainerConfig? {
        val id = AppPrefs.get(context).currentContainerId
        if (id.isEmpty()) return null
        return listContainers(context).firstOrNull { it.id == id }
    }

    fun setCurrent(context: Context, id: String) {
        AppPrefs.get(context).currentContainerId = id
        currentConfig = null
    }

    // ---------- process lifecycle ----------

    val isRunning: Boolean get() = running.get() && (procRef.get()?.isAlive == true)

    fun start(context: Context, cfg: ContainerConfig, micEnabled: Boolean) {
        if (isRunning) {
            TerminalBus.appendLine("[container] 已有容器在运行，请先终止。")
            return
        }
        val rootfs = File(cfg.rootfs(context))
        if (!rootfs.exists() || rootfs.listFiles()?.isNotEmpty() != true) {
            TerminalBus.appendLine("[container] rootfs 不存在或为空：${rootfs.absolutePath}", true)
            TerminalBus.appendLine("[container] 请先在设置页下载/导入镜像。", true)
            return
        }
        val (argv, _env) = ProotManager.build(context, cfg, micEnabled) { msg ->
            TerminalBus.appendLine(msg, true)
        }
        TerminalBus.appendLine(ProotManager.describe(cfg, micEnabled))

        val pb = ProcessBuilder(argv).redirectErrorStream(true)
        pb.directory(File(cfg.rootfs(context)))
        // proot needs PATH for some shims; pass a minimal env.
        pb.environment().clear()
        pb.environment()["PATH"] = "/system/bin:/system/xbin"
        pb.environment()["TMPDIR"] = context.cacheDir.absolutePath

        // 尝试分配 pty，让 bash 以交互模式运行并恢复行缓冲输出。
        // 失败则回退到管道模式（非交互，输出可能全缓冲）。
        val pty = PtyHelper.open()
        if (pty != null) {
            val slave = File(pty.slavePath)
            pb.redirectInput(slave)
            pb.redirectOutput(slave)
            pb.redirectError(slave)
            TerminalBus.appendLine("[proot] 已分配 PTY，终端交互模式已启用。", false)
        } else {
            TerminalBus.appendLine("[proot] 未分配到 PTY，回退管道模式（输出可能延迟）。", true)
        }

        val proc = try {
            pb.start()
        } catch (e: IOException) {
            pty?.close()
            TerminalBus.appendLine(
                "[proot] 二进制执行失败: ${e.message}\n[proot] 请检查 SELinux/W^X 权限，或重新安装 APK。",
                true
            )
            return
        }
        procRef.set(proc)
        ptyRef.set(pty)
        currentConfig = cfg
        running.set(true)
        onStateChanged?.invoke(true)

        // stdout/stderr pump：有 pty 时从 master 读，否则从管道读。
        val out: InputStream = pty?.masterIn ?: proc.inputStream
        streamToBus(out, false)
        // pty 模式下 stderr 已合并到 master；管道模式下 redirectErrorStream(true) 也已合并。

        // stdin sink：有 pty 时写 master，否则写管道。
        val outStream: OutputStream = pty?.masterOut ?: proc.outputStream
        stdinRef.set(OutputStreamWriter(outStream, Charsets.UTF_8))

        // Waiter: detect abnormal exit.
        Thread({
            try {
                val code = proc.waitFor()
                running.set(false)
                onStateChanged?.invoke(false)
                stdinRef.getAndSet(null)?.runCatching { close() }
                TerminalBus.appendLine("[container] proot 进程退出 code=$code", code != 0)
                if (code != 0) onDied?.invoke()
            } catch (_: InterruptedException) {
                // killed by stop()
            } finally {
                running.set(false)
            }
        }, "proot-waiter").start()
    }

    private fun streamToBus(stream: InputStream, isError: Boolean) {
        Thread({
            val reader = BufferedReader(stream.reader(Charsets.UTF_8), 4 * 1024)
            try {
                val buf = CharArray(2048)
                while (true) {
                    val n = reader.read(buf)
                    if (n <= 0) break
                    TerminalBus.append(String(buf, 0, n), isError)
                }
            } catch (_: IOException) {
                // stream closed — normal on process exit
            } catch (oom: OutOfMemoryError) {
                TerminalBus.appendLine("[container] 内存不足，输出流关闭。", true)
            }
        }, if (isError) "proot-err" else "proot-out").start()
    }

    fun sendInput(text: String) {
        val w = stdinRef.get() ?: return
        try {
            w.write(text)
            w.write("\n")
            w.flush()
        } catch (_: IOException) {
            // process gone — ignore
        }
    }

    /** Send a raw command (no newline) — used by VNC launcher etc. */
    fun execRaw(text: String) {
        val w = stdinRef.get() ?: return
        try {
            w.write(text)
            w.flush()
        } catch (_: IOException) { /* ignore */ }
    }

    fun stop(context: Context) {
        running.set(false)
        val proc = procRef.getAndSet(null) ?: return
        try {
            Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c",
                "pkill -9 -f vncserver; pkill -9 -f Xtightvnc; pkill -9 -f Xtigervnc"))
                .waitFor()
        } catch (_: Throwable) { }
        proc.destroy()
        procRef.set(null)
        stdinRef.getAndSet(null)?.runCatching { close() }
        ptyRef.getAndSet(null)?.runCatching { close() }
        TerminalBus.appendLine("[container] 容器已终止。")
    }

    /** Forcibly kill everything spawned. Called on real app exit. */
    fun killEverything(context: Context) {
        running.set(false)
        procRef.getAndSet(null)?.runCatching {
            destroy()
        }
        stdinRef.getAndSet(null)?.runCatching { close() }
        ptyRef.getAndSet(null)?.runCatching { close() }
        try {
            Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c",
                "pkill -9 -f proot; pkill -9 -f vncserver; pkill -9 -f Xtightvnc; pkill -9 -f Xtigervnc"))
                .waitFor()
        } catch (_: Throwable) { }
    }
}
