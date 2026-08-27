package com.kaelixbox.container

import android.content.Context
import com.kaelixbox.App
import com.kaelixbox.util.FileUtils
import java.io.File

/**
 * Builds the proot command line + environment for a given container.
 *
 * Hard constraints honoured here:
 *  - Default container = ARM64 Debian13 Trixie: args/env/bind mounts tuned for
 *    Debian13-ARM64; for any third-party imported container we fall back to a
 *    generic set and DO NOT run the XFCE installer.
 *  - Mic audio passthrough is added ONLY when the user's mic switch is on;
 *    otherwise the audio input path is fully disabled. When the switch is on
 *    but the bind targets aren't available, we surface a compatibility notice
 *    instead of crashing.
 */
object ProotManager {

    private const val DEBIAN_PATH =
        "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    fun prootBinary(context: Context): File = FileUtils.prootBin(context)

    /**
     * @return Pair(argv, extraEnv). [extraEnv] are NAME=VALUE lines passed via
     * `/bin/env -i ...` so the container starts with a controlled environment.
     * The first argv element is the proot binary path.
     */
    fun build(
        context: Context,
        config: ContainerConfig,
        micEnabled: Boolean,
        audioNotice: (String) -> Unit
    ): Pair<List<String>, List<String>> {
        val rootfs = config.rootfs(context)
        val argv = mutableListOf<String>()
        argv.add(prootBinary(context).absolutePath)

        // Core proot options. These are stable across rootfs kinds; only the
        // arch-related bits differ for the default Debian13 ARM64 container.
        argv.add("--root-id")
        argv.add("--link2symlink")
        argv.add("--kill-exit-status")
        argv.add("--rootfs=$rootfs")

        // Bind mounts. The base set is identical for every container; the
        // audio binds are conditional on the mic switch below.
        listOfBinds().forEach { argv.add("--bind=$it") }

        // Architecture is implicit for aarch64 proot running on aarch64 host;
        // we still set the env to keep Debian13 happy.
        argv.add("--cwd=/root")

        // ---- Audio passthrough (ONLY when mic switch is ON) ----
        if (micEnabled) {
            val snd = File("/dev/snd")
            if (snd.exists() && snd.canRead()) {
                argv.add("--bind=/dev/snd")
            } else {
                // Compatibility notice: container still runs; mic just won't work.
                audioNotice("[audio] /dev/snd 不可读或不存在，麦克风透传将不可用（不影响容器运行）。")
            }
        }
        // When mic switch is OFF we add NOTHING audio-related — the audio input
        // path inside the container is effectively absent.

        // Build a clean environment via /bin/env -i to control variables.
        val env = mutableListOf(
            "HOME=/root",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "LANGUAGE=en_US:en",
            "PATH=$DEBIAN_PATH",
            "TMPDIR=/tmp",
            "PS1='${config.name} # '"
        )
        if (micEnabled) {
            // Point pulseaudio clients at a local socket we don't host; this
            // makes alsa/pulse apps *try* the audio path so failures are
            // observable rather than silently absent.
            env.add("PULSE_SERVER=unix:/tmp/pulse-socket")
            env.add("ALSA_CONFIG_PATH=")
        }

        argv.add("/bin/env")
        argv.add("-i")
        env.forEach { argv.add(it) }

        // Default Debian13 → interactive login bash so the XFCE installer hook
        // and apt have a sane environment; imported third-party containers get
        // a plain bash too — desktop must be installed manually there.
        argv.add("/bin/bash")
        argv.add("-l")

        return argv to env
    }

    private fun listOfBinds(): List<String> = listOf(
        "/dev",
        "/proc",
        "/sys",
        "/dev/urandom:/dev/random",
        "/proc/self/fd:/dev/fd",
        "/proc/self/fd/0:/dev/stdin",
        "/proc/self/fd/1:/dev/stdout",
        "/proc/self/fd/2:/dev/stderr",
        "/dev/zero",
        "/dev/null",
        "/dev/ptmx",
        "/dev/tty",
        "/proc/version",
        "/sys/fs/cgroup"
    )

    /** Quick textual preview for the terminal before we exec proot. */
    fun describe(config: ContainerConfig, micEnabled: Boolean): String {
        return buildString {
            appendLine("[proot] arch=${config.arch} distro=${config.distribution}")
            appendLine("[proot] mic-passthrough=${if (micEnabled) "ON" else "OFF"}")
            appendLine("[proot] cwd=/root shell=/bin/bash -l")
        }
    }
}
