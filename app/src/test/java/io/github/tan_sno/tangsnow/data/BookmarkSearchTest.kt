package io.github.tan_sno.tangsnow.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 书签搜索「SQL 预筛 + 应用侧精确过滤」的边界测试
 * （2026-10-20 审查报告 A-3）。
 *
 * ## 为什么值得测
 * 这套设计整条压在**一个不变式**上：
 *
 * > 若应用侧判定为真匹配（`url.lowercase().contains(kw)`），则该行**一定**能被预筛模式选中。
 *
 * 不变式破了 = 预筛把真匹配挡在门外 = 中文联想「搜不到已有的书签」，
 * 而且**没有任何异常**、只是结果变少 ⇒ 极难在真机上发现。
 * 另一半（预筛可以多给候选、精确判定负责收口）由 [BookmarkSearch.matches] 保证，
 * 两条合起来才是「语义与旧的内存过滤逐字一致」。
 *
 * ## 为什么能在 JVM 里测
 * 预筛模式与精确判定都是**纯函数**（不碰 Android、不碰 SQLite），
 * 故这里用一份「SQLite LIKE 语义」的最小实现当判据：
 * ASCII 大小写不敏感、`_` 恰好一个码点、`%` 任意序列、`\` 转义。
 * 判据按**码点**推进（SQLite 的 `_`/字面量都以 UTF-8 码点为单位）——
 * 语料含星平面字符（🔒），按 UTF-16 码元自说自话会把它钉成假绿
 * （2026-10-24 复核：旧判据恰好掩盖了实现按 Char 遍历生成孤立代理的漏召回）。
 */
class BookmarkSearchTest {

    @Test
    fun `纯ASCII与非ASCII分流`() {
        assertTrue(BookmarkSearch.isAsciiOnly("wiki"))
        assertTrue(BookmarkSearch.isAsciiOnly(""))
        assertFalse(BookmarkSearch.isAsciiOnly("咖啡"))
        assertFalse(BookmarkSearch.isAsciiOnly("café"))
    }

    @Test
    fun `无大小写概念的字符按字面量精确匹配`() {
        // CJK 没有大小写变体 ⇒ 字面量即精确匹配（这正是中文联想走 SQL 的前提）
        assertEquals("%咖啡%", BookmarkSearch.prefilterPattern("咖啡"))
        assertEquals("%北京%", BookmarkSearch.prefilterPattern("北京"))
    }

    @Test
    fun `有大小写变体的字符用通配兜底`() {
        // 'é' 的原文可能是 'É' ⇒ 字面量会漏 ⇒ 用 '_'（恰好一个字符）兜底
        assertEquals("%caf_%", BookmarkSearch.prefilterPattern("café"))
        // 希腊 sigma：'σ' / 'ς' / 'Σ' 三个形态
        assertEquals("%_%", BookmarkSearch.prefilterPattern("σ"))
        // 'ß' 的原文可能是 'ẞ'
        assertEquals("%stra_e%", BookmarkSearch.prefilterPattern("straße"))
    }

    @Test
    fun `可能被非ASCII原文折叠出来的i与k用通配兜底`() {
        // 'i' 可能是 'İ'(U+0130) 折叠出来的、'k' 可能是 'K'(U+212A) 折叠出来的
        // ⇒ 这两个 ASCII 字母在原文里未必是 ASCII，字面量会漏
        assertEquals("%_%", BookmarkSearch.prefilterPattern("i"))
        assertEquals("%_%", BookmarkSearch.prefilterPattern("k"))
        assertEquals("%w___%", BookmarkSearch.prefilterPattern("wiki"))
    }

    @Test
    fun `LIKE元字符按字面量转义`() {
        // 用户输入的 % / _ / \ 不能被当成通配符（与 searchBookmarks 的 ESCAPE 口径一致）
        assertEquals("%50\\%%", BookmarkSearch.prefilterPattern("50%"))
        assertEquals("%a\\_b%", BookmarkSearch.prefilterPattern("a_b"))
        assertEquals("%a\\\\b%", BookmarkSearch.prefilterPattern("a\\b"))
    }

    @Test
    fun `含组合上点的关键词放弃预筛`() {
        // 'İ'(U+0130) 折叠后是 'i' + U+0307（两个码点）⇒ 原文区域可能比关键词短一个字符，
        // LIKE 的 '_'（恰好一个字符）对不上 ⇒ 预筛会漏真匹配 ⇒ 这种情况直接放弃预筛。
        assertNull(BookmarkSearch.prefilterPattern("i̇"))
        assertNull(BookmarkSearch.prefilterPattern("İ".lowercase()))
    }

    @Test
    fun `模式宽度恒等于关键词码点数`() {
        // '_' 恰好匹配一个码点 ⇒ 模式里「要匹配一个码点」的项数必须等于关键词的码点数
        //（SQLite LIKE 按 UTF-8 码点计数；用 UTF-16 码元数会把星平面关键词钉成假绿）。
        // ⚠️ 不能直接比**模式串长度**：转义（`\%` / `\_` / `\\`）会让模式多出反斜杠。
        for (kw in CORPUS + KEYWORDS) {
            val pattern = BookmarkSearch.prefilterPattern(kw.lowercase()) ?: continue
            assertEquals(
                "关键词「$kw」的模式宽度对不上：$pattern",
                kw.codePointCount(0, kw.length),
                matchedWidth(pattern),
            )
        }
    }

    @Test
    fun `增补平面关键词用通配兜底而非孤立代理字面量`() {
        // 星平面字符在 UTF-16 里是代理对：按 Char 遍历会把两个孤立代理当字面量拼进模式，
        // 经 JNI 转码后与库中合法 UTF-8 永不相等 ⇒ 预筛恒不命中（2026-10-24 修复）。
        assertEquals("%_%", BookmarkSearch.prefilterPattern("🔒"))
        // 'k' 仍按 i/k 兜底通配（它可能来自 K(U+212A) 的折叠），只有星平面那一位是本用例重点
        assertEquals("%_loc_%", BookmarkSearch.prefilterPattern("🔒lock"))
        // BMP 的 ★ 无大小写概念，仍是字面量；只有星平面那一位通配
        assertEquals("%_★%", BookmarkSearch.prefilterPattern("🔒★"))
    }

    @Test
    fun `应用侧精确判定与旧的内存过滤一致`() {
        // 旧实现：url.lowercase().contains(kw) || title.lowercase().contains(kw)
        assertTrue(BookmarkSearch.matches("https://café.example", "", "café"))
        assertTrue(BookmarkSearch.matches("https://x.example", "CAFÉ Store", "café"))
        assertTrue(BookmarkSearch.matches("https://WIKI.example", "", "wiki"))
        assertFalse(BookmarkSearch.matches("https://x.example", "Coffee", "cafe"))
        // 空标题不应误伤
        assertFalse(BookmarkSearch.matches("https://x.example", "", "cafe"))
    }

    @Test
    fun `预筛是精确匹配集合的超集`() {
        // ★ 本测试文件的核心不变式：真匹配一个都不能被预筛漏掉
        var checked = 0
        for (kw in KEYWORDS) {
            val kwl = kw.lowercase()
            val pattern = BookmarkSearch.prefilterPattern(kwl) ?: continue
            for (s in CORPUS) {
                if (!BookmarkSearch.matches(s, s, kwl)) continue
                checked++
                assertTrue(
                    "预筛漏掉了真匹配：原文「$s」/ 关键词「$kw」/ 模式「$pattern」",
                    likeMatch(s, pattern),
                )
            }
        }
        assertTrue("语料与关键词的组合没有产生任何真匹配（断言本身失效了）", checked > 0)
    }

    @Test
    fun `预筛不会把不可能的匹配也放进来`() {
        // 反向抽查：预筛是「粗筛」，但对 CJK 这种无大小写概念的字符应当**就是精确**的
        val pattern = BookmarkSearch.prefilterPattern("咖啡")!!
        assertTrue(likeMatch("https://x.example/咖啡店", pattern))
        assertFalse(likeMatch("https://x.example/咖喱店", pattern))
        assertFalse(likeMatch("https://x.example/coffee", pattern))
    }

    // ------------------------------------------------------------------ 语料

    private companion object {
        /** 原文语料：覆盖 ASCII / CJK / 变音拉丁 / 土耳其 / 希腊 / Kelvin / ẞ / 组合字符 */
        val CORPUS = listOf(
            "wikipedia", "WIKIPEDIA", "iphone", "IPHONE", "cafe", "CAFE",
            "咖啡", "北京", "搜索引擎", "中文书签", "棠雪TangSnow",
            "café", "CAFÉ", "naïve", "NAÏVE", "über", "ÜBER", "niño",
            "İstanbul", "istanbul", "İ", "i̇", "ı",
            "ΣΊΣΥΦΟΣ", "σύν", "ς", "Σ", "σ",
            "Ångström", "K", "k", "straße", "STRAẞE", "ẞ", "ß",
            "ЖУК", "жук", "שלום", "مرحبا", "🔒lock", "★☆",
            "aİb", "İa", "aİ", "xẞy", "Éİ", "İÉ",
        )

        /** 关键词语料：同上一组的关键切片 */
        val KEYWORDS = listOf(
            "i", "k", "ai", "ak", "ki", "ik", "a", "café", "cafe", "CAFÉ",
            "咖啡", "北", "中文", "naïve", "über", "niño", "Σ", "σ", "ς", "σύ",
            "Å", "ång", "ß", "ẞ", "straße", "Ж", "ж", "שלום", "مرحبا",
            "🔒", "★", "É", "aİ", "wikipedia", "WIKI", "lock", "TangSnow", "棠雪",
        )
    }

    // ------------------------------------------------------------------ LIKE 判据

    /**
     * 模式里「要匹配一个字符」的位数（去掉转义反斜杠、去掉两端的通配 `%`）。
     *
     * 转义是**字面量**（`\%` 匹配一个 `%` 字符），所以它占一位而不是两位。
     */
    private fun matchedWidth(pattern: String): Int {
        var i = 0
        var n = 0
        while (i < pattern.length) {
            if (pattern[i] == '\\') {
                i += 2 // 反斜杠 + 被转义的字面字符 = 匹配一个字符
                n += 1
            } else {
                i += 1
                n += 1
            }
        }
        return n - 2 // 去掉两端的 '%'（它们是「任意长度」通配，不占位）
    }

    /**
     * SQLite `LIKE` 语义的最小实现（ASCII 大小写不敏感、`_` 一个码点、`%` 任意序列、`\` 转义）。
     *
     * 只用于本测试当判据：真机上跑的是 SQLite，这里要的是**同一套**语义，
     * 否则断言就成了自说自话。文本按**码点**推进（SQLite LIKE 以 UTF-8 码点为单位），
     * 模式里的字面量只会是 BMP（实现把星平面一律写成 `_`）⇒ 模式侧按 Char 推进即可。
     */
    private fun likeMatch(text: String, pattern: String): Boolean =
        likeMatch(text, 0, pattern, 0)

    private fun likeMatch(text: String, ti: Int, pattern: String, pi: Int): Boolean {
        var t = ti
        var p = pi
        while (p < pattern.length) {
            when (val pc = pattern[p]) {
                '\\' -> {
                    if (p + 1 >= pattern.length) return false
                    if (t >= text.length) return false
                    val tc = text.codePointAt(t)
                    if (!codePointEqualsFold(tc, pattern[p + 1].code)) return false
                    p += 2
                    t += Character.charCount(tc)
                }
                '%' -> {
                    var k = t
                    while (true) {
                        if (likeMatch(text, k, pattern, p + 1)) return true
                        if (k >= text.length) break
                        k += Character.charCount(text.codePointAt(k))
                    }
                    return false
                }
                '_' -> {
                    if (t >= text.length) return false
                    t += Character.charCount(text.codePointAt(t))
                    p += 1
                }
                else -> {
                    if (t >= text.length) return false
                    val tc = text.codePointAt(t)
                    if (!codePointEqualsFold(tc, pc.code)) return false
                    p += 1
                    t += Character.charCount(tc)
                }
            }
        }
        return t == text.length
    }

    /** ASCII 大小写不敏感比较（SQLite 的 `LIKE` 只折叠 ASCII，其余字节精确） */
    private fun codePointEqualsFold(a: Int, b: Int): Boolean =
        a == b || (a in 'A'.code..'Z'.code && a + 32 == b) || (b in 'A'.code..'Z'.code && b + 32 == a)
}
