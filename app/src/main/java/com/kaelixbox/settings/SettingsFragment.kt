package com.kaelixbox.settings

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.kaelixbox.App
import com.kaelixbox.R
import com.kaelixbox.container.ContainerManager
import com.kaelixbox.container.ImageInstaller
import com.kaelixbox.container.TerminalBus
import com.kaelixbox.databinding.FragmentSettingsBinding
import com.kaelixbox.prefs.AppPrefs
import com.kaelixbox.util.FileUtils
import com.kaelixbox.util.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

class SettingsFragment : Fragment() {

    private var _b: FragmentSettingsBinding? = null
    private val b get() = _b!!

    private lateinit var adapter: ContainerAdapter

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        importArchive(uri)
    }

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val prefs = AppPrefs.get(requireContext())
        if (!granted) {
            // Revert the switch + persist as off; do not block the app.
            prefs.setMicPassthrough(false)
            b.switchMic.isChecked = false
            Toast.makeText(requireContext(),
                "麦克风权限被拒绝，音频透传将不可用", Toast.LENGTH_SHORT).show()
        } else {
            prefs.setMicPassthrough(true)
            if (ContainerManager.isRunning) {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.title_warning)
                    .setMessage(R.string.msg_mic_needs_restart)
                    .setPositiveButton(R.string.ok, null)
                    .show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentSettingsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val prefs = AppPrefs.get(requireContext())
        b.switchMic.isChecked = prefs.micPassthroughEnabled()

        b.switchMic.setOnCheckedChangeListener { btn, checked ->
            if (btn.isPressed) {  // only react to user taps
                if (checked) {
                    // Only request mic permission when the user turns it on.
                    micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                } else {
                    prefs.setMicPassthrough(false)
                    if (ContainerManager.isRunning) {
                        AlertDialog.Builder(requireContext())
                            .setTitle(R.string.title_warning)
                            .setMessage(R.string.msg_mic_needs_restart)
                            .setPositiveButton(R.string.ok, null).show()
                    }
                }
            }
        }

        b.btnBattery.setOnClickListener {
            PermissionHelper.openBatteryOptimizationSettings(requireActivity())
        }
        b.btnImportImage.setOnClickListener {
            // Verify storage permission each time before any IO.
            if (!PermissionHelper.hasStorage(requireContext())) {
                PermissionHelper.requestStorage(requireActivity())
                Toast.makeText(requireContext(),
                    R.string.msg_permission_storage_denied, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            pickImage.launch(arrayOf("*/*"))
        }

        b.containerList.layoutManager = LinearLayoutManager(requireContext())
        adapter = ContainerAdapter(
            currentId = { AppPrefs.get(requireContext()).currentContainerId },
            onSwitch = { switchContainer(it) },
            onDelete = { deleteContainer(it) }
        )
        b.containerList.adapter = adapter

        refreshList()
    }

    private fun switchContainer(cfg: com.kaelixbox.container.ContainerConfig) {
        if (ContainerManager.isRunning) {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.title_warning)
                .setMessage("请先停止当前容器再切换。")
                .setPositiveButton(R.string.ok, null).show()
            return
        }
        AppPrefs.get(requireContext()).currentContainerId = cfg.id
        TerminalBus.appendLine("[container] 切换到 ${cfg.name}")
        refreshList()
    }

    private fun deleteContainer(cfg: com.kaelixbox.container.ContainerConfig) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_delete_container)
            .setMessage("删除 ${cfg.name}？此操作不可恢复。")
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                ContainerManager.removeContainer(requireContext(), cfg.id)
                refreshList()
            }.show()
    }

    private fun refreshList() {
        val list = ContainerManager.listContainers(requireContext())
        val cur = AppPrefs.get(requireContext()).currentContainerId
        adapter.submit(list, cur)
    }

    private fun importArchive(uri: Uri) {
        // Stream the picked archive into a temp cache file, then extract.
        val targetName = "import_${System.currentTimeMillis()}"
        val cfg = com.kaelixbox.container.ContainerConfig(
            id = targetName,
            name = "自定义容器",
            arch = "arm64",
            distribution = "custom",
            isDefaultDebian13 = false,
            vncPassword = "kaelix"
        )
        val cache = File(FileUtils.downloadCacheDir(requireContext()), "$targetName.archive")
        val rootfs = FileUtils.rootfsDir(requireContext(), cfg.id)
        val installer = ImageInstaller(requireContext()) { msg, err ->
            TerminalBus.appendLine(msg, err)
        }
        (requireActivity().application as App).appScope.launch {
            val archive = withContext(Dispatchers.IO) {
                try {
                    requireContext().contentResolver.openInputStream(uri).use { input ->
                        if (input == null) return@withContext null
                        cache.outputStream().use { out: OutputStream ->
                            FileUtils.copyTo(input, out)
                        }
                    }
                    cache
                } catch (e: Exception) {
                    TerminalBus.appendLine("导入读取失败: ${e.message}", true)
                    null
                }
            } ?: return@launch
            val r = installer.installFromFile(archive, rootfs)
            withContext(Dispatchers.Main) {
                when (r) {
                    is ImageInstaller.Result.Ok -> {
                        ContainerManager.addContainer(requireContext(), cfg)
                        AppPrefs.get(requireContext()).currentContainerId = cfg.id
                        TerminalBus.appendLine("[import] 导入完成，已切换到 ${cfg.name}")
                        Toast.makeText(requireContext(), "导入完成", Toast.LENGTH_SHORT).show()
                    }
                    is ImageInstaller.Result.DiskFull ->
                        Toast.makeText(requireContext(), R.string.msg_disk_full, Toast.LENGTH_LONG).show()
                    is ImageInstaller.Result.Corrupt ->
                        Toast.makeText(requireContext(), R.string.msg_image_corrupt, Toast.LENGTH_LONG).show()
                    else ->
                        Toast.makeText(requireContext(), "导入失败", Toast.LENGTH_LONG).show()
                }
                cache.delete()
                refreshList()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
