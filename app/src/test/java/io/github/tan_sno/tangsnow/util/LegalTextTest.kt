package io.github.tan_sno.tangsnow.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 法律文本加粗标记的解析测试。
 *
 * 这段解析直接决定**用户看到的隐私政策长什么样**：三个渲染点（阅读页、同意页兜底弹窗、
 * 关于页兜底弹窗）都走它。边界都在这里 —— 落单的标记、空标记、无标记；
 * 一旦把落单的 `**` 也吃掉，正文里真正的星号就会静默消失，而那种错误在界面上不会报错。
 */
class LegalTextTest {

    @Test
    fun `成对标记被去掉且区间正确`() {
        val m = LegalText.parse("a**bc**d")
        assertEquals("abcd", m.plain)
        assertEquals(listOf(1..2), m.boldRanges)
    }

    @Test
    fun `多处标记各自成段`() {
        val m = LegalText.parse("**甲**在中间**乙**尾")
        assertEquals("甲在中间乙尾", m.plain)
        assertEquals(listOf(0..0, 4..4), m.boldRanges)
    }

    @Test
    fun `无标记时原样返回且不加粗`() {
        val m = LegalText.parse("没有任何标记")
        assertEquals("没有任何标记", m.plain)
        assertTrue(m.boldRanges.isEmpty())
    }

    @Test
    fun `落单的标记原样保留`() {
        // 只有开标记：不能吞掉正文，也不能凭空加粗
        val m = LegalText.parse("前面**后面没有配对")
        assertEquals("前面**后面没有配对", m.plain)
        assertTrue(m.boldRanges.isEmpty())
    }

    @Test
    fun `空标记被丢弃且不产生加粗区间`() {
        val m = LegalText.parse("a****b")
        assertEquals("ab", m.plain)
        assertTrue(m.boldRanges.isEmpty())
    }

    @Test
    fun `标记在结尾时同样成对处理`() {
        val m = LegalText.parse("结尾**加粗**")
        assertEquals("结尾加粗", m.plain)
        assertEquals(listOf(2..3), m.boldRanges)
    }
}
