package io.github.tan_sno.tangsnow.util

import io.github.tan_sno.tangsnow.data.SearchEngine
import io.github.tan_sno.tangsnow.data.SharedFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 地址栏输入解析的单元测试。
 *
 * 这些用例锁住的是「用户敲了什么 → 打开什么」这条最基础的行为：
 * 该当网址的不能丢给搜索引擎，该当搜索词的不能被误当域名。
 * 全部为纯逻辑（不依赖 android.net.Uri），可在普通 JVM 单测中运行。
 *
 * ## 期望值来自**两侧共享的 JSON 夹具**（2026-10-20 审查报告 C-14）
 *
 * 此前这里与 Rust 侧 `tests/url_utils_parity.rs` **各写一份期望值** —— 那是**镜像**：
 * 改实现 + 同步改本侧的镜像期望值 ⇒ 两侧照样全绿，而两边的口径已经漂了
 * （B-1 那次「Rust 按码点、Kotlin 按 UTF-16」就是这么活下来的）。
 * 现在两边读**同一份**夹具（`tangsnow-rs/tangsnow-core/tests/fixtures/url_utils_cases.json`）
 * ⇒ 改夹具必然两侧一起红；只想改一侧的期望值，必须显式改夹具 —— 那一刻两侧就一起变了。
 *
 * ## 本文件负责哪几个 kind
 * `looksLikeUri` / `resolveInfo` / `isLocalNetworkAddress` / `extractUrlFromText`；
 * 另两个 kind 归 [SchemeGateTest] 与 [PercentEncodeTest]。
 * **三个文件负责的 kind 并集必须与夹具里的 kind 集合相等**（下面那条用例在钉这件事）：
 * 否则夹具里会出现「有条用例、却没有任何一个 Kotlin 文件认领」的空转 ——
 * Rust 侧会红（它的派发不认这个 kind），Kotlin 侧也必须红。
 */
class UrlUtilsTest {

    private val engine = SearchEngine(
        id = "test",
        template = "https://search.test/?q={q}",
        queryParam = "q",
    )

    @Test
    fun `夹具里的每条用例与Rust侧同口径`() {
        val cases = SharedFixtures.cases(FIXTURE)
        val seen = mutableMapOf<String, Int>()
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val kind = c.getString("kind")
            if (kind !in OWNED_KINDS) continue
            seen[kind] = (seen[kind] ?: 0) + 1
            val id = c.getString("id")
            val a = c.getJSONArray("args")
            when (kind) {
                "looksLikeUri" -> assertEquals(
                    "夹具用例「$id」",
                    c.getBoolean("expected"),
                    UrlUtils.looksLikeUri(a.getString(0)),
                )
                "resolveInfo" -> {
                    val got = UrlUtils.resolveInfo(a.getString(0), engine)
                    if (c.isNull("expected")) {
                        assertNull("夹具用例「$id」", got)
                    } else {
                        val want = c.getJSONObject("expected")
                        assertNotNull("夹具用例「$id」", got)
                        assertEquals("夹具用例「$id」", want.getString("url"), got!!.url)
                        assertEquals("夹具用例「$id」", want.getBoolean("isSearch"), got.isSearch)
                    }
                }
                "isLocalNetworkAddress" -> assertEquals(
                    "夹具用例「$id」",
                    c.getBoolean("expected"),
                    UrlUtils.isLocalNetworkAddress(a.getString(0)),
                )
                "extractUrlFromText" -> {
                    val got = UrlUtils.extractUrlFromText(a.getString(0))
                    if (c.isNull("expected")) {
                        assertNull("夹具用例「$id」", got)
                    } else {
                        assertEquals("夹具用例「$id」", c.getString("expected"), got)
                    }
                }
                else -> throw AssertionError("夹具里的 kind「$kind」本文件不认（改夹具要同步两侧的派发）")
            }
        }
        for (k in OWNED_KINDS) {
            assertTrue("kind「$k」在夹具里一条用例都没有 ⇒ 门禁空转", (seen[k] ?: 0) > 0)
        }
    }

    @Test
    fun `夹具里的kind全部有归属`() {
        val cases = SharedFixtures.cases(FIXTURE)
        val kinds = (0 until cases.length())
            .map { cases.getJSONObject(it).getString("kind") }
            .toSet()
        val owned = UrlUtilsTest.OWNED_KINDS + SchemeGateTest.OWNED_KINDS + PercentEncodeTest.OWNED_KINDS
        assertEquals(
            "夹具里的 kind 集合与三个 Kotlin 文件负责的 kind 并集不一致 —— " +
                "新增 kind 必须有人认领，否则那条用例在 Kotlin 侧会静默空转（Rust 侧会红，这里也得红）",
            owned,
            kinds,
        )
    }

    @Test
    fun `resolve 就是 resolveInfo 的 url`() {
        // 这是**同一侧两个函数之间的关系**，不是跨语言的期望值 ⇒ 不进共享夹具
        // （进了就得两侧各写一遍，又变回镜像）。夹具里的 resolveInfo 已钉住两者的输入。
        for (input in listOf(
            "https://example.com/x",
            "example.com",
            "localhost:8080",
            "棠雪 浏览器",
            "",
            "   ",
        )) {
            assertEquals(
                "resolve 必须就是 resolveInfo 的 url（输入：$input）",
                UrlUtils.resolveInfo(input, engine)?.url,
                UrlUtils.resolve(input, engine),
            )
        }
    }

    companion object {
        /** 两侧共享的夹具（Rust 侧读同一个文件） */
        const val FIXTURE = "url_utils_cases.json"

        /** 本文件负责的 kind（其余归兄弟文件；并集必须与夹具里的 kind 集合相等） */
        val OWNED_KINDS: Set<String> = setOf(
            "looksLikeUri",
            "resolveInfo",
            "isLocalNetworkAddress",
            "extractUrlFromText",
        )
    }
}
