package io.github.tan_sno.tangsnow.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BookmarkHtml]：Netscape 书签 HTML 的解析与导入清洗。
 * 全部为纯字符串/数据逻辑，可在普通 JVM 单测中运行。
 */
class BookmarkHtmlTest {

    // ------------------------------------------------------------- parseImport

    @Test
    fun `解析标准 Netscape 导出格式`() {
        val html = """
            <!DOCTYPE NETSCAPE-Bookmark-file-1>
            <DL><p>
            <DT><A HREF="https://example.com/" ADD_DATE="1700000000">示例站点</A>
            <DT><A HREF="https://x.cn/a" ADD_DATE="1700000001">另一个</A>
            </DL><p>
        """.trimIndent()
        val entries = BookmarkHtml.parseImport(html)
        assertEquals(2, entries.size)
        assertEquals("https://example.com/", entries[0].url)
        assertEquals("示例站点", entries[0].title)
        assertEquals("https://x.cn/a", entries[1].url)
    }

    @Test
    fun `属性顺序与引号风格不影响解析`() {
        val html = """<a add_date="1" target="_blank" href='https://single.cn/x'>单引号</a>""" +
            "<a TITLE=\"标题在前\" HREF=https://bare.cn/y>裸引号</a>"
        val entries = BookmarkHtml.parseImport(html)
        assertEquals(2, entries.size)
        assertEquals("https://single.cn/x", entries[0].url)
        assertEquals("https://bare.cn/y", entries[1].url)
        // 书签标题 = 标签内的可见文本（TITLE 等属性不是标题）
        assertEquals("裸引号", entries[1].title)
    }

    @Test
    fun `标题中的基本实体被解码`() {
        val html = """<DT><A HREF="https://e.cn/">A&amp;B&lt;C&gt;"q"&#39;s&#39;</A>"""
        val entries = BookmarkHtml.parseImport(html)
        assertEquals("""A&B<C>"q"'s'""", entries[0].title)
    }

    @Test
    fun `实体只解一趟——已转义的实体样文本往返不被二次解码`() {
        // 为什么必须有这条独立断言：差分基准（parseBeforeLinearization）调的是**当前**的
        // decodeEntities，链式实现与单趟实现的差异会被同一个函数自我抵消 —— 全仓的差分用例
        // 在旧的链式实现下同样全绿。只有直接对语义下断言才钉得住：`&amp;lt;` 只能解成字面量
        // `&lt;`；若按链式先解 `&amp;` 再解 `&lt;`，它会一路变成 `<`，于是「Use &lt;div&gt;」
        // 这类**标题里本就写着实体样文字**的站点，导出→导入一次就被永久改写。
        assertEquals("&lt;div&gt;", BookmarkHtml.decodeEntities("&amp;lt;div&amp;gt;"))

        // 与 export 的转义（先 & 再 < > "）互为逆运算：往返之后标题逐字不变
        val original = "Use &lt;div&gt; & \"q\""
        val html = BookmarkHtml.export(
            listOf(Bookmark(1, "https://e.cn/", original, 0L))
        )
        val parsed = BookmarkHtml.parseImport(html)
        assertEquals(1, parsed.size)
        assertEquals(original, parsed[0].title)
    }

    @Test
    fun `无 href 的行被跳过`() {
        val html = "<DT><A>没有链接</A>\n<DT><A HREF=\"https://ok.cn/\">好的</A>"
        val entries = BookmarkHtml.parseImport(html)
        assertEquals(1, entries.size)
        assertEquals("https://ok.cn/", entries[0].url)
    }

    @Test
    fun `空输入与超限输入返回空`() {
        assertEquals(0, BookmarkHtml.parseImport("").size)
        // 超过体积上限视为无效文件，整体拒绝
        val big = "x".repeat(BookmarkHtml.MAX_IMPORT_CHARS + 1)
        assertEquals(0, BookmarkHtml.parseImport(big).size)
    }

    @Test
    fun `超过解析上限的条目被计数而不是静默丢弃`() {
        // 上限本身是防御性的内存有界化，保留；但截断必须**如实回报** —— 否则一个 2.5 万条的
        // 导出文件在界面上会显示成「已导入 1000 / 跳过 19000」，与实际内容不符
        // （余下 5000 条连数都没数，用户会以为文件里就只有那么些条目）。
        val over = 7
        val html = "<a href=\"https://p.cn/\">t</a>"
            .repeat(BookmarkHtml.RAW_PARSE_ENTRY_CAP + over)

        val (entries, dropped) = BookmarkHtml.parseImportCounted(html)
        assertEquals(BookmarkHtml.RAW_PARSE_ENTRY_CAP, entries.size)
        assertEquals(over, dropped)

        // 没触到上限时不得虚报
        val (all, none) = BookmarkHtml.parseImportCounted("<a href=\"https://p.cn/\">t</a>".repeat(3))
        assertEquals(3, all.size)
        assertEquals(0, none)

        // 解析入口（不计数的那个）行为不变：仍然只收前 RAW_PARSE_ENTRY_CAP 条
        assertEquals(BookmarkHtml.RAW_PARSE_ENTRY_CAP, BookmarkHtml.parseImport(html).size)
    }

    @Test(timeout = 15_000)
    fun `对抗性输入线性完成——复杂度回归哨兵`() {
        // 四种形态都曾在旧实现上退化，规模取到「旧实现需数十秒~小时级」的量级：
        // 任何一处退化成「每轮重新扫尾」，本用例都会在 15 秒内超时失败。
        // （旧版哨兵用 `"<a ".repeat(50_000)` 且不设超时，实测本身就要 7.1 秒 ⇒ 拦不住回归。）
        //   ① 整篇 `<a `（有空白、无 '>'）    ⇒ 旧 ANCHOR_TAG 的 [^>]* 回溯到文末
        //   ② 整篇 `<a`（连空白都没有）        ⇒ 无匹配，最便宜的一条，作对照
        //   ③ 整篇 `<a >`（有 '>'、无 '</a>'）⇒ 旧实现每轮重查 </a> 各扫一次文末
        //   ④ 收尾前缀反复出现却都不成立       ⇒ 收尾查找的推进与终止是否正确
        val cases = listOf(
            "<a ".repeat(200_000),
            "<a".repeat(300_000),
            "<a >".repeat(150_000),
            "<a >".repeat(30_000) + "</abbr>".repeat(60_000),
        )
        for (t in cases) {
            assertEquals("形态长度 ${t.length}", 0, BookmarkHtml.parseImport(t).size)
        }
    }

    @Test
    fun `收尾标签前缀相似但不成立的输入按原语义处理`() {
        // `</abbr>` 里有 `</a` 前缀但不是收尾标签：标题应继续延伸到真正的 `</a>`
        val e = BookmarkHtml.parseImport("<a href=\"https://a.cn/\">标题</abbr>继续</a>")
        assertEquals(1, e.size)
        assertEquals("https://a.cn/", e[0].url)
        assertEquals("标题</abbr>继续", e[0].title)
    }

    // ------------------------------------------------- 与改造前的差分（行为等价性）

    @Test
    fun `与改造前的两段式实现逐条等价——边界语料`() {
        val corpus = listOf(
            "", "<a ", "<a", "<a>", "</a>", "<a >",
            "<a href=\"u\">t</a>",
            "<A HREF=\"https://e.cn/\">标题</A>",
            "<a href=\"u\">t</a><a href=\"v\">w</a>",
            // 缺收尾标签：按空标题收（href 仍保留）
            "<a href=\"u\">没有收尾",
            "<a href=\"u\">一<a href=\"v\">二</a>",
            // 一个 </a> 只被消费一次
            "<a href=\"u\">x<a href=\"v\">y</a><a href=\"w\">z</a>",
            // data-href / hreflang 不抢匹配
            "<a data-href=\"evil\" href=\"good\">t</a>",
            "<a data-href=\"evil\">t</a>",
            "<a hreflang=\"zh\" href=\"good\">t</a>",
            // 引号风格与空格
            "<a href='s'>t</a>",
            "<a href=bare>t</a>",
            "<a HREF = \"spaced\" >t</a>",
            // 无 href / 空 href
            "<a >无链接</a>",
            "<a href=\"\">空</a>",
            "<a href=\"   \">空白</a>",
            // 跨行标题与实体
            "<a href=\"u\">第一行\n第二行</a>",
            "<a href=\"u?a=1&amp;b=2\">A&amp;B</a>",
            // 全角空格不是 ASCII 空白（旧口径同样不认）
            "<a\u3000href=\"u\">t</a>",
            "<a href=\"u\">t</a\u3000>",
            // 标签内含 '<'，以及标签内嵌一个 `<a ` 形态
            "<a title=\"a<b\" href=\"u\">t</a>",
            "<a title=\"<a  href=inner\" href=\"u\">t</a>",
        )
        for (html in corpus) {
            assertEquals(
                "与改造前不等价：$html",
                parseBeforeLinearization(html),
                BookmarkHtml.parseImport(html).map { it.url to it.title },
            )
        }
    }

    @Test
    fun `与改造前的两段式实现逐条等价——随机语料`() {
        // 固定种子：失败可复现。token 集合刻意覆盖「标签/收尾/属性/空白/引号」的交叉，
        // 也包括 `</abbr>`、`\u3000`、内嵌 `<a` 这些只在组合下才暴露的边角。
        val tokens = listOf(
            "<a ", "<a>", "<a", "</a>", "</A>", "</a >", "</abbr>", ">", "<",
            "href=", " href=", "href = ", "HREF=", " data-href=", " hreflang=",
            "\"", "'", "https://e.cn/", " ", "\n", "\t", "标题", "&amp;", "&#39;",
            "<DT>", "x", "=", "a", "\u3000",
        )
        val rnd = java.util.Random(20260926L)
        repeat(500) { case ->
            val sb = StringBuilder()
            repeat(rnd.nextInt(45)) { sb.append(tokens[rnd.nextInt(tokens.size)]) }
            val html = sb.toString()
            assertEquals(
                "随机语料第 $case 例与改造前不等价：$html",
                parseBeforeLinearization(html),
                BookmarkHtml.parseImport(html).map { it.url to it.title },
            )
        }
    }

    // ------------------------------------------------------------- sanitize

    @Test
    fun `非 http_s scheme 一律拒收`() {
        val raw = listOf(
            BookmarkHtml.Entry("javascript:alert(1)", "脚本"),
            BookmarkHtml.Entry("file:///sdcard/x", "本地文件"),
            BookmarkHtml.Entry("about:blank", "内部页"),
            BookmarkHtml.Entry("https://ok.cn/", "好的"),
        )
        val (entries, skipped) = BookmarkHtml.sanitize(raw, emptySet())
        assertEquals(1, entries.size)
        assertEquals(3, skipped)
        assertEquals("https://ok.cn/", entries[0].url)
    }

    @Test
    fun `与已有书签及批内自身去重`() {
        val raw = listOf(
            BookmarkHtml.Entry("https://a.cn/", "已有"),
            BookmarkHtml.Entry("https://a.cn/", "批内重复"),
            BookmarkHtml.Entry("https://b.cn/", "新的"),
        )
        val (entries, skipped) = BookmarkHtml.sanitize(raw, setOf("https://a.cn/"))
        assertEquals(1, entries.size)
        assertEquals(2, skipped)
        assertEquals("https://b.cn/", entries[0].url)
    }

    @Test
    fun `导入数量受上限约束`() {
        val raw = (0 until BookmarkHtml.MAX_IMPORT_COUNT + 50).map {
            BookmarkHtml.Entry("https://p$it.cn/", "t$it")
        }
        val (entries, skipped) = BookmarkHtml.sanitize(raw, emptySet())
        assertEquals(BookmarkHtml.MAX_IMPORT_COUNT, entries.size)
        assertEquals(50, skipped)
    }

    @Test
    fun `超长标题截断而非拒收`() {
        val longTitle = "标".repeat(BookmarkHtml.MAX_TITLE_LENGTH + 10)
        val (entries, _) = BookmarkHtml.sanitize(
            listOf(BookmarkHtml.Entry("https://t.cn/", longTitle)), emptySet()
        )
        assertEquals(BookmarkHtml.MAX_TITLE_LENGTH, entries[0].title.length)
    }

    // ------------------------------------------------------------- export

    @Test
    fun `导出可被自身解析往返且转义正确`() {
        val list = listOf(
            Bookmark(1, "https://e.cn/a&b", "标题<一>", 1700000000000L),
            Bookmark(2, "https://e.cn/\"q\"", "", 1700000001000L),
        )
        val html = BookmarkHtml.export(list)
        // 空标题回退为 URL（与解析端 ifBlank 口径一致）
        assertTrue(html.contains("""<A HREF="https://e.cn/a&amp;b" ADD_DATE="1700000000">标题&lt;一&gt;</A>"""))
        val parsed = BookmarkHtml.parseImport(html)
        assertEquals(2, parsed.size)
        assertEquals("https://e.cn/a&b", parsed[0].url)
        assertEquals("标题<一>", parsed[0].title)
        assertEquals("https://e.cn/\"q\"", parsed[1].url)
        assertEquals("https://e.cn/\"q\"", parsed[1].title)
    }

    // ------------------------------------------------------------- isHttpUrl

    @Test
    fun `scheme 判定大小写不敏感且拒绝无 scheme`() {
        assertTrue(BookmarkHtml.isHttpUrl("HTTPS://A.cn/"))
        assertTrue(BookmarkHtml.isHttpUrl("http://a.cn/"))
        assertFalse(BookmarkHtml.isHttpUrl("a.cn/"))
        assertFalse(BookmarkHtml.isHttpUrl("moz-extension://abc/x"))
    }

    // ------------------------------------------- 差分基准（仅测试用，非生产代码）

    /**
     * **改造前**（两段式）的实现原文，只服务于差分用例。
     *
     * 留着它是为了把「线性化没有改变任何可观察行为」变成机器可验证的事实：
     * `<a >`、`</abbr>`、标签内嵌 `<a`、`data-href`、全角空格这些交叉情形，
     * 靠人读代码是覆盖不全的。
     *
     * ⚠️ 它本身是 O(n²)，**只允许喂小规模语料**（差分用例的语料都 < 1KB）。
     */
    private fun parseBeforeLinearization(text: String): List<Pair<String, String>> {
        if (text.length > BookmarkHtml.MAX_IMPORT_CHARS) return emptyList()
        val out = ArrayList<Pair<String, String>>()
        var from = 0
        while (true) {
            val tag = REF_ANCHOR_TAG.find(text, from) ?: break
            val bodyStart = tag.range.last + 1
            val close = REF_CLOSE_TAG.find(text, bodyStart)
            val title = if (close != null) text.substring(bodyStart, close.range.first) else ""
            from = if (close != null) close.range.last + 1 else bodyStart
            val href = REF_HREF_ATTR.find(tag.value)?.let { m ->
                (m.groupValues[2].ifEmpty { m.groupValues[3] }).ifEmpty { m.groupValues[4] }
            } ?: continue
            if (href.isBlank()) continue
            out += BookmarkHtml.decodeEntities(href).trim() to
                BookmarkHtml.decodeEntities(title).trim()
        }
        return out
    }

    private companion object {
        val REF_ANCHOR_TAG = Regex("""<a\s[^>]*>""", RegexOption.IGNORE_CASE)
        val REF_CLOSE_TAG = Regex("""</a\s*>""", RegexOption.IGNORE_CASE)
        val REF_HREF_ATTR =
            Regex("""(?<![\w-])href\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)
    }
}
