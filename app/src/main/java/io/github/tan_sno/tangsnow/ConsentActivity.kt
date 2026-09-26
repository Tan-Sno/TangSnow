package io.github.tan_sno.tangsnow

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.github.tan_sno.tangsnow.data.ConsentGate
import io.github.tan_sno.tangsnow.data.LocaleManager
import io.github.tan_sno.tangsnow.data.PreferenceStore
import io.github.tan_sno.tangsnow.data.SessionStore
import io.github.tan_sno.tangsnow.data.ThemeController
import io.github.tan_sno.tangsnow.databinding.ActivityConsentBinding
import io.github.tan_sno.tangsnow.util.LegalText

/**
 * 首次冷启动 / 政策版本更新时的同意页。
 *
 * 设计要点（对齐产品要求）：
 *  - 它是 Launcher 入口；在用户点「同意并继续」之前，本页不初始化 GeckoView、
 *    不加载网页、不发起任何网络请求，仅展示隐私政策摘要；
 *  - 底部左「同意并继续」、右「不同意」，两者使用完全一致的样式，无诱导倾向；
 *  - 点「不同意」立即结束进程，不做任何数据处理；
 *  - 点「同意」立即预热引擎并进入主界面（不弹欢迎窗，加快冷启动）；
 *  - MainActivity 顶部同样设有门禁：任何绕过本页直达浏览器的入口都会被转回这里。
 */
class ConsentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConsentBinding
    private lateinit var prefs: PreferenceStore
    private var pendingUrl: String? = null
    private var pendingLibraryTab: Int = -1
    private var agreed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferenceStore(this)
        ThemeController.apply(prefs.theme)

        // 已同意且政策未更新：直接放行（冷启动被系统再次带到本页的普通情况）
        if (!ConsentGate.needsConsent(prefs)) {
            openMainAndFinish()
            return
        }

        // 语言初选：设备语言既非中文也非英文、且用户从未做过选择时，才插一页问一次
        // （理由见 LanguageSetupActivity 的类注释）。必须**早于** setContentView ——
        // 否则同意页会先用错误语言闪一下。
        //
        // 放在 needsConsent 判断**之后**是刻意的：已经在用本应用的老用户（政策未更新）
        // 不该被突然拦一页；语言初选只服务于「全新的一次同意流程」。
        if (LocaleManager.shouldOfferInitialChoice(this, prefs)) {
            // 原 intent 原样转交 —— 外部 ACTION_VIEW 调起的深链不能被中间这页吃掉
            startActivity(Intent(intent).setClass(this, LanguageSetupActivity::class.java))
            finish()
            return
        }

        enableEdgeToEdge()
        binding = ActivityConsentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 深链：同意后原样转交给主界面
        pendingUrl = intent.getStringExtra(BrowserOpener.EXTRA_OPEN_URL)
        pendingLibraryTab = intent.getIntExtra(BrowserOpener.EXTRA_LIBRARY_TAB, -1)

        binding.rowFullPrivacy.setOnClickListener { openDocOrInline(LegalActivity.DOC_PRIVACY) }
        binding.rowAgreement.setOnClickListener { openDocOrInline(LegalActivity.DOC_AGREEMENT) }
        binding.btnAgree.setOnClickListener { agreeAndContinue() }
        binding.btnDisagree.setOnClickListener { disagreeAndExit() }
    }

    /** 阅读入口：优先开整页；启动失败则原地弹全文，避免“点开就退回”观感 */
    private fun openDocOrInline(doc: Int) {
        if (!LegalActivity.start(this, doc)) {
            val (titleRes, bodyRes) = LegalActivity.docResources(doc)
            AlertDialog.Builder(this)
                .setTitle(titleRes)
                // 与阅读页同一处理：`**…**` 转加粗，别让用户看到星号（见 util/LegalText）
                .setMessage(LegalText.emphasize(this, bodyRes))
                .setPositiveButton(R.string.dlg_ok, null)
                .show()
        }
    }

    private fun agreeAndContinue() {
        if (agreed) return
        agreed = true
        binding.btnAgree.isEnabled = false
        binding.btnDisagree.isEnabled = false
        ConsentGate.agree(prefs)

        // 用户已同意 → 立即把「会话快照读盘」排进后台串行队列。
        //
        // 为什么需要这一步：`TangSnowApplication.onCreate` 里那次预读是按「点击同意前
        // 不加载网页、不处理任何数据」的承诺**主动跳过**的（首启 / 政策更新后必然走到这里）。
        // 若此处不补读，MainActivity 冷启动时 `SessionStore.consume()` 会因预读未就绪
        // 退回**主线程同步读盘 + JSON 解析** —— 正是本轮要消除掉的那笔首帧开销。
        // 位置刻意放在 `agree()` **之后**：绝不在用户同意之前读任何本地数据。
        SessionStore.preload(this)

        // 用户已同意：立即预热 GeckoRuntime（内核初始化最耗时的一步），随即进入主界面。
        // 去掉原先「祝您使用愉快」2 秒弹窗，加快冷启动；绝不早于用户同意执行预热。
        // 预热若耗时较长，按钮文案先给出「正在启动」反馈，避免点击后无响应的错觉。
        binding.btnAgree.text = getString(R.string.consent_starting)
        binding.root.post {
            if (isFinishing || isDestroyed) return@post
            io.github.tan_sno.tangsnow.browser.BrowserSessionManager.warmUp(applicationContext, prefs)
            openMainAndFinish()
        }
    }

    /**
     * 不同意：结束全部行为并退出。
     *
     * 这里**不再主动 killProcess**：同意前本页从未初始化 GeckoView、未加载网页、未发起
     * 任何网络请求，进程残留没有任何数据风险；而强杀进程会让部分 ROM 额外弹出
     * 「应用已停止运行」的系统提示，反而像是崩溃。以 finishAffinity + 移除任务栈收尾，
     * 由系统正常回收空进程即可。
     */
    private fun disagreeAndExit() {
        binding.btnAgree.isEnabled = false
        binding.btnDisagree.isEnabled = false
        finishAffinity()
        finishAndRemoveTask()
    }

    private fun openMainAndFinish() {
        val go = Intent(this, MainActivity::class.java).apply {
            flags = BrowserOpener.FLAGS_BRING_TO_FRONT
        }
        pendingUrl?.let { go.putExtra(BrowserOpener.EXTRA_OPEN_URL, it) }
        if (intent.getBooleanExtra(BrowserOpener.EXTRA_OPEN_NEW_TAB, false)) {
            go.putExtra(BrowserOpener.EXTRA_OPEN_NEW_TAB, true)
        }
        if (pendingLibraryTab in 0..2) {
            go.putExtra(BrowserOpener.EXTRA_LIBRARY_TAB, pendingLibraryTab)
        }
        startActivity(go)
        finish()
    }
}