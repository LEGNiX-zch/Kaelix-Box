package com.kaelixbox.vnc

import android.app.AlertDialog
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.kaelixbox.App
import com.kaelixbox.MainActivity
import com.kaelixbox.R
import com.kaelixbox.container.TerminalBus
import com.kaelixbox.databinding.FragmentVncBinding
import com.kaelixbox.prefs.AppPrefs
import kotlin.math.max
import kotlin.math.min

/**
 * Hosts the in-app VNC rendering surface + the floating control button.
 *
 * Lifecycle: created when the user taps the triangle VNC icon, torn down
 * when the user picks "返回退出VNC" from the FAB menu, when the container
 * dies (ProcessMonitor → MainActivity.closeVnc), or when VNC disconnects.
 *
 * Rotation: MainActivity declares configChanges=orientation|screenSize so
 * Android does NOT recreate this fragment on rotate — we keep the live
 * session and just re-layout, which is what prevents VNC/FAB leaks across
 * config changes.
 */
class VncFragment : Fragment() {

    private var _b: FragmentVncBinding? = null
    private val b get() = _b!!
    private var fab: FloatingControlsView? = null
    private var zoomDialog: AlertDialog? = null
    private var extDialog: AlertDialog? = null

    private val session get() = VncHost.session

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentVncBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val s = session ?: run {
            (activity as? MainActivity)?.closeVnc()
            return
        }
        s.onClientCreated = { client ->
            requireActivity().runOnUiThread {
                b.vncView.bind(client)
                showFab()
            }
        }
        // Route framebuffer updates to the surface view. onBitmap runs on the
        // VncClient IO thread; the view's updateFramebuffer posts to the UI
        // thread itself.
        s.onBitmap = { fb -> b.vncView.updateFramebuffer(fb) }
        // Surface the password once more in case the user missed it.
        TerminalBus.appendLine("[vnc] 等待服务端握手…")
    }

    // ---------------- floating control button ----------------

    private fun showFab() {
        if (fab != null) return  // single-instance guard
        val act = activity ?: return
        val root = act.findViewById<FrameLayout>(android.R.id.content) ?: return
        val view = FloatingControlsView(act).apply {
            val size = resources.getDimensionPixelSize(R.dimen.fab_size)
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                val prefs = AppPrefs.get(act)
                val x = if (prefs.fabX >= 0) prefs.fabX.toInt() else root.width - size - 24
                val y = if (prefs.fabY >= 0) prefs.fabY.toInt() else root.height - size - 80
                gravity = Gravity.TOP or Gravity.START
                leftMargin = x.coerceAtLeast(0)
                topMargin = y.coerceAtLeast(0)
                enableDrag(x.toFloat(), y.toFloat())
            }
            installDrag(root)
            onNormalKeyboard = { showIme() }
            onExtendedKeyboard = { showExtKeyboard() }
            onZoom = { showZoomPanel() }
            onExit = { (activity as? MainActivity)?.closeVnc() }
        }
        root.addView(view)
        fab = view
    }

    private fun destroyFab() {
        val f = fab ?: return
        val act = activity ?: return
        val (x, y) = f.docked()
        AppPrefs.get(act).fabX = x
        AppPrefs.get(act).fabY = y
        (act.findViewById<FrameLayout>(android.R.id.content))?.removeView(f)
        fab = null
    }

    // ---------------- input helpers ----------------

    private fun showIme() {
        b.vncView.requestFocus()
        val imm = requireContext()
            .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(b.vncView, InputMethodManager.SHOW_FORCED)
    }

    private fun showZoomPanel() {
        val act = activity ?: return
        val view = layoutInflater.inflate(R.layout.dialog_zoom, null)
        val value = view.findViewById<TextView>(R.id.zoom_value)
        fun render() {
            val pct = (b.vncView.zoom * 100).toInt()
            value.text = "$pct%"
        }
        render()
        view.findViewById<Button>(R.id.zoom_out).setOnClickListener {
            b.vncView.setZoom(max(0.5f, b.vncView.zoom - 0.25f)); render()
        }
        view.findViewById<Button>(R.id.zoom_in).setOnClickListener {
            b.vncView.setZoom(min(6f, b.vncView.zoom + 0.25f)); render()
        }
        zoomDialog?.dismiss()
        zoomDialog = AlertDialog.Builder(act).setView(view)
            .setOnDismissListener { render() }
            .setNeutralButton(R.string.ok) { d, _ -> d.dismiss() }
            .create().also { it.show() }
    }

    // ---------------- extended keyboard ----------------

    private data class SpecialKey(val label: String, val keysym: Int, val sticky: Boolean = false)

    private val keys = listOf(
        SpecialKey("Esc", 0xFF1B),
        SpecialKey("Tab", 0xFF09),
        SpecialKey("Ctrl", 0xFFE3, sticky = true),
        SpecialKey("Alt", 0xFFE9, sticky = true),
        SpecialKey("Shift", 0xFFE1, sticky = true),
        SpecialKey("⌫", 0xFF08),
        SpecialKey("⏎", 0xFF0D),
        SpecialKey("↑", 0xFF52),
        SpecialKey("↓", 0xFF54),
        SpecialKey("←", 0xFF51),
        SpecialKey("→", 0xFF53),
        SpecialKey("F1", 0xFFBE), SpecialKey("F2", 0xFFBF), SpecialKey("F3", 0xFFC0),
        SpecialKey("F4", 0xFFC1), SpecialKey("F5", 0xFFC2), SpecialKey("F6", 0xFFC3),
        SpecialKey("F7", 0xFFC4), SpecialKey("F8", 0xFFC5), SpecialKey("F9", 0xFFC6),
        SpecialKey("F10", 0xFFC7), SpecialKey("F11", 0xFFC8), SpecialKey("F12", 0xFFC9)
    )

    private val stickyState = HashSet<Int>()
    @Volatile private var lastKeySentAt = 0L

    private fun showExtKeyboard() {
        val act = activity ?: return
        val grid = layoutInflater.inflate(R.layout.dialog_ext_keyboard, null)
            .findViewById<GridLayout>(R.id.keys_grid)
        grid.removeAllViews()
        val size = resources.getDimensionPixelSize(R.dimen.ext_key_size)
        keys.forEach { k ->
            val btn = Button(act).apply {
                text = k.label
                layoutParams = GridLayout.LayoutParams().apply {
                    width = size; height = size
                    setMargins(4, 4, 4, 4)
                }
                setOnClickListener { sendSpecial(k) }
            }
            grid.addView(btn)
        }
        extDialog?.dismiss()
        extDialog = AlertDialog.Builder(act).setView(grid)
            .setTitle(R.string.ext_keyboard_title)
            .setOnDismissListener {
                // release any held sticky modifiers
                stickyState.forEach { ks -> session?.client()?.sendKeyEvent(ks, false) }
                stickyState.clear()
            }
            .setNeutralButton(R.string.ok, null)
            .create().also { it.show() }
    }

    private fun sendSpecial(k: SpecialKey) {
        // Debounce: drop repeats within the debounce window so a fast double
        // tap doesn't fire the key twice.
        val now = System.currentTimeMillis()
        if (now - lastKeySentAt < 90) return
        lastKeySentAt = now
        val c = session?.client() ?: return
        if (k.sticky) {
            val held = stickyState.contains(k.keysym)
            c.sendKeyEvent(k.keysym, !held)
            if (held) stickyState.remove(k.keysym) else stickyState.add(k.keysym)
        } else {
            c.sendKeyEvent(k.keysym, true)
            // brief up-event so single-shot keys don't auto-repeat
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                c.sendKeyEvent(k.keysym, false)
            }, 60)
        }
    }

    // ---------------- teardown ----------------

    override fun onDestroyView() {
        super.onDestroyView()
        zoomDialog?.dismiss(); zoomDialog = null
        extDialog?.dismiss(); extDialog = null
        destroyFab()
        _b = null
    }
}
