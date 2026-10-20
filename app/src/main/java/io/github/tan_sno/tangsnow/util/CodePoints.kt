package io.github.tan_sno.tangsnow.util

/**
 * 码点（code point）粒度的字符串截取。
 *
 * ## 为什么需要它
 *
 * Kotlin 的 `String` 是 **UTF-16 码元**序列，而 `String.take(n)` 取的是前 n 个**码元**：
 * n 恰好落在代理对中间时，它会把一个星平面字符（emoji、生僻字）**劈成两半**，
 * 落库一个**孤立代理**（渲染为替换字符 `�`）。Rust 侧 `str::chars()` 迭代的是**码点**，
 * 永远不会劈开 ⇒ 两侧对同一条输入会给出**不同**的结果。
 *
 * 本文件把「按码点截取」这件事收成一处，供两侧对齐使用：
 *  - Kotlin：[takeCodePoints] ≡ Rust `s.chars().take(n).collect::<String>()`
 *  - Rust：`tangsnow-core/src/bookmark_html.rs` 的 `e.title.chars().take(MAX_TITLE_LENGTH)`
 *
 * ⚠️ 两侧的**上限常量也要同源**（`MAX_TITLE_LENGTH`）：本仓的跨语言比对
 * （`app/src/test/.../CrossLanguageLiteralParityTest`）只比字面量集合，
 * **不比截断语义** ⇒ 语义漂移不会被它发现。这条口径靠本文件的注释 + 两边的
 * `*_parity` 用例共同钉住。
 */
internal object CodePoints {

    /**
     * 取前 [n] 个**码点**（绝不劈开代理对）。
     *
     * @param n 码点数上限；`<= 0` 返回空串
     */
    fun takeCodePoints(s: String, n: Int): String {
        if (n <= 0) return ""
        var i = 0
        var count = 0
        while (i < s.length) {
            if (count == n) break
            i += Character.charCount(s.codePointAt(i))
            count++
        }
        return s.substring(0, i)
    }

    /** 码点数（≠ `String.length` 的 UTF-16 码元数）。 */
    fun countCodePoints(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            i += Character.charCount(s.codePointAt(i))
            n++
        }
        return n
    }
}
