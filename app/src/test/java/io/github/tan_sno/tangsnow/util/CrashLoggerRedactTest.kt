package io.github.tan_sno.tangsnow.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 崩溃日志的 URL 脱敏（P1-2 的配套）。
 *
 * 政策第 7 条对崩溃日志给的是**封闭式**承诺：「仅含…与错误堆栈，并可能记录最近访问站点的
 * 域名（仅域名、不含完整网址）」。而异常 message 里常带完整 URL —— 不脱敏就等于承诺为假。
 */
class CrashLoggerRedactTest {

    @Test
    fun `完整 URL 被收敛成 scheme 与 host`() {
        assertEquals(
            "java.io.FileNotFoundException: https://example.com/…",
            CrashLogger.redactUrls("java.io.FileNotFoundException: https://example.com/a/b?q=1#f"),
        )
    }

    @Test
    fun `端口保留而路径查询片段丢弃`() {
        assertEquals(
            "java.net.ConnectException: http://10.0.0.2:8080/…",
            CrashLogger.redactUrls("java.net.ConnectException: http://10.0.0.2:8080/download?token=secret"),
        )
    }

    @Test
    fun `userinfo 里的凭据不得残留`() {
        val out = CrashLogger.redactUrls("boom https://user:pa55@example.com/private/x.pdf")
        assertFalse("脱敏后不得含凭据", out.contains("pa55"))
        assertFalse("脱敏后不得含用户名", out.contains("user:"))
        assertEquals("boom https://example.com/…", out)
    }

    @Test
    fun `大小写 scheme 同样处理`() {
        assertEquals("HTTPS://example.com/…", CrashLogger.redactUrls("HTTPS://example.com/x"))
    }

    @Test
    fun `同一行的多个 URL 都被处理`() {
        assertEquals(
            "a https://a.com/… b http://b.com/…",
            CrashLogger.redactUrls("a https://a.com/1 b http://b.com/2"),
        )
    }

    @Test
    fun `非 URL 文本一字不动（脱敏不得破坏排障价值）`() {
        val stack = """
            java.lang.IllegalStateException: session is not open
            	at io.github.tan_sno.tangsnow.MainActivity.onDestroy(MainActivity.kt:666)
            	at android.app.Activity.performDestroy(Activity.java:8305)
        """.trimIndent()
        assertEquals(stack, CrashLogger.redactUrls(stack))
    }

    @Test
    fun `只有 scheme 没有域名时不会被误改成半截`() {
        // `https://` 后面直接是空白：`\S+` 一个字符都没吃到 ⇒ 不匹配，原样保留
        assertEquals("no url here: https://", CrashLogger.redactUrls("no url here: https://"))
    }

    @Test
    fun `clearHost 之后「最近访问站点」不再留存`() {
        // 这一条钉的是 P1-2 的第二半：值住在进程内存里，没有出口就会跨过无痕会话与清除动作。
        CrashLogger.clearHost()
        assertNull(CrashLogger.lastHostOrNull())
    }
}
