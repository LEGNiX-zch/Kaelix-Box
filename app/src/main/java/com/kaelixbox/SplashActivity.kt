package com.kaelixbox

import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.kaelixbox.container.ContainerManager
import com.kaelixbox.container.ImageConfig
import com.kaelixbox.prefs.AppPrefs

/**
 * 启动页：纯白背景 + 简洁 logo 淡入动画。
 * 首次启动（无容器）弹出镜像来源选择对话框；已有容器则直接进入主页。
 */
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val logo = findViewById<TextView>(R.id.splash_logo)
        val name = findViewById<TextView>(R.id.app_name)

        // 简洁淡入动画
        listOf(logo, name).forEachIndexed { i, v ->
            ObjectAnimator.ofFloat(v, "alpha", 0f, 1f).apply {
                duration = 600
                startDelay = i * 200L
                interpolator = AccelerateDecelerateInterpolator()
            }.start()
        }

        // 动画结束后判定是否需要选择镜像
        logo.postDelayed({ decideFlow() }, 1000)
    }

    private fun decideFlow() {
        val prefs = AppPrefs.get(this)
        // 已有容器或已安装默认镜像 → 直接进主页
        if (prefs.defaultImageInstalled() || ContainerManager.listContainers(this).isNotEmpty()) {
            goMain()
            return
        }
        showImageChoiceDialog()
    }

    private fun showImageChoiceDialog() {
        val isArm64 = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }
        val items = if (isArm64) {
            arrayOf(
                getString(R.string.splash_choose_download),
                getString(R.string.splash_choose_import)
            )
        } else {
            // 32 位设备仅允许本地导入
            arrayOf(getString(R.string.splash_choose_import))
        }
        // 注意：不使用自定义主题 KaelixAlertDialog，
        // 该主题继承自 MaterialComponents.Dialog.Alert，与 setItems 列表不兼容，
        // 会导致选项列表不可见。改用默认主题，动画通过 windowAnimations 应用。
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.splash_choose_title)
            .setMessage(R.string.splash_choose_message)
            .setCancelable(false)
            .setItems(items) { _, which ->
                if (isArm64 && which == 0) {
                    // 在线下载
                    DownloadActivity.startForDownload(this)
                } else {
                    // 本地导入
                    DownloadActivity.startForImport(this)
                }
                overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
            }
            .show()
        // 应用弹出/消失动画
        dialog.window?.attributes?.windowAnimations = R.style.KaelixDialogAnimation
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        finish()
    }
}
