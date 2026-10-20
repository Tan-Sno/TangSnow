package io.github.tan_sno.tangsnow.core

import io.github.tan_sno.tangsnow.data.Bookmark
import io.github.tan_sno.tangsnow.data.BookmarkHtml

/**
 * 「外部内核」接缝 —— **coreKotlin** flavor 侧实现（2026-10-19，W1 接线）。
 *
 * ## 契约
 * 每个成员返回 `null` = **本次不接管**，调用方必须回落到共享 `src/main` 里的纯 Kotlin 实现，
 * 并把它当作本次的唯一结果。非 null = 本 flavor 的外部内核接管了这次调用，调用方直接用。
 *
 * ## 为什么是「null = 不接管」而不是「直接给结果」
 * 共享 `src/test` 的同一批断言会在**两个 flavor** 上各跑一遍（`app/src/test` 属于共享源集）。
 * JVM 上 native 库必然不可用，所以 coreRust 侧的共享测试走的也是「不接管 → 回落」这条路径。
 * 若外部内核在不可用时返回**安全默认值**（`false` / `""` / `null` 这些是"算出来的答案"），
 * 回落就会被静默跳过 ⇒ 共享断言要么红在语义上、要么被默认值掩盖真问题（签名漂移）。
 * 让「不可用」与「算出来是空」在调用方眼里可区分，是这层唯一的设计要点。
 *
 * ## 本 flavor 为何全部是 null
 * coreKotlin 没有外部内核 —— 它就是 Kotlin 实现本身。故这里的成员**不是占位符**，
 * 而是该 flavor 的真实形态：一次也不接管，全部由共享实现完成。
 *
 * ## 由谁保证两个 flavor 都实现了同一组签名
 * 编译器：`src/main` 的调用点引用 [CoreImpl]，而 [CoreImpl] 只存在于 flavor 源集 ⇒
 * 少一个 flavor 就是编译失败。不需要接口、不需要运行时 if、不需要 DI。
 */
internal object CoreImpl {

    /** 地址栏解析：`[url, isSearch("1"/"0")]`；null = 不接管 */
    fun resolveInfo(input: String, template: String): Array<String>? = null

    fun looksLikeUri(input: String): Boolean? = null

    fun schemeOf(input: String): String? = null

    fun isPortableUrl(url: String?): Boolean? = null

    fun percentEncode(value: String): String? = null

    fun searchUrl(template: String, query: String): String? = null

    fun displayForUrl(url: String, template: String, queryParam: String?, engineId: String): String? = null

    // ================================================================
    // W2 批（2026-10-20）—— 有落盘 / 隐私 / 数据往返语义的桥。
    // 契约同上：null = 本次不接管。coreKotlin 恒不接管 —— 它本身就是共享 Kotlin 实现。
    // ================================================================

    /** 书签导入解析；null = 不接管 */
    fun bookmarkParse(html: String, nowMs: Long): Pair<List<BookmarkHtml.Entry>, Int>? = null

    /** 书签导入清洗；null = 不接管 */
    fun bookmarkSanitize(
        raw: List<BookmarkHtml.Entry>,
        existingUrls: Set<String>,
    ): Pair<List<BookmarkHtml.Entry>, Int>? = null

    /** 书签导出为 Netscape HTML；null = 不接管 */
    fun bookmarkExport(bookmarks: List<Bookmark>): String? = null

    /** 下载文件名解析；null = 不接管（native 可用时 Rust 侧恒给非空名，见 [dlSanitizeFileName]） */
    fun dlParseFileName(contentDisposition: String?, url: String?): String? = null

    fun dlSanitizeFileName(raw: String): String? = null

    fun dlIsExecutableName(fileName: String): Boolean? = null

    fun dlBareMimeType(raw: String?): String? = null

    fun crashRedactUrls(text: String): String? = null

    fun crashDisplayStamp(fileName: String): String? = null

    // ================================================================
    // W2 收尾（2026-10-20 第二批）—— PLAN 官方 W2 清单的最后两桥。
    // ================================================================

    /** 历史入库过滤（about:/data: 等前缀不进历史）；null = 不接管 */
    fun shouldSkipHistoryUrl(url: String): Boolean? = null

    /** 法律文本 `**…**` 加粗区间（扁平 [start,end,...]，字符索引闭区间）；null = 不接管 */
    fun legalParseBoldRanges(raw: String): IntArray? = null
}