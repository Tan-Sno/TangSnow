package io.github.tan_sno.tangsnow.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * center-crop 矩形（F 批）。
 *
 * 这张矩形是「先裁后缩」的无拉伸依据，也是内存上界的来源：裁完的中间图不得大于源图，
 * 缩放产物恒等于目标尺寸 —— 因此**绝不允许**出现「按长边放大到 9600×2400」那种中间态。
 */
class BitmapsCoverRectTest {

    private fun rect(srcW: Int, srcH: Int, tw: Int, th: Int): IntArray =
        Bitmaps.centerCropRect(srcW, srcH, tw, th)

    /** 断言矩形落在源图内、尺寸为正、且宽高比与目标一致（±1px 取整误差） */
    private fun assertConsistent(r: IntArray, srcW: Int, srcH: Int, tw: Int, th: Int) {
        val (x, y, w, h) = listOf(r[0], r[1], r[2], r[3])
        assertTrue("x 越界: $x", x >= 0)
        assertTrue("y 越界: $y", y >= 0)
        assertTrue("w 越界: $w", w > 0 && x + w <= srcW)
        assertTrue("h 越界: $h", h > 0 && y + h <= srcH)
        // 宽高比一致：|w/h - tw/th| 的交叉相乘判据，容忍 1px 取整
        assertTrue(
            "宽高比偏离目标 ($w×$h vs $tw×$th)",
            kotlin.math.abs(w.toLong() * th - h.toLong() * tw) <= maxOf(tw, th).toLong(),
        )
    }

    @Test
    fun `宽幅源图配竖向目标：裁掉左右，不放大`() {
        // 旧实现会把 8000×500 放大到 9600×2400（≈23MP / 92MB）—— 这是本批要消灭的形态
        val r = rect(8000, 500, 1080, 2400)
        assertConsistent(r, 8000, 500, 1080, 2400)
        assertEquals(225, r[2])
        assertEquals(500, r[3])
        assertTrue("中间图不得超过源图像素数", r[2].toLong() * r[3] <= 8000L * 500)
    }

    @Test
    fun `竖幅源图配横向目标：裁掉上下`() {
        val r = rect(500, 8000, 2400, 1080)
        assertConsistent(r, 500, 8000, 2400, 1080)
        assertEquals(500, r[2])
        assertEquals(225, r[3])
    }

    @Test
    fun `方形源配竖向目标：左右各裁一半`() {
        val r = rect(1000, 1000, 1080, 2400)
        assertConsistent(r, 1000, 1000, 1080, 2400)
        assertEquals(450, r[2])
        assertEquals(1000, r[3])
        assertEquals("居中", 275, r[0])
        assertEquals(0, r[1])
    }

    @Test
    fun `宽高比恰好相等时原样返回整张`() {
        val r = rect(1080, 2400, 1080, 2400)
        assertConsistent(r, 1080, 2400, 1080, 2400)
        assertEquals(0, r[0]); assertEquals(0, r[1])
        assertEquals(1080, r[2]); assertEquals(2400, r[3])
    }

    @Test
    fun `1px 源图与极端长宽比不越界也不除零`() {
        listOf(
            rect(1, 1, 1080, 2400),
            rect(1, 4000, 1080, 2400),
            rect(4000, 1, 1080, 2400),
            rect(3, 2, 4000, 4000),
        ).forEachIndexed { i, r ->
            assertTrue("第 $i 个矩形的 w 必须为正: ${r[2]}", r[2] > 0)
            assertTrue("第 $i 个矩形的 h 必须为正: ${r[3]}", r[3] > 0)
        }
    }

    @Test
    fun `非法尺寸不崩溃且返回可用的矩形`() {
        listOf(
            rect(0, 100, 1080, 2400),
            rect(100, 0, 1080, 2400),
            rect(100, 100, 0, 2400),
            rect(100, 100, 1080, 0),
        ).forEach { r ->
            assertTrue(r[2] >= 1)
            assertTrue(r[3] >= 1)
        }
    }
}
