package io.github.tan_sno.tangsnow

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.tan_sno.tangsnow.databinding.ActivityAboutBinding
import io.github.tan_sno.tangsnow.update.UpdateChecker
import io.github.tan_sno.tangsnow.util.LegalText
import io.github.tan_sno.tangsnow.util.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「关于棠雪」：应用信息 + 三个文档入口。
 * 从上到下依次为：隐私政策摘要 / 完整隐私政策 / 用户协议。
 * 文本源与首次启动同意页一致（见 [io.github.tan_sno.tangsnow.data.LegalDocs]），
 * 避免同一文档多份副本在后续更新中失同步。
 */
class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        val current = UpdateChecker.current(this)
        binding.txtVersion.text = getString(
            R.string.about_version_format,
            current.versionName.ifBlank { getString(R.string.pref_version_unknown) }
        )

        // 每行预览一小段正文，避免“入口移走后内容找不到”的观感
        binding.txtRowSummarySub.text = previewOf(R.string.legal_privacy_summary)
        binding.txtRowPrivacySub.text = previewOf(R.string.privacy_policy_text)
        binding.txtRowAgreementSub.text = previewOf(R.string.user_agreement_text)

        binding.rowSummary.setOnClickListener { openDoc(LegalActivity.DOC_SUMMARY) }
        binding.rowPrivacy.setOnClickListener { openDoc(LegalActivity.DOC_PRIVACY) }
        binding.rowAgreement.setOnClickListener { openDoc(LegalActivity.DOC_AGREEMENT) }

        // 崩溃报告：仅本地留存；可查看 / 分享 / 删除
        binding.rowCrashes.setOnClickListener { showCrashReports() }
        // 项目主页：隐私政策与用户协议都写着「可通过本项目 GitHub 仓库提交 Issue」，
        // 此前应用内没有任何落点。交给 BrowserOpener 打开（它会送进主界面的标签页），
        // 与浏览器自身的习惯一致。
        binding.rowProject.setOnClickListener {
            BrowserOpener.open(this, "https://github.com/Tan-Sno/TangSnow")
        }
        refreshCrashSummary()
    }

    /** 刷新“崩溃报告”行的摘要（日志条数）。listFiles 属磁盘 I/O，放 IO 线程（StrictMode 干净） */
    private fun refreshCrashSummary() {
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                io.github.tan_sno.tangsnow.util.CrashLogger.list(this@AboutActivity).size
            }
            if (isFinishing || isDestroyed) return@launch
            binding.txtRowCrashesSub.text = if (count == 0) {
                getString(R.string.about_crash_summary_empty)
            } else {
                getString(R.string.about_crash_summary_format, count)
            }
        }
    }

    /** 崩溃日志文件名 `crash-yyyyMMdd-HHmmss.txt` 的展示格式化；预编译，避免每次都编译正则 */
    private val crashNamePattern = Regex("""^(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})(\d{2})$""")

    /** 崩溃日志选择（无日志时仅提示）。文件枚举在 IO 线程完成后回主线程弹窗 */
    private fun showCrashReports() {
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) {
                io.github.tan_sno.tangsnow.util.CrashLogger.list(this@AboutActivity)
            }
            if (isFinishing || isDestroyed) return@launch
            if (files.isEmpty()) {
                toast(R.string.about_crash_none)
                return@launch
            }
            val names = files.map { f ->
                // crash-20260908-101530.txt → 2026-09-08 10:15:30（仅展示用，删除仍用原文件）
                f.name.removePrefix("crash-").removeSuffix(".txt")
                    .replace(crashNamePattern, "$1-$2-$3 $4:$5:$6")
            }
            var chosen = 0
            AlertDialog.Builder(this@AboutActivity)
                .setTitle(R.string.about_crash_title)
                .setSingleChoiceItems(names.toTypedArray(), 0) { _: android.content.DialogInterface, which: Int -> chosen = which }
                .setNegativeButton(R.string.dlg_cancel, null)
                .setPositiveButton(R.string.about_crash_view) { _: android.content.DialogInterface, _: Int ->
                    if (chosen in files.indices) showCrashDetail(files[chosen])
                }
                .show()
        }
    }

    /** 查看单条崩溃日志：可分享 / 删除。正文读盘在 IO 线程，回主线程后建窗 */
    private fun showCrashDetail(file: java.io.File) {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                io.github.tan_sno.tangsnow.util.CrashLogger.content(file)
            }
            if (isFinishing || isDestroyed) return@launch
            showCrashDetailDialog(file, text.ifBlank { getString(R.string.about_crash_empty_file) })
        }
    }

    private fun showCrashDetailDialog(file: java.io.File, text: String) {
        val tv = android.widget.TextView(this).apply {
            this.text = text
            textSize = 11f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setTextIsSelectable(true)
        }
        val scroll = android.widget.ScrollView(this).apply {
            addView(
                tv,
                android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val maxH = (resources.displayMetrics.heightPixels * 0.55).toInt().coerceAtLeast(360)
        val box = android.widget.LinearLayout(this).apply {
            setPadding(dp(20), dp(10), dp(20), dp(10))
            addView(
                scroll,
                android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, maxH
                )
            )
        }
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setView(box)
            .setNegativeButton(R.string.about_crash_delete) { _: android.content.DialogInterface, _: Int ->
                // 文件删除属磁盘写，放 IO 线程；完成后异步刷新计数
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { runCatching { file.delete() } }
                    refreshCrashSummary()
                }
            }
            .setPositiveButton(R.string.about_crash_share) { _: android.content.DialogInterface, _: Int ->
                confirmShareCrash(file)
            }
            .show()
    }

    /**
     * 分享前再确认一次。
     *
     * 崩溃日志里**可能含最近访问站点的域名**（隐私政策第 7 条已披露，`CrashLogger.lastHost`
     * 只为崩溃复现而记录、仅域名）。但"政策里写了"不等于"用户此刻记得"，而分享是让数据
     * **离开本机**的动作 —— 值得一次即时告知。
     */
    private fun confirmShareCrash(file: java.io.File) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.about_crash_share)
            .setMessage(R.string.about_crash_share_notice)
            .setNegativeButton(R.string.dlg_cancel, null)
            .setPositiveButton(R.string.dlg_ok) { _: android.content.DialogInterface, _: Int ->
                shareCrashFile(file)
            }
            .show()
    }

    /** 通过系统分享面板把崩溃日志内容发给别人（如开发者）。读盘在 IO 线程，发送回主线程 */
    private fun shareCrashFile(file: java.io.File) {
        lifecycleScope.launch {
            val body = withContext(Dispatchers.IO) {
                io.github.tan_sno.tangsnow.util.CrashLogger.content(file)
            }
            if (isFinishing || isDestroyed) return@launch
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_SUBJECT, file.name)
                putExtra(android.content.Intent.EXTRA_TEXT, body.ifBlank { getString(R.string.about_crash_empty_file) })
            }
            runCatching {
                startActivity(android.content.Intent.createChooser(send, getString(R.string.about_crash_share_via)))
            }.onFailure {
                toast(R.string.toast_share_empty)
            }
        }
    }


    /** 优先跳转阅读页；启动失败（极小概率）则原地弹全文，保证正文永远可见 */
    private fun openDoc(doc: Int) {
        if (!LegalActivity.start(this, doc)) {
            val (titleRes, bodyRes) = LegalActivity.docResources(doc)
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(titleRes)
                // 与阅读页同一处理：`**…**` 转加粗，别让用户看到星号（见 util/LegalText）
                .setMessage(LegalText.emphasize(this, bodyRes))
                .setPositiveButton(R.string.dlg_ok, null)
                .show()
        }
    }

    private fun previewOf(@androidx.annotation.StringRes res: Int): String {
        // 先去掉 `**…**` 标记再截断：预览是纯文本行（带不了 Span），若正好截在标记中间就会
        // 露出半个星号。复用 LegalText 的解析，保证与阅读页对同一份文本的理解一致。
        val flat = LegalText.parse(getString(res)).plain.replace('\n', ' ').trim()
        return if (flat.length <= 46) flat else flat.take(46) + "…"
    }
}