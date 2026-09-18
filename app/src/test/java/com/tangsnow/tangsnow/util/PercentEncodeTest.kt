package com.tangsnow.tangsnow.util

import com.tangsnow.tangsnow.data.SearchEngine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 百分号编码与搜索 URL 生成的单元测试。
 *
 * 重点：编码结果必须与 Android `Uri.encode` **逐字节一致** —— 这条改造只是把框架调用
 * 换成同语义的纯函数（为了可单测），绝不能顺带改变任何线上搜索 URL。
 */
class PercentEncodeTest {

    @Test
    fun `字母数字与 unreserved 字符原样保留`() {
        val keep = "abcXYZ0189_-!.~'()*"
        assertEquals(keep, percentEncode(keep))
    }

    @Test
    fun `保留字符以外的字符按 UTF-8 转百分号`() {
        assertEquals("%20", percentEncode(" "))
        assertEquals("%2F", percentEncode("/"))
        assertEquals("%3F", percentEncode("?"))
        assertEquals("%26", percentEncode("&"))
        assertEquals("%3D", percentEncode("="))
        assertEquals("%2B", percentEncode("+"))
        assertEquals("%23", percentEncode("#"))
        assertEquals("%25", percentEncode("%"))
    }

    @Test
    fun `中文按 UTF-8 逐字节编码`() {
        assertEquals("%E6%A3%A0%E9%9B%AA", percentEncode("棠雪"))
    }

    @Test
    fun `空串返回空串`() {
        assertEquals("", percentEncode(""))
    }

    @Test
    fun `Emoji 等多字节字符不越界`() {
        // U+1F600 → F0 9F 98 80
        assertEquals("%F0%9F%98%80", percentEncode("\uD83D\uDE00"))
    }

    @Test
    fun `模板占位符被替换为编码后的关键词`() {
        val e = SearchEngine("x", "https://s.test/?q={q}")
        assertEquals("https://s.test/?q=%E6%A3%A0%E9%9B%AA", e.searchUrl("棠雪"))
    }

    @Test
    fun `模板也支持百分号 s 占位符`() {
        val e = SearchEngine("x", "https://s.test/?wd=%s")
        assertEquals("https://s.test/?wd=abc", e.searchUrl("abc"))
    }

    @Test
    fun `模板不含占位符时追加 q 参数`() {
        val e = SearchEngine("x", "https://s.test/search")
        assertEquals("https://s.test/search?q=abc", e.searchUrl("abc"))
        val withQuery = SearchEngine("y", "https://s.test/search?lang=cn")
        assertEquals("https://s.test/search?lang=cn&q=abc", withQuery.searchUrl("abc"))
    }

    @Test
    fun `空模板回退到内置兜底地址`() {
        val e = SearchEngine("x", "")
        assertEquals("https://www.bing.com/search?q=abc", e.searchUrl("abc"))
    }

    @Test
    fun `关键词前后空白被裁剪`() {
        val e = SearchEngine("x", "https://s.test/?q={q}")
        assertEquals("https://s.test/?q=abc", e.searchUrl("  abc  "))
    }
}
