package io.github.tan_sno.tangsnow.util

import io.github.tan_sno.tangsnow.data.SharedFixtures
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

    /** 两侧共享的夹具（Rust 侧读同一个文件） */
    private fun case(id: String) = SharedFixtures.pick(FIXTURE, id)

    private companion object {
        const val FIXTURE = "crash_logger_cases.json"
    }

    /**
     * **与 Rust 侧同口径的脱敏用例**（期望值来自两侧共享的 JSON 夹具）。
     *
     * 2026-10-20 审查报告 C-14：原先这里与 Rust 侧 `crash_logger_parity.rs` **各写一份期望值**
     * —— 那是**镜像**：改实现 + 同步改本侧的镜像期望值 ⇒ 两侧照样全绿，而脱敏口径已经漂了。
     * 现在两边读**同一份**夹具（`tangsnow-rs/tangsnow-core/tests/fixtures/crash_logger_cases.json`）
     * ⇒ 改夹具必然两侧一起红；只想改一侧的期望值，必须显式改夹具 —— 那一刻两侧就一起变了。
     */
    @Test
    fun `完整 URL 被收敛成 scheme 与 host`() {
        assertEquals(
            case("完整 URL 被收敛成 scheme 与 host").expected,
            CrashLogger.redactUrls(case("完整 URL 被收敛成 scheme 与 host").input),
        )
    }

    @Test
    fun `端口保留而路径查询片段丢弃`() {
        assertEquals(
            case("端口保留而路径查询片段丢弃").expected,
            CrashLogger.redactUrls(case("端口保留而路径查询片段丢弃").input),
        )
    }

    @Test
    fun `userinfo 里的凭据不得残留`() {
        val c = case("userinfo 里的凭据不得残留")
        val out = CrashLogger.redactUrls(c.input)
        assertFalse("脱敏后不得含凭据", out.contains("pa55"))
        assertFalse("脱敏后不得含用户名", out.contains("user:"))
        assertEquals(c.expected, out)
    }

    @Test
    fun `路径里含 at 符号时 host 不得被误剥`() {
        // userinfo 的 `@` 只在 authority 段合法；先截 authority 再剥 `@`，
        // 否则 /docs/@user 里的 `@` 会把 host 连同路径前缀整个吃掉
        val c = case("路径里含 at 符号时 host 不得被误剥")
        assertEquals(c.expected, CrashLogger.redactUrls(c.input))
    }

    @Test
    fun `userinfo 与路径含 at 符号同时存在时各归其位`() {
        val c = case("userinfo 与路径含 at 符号同时存在时各归其位")
        val out = CrashLogger.redactUrls(c.input)
        assertFalse(out.contains("alice"))
        assertEquals(c.expected, out)
    }

    @Test
    fun `大小写 scheme 同样处理`() {
        val c = case("大小写 scheme 同样处理")
        assertEquals(c.expected, CrashLogger.redactUrls(c.input))
    }

    @Test
    fun `同一行的多个 URL 都被处理`() {
        val c = case("同一行的多个 URL 都被处理")
        assertEquals(c.expected, CrashLogger.redactUrls(c.input))
    }

    @Test
    fun `非 URL 文本一字不动（脱敏不得破坏排障价值）`() {
        val c = case("非 URL 文本一字不动（脱敏不得破坏排障价值）")
        assertEquals(c.expected, CrashLogger.redactUrls(c.input))
    }

    @Test
    fun `只有 scheme 没有域名时不会被误改成半截`() {
        // `https://` 后面直接是空白：`\S+` 一个字符都没吃到 ⇒ 不匹配，原样保留
        val c = case("只有 scheme 没有域名时不会被误改成半截")
        assertEquals(c.expected, CrashLogger.redactUrls(c.input))
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
    fun `敏感键名后跟认证方案时凭据仍被收敛`() {
        // 顺序敏感的另一形态：键名本身含敏感词（token/session/…）时，若键值层先跑，
        // 会把值槽里的方案词（Bearer/Basic）当"值"吃掉，认证方案层随即失配 ⇒
        // 真凭据成了无主裸串落盘（2026-10-24 复核：两层嵌套顺序曾与 KDoc 要求相反）。
        val out = CrashLogger.redact("token: Bearer eyJhbGciOiJIUzI1NiJ9.abc")
        assertFalse("脱敏后不得含 JWT 片段", out.contains("eyJhbGci"))
        assertFalse("方案词也不得残留", out.contains("Bearer"))
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

    // ---- 以下 4 条钉 2026-10-19 的第三层收紧：复合/粘连的凭据名与 JSON 带引号键 ----
    // 背景：早期 `CREDENTIAL_PAIRS` 用固定词表 + `(?<![A-Za-z0-9_])`，把前缀为 `_` 或直接粘连的
    // 复合名整类漏掉；实测 13 种形态一字不落落盘（`client_secret` / `id_token` / `secret_key` 等）。

    @Test
    fun `复合与粘连的凭据名也被收敛`() {
        val out = CrashLogger.redact(
            "client_secret=SUPERSECRET id_token=eyJabc.def secret_key=SECRETVALUE clientsecret=GLUED",
        )
        assertFalse("脱敏后不得含 client_secret 的值", out.contains("SUPERSECRET"))
        assertFalse("脱敏后不得含 id_token 的值", out.contains("eyJabc"))
        assertFalse("脱敏后不得含 secret_key 的值", out.contains("SECRETVALUE"))
        assertFalse("脱敏后不得含粘连名 clientsecret 的值", out.contains("GLUED"))
        assertEquals("client_secret=… id_token=… secret_key=… clientsecret=…", out)
    }

    @Test
    fun `JSON 引号包裹的凭据键也被收敛`() {
        val out = CrashLogger.redact("""{"client_secret":"SUPERSECRET"}""")
        assertFalse("脱敏后不得含凭据值", out.contains("SUPERSECRET"))
        // 注意：`\S+` 会把值后面的 `}` 一并吃掉（与既有 `token=abc123` 同口径，日志里少个花括号无碍隐私）
        assertEquals("""{"client_secret":…""", out)
    }

    @Test
    fun `下划线 key 家族（access_key 与 private_key）也被收敛`() {
        // `key` 单独太泛（会误伤 keyboard/keyCode），故只显式纳入常见的 `<前缀>_key` 形态。
        val out = CrashLogger.redact("access_key=AKIAIOSFODNN7EXAMPLE private_key=IEnjf8s")
        assertFalse("脱敏后不得含 access_key 的值", out.contains("AKIAIOSFODNN7EXAMPLE"))
        assertFalse("脱敏后不得含 private_key 的值", out.contains("IEnjf8s"))
    }

    @Test
    fun `含敏感词的类名与普通词不被误伤`() {
        // 判据要求敏感词所在标识符之后紧跟 `["']?\s*[:=]`；类名/普通句子里没有这个分隔，必须原样保留。
        val frame = "at io.github.tan_sno.tangsnow.SessionManager.foo(SessionManager.kt:1603)"
        val prose = "java.lang.IllegalStateException: session is not open"
        assertEquals(frame, CrashLogger.redact(frame))
        assertEquals(prose, CrashLogger.redact(prose))
    }
}
