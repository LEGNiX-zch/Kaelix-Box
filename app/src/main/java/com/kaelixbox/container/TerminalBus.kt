package com.kaelixbox.container

import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory bridge between the proot process IO threads and the terminal UI.
 *
 * Decoupling the process IO from the Fragment lifecycle is deliberate: when
 * the user navigates terminal → settings → terminal, the proot process keeps
 * running and emitting lines; the active subscriber (if any) replays the
 * trailing buffer plus the live stream.
 */
object TerminalBus {

    const val MAX_BUFFER = 256 * 1024

    private val lock = Any()
    private val buffer = StringBuilder(MAX_BUFFER)
    private val listeners = CopyOnWriteArrayList<(CharSequence) -> Unit>()
    private val lineListeners = CopyOnWriteArrayList<(String, Boolean) -> Unit>()

    fun append(text: String, isError: Boolean = false) {
        if (text.isEmpty()) return
        synchronized(lock) {
            buffer.append(text)
            if (buffer.length > MAX_BUFFER) {
                buffer.delete(0, buffer.length - MAX_BUFFER)
            }
        }
        listeners.forEach { runCatching { it(text) } }
        // Also emit line-by-line for typed listeners.
        text.split('\n').forEach { line ->
            lineListeners.forEach { runCatching { it(line, isError) } }
        }
    }

    fun appendLine(text: String, isError: Boolean = false) =
        append(text + "\n", isError)

    fun snapshot(): String = synchronized(lock) { buffer.toString() }

    fun subscribe(listener: (CharSequence) -> Unit): Subscription {
        listeners.add(listener)
        // replay current snapshot so a freshly attached terminal sees history
        synchronized(lock) { runCatching { listener(buffer) } }
        return Subscription { listeners.remove(listener) }
    }

    fun clear() {
        synchronized(lock) { buffer.setLength(0) }
    }

    class Subscription internal constructor(private val detach: () -> Unit) {
        fun unsubscribe() = detach()
    }
}
