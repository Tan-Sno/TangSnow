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
}
