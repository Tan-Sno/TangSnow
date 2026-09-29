package io.github.tan_sno.tangsnow.data

import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * 应用语言切换：中文 / English / 跟随系统。
 * 通过 AppCompatDelegate 的 per-app locales 接口落地，
 * 不写 manifest 也能在所有 Activity 上即时刷新。
 *
 * ## 为什么需要"已应用标记"，以及它为什么**只**对 API 33+ 生效
 * Android 13（API 33）允许用户在**系统设置**里单独为本应用指定语言（per-app language），
 * 该选择由**系统**保存，与应用内偏好是两套东西：用户在系统里把棠雪设为英文，
 * 而应用内偏好仍是「跟随系统」（空值）；若每次冷启动都无条件
 * `setApplicationLocales(空列表)`，系统里的选择会被抹掉。因此这里记录
 * "上次由本应用应用过的语言值"（独立的 SharedPreferences，不占应用设置项），
 * 只有偏好值与它不同才真正调用系统接口 —— 用户在系统设置里的选择因此得以保留。
 *
 * ⚠️ API 32 及以下**没有**可被覆盖的系统级存储：`setApplicationLocales` 只对当前进程生效，
 * 而 AppCompat 仅在清单声明了 `AppLocalesMetadataHolderService` + `autoStoreLocales` 时
 * 才代为落盘（本应用未声明）⇒ 这一区间"已应用标记"必须被忽略、每次都重放，
 * 否则重启后不再调用系统接口，用户选的 zh/en 会静默回退成系统语言且不自愈。
 * 判定收在 [needReapply] 一处。
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
     * 是否真的调用系统接口由 [needReapply] 判定（详见类注释与该方法）。
     */
    fun apply(context: Context, prefs: PreferenceStore) {
        val tag = normalize(prefs.appLocale)
        if (!needReapply(Build.VERSION.SDK_INT, tag, appliedTag(context))) return
        applyTag(context, tag)
    }

    /**
     * 冷启动时是否必须重新调用 `AppCompatDelegate.setApplicationLocales`。
     *
     * - **API 33+**：该接口落到**系统级** per-app language，用户可能在系统设置里改过；
     *   仅当偏好值与上次已应用值不同才重放，以免抹掉用户的选择。
     * - **API ≤ 32**：没有系统级存储（见类注释），必须**每次冷启动**重放；
     *   若沿用"与已应用值相同就跳过"，用户显式选过的语言会在进程重启后静默回退。
     *
     * 抽成纯函数（sdkInt 由调用方传入而非直接读 Build）以便 JVM 单测钉住真值表。
     */
    internal fun needReapply(sdkInt: Int, tag: String, appliedTag: String?): Boolean =
        sdkInt < Build.VERSION_CODES.TIRAMISU || tag != appliedTag

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
