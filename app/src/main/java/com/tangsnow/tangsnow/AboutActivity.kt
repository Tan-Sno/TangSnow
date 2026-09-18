package com.tangsnow.tangsnow

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.tangsnow.tangsnow.databinding.ActivityAboutBinding
import com.tangsnow.tangsnow.update.UpdateChecker
import com.tangsnow.tangsnow.util.dp

/**
 * 「关于棠雪」：应用信息 + 三个文档入口。
 * 从上到下依次为：隐私政策摘要 / 完整隐私政策 / 用户协议。
 * 文本源与首次启动同意页一致（见 [com.tangsnow.tangsnow.data.LegalDocs]），
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
        refreshCrashSummary()
    }

    /** 刷新“崩溃报告”行的摘要（日志条数） */
    private fun refreshCrashSummary() {
        val count = com.tangsnow.tangsnow.util.CrashLogger.list(this).size
        binding.txtRowCrashesSub.text = if (count == 0) {
            getString(R.string.about_crash_summary_empty)
        } else {
            getString(R.string.about_crash_summary_format, count)
        }
    }

    /** 崩溃日志选择（无日志时仅提示） */
    /** 崩溃日志文件名 `crash-yyyyMMdd-HHmmss.txt` 的展示格式化；预编译，避免每次都编译正则 */
    private val crashNamePattern = Regex("""^(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})(\d{2})$""")

    private fun showCrashReports() {
        val files = com.tangsnow.tangsnow.util.CrashLogger.list(this)
        if (files.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.about_crash_none, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val names = files.map { f ->
            // crash-20260908-101530.txt → 2026-09-08 10:15:30（仅展示用，删除仍用原文件）
            f.name.removePrefix("crash-").removeSuffix(".txt")
                .replace(crashNamePattern, "$1-$2-$3 $4:$5:$6")
        }
        var chosen = 0
        AlertDialog.Builder(this)
            .setTitle(R.string.about_crash_title)
            .setSingleChoiceItems(names.toTypedArray(), 0) { _: android.content.DialogInterface, which: Int -> chosen = which }
            .setNegativeButton(R.string.dlg_cancel, null)
            .setPositiveButton(R.string.about_crash_view) { _: android.content.DialogInterface, _: Int ->
                if (chosen in files.indices) showCrashDetail(files[chosen])
            }
            .show()
    }

    /** 查看单条崩溃日志：可分享 / 删除 */
    private fun showCrashDetail(file: java.io.File) {
        val text = com.tangsnow.tangsnow.util.CrashLogger.content(file).ifBlank { getString(R.string.about_crash_empty_file) }
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
                file.delete()
                refreshCrashSummary()
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

    /** 通过系统分享面板把崩溃日志内容发给别人（如开发者） */
    private fun shareCrashFile(file: java.io.File) {
        val body = com.tangsnow.tangsnow.util.CrashLogger.content(file)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, file.name)
            putExtra(android.content.Intent.EXTRA_TEXT, body.ifBlank { getString(R.string.about_crash_empty_file) })
        }
        runCatching {
            startActivity(android.content.Intent.createChooser(send, getString(R.string.about_crash_share_via)))
        }.onFailure {
            android.widget.Toast.makeText(this, R.string.toast_share_empty, android.widget.Toast.LENGTH_SHORT).show()
        }
    }


    /** 优先跳转阅读页；启动失败（极小概率）则原地弹全文，保证正文永远可见 */
    private fun openDoc(doc: Int) {
        if (!LegalActivity.start(this, doc)) {
            val (titleRes, bodyRes) = LegalActivity.docResources(doc)
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setMessage(bodyRes)
                .setPositiveButton(R.string.dlg_ok, null)
                .show()
        }
    }

    private fun previewOf(@androidx.annotation.StringRes res: Int): String {
        val flat = getString(res).replace('\n', ' ').trim()
        return if (flat.length <= 46) flat else flat.take(46) + "…"
    }
}