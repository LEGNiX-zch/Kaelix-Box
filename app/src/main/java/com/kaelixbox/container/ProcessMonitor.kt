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
     * Called once at app process start (from Application.onCreate, NOT from
     * Activity.onCreate). If the previous process was killed by the system
     * we surface a one-time notice.
     *
     * The flag is set to true in [markForPotentialKill] (called from
     * Activity.onPause) and cleared in [clearKilledFlag] (called from
     * Activity.onResume). A clean foreground→background→foreground cycle
     * therefore never logs; only an actual process death while backgrounded
     * leaves the flag set for the next launch.
     */
    fun markBootFlagIfKilled(context: Context) {
        val prefs = AppPrefs.get(context)
        if (prefs.wasKilledBySystem()) {
            TerminalBus.appendLine(
                "[system] 应用被系统杀死，请设置无限制省电（部分国产 ROM 即使设置也可能被杀）。",
                true
            )
            prefs.setKilledBySystem(false)
        }
    }

    /** Activity.onPause: we may be killed soon. */
    fun markForPotentialKill(context: Context) {
        AppPrefs.get(context).setKilledBySystem(true)
    }

    /** Activity.onResume: we're back, clear the kill flag. */
    fun clearKilledFlag(context: Context) {
        AppPrefs.get(context).setKilledBySystem(false)
    }
}
