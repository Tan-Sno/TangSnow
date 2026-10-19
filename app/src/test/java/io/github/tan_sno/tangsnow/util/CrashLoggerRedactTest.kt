package io.github.tan_sno.tangsnow.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `路径里含 at 符号时 host 不得被误剥`() {
        // userinfo 的 `@` 只在 authority 段合法；先截 authority 再剥 `@`，
        // 否则 /docs/@user 里的 `@` 会把 host 连同路径前缀整个吃掉
        assertEquals(
            "fail https://example.com/…",
            CrashLogger.redactUrls("fail https://example.com/docs/@user?page=2"),
        )
    }

    @Test
    fun `userinfo 与路径含 at 符号同时存在时各归其位`() {
        val out = CrashLogger.redactUrls("fetch https://alice@s3.example.com/blobs/@me/x")
        assertFalse(out.contains("alice"))
        assertEquals("fetch https://s3.example.com/…", out)
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

    // ---- 以下 5 条钉 2026-10-19 补的两层：无 `//` 的 scheme 与不构成 URL 的凭据 ----
    // 背景：`redactUrls` 只认 `scheme://…`，而异常 message 里更常见的是不带双斜杠的形态。

    @Test
    fun `mailto 链接收敛为 scheme 与省略号`() {
        assertEquals(
            "boom mailto:…",
            CrashLogger.redact("boom mailto:alice@example.com?subject=hi"),
        )
    }

    @Test
    fun `data URI 不把内嵌内容写进日志`() {
        val out = CrashLogger.redact("load data:text/html;base64,PGh0bWw+dXNlcg==")
        assertFalse("脱敏后不得含内嵌 base64 内容", out.contains("PGh0bWw"))
        assertEquals("load data:…", out)
    }

    @Test
    fun `键值对凭据只收敛值并保留键名`() {
        // 键名保留是刻意的：排障时仍能看出"这里原本有个 token"，只把值收敛掉。
        assertEquals(
            "token=… 与 password: …",
            CrashLogger.redact("token=abc123 与 password: hunter2"),
        )
    }

    @Test
    fun `Bearer 认证方案后的凭据被收敛`() {
        // 顺序敏感：若让键值层先吃掉 `Authorization: Bearer`，后面的 JWT 就成了无主的裸 token。
        val out = CrashLogger.redact("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.abc")
        assertFalse("脱敏后不得含 JWT 片段", out.contains("eyJhbGci"))
    }

    @Test
    fun `整体脱敏链幂等且不误伤裸路径`() {
        val text = "err /data/user/0/app/files/x.txt 与 https://a.com/b?token=s 与 token=t"
        val once = CrashLogger.redact(text)
        assertFalse("URL 查询里的 token 不得残留", once.contains("token=s"))
        assertFalse("裸 token 的值不得残留", once.contains("token=t"))
        assertTrue("裸路径不属于 URL，必须保留（排障价值）", once.contains("/data/user/0/app/files/x.txt"))
        assertEquals("重复脱敏必须稳定（幂等）", once, CrashLogger.redact(once))
    }
}
