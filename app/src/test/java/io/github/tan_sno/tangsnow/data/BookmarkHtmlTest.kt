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
}
