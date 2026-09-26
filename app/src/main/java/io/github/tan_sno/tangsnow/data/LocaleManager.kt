package io.github.tan_sno.tangsnow.data

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * 应用语言切换：中文 / English / 跟随系统。
 * 通过 AppCompatDelegate 的 per-app locales 接口落地，
 * 不写 manifest 也能在所有 Activity 上即时刷新。
 *
 * ## 为什么需要"已应用标记"
 * Android 13+ 允许用户在**系统设置**里单独为本应用指定语言（per-app language），
 * 该选择由系统保存。其存储位置与应用内偏好是两套东西，于是出现冲突：
 * 用户在系统里把棠雪设为英文，而应用内偏好仍是「跟随系统」（空值）；
 * 若每次冷启动都无条件 `setApplicationLocales(空列表)`，系统里的选择会被抹掉。
 *
 * 因此这里记录"上次由本应用应用过的语言值"（存在独立的 SharedPreferences 里，
 * 不占用应用设置项）：只有偏好值与它不同才真正调用系统接口。
 * 用户在系统设置里的选择因此得以保留，直到用户在应用内重新选择语言为止。
 */
object LocaleManager {

    private const val PREFS_NAME = "tangsnow_locale"
    private const val KEY_APPLIED_TAG = "applied_tag"

    private const val SYSTEM = ""      // 跟随系统
    const val ZH = "zh"
    const val EN = "en"

    /** 将 [tag] 标准化为受支持的列表（系统 / zh / en） */
    fun normalize(tag: String?): String = when (tag) {
        EN, ZH -> tag
        else -> SYSTEM
    }

    /**
     * 冷启动应用偏好里的语言。
     * 仅在偏好值与上次已应用值不同时才调用系统接口，避免覆盖用户在系统设置里的
     * per-app 语言选择（详见类注释）。
     */
    fun apply(context: Context, prefs: PreferenceStore) {
        val tag = normalize(prefs.appLocale)
        if (tag == appliedTag(context)) return
        applyTag(context, tag)
    }

    /**
     * 直接按 [tag] 应用语言（用户在应用内显式选择时调用）。
     * 设置页的 OnPreferenceChangeListener 必须先于偏好持久化执行（AndroidX 顺序），
     * 因此监听器里不能重读存储值，必须用回调给的 newValue 调本方法。
     */
    fun applyTag(context: Context, tag: String?) {
        val normalized = normalize(tag)
        val list = if (normalized.isEmpty()) LocaleListCompat.getEmptyLocaleList()
        else LocaleListCompat.forLanguageTags(normalized)
        AppCompatDelegate.setApplicationLocales(list)
        setAppliedTag(context, normalized)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ------------------------------------------------------- 首次启动的语言初选

    /**
     * 首次启动是否需要展示「语言初选」页。
     *
     * 背景：仓库对外文档已以英文为默认，但**应用内** `values/`（Android 的默认资源）是中文
     * ⇒ 任何非英文语言环境（日语、法语、德语…）的设备都会落到 `values/`，拿到**中文界面**。
     * 与其重排整个资源目录（回归风险高、diff 巨大），不如在这些设备上**问一次**。
     *
     * 三种情况都不打扰：
     *  ① 已经问过（[PreferenceStore.languageChoiceOffered]）；
     *  ② 用户已显式选过语言（[PreferenceStore.appLocale] 非空）—— 注意空值是「跟随系统」的
     *     正常取值，不能拿它当"没问过"的依据，那件事由标记 ① 负责；
     *  ③ 设备语言本来就是中文或英文 —— 界面本就正确，再弹纯属多余。
     */
    fun shouldOfferInitialChoice(context: Context, prefs: PreferenceStore): Boolean =
        shouldOffer(prefs.languageChoiceOffered, prefs.appLocale, deviceLanguage(context))

    /** [shouldOfferInitialChoice] 的纯逻辑部分，抽出来便于 JVM 单测直接覆盖 */
    internal fun shouldOffer(offered: Boolean, appLocale: String, deviceLanguage: String): Boolean {
        if (offered) return false
        if (appLocale.isNotEmpty()) return false
        val lang = deviceLanguage.trim().lowercase()
        // 取不到设备语言就无从判断「用户是否看得懂中文」，宁可不打扰
        //（用户仍可在「设置 → 语言」里自己改）
        if (lang.isEmpty()) return false
        return lang != "zh" && lang != "en"
    }

    /** 记录「已经问过」：用户选了「跟随系统」也照样算问过，避免每次冷启动重复弹出 */
    fun markInitialChoiceOffered(prefs: PreferenceStore) {
        prefs.languageChoiceOffered = true
    }

    /**
     * 设备当前的语言（小写语言码，如 `zh` / `en` / `ja`）。
     * 取 [android.content.res.Configuration.locales] 的首项 —— API 24+ 已取代废弃的 `locale` 字段。
     */
    internal fun deviceLanguage(context: Context): String {
        val locales = context.resources.configuration.locales
        if (locales.isEmpty) return ""
        return locales[0].language.orEmpty().lowercase()
    }

    /** 上次由本应用应用过的语言；null = 从未应用过（首次启动） */
    private fun appliedTag(context: Context): String? =
        prefs(context).getString(KEY_APPLIED_TAG, null)

    private fun setAppliedTag(context: Context, tag: String) {
        prefs(context).edit().putString(KEY_APPLIED_TAG, tag).apply()
    }
}
