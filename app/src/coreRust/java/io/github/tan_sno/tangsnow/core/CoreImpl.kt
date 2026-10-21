package io.github.tan_sno.tangsnow.core

import io.github.tan_sno.tangsnow.data.Bookmark
import io.github.tan_sno.tangsnow.data.BookmarkHtml

/**
 * 「外部内核」接缝 —— **coreRust** flavor 侧实现（2026-10-19，W1 接线）。
 *
 * 契约与 coreKotlin 侧逐字相同（见该文件）：返回 `null` = 本次不接管，调用方回落共享
 * `src/main` 里的纯 Kotlin 实现。本 flavor 的接管来源是 Rust 核心（[CoreNative]）。
 *
 * ## 两档「不接管」的分工
 *  - **整库不可用**（`.so` 未随包 / `System.loadLibrary` 失败 ⇒ `available == false`）：
 *    这里**全部**返回 null ⇒ 本 flavor 的行为与 coreKotlin 逐字节一致。JVM 单测与
 *    「Rust 包在其库加载失败的真机上」都是这一档，是刻意的功能降级而不是崩溃。
 *  - **已加载但本次调用失败**：`CoreNative.guard` 已就地给出该函数的安全默认值
 *    （`false` / `""` / `null`…），那是 **非 null** 的，会被本层当作"接管结果"原样返回。
 *    这是刻意的：guard 的默认值就是该函数在 Rust 侧失败时的事故口径（多数是 fail-closed），
 *    在这一层再叠一次回落只会把「Rust 侧出事了」这个事实盖掉，与 [CoreNative] 的降级设计相反。
 *
 * ## 分批情况
 *  - **W1（2026-10-19）**：7 个单值进出的纯字符串函数，无 JNI 结构 ⇒ 签名漂移的后果可预期。
 *  - **W2（2026-10-20）**：带落盘 / 隐私 / 数据往返语义的 11 个桥 —— 下载文件名 4、崩溃日志 2、
 *    书签 JSON 三件（`bookmarkParse/Sanitize/Export`）、历史入库过滤 1、法律粗体区间 1。
 *    书签那三个在原计划里归 W3（JSON 桥），并入的理由：它们的边界缺陷（CESU-8 / 别名回显 /
 *    描述符 / 数组写回）已在 `.so` 侧重编修复，并有设备侧仪器测试（甲2）钉住前置。
 *    见工作区的 `../notes-tangsnow/PLAN-2026-10-15-flavor-wiring.md` §二
 *    （**在仓库外**：本仓只进代码，工作笔记不进仓，故路径相对仓库根是 `../notes-tangsnow/…`）。
 *  - **W3（未接）**：数组 / 管道协议族（`bitmap*`、`dt*`、`up*`…）。
 */
internal object CoreImpl {

    /** 只在库真的加载成功时才问 Rust；否则返回 null（= 不接管，交回共享 Kotlin 实现）。 */
    private inline fun <T> native(block: () -> T): T? =
        if (CoreNative.available) block() else null

    fun resolveInfo(input: String, template: String): Array<String>? =
        native { CoreNative.resolveInfo(input, template) }

    fun looksLikeUri(input: String): Boolean? = native { CoreNative.looksLikeUri(input) }

    fun schemeOf(input: String): String? = native { CoreNative.schemeOf(input) }

    fun isPortableUrl(url: String?): Boolean? = native { CoreNative.isPortableUrl(url) }

    fun percentEncode(value: String): String? = native { CoreNative.percentEncode(value) }

    fun searchUrl(template: String, query: String): String? =
        native { CoreNative.searchUrlFromTemplate(template, query) }

    fun displayForUrl(url: String, template: String, queryParam: String?, engineId: String): String? =
        native { CoreNative.displayForUrl(url, template, queryParam, engineId) }

    // ================================================================
    // W2 批（2026-10-20）—— 有落盘 / 隐私 / 数据往返语义的桥。
    //
    // ⚠️ 与 W1 的差别：W2 里几个桥在 **CoreNative 层**的非 null 默认值是「安全默认值」
    // （`dlIsExecutableName` → true、`dlSanitizeFileName` → "download"、`crashDisplayStamp` → 原串）。
    // 若不加 `available` 闸门，JVM 上（库必然不可用）这些默认值会被当成"接管结果"返回，
    // 于是 coreRust 的共享测试就不再与 coreKotlin 逐字节一致。故本层统一先问 `CoreNative.available`，
    // 不可用一律 null（= 不接管），与 W1 形状一致。
    //
    // 书签三件走 [CoreBridge]（JSON 适配）；只在 `available` 时调用 —— CoreBridge 会构造
    // `org.json` 对象，JVM 上那是未打桩的 Android 类，直接调会抛。闸门把它挡住。
    // ================================================================

    fun bookmarkParse(html: String, nowMs: Long): Pair<List<BookmarkHtml.Entry>, Int>? =
        native { CoreBridge.parseImportCounted(html, nowMs) }

    fun bookmarkSanitize(
        raw: List<BookmarkHtml.Entry>,
        existingUrls: Set<String>,
    ): Pair<List<BookmarkHtml.Entry>, Int>? = native { CoreBridge.sanitize(raw, existingUrls) }

    fun bookmarkExport(bookmarks: List<Bookmark>): String? = native { CoreBridge.export(bookmarks) }

    fun dlParseFileName(contentDisposition: String?, url: String?): String? =
        native { CoreNative.dlParseFileName(contentDisposition, url) }

    fun dlSanitizeFileName(raw: String): String? = native { CoreNative.dlSanitizeFileName(raw) }

    fun dlIsExecutableName(fileName: String): Boolean? = native { CoreNative.dlIsExecutableName(fileName) }

    fun dlBareMimeType(raw: String?): String? = native { CoreNative.dlBareMimeType(raw) }

    fun crashRedactUrls(text: String): String? = native { CoreNative.crashRedactUrls(text) }

    fun crashDisplayStamp(fileName: String): String? = native { CoreNative.crashDisplayStamp(fileName) }

    // ================================================================
    // W2 收尾（2026-10-20 第二批）—— PLAN 官方 W2 清单的最后两桥。
    // ================================================================

    /** 历史入库过滤；guard 默认 true 是 fail-closed（不进历史），available=false 时由本层归 null */
    fun shouldSkipHistoryUrl(url: String): Boolean? = native { CoreNative.shouldSkipHistoryUrl(url) }

    /** 法律 `**…**` 加粗区间（扁平 [start,end,...] 闭区间）；null = 不接管或区间算不出 */
    fun legalParseBoldRanges(raw: String): IntArray? = native { CoreNative.legalParseBoldRanges(raw) }
}