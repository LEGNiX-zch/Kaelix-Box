package com.kaelixbox.vnc

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import com.kaelixbox.R
import kotlin.math.abs

/**
 * The single-instance, draggable, semi-transparent floating control button.
 *
 * Rules enforced:
 *  - it is created/destroyed together with the VNC view; never shown on the
 *    terminal / settings / about screens,
 *  - finger-draggable to anywhere on screen; the last docked coordinates are
 *    persisted so the next VNC session restores the same spot,
 *  - tapping opens a 4-option menu (普通键盘 / 扩展键盘 / 缩放 / 返回退出VNC).
 *
 * Only one instance is ever created per VNC session; the host VncFragment owns
 * the single reference and tears it down on exit.
 */
class FloatingControlsView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    init {
        setBackgroundResource(R.drawable.bg_fab)
        alpha = 0.78f
        setOnClickListener { openMenu() }
    }

    var onNormalKeyboard: (() -> Unit)? = null
    var onExtendedKeyboard: (() -> Unit)? = null
    var onZoom: (() -> Unit)? = null
    var onExit: (() -> Unit)? = null

    private fun openMenu() {
        val pm = PopupMenu(context, this, Gravity.END)
        pm.menu.add(0, 1, 0, context.getString(R.string.ext_keyboard_title).let { "普通键盘" })
        pm.menu.add(0, 2, 0, context.getString(R.string.ext_keyboard_title))
        pm.menu.add(0, 3, 0, context.getString(R.string.zoom_title))
        pm.menu.add(0, 4, 0, "返回退出VNC")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { onNormalKeyboard?.invoke(); true }
                2 -> { onExtendedKeyboard?.invoke(); true }
                3 -> { onZoom?.invoke(); true }
                4 -> { onExit?.invoke(); true }
                else -> false
            }
        }
        pm.show()
    }

    // ---- drag handling (attached by the host via setOnTouchListener) ----
    fun enableDrag(restoredX: Float, restoredY: Float) {
        dragX = restoredX
        dragY = restoredY
    }

    private var dragX = -1f
    private var dragY = -1f
    private var rawStartX = 0f
    private var rawStartY = 0f
    private var moved = false

    fun installDrag(parent: ViewGroup) {
        setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    rawStartX = e.rawX; rawStartY = e.rawY; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - rawStartX
                    val dy = e.rawY - rawStartY
                    if (abs(dx) > 4 || abs(dy) > 4) moved = true
                    if (moved) {
                        val lp = layoutParams as? FrameLayout.LayoutParams
                            ?: return@setOnTouchListener true
                        lp.leftMargin = (e.rawX - width / 2).toInt().coerceAtLeast(0)
                        lp.topMargin = (e.rawY - height / 2).toInt().coerceAtLeast(0)
                        dragX = lp.leftMargin.toFloat()
                        dragY = lp.topMargin.toFloat()
                        layoutParams = lp
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) performClick()
                    dragX.let { /* persist handled by host on exit */ }
                }
            }
            true
        }
    }

    fun docked(): Pair<Float, Float> = dragX to dragY
}
