package com.kaelixbox.prefs

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single source of truth for persistent app state backed by SharedPreferences.
 *
 * Holds: mic passthrough flag, container list & current selection, floating FAB
 * last docked coordinates, about-page avatar path & nickname, plus misc flags.
 */
class AppPrefs private constructor(private val sp: SharedPreferences) {

    fun micPassthroughEnabled(): Boolean = sp.getBoolean(KEY_MIC, false)
    fun setMicPassthrough(enabled: Boolean) = sp.edit().putBoolean(KEY_MIC, enabled).apply()

    var currentContainerId: String
        get() = sp.getString(KEY_CURRENT_CONTAINER, "") ?: ""
        set(value) = sp.edit().putString(KEY_CURRENT_CONTAINER, value).apply()

    /** Persisted containers as a JSON array string. */
    var containersJson: String
        get() = sp.getString(KEY_CONTAINERS, "[]") ?: "[]"
        set(value) = sp.edit().putString(KEY_CONTAINERS, value).apply()

    var fabX: Float
        get() = sp.getFloat(KEY_FAB_X, -1f)
        set(v) = sp.edit().putFloat(KEY_FAB_X, v).apply()

    var fabY: Float
        get() = sp.getFloat(KEY_FAB_Y, -1f)
        set(v) = sp.edit().putFloat(KEY_FAB_Y, v).apply()

    var nickname: String
        get() = sp.getString(KEY_NICKNAME, "") ?: ""
        set(v) = sp.edit().putString(KEY_NICKNAME, v).apply()

    var avatarPath: String
        get() = sp.getString(KEY_AVATAR, "") ?: ""
        set(v) = sp.edit().putString(KEY_AVATAR, v).apply()

    fun batteryPromptShown(): Boolean = sp.getBoolean(KEY_BATTERY_PROMPT, false)
    fun markBatteryPromptShown() = sp.edit().putBoolean(KEY_BATTERY_PROMPT, true).apply()

    fun defaultImageInstalled(): Boolean = sp.getBoolean(KEY_DEFAULT_IMAGE, false)
    fun markDefaultImageInstalled(v: Boolean) = sp.edit().putBoolean(KEY_DEFAULT_IMAGE, v).apply()

    fun wasKilledBySystem(): Boolean = sp.getBoolean(KEY_KILLED_FLAG, false)
    fun setKilledBySystem(v: Boolean) = sp.edit().putBoolean(KEY_KILLED_FLAG, v).apply()

    /** 自定义镜像下载加速地址，留空使用 aka.ms 默认加速。 */
    var mirrorUrl: String
        get() = sp.getString(KEY_MIRROR_URL, "") ?: ""
        set(v) = sp.edit().putString(KEY_MIRROR_URL, v).apply()

    companion object {
        private const val KEY_MIC = "mic_passthrough"
        private const val KEY_CURRENT_CONTAINER = "current_container_id"
        private const val KEY_CONTAINERS = "containers_json"
        private const val KEY_FAB_X = "fab_x"
        private const val KEY_FAB_Y = "fab_y"
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_AVATAR = "avatar_path"
        private const val KEY_BATTERY_PROMPT = "battery_prompt_shown"
        private const val KEY_DEFAULT_IMAGE = "default_image_installed"
        private const val KEY_KILLED_FLAG = "killed_by_system"
        private const val KEY_MIRROR_URL = "mirror_url"

        @Volatile private var instance: AppPrefs? = null
        fun get(context: Context): AppPrefs {
            return instance ?: synchronized(this) {
                instance ?: AppPrefs(
                    context.applicationContext
                        .getSharedPreferences("kaelix_box_prefs", Context.MODE_PRIVATE)
                ).also { instance = it }
            }
        }

        /** Helper: rebuild a fresh JSON containers array. */
        fun encodeContainers(list: List<JSONObject>): String {
            val arr = JSONArray()
            list.forEach { arr.put(it) }
            return arr.toString()
        }
    }
}
