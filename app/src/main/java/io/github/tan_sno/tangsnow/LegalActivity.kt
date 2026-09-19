package io.github.tan_sno.tangsnow

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import io.github.tan_sno.tangsnow.data.LegalDocs
import io.github.tan_sno.tangsnow.databinding.ActivityLegalBinding

/**
 * 法律文档阅读页：展示「隐私政策摘要 / 完整隐私政策 / 用户协议 / 开源协议」任一篇。
 * 供同意页的「查看完整…」入口与「设置 → 关于棠雪」共用，文本统一取自 [LegalDocs]。
 */
class LegalActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLegalBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityLegalBinding.inflate(layoutInflater)
            setContentView(binding.root)
            enableEdgeToEdge()

            val doc = intent.getIntExtra(EXTRA_DOC, DOC_PRIVACY)
            val (titleRes, textRes) = resolve(doc)
            binding.btnBack.setOnClickListener { finish() }
            binding.txtTitle.setText(titleRes)
            binding.txtBody.text = getString(textRes)
        } catch (t: Throwable) {
            Log.e(TAG, "onCreate failed", t)
            finish()
        }
    }

    companion object {
        private const val TAG = "LegalActivity"
        const val EXTRA_DOC = "tangsnow_legal_doc"

        const val DOC_SUMMARY = 0
        const val DOC_PRIVACY = 1
        const val DOC_AGREEMENT = 2
        /** 开源协议（信息性文本，不参与同意门禁） */
        const val DOC_LICENSES = 3

        /** 由文档 id 得到标题与正文；未识别的 id 一律按完整隐私政策展示。 */
        private fun resolve(doc: Int): Pair<Int, Int> = when (doc) {
            DOC_SUMMARY -> LegalDocs.TITLE_SUMMARY to LegalDocs.SUMMARY
            DOC_AGREEMENT -> LegalDocs.TITLE_AGREEMENT to LegalDocs.AGREEMENT
            DOC_LICENSES -> LegalDocs.TITLE_LICENSES to LegalDocs.LICENSES
            else -> LegalDocs.TITLE_PRIVACY to LegalDocs.PRIVACY
        }

        /** 公开给 About/Consent 页用于“启动失败时原地弹文本”的兜底 */
        fun docResources(doc: Int): Pair<Int, Int> = resolve(doc)

        fun start(context: Context, doc: Int): Boolean {
            return try {
                context.startActivity(
                    Intent(context, LegalActivity::class.java)
                        .putExtra(EXTRA_DOC, doc)
                )
                true
            } catch (t: Throwable) {
                Log.e(TAG, "start failed", t)
                false
            }
        }
    }
}