package com.kaelixbox.vnc

import android.content.Context
import com.kaelixbox.container.ContainerManager
import com.kaelixbox.container.TerminalBus
import com.kaelixbox.container.ContainerConfig

/**
 * Orchestrates the lifecycle of an in-container tightvncserver + the in-app
 * RFB client that connects back to it over the loopback.
 *
 * Flow:
 *  1. allocate a free port in 5901..5903 (killing any residual vncserver),
 *  2. send `vncserver :<display> -geometry WxH -depth 24` to the running
 *     proot container via its stdin (output flows back to the terminal),
 *  3. print the VNC password line so the user can see it,
 *  4. spin up a [VncClient] against 127.0.0.1:<port> and connect.
 *
 * On close we send `vncserver -kill :<display>` to release the port and stop
 * the client so no orphaned vncserver stays running.
 */
class VncSession(
    private val context: Context,
    private val config: ContainerConfig,
    private val onConnected: () -> Unit,
    private val onDisconnected: (String?) -> Unit
) {
    @Volatile private var client: VncClient? = null
    /** Invoked on the main thread the moment the client object is created. */
    @Volatile var onClientCreated: ((VncClient) -> Unit)? = null
    /** Framebuffer sink; the view layer binds this once it exists. */
    @Volatile var onBitmap: ((VncFramebuffer) -> Unit)? = null
    @Volatile var port: Int = 0
        private set
    @Volatile private var display: Int = 0
    @Volatile var started = false
        private set

    fun start(width: Int = 1280, height: Int = 720): Boolean {
        if (!ContainerManager.isRunning) {
            TerminalBus.appendLine("[vnc] 容器未运行，先启动容器。", true)
            return false
        }
        val p = PortAllocator.freePort(preClean = true) ?: return false
        port = p
        display = PortAllocator.displayForPort(p)
        TerminalBus.appendLine("[vnc] 使用端口 $port (display :$display)")

        // Launch tightvncserver inside the container via stdin. Geometry +
        // depth are passed on the command line; the password was set by the
        // XFCE installer (and is the container's configured VNC password).
        val launchCmd = "vncserver :$display -geometry ${width}x${height} -depth 24\n"
        ContainerManager.execRaw(launchCmd)
        TerminalBus.appendLine("[vnc] VNC 密码: ${config.vncPassword}")

        // Give tightvncserver a moment to bind the port.
        Thread {
            try {
                Thread.sleep(1500)
            } catch (_: Throwable) {}
            val c = VncClient(
                host = "127.0.0.1",
                port = port,
                password = config.vncPassword,
                onBitmap = { fb -> this@VncSession.onBitmap?.invoke(fb) },
                onConnected = onConnected,
                onDisconnected = onDisconnected
            )
            client = c
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onClientCreated?.invoke(c)
            }
            c.start()
        }.start()
        started = true
        return true
    }

    fun stop() {
        if (started) {
            // Kill vncserver inside the container to release the port.
            ContainerManager.execRaw("vncserver -kill :$display 2>/dev/null\n")
        }
        client?.stop("用户关闭VNC")
        client = null
        started = false
        // Belt & braces: pkill any leftover vncserver processes.
        try {
            Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c",
                "pkill -9 -f vncserver; pkill -9 -f Xtightvnc; pkill -9 -f Xtigervnc"))
                .waitFor()
        } catch (_: Throwable) { }
    }

    fun client(): VncClient? = client
}
