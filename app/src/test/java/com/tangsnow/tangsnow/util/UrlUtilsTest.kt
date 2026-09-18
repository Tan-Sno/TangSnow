package com.tangsnow.tangsnow.util

import com.tangsnow.tangsnow.data.SearchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 地址栏输入解析的单元测试。
 *
 * 这些用例锁住的是「用户敲了什么 → 打开什么」这条最基础的行为：
 * 该当网址的不能丢给搜索引擎，该当搜索词的不能被误当域名。
 * 全部为纯逻辑（不依赖 android.net.Uri），可在普通 JVM 单测中运行。
 */
class UrlUtilsTest {

    private val engine = SearchEngine(
        id = "test",
        template = "https://search.test/?q={q}",
        queryParam = "q",
    )

    // ------------------------------------------------------------- looksLikeUri

    @Test
    fun `带协议与路径的完整地址识别为网址`() {
        assertTrue(UrlUtils.looksLikeUri("https://example.com/a/b?c=d"))
        assertTrue(UrlUtils.looksLikeUri("http://example.com"))
    }

    @Test
    fun `裸域名识别为网址`() {
        assertTrue(UrlUtils.looksLikeUri("example.com"))
        assertTrue(UrlUtils.looksLikeUri("sub.example.com/path"))
        assertTrue(UrlUtils.looksLikeUri("example.com:8080"))
    }

    @Test
    fun `localhost 与内网设备名识别为网址而非搜索词`() {
        // 开发/内网日常入口：丢给搜索引擎等于功能缺失
        assertTrue(UrlUtils.looksLikeUri("localhost"))
        assertTrue(UrlUtils.looksLikeUri("localhost:8080"))
        assertTrue(UrlUtils.looksLikeUri("nas:5000"))
    }

    @Test
    fun `中文域名识别为网址`() {
        assertTrue(UrlUtils.looksLikeUri("例子.测试"))
    }

    @Test
    fun `普通词句不是网址`() {
        assertFalse(UrlUtils.looksLikeUri("hello"))
        assertFalse(UrlUtils.looksLikeUri("今天天气怎么样"))
        assertFalse(UrlUtils.looksLikeUri(""))
        assertFalse(UrlUtils.looksLikeUri("   "))
    }

    @Test
    fun `含空白的输入一律不是网址`() {
        assertFalse(UrlUtils.looksLikeUri("example.com 空格"))
        assertFalse(UrlUtils.looksLikeUri("aa bb.com"))
    }

    // ------------------------------------------------------------- resolveInfo

    @Test
    fun `http 与 https 直接透传且不标记为搜索`() {
        val r = UrlUtils.resolveInfo("https://example.com/x", engine)
        assertEquals("https://example.com/x", r?.url)
        assertEquals(false, r?.isSearch)
    }

    @Test
    fun `裸域名补 https`() {
        val r = UrlUtils.resolveInfo("example.com", engine)
        assertEquals("https://example.com", r?.url)
        assertEquals(false, r?.isSearch)
    }

    @Test
    fun `localhost 与内网主机补 http`() {
        assertEquals("http://localhost:8080", UrlUtils.resolveInfo("localhost:8080", engine)?.url)
        assertEquals("http://nas:5000", UrlUtils.resolveInfo("nas:5000", engine)?.url)
    }

    @Test
    fun `中文域名转 punycode 后按网址导航`() {
        val r = UrlUtils.resolveInfo("例子.测试", engine)
        assertTrue(r?.url?.startsWith("https://xn--") == true)
        assertEquals(false, r?.isSearch)
    }

    @Test
    fun `普通词句走搜索引擎且标记为搜索`() {
        val r = UrlUtils.resolveInfo("棠雪 浏览器", engine)
        assertEquals(true, r?.isSearch)
        // 关键词必须被百分号编码后放进模板
        assertEquals("https://search.test/?q=%E6%A3%A0%E9%9B%AA%20%E6%B5%8F%E8%A7%88%E5%99%A8", r?.url)
    }

    @Test
    fun `空输入返回 null`() {
        assertNull(UrlUtils.resolveInfo("", engine))
        assertNull(UrlUtils.resolveInfo("   ", engine))
    }

    @Test
    fun `resolve 为空输入返回 null 且等价于 resolveInfo 的 url`() {
        assertNull(UrlUtils.resolve("", engine))
        assertEquals(
            UrlUtils.resolveInfo("example.com", engine)?.url,
            UrlUtils.resolve("example.com", engine),
        )
    }
}
