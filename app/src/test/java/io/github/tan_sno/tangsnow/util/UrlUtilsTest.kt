package io.github.tan_sno.tangsnow.util

import io.github.tan_sno.tangsnow.data.SearchEngine
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

    // ------------------------------------------------------------- extractUrlFromText

    @Test
    fun `分享提取_纯链接原样返回`() {
        assertEquals(
            "https://example.com/a/b?q=1",
            UrlUtils.extractUrlFromText("https://example.com/a/b?q=1"),
        )
    }

    @Test
    fun `分享提取_链接前后有文字时取第一个链接`() {
        assertEquals(
            "https://x.com/1",
            UrlUtils.extractUrlFromText("看看这个 https://x.com/1 还有 https://y.com/2"),
        )
    }

    @Test
    fun `分享提取_句尾标点被剥离`() {
        assertEquals(
            "https://x.com/1",
            UrlUtils.extractUrlFromText("快看 https://x.com/1."),
        )
        assertEquals(
            "https://x.com/1",
            UrlUtils.extractUrlFromText("快看 (https://x.com/1)"),
        )
    }

    @Test
    fun `分享提取_说明文字黏在链接后被 CJK 标点截断`() {
        // 无空格黏连：\S+ 会连「。转疯了」一起捕获，必须从 CJK 标点处硬截断
        assertEquals(
            "https://x.com/1",
            UrlUtils.extractUrlFromText("https://x.com/1。转疯了"),
        )
        assertEquals(
            "https://x.com/1",
            UrlUtils.extractUrlFromText("https://x.com/1，快看"),
        )
    }

    @Test
    fun `分享提取_截断的百分号编码残渣被剥掉而完整编码保留`() {
        // 完整编码（如 %20）是 URL 的正常组成部分，必须保留
        assertEquals(
            "https://x.com/a%20b",
            UrlUtils.extractUrlFromText("https://x.com/a%20b。"),
        )
        // 「% + 不足两位十六进制」的不完整残渣剥回边界；已形如完整编码的尾巴
        // （%B8）与真实编码无法区分，保守保留 —— 宁可多留两位也不损坏合法 URL
        assertEquals(
            "https://x.com/a%E4",
            UrlUtils.extractUrlFromText("https://x.com/a%E4%B"),
        )
        assertEquals(
            "https://x.com/a%E4%B8",
            UrlUtils.extractUrlFromText("https://x.com/a%E4%B8%"),
        )
        assertEquals(
            "https://x.com/a",
            UrlUtils.extractUrlFromText("https://x.com/a%"),
        )
    }

    @Test
    fun `分享提取_大写 scheme 原样保留`() {
        assertEquals(
            "HTTP://X.com/Path",
            UrlUtils.extractUrlFromText("HTTP://X.com/Path"),
        )
    }

    @Test
    fun `分享提取_无链接的纯文本返回 null`() {
        assertNull(UrlUtils.extractUrlFromText("今天天气不错"))
        assertNull(UrlUtils.extractUrlFromText(""))
        assertNull(UrlUtils.extractUrlFromText("ftp://example.com/file"))
        assertNull(UrlUtils.extractUrlFromText("example.com/无协议地址"))
    }
}
