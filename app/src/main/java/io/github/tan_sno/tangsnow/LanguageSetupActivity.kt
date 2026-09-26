package io.github.tan_sno.tangsnow

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import io.github.tan_sno.tangsnow.data.LocaleManager
import io.github.tan_sno.tangsnow.data.PreferenceStore
import io.github.tan_sno.tangsnow.data.ThemeController
import io.github.tan_sno.tangsnow.databinding.ActivityLanguageSetupBinding

/**
 * 首次启动的「语言初选」页。
 *
 * ## 为什么需要它
 *
 * 应用内 `values/`（Android 的**默认资源**）是中文 ⇒ 任何**非英文语言环境**（日语、法语、
 * 德语…）的设备都会落到 `values/`，拿到的是**中文界面**；而此时的用户通常还不知道
 * 「设置 → 语言」里有开关。与其重排整个资源目录（`values/` ↔ `values-zh/` 对调，回归风险高、
 * diff 巨大），不如在这些设备上**问一次**，默认选中 English。
 *
 * ## 谁会看到它 —— 三种情况都不打扰
 *
 * 由 [LocaleManager.shouldOfferInitialChoice] 判定，只有「从没问过 + 从没选过 + 设备语言既非
 * 中文也非英文」才进入本页；中文/英文设备上的用户**完全看不到**。
 * 判定放在 [ConsentActivity.onCreate] 且**早于** `setContentView`，避免同意页先用错误语言闪一下。
 *
 * ## 为什么是独立页而不是嵌进同意页
 *
 * 同意页承载法律文本、语义严肃，不该混入设置项；而且**语言必须先定下来**，
 * 同意页才能用正确的语言渲染。
 */
class LanguageSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLanguageSetupBinding
    private lateinit var prefs: PreferenceStore

    /** 当前选择；默认 English（详见 onCreate 的说明） */
    private var chosen: String = LocaleManager.EN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferenceStore(this)
        ThemeController.apply(prefs.theme)

        // 已经问过（含 applyTag 触发重建后的再次进入）：直接把接力棒交给同意页。
        // 这是本页唯一的"续跑"路径，不需要额外的状态字段。
        if (!LocaleManager.shouldOfferInitialChoice(this, prefs)) {
            goNext()
            return
        }

        enableEdgeToEdge()
        binding = ActivityLanguageSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 默认选中 English：能走到本页的用户，其设备语言既不是中文也不是英文，
        // 对他而言英文是唯一可能看懂的国际通用语言（也符合仓库「英文为默认」的定位）。
        binding.languageGroup.check(R.id.radioEnglish)

        binding.languageGroup.setOnCheckedChangeListener { _, checkedId ->
            chosen = if (checkedId == R.id.radioChinese) LocaleManager.ZH else LocaleManager.EN
        }
        binding.btnContinue.setOnClickListener { applyAndContinue() }
    }

    private fun applyAndContinue() {
        binding.btnContinue.isEnabled = false
        // ⚠️ 三件事缺一不可，顺序刻意：
        //  ① markInitialChoiceOffered：先落「已问过」，applyTag 触发的本页重建会在
        //     onCreate 里检测到它并直接 goNext，正好把流程推下去；
        //  ② prefs.appLocale = chosen：**必须显式落盘** —— applyTag 只写 applied_tag
        //     与内存内的 locales，从不写 app_locale；少了这一行，下次冷启动
        //     LocaleManager.apply() 会发现 appLocale("")≠appliedTag("en")，主动把
        //     locales 抹回系统语言 —— 用户在初选页选的语言**第二次冷启动即被撤销**
        //     （设置页靠 ListPreference 框架落盘，故没有这个问题；此页必须自己写）；
        //  ③ applyTag：真正把语言应用到界面。
        LocaleManager.markInitialChoiceOffered(prefs)
        prefs.appLocale = chosen
        LocaleManager.applyTag(this, chosen)
        goNext()
    }

    /**
     * 交棒给同意页。
     * 原 intent **原样转交**：外部以 ACTION_VIEW 调起的深链（含 data 与 extras）
     * 不能被中间这一页吃掉，否则用户点链接进来会落到主页。
     */
    private fun goNext() {
        startActivity(Intent(intent).setClass(this, ConsentActivity::class.java))
        finish()
    }
}
