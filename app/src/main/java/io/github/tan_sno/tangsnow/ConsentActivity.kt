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
import io.github.tan_sno.tangsnow.util.UrlUtils

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
        // ⚠️ 读 extras 必须放在**所有出口之前**：本页有三条出路（已同意直接放行、语言初选
        //    转交、正常渲染后等用户点同意），只要有一条没读过，那条路上的 EXTRA_OPEN_URL /
        //    EXTRA_LIBRARY_TAB 就会被静默丢掉。放在最前面 = 三种情况共用同一份状态。
        adoptIntentExtras(intent)
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

        // 深链已在上方（所有出口之前）统一读过，见 adoptIntentExtras 的说明

        binding.rowFullPrivacy.setOnClickListener { openDocOrInline(LegalActivity.DOC_PRIVACY) }
        binding.rowAgreement.setOnClickListener { openDocOrInline(LegalActivity.DOC_AGREEMENT) }
        binding.btnAgree.setOnClickListener { agreeAndContinue() }
        binding.btnDisagree.setOnClickListener { disagreeAndExit() }
    }

    /**
     * 记下随本页一起进来的「待打开目标」，同意通过后原样转交主界面。
     *
     * 为什么必须抽出来、并在 onCreate 与 [onNewIntent] **两处**都调用：本页是 `singleTask`
     * （见 Manifest），实例已在栈上时系统只会回调 `onNewIntent`，**不会**再走 onCreate ——
     * 而 MainActivity 的门禁重定向（未同意时它把外部链接 / 分享转发到本页）走的正是这条路。
     * 只在 onCreate 里读 extras 的话，就会出现「同意页已经开着 → 从外部点开一条链接 / 在别的
     * 应用里分享一条链接进来 → 用户点同意 → 落在主页」：链接被静默吞掉，且没有任何提示。
     *
     * 读取口径与 MainActivity 的门禁转发一致：`EXTRA_OPEN_URL` 优先；若拿到的是 SEND 分享
     * （正常路径下 extras 已被上游归一化，这里作兜底），从文本里提取第一个 http(s) 链接。
     * 只在拿到非空值时覆盖既有值，避免「拿不到新目标」把先前那次也抹掉。
     *
     * ⚠️ 这里**刻意不做** http/https 白名单：本页不是 URL 的外部入口（Manifest 只声明了
     * MAIN/LAUNCHER），随门禁转交的 extras 最终由主界面那个 exported 入口统一按 scheme
     * 闸门（[MainActivity] 的 externalUrl）筛一遍 —— 校验留在**那一处**是有意的（单一事实
     * 来源，避免两份规则漂移）。因此本函数**依赖**下游闸门存在：将来若有人改动或移走
     * `externalUrl()` 的 scheme 校验，这里必须同步补上。
     */
    private fun adoptIntentExtras(intent: Intent?) {
        if (intent == null) return
        val url = intent.getStringExtra(BrowserOpener.EXTRA_OPEN_URL)
            ?: intent.takeIf { it.action == Intent.ACTION_SEND }
                ?.getStringExtra(Intent.EXTRA_TEXT)
                ?.let { UrlUtils.extractUrlFromText(it) }
        url?.takeIf { it.isNotBlank() }?.let { pendingUrl = it }
        val libTab = intent.getIntExtra(BrowserOpener.EXTRA_LIBRARY_TAB, -1)
        if (libTab in 0..2) pendingLibraryTab = libTab
    }

    /**
     * 本页在栈上时系统投递新 intent 的入口（singleTask 的复用路径）。
     * 除重取 extras 外，还要复核门禁：这期间用户可能在别处（系统设置等）已同意过，那就直接放行。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 同步当前 intent：后续任何地方再读 getIntent() 拿到的都是最新值（与 MainActivity 同口径）
        setIntent(intent)
        adoptIntentExtras(intent)
        if (!ConsentGate.needsConsent(prefs)) openMainAndFinish()
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