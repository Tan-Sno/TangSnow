package io.github.tan_sno.tangsnow.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 「LIMIT」入参守卫的边界测试。
 *
 * ## 为什么值得测
 * [BrowserDb.trimHistoryTo] / [BrowserDb.recentHistory] 把上限**直接插进 SQL**
 * （值是 `Int`，不构成注入），但 `LIMIT 0` 会让子查询返回空集 ⇒ `NOT IN (∅)` 对每一行都为真
 * ⇒ **静默清空整张历史表**，而调用方看不到任何异常。这两个方法都是 `public` 且原本无守卫，
 * 一个 `0` 或负数就够了。
 *
 * `BrowserDb` 本身要 Android `Context`（`SQLiteOpenHelper`）故无法在 JVM 单测里直接跑，
 * 守卫本身是纯函数，钉住它就等于钉住「上层拿不到 0/负数」这个前提。
 */
class BrowserDbLimitGuardTest {

    @Test
    fun `正整数原样通过`() {
        assertEquals(200, BrowserDb.positiveLimit(200, "keep"))
        assertEquals(1, BrowserDb.positiveLimit(1, "limit"))
    }

    @Test
    fun `零与负数一律拒绝`() {
        // 0 ⇒ NOT IN (空集) ⇒ 清空整表；负数 ⇒ SQLite 视作「不限」⇒ 返回全表 / 不裁剪
        for (bad in intArrayOf(0, -1, -200, Int.MIN_VALUE)) {
            assertThrows(
                "keep=$bad 未被拒绝",
                IllegalArgumentException::class.java,
            ) { BrowserDb.positiveLimit(bad, "keep") }
            assertThrows(
                "limit=$bad 未被拒绝",
                IllegalArgumentException::class.java,
            ) { BrowserDb.positiveLimit(bad, "limit") }
        }
    }

    @Test
    fun `报错文案点明后果，便于排障`() {
        val msg = assertThrows(
            IllegalArgumentException::class.java,
        ) { BrowserDb.positiveLimit(0, "keep") }.message.orEmpty()
        // 静默清空整表是最需要被看见的那种失败，文案必须自己解释它
        assertEquals(true, msg.contains("keep"))
        assertEquals(true, msg.contains("整表"))
    }
}
