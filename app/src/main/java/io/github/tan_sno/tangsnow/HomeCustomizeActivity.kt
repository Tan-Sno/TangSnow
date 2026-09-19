package io.github.tan_sno.tangsnow

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.github.tan_sno.tangsnow.data.HomeShortcut
import io.github.tan_sno.tangsnow.data.PreferenceStore
import io.github.tan_sno.tangsnow.databinding.ActivityHomeCustomizeBinding
import io.github.tan_sno.tangsnow.util.ImageLoader
import io.github.tan_sno.tangsnow.util.dp
import kotlinx.coroutines.launch

/**
 * 定制主页：
 *  - 选首页背景风格：棠雪 / 薄雾 / 极简 / 自定义图片
 *  - 管理首页快捷方式：添加、删除（最多 8 个）
 * 所有修改即时保存，返回主页即见。
 */
class HomeCustomizeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeCustomizeBinding
    private lateinit var prefs: PreferenceStore

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) applyPickedImage(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = PreferenceStore(this)
        binding = ActivityHomeCustomizeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.styleTangSnow.setOnClickListener { onStyleSelected(PreferenceStore.STYLE_TANGSNOW) }
        binding.styleMist.setOnClickListener { onStyleSelected(PreferenceStore.STYLE_MIST) }
        binding.stylePlain.setOnClickListener { onStyleSelected(PreferenceStore.STYLE_PLAIN) }
        binding.styleImage.setOnClickListener { onStyleSelected(PreferenceStore.STYLE_IMAGE) }
        binding.btnPickImage.setOnClickListener { launchImagePicker() }
        binding.btnClearImage.setOnClickListener { clearPickedImage() }
        binding.btnAddShortcut.setOnClickListener { showAddDialog() }

        // 历史遗留状态自愈：旧版本允许"选中了自定义图片但从未选图"，此时主页背景会
        // 无提示地退化成纯色。这里回落到极简，避免带病状态延续。
        if (prefs.homeStyle == PreferenceStore.STYLE_IMAGE && prefs.homeImageUri.isNullOrBlank()) {
            prefs.homeStyle = PreferenceStore.STYLE_PLAIN
        }
        applyStyle(prefs.homeStyle)
        refreshShortcuts()
        refreshImagePreview()
    }

    // ------------------------------------------------------------- 背景风格

    /**
     * 用户点击风格项。
     * 「自定义图片」在尚未选图时先拉起相册、选到才生效——否则会出现
     * "点了自定义图片、界面却变成一片纯色"的无解释状态。
     */
    private fun onStyleSelected(style: String) {
        if (style == PreferenceStore.STYLE_IMAGE && prefs.homeImageUri.isNullOrBlank()) {
            launchImagePicker()
            return
        }
        applyStyle(style)
    }

    /** 落地风格选择（仅刷新选中态与预览，不触发选图；供初始绑定与点击共用） */
    private fun applyStyle(style: String) {
        prefs.homeStyle = style
        refreshStyleSelection(style)
        refreshImagePreview()
    }

    private fun refreshStyleSelection(selected: String) {
        fun style(container: LinearLayout, label: TextView, active: Boolean) {
            label.setTextColor(
                ContextCompat.getColor(
                    this, if (active) R.color.accent else R.color.accent_muted
                )
            )
            label.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            container.setBackgroundResource(
                if (active) R.drawable.bg_style_selected else android.R.color.transparent
            )
        }
        style(binding.styleTangSnow, binding.styleTangSnowLabel, selected == PreferenceStore.STYLE_TANGSNOW)
        style(binding.styleMist, binding.styleMistLabel, selected == PreferenceStore.STYLE_MIST)
        style(binding.stylePlain, binding.stylePlainLabel, selected == PreferenceStore.STYLE_PLAIN)
        style(binding.styleImage, binding.styleImageLabel, selected == PreferenceStore.STYLE_IMAGE)
    }

    // ------------------------------------------------------------- 自定义图片背景

    private fun launchImagePicker() {
        pickImageLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private fun applyPickedImage(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: Throwable) {
            // 部分 provider 不支持持久权限（如沙盒选择器临时授权），
            // 当前进程能读取就行，下次启动若失败会落到 PLAIN 风格（MainActivity 已处理）。
        }
        prefs.homeImageUri = uri.toString()
        prefs.homeStyle = PreferenceStore.STYLE_IMAGE
        refreshStyleSelection(PreferenceStore.STYLE_IMAGE)
        refreshImagePreview()
    }

    private fun clearPickedImage() {
        prefs.homeImageUri = null
        if (prefs.homeStyle == PreferenceStore.STYLE_IMAGE) {
            prefs.homeStyle = PreferenceStore.STYLE_PLAIN
            refreshStyleSelection(PreferenceStore.STYLE_PLAIN)
        }
        refreshImagePreview()
    }

    /**
     * 刷新预览图。
     *
     * 解码放到 IO 线程并按预览尺寸采样：早期实现用 `setImageURI` 在主线程整张解码，
     * 一张相机原图就足以让本页卡住甚至 OOM（预览控件只有 120dp 高，本就无需原图）。
     */
    private fun refreshImagePreview() {
        val uriString = prefs.homeImageUri
        if (uriString.isNullOrBlank()) {
            showPreview(null)
            return
        }
        val uri = runCatching { Uri.parse(uriString) }.getOrNull()
        if (uri == null) {
            showPreview(null)
            return
        }
        lifecycleScope.launch {
            val bmp = ImageLoader.loadScaled(this@HomeCustomizeActivity, uri, PREVIEW_MAX_W, PREVIEW_MAX_H)
            if (isFinishing || isDestroyed) return@launch
            showPreview(bmp)
        }
    }

    private fun showPreview(bmp: android.graphics.Bitmap?) {
        binding.imgHomePreview.setImageBitmap(bmp)
        binding.imgHomePreview.isVisible = bmp != null
    }

    // ------------------------------------------------------------- 快捷方式

    private fun refreshShortcuts() {
        val list = prefs.homeShortcuts
        binding.shortcutsContainer.removeAllViews()
        binding.shortcutsEmpty.isVisible = list.isEmpty()
        list.forEachIndexed { index, shortcut ->
            binding.shortcutsContainer.addView(makeShortcutRow(index, shortcut))
        }
    }

    private fun makeShortcutRow(index: Int, shortcut: HomeShortcut): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(4), dp(10))
            setBackgroundResource(R.drawable.bg_style_plain)
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = shortcut.name
            textSize = 15f
            setTextColor(ContextCompat.getColor(context, R.color.accent))
        })
        texts.addView(TextView(this).apply {
            text = shortcut.url
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.accent_muted))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        row.addView(texts)

        val del = AppCompatImageButton(this).apply {
            setImageResource(R.drawable.ic_close)
            setBackgroundResource(android.R.color.transparent)
            setColorFilter(ContextCompat.getColor(context, R.color.accent_muted))
            contentDescription = getString(R.string.btn_delete)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setOnClickListener {
                prefs.removeHomeShortcut(index)
                refreshShortcuts()
            }
        }
        row.addView(del)
        (del.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
        return row
    }

    private fun showAddDialog() {
        if (prefs.homeShortcuts.size >= PreferenceStore.MAX_HOME_SHORTCUTS) {
            Toast.makeText(this, R.string.shortcut_max, Toast.LENGTH_SHORT).show()
            return
        }
        val nameInput = EditText(this).apply { hint = getString(R.string.shortcut_name_hint) }
        val urlInput = EditText(this).apply {
            hint = getString(R.string.shortcut_url_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 8, 48, 8)
            addView(nameInput)
            addView(urlInput)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.shortcut_add)
            .setView(body)
            .setNegativeButton(R.string.dlg_cancel, null)
            .setPositiveButton(R.string.dlg_ok) { _, _ ->
                val ok = prefs.addHomeShortcut(
                    nameInput.text.toString(),
                    urlInput.text.toString(),
                )
                if (!ok) {
                    Toast.makeText(this, R.string.shortcut_invalid, Toast.LENGTH_SHORT).show()
                }
                refreshShortcuts()
            }
            .show()
    }


    private companion object {
        /** 预览解码目标尺寸：控件高度仅 120dp，超过此尺寸的解码纯属浪费内存 */
        const val PREVIEW_MAX_W = 1080
        const val PREVIEW_MAX_H = 720
    }
}