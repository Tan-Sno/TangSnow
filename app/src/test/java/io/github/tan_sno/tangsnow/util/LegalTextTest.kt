package io.github.tan_sno.tangsnow.util

import io.github.tan_sno.tangsnow.data.SharedFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 法律文本加粗标记的解析测试。
 *
 * 这段解析直接决定**用户看到的隐私政策长什么样**：三个渲染点（阅读页、同意页兜底弹窗、
 * 关于页兜底弹窗）都走它。边界都在这里 —— 落单的 `**`、空标记、无标记；
 * 一旦把落单的 `**` 也吃掉，正文里真正的星号就会静默消失，而那种错误在界面上不会报错。
 *
 * ## 期望值来自**两侧共享的 JSON 夹具**（2026-10-20 审查报告 C-14）
 *
 * 此前这里与 Rust 侧 `tests/legal_text_parity.rs` **各写一份期望值** —— 那是**镜像**：
 * 改实现 + 同步改本侧的镜像期望值 ⇒ 两侧照样全绿，而两边的行为已经漂了
 * （B-1 那次「Rust 按码点、Kotlin 按 UTF-16」就是这么活下来的）。
 * 现在两边读**同一份**夹具（`tangsnow-rs/tangsnow-core/tests/fixtures/legal_text_cases.json`）
 * ⇒ 改夹具必然两侧一起红。
 *
 * 区间口径：**UTF-16 码元**闭区间（与 `String.length` / `Spannable` 下标同口径）。
 */
class LegalTextTest {

    /** 共享夹具里的用例（两侧同源） */
    private val cases by lazy {
        SharedFixtures.cases("legal_text_cases.json")
    }

    @Test
    fun `夹具里的每条用例都按同一口径解析`() {
        assertTrue("夹具里一条用例都没有 —— 门禁会空转", cases.length() > 0)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val raw = c.getString("raw")
            val expectedPlain = c.getString("plain")
            val ranges = c.getJSONArray("boldRanges")
            val expected = (0 until ranges.length()).map { j ->
                val r = ranges.getJSONArray(j)
                r.getInt(0)..r.getInt(1)
            }
            val m = LegalText.parse(raw)
            assertEquals("夹具用例：$raw", expectedPlain, m.plain)
            assertEquals("夹具用例：$raw", expected, m.boldRanges)
        }
    }

    @Test
    fun `夹具本身是自洽的`() {
        // 夹具写错时（区间端点写反、区间超出正文长度）应当**当场**红，
        // 而不是等某一侧跑起来才暴露 —— 两侧都读它，坏夹具会同时毒到两边。
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val raw = c.getString("raw")
            val plain = c.getString("plain")
            val ranges = c.getJSONArray("boldRanges")
            for (j in 0 until ranges.length()) {
                val r = ranges.getJSONArray(j)
                val a = r.getInt(0)
                val b = r.getInt(1)
                assertTrue("夹具里的闭区间端点写反了：$a..$b（用例 $raw）", a <= b)
                val width = plain.length // Kotlin 的 String.length 就是 UTF-16 码元数
                assertTrue(
                    "夹具里的区间超出正文长度：$a..$b vs $width（用例 $raw）",
                    b < width,
                )
            }
        }
    }
}
