package io.github.tan_sno.tangsnow.data

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索引擎引擎表的单元测试。
 *
 * 覆盖两处历史缺陷：
 *  - [SearchEngines.find] 的兜底：常量与内置列表不一致时不能让启动路径抛异常；
 *  - [SearchEngines.hostMatches] 的主机别名：在 `www.bing.com` 上搜索时，
 *    地址栏要显示关键词而不是整条 URL。
 *
 * ## 期望值来自**两侧共享的 JSON 夹具**（2026-10-20 审查报告 C-14）
 *
 * 引擎查找 / 主机判定 / 取主机三组原先与 Rust 侧 `search_engines_parity.rs`
 * **各写一份期望值** —— 那是**镜像**：改实现 + 同步改本侧的镜像期望值 ⇒ 两侧照样全绿，
 * 而两边的口径已经漂了（2026-10-16 的别名表漏行"bing"就是这么漏掉的）。
 * 现在两边读**同一份**夹具（`tangsnow-rs/tangsnow-core/tests/fixtures/search_engines_cases.json`）。
 *
 * 内置引擎**模板表**不在夹具内 —— 它是一张表，由 `SearchEngineTableParityTest` 并排比对两侧源码。
 */
class SearchEnginesTest {

    /**
     * **夹具里的每条用例与 Rust 侧同口径**。
     *
     * kind ∈ find / hostMatches / templateHostOf；args 按 kind 顺序；expected 为 null 表示「无结果」。
     */
    @Test
    fun `夹具里的每条用例与Rust侧同口径`() {
        val cases = SharedFixtures.cases(FIXTURE)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val id = c.getString("id")
            val kind = c.getString("kind")
            val a = c.getJSONArray("args")
            when (kind) {
                "find" -> {
                    // args[0] = 引擎 id（null = 空 id）；args[0] == "__empty_list__" 时
                    // 用**空列表**找（args[1] 才是要找的 id）
                    val empty = !a.isNull(0) && a.getString(0) == EMPTY_MARKER
                    val list = if (empty) emptyList() else SearchEngines.builtins
                    val want: String? = when {
                        empty -> a.getString(1)
                        a.isNull(0) -> null
                        else -> a.getString(0)
                    }
                    assertEquals(
                        "夹具用例「$id」",
                        c.getString("expected"),
                        SearchEngines.find(list, want).id,
                    )
                }
                "hostMatches" -> {
                    val got = SearchEngines.hostMatches(
                        a.getString(0),
                        a.getString(1),
                        a.getString(2),
                    )
                    assertEquals("夹具用例「$id」", c.getBoolean("expected"), got)
                }
                "templateHostOf" -> {
                    val got = SearchEngines.templateHostOf(a.getString(0))
                    if (c.isNull("expected")) {
                        assertEquals("夹具用例「$id」", null, got)
                    } else {
                        assertEquals("夹具用例「$id」", c.getString("expected"), got)
                    }
                }
                else -> throw AssertionError("夹具里的 kind「$kind」本文件不认（改夹具要同步两侧的派发）")
            }
        }
    }

    @Test
    fun `默认引擎必须存在于内置列表且带查询参数名`() {
        val default = SearchEngines.builtins.first { it.id == SearchEngines.DEFAULT_ID }
        assertTrue("默认引擎必须带查询参数名", !default.queryParam.isNullOrBlank())
    }

    private companion object {
        /** 两侧共享的夹具（Rust 侧读同一个文件） */
        const val FIXTURE = "search_engines_cases.json"

        /** 夹具里表示「用空列表找」的标记（Rust 侧同名） */
        const val EMPTY_MARKER = "__empty_list__"
    }
}
