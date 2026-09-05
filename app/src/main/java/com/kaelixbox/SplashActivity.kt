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
        // 不使用 setItems：MaterialComponents 主题下 setItems 列表项可能不渲染，
        // 导致弹窗只有标题和消息、没有可点击选项。改用显式按钮保证一定可见。
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.splash_choose_title)
            .setMessage(R.string.splash_choose_message)
            .setCancelable(false)

        if (isArm64) {
            builder.setPositiveButton(R.string.splash_choose_download) { _, _ ->
                DownloadActivity.startForDownload(this)
                overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
            }
            builder.setNegativeButton(R.string.splash_choose_import) { _, _ ->
                DownloadActivity.startForImport(this)
                overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
            }
        } else {
            // 32 位设备仅允许本地导入
            builder.setPositiveButton(R.string.splash_choose_import) { _, _ ->
                DownloadActivity.startForImport(this)
                overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
            }
        }

        val dialog = builder.show()
        dialog.window?.attributes?.windowAnimations = R.style.KaelixDialogAnimation
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        finish()
    }
}
