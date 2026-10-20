package io.github.tan_sno.tangsnow.core

import android.text.Spannable
import android.text.style.StyleSpan
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tan_sno.tangsnow.data.Bookmark
import io.github.tan_sno.tangsnow.util.LegalText
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **W2 九桥在设备上真的接管了**（不是回落共享 Kotlin 实现）的硬证据（2026-10-20）。
 *
 * ## 判据（沿用 W1 的口径，见 [JniBoundaryTest]）
 * JVM 上 `CoreNative.available == false` ⇒ [CoreImpl] 全部返回 null（= 不接管），所以
 * 「两边都绿」只证明**降级路径没崩**。要在设备上证明 Rust 真接管，必须挑**非 null 的业务路径**
 * 输入，断言 `CoreImpl.xxx(...) != null` —— 桥没绑上或库没加载时这一档会落回 null，断言立刻红。
 *
 * ## 四组断言
 *  1. 下载文件名 4 桥：非空业务输入 → 非 null，且值符合共享实现口径；
 *  2. 崩溃日志 2 桥：非空输入 → 非 null；
 *  3. 书签 3 桥 + **真写 filesDir 往返**：export → 落盘 → 读回 → parse → sanitize，条目逐项保真
 *     （含 emoji —— CESU-8 往返正是只有设备才看得见的那一档，见 H3）；
 *  4. 法律粗体桥：**区间与 Kotlin 的 UTF-16 码元下标同口径**（见下）。
 *
 * 不起 Activity、不碰 GeckoView（理由同 [JniBoundaryTest]：本机 16KB AVD 上主界面会让模拟器段错误）。
 */
class W2SeamDeviceTest {

    @Test
    fun 核心库已加载() {
        assertTrue("W2 断言的前提：.so 必须随包并加载成功", CoreNative.available)
    }

    @Test
    fun 历史过滤与法律粗体两桥真接管() {
        // shouldSkipHistoryUrl：Boolean，无 null 业务态（guard 默认 true 是 fail-closed）
        assertEquals(true, CoreImpl.shouldSkipHistoryUrl("about:home"))
        assertEquals(true, CoreImpl.shouldSkipHistoryUrl("DATA:text/html,x"))
        assertEquals(false, CoreImpl.shouldSkipHistoryUrl("https://example.com/"))

        // legalParseBoldRanges：扁平 [start,end,...] 闭区间；空数组 = 无成对标记（合法结果）
        val ranges = CoreImpl.legalParseBoldRanges("a**bc**d")
        assertNotNull("legalParseBoldRanges 应为 non-null", ranges)
        assertArrayEquals(intArrayOf(1, 2), ranges)
        assertArrayEquals(intArrayOf(), CoreImpl.legalParseBoldRanges("无标记"))
    }

    /**
     * **法律粗体：native 接管时的区间与 Kotlin 同口径**（2026-10-20 审查报告 B-1）。
     *
     * 这条断言打的是 `LegalText.parse` 里**此前从未被执行过**的那条分支：
     * coreRust flavor + 设备上 `.so` 已加载 ⇒ 区间来自 Rust，
     * 而 `Spannable` 的下标按 **UTF-16 码元**计 ⇒ 两侧必须同口径，
     * 否则 emoji 之后的加粗会整段左移（套到代理对后半截上，**且不报错**）。
     *
     * 用 `emphasize`（公开 API）而不是 `parse`（internal）：断言的是**最终落到 Span 上的位置**
     * —— 那才是用户看到的东西。
     */
    @Test
    fun 法律粗体桥与Kotlin同口径且emoji不左移() {
        // ① 桥真接管（native 可用 ⇒ 非 null）
        val native = CoreImpl.legalParseBoldRanges("a😀**bc**d")
        assertNotNull("legalParseBoldRanges 应为 non-null", native)
        assertArrayEquals(intArrayOf(3, 4), native)

        // ② 端到端：区间最终套到 Span 上，必须落在 "bc" 上
        val spanned = LegalText.emphasize("a😀**bc**d")
        assertTrue("emphasize 应返回 Spannable", spanned is Spannable)
        val sp = spanned as Spannable
        val bold = sp.getSpans(0, sp.length, StyleSpan::class.java)
        assertEquals("应恰好一处加粗", 1, bold.size)
        assertEquals("加粗应从第 3 个 UTF-16 码元开始", 3, sp.getSpanStart(bold[0]))
        assertEquals("加粗应覆盖 'bc' 两个码元", 5, sp.getSpanEnd(bold[0]))
    }

    @Test
    fun 下载文件名四桥真接管() {
        // parseFileName：Content-Disposition 优先；native 可用时 Rust 恒给非空名
        val parsed = CoreImpl.dlParseFileName("attachment; filename=\"报告.pdf\"", "https://a.com/x")
        assertNotNull("dlParseFileName 应为 non-null", parsed)
        assertEquals("报告.pdf", parsed)

        // sanitizeFileName：路径分隔符等非法字符 → `_`
        val clean = CoreImpl.dlSanitizeFileName("a/b:c*.txt")
        assertNotNull("dlSanitizeFileName 应为 non-null", clean)
        assertTrue("非法字符必须被替换，实际=$clean", clean!!.none { it in "/\\:*?\"<>|" })

        // isExecutableName：Boolean，无 null 业务态
        assertEquals(true, CoreImpl.dlIsExecutableName("setup.apk"))
        assertEquals(false, CoreImpl.dlIsExecutableName("notes.txt"))

        // bareMimeType：剥 `;` 参数
        assertEquals("text/html", CoreImpl.dlBareMimeType("text/html; charset=utf-8"))
    }

    @Test
    fun 崩溃日志两桥真接管() {
        val redacted = CoreImpl.crashRedactUrls("boom https://example.com/a/b?token=x")
        assertNotNull("crashRedactUrls 应为 non-null", redacted)
        assertEquals("boom https://example.com/…", redacted)

        val stamp = CoreImpl.crashDisplayStamp("crash-20261001-121500-123.txt")
        assertNotNull("crashDisplayStamp 应为 non-null", stamp)
        assertEquals("2026-10-01 12:15:00", stamp)
    }

    @Test
    fun 书签三桥真接管且落盘往返保真() {
        val src = listOf(
            Bookmark(
                id = 1L,
                url = "https://例子.example/?a=1&b=2",
                title = "示例 & 站点 🎈",
                createdAt = 1_700_000_000_000L,
            ),
            Bookmark(id = 2L, url = "https://plain.example/x", title = "第二个", createdAt = 1_700_000_100_000L),
        )

        // ① export（Rust → Netscape HTML），非 null
        val html = CoreImpl.bookmarkExport(src)
        assertNotNull("bookmarkExport 应为 non-null", html)

        // ② 真写 filesDir 再读回 —— 走真实文件 IO，不是内存里的字符串对拷
        val filesDir = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        val f = File(filesDir, "w2-seam-roundtrip.html")
        try {
            f.writeText(html!!, Charsets.UTF_8)
            val readBack = f.readText(Charsets.UTF_8)
            assertEquals("落盘往返必须逐字一致", html, readBack)

            // ③ parse（Rust JSON 桥）：非 null，且条目保真（含 emoji / 实体往返）
            val parsed = CoreImpl.bookmarkParse(readBack, System.currentTimeMillis())
            assertNotNull("bookmarkParse 应为 non-null（JSON 桥真接管）", parsed)
            val (raw, dropped) = parsed!!
            assertEquals("未达原始条目上限，不应有 dropped", 0, dropped)
            assertEquals(src.size, raw.size)
            assertEquals(src[0].url, raw[0].url)
            assertEquals(src[0].title, raw[0].title)
            assertEquals(src[0].createdAt, raw[0].createdAtMs)
            assertEquals(src[1].url, raw[1].url)

            // ④ sanitize（Rust JSON 桥）：非 null；已存在 URL 应去重并计入 skipped
            val (kept, skipped) = CoreImpl.bookmarkSanitize(raw, emptySet())!!
            assertEquals(src.size, kept.size)
            assertEquals(0, skipped)
            val again = CoreImpl.bookmarkSanitize(kept, setOf(src[0].url))!!
            assertEquals("第一条与 existing 重复，应计 1 条 skipped", 1, again.second)
        } finally {
            f.delete()
        }
    }
}