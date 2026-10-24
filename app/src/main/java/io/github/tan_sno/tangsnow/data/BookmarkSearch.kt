package io.github.tan_sno.tangsnow.data

/**
 * 书签模糊匹配的**关键词分析**（纯函数，无 Android 依赖 ⇒ 可 JVM 单测）。
 *
 * ## 要解决的问题（2026-10-20 审查报告 A-3）
 *
 *  - SQLite 的 `lower()` / `LIKE` **只折叠 ASCII**（实测 `lower('CAFÉ')='CAFÉ'`），
 *    所以含非 ASCII 的关键词**不能**下推给 SQL 做**精确**匹配 ——
 *    直接下推会把「CAFÉ Store」从 café 的联想里整个丢掉。
 *  - 完全退回「全表读进内存再过滤」也不对：书签表**没有总量上限**
 *    （`MAX_IMPORT_COUNT` 只是*单次*导入上限，多次导入会累积），
 *    每敲一个字就把整表读成对象、逐行做两次 `lowercase()` ⇒ 代价随表大小线性增长。
 *
 * ## 现在的做法：SQL 侧**预筛** + 应用侧**精确过滤**
 *
 *  - [prefilterPattern] 生成一个 LIKE 模式，它是「精确匹配集合」的**超集**
 *    （只多给候选、不少给候选 ⇒ 不漏召回）；
 *  - [matches] 做应用侧精确判定，与旧的内存过滤**逐字一致**（同一个
 *    `lowercase().contains`）⇒ 语义不变；
 *  - 粗筛在 SQLite 原生侧完成，只有**候选行**才付 `lowercase()` 的钱。
 *
 * ## 预筛为什么可以只做「粗筛」
 *
 * SQLite 的 `LIKE` 对 ASCII 大小写不敏感、对非 ASCII **字节精确**。于是：
 *  - **无大小写概念的字符**（CJK、希伯来、阿拉伯、泰文…）⇒ 字面量即精确匹配；
 *  - **有大小写变体的字符**（é/É、σ/Σ/ς、ß/ẞ…）⇒ 原文可能是大写形态，
 *    字面量会漏 ⇒ 用 `_`（恰好一个字符）通配兜底。
 *
 * 两处**必须**通配而不能字面量的 ASCII 字母：
 *  - `i`：原文可能是 `İ`(U+0130)，Java 折叠后是 `i` + U+0307；
 *  - `k`：原文可能是 `K`(U+212A)，Java 折叠后是 `k`。
 *
 * 增补平面字符（emoji、扩展汉字等，UTF-16 代理对）与未配对的孤立代理也一律 `_`：
 * 按 Char 遍历会把孤立代理当字面量，其经 JNI 转码的字节与库中合法 UTF-8 永不相等。
 */
object BookmarkSearch {

    /**
     * 关键词是否**只含 ASCII**。
     *
     * 只含 ASCII 时可直接用 SQLite 的 `lower() + LIKE` 做**精确**匹配
     * （见 [BrowserDb.searchBookmarks]）；含非 ASCII 时走预筛路径。
     */
    fun isAsciiOnly(keyword: String): Boolean = keyword.all { it.code < 128 }

    /**
     * 应用侧**精确**判定：url 或 title 包含关键词（不区分大小写）。
     *
     * 与旧的「全表读进内存再 `filter`」**逐字一致**（同一个 `lowercase().contains`），
     * 是「预筛不漏召回」这件事的另一半保证。
     */
    fun matches(url: String, title: String, keyword: String): Boolean =
        url.lowercase().contains(keyword) || title.lowercase().contains(keyword)

    /**
     * 构造预筛用的 LIKE 模式（**超集**，不漏召回）。
     *
     * @return 模式字符串；`null` = 该关键词**无法安全预筛**，
     *   调用方须退回「全表扫描 + 应用侧精确过滤」（语义不丢，代价退回旧水平）。
     */
    fun prefilterPattern(keyword: String): String? {
        // `İ`(U+0130) 经 Java 大小写折叠会展开成**两个**码点（`i` + U+0307），
        // 于是「原文区域长度」可能比关键词长度少 1 ⇒ LIKE 的 `_`（恰好一个字符）对不上，
        // 预筛就会**漏掉**真匹配。含组合上点的关键词一律放弃预筛。
        if (keyword.indexOf(COMBINING_DOT_ABOVE) >= 0) return null

        val sb = StringBuilder(keyword.length + 2)
        sb.append('%')
        // **按码点**遍历：增补平面字符（emoji、扩展汉字等）在 UTF-16 里是代理对，按 Char
        // 遍历会把两个孤立代理当字面量拼进 LIKE 模式 —— 孤立代理经 JNI 转码后的字节与
        // 库中合法 UTF-8 永不相等 ⇒ 预筛恒不命中、真匹配被静默漏掉（2026-10-24 复核）。
        // 码点 > 0xFFFF 与未配对的孤立代理一律 `_` 通配（SQLite LIKE 的 `_` 按 UTF-8
        // 码点恰好匹配一个字符），超集性保持，精确判定交给应用侧 [matches]。
        keyword.codePoints().forEach { cp ->
            when {
                // `i` 可能来自 `İ`，`k` 可能来自 `K`(U+212A)：原文里这两个位置未必是 ASCII
                cp == 'i'.code || cp == 'k'.code -> sb.append('_')
                // 增补平面 / 孤立代理 ⇒ 无法写成合法 UTF-8 字面量 ⇒ 通配兜底
                cp >= 0x10000 || cp in 0xD800..0xDFFF -> sb.append('_')
                // 非 ASCII 且**有大小写变体** ⇒ 原文可能是大写形态 ⇒ 通配兜底
                cp >= 128 && !isCaseSafe(cp.toChar()) -> sb.append('_')
                // 其余（ASCII 字母数字、无大小写概念的 CJK 等）⇒ 字面量精确匹配
                else -> appendLiteral(sb, cp.toChar())
            }
        }
        sb.append('%')
        return sb.toString()
    }

    /**
     * 该字符是否**没有大小写变体**（CJK、希伯来、阿拉伯、泰文…）。
     *
     * 用 `String` 版折叠（可产生多码点）而不是 `Char` 版：
     * `Char.uppercaseChar()` 对 `ß` 返回 `ß`（无法表达 `SS`），
     * 会把它误判成「无大小写变体」⇒ 原文里的 `ẞ` 就被漏掉了。
     */
    private fun isCaseSafe(ch: Char): Boolean {
        val s = ch.toString()
        // Locale.ROOT 的折叠（Kotlin 的 uppercase()/lowercase() 默认就是 ROOT）⇒ 不受系统区域设置影响
        return s.uppercase() == s && s.lowercase() == s
    }

    /** LIKE 的字面量：转义 `%` / `_` / `\`（否则用户输入的 `%` 会被当通配符） */
    private fun appendLiteral(sb: StringBuilder, ch: Char) {
        if (ch == '%' || ch == '_' || ch == '\\') sb.append('\\')
        sb.append(ch)
    }

    /** 组合上点 U+0307：`İ`(U+0130) 折叠后的第二个码点 */
    private const val COMBINING_DOT_ABOVE = '\u0307'
}
