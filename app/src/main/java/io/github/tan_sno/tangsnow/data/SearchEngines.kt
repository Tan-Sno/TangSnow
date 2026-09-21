package io.github.tan_sno.tangsnow.data

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import io.github.tan_sno.tangsnow.R
import io.github.tan_sno.tangsnow.util.percentEncode

/**
 * 一个搜索引擎 = id（偏好里的存储值）+ 展示名 + 搜索模板。
 * 模板里用 {q}（或 %s）代表关键词占位；若未含占位符则按 q= 追加。
 *
 * 展示名有两个来源，**内置引擎一律用 [labelRes]**：
 *  - 内置引擎：`labelRes` 指向 `strings.xml`，中英各一份，随系统语言切换；
 *  - 自定义引擎：`label` 为用户自己起的名字，无处可翻译。
 * 这样「名称」只有单一事实来源，不再出现「Kotlin 里硬编码中文 + strings.xml 另写一份」的重复，
 * 也就不会出现英文版缺失某个引擎名的情况。
 */
data class SearchEngine(
    val id: String,
    val template: String,
    /** 展示名：仅供自定义引擎使用；内置引擎留空并改由 [labelRes] 提供 */
    val label: String = "",
    /** 内置引擎的本地化名称资源；为 null 时回退 [label] */
    @StringRes val labelRes: Int? = null,
    val isCustom: Boolean = false,
    /** 搜索结果页的关键词参数名，用于把结果页 URL 还原成用户输入的关键词 */
    val queryParam: String? = null,
) {
    fun searchUrl(query: String): String {
        // 用自带的纯函数 percentEncode 而非 android.net.Uri.encode：
        // 语义一致（逐字节相同），但让本方法可在 JVM 单测里直接验证
        val encoded = percentEncode(query.trim())
        val substituted = template.replace("{q}", encoded).replace("%s", encoded)
        if (substituted != template) return substituted
        // 模板不含占位符：视为没有查询参数，补一个 q
        val base = template.ifBlank { DEFAULT_FALLBACK }
        val glue = if (base.contains('?')) "&q=" else "?q="
        return base + glue + encoded
    }

    private companion object {
        const val DEFAULT_FALLBACK = "https://www.bing.com/search"
    }
}

/**
 * 内置搜索引擎（含国内热门）+ 从偏好里读出的自定义引擎。
 * 默认「必应国内」：在国内运营商网络下 cn.bing.com 一般可达且响应稳定，
 * 「Bing 国际」(global.bing.com) 在国内多数网络下被劫持/不可达，仅作为可选条目保留，
 * 供具备国际访问能力的用户在「更多 → 切换引擎」中自行选择。
 */
object SearchEngines {

    const val DEFAULT_ID = "bing_cn"

    /**
     * 自定义引擎 id 前缀：id 形如 `custom_<在自定义列表中的下标>`（见 [all]）。
     * 收在这里是因为「生成 id」与「按 id 回推下标」分处两个文件（另一处在
     * [PreferenceStore.removeCustomEngine]），字面量各写一份必然漂移。
     */
    const val CUSTOM_ID_PREFIX = "custom_"

    val builtins: List<SearchEngine> = listOf(
        // 国内版：默认入口；cn.bing.com 在国内可直接访问
        SearchEngine(
            "bing_cn", "https://cn.bing.com/search?q={q}",
            labelRes = R.string.engine_bing_cn, queryParam = "q",
        ),
        // 国际版：仅供有国际访问能力的用户选择
        SearchEngine(
            "bing", "https://global.bing.com/search?q={q}&mkt=en-US&setlang=zh-Hans",
            labelRes = R.string.engine_bing, queryParam = "q",
        ),
        SearchEngine(
            "baidu", "https://www.baidu.com/s?wd={q}",
            labelRes = R.string.engine_baidu, queryParam = "wd",
        ),
        SearchEngine(
            "sogou", "https://www.sogou.com/web?query={q}",
            labelRes = R.string.engine_sogou, queryParam = "query",
        ),
        SearchEngine(
            "so360", "https://www.so.com/s?q={q}",
            labelRes = R.string.engine_so360, queryParam = "q",
        ),
        SearchEngine(
            "sm", "https://m.sm.cn/s?q={q}",
            labelRes = R.string.engine_sm, queryParam = "q",
        ),
        // 以下引擎官方图标不可达（Google / DuckDuckGo 在国内被屏蔽，且其官方图标为
        // .ico 格式 Android 系统无法解码），故保留条目但用放大镜兜底，由用户自行启用与切换。
        SearchEngine(
            "google", "https://www.google.com/search?q={q}",
            labelRes = R.string.engine_google, queryParam = "q",
        ),
        SearchEngine(
            "ddg", "https://duckduckgo.com/?q={q}",
            labelRes = R.string.engine_ddg, queryParam = "q",
        ),
    )

    /** 内置 + 用户自定义（自定义在前置导入时计算 id 后接在其后） */
    fun all(prefs: PreferenceStore): List<SearchEngine> {
        val customs = prefs.customEngines
            .mapIndexed { i, ce ->
                SearchEngine(
                    id = "$CUSTOM_ID_PREFIX$i",
                    template = ce.template,
                    label = ce.name,
                    isCustom = true,
                )
            }
        return builtins + customs
    }

    /**
     * 按 id 查找引擎，缺失/未知回退默认引擎。
     *
     * 兜底用 `firstOrNull` 而非 `first`：`builtins` 与 [DEFAULT_ID] 的一致性靠约定维持，
     * 一旦有人改了常量却漏改列表，`first` 会直接抛 NoSuchElementException 崩在启动路径上；
     * 这里退化为「取第一个内置引擎」，功能可用优先。
     */
    fun find(all: List<SearchEngine>, id: String?): SearchEngine =
        all.firstOrNull { it.id == id }
            ?: builtins.firstOrNull { it.id == DEFAULT_ID }
            ?: builtins.first()

    /** 当前选中的引擎（按偏好 id 查找，缺失/未知回退默认） */
    fun current(prefs: PreferenceStore): SearchEngine =
        find(all(prefs), prefs.searchEngineId)

    /** 引擎在界面上显示的本地化名称；自定义引擎沿用用户起的名字 */
    fun localizedLabel(context: Context, engine: SearchEngine): String =
        engine.labelRes?.let(context::getString) ?: engine.label

    /**
     * 把搜索结果页 URL 还原为用户输入的关键词（对齐 Via：搜「1」地址栏就显示「1」）。
     * 非搜索结果页返回 null。
     */
    fun displayForUrl(url: String, engines: List<SearchEngine>): String? {
        val uri = Uri.parse(url) ?: return null
        val host = uri.host?.lowercase() ?: return null
        for (engine in engines) {
            val param = engine.queryParam ?: continue
            val tplHost = templateHostOf(engine.template) ?: continue
            if (!hostMatches(host, engine.id, tplHost)) continue
            val query = uri.getQueryParameter(param)
            if (!query.isNullOrBlank()) return query
        }
        return null
    }

    /**
     * 该主机是否属于这个搜索引擎。
     *
     * 除模板主机与其子域外，还要认"同一引擎的其它官方主机"：用户实际在
     * `www.bing.com` 上搜索，而模板写的是 `cn.bing.com`——只比对模板的话，
     * 地址栏会显示整条 URL 而不是关键词，与"搜完只显示关键词"的目标不符。
     *
     * 纯字符串运算（不碰 android.net.Uri），故声明为 internal 以便单元测试直接覆盖。
     */
    /**
     * 从模板里取出主机（**纯字符串运算**，不碰 `android.net.Uri`）。
     *
     * 为什么不用 `Uri.parse(template).host`：
     *  ① 本文件的 `hostMatches` / `percentEncode` 都刻意避开 Uri，就是为了能在 JVM 单测里直接验证
     *     —— Uri 在单元测试中是抛异常的桩；
     *  ② [displayForUrl] 在**每次页面跳转**都会对**每个引擎**调用一次，现场 parse 属不必要的分配。
     *
     * 输入如 `https://cn.bing.com/search?q={q}` → 返回 `cn.bing.com`（小写、无端口）。
     * 取不到主机时返回 null，调用方跳过该引擎。
     */
    internal fun templateHostOf(template: String): String? {
        // 无 '://' 时按整串处理（模板本可写成 cn.bing.com/search?q= 这种省略协议的形式）
        val afterScheme = template.substringAfter("://", template)
        val hostPart = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        // 去掉 userinfo@ 与端口
        val host = hostPart.substringAfterLast('@').substringBefore(':')
        return host.takeIf { it.isNotBlank() }?.lowercase()
    }

    internal fun hostMatches(host: String, engineId: String, templateHost: String): Boolean {
        val bare = host.removePrefix("www.")
        val candidates = buildList {
            add(templateHost)
            addAll(HOST_ALIASES[engineId].orEmpty())
        }
        return candidates.any { candidate ->
            val bareCandidate = candidate.removePrefix("www.")
            bare == bareCandidate || bare.endsWith(".$bareCandidate")
        }
    }

    /**
     * 同一搜索引擎的**模板主机之外**的其它官方主机（全部小写、可含子域）。
     * 与模板主机等价（仅差 `www.` 前缀）或与之完全重复的条目不在此重复登记。
     */
    private val HOST_ALIASES: Map<String, List<String>> = mapOf(
        // 模板主机 cn.bing.com；bing.com 是同一品牌在无区域前缀时的主机
        "bing_cn" to listOf("bing.com"),
        "baidu" to listOf("m.baidu.com"),
        "sogou" to listOf("m.sogou.com"),
        "so360" to listOf("m.so.com"),
        "sm" to listOf("sm.cn"),
        "ddg" to listOf("html.duckduckgo.com"),
    )
}

/** 自定义引擎的数据形态（存于偏好 JSON）。 */
data class CustomEngine(val name: String, val template: String)
