package com.kaelixbox

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.kaelixbox.about.AboutFragment
import com.kaelixbox.container.ContainerConfig
import com.kaelixbox.container.ContainerManager
import com.kaelixbox.container.ImageInstaller
import com.kaelixbox.container.ProcessMonitor
import com.kaelixbox.container.TerminalBus
import com.kaelixbox.container.XFCEInstaller
import com.kaelixbox.databinding.ActivityMainBinding
import com.kaelixbox.prefs.AppPrefs
import com.kaelixbox.settings.SettingsFragment
import com.kaelixbox.terminal.TerminalFragment
import com.kaelixbox.util.FileUtils
import com.kaelixbox.util.PermissionHelper
import com.kaelixbox.vnc.VncFragment
import com.kaelixbox.vnc.VncHost
import com.kaelixbox.vnc.VncSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val terminal = TerminalFragment()
    private val settings = SettingsFragment()
    private val about = AboutFragment()

    private var current: Fragment = terminal

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.title = null  // we render our own title TextView

        setupBottomNav()
        switchTo(terminal)

        b.btnStopContainer.setOnClickListener { stopContainer() }
        b.btnStartVnc.setOnClickListener { startVnc() }

        // Startup requirements: storage permission, battery prompt,
        // first-launch default image download.
        PermissionHelper.requestStorage(this)
        promptBatteryOptimization()
        maybeInstallDefaultImage()

        // Wire watchdogs.
        ContainerManager.onDied = {
            runOnUiThread {
                if (VncHost.session != null) closeVnc()
                Toast.makeText(this, R.string.msg_process_died, Toast.LENGTH_LONG).show()
                switchTo(terminal)
            }
        }
        ProcessMonitor.onContainerDied = {
            runOnUiThread { if (VncHost.session != null) closeVnc(); switchTo(terminal) }
        }
        ProcessMonitor.start((application as App).appScope, this)
    }

    // ---------------- bottom navigation ----------------

    private data class NavSpec(val icon: Int, val label: Int, val fragment: Fragment)
    private val navs = linkedMapOf(
        R.id.nav_terminal to NavSpec(R.drawable.ic_nav_terminal, R.string.nav_terminal, terminal),
        R.id.nav_settings to NavSpec(R.drawable.ic_nav_settings, R.string.nav_settings, settings),
        R.id.nav_about to NavSpec(R.drawable.ic_nav_about, R.string.nav_about, about)
    )

    private fun setupBottomNav() {
        navs.forEach { (id, spec) ->
            val item = b.root.findViewById<LinearLayout>(id)
            item.findViewById<ImageView>(R.id.nav_icon).setImageResource(spec.icon)
            item.findViewById<TextView>(R.id.nav_label).setText(spec.label)
            item.setOnClickListener {
                if (current == spec.fragment) return@setOnClickListener
                // VNC has its own chrome; switching away from it tears it down.
                if (current is VncFragment) closeVnc()
                switchTo(spec.fragment)
                navs.keys.forEach { otherId ->
                    b.root.findViewById<View>(otherId).isActivated = (otherId == id)
                }
            }
        }
        // Default highlight = terminal.
        b.navTerminal.root.isActivated = true
    }

    private fun switchTo(fragment: Fragment) {
        current = fragment
        supportFragmentManager.beginTransaction()
            .replace(R.id.content_frame, fragment)
            .commitAllowingStateLoss()
        // Update nav highlight.
        val id = when (fragment) {
            terminal -> R.id.nav_terminal
            settings -> R.id.nav_settings
            about -> R.id.nav_about
            else -> -1
        }
        navs.keys.forEach { oid ->
            b.root.findViewById<View>(oid).isActivated = (oid == id)
        }
        // Action bar icons only meaningful on terminal + vnc; hide on others.
        val iconsVisible = fragment is TerminalFragment || fragment is VncFragment
        b.btnStopContainer.visibility = if (iconsVisible) View.VISIBLE else View.GONE
        b.btnStartVnc.visibility = if (iconsVisible) View.VISIBLE else View.GONE
    }

    // ---------------- action bar buttons ----------------

    private fun stopContainer() {
        // Tear down VNC chrome first, then kill proot.
        if (VncHost.session != null) closeVnc()
        ContainerManager.stop(this)
        switchTo(terminal)
    }

    private fun startVnc() {
        val cfg = ContainerManager.current(this) ?: run {
            Toast.makeText(this, R.string.terminal_empty, Toast.LENGTH_LONG).show()
            return
        }
        // Ensure the container is running. If it isn't, boot it now (and run
        // the XFCE installer hook for the default Debian13 container).
        if (!ContainerManager.isRunning) {
            val mic = AppPrefs.get(this).micPassthroughEnabled()
            ContainerManager.start(this, cfg, mic)
            // Give proot a beat to come up before we ask XFCE to install /
            // vncserver to launch.
            Thread {
                try { Thread.sleep(800) } catch (_: Throwable) {}
                runOnUiThread {
                    if (cfg.isDefaultDebian13 && !XFCEInstaller.isInstalled(this, cfg)) {
                        XFCEInstaller.runOnceIfDefault(this, cfg)
                    }
                    launchVncSession(cfg)
                }
            }.start()
            return
        }
        launchVncSession(cfg)
    }

    private fun launchVncSession(cfg: ContainerConfig) {
        val session = VncSession(
            context = this,
            config = cfg,
            onConnected = {
                runOnUiThread {
                    TerminalBus.appendLine("[vnc] 已连接，渲染桌面中。")
                }
            },
            onDisconnected = { reason ->
                runOnUiThread {
                    TerminalBus.appendLine("[vnc] ${reason ?: "断开"}", true)
                    closeVnc()
                    switchTo(terminal)
                    Toast.makeText(this, R.string.msg_vnc_disconnected, Toast.LENGTH_SHORT).show()
                }
            }
        )
        VncHost.session = session
        VncHost.config = cfg
        VncHost.onCloseRequested = { closeVnc() }
        val ok = session.start()
        if (!ok) {
            Toast.makeText(this, R.string.msg_no_port_available, Toast.LENGTH_LONG).show()
            return
        }
        // Surface the allocated port to the user.
        AlertDialog.Builder(this)
            .setTitle(R.string.title_port_allocated)
            .setMessage("当前 VNC 端口: ${session.port}\nVNC 密码: ${cfg.vncPassword}")
            .setPositiveButton(R.string.ok) { d, _ -> d.dismiss() }
            .setOnDismissListener {
                // After acknowledging, show the VNC surface.
                switchTo(VncFragment())
            }
            .show()
    }

    fun closeVnc() {
        VncHost.session?.stop()
        VncHost.session = null
        // Pop the VNC fragment and go back to the terminal.
        if (current is VncFragment) switchTo(terminal)
    }

    // ---------------- battery + first-launch image ----------------

    private fun promptBatteryOptimization() {
        val prefs = AppPrefs.get(this)
        if (prefs.batteryPromptShown()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.title_warning)
            .setMessage(R.string.msg_battery_prompt)
            .setPositiveButton(R.string.ok) { _, _ ->
                PermissionHelper.openBatteryOptimizationSettings(this)
            }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { prefs.markBatteryPromptShown() }
            .show()
    }

    private fun maybeInstallDefaultImage() {
        val prefs = AppPrefs.get(this)
        if (prefs.defaultImageInstalled()) return
        if (ContainerManager.listContainers(this).isNotEmpty()) {
            prefs.markDefaultImageInstalled(true); return
        }
        val cfg = ContainerConfig(
            id = "debian13-default",
            name = ContainerConfig.DEFAULT_NAME,
            arch = ContainerConfig.DEFAULT_ARCH,
            distribution = ContainerConfig.DEFAULT_DISTRO,
            isDefaultDebian13 = true,
            vncPassword = ContainerConfig.DEFAULT_VNC_PASS
        )
        val url = "https://github.com/2cd/debian-museum/releases/download/v1.0/13_trixie_arm64.tar.zst"
        val cache = java.io.File(FileUtils.downloadCacheDir(this), "debian13.tar.zst")
        val rootfs = FileUtils.rootfsDir(this, cfg.id)
        val installer = ImageInstaller(this) { msg, err -> TerminalBus.appendLine(msg, err) }

        (application as App).appScope.launch {
            TerminalBus.appendLine("[image] 后台下载 Debian13 Trixie ARM64 镜像…")
            val r = installer.installFromUrl(url, rootfs, cache) { dl, total ->
                if (total > 0) {
                    val pct = (dl * 100 / total).toInt()
                    TerminalBus.appendLine("\r[image] 下载 $pct% (${dl / 1024}KiB)", false)
                }
            }
            withContext(Dispatchers.Main) {
                when (r) {
                    is ImageInstaller.Result.Ok -> {
                        ContainerManager.addContainer(this@MainActivity, cfg)
                        AppPrefs.get(this@MainActivity).currentContainerId = cfg.id
                        prefs.markDefaultImageInstalled(true)
                        TerminalBus.appendLine("[image] 镜像就绪，点击三角形 VNC 图标即可启动桌面。")
                    }
                    else -> showDownloadFailedFallback()
                }
            }
        }
    }

    private fun showDownloadFailedFallback() {
        AlertDialog.Builder(this)
            .setTitle(R.string.title_error)
            .setMessage(R.string.msg_download_image_failed)
            .setPositiveButton(R.string.ok) { _, _ -> switchTo(settings) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------- permission result ----------------

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PermissionHelper.REQ_STORAGE) {
            val ok = grantResults.isNotEmpty() && grantResults.all { it == 0 }
            if (!ok) {
                Toast.makeText(this, R.string.msg_permission_storage_denied, Toast.LENGTH_LONG).show()
            }
        }
    }
}
