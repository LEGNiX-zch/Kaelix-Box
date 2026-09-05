package com.kaelixbox.about

import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.kaelixbox.R
import com.kaelixbox.databinding.DialogDonateBinding
import com.kaelixbox.databinding.FragmentAboutBinding
import com.kaelixbox.prefs.AppPrefs
import com.kaelixbox.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.kaelixbox.App

class AboutFragment : Fragment() {

    private var _b: FragmentAboutBinding? = null
    private val b get() = _b!!

    private val pickAvatar = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val target = FileUtils.avatarTarget(requireContext())
        (requireActivity().application as App).appScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    requireContext().contentResolver.openInputStream(uri).use { input ->
                        if (input == null) return@withContext
                        target.outputStream().use { out ->
                            FileUtils.copyTo(input, out)
                        }
                    }
                    AppPrefs.get(requireContext()).avatarPath = target.absolutePath
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(),
                            "头像读取失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            withContext(Dispatchers.Main) { loadAvatar() }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentAboutBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val prefs = AppPrefs.get(requireContext())
        b.nickname.setText(prefs.nickname)
        b.nickname.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) prefs.nickname = b.nickname.text.toString().trim()
        }
        b.avatar.setOnClickListener { pickAvatar.launch(arrayOf("image/*")) }
        b.btnDonate.setOnClickListener { showDonateDialog() }
        loadAvatar()
    }

    private fun showDonateDialog() {
        val db = DialogDonateBinding.inflate(layoutInflater)
        val resId = resources.getIdentifier("donate_qr", "drawable", requireContext().packageName)
        if (resId != 0) {
            db.donateImage.setImageResource(resId)
        } else {
            db.donateImage.setImageResource(R.drawable.ic_nav_about)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.about_donate_title)
            .setView(db.root)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun loadAvatar() {
        val path = AppPrefs.get(requireContext()).avatarPath
        val bmp = if (path.isNotEmpty() && java.io.File(path).exists()) {
            try {
                BitmapFactory.decodeFile(path)
            } catch (_: Throwable) { null }
        } else null
        if (bmp != null) {
            b.avatar.imageTintList = null
            b.avatar.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            b.avatar.setImageBitmap(bmp)
        } else {
            b.avatar.imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            b.avatar.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            b.avatar.setImageResource(R.drawable.ic_nav_about)
        }
    }

    override fun onPause() {
        super.onPause()
        AppPrefs.get(requireContext()).nickname = b.nickname.text.toString().trim()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
