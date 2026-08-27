package com.kaelixbox.container

import android.content.Context
import com.kaelixbox.util.FileUtils
import org.json.JSONObject

/**
 * One persistent container instance.
 *
 * `isDefaultDebian13` distinguishes the auto-installed default container (for
 * which the XFCE install script + proot args are tuned) from any third-party
 * tar the user imports (no auto desktop, generic args).
 */
data class ContainerConfig(
    val id: String,
    val name: String,
    val arch: String,           // e.g. "arm64" / "aarch64"
    val distribution: String,   // "debian13" / "custom"
    val isDefaultDebian13: Boolean,
    val vncPassword: String,
    val vncDisplay: Int = 1,
    val vncPort: Int = 5901,
    val xfceInstalled: Boolean = false
) {
    fun rootfs(context: Context): String = FileUtils.rootfsDir(context, id).absolutePath

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("arch", arch)
        put("distribution", distribution)
        put("isDefaultDebian13", isDefaultDebian13)
        put("vncPassword", vncPassword)
        put("vncDisplay", vncDisplay)
        put("vncPort", vncPort)
        put("xfceInstalled", xfceInstalled)
    }

    companion object {
        fun fromJson(o: JSONObject): ContainerConfig = ContainerConfig(
            id = o.optString("id"),
            name = o.optString("name"),
            arch = o.optString("arch", "arm64"),
            distribution = o.optString("distribution", "custom"),
            isDefaultDebian13 = o.optBoolean("isDefaultDebian13", false),
            vncPassword = o.optString("vncPassword", "kaelix"),
            vncDisplay = o.optInt("vncDisplay", 1),
            vncPort = o.optInt("vncPort", 5901),
            xfceInstalled = o.optBoolean("xfceInstalled", false),
        )

        /** Default Debian13 Trixie ARM64 container descriptor. */
        const val DEFAULT_NAME = "Debian13 Trixie (ARM64)"
        const val DEFAULT_DISTRO = "debian13"
        const val DEFAULT_ARCH = "arm64"
        const val DEFAULT_VNC_PASS = "kaelix"
    }
}
