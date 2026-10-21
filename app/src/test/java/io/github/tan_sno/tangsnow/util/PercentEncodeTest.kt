package io.github.tan_sno.tangsnow.util

import io.github.tan_sno.tangsnow.data.SharedFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 百分号编码的单元测试。
 *
 * 重点：编码结果必须与 Android `Uri.encode` **逐字节一致** —— 这条改造只是把框架调用
 * 换成同语义的纯函数（为了可单测），绝不能顺带改变任何线上搜索 URL。
 *
 * 搜索 URL 的**模板**行为不在本文件：它归 `search_engines` 那一对（见文件末尾的说明）。
 *
 * ## 期望值来自**两侧共享的 JSON 夹具**（2026-10-20 审查报告 C-14）
 *
 * 编码的逐点期望值原先与 Rust 侧 `tests/url_utils_parity.rs` **各写一份** —— 那是**镜像**：
 * 改实现 + 同步改本侧的镜像期望值 ⇒ 两侧照样全绿，而两边的口径已经漂了
 * （B-1 那次「Rust 按码点、Kotlin 按 UTF-16」就是这么活下来的）。
 * 现在两边读**同一份**夹具（`tangsnow-rs/tangsnow-core/tests/fixtures/url_utils_cases.json`）
 * ⇒ 改夹具必然两侧一起红。
 *
 * ## 本文件负责哪几个 kind
 * `percentEncode`；另几个 kind 归 [UrlUtilsTest] 与 [SchemeGateTest]。
 */
class PercentEncodeTest {

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
                "percentEncode" -> assertEquals(
                    "夹具用例「$id」",
                    c.getString("expected"),
                    percentEncode(a.getString(0)),
                )
                else -> throw AssertionError("夹具里的 kind「$kind」本文件不认（改夹具要同步两侧的派发）")
            }
        }
        for (k in OWNED_KINDS) {
            assertTrue("kind「$k」在夹具里一条用例都没有 ⇒ 门禁空转", (seen[k] ?: 0) > 0)
        }
    }

    @Test
    fun `一侧独有：孤立代理项按替换字符编码`() {
        // Kotlin 的 toByteArray(UTF_8) 把**孤立代理项**换成 '?'（0x3F）⇒ 编成 %3F；
        // Rust 的 str 装不下孤立代理项 ⇒ 这条语料在 Rust 侧根本无法表达。
        // 故它**不进共享夹具**（只有两侧同口径的语料才进夹具），留在本侧钉住现状。
        assertEquals("%3F", percentEncode("\uD800"))
    }

    // ------------------------------------------------------------- 搜索模板
    //
    // ⚠️ 搜索引擎**模板**的行为（占位符替换 / 追加 q 参数 / 空模板兜底）原先在本文件里
    // 各钉一份期望值，而 Rust 侧 `tests/search_engines_parity.rs` 也钉了一份 ——
    // 两侧实现逐行等价，却各自钉一份 ⇒ 那正是**镜像**（改实现 + 同步改镜像期望值，
    // 两侧照样全绿而口径已漂）。
    // 现已并入 `search_engines_cases.json` 的 `searchUrl` 一组，由 [SearchEnginesTest] 读。

    companion object {
        /** 两侧共享的夹具（Rust 侧读同一个文件） */
        const val FIXTURE = "url_utils_cases.json"

        /** 本文件负责的 kind（其余归兄弟文件；并集必须与夹具里的 kind 集合相等） */
        val OWNED_KINDS: Set<String> = setOf("percentEncode")
    }
}
