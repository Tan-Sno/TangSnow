package io.github.tan_sno.tangsnow.util

import io.github.tan_sno.tangsnow.data.SharedFixtures
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * center-crop 矩形（F 批）。
 *
 * 这张矩形是「先裁后缩」的无拉伸依据，也是内存上界的来源：裁完的中间图不得大于源图，
 * 缩放产物恒等于目标尺寸 —— 因此**绝不允许**出现「按长边放大到 9600×2400」那种中间态。
 *
 * 期望值来自**两侧共享的 JSON 夹具**
 * （`tangsnow-rs/tangsnow-core/tests/fixtures/bitmaps_cover_rect_cases.json`），
 * Rust 侧 `tests/bitmaps_parity.rs` 读同一个文件。
 * 此前两侧各写一份期望值 —— 那是**镜像**：改实现 + 同步改本侧的镜像期望值 ⇒
 * 两侧照样全绿，而两边的裁剪口径已经漂了
 * （矩形偏几个像素 ⇒ 缩放后宽高比与目标不符 ⇒ 背景被拉伸）。
 */
class BitmapsCoverRectTest {

    /** 裁剪结果 `[x, y, w, h]` */
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
        // 中间图不得大于源图：先裁后缩的前提 —— 越界就意味着这一步偷偷放大了（会爆内存）
        assertTrue(
            "中间图大于源图 ($w×$h vs $srcW×$srcH)",
            w.toLong() * h <= srcW.toLong() * srcH,
        )
    }

    /**
     * **裁剪矩形与 Rust 侧同口径**（期望值来自两侧共享的 JSON 夹具）。
     *
     * 改口径只能改夹具 —— 那一刻两侧一起变；只改一侧的期望值这条路已经堵死。
     */
    @Test
    fun `裁剪矩形与Rust侧同口径`() {
        val cases = SharedFixtures.cases(FIXTURE)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val id = c.getString("id")
            val d = dims(c)
            val r = rect(d[0], d[1], d[2], d[3])
            val e = c.getJSONArray("expected")
            assertEquals("夹具用例「$id」的 x", e.getInt(0), r[0])
            assertEquals("夹具用例「$id」的 y", e.getInt(1), r[1])
            assertEquals("夹具用例「$id」的 w", e.getInt(2), r[2])
            assertEquals("夹具用例「$id」的 h", e.getInt(3), r[3])
        }
    }

    /**
     * **合法输入下矩形落在源图内、宽高比与目标一致**。
     *
     * 性质（而不是逐点的期望值）：它对**任何**合法输入都成立，而夹具只能钉住有限的几个点。
     * 从夹具里筛出合法输入 ⇒ 往夹具里加用例时，这一侧的性质覆盖面同步变大。
     */
    @Test
    fun `合法输入下矩形落在源图内且宽高比与目标一致`() {
        var checked = 0
        val cases = SharedFixtures.cases(FIXTURE)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val d = dims(c)
            // 退化输入走的是守卫分支（压根不裁），不满足「宽高比与目标一致」这个性质
            if (d.any { it <= 0 }) continue
            val r = rect(d[0], d[1], d[2], d[3])
            assertConsistent(r, d[0], d[1], d[2], d[3])
            checked++
        }
        assertTrue("一条合法输入都没有 ⇒ 性质断言空转", checked > 0)
    }

    /**
     * **退化输入也要给出可用的矩形**。
     *
     * 守卫分支不是「返回零尺寸」而是「返回一个至少 1px 的矩形」—— 调用方拿它去裁，
     * 零尺寸会让 `Bitmap.createBitmap` 直接抛异常。
     */
    @Test
    fun `退化输入返回可用的矩形`() {
        var checked = 0
        val cases = SharedFixtures.cases(FIXTURE)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val d = dims(c)
            if (d.all { it > 0 }) continue
            val r = rect(d[0], d[1], d[2], d[3])
            assertTrue(
                "夹具用例「${c.getString("id")}」：退化输入也要给出可用的矩形",
                r[2] >= 1 && r[3] >= 1,
            )
            checked++
        }
        assertTrue("一条退化输入都没有 ⇒ 守卫分支没人盯", checked > 0)
    }

    /** 夹具用例的四个尺寸入参 `[srcW, srcH, targetW, targetH]` */
    private fun dims(c: JSONObject): IntArray {
        val a = c.getJSONArray("args")
        return intArrayOf(a.getInt(0), a.getInt(1), a.getInt(2), a.getInt(3))
    }

    private companion object {
        /** 两侧共享的夹具（Rust 侧读同一个文件） */
        const val FIXTURE = "bitmaps_cover_rect_cases.json"
    }
}
