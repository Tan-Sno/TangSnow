package io.github.tan_sno.tangsnow.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网页导航 scheme 白名单（C 批 / P2-3）。
 *
 * 这**一份**判定同时被两处引用：内核侧的 `onLoadRequest`（拦网页内容发起的外链）与
 * 应用侧的 `onOpenInCurrentTab`（拦 window.open 经 loadUri 绕过内核闸门）。因此本文件里的
 * 每一条断言都同时钉住了「不留缺口」与「不误挡」两侧。
 */
class SchemeGateTest {

    // ------------------------------------------------------------- schemeOf

    @Test
    fun `scheme 解析大小写不敏感且不含冒号`() {
        assertEquals("http", UrlUtils.schemeOf("http://a.com"))
        assertEquals("https", UrlUtils.schemeOf("HTTPS://a.com"))
        assertEquals("about", UrlUtils.schemeOf("about:blank"))
        assertEquals("file", UrlUtils.schemeOf("file:///data/data/x/files/a.json"))
        assertEquals("moz-extension", UrlUtils.schemeOf("moz-extension://uuid/page.html"))
    }

    @Test
    fun `没有合法 scheme 时返回空串而不是瞎猜`() {
        assertEquals("", UrlUtils.schemeOf(""))
        assertEquals("", UrlUtils.schemeOf("example.com/x"))          // 无冒号
        assertEquals("", UrlUtils.schemeOf(":no-scheme"))
        assertEquals("", UrlUtils.schemeOf("//example.com/x"))        // 协议相对
        // 冒号前含非法 scheme 字符 ⇒ 按「没有 scheme」处理，不臆造
        assertEquals("", UrlUtils.schemeOf("no scheme ://x"))
    }

    // ------------------------------------------------------------- 白名单

    @Test
    fun `网页导航 scheme 全部放行`() {
        listOf("http", "https", "about", "data", "blob").forEach {
            assertTrue("$it 应放行", UrlUtils.isWebNavigationScheme(it))
        }
    }

    @Test
    fun `特权与外部 scheme 一律不放行`() {
        // file:/moz-extension: 是**安全**判据；其余是「不该由网页内容驱动」或另有专用通道
        listOf(
            "file", "moz-extension", "javascript", "intent", "market", "mailto", "tel", "geo",
            "content", "package", "chrome", "resource", "", null,
        ).forEach {
            assertFalse("$it 不应放行", UrlUtils.isWebNavigationScheme(it))
        }
    }

    @Test
    fun `大小写不同的 scheme 走同一结论`() {
        assertTrue(UrlUtils.isWebNavigationScheme(UrlUtils.schemeOf("DATA:text/html,<b>x")))
        assertFalse(UrlUtils.isWebNavigationScheme(UrlUtils.schemeOf("File:///etc/hosts")))
    }

    /**
     * 三分法必须**互不重叠、且合起来覆盖全部输入** —— `window.open` 的收口正是按它分流的：
     * 网页档照常加载、禁用档静默丢弃、其余交回「外部打开」。
     * 判据一乱，两侧就同时出问题：把 `file` 划进"其余"= 安全缺口（交给系统也读不到，但会刷提示）；
     * 把 `mailto` 划进"禁用档"= 功能回归（`target="_blank"` 的邮件按钮点了没反应）。
     */
    @Test
    fun `三分法互不重叠：网页档、禁用档、交系统档`() {
        val web = listOf("http", "https", "about", "data", "blob")
        val never = listOf("file", "moz-extension", "javascript")
        val external = listOf("mailto", "tel", "market", "intent", "geo", "whatsapp", "package", "content")

        web.forEach {
            assertTrue("$it 应属网页档", UrlUtils.isWebNavigationScheme(it))
            assertFalse("$it 不得同时属禁用档", UrlUtils.isNeverWebContentScheme(it))
        }
        never.forEach {
            assertTrue("$it 应属禁用档", UrlUtils.isNeverWebContentScheme(it))
            assertFalse("$it 不得属网页档", UrlUtils.isWebNavigationScheme(it))
        }
        external.forEach {
            assertFalse("$it 不该属网页档", UrlUtils.isWebNavigationScheme(it))
            assertFalse("$it 不该属禁用档（误挡即功能回归）", UrlUtils.isNeverWebContentScheme(it))
        }
    }
}
