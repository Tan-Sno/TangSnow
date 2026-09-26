package io.github.tan_sno.tangsnow.data

/**
 * Netscape 书签 HTML 的解析与导入清洗（纯字符串逻辑，可被 JVM 单测覆盖）。
 *
 * Netscape 格式是各浏览器书签导入/导出的事实标准（Chrome / Firefox / Edge 的
 * 「导出书签」都产出它），一行一个条目，形如：
 * ```
 * <DT><A HREF="https://example.com/" ADD_DATE="1700000000">示例站点</A>
 * ```
 * 属性顺序不保证、大小写不保证、标题里可有 HTML 实体，故解析按宽容口径处理；
 * 导入侧的清洗（scheme 白名单、去重、上限）单独成函数，让边界可测。
 */
object BookmarkHtml {

    /** 待插入的书签条目 */
    data class Entry(val url: String, val title: String)

    /** 开始标签（`[^>]*` 无嵌套量词 —— 全程线性，见 [parseImport] 的复杂度说明） */
    private val ANCHOR_TAG = Regex("""<a\s[^>]*>""", RegexOption.IGNORE_CASE)

    /** 条目的收尾标签（标题即开始标签与它之间的文本） */
    private val CLOSE_TAG = Regex("""</a\s*>""", RegexOption.IGNORE_CASE)

    /** 标签内的 href 属性（单/双引号或无引号） */
    private val HREF_ATTR = Regex("""href\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)

    /** 导入清洗时单条标题的上限（超出截断，不拒收） */
    internal const val MAX_TITLE_LENGTH = 200

    /** 单次导入的条目上限：防御超大/恶意文件，>1000 条的书签属于异常体量 */
    internal const val MAX_IMPORT_COUNT = 1000

    /** 导入文件体积上限（字符数）：5MB 的纯文本书签文件已远超任何正常导出 */
    internal const val MAX_IMPORT_CHARS = 5_000_000

    /**
     * 从书签 HTML 文本中解析全部条目（不做清洗，见 [sanitize]）。
     * 解析失败的条目（无 href / 空标题且空 URL）直接跳过，不让单个坏行中断整体。
     */
    /**
     * 从书签 HTML 文本中解析全部条目（不做清洗，见 [sanitize]）。
     * 解析失败的条目（无 href / 空 URL）直接跳过，不让单个坏行中断整体。
     *
     * 为什么是「找开始标签 → 段内二次提取」的两段式，而不是一条大正则扫全文：
     * `<a\s+[^>]*?href...` 形态在**没有 `>` 收尾**的对抗性输入（如整篇 `<a ` 重复，
     * 5MB 上限内合法）上，每个起点都会把惰性量词扫到文件尾 —— O(n²)，分钟级卡住
     * 导入线程且无法取消。两段式的每一环都无嵌套量词，全文 O(n)。
     * 标题允许跨行（比逐行正则更宽容）；`</a>` 缺失时按空标题收该条目。
     */
    fun parseImport(text: String): List<Entry> {
        if (text.length > MAX_IMPORT_CHARS) return emptyList()
        val out = ArrayList<Entry>()
        var from = 0
        while (true) {
            val tag = ANCHOR_TAG.find(text, from) ?: break
            val bodyStart = tag.range.last + 1
            val close = CLOSE_TAG.find(text, bodyStart)
            val title = if (close != null) text.substring(bodyStart, close.range.first) else ""
            from = if (close != null) close.range.last + 1 else bodyStart
            val href = HREF_ATTR.find(tag.value)?.let { m ->
                (m.groupValues[2].ifEmpty { m.groupValues[3] }).ifEmpty { m.groupValues[4] }
            } ?: continue
            if (href.isBlank()) continue
            // href 同样要解码：主流浏览器导出时会把 URL 里的 & 写成 &amp;，
            // 不解码则导入的带查询参数链接全部失效
            out += Entry(decodeEntities(href).trim(), decodeEntities(title).trim())
        }
        return out
    }

    /**
     * 导入清洗：scheme 必须 http/https（about:/javascript:/file: 等一律拒收），
     * 与 [existingUrls] 及批内自身去重，标题截断到 [MAX_TITLE_LENGTH]，
     * 总量截断到 [MAX_IMPORT_COUNT]。
     * @return 待插入条目与被跳过的条数（无效 / 重复 / 超上限），供界面如实提示
     */
    fun sanitize(
        raw: List<Entry>,
        existingUrls: Set<String>,
    ): Pair<List<Entry>, Int> {
        val out = ArrayList<Entry>(raw.size.coerceAtMost(MAX_IMPORT_COUNT))
        val seen = HashSet<String>(existingUrls)
        var skipped = 0
        for (e in raw) {
            val url = e.url.trim()
            // 超上限 / 非 http(s) / 重复（含批内重复）一律计入「跳过」，界面如实提示
            if (out.size >= MAX_IMPORT_COUNT ||
                !isHttpUrl(url) ||
                !seen.add(url)
            ) {
                skipped++
                continue
            }
            out += Entry(url, e.title.take(MAX_TITLE_LENGTH))
        }
        return out to skipped
    }

    /** 仅放行 http/https —— about:/javascript:/file:/intent: 等一律不得经导入进入书签 */
    internal fun isHttpUrl(url: String): Boolean {
        val colon = url.indexOf(':')
        if (colon <= 0) return false
        val scheme = url.substring(0, colon).lowercase()
        return scheme == "http" || scheme == "https"
    }

    /** 基本实体解码（书签导出文件里最常见的几个）；未知实体原样保留 */
    internal fun decodeEntities(s: String): String = s
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")

    /**
     * 导出：把全部书签序列化为 Netscape 书签 HTML。
     * url 与 title 做最小 HTML 转义（& < > "），与主流浏览器导出格式兼容。
     */
    fun export(bookmarks: List<Bookmark>): String = buildString {
        append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n")
        append("<!-- This is an automatically generated file. It will be read and overwritten. DO NOT EDIT! -->\n")
        append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n")
        append("<TITLE>Bookmarks</TITLE>\n")
        append("<H1>Bookmarks</H1>\n")
        append("<DL><p>\n")
        for (b in bookmarks) {
            append("<DT><A HREF=\"")
            append(escape(b.url))
            append("\" ADD_DATE=\"")
            append(b.createdAt / 1000)
            append("\">")
            append(escape(b.title.ifBlank { b.url }))
            append("</A>\n")
        }
        append("</DL><p>\n")
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
