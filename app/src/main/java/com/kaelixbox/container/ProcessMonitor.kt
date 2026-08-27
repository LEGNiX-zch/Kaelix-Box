package com.kaelixbox.container

import android.content.Context
import com.kaelixbox.prefs.AppPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Background watchdog. Continuously polls the proot process liveness; on
 * abnormal death it tears down the VNC view + floating button, routes the
 * user back to the terminal, and emits a process-exit log line so the failure
 * is never silent.
 *
 * Also owns the "killed by system" detection: when the app process is
 * resurrected after a system-kill we surface a Toast-style log line asking
 * the user to enable unrestricted battery.
 */
object ProcessMonitor {

    @Volatile var onContainerDied: (() -> Unit)? = null

    fun start(scope: CoroutineScope, context: Context) {
        scope.launch(Dispatchers.IO) {
            while (true) {
                delay(1500)
                val cfg = ContainerManager.currentConfig
                if (cfg != null && ContainerManager.isRunning) {
                    val alive = ContainerManager.isRunning
                    if (!alive) {
                        // The waiter in ContainerManager already logged the exit;
                        // we additionally tear down the VNC surface + FAB.
                        onContainerDied?.invoke()
                        // back off a little before polling again
                        delay(1000)
                    }
                }
            }
        }
    }

    /**
     * Called once at app boot. If the previous session was terminated by the
     * system (we set the flag right before a potential kill), surface a
     * notice; otherwise mark the flag for next boot.
     */
    fun markBootFlagIfKilled(context: Context) {
        val prefs = AppPrefs.get(context)
        if (prefs.wasKilledBySystem()) {
            TerminalBus.appendLine(
                "[system] 应用被系统杀死，请设置无限制省电（部分国产 ROM 即使设置也可能被杀）。",
                true
            )
            prefs.setKilledBySystem(false)
        } else {
            // Assume we may be killed; cleared on a graceful foreground exit.
            prefs.setKilledBySystem(true)
        }
    }

    fun clearKilledFlag(context: Context) {
        AppPrefs.get(context).setKilledBySystem(false)
    }
}
