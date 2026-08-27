package com.kaelixbox.container

import android.content.Context
import com.kaelixbox.util.FileUtils
import java.io.File

/**
 * Runs the in-container XFCE4 + tightvncserver bootstrap for the DEFAULT
 * Debian13 Trixie ARM64 container ONLY.
 *
 * Constraints:
 *  - the bash script is embedded as a Kotlin string (no external files),
 *  - runs only for the default Debian13 container; any third-party imported
 *    container is left untouched (user installs desktop manually),
 *  - generates a marker file under the rootfs so we never reinstall on every
 *    startup; all stdout/stderr is printed back to the terminal because the
 *    script is piped through the running proot's stdin (which streams out via
 *    [TerminalBus]).
 */
object XFCEInstaller {

    private const val MARKER_REL = "root/.kaelix-xfce-installed"

    fun isInstalled(context: Context, cfg: ContainerConfig): Boolean {
        if (!cfg.isDefaultDebian13) return false
        return File(FileUtils.rootfsDir(context, cfg.id), MARKER_REL).exists()
    }

    fun markInstalled(context: Context, cfg: ContainerConfig) {
        if (!cfg.isDefaultDebian13) return
        File(FileUtils.rootfsDir(context, cfg.id), MARKER_REL).apply {
            parentFile?.mkdirs()
            writeText("installed\n")
        }
    }

    /** Emit the install pipeline. Caller pipes this to the live proot stdin. */
    fun scriptFor(cfg: ContainerConfig, vncDisplay: Int, vncPassword: String, width: Int, height: Int): String {
        if (!cfg.isDefaultDebian13) return ""
        return INSTALL_TEMPLATE
            .replace("__WIDTH__", width.toString())
            .replace("__HEIGHT__", height.toString())
            .replace("__DISPLAY__", vncDisplay.toString())
            .replace("__PASS__", vncPassword)
    }

    /**
     * Inline installer. Sends the script to the live proot process via stdin
     * so all output is captured by the terminal. Idempotent: skipped if the
     * marker file already exists.
     */
    fun runOnceIfDefault(context: Context, cfg: ContainerConfig, w: Int = 1280, h: Int = 720) {
        if (!cfg.isDefaultDebian13) return
        if (isInstalled(context, cfg)) {
            TerminalBus.appendLine("[xfce] 已安装，跳过。")
            return
        }
        TerminalBus.appendLine("[xfce] 开始后台安装 XFCE4 + tightvncserver + 中文字体…")
        val script = scriptFor(cfg, cfg.vncDisplay, cfg.vncPassword, w, h)
        // Pipe the script into the running shell. Use a heredoc so it survives
        // line buffering.
        val wrapped = "cat <<'KAELIX_EOF' | bash\n$script\nKAELIX_EOF\n"
        ContainerManager.execRaw(wrapped)
    }

    /**
     * The script. Kept dependency-light; tightvncserver is used (per spec).
     * Fonts-noto-cjk gives Chinese rendering. VNC resolution + password are
     * configured to match what the APP side VNC client will connect to.
     */
    private val INSTALL_TEMPLATE = """#!/bin/bash
set -e
echo '[xfce] apt update ...'
export DEBIAN_FRONTEND=noninteractive
apt-get update -y || true
echo '[xfce] installing packages (this can take a while) ...'
apt-get install -y --no-install-recommends \
    xfce4 xfce4-goodies tightvncserver fonts-noto-cjk dbus-x11 \
    xterm sudo procps ca-certificates
echo '[xfce] configuring tightvncserver ...'
mkdir -p /root/.vnc
cat > /root/.vnc/xstartup <<'XSTARTUP'
#!/bin/sh
unset SESSION_MANAGER
unset DBUS_SESSION_BUS_ADDRESS
export XKL_XMODMAP_DISABLE=1
exec startxfce4
XSTARTUP
chmod +x /root/.vnc/xstartup
# Default geometry & depth, plus the password so the APP can connect.
echo '[xfce] setting VNC password ...'
printf '__PASS__\n__PASS__\nn\n' | vncpasswd /root/.vnc/passwd >/dev/null 2>&1 || true
chmod 600 /root/.vnc/passwd 2>/dev/null || true
cat > /root/.vnc/default.env <<'ENV'
export USER=root
export HOME=/root
export XAUTHORITY=/root/.Xauthority
export DISPLAY=:__DISPLAY__
ENV
# Pre-generate a per-session geometry template the launcher can read.
mkdir -p /root/.kaelix
cat > /root/.kaelix/vnc.conf <<CONF
geometry=__WIDTH__x__HEIGHT__
depth=24
display=__DISPLAY__
CONF
echo '[xfce] marking installed ...'
touch /root/.kaelix-xfce-installed
echo '[xfce] 安装完成。点击三角形 VNC 图标即可启动桌面。'
"""
}
