package com.kaelixbox.vnc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * In-app VNC renderer. Holds the framebuffer [Bitmap], paints it with a
 * zoom/pan transform, and translates single-finger taps/drags into RFB
 * PointerEvents forwarded to the [VncClient].
 *
 * Zoom pivot rule (per spec): scaling pivots on the *server-reported remote
 * mouse coordinate* (the last pointer position we sent), NEVER the raw touch
 * coordinate — this prevents the desktop from shifting off under the cursor
 * when the user pinches. Single-finger drag pans the view only when zoomed;
 * when un-zoomed it moves the remote pointer directly.
 */
class VncSurfaceView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var client: VncClient? = null
    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { isFilterBitmap = false }

    @Volatile var zoom: Float = 1f
        private set
    private var panX = 0f
    private var panY = 0f

    // last remote (server-side) mouse coordinates we sent
    private var lastPointerRemoteX = 0f
    private var lastPointerRemoteY = 0f

    private var btnMask = 0

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = true
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            // Pivot on the remote mouse point so the desktop under the cursor
            // stays anchored (NOT on the touch midpoint).
            val newZoom = (zoom * detector.scaleFactor).coerceIn(0.5f, 6f)
            applyZoomPivot(newZoom)
            return true
        }
    })

    private fun applyZoomPivot(newZoom: Float) {
        val screenPivotX = lastPointerRemoteX * zoom + panX
        val screenPivotY = lastPointerRemoteY * zoom + panY
        zoom = newZoom
        panX = screenPivotX - lastPointerRemoteX * zoom
        panY = screenPivotY - lastPointerRemoteY * zoom
        clampPan()
        invalidate()
    }

    fun setZoom(target: Float) {
        applyZoomPivot(target.coerceIn(0.5f, 6f))
    }

    fun bind(client: VncClient) { this.client = client }

    fun updateFramebuffer(fb: VncFramebuffer) {
        post {
            when (fb) {
                is VncFramebuffer.Raw -> {
                    var bmp = bitmap
                    if (bmp == null || bmp.width != fb.width || bmp.height != fb.height) {
                        bmp = Bitmap.createBitmap(fb.width, fb.height, Bitmap.Config.ARGB_8888)
                        bitmap = bmp
                    }
                    bmp.setPixels(fb.pixels, 0, fb.width, 0, 0, fb.width, fb.height)
                    invalidate()
                }
                is VncFramebuffer.Resize -> {
                    bitmap?.recycle()
                    bitmap = Bitmap.createBitmap(fb.width, fb.height, Bitmap.Config.ARGB_8888)
                    invalidate()
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val bmp = bitmap ?: return
        val src = Rect(0, 0, bmp.width, bmp.height)
        val dst = Rect(
            panX.roundToInt(),
            panY.roundToInt(),
            (panX + bmp.width * zoom).roundToInt(),
            (panY + bmp.height * zoom).roundToInt()
        )
        canvas.drawBitmap(bmp, src, dst, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                btnMask = 1 // left button down
                sendPointerAt(event.x, event.y, btnMask)
            }
            MotionEvent.ACTION_MOVE -> {
                if (zoom > 1.01f) {
                    // Pan the view while zoomed.
                    panX += event.x - lastTouchX
                    panY += event.y - lastTouchY
                    clampPan()
                    invalidate()
                } else {
                    sendPointerAt(event.x, event.y, btnMask)
                }
            }
            MotionEvent.ACTION_UP -> {
                btnMask = 0
                sendPointerAt(event.x, event.y, 0)
            }
        }
        lastTouchX = event.x
        lastTouchY = event.y
        return true
    }

    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private fun sendPointerAt(screenX: Float, screenY: Float, mask: Int) {
        val rx = ((screenX - panX) / zoom).roundToInt().coerceIn(0, (bitmap?.width ?: 1) - 1)
        val ry = ((screenY - panY) / zoom).roundToInt().coerceIn(0, (bitmap?.height ?: 1) - 1)
        lastPointerRemoteX = rx.toFloat()
        lastPointerRemoteY = ry.toFloat()
        client?.sendPointerEvent(rx, ry, mask)
    }

    private fun clampPan() {
        val bmp = bitmap ?: return
        val vw = width.toFloat(); val vh = height.toFloat()
        val bw = bmp.width * zoom; val bh = bmp.height * zoom
        panX = if (bw <= vw) (vw - bw) / 2 else panX.coerceIn(vw - bw, 0f)
        panY = if (bh <= vh) (vh - bh) / 2 else panY.coerceIn(vh - bh, 0f)
        if (abs(panX) > 100000f) panX = 0f
        if (abs(panY) > 100000f) panY = 0f
    }

    fun sendKey(keysym: Int, down: Boolean) {
        client?.sendKeyEvent(keysym, down)
    }

    fun resetTransform() {
        zoom = 1f; panX = 0f; panY = 0f; invalidate()
    }
}
