package io.github.tan_sno.tangsnow.util

import io.github.tan_sno.tangsnow.data.SharedFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 位图采样数学的单元测试。
 *
 * 这段逻辑决定「相册大图解码后占多少内存」，是 OOM 的第一道闸门：
 * 采样不足 → 解码出上百 MB 位图直接崩；采样过头 → 背景图糊。
 * 两条约束（不小于目标尺寸 / 不超过总像素上限）冲突时**优先保内存**。
 */
class BitmapsSampleSizeTest {

    private val fourMp = 4_000_000L

    /**
     * **采样倍数与 Rust 侧同口径**（期望值来自两侧共享的 JSON 夹具）。
     *
     * 2026-10-20 审查报告 C-14：原先这里与 Rust 侧 `bitmaps_parity.rs` **各写一份期望值**
     * —— 那是**镜像**：改实现 + 同步改本侧的镜像期望值 ⇒ 两侧照样全绿，而两边的采样口径已漂
     * （采样不足 → 解码出上百 MB 位图直接崩；采样过头 → 背景图糊）。
     * 现在两边读**同一份**夹具（`tangsnow-rs/tangsnow-core/tests/fixtures/bitmaps_sample_size_cases.json`）。
     */
    @Test
    fun `采样倍数与Rust侧同口径`() {
        val cases = SharedFixtures.cases(FIXTURE)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val id = c.getString("id")
            val a = c.getJSONArray("args")
            val w = a.getInt(0)
            val h = a.getInt(1)
            val sample = Bitmaps.requiredSampleSize(w, h, a.getInt(2), a.getInt(3), a.getLong(4))
            assertEquals("夹具用例「$id」", c.getInt("expected"), sample)
            // ⚠️ 这里**不**断言「像素上限」：退化输入（目标尺寸非正 ⇒ 直接返回 1）本来就不满足它，
            // 像素上限是**性质**（见下一条用例），不是逐点的期望值。
        }
    }

    @Test
    fun `任何输入下结果都满足像素上限且必然收敛`() {
        val cases = listOf(
            Triple(12000, 8000, 1080 to 2400),
            Triple(30000, 20000, 4000 to 4000),
            Triple(1, 1, 10 to 10),
            Triple(100000, 100000, 1 to 1),
        )
        cases.forEach { (w, h, req) ->
            val sample = Bitmaps.requiredSampleSize(w, h, req.first, req.second, fourMp)
            assertTrue("sample 必须为正", sample >= 1)
            assertTrue(
                "w=$w h=$h req=$req sample=$sample 超出像素上限",
                (w / sample).toLong() * (h / sample) <= fourMp,
            )
        }
    }

    private companion object {
        /** 两侧共享的夹具（Rust 侧读同一个文件） */
        const val FIXTURE = "bitmaps_sample_size_cases.json"
    }
}

