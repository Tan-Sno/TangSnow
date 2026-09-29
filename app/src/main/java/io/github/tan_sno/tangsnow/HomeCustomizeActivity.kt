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
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 定制主页：
 *  - 选首页背景风格：棠雪 / 薄雾 / 极简 / 自定义图片
 *  - 管理首页快捷方式：添加、删除（最多 8 个）
 * 所有修改即时保存，返回主页即见。
 */
class HomeCustomizeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeCustomizeBinding

    /** 本页展示中的对话框（新增快捷方式）。见 DialogTracker 的类注释 */
    private val dialogs = io.github.tan_sno.tangsnow.ui.DialogTracker()

    override fun onDestroy() {
        dialogs.cancelAll()
        super.onDestroy()
    }
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
                    this, if (active) R.color.accent_text else R.color.accent_muted
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
        // 改为「复制为应用内副本」：与照片选择器的授权模型无关，100% 跨重启有效。
        // 仅复制成功才写偏好；失败时保持原风格并有失败提示。
        lifecycleScope.launch {
            val copied = withContext(Dispatchers.IO) { copyImageToInternal(uri) }
            if (isFinishing || isDestroyed) {
                // 宿主已销毁：这份副本无人认领（偏好不会写、之后也没人会再删它）⇒ 就地删除
                deleteHomeImageCopy(copied?.toString())
                return@launch
            }
            if (copied != null) {
                // 清理旧副本（防堆积）—— 只删本应用私有目录里的副本，其余路径一律不动（见下方守卫）
                deleteHomeImageCopy(prefs.homeImageUri)
                prefs.homeImageUri = copied.toString()
                prefs.homeStyle = PreferenceStore.STYLE_IMAGE
                refreshStyleSelection(PreferenceStore.STYLE_IMAGE)
                refreshImagePreview()
            } else {
                toast(R.string.home_image_copy_failed)
            }
        }
    }

    /**
     * 删除 [HOME_IMAGE_DIR] 里的主页图副本。
     *
     * 存在的理由：偏好被清掉或替换后，**文件本身不会自己消失** —— 少了统一出口就会在
     * `filesDir` 里持续累积无主副本（用户以为「清除图片」已经把它删了，其实还在盘上）。
     * 故清除、替换、宿主已销毁三条路径都收口到这里。
     *
     * 只接受 `file://` 且路径确实落在本应用 `filesDir/[HOME_IMAGE_DIR]` 下：存量偏好里可能
     * 是相册的 `content://`（旧版本写法），也可能是被外部写入的任意路径，一律不动。
     * 失败静默 —— 删除是尽力而为，不该影响选图流程。
     */
    private fun deleteHomeImageCopy(uriString: String?) {
        val path = uriString?.takeIf { it.startsWith("file://") }
            ?.let { runCatching { Uri.parse(it).path }.getOrNull() }
            ?: return
        val dir = File(filesDir, HOME_IMAGE_DIR)
        val insideDir = runCatching {
            File(path).canonicalPath.startsWith(dir.canonicalPath + File.separator)
        }.getOrDefault(false)
        if (!insideDir) return
        runCatching { File(path).delete() }
    }

    /**
     * 把所选图片复制到应用内 filesDir 子目录；失败返回 null。
     *
     * 刻意**不设体积上限**：这是用户自己挑的背景图，加阈值就等于「某些大图设不上背景」——
     * 而本改动之前（只存相册 URI、不复制）它是能用的，属**新增的可见回退**。
     * 导入 `.xpi` 那条路设上限是因为来源不受控（防解压炸弹 / 填满缓存），这里来源是系统照片选择器。
     */
    private fun copyImageToInternal(uri: Uri): Uri? {
        val dir = File(filesDir, HOME_IMAGE_DIR)
        dir.mkdirs()
        // 原子唯一名交给文件系统（旧写法 `bg-<毫秒>.jpg` 同毫秒会撞同一路径）
        val out = File.createTempFile("bg-", ".jpg", dir)
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().buffered().use { sink ->
                    input.copyTo(sink)
                }
            } ?: return null
            if (out.length() == 0L) {
                out.delete()
                return null
            }
            Uri.fromFile(out)
        } catch (e: Throwable) {
            runCatching { out.delete() }
            null
        }
    }

    private fun clearPickedImage() {
        // 先删副本再清偏好：顺序反过来的话路径就丢了，副本会永远留在 filesDir 里
        deleteHomeImageCopy(prefs.homeImageUri)
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
        // 记录本次请求的 URI，用于竞态复查：解码返回后若偏好已被清除则丢弃结果。
        val requestedUri = uriString
        lifecycleScope.launch {
            val bmp = ImageLoader.loadScaled(this@HomeCustomizeActivity, uri, PREVIEW_MAX_W, PREVIEW_MAX_H)
            if (isFinishing || isDestroyed) return@launch
            // 复查：偏好未被清除（用户未点「清除图片」）且当前风格仍为 IMAGE 才写回
            if (prefs.homeImageUri == requestedUri && prefs.homeStyle == PreferenceStore.STYLE_IMAGE) {
                showPreview(bmp)
            }
            // 否则丢弃结果（避免旧图回显）
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
            setTextColor(ContextCompat.getColor(context, R.color.accent_text))
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
            toast(R.string.shortcut_max)
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
            // 原先是原始像素（48/8），在不同密度屏上视觉差别极大；按 3x 屏的口径折算为 dp
            // （48px@3x = 16dp、8px@3x ≈ 2.7dp → 3dp），与全项目统一的 dp() 写法对齐
            setPadding(dp(16), dp(3), dp(16), dp(3))
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
                    toast(R.string.shortcut_invalid)
                }
                refreshShortcuts()
            }
            .create()
            .let { dialogs.track(it) }
    }


    private companion object {
        /** 预览解码目标尺寸：控件高度仅 120dp，超过此尺寸的解码纯属浪费内存 */
        const val PREVIEW_MAX_W = 1080
        const val PREVIEW_MAX_H = 720

        /** 主页图副本所在子目录（`filesDir/<此名>`）；删除副本时用它做归属校验 */
        const val HOME_IMAGE_DIR = "home_bg"
    }
}