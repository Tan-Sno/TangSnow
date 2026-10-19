package io.github.tan_sno.tangsnow.core

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
 * ## 为什么 W1 只接这 7 个
 * 都是单值进出的纯字符串函数，无 JNI 结构（无数组、无 JSON 桥）⇒ 签名漂移的后果可预期；
 * 带落盘/隐私语义的（W2）与结构敏感的（W3，JSON/数组/管道协议）另批，见
 * `notes-tangsnow/PLAN-2026-10-15-flavor-wiring.md` §二。
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
}