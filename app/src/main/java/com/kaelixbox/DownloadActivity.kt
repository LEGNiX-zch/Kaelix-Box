package com.kaelixbox

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.kaelixbox.container.ContainerConfig
import com.kaelixbox.container.ContainerManager
import com.kaelixbox.container.ImageConfig
import com.kaelixbox.container.ImageInstaller
import com.kaelixbox.container.TerminalBus
import com.kaelixbox.databinding.ActivityDownloadBinding
import com.kaelixbox.prefs.AppPrefs
import com.kaelixbox.util.FileUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DecimalFormat

/**
 * 独立的下载/解压进度页面。
 *
 * 两种模式：
 *  - MODE_DOWNLOAD：在线下载 → SHA256 校验 → 解压
 *  - MODE_IMPORT：本地选择文件 → 解压（跳过 SHA256）
 *
 * 任务运行在独立 CoroutineScope，切换页面不会杀死后台任务。
 * 下载/解压过程中相关按钮置灰锁定，完成后弹窗提示并解锁主页。
 */
class DownloadActivity : AppCompatActivity() {

    private lateinit var b: ActivityDownloadBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var heartbeatJob: Job? = null
    private var mode = MODE_DOWNLOAD
    @Volatile private var lastExtractPct = 0

    private val pickFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) startImport(uri) else finishBackToChoice()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDownloadBinding.inflate(layoutInflater)
        setContentView(b.root)

        mode = intent.getIntExtra(EXTRA_MODE, MODE_DOWNLOAD)

        b.btnCancel.setOnClickListener { finishBackToChoice() }
        b.btnRetry.setOnClickListener { startTask() }
        b.btnEnter.setOnClickListener { enterMain() }

        if (mode == MODE_IMPORT) {
            pickFile.launch(arrayOf("*/*"))
        } else {
            startTask()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopExtractHeartbeat()
        // 不取消 job，保持后台任务继续运行（仅在用户主动取消时才取消）
    }

    // ---------------- 在线下载 ----------------

    private fun startTask() {
        b.btnRetry.visibility = View.GONE
        b.btnEnter.visibility = View.GONE
        b.btnCancel.visibility = View.VISIBLE
        b.btnCancel.isEnabled = true
        b.logScroll.visibility = View.GONE
        lockButtons(true)

        val prefs = AppPrefs.get(this)
        val cfg = ContainerConfig(
            id = "debian13-default",
            name = ContainerConfig.DEFAULT_NAME,
            arch = ContainerConfig.DEFAULT_ARCH,
            distribution = ContainerConfig.DEFAULT_DISTRO,
            isDefaultDebian13 = true,
            vncPassword = ContainerConfig.DEFAULT_VNC_PASS
        )
        val cache = File(FileUtils.downloadCacheDir(this), ImageConfig.IMAGE_FILENAME)
        val rootfs = FileUtils.rootfsDir(this, cfg.id)
        val installer = ImageInstaller(this) { msg, err ->
            TerminalBus.appendLine(msg, err)
            appendLog(msg)
        }

        // 速度计算
        var lastBytes = 0L
        var lastTime = System.currentTimeMillis()

        job = scope.launch {
            val r = installer.installFromUrl(
                mirrorUrl = ImageConfig.mirrorUrl(prefs.mirrorUrl),
                fallbackUrl = ImageConfig.GITHUB_RELEASE_URL,
                expectedSha256 = ImageConfig.EXPECTED_SHA256,
                destRootfs = rootfs,
                cache = cache,
                onProgress = { dl, total ->
                    val now = System.currentTimeMillis()
                    val speed = if (now - lastTime > 0) {
                        (dl - lastBytes) * 1000.0 / (now - lastTime)
                    } else 0.0
                    lastBytes = dl
                    lastTime = now
                    runOnUiThread { updateDownload(dl, total, speed) }
                },
                onExtractProgress = { processed, total ->
                    runOnUiThread { updateExtract(processed, total) }
                },
                onStage = { stage ->
                    runOnUiThread { setStage(stage) }
                },
                onExtractEntry = { name ->
                    runOnUiThread { appendLog("正在解压: $name") }
                }
            )
            withContext(Dispatchers.Main) { handleResult(r, cfg, true) }
        }
    }

    // ---------------- 本地导入 ----------------

    private fun startImport(uri: Uri) {
        b.btnRetry.visibility = View.GONE
        b.btnEnter.visibility = View.GONE
        b.btnCancel.visibility = View.VISIBLE
        b.btnCancel.isEnabled = true
        b.logScroll.visibility = View.VISIBLE
        lockButtons(true)
        setStage("extract")

        val targetName = "import_${System.currentTimeMillis()}"
        val cfg = ContainerConfig(
            id = targetName,
            name = "自定义容器",
            arch = "arm64",
            distribution = "custom",
            isDefaultDebian13 = false,
            vncPassword = "kaelix"
        )
        val cache = File(FileUtils.downloadCacheDir(this), "$targetName.archive")
        val rootfs = FileUtils.rootfsDir(this, cfg.id)
        val installer = ImageInstaller(this) { msg, err ->
            TerminalBus.appendLine(msg, err)
            appendLog(msg)
        }

        job = scope.launch {
            appendLog("正在读取本地镜像文件…")
            val archive = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri).use { input ->
                        if (input == null) return@withContext null
                        cache.outputStream().use { out ->
                            FileUtils.copyTo(input, out) { copied ->
                                runOnUiThread {
                                    appendLog("已读取 ${formatSize(copied)}")
                                }
                            }
                        }
                    }
                    cache
                } catch (e: Exception) {
                    TerminalBus.appendLine("导入读取失败: ${e.message}", true)
                    appendLog("导入读取失败: ${e.message}")
                    null
                }
            } ?: return@launch

            appendLog("读取完成，开始解压…")
            val r = installer.installFromFile(archive, rootfs,
                onExtractProgress = { processed, total ->
                    runOnUiThread { updateExtract(processed, total) }
                },
                onExtractEntry = { name ->
                    runOnUiThread { appendLog("正在解压: $name") }
                }
            )
            withContext(Dispatchers.Main) { handleResult(r, cfg, false) }
        }
    }

    // ---------------- UI 更新 ----------------

    private fun setStage(stage: String) {
        when (stage) {
            "download" -> {
                stopExtractHeartbeat()
                b.stageText.setText(R.string.stage_download)
                b.progressBar.isIndeterminate = false
                b.progressSpinner.visibility = View.GONE
                b.progressBar.visibility = View.VISIBLE
                b.logScroll.visibility = View.GONE
            }
            "verify" -> {
                stopExtractHeartbeat()
                b.stageText.setText(R.string.stage_verify)
                b.progressBar.isIndeterminate = true
                b.speedText.visibility = View.GONE
                b.sizeText.visibility = View.GONE
                b.logScroll.visibility = View.GONE
            }
            "extract" -> {
                b.stageText.setText(R.string.stage_extract)
                b.progressBar.isIndeterminate = false
                b.progressBar.progress = 0
                b.speedText.visibility = View.GONE
                b.sizeText.visibility = View.GONE
                b.logScroll.visibility = View.VISIBLE
                startExtractHeartbeat()
            }
        }
    }

    /** 追加一行日志到进度页底部的滚动日志区，并自动滚动到底部。 */
    private fun appendLog(msg: String) {
        val current = b.logText.text?.toString() ?: ""
        val next = if (current.isEmpty()) msg else "$current\n$msg"
        // 限制日志行数，避免内存无限增长
        val lines = next.lines()
        val trimmed = if (lines.size > 200) lines.takeLast(200).joinToString("\n") else next
        b.logText.text = trimmed
        b.logScroll.post { b.logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun updateDownload(downloaded: Long, total: Long, speedBytesPerSec: Double) {
        setStage("download")
        val pct = if (total > 0) (downloaded * 100 / total).toInt() else 0
        b.progressBar.progress = pct
        b.percentText.text = getString(R.string.download_percent, pct)
        b.sizeText.text = getString(
            R.string.download_size,
            formatSize(downloaded),
            if (total > 0) formatSize(total) else "?"
        )
        b.sizeText.visibility = View.VISIBLE
        b.speedText.text = getString(R.string.download_speed, formatSize(speedBytesPerSec.toLong()))
        b.speedText.visibility = View.VISIBLE
    }

    private fun updateExtract(processed: Long, total: Long) {
        val pct = if (total > 0) (processed * 100 / total).toInt() else 0
        lastExtractPct = pct
        b.progressBar.progress = pct
        b.percentText.text = getString(R.string.download_percent, pct)
    }

    /**
     * 启动解压心跳：每隔 2 秒输出一次当前进度，
     * 帮助用户区分「正在运行」和「真正卡死」。
     * 即使没有新的文件条目被处理（如正在解压大文件），也会持续刷新状态。
     */
    private fun startExtractHeartbeat() {
        stopExtractHeartbeat()
        heartbeatJob = scope.launch {
            var tick = 0
            while (isActive) {
                delay(2000)
                tick++
                val pct = lastExtractPct
                withContext(Dispatchers.Main) {
                    appendLog("解压进行中… $pct%（心跳 #$tick）")
                }
            }
        }
    }

    private fun stopExtractHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun handleResult(
        r: ImageInstaller.Result,
        cfg: ContainerConfig,
        isDownload: Boolean
    ) {
        stopExtractHeartbeat()
        lockButtons(false)
        b.btnCancel.visibility = View.GONE
        when (r) {
            is ImageInstaller.Result.Ok -> {
                ContainerManager.addContainer(this, cfg)
                AppPrefs.get(this).currentContainerId = cfg.id
                if (isDownload) {
                    AppPrefs.get(this).markDefaultImageInstalled(true)
                }
                b.stageText.setText(R.string.stage_done)
                b.progressBar.progress = 100
                b.percentText.text = "100%"
                b.btnEnter.visibility = View.VISIBLE
                Toast.makeText(
                    this,
                    if (isDownload) R.string.msg_extract_success else R.string.msg_import_success,
                    Toast.LENGTH_LONG
                ).show()
            }
            is ImageInstaller.Result.DiskFull -> {
                showDiskFullDialog(r.required, r.available)
            }
            is ImageInstaller.Result.Corrupt -> {
                if (isDownload && r.reason == "SHA256 mismatch") {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.title_error)
                        .setMessage(R.string.msg_sha256_failed)
                        .setPositiveButton(R.string.btn_retry) { _, _ -> startTask() }
                        .setNegativeButton(R.string.cancel) { _, _ -> finishBackToChoice() }
                        .showAnimated()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.title_error)
                        .setMessage(
                            if (isDownload) R.string.msg_image_corrupt
                            else R.string.msg_import_image_failed
                        )
                        .setPositiveButton(R.string.ok) { _, _ -> finishBackToChoice() }
                        .showAnimated()
                }
            }
            else -> {
                AlertDialog.Builder(this)
                    .setTitle(R.string.title_error)
                    .setMessage(
                        if (isDownload) R.string.msg_download_image_failed
                        else R.string.msg_import_image_failed
                    )
                    .setPositiveButton(R.string.btn_retry) { _, _ ->
                        if (isDownload) startTask()
                        else pickFile.launch(arrayOf("*/*"))
                    }
                    .setNegativeButton(R.string.cancel) { _, _ -> finishBackToChoice() }
                    .showAnimated()
            }
        }
    }

    private fun showDiskFullDialog(required: Long, available: Long) {
        val reqMiB = required / 1024 / 1024
        val availMiB = available / 1024 / 1024
        AlertDialog.Builder(this)
            .setTitle(R.string.title_error)
            .setMessage(getString(R.string.msg_disk_full_detail, reqMiB, availMiB))
            .setPositiveButton(R.string.ok) { _, _ -> finishBackToChoice() }
            .showAnimated()
    }

    /**
     * 返回启动页并告知「需要重新弹出镜像来源选择对话框」。
     * 避免用户点取消后卡死在 K 启动页无法操作。
     */
    private fun finishBackToChoice() {
        setResult(RESULT_CANCELED)
        finish()
    }

    /**
     * 构建并显示对话框，同时应用弹出/消失动画。
     * 不使用自定义主题构建器，避免 MaterialComponents 主题与 setItems 等不兼容。
     */
    private fun AlertDialog.Builder.showAnimated(): AlertDialog {
        val d = create()
        d.window?.attributes?.windowAnimations = R.style.KaelixDialogAnimation
        d.show()
        return d
    }

    /**
     * 锁定/解锁会触发新任务的按钮，防止下载/解压过程中重复点击造成多任务冲突。
     * 取消按钮始终可用，允许用户中断当前任务。
     */
    private fun lockButtons(lock: Boolean) {
        b.btnRetry.isEnabled = !lock
        b.btnEnter.isEnabled = !lock
        // 取消按钮保持可用：允许用户中断后台任务
        b.btnCancel.isEnabled = true
    }

    private fun enterMain() {
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        finish()
    }

    companion object {
        const val MODE_DOWNLOAD = 0
        const val MODE_IMPORT = 1
        const val EXTRA_MODE = "mode"

        fun startForDownload(context: Context) {
            context.startActivity(Intent(context, DownloadActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_DOWNLOAD)
            })
        }

        fun startForImport(context: Context) {
            context.startActivity(Intent(context, DownloadActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_IMPORT)
            })
        }

        private val DF = DecimalFormat("#.##")
        fun formatSize(bytes: Long): String {
            return when {
                bytes >= 1L shl 30 -> DF.format(bytes / (1L shl 30).toDouble()) + " GiB"
                bytes >= 1L shl 20 -> DF.format(bytes / (1L shl 20).toDouble()) + " MiB"
                bytes >= 1L shl 10 -> DF.format(bytes / (1L shl 10).toDouble()) + " KiB"
                else -> "$bytes B"
            }
        }
    }
}
