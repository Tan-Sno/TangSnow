package io.github.tan_sno.tangsnow.data

import io.github.tan_sno.tangsnow.util.CodePoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BookmarkHtml]：Netscape 书签 HTML 的解析与导入清洗。
 * 全部为纯字符串/数据逻辑，可在普通 JVM 单测中运行。
 *
 * ## 期望值来自**两侧共享的 JSON 夹具**（2026-10-20 审查报告 C-14）
 *
 * 解析/清洗/导出/ADD_DATE 的期望值此前与 Rust 侧 `tests/bookmark_html_parity.rs`
 * **各写一份** —— 那是**镜像**：改实现 + 同步改本侧的镜像期望值 ⇒ 两侧照样全绿，
 * 而两边的口径已经漂了（「标题截断按码点 vs 按 UTF-16」就是这么活下来的）。
 * 现在两边读**同一份**夹具
 * （`tangsnow-rs/tangsnow-core/tests/fixtures/bookmark_html_cases.json`）⇒ 改夹具必然两侧一起红。
 *
 * 留在本文件里的是**性质**（复杂度哨兵、体积/条数上限、按码点截断、导出→导入往返、
 * 与改造前两段式实现的逐条差分），它们断言的是「性质」而非「某个具体值」，故不进夹具。
 */
class BookmarkHtmlTest {

    // ------------------------------------------------------------- 共享夹具

    /**
     * **夹具里的每条用例与 Rust 侧同口径**。
     *
     * kind ∈ parse / parseAddDate / sanitize / export / isHttpUrl；args 按 kind 顺序；
     * expected 的形状随 kind 而定（详见夹具的 `_kind`）。
     *
     * 为什么这比「两侧各写一份期望值」更强：夹具里的期望值**独立于实现**，
     * 故「实现错了 ⇒ 两侧一起红」；而镜像写法里改实现 + 同步改本侧期望值 ⇒ 两侧照样全绿。
     */
    @Test
    fun `夹具里的每条用例与Rust侧同口径`() {
        val cases = SharedFixtures.cases(FIXTURE)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val id = c.getString("id")
            val kind = c.getString("kind")
            val a = c.getJSONArray("args")
            when (kind) {
                // args=[html] → expected=[{url,title,createdAtMs}]（按导入顺序）
                "parse" -> {
                    val got = BookmarkHtml.parseImport(a.getString(0))
                    val want = c.getJSONArray("expected")
                    assertEquals("夹具用例「$id」条目数", want.length(), got.size)
                    for (j in 0 until want.length()) {
                        val w = want.getJSONObject(j)
                        assertEquals("夹具用例「$id」第 $j 条 url", w.getString("url"), got[j].url)
                        assertEquals("夹具用例「$id」第 $j 条 title", w.getString("title"), got[j].title)
                        assertTrue("夹具用例「$id」第 $j 条缺 createdAtMs", w.has("createdAtMs"))
                        val wantMs = if (w.isNull("createdAtMs")) null else w.getLong("createdAtMs")
                        // 分开判 null：交给 assertEquals 会被解析成 (long, long) 重载 ⇒ null 时抛 NPE
                        if (wantMs == null) {
                            assertNull("夹具用例「$id」第 $j 条 createdAtMs", got[j].createdAtMs)
                        } else {
                            assertEquals("夹具用例「$id」第 $j 条 createdAtMs", wantMs, got[j].createdAtMs!!)
                        }
                    }
                }
                // args=[raw, now] → expected=int|null
                "parseAddDate" -> {
                    // now 是**夹具给的数据** ⇒ 与真实墙钟无关，两侧同值
                    assertTrue("夹具用例「$id」缺 expected", c.has("expected"))
                    val want = if (c.isNull("expected")) null else c.getLong("expected")
                    val got = BookmarkHtml.parseAddDate(a.getString(0), a.getLong(1))
                    if (want == null) {
                        assertNull("夹具用例「$id」", got)
                    } else {
                        assertEquals("夹具用例「$id」", want, got!!)
                    }
                }
                // args=[html, existingUrls[]] → expected={kept:[{url,title}], skipped:int}
                "sanitize" -> {
                    val existing = a.getJSONArray(1).let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }.toSet()
                    }
                    val (kept, skipped) = BookmarkHtml.sanitize(
                        BookmarkHtml.parseImport(a.getString(0)), existing
                    )
                    val want = c.getJSONObject("expected")
                    val wantKept = want.getJSONArray("kept")
                    assertEquals("夹具用例「$id」kept 条数", wantKept.length(), kept.size)
                    for (j in 0 until wantKept.length()) {
                        val w = wantKept.getJSONObject(j)
                        assertEquals("夹具用例「$id」第 $j 条 url", w.getString("url"), kept[j].url)
                        assertEquals("夹具用例「$id」第 $j 条 title", w.getString("title"), kept[j].title)
                    }
                    assertEquals("夹具用例「$id」skipped", want.getInt("skipped"), skipped)
                }
                // args=[{url,title,createdAt}] → expected=str（完整导出文本）
                "export" -> {
                    val items = a.getJSONArray(0)
                    val list = (0 until items.length()).map { j ->
                        val o = items.getJSONObject(j)
                        Bookmark(0L, o.getString("url"), o.getString("title"), o.getLong("createdAt"))
                    }
                    assertEquals("夹具用例「$id」", c.getString("expected"), BookmarkHtml.export(list))
                }
                // args=[url] → expected=bool
                "isHttpUrl" -> {
                    assertEquals(
                        "夹具用例「$id」",
                        c.getBoolean("expected"),
                        BookmarkHtml.isHttpUrl(a.getString(0)),
                    )
                }
                else -> throw AssertionError("夹具里的 kind「$kind」本文件不认（改夹具要同步两侧的派发）")
            }
        }
    }

    // ------------------------------------------------------------- 上限与体积（性质）

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

    @Test
    fun `导入数量受上限约束`() {
        val raw = (0 until BookmarkHtml.MAX_IMPORT_COUNT + 50).map {
            BookmarkHtml.Entry("https://p$it.cn/", "t$it")
        }
        val (entries, skipped) = BookmarkHtml.sanitize(raw, emptySet())
        assertEquals(BookmarkHtml.MAX_IMPORT_COUNT, entries.size)
        assertEquals(50, skipped)
    }

    // ------------------------------------------------------------- 复杂度哨兵（性质）

    @Test(timeout = 15_000)
    fun `有效锚极多时仍在时限内完成——计数版本不得拖慢解析`() {
        // 覆盖「有效锚极多」这一形态：旧实现撞到 RAW_PARSE_ENTRY_CAP 就 break，而计数版本
        // 必须扫完才能如实回报被丢弃的条数（否则界面数字又不对）—— 本用例证明这不是新的性能洞。
        // 规模取到**逼近体积上限**（≈5MB）：任何超线性退化都会在 15 秒内超时失败。
        val perAnchor = "<a href=\"https://p.cn/\">t</a>".length
        val n = BookmarkHtml.MAX_IMPORT_CHARS / perAnchor
        val html = "<a href=\"https://p.cn/\">t</a>".repeat(n)
        assertTrue("输入必须落在体积上限内：${html.length}", html.length <= BookmarkHtml.MAX_IMPORT_CHARS)

        val t0 = System.nanoTime()
        val (entries, dropped) = BookmarkHtml.parseImportCounted(html)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("perf: anchors=$n len=${html.length} entries=${entries.size} dropped=$dropped ms=$ms")

        assertEquals(BookmarkHtml.RAW_PARSE_ENTRY_CAP, entries.size)
        assertEquals(n - BookmarkHtml.RAW_PARSE_ENTRY_CAP, dropped)
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

    // ------------------------------------------------------------- 标题截断（性质）

    @Test
    fun `超长标题截断而非拒收`() {
        val longTitle = "标".repeat(BookmarkHtml.MAX_TITLE_LENGTH + 10)
        val (entries, _) = BookmarkHtml.sanitize(
            listOf(BookmarkHtml.Entry("https://t.cn/", longTitle)), emptySet()
        )
        // 量纲是**码点**（与 Rust `chars().take(n)` 同口径）—— 用 UTF-16 码元数断言
        // 在标题含 emoji 时会骗过自己（emoji 占 2 个码元），所以这里走共享的码点计数。
        assertEquals(BookmarkHtml.MAX_TITLE_LENGTH, CodePoints.countCodePoints(entries[0].title))
    }

    /**
     * 标题截断必须按**码点**、不得劈开代理对。
     *
     * 回归的是什么：`String.take(n)` 取的是前 n 个 **UTF-16 码元**，n 落在代理对中间时
     * 会把一个 emoji 劈成两半 ⇒ 落库一个**孤立代理**（渲染成 `�`）。而 Rust 侧
     * `chars().take(n)` 按码点 ⇒ 两侧原本对同一条含 emoji 的标题会给出不同结果。
     * 现两侧统一按码点（Kotlin 见 `util/CodePoints.takeCodePoints`）。
     */
    @Test
    fun `标题截断按码点_不劈开代理对且与Rust侧同口径`() {
        val emoji = "🎈" // U+1F388，星平面：Kotlin 里占 2 个 UTF-16 码元
        // 199 个 BMP 字符 + 一串 emoji：截断点正好落在代理对中间
        val longTitle = "标".repeat(BookmarkHtml.MAX_TITLE_LENGTH - 1) + emoji + "尾"
        val (entries, _) = BookmarkHtml.sanitize(
            listOf(BookmarkHtml.Entry("https://t.cn/", longTitle)), emptySet()
        )
        val title = entries[0].title
        // 1) 不得出现孤立代理：任何代理项都必须是完整的「高+低」配对
        var i = 0
        while (i < title.length) {
            val cp = title.codePointAt(i)
            if (Character.isHighSurrogate(title[i])) {
                assertTrue("代理对必须完整，低代理缺失于 $i", i + 1 < title.length)
                assertTrue("代理对必须完整，低代理不是代理于 $i", Character.isLowSurrogate(title[i + 1]))
                i += 2
            } else {
                assertFalse("此处不应出现孤立低代理于 $i", Character.isLowSurrogate(title[i]))
                i++
            }
        }
        // 2) 量纲是码点（与 Rust `chars().count()` 一致），不是 UTF-16 码元
        assertEquals(BookmarkHtml.MAX_TITLE_LENGTH, CodePoints.countCodePoints(title))
        // 3) 截断后的末尾必须是完整的 emoji（而不是它的半个代理）
        assertTrue(title.endsWith(emoji))
    }

    // ------------------------------------------------------------- 导出→导入往返（性质）

    /**
     * 导出→导入必须**无损**（性质，不是某个具体期望值）。
     *
     * 前提：标题**两端无空白** —— 导入侧会裁掉两端空白（`trim`），导出侧不裁，
     * 故带空白标题的往返不是无损的（那是既有口径，不在本性质的范围内）。
     */
    @Test
    fun `导出导入往返_标题与收藏时间逐字不变`() {
        for (title in listOf("Use &lt;div&gt;", "示例 & <站点>", "say \"hi\"", "普通标题")) {
            val html = BookmarkHtml.export(
                listOf(Bookmark(0L, "https://r.cn/", title, 1_700_000_000_000L))
            )
            val parsed = BookmarkHtml.parseImport(html)
            assertEquals("标题 $title 往返后条目数不对", 1, parsed.size)
            assertEquals("标题 $title 往返后 URL 变了", "https://r.cn/", parsed[0].url)
            assertEquals("标题 $title 往返后被改写", title, parsed[0].title)
            // 往返损失亚秒（导出格式的 ADD_DATE 是秒级）
            assertNotNull("标题 $title 往返后收藏时间丢了", parsed[0].createdAtMs)
            assertEquals("标题 $title 往返后收藏时间变了", 1_700_000_000_000L, parsed[0].createdAtMs!!)
        }
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
        /** 两侧共享的夹具（Rust 侧读同一个文件） */
        const val FIXTURE = "bookmark_html_cases.json"

        val REF_ANCHOR_TAG = Regex("""<a\s[^>]*>""", RegexOption.IGNORE_CASE)
        val REF_CLOSE_TAG = Regex("""</a\s*>""", RegexOption.IGNORE_CASE)
        val REF_HREF_ATTR =
            Regex("""(?<![\w-])href\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)
    }
}
