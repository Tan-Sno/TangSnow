package io.github.tan_sno.tangsnow.util

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

    @Test
    fun `尺寸远大于目标时按 2 的幂降采样`() {
        // 4000x3000（12MP）→ 目标 1000x1500：可降到 2000x1500（仍不小于目标）
        // 再降一级 1000x750 会小于 1500 高度，故停在 2
        assertEquals(2, Bitmaps.requiredSampleSize(4000, 3000, 1000, 1500, fourMp))
    }

    @Test
    fun `小图不采样`() {
        assertEquals(1, Bitmaps.requiredSampleSize(800, 600, 1080, 1920, fourMp))
    }

    @Test
    fun `内存闸门优先于清晰度`() {
        // 12000x8000 = 96MP。按尺寸只需 sample=2（高度 4000 已不小于 2400），
        // 但 6000x4000 = 24MP 远超上限，必须继续提到 8（1500x1000 = 1.5MP）
        val sample = Bitmaps.requiredSampleSize(12000, 8000, 1080, 2400, fourMp)
        assertEquals(8, sample)
        assertTrue((12000 / sample).toLong() * (8000 / sample) <= fourMp)
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

    @Test
    fun `非法尺寸返回 1 而不是死循环`() {
        assertEquals(1, Bitmaps.requiredSampleSize(0, 0, 100, 100, fourMp))
        assertEquals(1, Bitmaps.requiredSampleSize(-5, 100, 100, 100, fourMp))
    }

    @Test
    fun `上限极大时退化为纯按尺寸采样`() {
        assertEquals(4, Bitmaps.requiredSampleSize(4096, 4096, 1024, 1024, Long.MAX_VALUE))
    }
}
