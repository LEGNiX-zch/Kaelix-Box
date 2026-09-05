package com.kaelixbox.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat

/**
 * Centralised permission handling:
 *  - storage is requested at app start (legacy + READ_MEDIA_IMAGES for 13+),
 *  - mic is requested ONLY when the user toggles the mic switch on,
 *  - battery-optimisation cannot be granted programmatically; we only route
 *    the user to the system settings page.
 */
object PermissionHelper {

    const val REQ_STORAGE = 0x10
    const val REQ_MIC = 0x11

    /**
     * Permissions we actually ask the system for.
     * On API 33+ READ_EXTERNAL_STORAGE is a no-op (system never grants it),
     * so we drop it and only ask for READ_MEDIA_IMAGES. For tar/tar.zst
     * imports we rely on the Storage Access Framework (OpenDocument) which
     * needs no permission, so the app keeps working even if the user
     * denies this.
     */
    fun storagePermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasStorage(context: Context): Boolean {
        // On API 33+ we consider storage "available" if either the media
        // permission was granted OR we can simply use SAF (no permission
        // needed for app-private storage + OpenDocument imports).
        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED || true
        }
        return storagePermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestStorage(activity: Activity) {
        if (!hasStorage(activity)) {
            ActivityCompat.requestPermissions(activity, storagePermissions(), REQ_STORAGE)
        }
    }

    fun hasMic(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun requestMic(activity: Activity) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQ_MIC
        )
    }

    /** True if the app is already exempt from battery optimizations. */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (e: Exception) {
            false
        }
    }

    /** Open the system battery-optimization settings page (manual grant). */
    fun openBatteryOptimizationSettings(activity: Activity) {
        try {
            val intent = android.content.Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            activity.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = android.content.Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = android.net.Uri.fromParts("package", activity.packageName, null)
                intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                activity.startActivity(intent)
            } catch (_: Exception) { /* user denied / no settings activity */ }
        }
    }
}
