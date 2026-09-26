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

    /**
     * 标签内的 href 属性（单/双引号或无引号）；lookbehind 防止 `data-href=` 里的 href= 被抢匹配。
     *
     * 作用域是**单个开始标签字符串**（见 [parseImport]），而标签之间互不重叠
     * ⇒ 全文总扫描量与原文等长，不会退化。
     */
    private val HREF_ATTR = Regex("""(?<![\w-])href\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)

    /** 收尾标签前缀。刻意不用正则：查找游标单调推进即可线性，见 [parseImport] 的复杂度说明 */
    private const val CLOSE_PREFIX = "</a"

    /** 导入清洗时单条标题的上限（超出截断，不拒收） */
    internal const val MAX_TITLE_LENGTH = 200

    /** 单次导入的条目上限：防御超大/恶意文件，>1000 条的书签属于异常体量 */
    internal const val MAX_IMPORT_COUNT = 1000

    /** 导入文件体积上限（字符数）：5MB 的纯文本书签文件已远超任何正常导出 */
    internal const val MAX_IMPORT_CHARS = 5_000_000

    /**
     * 从书签 HTML 文本中解析全部条目（不做清洗，见 [sanitize]）。
     * 解析失败的条目（无 href / href 为空）直接跳过，不让单个坏行中断整体。
     *
     * ## 为什么是手写扫描，而不是正则
     *
     * 单个条目形如 `<A HREF="…">标题</A>`。用一条大正则扫全文时，
     * `<a\s+[^>]*?href…` 形态在**没有 `>` 收尾**的对抗性输入（如整篇 `<a ` 重复，
     * 5MB 上限内合法）上，每个起点都会把量词扫到文件尾再回溯 —— **O(n²)**，
     * 导入线程被拖住且**无法取消**（解析跑在导入协程的 `Dispatchers.IO` 上）。
     *
     * 换成「先找开始标签、再找收尾标签」的两段式**仍然不够**，实测有两处独立成因：
     *  1. `ANCHOR_TAG = <a\s[^>]*>` 的 `[^>]*` 是贪婪的，没有 `>` 时同样会扫到
     *     文末再回溯（整篇 `<a ` 输入即命中此路）；
     *  2. 每轮都从当前起点重新查 `</a>` 时，在「有 `>` 却始终没有 `</a>`」的输入
     *     （如整篇 `<a >`）上，n 轮各扫一次文末 —— 又是一次 O(n²)。
     * 二者都由 [BookmarkHtmlTest] 的复杂度哨兵实测钉住（修复前分别约 7.1 s 与
     * 按 5MB 外推小时级）。
     *
     * 因此这里改为**单次线性扫描**，三条硬约束共同保证 O(n)：
     *  1. 所有查找游标（`'<'` / `'>'` / `"</a"`）**只增不减**，扫描区间互不重叠；
     *  2. 「此后已无 `</a>`」这一结论**一旦得出即缓存**（`closeExhausted` 标志），
     *     不再重复扫尾 —— 这是消除第二处 O(n²) 的关键，也是与「两段式」的本质差别；
     *  3. 属性提取的正则只作用于**单个开始标签字符串**，而标签之间不重叠。
     *
     * 语义与改造前逐条对齐（由 [BookmarkHtmlTest] 的差分用例钉住）：标签内提 href
     * （单/双/无引号，`data-href` 不抢匹配）；标题取开始标签与收尾标签之间的文本
     * （允许跨行）；`</a>` 缺失时按空标题收该条目；无 href / href 为空则跳过该条；
     * 一个 `</a>` 只被消费一次。
     */
    fun parseImport(text: String): List<Entry> {
        if (text.length > MAX_IMPORT_CHARS) return emptyList()
        val out = ArrayList<Entry>()
        val n = text.length
        var i = 0
        // 收尾标签的查找游标，与「已确认此后无收尾标签」标志：二者共同消除重复扫尾
        var closeFrom = 0
        var closeExhausted = false
        while (true) {
            val lt = text.indexOf('<', i)
            if (lt < 0) break
            if (!isAnchorStart(text, lt)) {
                i = lt + 1
                continue
            }
            val gt = text.indexOf('>', lt)
            // 此后连 '>' 都没有了，不可能再有完整的开始标签 —— 整篇可以收工
            if (gt < 0) break
            val resumeAt = gt + 1
            var closeStart = -1
            // 默认从开始标签之后继续：与「收尾标签缺失」时的既有语义一致
            var next = resumeAt
            if (!closeExhausted) {
                var searchFrom = if (closeFrom > resumeAt) closeFrom else resumeAt
                while (true) {
                    val c = text.indexOf(CLOSE_PREFIX, searchFrom, ignoreCase = true)
                    if (c < 0) {
                        // 从该位置起已无收尾标签 ⇒ 更靠后的位置同样没有，
                        // 后续条目一概按空标题收，且不必再扫尾
                        closeExhausted = true
                        break
                    }
                    var j = c + CLOSE_PREFIX.length
                    while (j < n && isTagSpace(text[j])) j++
                    if (j < n && text[j] == '>') {
                        closeStart = c
                        closeFrom = j + 1
                        next = closeFrom
                        break
                    }
                    // `</a` 之后不是 '>'（如 `</abbr>`）：从下一位继续找
                    searchFrom = c + 1
                }
            }
            val title = if (closeStart >= 0) text.substring(resumeAt, closeStart) else ""
            i = next
            val href = HREF_ATTR.find(text.substring(lt, gt + 1))?.let { m ->
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
     * `lt` 处是不是开始标签的开头（`<a` 紧跟一个空白字符，大小写不敏感）。
     *
     * 刻意不用 `Char.isWhitespace()`：它包含 Unicode 空白，会比既有
     * `Regex("<a\\s…")` 的口径更宽，凭空放宽语义（见 [isTagSpace]）。
     */
    private fun isAnchorStart(text: String, lt: Int): Boolean =
        lt + 2 < text.length &&
            (text[lt + 1] == 'a' || text[lt + 1] == 'A') &&
            isTagSpace(text[lt + 2])

    /**
     * 与正则 `\s` 的默认（非 `UNICODE_CHARACTER_CLASS`）ASCII 空白集合一致：
     * 空格、制表（0x09）、换行（0x0A）、垂直制表（0x0B）、换页（0x0C）、回车（0x0D）。
     *
     * 注意 Kotlin **没有** `\f` 转义（Java 有），换页只能写 `\u000C`。
     */
    private fun isTagSpace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'

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
