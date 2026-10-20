package io.github.tan_sno.tangsnow.core

import io.github.tan_sno.tangsnow.data.Bookmark
import io.github.tan_sno.tangsnow.data.BookmarkHtml
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM 上 coreRust 的 [CoreImpl] 必须**全部不接管**（`available == false` ⇒ 全 null），2026-10-20。
 *
 * ## 为什么必须有这个文件
 * 「两 flavor 在 JVM 上逐字节一致」是 W1/W2 接缝的根基：共享 `app/src/test` 的同一批断言要在
 * 两个 flavor 上各跑一遍。W2 有几个桥在 [CoreNative] 层的**非 null 默认值是安全默认值**
 * （`dlIsExecutableName` → true、`dlSanitizeFileName` → "download"、`crashDisplayStamp` → 原串）。
 * 若 [CoreImpl] 不先问 `available`，JVM 上这些默认值会被当成"接管结果"返回 ⇒ coreRust 的共享
 * 测试就不再与 coreKotlin 一致。本文件把那道闸门钉死。
 *
 * ## 为什么在 `src/testCoreRust` 而不是共享 `src/test`
 * [CoreImpl] / [CoreNative] 是 flavor 专有符号，共享源集一旦引用，coreKotlin 那条通道就编译不过
 * （探针结论见 `notes-tangsnow/PLAN-2026-10-15-flavor-wiring.md` §一）。
 */
class CoreImplSeamTest {

    @Test
    fun jvm上核心库不可用() {
        assertFalse("JVM 载不动 Android ELF，这里必然是降级档", CoreNative.available)
    }

    @Test
    fun jvm上W2九桥全部不接管() {
        // 书签 3：走 CoreBridge（会构造 org.json），必须被 available 闸门挡住
        assertNull(CoreImpl.bookmarkParse("<DT><A HREF=\"https://a.com/\">a</A>", 0L))
        assertNull(
            CoreImpl.bookmarkSanitize(
                listOf(BookmarkHtml.Entry("https://a.com/", "a")),
                emptySet(),
            )
        )
        assertNull(
            CoreImpl.bookmarkExport(listOf(Bookmark(id = 1L, url = "https://a.com/", title = "a", createdAt = 0L)))
        )

        // 下载文件名 4
        assertNull(CoreImpl.dlParseFileName("attachment; filename=\"a.pdf\"", null))
        assertNull(CoreImpl.dlSanitizeFileName("a/b"))
        assertNull(CoreImpl.dlIsExecutableName("a.apk"))
        assertNull(CoreImpl.dlBareMimeType("text/html; charset=utf-8"))

        // 崩溃日志 2
        assertNull(CoreImpl.crashRedactUrls("x https://a.com/1"))
        assertNull(CoreImpl.crashDisplayStamp("crash-20261001-121500-123.txt"))

        // W2 收尾 2（2026-10-20 第二批）—— guard 默认值分别是 true（fail-closed）与 null，
        // 不先问 available 的话前者会把 JVM 上的 coreRust 测试带偏，这里一并钉死。
        assertNull(CoreImpl.shouldSkipHistoryUrl("about:home"))
        assertNull(CoreImpl.legalParseBoldRanges("a**b**c"))
    }

    /**
     * **W1 的七道闸门也要钉**（2026-10-20 审查报告 C-13）。
     *
     * 此前这里只钉了 W2 的九桥，W1 的七个**全漏** —— 而这七个里有**四个**的
     * [CoreNative] 层 guard 默认值是**非 null** 的：
     * `looksLikeUri` → false、`schemeOf` → ""、`isPortableUrl` → false、
     * `searchUrlFromTemplate` → Kotlin 兜底算出的 URL、`percentEncode` → Kotlin 兜底编码。
     *
     * 后果：[CoreImpl] 若哪天不先问 `available`，JVM 上这些默认值会被当成"接管结果"返回
     * （表现为「地址栏不回显」「编码结果与 coreKotlin 不同」…），而**没有任何测试会红** ——
     * 因为默认值大多"看起来合理"。
     */
    @Test
    fun jvm上W1七桥全部不接管() {
        // 单值进出的纯字符串函数（无 JNI 结构 ⇒ 签名漂移的后果可预期）
        assertNull(CoreImpl.resolveInfo("棠雪", "https://www.bing.com/search?q={q}"))
        assertNull(CoreImpl.looksLikeUri("https://example.com"))
        assertNull(CoreImpl.schemeOf("https://example.com"))
        assertNull(CoreImpl.isPortableUrl("https://example.com"))
        assertNull(CoreImpl.percentEncode("a b"))
        assertNull(CoreImpl.searchUrl("https://www.bing.com/search?q={q}", "棠雪"))
        assertNull(
            CoreImpl.displayForUrl(
                "https://www.bing.com/search?q=1",
                "https://www.bing.com/search?q={q}",
                "q",
                "bing_cn",
            )
        )
    }
}