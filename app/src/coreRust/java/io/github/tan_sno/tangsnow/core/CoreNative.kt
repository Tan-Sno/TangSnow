package io.github.tan_sno.tangsnow.core

import java.io.File

/**
 * Rust 核心逻辑库（tangsnow-core）的 JNI 桥 —— **防护网形态**（2026-10-08 深度重构批 D）。
 *
 * 命名转义：包名下划线 → JNI 里 `tan_sno` 写作 `tan_1sno`（Rust 侧符号已对齐，均为 *Impl）。
 *
 * ## 为什么每个调用都要防护网
 *
 * 深度精简后 Kotlin 侧**没有**降级实现（行为唯一所有权在 Rust），任何 JNI 层异常都会
 * 直接把调用方炸穿 —— 真机上表现为「一触发就闪退」。桥的正确形态是：
 *
 *  1. **加载即探明**：available 在类初始化时急切求值（加载失败=false，不抛）；
 *  2. **调用前守卫**：available=false 时直接返回安全默认值，**不触碰 native**；
 *  3. **调用时兜底**：native 侧任何 Throwable（UnsatisfiedLinkError/符号缺失/未来回归）
 *     被捕获 → 记录到诊断日志（Log + filesDir 落盘）→ 返回安全默认值。
 *
 * 这样「.so 环境异常」的后果从**闪退**降级为**功能降级**，且真因栈完整落盘可取。
 * 诊断日志经 [initDiagDir] 注入应用 filesDir；未注入时只走 Log（JVM 单测无 Log 桩，
 * 故 Log 调用本身也被 runCatching 包裹）。
 */
object CoreNative {

    /** 加载是否成功；false 时所有桥函数走安全默认值（见 [guard]）。 */
    val available: Boolean = try {
        System.loadLibrary("tangsnow_core")
        true
    } catch (_: Throwable) {
        false
    }

    @Volatile private var diagDir: File? = null

    /** 注入诊断落盘目录（应用 filesDir/diag）。应在 Application/首 Activity 尽早调用。 */
    fun initDiagDir(dir: File) {
        diagDir = dir
    }

    /** 统一防护网：异常 → Log + 落盘 → 安全默认值。
     *  ⚠️ block 返回 T? 并做 `?: default`：JNI 的 external 声明虽为非空，但 Rust 侧
     *  任何失败都返回 null（平台类型不检查）——2026-10-09 模拟器实锤「null 静默穿透
     *  非空声明 → Resolved(data class) 主构造 NPE 闪退」，故 null 必须就地兜底。 */
    private inline fun <T> guard(op: String, default: T, block: () -> T?): T {
        if (!available) return default
        return try {
            block() ?: default.also {
                runCatching { android.util.Log.e("CoreNative", "JNI '$op' returned null; fallback applied") }
            }
        } catch (t: Throwable) {
            runCatching {
                android.util.Log.e("CoreNative", "JNI '$op' failed", t)
            }
            recordDiag(op, t)
            default
        }
    }

    /** 诊断落盘：追加「时间戳 | 操作 | 异常全栈」到 diag/corenative.log（最多 256KB 截断）。 */
    private fun recordDiag(op: String, t: Throwable) {
        val dir = diagDir ?: return
        runCatching {
            val f = File(dir.apply { mkdirs() }, "corenative.log")
            if (f.length() > 256 * 1024) f.writeText("")
            f.appendText(
                "${System.currentTimeMillis()} | $op | ${t.javaClass.name}: ${t.message}\n" +
                    t.stackTraceToString().prependIndent("  ") + "\n",
            )
        }
    }

    // ---------------------------------------------------------------- 地址栏

    /** 地址栏解析：返回 `[url, isSearch]`（isSearch 为 "1"/"0"）；null = 空输入 */
    fun resolveInfo(input: String, template: String): Array<String>? =
        guard("resolveInfo", null) { resolveInfoImpl(input, template) }

    /** 从分享文本提取第一个 http(s) 链接；null = 无链接 */
    fun extractUrlFromText(text: String): String? =
        guard("extractUrlFromText", null) { extractUrlFromTextImpl(text) }

    /** 是否落在局域网（RFC 1918 / 链路本地 / IPv6 ULA / .local，mapped IPv6 已覆盖） */
    fun isLocalNetworkAddress(input: String): Boolean =
        guard("isLocalNetworkAddress", false) { isLocalNetworkAddressImpl(input) }

    fun looksLikeUri(input: String): Boolean =
        guard("looksLikeUri", false) { looksLikeUriImpl(input) }

    /** schemeOf：小写、不含冒号；无合法 scheme 返回空串 */
    fun schemeOf(input: String): String =
        guard("schemeOf", "") { schemeOfImpl(input) }

    fun isWebNavigationScheme(scheme: String?): Boolean =
        guard("isWebNavigationScheme", false) { isWebNavigationSchemeImpl(scheme) }

    fun isNeverWebContentScheme(scheme: String?): Boolean =
        guard("isNeverWebContentScheme", false) { isNeverWebContentSchemeImpl(scheme) }

    /** 可携带/可落盘判据（排除 about:/data:/blob:/file:/content:） */
    fun isPortableUrl(url: String?): Boolean =
        guard("isPortableUrl", false) { isPortableUrlImpl(url) }

    // ---------------------------------------------------------------- 搜索

    /** 与 [io.github.tan_sno.tangsnow.data.SearchEngine.searchUrl] 同口径 */
    fun searchUrlFromTemplate(template: String, query: String): String =
        guard("searchUrlFromTemplate", kotlinSearchUrl(template, query)) {
            searchUrlFromTemplateImpl(template, query)
        }

    /** 纯 Kotlin 参考实现（git 0e1cb9f 原版语义）：percentEncode → {q}/%s 替换 →
     *  无占位符补 ?q=（有 ? 用 &q=）→ 空模板回落 Bing。
     *  用途：native 不可用/返回 null 时的功能级兜底——default 不应是裸模板
     *  （裸模板带着 {q} 字面量去加载，搜索直接废）。 */
    private fun kotlinSearchUrl(template: String, query: String): String {
        val encoded = kotlinPercentEncode(query.trim())
        val substituted = template.replace("{q}", encoded).replace("%s", encoded)
        if (substituted != template) return substituted
        val base = template.ifBlank { "https://www.bing.com/search" }
        val glue = if (base.contains('?')) "&q=" else "?q="
        return base + glue + encoded
    }

    private val HEX = "0123456789ABCDEF".toCharArray()

    /** 与 android.net.Uri.encode 逐字节一致的百分号编码（原版纯函数）。 */
    private fun kotlinPercentEncode(value: String): String {
        val sb = StringBuilder(value.length * 3)
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
                ch == '_' || ch == '-' || ch == '!' || ch == '.' ||
                ch == '~' || ch == '\'' || ch == '(' || ch == ')' || ch == '*'
            ) {
                sb.append(ch)
            } else {
                sb.append('%')
                sb.append(HEX[c shr 4])
                sb.append(HEX[c and 0x0F])
            }
        }
        return sb.toString()
    }

    /** 与 android.net.Uri.encode 逐字节一致的百分号编码。
     *  降级默认值用本文件里已有的纯 Kotlin 参考实现，**不是恒等**：恒等会把未编码的
     *  `&`/`#`/`=` 原样插进搜索模板 ⇒ 查询参数注入 / 片段截断。 */
    fun percentEncode(value: String): String =
        guard("percentEncode", kotlinPercentEncode(value)) { percentEncodeImpl(value) }

    /**
     * 搜索结果页关键词回显（单引擎口径）。
     * @param template 引擎模板；@param queryParam 结果页关键词参数名（可为 null）
     * @return 关键词；非该引擎结果页返回 null
     */
    fun displayForUrl(url: String, template: String, queryParam: String?): String? =
        guard("displayForUrl", null) { displayForUrlImpl(url, template, queryParam) }

    // ---------------------------------------------------------------- 书签

    /**
     * 解析 Netscape 书签 HTML。
     * @return JSON：`{"entries":[{"url","title","created_at_ms"?}...],"dropped":n}`
     */
    fun bookmarkParse(html: String, nowMs: Long): String? =
        guard("bookmarkParse", null) { bookmarkParseImpl(html, nowMs) }

    /**
     * 导入清洗（scheme 白名单/去重/上限/标题截断）。
     * @param rawJson [bookmarkParse] 输出；@param existingJson 字符串数组 JSON
     * @return JSON：`{"entries":[{"url","title","created_at_ms"?}...],"skipped":n}`
     */
    fun bookmarkSanitize(rawJson: String, existingJson: String): String? =
        guard("bookmarkSanitize", null) { bookmarkSanitizeImpl(rawJson, existingJson) }

    /** 导出 Netscape HTML；@param bookmarksJson `[{"url","title","created_at_ms"}...]` */
    fun bookmarkExport(bookmarksJson: String): String? =
        guard("bookmarkExport", null) { bookmarkExportImpl(bookmarksJson) }

    // ---------------------------------------------------------------- 历史

    fun shouldSkipHistoryUrl(url: String): Boolean =
        guard("shouldSkipHistoryUrl", false) { shouldSkipHistoryUrlImpl(url) }

    // ---------------------------------------------------------------- 崩溃日志

    /** @return null = 脱敏不可用（核心缺失或本次调用失败）。调用方**必须**按「不可安全落盘」处理：
     *  默认值若是原文，政策第 7 条给的是封闭式承诺（仅域名、不含完整网址），一次降级就当场打破，
     *  而这份日志正是引导用户分享到外部的东西。 */
    fun crashRedactUrls(text: String): String? =
        guard("crashRedactUrls", null) { crashRedactUrlsImpl(text) }

    fun crashDisplayStamp(fileName: String): String =
        guard("crashDisplayStamp", fileName) { crashDisplayStampImpl(fileName) }

    // ---------------------------------------------------------------- 位图计算

    /** @return `<= 0` = 算不出来。默认值刻意**不是 1**：1 就是「整张解码」，正是会 OOM 的那一档；
     *  调用方拿到非正数应回落到本地实现（`Bitmaps.requiredSampleSizeImpl`，`!available` 分支已在用）。 */
    fun bitmapRequiredSampleSize(outW: Int, outH: Int, reqW: Int, reqH: Int, maxPixels: Long): Int =
        guard("bitmapRequiredSampleSize", 0) {
            bitmapRequiredSampleSizeImpl(outW, outH, reqW, reqH, maxPixels)
        }

    fun bitmapCenterCropRect(srcW: Int, srcH: Int, targetW: Int, targetH: Int): IntArray =
        guard("bitmapCenterCropRect", intArrayOf(0, 0, srcW, srcH)) {
            bitmapCenterCropRectImpl(srcW, srcH, targetW, targetH)
        }

    // ---------------------------------------------------------------- 下载文件名

    fun dlParseFileName(contentDisposition: String?, url: String?): String? =
        guard("dlParseFileName", null) { dlParseFileNameImpl(contentDisposition, url) }

    /** 降级默认值是**安全名**而不是原串：原串带着 `/`、`..\`、绝对路径，而 API 26-28 分支
     *  的 `File(dir, name)` 里 child 为绝对路径时结果即 child、`..` 逐级上跳 —— 该分支的不变式
     *  本就只靠「调用方先净化」活着。`"download"` 与 Rust 侧「清洗后为空」的回落同名，
     *  两条降级路径口径一致（MediaStore / createUnique 会自动给重名追加序号）。 */
    fun dlSanitizeFileName(raw: String): String =
        guard("dlSanitizeFileName", "download") { dlSanitizeFileNameImpl(raw) }

    /** 判不出来就**当可执行**：这条是 `.apk`/`.aab` 强确认闸门（`MainActivity` 的 `needsConfirm`）
     *  的判据，默认 false 等于在降级时把「没有『不再询问』的安装包确认」静默关掉。 */
    fun dlIsExecutableName(fileName: String): Boolean =
        guard("dlIsExecutableName", true) { dlIsExecutableNameImpl(fileName) }

    fun dlBareMimeType(raw: String?): String? =
        guard("dlBareMimeType", null) { dlBareMimeTypeImpl(raw) }

    // ---------------------------------------------------------------- 法律文本

    /** 返回扁平化的 [start0, end0, start1, end1, …]（字符索引闭区间）；null = 加载失败 */
    fun legalParseBoldRanges(raw: String): IntArray? =
        guard("legalParseBoldRanges", null) { legalParseBoldRangesImpl(raw) }

    // ---------------------------------------------------------------- 更新链路

    /** tag 名规整成可比较版本号（各剥一次 v/V + 贪婪数字段）；空串 = 无法识别 */
    fun upNormalizeVersion(raw: String): String? =
        guard("upNormalizeVersion", null) { upNormalizeVersionImpl(raw) }

    /** 远端版本是否更新（逐段整数比较，缺失段补 0；任一侧不可解析 → false） */
    fun upIsNewer(remote: String, local: String): Boolean =
        guard("upIsNewer", false) { upIsNewerImpl(remote, local) }

    /** 可信跳转目标：原文不含反斜杠 + https + host 严格等于 expectedHost（均忽略大小写） */
    fun upIsTrustedTarget(raw: String, scheme: String?, host: String?, expectedHost: String): Boolean =
        guard("upIsTrustedTarget", false) { upIsTrustedTargetImpl(raw, scheme, host, expectedHost) }

    /** ABI 资产匹配；名单以 '\n' 连接传入。null = 无命中 */
    fun upPickAsset(assetNamesJoined: String, supportedAbisJoined: String): String? =
        guard("upPickAsset", null) { upPickAssetImpl(assetNamesJoined, supportedAbisJoined) }

    /** 自定义搜索引擎/快捷方式 URL 规范化；null = 非法输入 */
    fun prefsNormalizeUrl(input: String): String? =
        guard("prefsNormalizeUrl", null) { prefsNormalizeUrlImpl(input) }

    // ---------------------------------------------------------------- 网页表单时间

    /**
     * 时间提示默认值的宽松解析（SMART 口径）。kind ∈ {time, datetime, date, month, week}；
     * 返回 "规范化值|秒形态标记"，null = 不可解析。WEEK 归一为该周周一。
     */
    fun dtParseLoose(kind: String, raw: String): String? =
        guard("dtParseLoose", null) { dtParseLooseImpl(kind, raw) }

    /** 选择器产出按解析时记录的形态格式化回交；null = 字段越界。 */
    fun dtFormatChosen(
        kind: String,
        withSeconds: Boolean,
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ): String? = guard("dtFormatChosen", null) {
        dtFormatChosenImpl(kind, withSeconds, year, month, day, hour, minute)
    }

    // ================================================================
    // 以下为 native 实现（JNI 符号 = CoreNative_xxxImpl，Rust 侧已同步）。
    // 全部 private —— 调用方只能走上面的防护网包装。
    // ================================================================

    private external fun resolveInfoImpl(input: String, template: String): Array<String>?
    private external fun extractUrlFromTextImpl(text: String): String?
    private external fun isLocalNetworkAddressImpl(input: String): Boolean
    private external fun looksLikeUriImpl(input: String): Boolean
    private external fun schemeOfImpl(input: String): String
    private external fun isWebNavigationSchemeImpl(scheme: String?): Boolean
    private external fun isNeverWebContentSchemeImpl(scheme: String?): Boolean
    private external fun isPortableUrlImpl(url: String?): Boolean
    private external fun searchUrlFromTemplateImpl(template: String, query: String): String
    private external fun percentEncodeImpl(value: String): String
    private external fun displayForUrlImpl(url: String, template: String, queryParam: String?): String?
    private external fun bookmarkParseImpl(html: String, nowMs: Long): String?
    private external fun bookmarkSanitizeImpl(rawJson: String, existingJson: String): String?
    private external fun bookmarkExportImpl(bookmarksJson: String): String?
    private external fun shouldSkipHistoryUrlImpl(url: String): Boolean
    private external fun crashRedactUrlsImpl(text: String): String
    private external fun crashDisplayStampImpl(fileName: String): String
    private external fun bitmapRequiredSampleSizeImpl(outW: Int, outH: Int, reqW: Int, reqH: Int, maxPixels: Long): Int
    private external fun bitmapCenterCropRectImpl(srcW: Int, srcH: Int, targetW: Int, targetH: Int): IntArray
    private external fun dlParseFileNameImpl(contentDisposition: String?, url: String?): String?
    private external fun dlSanitizeFileNameImpl(raw: String): String
    private external fun dlIsExecutableNameImpl(fileName: String): Boolean
    private external fun dlBareMimeTypeImpl(raw: String?): String?
    private external fun legalParseBoldRangesImpl(raw: String): IntArray?
    private external fun upNormalizeVersionImpl(raw: String): String?
    private external fun upIsNewerImpl(remote: String, local: String): Boolean
    private external fun upIsTrustedTargetImpl(raw: String, scheme: String?, host: String?, expectedHost: String): Boolean
    private external fun upPickAssetImpl(assetNamesJoined: String, supportedAbisJoined: String): String?
    private external fun prefsNormalizeUrlImpl(input: String): String?
    private external fun dtParseLooseImpl(kind: String, raw: String): String?
    private external fun dtFormatChosenImpl(
        kind: String,
        withSeconds: Boolean,
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ): String?
}
