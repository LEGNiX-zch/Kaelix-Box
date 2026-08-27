package com.kaelixbox.vnc

import com.kaelixbox.container.TerminalBus
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * VNC port allocator.
 *
 * Per spec: never hardcode 5901. Try 5901 first, then 5902 / 5903; the first
 * port that is free to bind (and whose display's vncserver we can launch) is
 * returned. If none of the three are available we surface an error dialog
 * and refuse to start VNC — never silently fail.
 *
 * Before probing we also attempt to kill any residual vncserver processes
 * that might be holding the ports from a crashed previous session.
 */
object PortAllocator {

    private const val BASE = 5901
    private const val TOP = 5903

    fun freePort(preClean: Boolean = true): Int? {
        if (preClean) {
            try {
                Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c",
                    "pkill -9 -f vncserver; pkill -9 -f Xtightvnc; pkill -9 -f Xtigervnc"))
                    .waitFor()
            } catch (_: Throwable) { }
            try { Thread.sleep(150) } catch (_: Throwable) {}
        }
        for (p in BASE..TOP) {
            if (isFree(p)) return p
        }
        TerminalBus.appendLine("[vnc] 5901-5903 端口全部被占用，无法启动 VNC。", true)
        return null
    }

    /** display number = port - 5900 (5901 → :1). */
    fun displayForPort(port: Int): Int = port - 5900

    private fun isFree(port: Int): Boolean = try {
        ServerSocket().use { s ->
            s.reuseAddress = false
            s.bind(InetSocketAddress("127.0.0.1", port))
        }
        true
    } catch (_: Throwable) {
        false
    }
}
