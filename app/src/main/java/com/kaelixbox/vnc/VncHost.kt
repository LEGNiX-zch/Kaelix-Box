package com.kaelixbox.vnc

import com.kaelixbox.container.ContainerConfig

/**
 * Cross-component bridge: MainActivity creates the [VncSession] (with the
 * allocated port + container config) and stashes it here; the [VncFragment]
 * reads it on attach. This avoids serialising non-parcelable state through
 * Fragment arguments.
 *
 * Single-slot holder — only one VNC session exists at a time, which also
 * enforces the "single floating-button instance" rule structurally.
 */
object VncHost {
    @Volatile var session: VncSession? = null
    @Volatile var config: ContainerConfig? = null
    @Volatile var onCloseRequested: (() -> Unit)? = null
}
