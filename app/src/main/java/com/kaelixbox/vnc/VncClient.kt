package com.kaelixbox.vnc

import com.kaelixbox.container.TerminalBus
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.DESKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal but real RFB 3.8 / VNC client with built-in VNC-Auth.
 *
 * Why a hand-rolled client rather than novnc/websockify/AVNC: per spec the app
 * must render the desktop IN-APP, no web stack, no shelling out to external
 * AVNC, no browser. So we implement the wire protocol directly and paint the
 * framebuffer onto a [VncSurfaceView].
 *
 * Encodings negotiated: Raw, CopyRect, Cursor (pseudo), DesktopSize (pseudo).
 * tightvncserver happily falls back to Raw when the client does not advertise
 * Tight/ZRLE, which keeps the decoder small & robust.
 */
class VncClient(
    private val host: String,
    private val port: Int,
    private val password: String,
    /** Mutable so the view layer can (re)bind once it exists. */
    var onBitmap: (VncFramebuffer) -> Unit,
    private val onCursor: ((CursorShape) -> Unit)? = null,
    private val onConnected: () -> Unit,
    private val onDisconnected: (String?) -> Unit
) {
    private val running = AtomicBoolean(false)
    private val sockRef = AtomicReference<Socket?>(null)
    @Volatile var fbWidth = 0
        private set
    @Volatile var fbHeight = 0
        private set

    private var out: DataOutputStream? = null
    private val outLock = Any()
    @Volatile var closed = false
        private set

    fun start() {
        if (running.getAndSet(true)) return
        Thread({ runLoop() }, "vnc-client").start()
    }

    fun stop(reason: String? = null) {
        closed = true
        running.set(false)
        try { sockRef.get()?.close() } catch (_: Throwable) {}
        onDisconnected(reason)
    }

    private fun runLoop() {
        val sock = Socket()
        sockRef.set(sock)
        try {
            sock.connect(InetSocketAddress(host, port), 8000)
            sock.tcpNoDelay = true
            val inS = DataInputStream(sock.getInputStream())
            val outS = DataOutputStream(sock.getOutputStream()).also { out = it }

            // 1. ProtocolVersion
            val ver = ByteArray(12)
            inS.readFully(ver)
            outS.writeBytes("RFB 003.008\n")
            outS.flush()

            // 2. Security types
            val nTypes = inS.readUnsignedByte()
            val types = ByteArray(nTypes).also { inS.readFully(it) }
            val useVncAuth = types.toList().contains(2)
            // Choose VNC Auth (2) if present, else None (1).
            val chosen = if (useVncAuth) 2 else if (types.toList().contains(1)) 1 else 0
            outS.writeByte(chosen); outS.flush()

            if (chosen == 2) {
                // 16-byte challenge
                val ch = ByteArray(16); inS.readFully(ch)
                val key = vncAuthKey(password)
                val cipher = Cipher.getInstance("DES/ECB/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
                val resp = ByteArray(16)
                System.arraycopy(cipher.doFinal(ch, 0, 8), 0, resp, 0, 8)
                System.arraycopy(cipher.doFinal(ch, 8, 8), 0, resp, 8, 8)
                outS.write(resp); outS.flush()
            }

            // 3. SecurityResult
            val ok = inS.readInt()
            if (ok != 0) {
                stop("VNC 鉴权失败 (密码错误)")
                return
            }

            // 4. ClientInit (shared = 1)
            outS.writeByte(1); outS.flush()

            // 5. ServerInit
            fbWidth = inS.readUnsignedShort()
            fbHeight = inS.readUnsignedShort()
            // server pixel format (16 bytes) + name
            val pf = ByteArray(16); inS.readFully(pf)
            val nameLen = inS.readInt()
            val nameBytes = ByteArray(nameLen); inS.readFully(nameBytes)

            // 6. SetPixelFormat: request 32bpp little-endian true colour, RGB
            // layout (red-shift=16, green=8, blue=0). With LE the on-wire bytes
            // per pixel are [B, G, R, 0], which paintRaw decodes directly.
            val pixFmt = byteArrayOf(
                32,   // bits-per-pixel
                24,   // depth
                0,    // big-endian-flag (0 = little-endian)
                1,    // true-colour-flag
                0xFF.toByte(), 0, // red-max (255)
                0xFF.toByte(), 0, // green-max (255)
                0xFF.toByte(), 0, // blue-max (255)
                16,   // red-shift
                8,    // green-shift
                0,    // blue-shift
                0, 0, 0  // padding
            )
            // Build red-max/green-max/blue-max as 16-bit LE = 0x00FF each
            pixFmt[4] = 0xFF.toByte(); pixFmt[5] = 0
            pixFmt[6] = 0xFF.toByte(); pixFmt[7] = 0
            pixFmt[8] = 0xFF.toByte(); pixFmt[9] = 0
            synchronized(outLock) {
                outS.writeByte(0)             // SetPixelFormat
                outS.writeByte(0); outS.writeShort(0)  // padding
                outS.write(pixFmt)
                outS.flush()
                // 7. SetEncodings: Raw(0), CopyRect(1), Cursor(-239), DesktopSize(-223)
                outS.writeByte(2)             // SetEncodings
                outS.writeByte(4)
                writeEnc(outS, 0)             // Raw
                writeEnc(outS, 1)             // CopyRect
                writeEnc(outS, -239)          // Cursor
                writeEnc(outS, -223)          // DesktopSize
                outS.flush()
            }

            onConnected()

            // 8. Initial full framebuffer request
            requestUpdate(0, 0, fbWidth, fbHeight, incremental = false)

            // 9. Main message loop
            while (running.get() && !closed) {
                val msgType = inS.read()
                if (msgType < 0) break
                when (msgType) {
                    0 -> handleFramebufferUpdate(inS)
                    1 -> handleSetColourMap(inS)
                    2 -> { inS.readUnsignedByte() } // bell
                    3 -> { inS.readFully(ByteArray(inS.readUnsignedShort())) } // server-cut-text
                    -1 -> { /* server closed */ break }
                    else -> { /* skip unknown */ }
                }
            }
            stop("连接已关闭")
        } catch (e: IOException) {
            stop("连接断开: ${e.message ?: "io error"}")
        } catch (e: Throwable) {
            stop("错误: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun writeEnc(out: DataOutputStream, v: Int) {
        out.write(v ushr 24 and 0xFF)
        out.write(v ushr 16 and 0xFF)
        out.write(v ushr 8 and 0xFF)
        out.write(v and 0xFF)
    }

    private fun requestUpdate(x: Int, y: Int, w: Int, h: Int, incremental: Boolean) {
        try {
            synchronized(outLock) {
                out?.apply {
                    writeByte(3)               // FramebufferUpdateRequest
                    writeByte(if (incremental) 1 else 0)
                    writeShort(x); writeShort(y); writeShort(w); writeShort(h)
                    flush()
                }
            }
        } catch (_: IOException) { /* connection dropping */ }
    }

    private fun handleSetColourMap(inS: DataInputStream) {
        inS.readUnsignedShort()            // padding
        val first = inS.readUnsignedShort()
        val n = inS.readUnsignedShort()
        inS.skipBytes(n * 6)
    }

    private fun handleFramebufferUpdate(inS: DataInputStream) {
        inS.readUnsignedByte()             // padding
        val nRects = inS.readUnsignedShort()
        var cursorX = 0; var cursorY = 0
        for (i in 0 until nRects) {
            val x = inS.readUnsignedShort()
            val y = inS.readUnsignedShort()
            val w = inS.readUnsignedShort()
            val h = inS.readUnsignedShort()
            val enc = inS.readInt()
            when (enc) {
                0 -> { // Raw
                    val bytes = w * h * 4
                    val data = ByteArray(bytes)
                    inS.readFully(data)
                    paintRaw(x, y, w, h, data)
                }
                1 -> { // CopyRect
                    val srcX = inS.readUnsignedShort()
                    val srcY = inS.readUnsignedShort()
                    paintCopyRect(x, y, w, h, srcX, srcY)
                }
                -239 -> { // Cursor
                    val w2 = inS.readUnsignedShort(); val h2 = inS.readUnsignedShort()
                    cursorX = inS.readUnsignedShort(); cursorY = inS.readUnsignedShort()
                    inS.readUnsignedShort() // padding
                    val px = ByteArray(w2 * h2 * 4); inS.readFully(px)
                    val mask = ByteArray(((w2 + 7) / 8) * h2); inS.readFully(mask)
                    onCursor?.invoke(CursorShape(w2, h2, cursorX, cursorY, px, mask))
                }
                -223 -> { // DesktopSize
                    fbWidth = w; fbHeight = h
                    onBitmap(VncFramebuffer.Resize(w, h))
                }
                else -> {
                    // Unknown encoding: we can't decode; skip by reading nothing
                    // extra (only Raw/CopyRect carry payloads and we've covered
                    // them). Drop the rect.
                }
            }
        }
        // Request next incremental update covering the whole framebuffer.
        requestUpdate(0, 0, fbWidth, fbHeight, incremental = true)
    }

    @Volatile private var frameBuffer: VncFramebuffer.Raw? = null

    private fun paintRaw(x: Int, y: Int, w: Int, h: Int, data: ByteArray) {
        val cur = frameBuffer ?: VncFramebuffer.Raw(fbWidth, fbHeight,
            IntArray(fbWidth * fbHeight)).also { frameBuffer = it }
        // We requested 32bpp LE RGB: bytes [B][G][R][0] → int 0x00RRGGBB
        val px = cur.pixels
        for (row in 0 until h) {
            val srcOff = row * w * 4
            val dstOff = (y + row) * cur.width + x
            for (col in 0 until w) {
                val o = srcOff + col * 4
                val b = data[o].toInt() and 0xFF
                val g = data[o + 1].toInt() and 0xFF
                val r = data[o + 2].toInt() and 0xFF
                px[dstOff + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        onBitmap(cur)
    }

    private fun paintCopyRect(x: Int, y: Int, w: Int, h: Int, srcX: Int, srcY: Int) {
        val cur = frameBuffer ?: return
        val px = cur.pixels
        // Copy bottom-up to avoid overlap corruption.
        if (srcY <= y) {
            for (row in h - 1 downTo 0) {
                System.arraycopy(px, (srcY + row) * cur.width + srcX,
                    px, (y + row) * cur.width + x, w)
            }
        } else {
            for (row in 0 until h) {
                System.arraycopy(px, (srcY + row) * cur.width + srcX,
                    px, (y + row) * cur.width + x, w)
            }
        }
        onBitmap(cur)
    }

    // ---------- input ----------

    fun sendKeyEvent(keysym: Int, down: Boolean) {
        try {
            synchronized(outLock) {
                out?.apply {
                    writeByte(4)             // key event
                    writeByte(if (down) 1 else 0)
                    writeShort(0); writeShort(0) // padding
                    write(keysym ushr 24 and 0xFF)
                    write(keysym ushr 16 and 0xFF)
                    write(keysym ushr 8 and 0xFF)
                    write(keysym and 0xFF)
                    flush()
                }
            }
        } catch (_: IOException) { }
    }

    fun sendPointerEvent(x: Int, y: Int, mask: Int) {
        try {
            synchronized(outLock) {
                out?.apply {
                    writeByte(5)             // pointer event
                    writeByte(mask)
                    writeShort(x); writeShort(y)
                    flush()
                }
            }
        } catch (_: IOException) { }
    }

    // ---------- helpers ----------

    /** VNC auth key: password truncated to 8 bytes, each byte bit-reversed. */
    private fun vncAuthKey(pw: String): ByteArray {
        val k = ByteArray(8)
        val src = pw.toByteArray().copyOf(8)
        for (i in 0 until 8) k[i] = reverseBits(src[i])
        return k
    }

    private fun reverseBits(b: Byte): Byte {
        var v = b.toInt() and 0xFF
        v = (v and 0xF0 ushr 4) or (v and 0x0F shl 4)
        v = (v and 0xCC ushr 2) or (v and 0x33 shl 2)
        v = (v and 0xAA ushr 1) or (v and 0x55 shl 1)
        return v.toByte()
    }
}

/** Incremental framebuffer payloads pushed to the view. */
sealed class VncFramebuffer {
    data class Raw(val width: Int, val height: Int, val pixels: IntArray) : VncFramebuffer()
    data class Resize(val width: Int, val height: Int) : VncFramebuffer()
}

data class CursorShape(
    val w: Int, val h: Int, val hotX: Int, val hotY: Int,
    val rgba: ByteArray, val mask: ByteArray
)
