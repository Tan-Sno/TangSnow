package io.github.tan_sno.tangsnow.extension

import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import io.github.tan_sno.tangsnow.R
import io.github.tan_sno.tangsnow.data.AppHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension
import java.util.concurrent.ConcurrentHashMap

/**
 * 可安装扩展目录（全部来自 Mozilla 官方签名源 AMO，绝不使用第三方镜像）。
 *
 * 合规边界：只收录「隐私保护 / 工具」类扩展。判定标准是**该扩展的首要定位**，
 * 而不是它顺带能做多少事：
 *  - ❌ 首要定位为「广告拦截」的一律不收录（uBlock Origin、AdGuard、**Ghostery** 等）。
 *    广告拦截在部分司法辖区会被定性为不正当竞争（见 (2018)京73民终433号火狐案、
 *    (2018)京73民终397号酷6案，以及 2025 年 720 浏览器案），主动推荐这类扩展风险最高。
 *    Ghostery 的官方定位即为 "Ad Blocker & Privacy"、默认开启广告拦截，故一并移除。
 *  - ✅ 首要定位为「跟踪器保护 / 隐私工具」的收录（Privacy Badger、ClearURLs 等）。
 * 本应用自身的内置跟踪保护同样不含广告拦截名单（只请求 CONTENT/SOCIAL/CRYPTOMINING/
 * FINGERPRINTING，**从不请求 AD/ANALYTIC**）。
 *
 * 图标：直接内嵌从 AMO 官方扩展包（Mozilla 签名 xpi）中提取的官方图标，
 * 离线即显、不受网络波动影响；未收录图标的扩展用字母头像兜底。
 *
 * 名称与直链：运行时通过 AMO API v5 解析（官方元数据实时对接）。
 *
 * 地区限制：AMO 可能对特定扩展/地区返回 HTTP 451（Mozilla 侧合规行为）；
 * 检测到 451 时界面标记「地区受限」，网络环境变化后自动恢复可安装状态。
 */
object ExtensionCatalog {

    /**
     * AMO 官方扩展商店首页。
     *
     * 用途：应用内「前往官方扩展商店」入口。它与本目录的区别是——
     * 本目录是**精选子集**（只收录隐私保护 / 工具类，见上方合规边界），
     * 而这里是 Mozilla 官方全量商店，交给用户在浏览内核里自行浏览与安装。
     * 不预设语言路径段，由 AMO 按浏览器语言自动本地化。
     */
    /**
     * AMO 官方源地址的**单一事实来源**。
     *
     * 为什么收在这里：域名此前散在多处 —— 本文件的几处 URL 拼接之外，还有两处**安全校验**
     * （`ExtInstallCoordinator` 的下载地址前缀校验、`ExtensionsActivity` 的自定义链接 host 校验）。
     * 「扩展只走 Mozilla 官方源」是本项目对外承诺的一条口径，散着写就有"漏改一处"的风险
     * （漏改校验处即等同于放宽准入），故统一到这两个常量。
     */
    const val AMO_HOST = "addons.mozilla.org"
    const val AMO_ORIGIN = "https://" + AMO_HOST

    const val OFFICIAL_STORE_URL = AMO_ORIGIN + "/firefox/extensions/"

    data class Entry(
        val slug: String,
        val addonId: Int,
        val name: String,
        /** 简介的资源 id（中英各一份，避免中文硬编码导致英文版缺失） */
        @StringRes
        val descRes: Int,
        /** 字母头像兜底底色（资源 id；色值统一在 colors.xml，保持配色单一事实来源） */
        @ColorRes
        val colorRes: Int,
        /** 内嵌官方图标资源；null = 无（用字母头像） */
        val iconRes: Int?,
    ) {
        /** AMO「latest」稳定直链（官方域名，始终指向当前最新版） */
        val xpiUrl: String =
            "${ExtensionCatalog.AMO_ORIGIN}/firefox/downloads/latest/$slug/addon-$addonId-latest.xpi"

        /** 官方扩展详情页 */
        val officialPage: String =
            "${ExtensionCatalog.AMO_ORIGIN}/firefox/addon/$slug/"
    }

    /** 本次应用生命周期内安装成功的记录：AMO slug → WebExtension id。
     *  以下集合会被 IO 线程（hydrate）与主线程（列表绑定）同时读写，
     *  必须全部使用并发容器，避免遍历期间并发修改导致崩溃。 */
    val installedIdsBySlug = ConcurrentHashMap<String, String>()

    /** 正在安装的扩展（slug）；用于界面按钮态与孤儿状态自愈 */
    val installing = ConcurrentHashMap.newKeySet<String>()

    /** 安装启动时间戳（slug → SystemClock.elapsedRealtime），用于超时自愈 */
    val installStartedAt = ConcurrentHashMap<String, Long>()

    /**
     * 自建下载兜底阶段的进度（slug → 1..100）。
     *
     * 只有「内核直链安装」那一步没走通、改用应用自建 OkHttp 下载时才可能有值 ——
     * 内核通道不暴露进度，服务端未给 `Content-Length` 时也算不出来（那时不会写入本表）。
     * 与 [installing] 一样是**进程级**状态：本页重建后仍能接着显示，不必从头再来。
     */
    val installProgress = ConcurrentHashMap<String, Int>()

    /** 官方确认的地区受限扩展（AMO 返回 451） */
    val regionBlocked = ConcurrentHashMap.newKeySet<String>()

    // --------------------------------------------------------- 实时官方元数据

    /** slug → AMO API 解析出的当前版本直链 */
    val resolvedUrls = ConcurrentHashMap<String, String>()

    /** slug → 官方本地化名称 */
    val displayNames = ConcurrentHashMap<String, String>()

    /**
     * 从 AMO 官方 API 拉取名称与当前版本直链。
     * @return HTTP 状态码（200 成功 / 451 地区受限 / 0 网络失败）
     */
    suspend fun hydrate(entry: Entry): Int = withContext(Dispatchers.IO) {
        try {
            val req = AppHttp.get(
                "$AMO_ORIGIN/api/v5/addons/addon/${entry.slug}/",
                acceptJson = true,
            ).build()
            AppHttp.client.newCall(req).execute().use { resp ->
                val code = resp.code
                if (code != 200) {
                    if (code == 451) regionBlocked.add(entry.slug)
                    return@use code
                }
                regionBlocked.remove(entry.slug)
                val body = resp.body.string()
                val json = JSONObject(body)

                json.optJSONObject("name")?.let { names ->
                    val localized = names.optString("zh-CN", "")
                        .ifBlank { names.optString("en-US", "") }
                    if (localized.isNotBlank()) displayNames[entry.slug] = localized
                }
                json.optJSONObject("current_version")?.optJSONObject("file")
                    ?.optString("url", "")?.takeIf { it.isNotBlank() }?.let { fileUrl ->
                        resolvedUrls[entry.slug] = fileUrl
                    }
                code
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 取消要原样传播，不能降级成状态码 0：「0 = 网络失败」会让调用方把
            // 已取消的协程当成一次真实失败而继续走重试/报错分支。
            throw e
        } catch (e: Exception) {
            0
        }
    }

    /**
     * 安装地址：**优先目录里拼出的「latest」直链**，API 解析出的 file 直链只作补充候选。
     *
     * ⚠️ 2026-09-18 实测更正（此前本段注释有误，已按实测改写）：
     *  `…/downloads/latest/<slug>/addon-<id>-latest.xpi` 会 **302** 跳到
     *  `https://addons.mozilla.org/firefox/downloads/file/<fileId>/<name>.xpi`
     *  —— **同一域名、同一端点**，两者仅差一次跳转，**不构成网络层面的冗余**。
     *  （原注释称前者「AMO 直接供包、实测可下载」、后者「会跳 CDN、某些网络下黑洞」，
     *  与实测不符：两者终点相同，命中同一域名 addons.mozilla.org。）
     *
     * 仍然让 latest 优先的理由：它不依赖 API 解析结果（even 解析失败也恒可用），
     * 且始终指向当前最新版本。真正的**第二条通路**是「把官方直链交给内核安装」那一步
     * （见 `ExtInstallCoordinator.performInstall` 第 ② 步），而不是这两个 URL 之间。
     */
    fun bestUrl(entry: Entry): String = entry.xpiUrl

    /** API 解析出的当前版本 file 直链；与 [bestUrl] 指向同一端点，仅作补充候选 */
    fun resolvedUrl(entry: Entry): String? = resolvedUrls[entry.slug]

    /**
     * 判断某推荐项是否已安装。
     *
     * **精确匹配**，只认两个来源：
     *  1. 本会话的安装记录（slug → 扩展 id）；
     *  2. 从已安装扩展的 AMO 列表页地址解析出的 slug。
     *
     * 旧实现额外按「显示名」兜底匹配，会误判：同名但不同来源的扩展一旦被认成「已安装」，
     * 按钮就永久停在「已安装（不可点）」，用户再也装不上（P1-16）。
     */
    fun isInstalled(entry: Entry, installedSlugs: Set<String>): Boolean =
        installedIdsBySlug.containsKey(entry.slug) || entry.slug in installedSlugs

    /**
     * 从已安装扩展的元数据解析它的 AMO slug：依据是 AMO 列表页地址
     * （`https://addons.mozilla.org/…/addon/<slug>/`）。
     * 非 AMO 来源（本地导入的第三方包等）解析不到，按「不在精选目录内」处理。
     */
    fun slugOf(ext: WebExtension): String? =
        ext.metaData.amoListingUrl
            ?.let { ADDON_PAGE_PATTERN.find(it)?.groupValues?.get(1) }
            ?.takeIf { it.isNotBlank() }

    /** 从 AMO 列表页地址中截取 slug */
    private val ADDON_PAGE_PATTERN = Regex("""/addon/([^/?#]+)""")

    /**
     * 卸载后移除「本会话已安装」记录。
     * 若不同步清除，可安装列表会一直把该扩展判为“已安装”而无法再次获取。
     */
    fun forgetInstalled(extId: String?) {
        if (extId.isNullOrBlank()) return
        val slug = installedIdsBySlug.entries.firstOrNull { it.value == extId }?.key
        if (slug != null) installedIdsBySlug.remove(slug)
    }

    val all: List<Entry> = listOf(
        // 全部为「隐私保护 / 工具」类扩展；首要定位为广告拦截 / 去广告的一律不收录。
        // 已按该标准移除：Ghostery（Ad Blocker & Privacy）、SponsorBlock（跳过赞助片段）。
        // 对应的 descRes / colorRes / iconRes 资源也已一并删除（否则 lint UnusedResources 会报未使用）。
        Entry(
            "privacy-badger17", 506646, "Privacy Badger",
            R.string.extension_desc_privacy_badger, R.color.ext_avatar_privacy_badger, null,
        ),
        Entry(
            "clearurls", 839767, "ClearURLs",
            R.string.extension_desc_clearurls, R.color.ext_avatar_clearurls, null,
        ),
        Entry(
            "darkreader", 855413, "Dark Reader",
            R.string.extension_desc_darkreader, R.color.ext_avatar_darkreader, R.drawable.ic_ext_darkreader,
        ),
        Entry(
            "tampermonkey", 683490, "Tampermonkey",
            R.string.extension_desc_tampermonkey, R.color.ext_avatar_tampermonkey, R.drawable.ic_ext_tampermonkey,
        ),
        Entry(
            "bitwarden-password-manager", 735894, "Bitwarden",
            R.string.extension_desc_bitwarden, R.color.ext_avatar_bitwarden, R.drawable.ic_ext_bitwarden,
        ),
        Entry(
            "simple-translate", 857110, "Simple Translate",
            R.string.extension_desc_simple_translate, R.color.ext_avatar_simple_translate, R.drawable.ic_ext_simple_translate,
        ),
        Entry(
            "styl-us", 814814, "Stylus",
            R.string.extension_desc_stylus, R.color.ext_avatar_stylus, R.drawable.ic_ext_styl_us,
        ),
    )
}