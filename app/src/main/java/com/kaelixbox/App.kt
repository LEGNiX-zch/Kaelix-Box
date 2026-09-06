package com.kaelixbox

import android.app.Activity
import android.app.Application
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import com.kaelixbox.container.ContainerManager
import com.kaelixbox.container.ProcessMonitor
import com.kaelixbox.prefs.AppPrefs
import com.kaelixbox.util.FileUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry. Owns a global app-scope coroutine context and the
 * Activity lifecycle bookkeeping used to decide when to tear down all
 * proot / vncserver processes.
 *
 * Lifecycle rules enforced here:
 *  - app backgrounded (still in recents): proot keeps running;
 *  - last Activity destroyed (real exit): forcibly kill proot + vncserver
 *    child processes to avoid orphaned background processes.
 */
class App : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = AppPrefs.get(this)

        // proot is shipped as libproot.so inside jniLibs and lives under
        // nativeLibraryDir at runtime — that path is exec-permitted by the
        // system, so NO asset extraction / chmod is needed. Critically, proot
        // must NOT be copied to filesDir, which is noexec on Android 13+ and
        // would trigger SELinux EACCES on exec.
        appScope.launch {
            val bin = FileUtils.prootBin(this@App)
            if (!bin.exists() || bin.length() < 1024) {
                android.util.Log.e("App", "proot native lib missing: ${bin.absolutePath}")
            }
            ProcessMonitor.markBootFlagIfKilled(this@App)
        }

        registerActivityLifecycleCallbacks(ExitWatcher)
    }

    private object ExitWatcher : ActivityLifecycleCallbacks {
        @Volatile private var live = 0
        override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {}
        override fun onActivityStarted(activity: Activity) { live++ }
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {
            live--
            if (live <= 0) {
                // App is leaving the recents/finishing for real. Forcibly kill
                // every child process spawned by proot + vncserver so we don't
                // leak orphaned background processes.
                live = 0
                ContainerManager.killEverything(activity.applicationContext)
            }
        }
        override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    companion object {
        @Volatile lateinit var instance: App
            private set
        @Volatile lateinit var prefs: AppPrefs
            private set

        fun killProcessTree(context: Context) {
            // Best-effort: pkill -9 the proot & vncserver descendants.
            try {
                Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c",
                    "pkill -9 -f proot; pkill -9 -f vncserver; pkill -9 -f Xtigervnc; pkill -9 -f Xtightvnc"))
                    .waitFor()
            } catch (_: Throwable) { /* ignore */ }
            @Suppress("DEPRECATION")
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            // Nothing else to do: we cannot kill by PID reliably cross-selinux.
        }
    }
}
