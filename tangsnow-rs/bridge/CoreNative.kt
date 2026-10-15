package io.github.tan_sno.tangsnow.core

import android.util.Log

/**
 * Rust 核心逻辑库（tangsnow-core）的 JNI 桥接层。
 *
 * ⚠️ JNI 命名转义：包名 `io.github.tan_sno.tangsnow.core` 中的下划线必须转义，
 * 对应 Rust 侧符号 `Java_io_github_tan_1sno_tangsnow_core_CoreNative_*`。
 *
 * 接入方式（三选一，渐进式，风险从低到高）：
 *
 * 1. **旁路验证（推荐先做）**：保留 Kotlin 现有实现作为权威，新增测试路径把
 *    同一输入分别喂给 Kotlin 与 Rust 侧，比对输出。跑一段时间无差异后再切换。
 *
 * 2. **委托切换**：把 `UrlUtils` / `BookmarkHtml` / `SearchEngines` 的调用点
 *    改为走本对象（Kotlin 实现保留为降级路径，见 [available]）。
 *
 * 3. **整体替换**：删除 Kotlin 纯逻辑实现，库缺失时由 [CoreUnavailable] 决定
 *    fail-fast 还是降级。
 *
 * .so 放置路径：`app/src/main/jniLibs/<abi>/libtangsnow_core.so`
 * （由 `tangsnow-rs/tools/build-android.sh` 产出）。
 */
object CoreNative {

    private const val TAG = "CoreNative"

    /** Rust 库是否加载成功；false 时调用方应走 Kotlin 降级路径 */
    val available: Boolean by lazy {
        try {
            System.loadLibrary("tangsnow_core")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "tangsnow_core 加载失败，回退 Kotlin 实现", e)
            false
        }
    }

    /** 库不可用且调用方要求 fail-fast 时抛出 */
    class CoreUnavailable : IllegalStateException("tangsnow_core 未加载")

    // ---------------------------------------------------------------- 地址栏

    /** 地址栏输入解析：返回 [url, isSearch("1"/"0")]；输入为空返回 null */
    external fun resolveInfo(input: String, engineTemplate: String): Array<String>?

    /** [`resolveInfo`] 的 URL 通道 */
    fun resolveUrl(input: String, engineTemplate: String): String? =
        resolveInfo(input, engineTemplate)?.get(0)

    // ---------------------------------------------------------------- 判定

    external fun looksLikeUri(input: String): Boolean

    external fun isLocalNetworkAddress(input: String): Boolean

    external fun schemeOf(input: String): String?

    external fun isWebNavigationScheme(scheme: String?): Boolean

    external fun isNeverWebContentScheme(scheme: String?): Boolean

    external fun isPortableUrl(url: String?): Boolean

    /** 从分享文本提取第一个 http(s) 链接；无链接返回 null */
    external fun extractUrlFromText(text: String): String?

    // ---------------------------------------------------------------- 搜索

    external fun searchUrlFromTemplate(template: String, query: String): String

    external fun templateHostOf(template: String): String?

    external fun hostMatches(host: String, engineId: String, templateHost: String): Boolean

    // ---------------------------------------------------------------- 书签

    /**
     * 解析 Netscape 书签 HTML。
     * @param html 原始文件内容
     * @param nowMs 「导入时刻」（epoch 毫秒）；Rust 侧不取系统时钟，由调用方显式给值
     * @return JSON：`{"entries":[[url,title,ms|null],...],"dropped":n}`
     */
    external fun bookmarkParse(html: String, nowMs: Long): String?

    /**
     * 导入清洗（scheme 白名单 / 去重 / 上限 / 标题截断）。
     * @param rawJson [bookmarkParse] 的 JSON 输出
     * @param existingJson 已有 URL 的 JSON 字符串数组
     * @return JSON：`{"entries":[[url,title,ms],...],"skipped":n}`
     */
    external fun bookmarkSanitize(rawJson: String, existingJson: String): String?

    /** 导出。@param bookmarksJson `[[url,title,createdAtMs],...]`；返回 Netscape HTML */
    external fun bookmarkExport(bookmarksJson: String): String?

    // ---------------------------------------------------------------- 历史

    external fun shouldSkipHistoryUrl(url: String): Boolean
}
