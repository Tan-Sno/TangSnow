package io.github.tan_sno.tangsnow.util

import io.github.tan_sno.tangsnow.data.SearchEngine

/**
 * 按 Android `Uri.encode(String)` 的语义做百分号编码（RFC 3986 unreserved 集之外的字节按 UTF-8 转 %XX）。
 *
 * 为什么不直接用 `Uri.encode`：`android.net.Uri` 是框架类，**JVM 单元测试里不可用**
 * （android.jar 的方法体是抛异常的桩），而搜索模板替换是纯逻辑、正是最该被单测覆盖的地方。
 * 本实现与 `Uri.encode` 保持逐字符一致（保留 `A-Za-z0-9` 与 `_-!.~'()*`，其余按 UTF-8 编码），
 * 因此替换后生成的搜索 URL 与改造前**逐字节相同**，不会改变任何线上行为。
 */
internal fun percentEncode(value: String): String {
    val sb = StringBuilder(value.length * 3)
    for (b in value.toByteArray(Charsets.UTF_8)) {
        val c = b.toInt() and 0xFF
        val ch = c.toChar()
        if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
            ch == '_' || ch == '-' || ch == '!' || ch == '.' ||
            ch == '~' || ch == '\'' || ch == '(' || ch == ')' || ch == '*'
        ) {
            sb.append(ch)
        } else {
            sb.append('%')
            sb.append(HEX[c shr 4])
            sb.append(HEX[c and 0x0F])
        }
    }
    return sb.toString()
}

private val HEX = "0123456789ABCDEF".toCharArray()

/** 把用户在地址栏输入的字符串解析为可加载的 URL。 */
object UrlUtils {

    /**
     * 匹配域名（含可选路径 / 查询串）。
     * 要求：
     *  - 至少含一个点（视为有 TLD）
     *  - 标签由字母数字与短横线组成，首尾不允许是短横线
     *  - 不含空白
     */
    private val HOST_PATTERN = Regex(
        """^(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+(?::\d{1,5})?(?:/\S*)?$"""
    )

    private val SCHEME_PATTERN = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*://\S+$""")

    /**
     * 本机地址：localhost 及其子域，可带端口与路径。
     * 主流浏览器都不会把 `localhost` / `localhost:8080` 当搜索词——那是开发调试的
     * 日常入口，丢给搜索引擎等于功能缺失。此处按 http 处理（本机服务默认不启用 TLS）。
     */
    private val LOCALHOST_PATTERN = Regex(
        """^(?:[A-Za-z0-9-]+\.)*localhost(?::\d{1,5})?(?:/\S*)?$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * 单标签主机 + 端口（如 `nas:5000`）。
     * 这类内网设备名不含点、会被 [HOST_PATTERN] 判为非域名，但它有明确端口，
     * 是"地址"而非"搜索词"；按 http 处理更符合内网设备的实际情况。
     */
    private val LAN_HOST_PORT_PATTERN = Regex(
        """^[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?:\d{1,5}(?:/\S*)?$"""
    )

    /**
     * 把可能的 Unicode 域名（如中文域名「例子.测试」）转成 ASCII（punycode）形式，
     * 便于 [HOST_PATTERN] 识别；保留端口与路径。无法识别为域名时返回 null。
     * 仅处理裸域名 + 可选端口 + 可选路径，不含协议前缀（协议已在上层单独判断）。
     */
    private fun toAsciiUrl(input: String): String? {
        if (input.any { it.isWhitespace() }) return null
        // 不含点就不是域名形态（「例子」这类单词仍应走搜索）
        val host = input.substringBefore('/')
        if (!host.contains('.')) return null
        val rest = input.removePrefix(host)
        val hostOnly = host.substringBefore(':')
        val port = host.removePrefix(hostOnly)
        val ascii = runCatching {
            java.net.IDN.toASCII(hostOnly, java.net.IDN.ALLOW_UNASSIGNED)
        }.getOrNull() ?: return null
        // 转出的结果必须仍是合法域名形态（含点、无空白/控制符），避免把搜索词硬转
        if (!ascii.contains('.') || ascii.any { it.isWhitespace() || it.code < 0x20 }) return null
        return ascii + port + rest
    }

    /** 任意空白字符（含全角空格？不 —— 与旧行为严格一致，仅 \s）。提到文件级避免每次调用重新编译。 */
    private val WHITESPACE = Regex("""\s""")

    /** 输入本身是否像一个网址（带协议，或裸域名形态，或本机/内网地址） */
    fun looksLikeUri(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.contains(WHITESPACE)) return false
        return trimmed.startsWith("http://", true) ||
            trimmed.startsWith("https://", true) ||
            SCHEME_PATTERN.matches(trimmed) ||
            HOST_PATTERN.matches(trimmed) ||
            LOCALHOST_PATTERN.matches(trimmed) ||
            LAN_HOST_PORT_PATTERN.matches(trimmed) ||
            toAsciiUrl(trimmed) != null
    }

    /**
     * 目标是否落在**局域网**上（RFC 1918 私有段 / 链路本地 / IPv6 ULA / `.local`）。
     *
     * 用途：Android 17（API 37）起，`targetSdk ≥ 37` 的应用访问局域网必须持有
     * `ACCESS_LOCAL_NETWORK` 运行时权限，否则连接会被内核直接拦掉（TCP 超时 /
     * UDP 报 EPERM），见 Android 官方《Local network permission》。浏览器必须能打开
     * 路由器 / NAS / 打印机的管理页，因此要在真正导航前据此申请权限。
     *
     * 判定口径：
     *  - 回环（`localhost`、`127.0.0.0/8`）**不算** —— 它不经过本地网络，不受该限制；
     *  - IPv4 只认 RFC 1918 三段与 169.254/16 链路本地；
     *  - IPv6 只认 fe80::/10 与 fc00::/7；
     *  - 主机名只认 `.local` 后缀（mDNS / Bonjour）。其余单标签名（`nas`、`router`）
     *    无法与公网短名可靠区分，**宁可漏判**（用户仍可在系统设置里手动授权），
     *    也不误判给每个域名都弹权限。
     *
     * 刻意不依赖 `android.net.Uri`：一是普通 JVM 单测里它是抛 `Stub!` 的桩，
     * 二是 `Uri.parse` 对无 scheme 的裸 host 会把整串当成 path。这里自己拆。
     */
    internal fun isLocalNetworkAddress(input: String): Boolean {
        val host = hostOf(input)?.trim()?.trimEnd('.')?.lowercase().orEmpty()
        if (host.isEmpty()) return false
        if (host == "localhost" || host.endsWith(".localhost")) return false
        if (host.endsWith(".local")) return true
        return if (host.contains(':')) isLocalIpv6(host) else isLocalIpv4(host)
    }

    /** 从「URL」或「裸 host[:port]」里取主机名；取不到返回 null。IPv6 字面量的方括号会被剥掉 */
    private fun hostOf(input: String): String? {
        val s = input.trim()
        if (s.isEmpty()) return null
        val schemeEnd = s.indexOf("://")
        val afterScheme = if (schemeEnd >= 0) s.substring(schemeEnd + 3) else s
        val authority = afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }
        val hostPort = authority.substringAfterLast('@')
        if (hostPort.isEmpty()) return null
        if (hostPort.startsWith("[")) {
            val end = hostPort.indexOf(']')
            return if (end > 1) hostPort.substring(1, end) else null
        }
        return hostPort.substringBefore(':').ifEmpty { null }
    }

    /** 仅 RFC 1918 三段 + 169.254/16；回环 `127.0.0.0/8` 明确排除 */
    private fun isLocalIpv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val o = IntArray(4)
        for (i in 0..3) {
            val v = parts[i].toIntOrNull() ?: return false
            if (v !in 0..255) return false
            o[i] = v
        }
        return when {
            o[0] == 10 -> true                        // 10.0.0.0/8
            o[0] == 172 && o[1] in 16..31 -> true     // 172.16.0.0/12
            o[0] == 192 && o[1] == 168 -> true        // 192.168.0.0/16
            o[0] == 169 && o[1] == 254 -> true        // 169.254.0.0/16 链路本地
            else -> false
        }
    }

    /** fe80::/10（链路本地）与 fc00::/7（ULA）；按首段十六进制前缀判断即可覆盖实际取值 */
    private fun isLocalIpv6(host: String): Boolean {
        val h = host.lowercase()
        return h.startsWith("fe8") || h.startsWith("fe9") ||
            h.startsWith("fea") || h.startsWith("feb") ||   // fe80::/10
            h.startsWith("fc") || h.startsWith("fd")        // fc00::/7
    }

    /** 地址栏输入解析结果：url = 可加载地址；isSearch = 是否走了搜索引擎 */
    data class Resolved(val url: String, val isSearch: Boolean)

    fun resolveInfo(input: String, engine: SearchEngine): Resolved? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        return when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> Resolved(trimmed, false)
            HOST_PATTERN.matches(trimmed) -> Resolved("https://$trimmed", false)
            // 本机 / 内网地址：当地址导航而非搜索（localhost、localhost:端口、设备名:端口）
            LOCALHOST_PATTERN.matches(trimmed) -> Resolved("http://$trimmed", false)
            LAN_HOST_PORT_PATTERN.matches(trimmed) -> Resolved("http://$trimmed", false)
            else -> {
                // 中文域名 / IDN：先转 punycode 再判断，避免被误当搜索词
                val ascii = toAsciiUrl(trimmed)
                if (ascii != null) Resolved("https://$ascii", false)
                else Resolved(engine.searchUrl(trimmed), true)
            }
        }
    }

    /**
     * @return 可直接交给 GeckoView.loadUri 的 URL；输入为空时返回 null
     */
    fun resolve(input: String, engine: SearchEngine): String? =
        resolveInfo(input, engine)?.url

    /**
     * 从 ACTION_SEND 分享来的任意文本里提取第一个 http(s) 链接。
     *
     * 为什么不直接整段交给浏览器：分享文本常见的形态是「标题 + 链接」或「链接 + 广告尾巴」，
     * 整段塞给地址栏会被 [resolveInfo] 判成搜索词。这里只取**第一个**链接，并做两层收口：
     *  1. **CJK 标点硬截断**：分享文案常把说明文字直接黏在链接后（无空格，`\S+` 会一并
     *     捕获成 `https://x.com/1。转疯了`），而 CJK 标点在真实 URL 里几乎不存在，
     *     故从第一个 CJK 标点处截断；
     *  2. **尾随标点剥离**：链接写在句尾时的句号、逗号、右括号、引号不属于 URL，
     *     连续剥到不再命中（Wikipedia 式 `/页_(消歧义)` 的**成对**右括号也会被剥，
     *     这是刻意的取舍 —— 分享场景里「链接括号结尾后接正文」远比「URL 以右括号
     *     结尾」常见，宁可少半个字符也不给地址栏塞标点）。
     * 末尾再清一次「被截断的百分号编码残渣」（合法编码恒为 `%` + 两位十六进制）。
     * 提取不出链接（纯文本分享）返回 null，由调用方决定提示方式。
     * 纯字符串运算（不碰 android.net.Uri），可直接被 JVM 单测覆盖。
     */
    private val URL_IN_TEXT = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

    /** CJK 标点：从这里起硬截断（真实 URL 几乎不含，是说明文字黏连的切点） */
    private val CJK_URL_CUTTER = Regex("""[。，、；：！？「」『』《》〈〉【】（）…—]""")

    /** 链接尾随的标点集合：英文标点 + 全角/CJK 闭合符号（连续剥到不再命中） */
    private val TRAILING_URL_PUNCTUATION = """.,;:!?'""" + "()[]{}<>\"“”‘’）］｝]》」』›»。"

    fun extractUrlFromText(text: String): String? {
        val raw = URL_IN_TEXT.find(text)?.value ?: return null
        val cut = CJK_URL_CUTTER.find(raw)?.range?.first ?: raw.length
        var url = raw.substring(0, cut)
        // 剥离连续的尾随标点（如句尾的「.」、右括号、引号）
        while (url.isNotEmpty() && url.last() in TRAILING_URL_PUNCTUATION) {
            url = url.dropLast(1)
        }
        // 截断的百分号编码残渣：合法编码恒为「% + 两位十六进制」，结尾若是 '%'
        // 或 '%+一位十六进制'（被标点/换行截断所致）则剥回完整边界；
        // 完整的 %XY 保留 —— 那是 URL 的正常组成部分（如 %20）
        while (true) {
            val n = url.length
            when {
                url.endsWith("%") -> url = url.dropLast(1)
                n >= 2 && url[n - 2] == '%' && url.last().isHexDigit() -> url = url.dropLast(2)
                else -> break
            }
        }
        return url.ifEmpty { null }
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
