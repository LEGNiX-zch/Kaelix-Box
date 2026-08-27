package com.kaelixbox.terminal

import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.method.ScrollingMovementMethod
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.kaelixbox.R
import com.kaelixbox.container.ContainerManager
import com.kaelixbox.container.TerminalBus

/**
 * Terminal screen. A scrolling log view + a command input. Subscribes to
 * [TerminalBus] for live proot stdout/stderr; on submit the input line is
 * piped to the live proot stdin.
 *
 * The proot process itself is owned by [ContainerManager] (outlives this
 * Fragment); the Fragment only renders IO, so navigating away and back never
 * loses history and never restarts the container.
 */
class TerminalFragment : Fragment() {

    private var output: TextView? = null
    private var input: EditText? = null
    private var scroll: ScrollView? = null
    private var sub: TerminalBus.Subscription? = null
    private val pending = StringBuilder()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_terminal, container, false)
        output = v.findViewById(R.id.terminal_output)
        input = v.findViewById(R.id.terminal_input)
        scroll = v.findViewById(R.id.terminal_scroll)
        output?.movementMethod = ScrollingMovementMethod()

        input?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                actionId == EditorInfo.IME_ACTION_DONE
            ) {
                submit()
                true
            } else false
        }
        input?.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
            ) {
                submit()
                true
            } else false
        }
        return v
    }

    private fun submit() {
        val et = input ?: return
        val text = et.text.toString()
        if (text.isEmpty() && ContainerManager.isRunning) {
            // user just pressed enter — send newline so shell prompt moves
            ContainerManager.sendInput("")
            et.text?.clear()
            return
        }
        if (!ContainerManager.isRunning) {
            et.text?.clear()
            return
        }
        // Echo locally so the user sees their typed command.
        appendText("$ " + text + "\n")
        ContainerManager.sendInput(text)
        et.text?.clear()
    }

    override fun onResume() {
        super.onResume()
        val out = output ?: return
        sub = TerminalBus.subscribe { chunk ->
            requireActivity().runOnUiThread {
                appendText(chunk.toString())
            }
        }
        appendText(TerminalBus.snapshot())
        postScroll()
    }

    override fun onPause() {
        super.onPause()
        sub?.unsubscribe()
        sub = null
    }

    private fun appendText(text: CharSequence) {
        if (text.isEmpty()) return
        val out = output ?: return
        // Cap the visible text length to avoid OOM on a runaway container.
        val builder = out.text as? SpannableStringBuilder
            ?: SpannableStringBuilder(out.text)
        builder.append(text)
        if (builder.length > MAX_VIEW_CHARS) {
            builder.delete(0, builder.length - MAX_VIEW_CHARS)
        }
        out.text = builder
        postScroll()
    }

    private fun postScroll() {
        scroll?.post {
            scroll?.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    companion object {
        private const val MAX_VIEW_CHARS = 256 * 1024
    }
}
