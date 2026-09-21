package io.github.tan_sno.tangsnow.data

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject

/** 首页快捷方式（定制主页功能，存于偏好 JSON）。 */
data class HomeShortcut(val name: String, val url: String)

/**
 * 类型安全的偏好设置封装。
 *  - 用属性访问，杜绝散落的字符串 key 与强制类型转换。
 *  - getter 对异常值兜底，setter 统一走 [SharedPreferences.apply]。
 */
class PreferenceStore(context: Context) {

    private val prefs: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    /** 当前搜索引擎 id（内置或 custom_N），默认「必应国内」 */
    var searchEngineId: String
        get() = prefs.getString(KEY_SEARCH_ENGINE, SearchEngines.DEFAULT_ID)
            ?: SearchEngines.DEFAULT_ID
        set(value) {
            prefs.edit().putString(KEY_SEARCH_ENGINE, value).apply()
        }

    /**
     * 自定义主页背景图片（持久化的 content URI 字符串）。为空表示未设置。
     */
    var homeImageUri: String?
        get() = prefs.getString(KEY_HOME_IMAGE_URI, null)
        set(value) {
            val edit = prefs.edit()
            if (value == null) edit.remove(KEY_HOME_IMAGE_URI) else edit.putString(KEY_HOME_IMAGE_URI, value)
            edit.apply()
        }

    /**
     * 应用语言标签："zh" / "en" / ""（跟随系统）。
     */
    var appLocale: String
        get() = prefs.getString(KEY_APP_LOCALE, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_APP_LOCALE, value).apply()
        }

    var theme: Theme
        get() = Theme.fromKey(prefs.getString(KEY_THEME, Theme.DEFAULT.key))
        set(value) {
            prefs.edit().putString(KEY_THEME, value.key).apply()
        }

    var fontSize: Float
        get() = prefs.getString(KEY_FONT_SIZE, "1.0")?.toFloatOrNull() ?: 1.0f
        set(value) {
            prefs.edit().putString(KEY_FONT_SIZE, value.toString()).apply()
        }

    var desktopMode: Boolean
        get() = prefs.getBoolean(KEY_DESKTOP_MODE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DESKTOP_MODE, value).apply()
        }

    /** 下载前是否弹确认（关闭后直接开始下载；可在设置中重新打开） */
    var askBeforeDownload: Boolean
        get() = prefs.getBoolean(KEY_ASK_BEFORE_DOWNLOAD, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ASK_BEFORE_DOWNLOAD, value).apply()
        }

    var javaScript: Boolean
        get() = prefs.getBoolean(KEY_JAVASCRIPT, true)
        set(value) {
            prefs.edit().putBoolean(KEY_JAVASCRIPT, value).apply()
        }

    var privateMode: Boolean
        get() = prefs.getBoolean(KEY_PRIVATE_MODE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PRIVATE_MODE, value).apply()
        }

    /**
     * 会话恢复：进程被系统回收后，下次启动自动恢复上次打开的标签页。
     * 仅保存/恢复普通（非无痕）标签，数据只在本机；默认开启，可在设置中关闭。
     */
    var sessionRestoreEnabled: Boolean
        get() = prefs.getBoolean(KEY_SESSION_RESTORE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SESSION_RESTORE, value).apply()
        }

    /** 地址栏位置：false=顶部（默认），true=沉到底部（单手操作，对齐 Firefox） */
    var toolbarBottom: Boolean
        get() = prefs.getBoolean(KEY_TOOLBAR_BOTTOM, false)
        set(value) {
            prefs.edit().putBoolean(KEY_TOOLBAR_BOTTOM, value).apply()
        }

    // --------------------------------------------------------- 跟踪保护（分档）

    /**
     * 跟踪保护模式：off / standard（默认）/ strict / custom。
     * 会话级布尔开关由该模式派生；细粒度配置在内核运行时创建时按此注入。
     * 老版本只有一个“开关”，无新模式值时按旧值迁移（关→off，开/未设置→standard）。
     */
    var trackingMode: String
        get() {
            val stored = prefs.getString(KEY_TRACKING_MODE, null)
            if (stored != null) {
                return if (stored in TRACKING_MODES) stored else TRACKING_STANDARD
            }
            // 旧版本迁移：老布尔开关曾被关闭过 → off，否则沿用默认标准档；
            // 迁移结果写回 tracking_mode，保证偏好 UI（ListPreference）与运行时读取一致
            val migrated = if (prefs.getBoolean(KEY_TRACKING_PROTECTION_LEGACY, true)) {
                TRACKING_STANDARD
            } else TRACKING_OFF
            prefs.edit().putString(KEY_TRACKING_MODE, migrated).apply()
            return migrated
        }
        set(value) {
            if (value !in TRACKING_MODES) return
            prefs.edit().putString(KEY_TRACKING_MODE, value).apply()
        }

    /** 会话级跟踪保护开关 = 模式不是「关闭」（GeckoSessionSettings.useTrackingProtection） */
    val trackingProtection: Boolean
        get() = trackingMode != TRACKING_OFF

    /** 自定义档：拦截跟踪内容（含社交跟踪器） */
    var trackingCustomContent: Boolean
        get() = prefs.getBoolean(KEY_TRACKING_CUSTOM_CONTENT, true)
        set(value) {
            prefs.edit().putBoolean(KEY_TRACKING_CUSTOM_CONTENT, value).apply()
        }

    /** 自定义档：拦截指纹跟踪 */
    var trackingCustomFingerprint: Boolean
        get() = prefs.getBoolean(KEY_TRACKING_CUSTOM_FINGERPRINT, true)
        set(value) {
            prefs.edit().putBoolean(KEY_TRACKING_CUSTOM_FINGERPRINT, value).apply()
        }

    /** 自定义档：拦截挖矿脚本 */
    var trackingCustomCryptominer: Boolean
        get() = prefs.getBoolean(KEY_TRACKING_CUSTOM_CRYPTO, true)
        set(value) {
            prefs.edit().putBoolean(KEY_TRACKING_CUSTOM_CRYPTO, value).apply()
        }

    /** 自定义档：跨站 Cookie 隔离（Total Cookie Protection） */
    var trackingCustomCookieIsolate: Boolean
        get() = prefs.getBoolean(KEY_TRACKING_CUSTOM_COOKIE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_TRACKING_CUSTOM_COOKIE, value).apply()
        }

    /** 全球隐私控制（GPC）：向支持的网站发送“请勿出售/分享我的数据”声明（默认开启） */
    var gpcEnabled: Boolean
        get() = prefs.getBoolean(KEY_GPC_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_GPC_ENABLED, value).apply()
        }

    /** 链接跟踪参数清理：打开链接时移除 utm_ 等跟踪参数（默认开启，与跟踪保护档位相互独立） */
    var paramStrippingEnabled: Boolean
        get() = prefs.getBoolean(KEY_PARAM_STRIPPING, true)
        set(value) {
            prefs.edit().putBoolean(KEY_PARAM_STRIPPING, value).apply()
        }

    /**
     * 禁止截屏 / 隐藏最近任务预览（默认关闭）。
     *
     * 为什么是选项而不是默认开启：`FLAG_SECURE` 会让**用户自己也截不了图**，
     * 对「想把页面存下来」的正常使用是负体验 —— 隐私保护不该顺带剥夺用户的正当能力。
     * 故默认关闭，需要时按需开启（如查看敏感页面）。
     */
    var secureScreen: Boolean
        get() = prefs.getBoolean(KEY_SECURE_SCREEN, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SECURE_SCREEN, value).apply()
        }

    /**
     * 已接受的隐私政策/用户协议版本（见 [LegalDocs.POLICY_VERSION]）。
     * 小于当前版本说明需要重新征得同意（首次安装或政策更新后）。
     */
    var acceptedPolicyVersion: Int
        get() = prefs.getInt(KEY_POLICY_VERSION, 0)
        set(value) {
            prefs.edit().putInt(KEY_POLICY_VERSION, value).apply()
        }

    // --------------------------------------------------------- 定制主页

    /** 首页风格：TangSnow 棠雪（默认）/ mist 薄雾 / plain 极简 / image 自定义图片 */
    var homeStyle: String
        get() = prefs.getString(KEY_HOME_STYLE, Companion.STYLE_TANGSNOW) ?: Companion.STYLE_TANGSNOW
        set(value) {
            prefs.edit().putString(KEY_HOME_STYLE, value).apply()
        }

    /** 首页快捷方式（最多 [Companion.MAX_HOME_SHORTCUTS] 个） */
    /**
     * 首页快捷方式。
     *
     * 带 JSON 解析缓存：`MainActivity.refreshHome()` 在**每次 onResume** 都会取一次，
     * 而每次访问都重解析 JSON 是纯浪费。缓存以「原始 JSON 字符串是否变化」为准，
     * 写入方（增删快捷方式）改了 JSON 即自动失效，无需手动清理 —— 与 customEngines 同一套写法。
     */
    val homeShortcuts: List<HomeShortcut>
        get() {
            val json = prefs.getString(KEY_HOME_SHORTCUTS, null) ?: return emptyList()
            cachedShortcuts?.let { if (json == cachedShortcutsJson) return it }
            val parsed = parseShortcuts(json)
            cachedShortcutsJson = json
            cachedShortcuts = parsed
            return parsed
        }

    private fun parseShortcuts(json: String): List<HomeShortcut> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name", "").trim()
            val url = o.optString("url", "").trim()
            if (name.isEmpty() || url.isEmpty()) null else HomeShortcut(name, url)
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun addHomeShortcut(name: String, url: String): Boolean {
        val n = name.trim()
        val u = Companion.normalizeUrl(url)
        if (n.isEmpty() || u == null) return false
        val list = homeShortcuts
        if (list.any { it.url == u }) return false
        if (list.size >= Companion.MAX_HOME_SHORTCUTS) return false
        return putShortcuts(list + HomeShortcut(n, u))
    }

    fun removeHomeShortcut(index: Int): Boolean {
        val list = homeShortcuts.toMutableList()
        if (index !in list.indices) return false
        list.removeAt(index)
        return putShortcuts(list)
    }

    private fun putShortcuts(list: List<HomeShortcut>): Boolean {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.name).put("url", it.url)) }
        prefs.edit().putString(KEY_HOME_SHORTCUTS, arr.toString()).apply()
        return true
    }

    // --------------------------------------------------------- 自定义搜索引擎

    /** 自定义搜索引擎列表（最多 [Companion.MAX_CUSTOM_ENGINES] 个）。
     *  按原始 JSON 记忆化：地址栏联想/导航等高频路径不再每次重解析。 */
    private var cachedEnginesJson: String? = null
    private var cachedEngines: List<CustomEngine>? = null
    private var cachedShortcutsJson: String? = null
    private var cachedShortcuts: List<HomeShortcut>? = null

    val customEngines: List<CustomEngine>
        get() {
            val json = prefs.getString(KEY_CUSTOM_ENGINES, null)
            cachedEngines?.let { if (json == cachedEnginesJson) return it }
            val parsed = parseEngines(json)
            cachedEnginesJson = json
            cachedEngines = parsed
            return parsed
        }

    private fun parseEngines(json: String?): List<CustomEngine> {
        json ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                CustomEngine(o.optString("name"), o.optString("template"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addCustomEngine(name: String, template: String): Boolean {
        val trimmedName = name.trim()
        val trimmedTemplate = template.trim()
        if (trimmedName.isEmpty() || trimmedTemplate.isEmpty()) return false
        // 安全边界：自定义引擎模板必须是网页地址，杜绝 javascript:/file: 等 scheme 混入
        if (!Companion.isHttpTemplate(trimmedTemplate)) return false
        val list = customEngines
        if (list.any { it.name == trimmedName || it.template == trimmedTemplate }) return false
        if (list.size >= Companion.MAX_CUSTOM_ENGINES) return false

        val arr = JSONArray()
        (list + CustomEngine(trimmedName, trimmedTemplate)).forEach {
            arr.put(JSONObject().put("name", it.name).put("template", it.template))
        }
        // 统一走 apply()：与其余 setter 一致，且避免在主线程同步写盘
        prefs.edit().putString(KEY_CUSTOM_ENGINES, arr.toString()).apply()
        return true
    }

    fun removeCustomEngine(index: Int): Boolean {
        val list = customEngines.toMutableList()
        if (index !in list.indices) return false
        list.removeAt(index)
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("name", it.name).put("template", it.template))
        }
        prefs.edit().putString(KEY_CUSTOM_ENGINES, arr.toString()).apply()
        // 修正被删引擎之后自定义 id 的引用错位：
        //  - 选中的正是被删项 → 回退默认
        //  - 选中的在被删项之后 → 下标减 1 保持指向同一引擎
        // 判定本身抽到 companion 的 remapSelectedEngine，那样才能被单测覆盖。
        // 只在真的变了才写回：setter 会落盘，值没变就不该产生一次多余的写入。
        val fixedId = Companion.remapSelectedEngine(searchEngineId, index)
        if (fixedId != searchEngineId) searchEngineId = fixedId
        return true
    }

    companion object {
        /**
         * 首页风格「棠雪」（默认）。
         * 取值随品牌统一为 `tangsnow`；因 applicationId 变更使安装沙箱整体更新，
         * 不存在改名前的历史偏好，故无需兼容旧取值。
         */
        const val STYLE_TANGSNOW = "tangsnow"
        const val STYLE_MIST = "mist"
        const val STYLE_PLAIN = "plain"
        const val STYLE_IMAGE = "image"
        const val MAX_HOME_SHORTCUTS = 8

        /** 自定义搜索模板只允许 http/https（安全边界，见 [addCustomEngine]） */
        fun isHttpTemplate(template: String): Boolean {
            val t = template.trim().lowercase()
            return t.startsWith("https://") || t.startsWith("http://")
        }

        /**
         * 删除第 [removedIndex] 个自定义引擎后，把「当前选中的引擎 id」修正到仍指向同一个引擎。
         *
         * 为什么单独抽成纯函数：自定义引擎的 id 是 `custom_<下标>`，删掉一个会让其后所有下标
         * 前移，这段算术一旦出错，表现是「删掉 A，选中的却变成了 B」—— 靠读代码看不出来，
         * 只能靠断言兜住（见 `PreferenceStoreSanitizeTest`）。
         *
         * 规则：非自定义 id 原样返回；选中的正是被删项、或 id 已不可解析 → 回退默认引擎；
         * 选中的在被删项之后 → 下标减 1（仍指向同一个引擎）；在被删项之前 → 原样不变。
         */
        internal fun remapSelectedEngine(selectedId: String, removedIndex: Int): String {
            if (!selectedId.startsWith(SearchEngines.CUSTOM_ID_PREFIX)) return selectedId
            val selected = selectedId.removePrefix(SearchEngines.CUSTOM_ID_PREFIX).toIntOrNull()
                ?: return SearchEngines.DEFAULT_ID
            return when {
                selected == removedIndex -> SearchEngines.DEFAULT_ID
                selected > removedIndex -> "${SearchEngines.CUSTOM_ID_PREFIX}${selected - 1}"
                else -> selectedId
            }
        }

        /** 补全协议；非法输入返回 null。
         *  仅接受 http/https 或「裸域名」（自动补 https）：
         *  不放行 javascript:/file:/intent: 等自定义 scheme，避免快捷方式被用于
         *  执行脚本或访问本地文件（安全边界）。含空白的一律视为无效。 */
        fun normalizeUrl(input: String): String? {
            val trimmed = input.trim()
            if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
            val lower = trimmed.lowercase()
            return when {
                lower.startsWith("https://") || lower.startsWith("http://") -> trimmed
                // 其它 scheme（如 file://、javascript://、intent://）一律拒绝
                trimmed.contains("://") -> null
                trimmed.contains('.') -> "https://$trimmed"
                else -> null
            }
        }

        // 以下为**设置项存储键**，必须与 res/xml/preferences.xml 里的 app:key 逐字一致；
        // 公开出来供 SettingsFragment 复用，避免同一个键名在 Kotlin 里写两遍（改一处漏一处即静默失效）。
        const val KEY_SEARCH_ENGINE = "search_engine"
        const val KEY_THEME = "theme"
        const val KEY_FONT_SIZE = "font_size"
        const val KEY_DESKTOP_MODE = "desktop_mode"
        const val KEY_ASK_BEFORE_DOWNLOAD = "ask_before_download"
        const val KEY_JAVASCRIPT = "javascript"
        const val KEY_PRIVATE_MODE = "private_mode"
        const val KEY_SECURE_SCREEN = "secure_screen"
        const val KEY_SESSION_RESTORE = "session_restore_enabled"
        const val KEY_TOOLBAR_BOTTOM = "toolbar_bottom"
        /** 老版本的单布尔开关键：仅用于迁移到新模式（tracking_mode 不存在时读取） */
        private const val KEY_TRACKING_PROTECTION_LEGACY = "tracking_protection"
        const val KEY_TRACKING_MODE = "tracking_mode"
        const val KEY_TRACKING_CUSTOM_CONTENT = "tracking_custom_content"
        const val KEY_TRACKING_CUSTOM_FINGERPRINT = "tracking_custom_fingerprint"
        const val KEY_TRACKING_CUSTOM_CRYPTO = "tracking_custom_crypto"
        const val KEY_TRACKING_CUSTOM_COOKIE = "tracking_custom_cookie"
        const val KEY_GPC_ENABLED = "privacy_gpc_enabled"
        const val KEY_PARAM_STRIPPING = "privacy_param_stripping"
        private const val KEY_POLICY_VERSION = "accepted_policy_version"
        private const val KEY_HOME_STYLE = "home_style"
        private const val KEY_HOME_SHORTCUTS = "home_shortcuts"
        private const val KEY_HOME_IMAGE_URI = "home_image_uri"
        private const val KEY_CUSTOM_ENGINES = "custom_engines"
        const val KEY_APP_LOCALE = "app_locale"
        private const val MAX_CUSTOM_ENGINES = 8

        const val TRACKING_OFF = "off"
        const val TRACKING_STANDARD = "standard"
        const val TRACKING_STRICT = "strict"
        const val TRACKING_CUSTOM = "custom"
        val TRACKING_MODES = listOf(
            TRACKING_OFF, TRACKING_STANDARD, TRACKING_STRICT, TRACKING_CUSTOM
        )
    }
}
