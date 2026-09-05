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
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
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
    private val fragments: List<Fragment> = listOf(
        TerminalFragment(),
        SettingsFragment(),
        AboutFragment()
    )
    private val pageIds = listOf(R.id.nav_terminal, R.id.nav_settings, R.id.nav_about)

    private var vncFragment: VncFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.title = null

        setupViewPager()
        setupBottomNav()

        b.btnStopContainer.setOnClickListener { stopContainer() }
        b.btnStartVnc.setOnClickListener { startVnc() }

        PermissionHelper.requestStorage(this)
        promptBatteryOptimization()
        maybeInstallDefaultImage()

        ContainerManager.onDied = {
            runOnUiThread {
                if (VncHost.session != null) closeVnc()
                Toast.makeText(this, R.string.msg_process_died, Toast.LENGTH_LONG).show()
                selectPage(0)
            }
        }
        ProcessMonitor.onContainerDied = {
            runOnUiThread {
                if (VncHost.session != null) closeVnc()
                selectPage(0)
            }
        }
        ProcessMonitor.start((application as App).appScope, this)
    }

    override fun onResume() {
        super.onResume()
        // We're in the foreground again — clear the "might be killed" flag.
        ProcessMonitor.clearKilledFlag(this)
    }

    override fun onPause() {
        super.onPause()
        // We're going to the background — the system may kill us.
        ProcessMonitor.markForPotentialKill(this)
    }

    // ---------------- ViewPager2 ----------------

    private fun setupViewPager() {
        b.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = fragments.size
            override fun createFragment(position: Int) = fragments[position]
        }
        // Slide + subtle scale animation. Alpha stays at 1.0 because all
        // fragment roots have opaque backgrounds; fading would show the page
        // underneath and cause the "overlapping UI" visual bug.
        b.viewPager.setPageTransformer { page, position ->
            val abs = kotlin.math.abs(position)
            page.alpha = 1f
            page.translationX = -position * page.width * 0.25f
            page.scaleX = 1f - abs * 0.08f
            page.scaleY = 1f - abs * 0.08f
        }
        b.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                updateNavHighlight(position)
                val frag = fragments[position]
                val iconsVisible = frag is TerminalFragment
                b.btnStopContainer.visibility = if (iconsVisible) View.VISIBLE else View.GONE
                b.btnStartVnc.visibility = if (iconsVisible) View.VISIBLE else View.GONE
            }
        })
    }

    private fun selectPage(index: Int) {
        if (b.viewPager.currentItem != index) {
            b.viewPager.setCurrentItem(index, true)
        } else {
            updateNavHighlight(index)
        }
    }

    // ---------------- bottom navigation ----------------

    private data class NavSpec(val icon: Int, val label: Int)
    private val navs = listOf(
        NavSpec(R.drawable.ic_nav_terminal, R.string.nav_terminal),
        NavSpec(R.drawable.ic_nav_settings, R.string.nav_settings),
        NavSpec(R.drawable.ic_nav_about, R.string.nav_about)
    )

    private fun setupBottomNav() {
        navs.forEachIndexed { index, spec ->
            val item = b.root.findViewById<LinearLayout>(pageIds[index])
            item.findViewById<ImageView>(R.id.nav_icon).setImageResource(spec.icon)
            item.findViewById<TextView>(R.id.nav_label).setText(spec.label)
            item.setOnClickListener {
                if (vncFragment != null) closeVnc()
                selectPage(index)
            }
        }
        updateNavHighlight(0)
    }

    private fun updateNavHighlight(selected: Int) {
        pageIds.forEachIndexed { index, id ->
            b.root.findViewById<View>(id).isActivated = (index == selected)
        }
    }

    // ---------------- action bar buttons ----------------

    private fun stopContainer() {
        if (VncHost.session != null) closeVnc()
        ContainerManager.stop(this)
        selectPage(0)
    }

    private fun startVnc() {
        val cfg = ContainerManager.current(this) ?: run {
            Toast.makeText(this, R.string.terminal_empty, Toast.LENGTH_LONG).show()
            return
        }
        if (!ContainerManager.isRunning) {
            val mic = AppPrefs.get(this).micPassthroughEnabled()
            ContainerManager.start(this, cfg, mic)
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
                runOnUiThread { TerminalBus.appendLine("[vnc] 已连接，渲染桌面中。") }
            },
            onDisconnected = { reason ->
                runOnUiThread {
                    TerminalBus.appendLine("[vnc] ${reason ?: "断开"}", true)
                    closeVnc()
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
        AlertDialog.Builder(this)
            .setTitle(R.string.title_port_allocated)
            .setMessage("当前 VNC 端口: ${session.port}\nVNC 密码: ${cfg.vncPassword}")
            .setPositiveButton(R.string.ok) { d, _ -> d.dismiss() }
            .setOnDismissListener { showVncOverlay() }
            .show()
    }

    private fun showVncOverlay() {
        val frag = VncFragment()
        vncFragment = frag
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                androidx.appcompat.R.anim.abc_fade_in,
                androidx.appcompat.R.anim.abc_fade_out
            )
            .replace(R.id.vnc_container, frag, "vnc")
            .commitAllowingStateLoss()
    }

    fun closeVnc() {
        VncHost.session?.stop()
        VncHost.session = null
        val frag = vncFragment
        if (frag != null) {
            supportFragmentManager.beginTransaction()
                .remove(frag)
                .commitAllowingStateLoss()
        }
        vncFragment = null
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
                    TerminalBus.appendLine("[image] 下载 $pct% (${dl / 1024}KiB)", false)
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
            .setPositiveButton(R.string.ok) { _, _ -> selectPage(1) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------- permission result ----------------

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PermissionHelper.REQ_STORAGE) {
            if (!PermissionHelper.hasStorage(this)) {
                Toast.makeText(this, R.string.msg_permission_storage_denied, Toast.LENGTH_LONG).show()
            }
        }
    }
}
