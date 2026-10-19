package io.github.tan_sno.tangsnow.core

import java.io.File

/**
 * Rust 核心逻辑库（tangsnow-core）的 JNI 桥 —— **防护网形态**（2026-10-08 深度重构批 D）。
 *
 * 命名转义：包名下划线 → JNI 里 `tan_sno` 写作 `tan_1sno`（Rust 侧符号已对齐，均为 *Impl）。
 *
 * ## 为什么每个调用都要防护网
 *
 * ⚠️ **2026-10-19（W1 接线）更正**：此处原先写「Kotlin 侧**没有**降级实现（行为唯一所有权在 Rust）」
 * —— 接 W1 之后这句不再成立：那 7 个纯字符串函数在 `src/main` 保留了纯 Kotlin 实现，由
 * [CoreImpl] 决定谁接管（库不可用 ⇒ 不接管 ⇒ 回落到共享实现）。未接线的其余函数仍维持原判。
 * 防护网形态未变。任何 JNI 层异常都会
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
     *  非空声明 → Resolved(data class) 主构造 NPE 闪退」，故 null 必须就地兜底。
     *
     *  @param nullIsResult 该函数的 **null 本身就是业务结果**（"没有链接" / "不是该引擎结果页" /
     *  "无命中"），不是失败。为 true 时不写降级留痕：否则每次空输入、每次分享文本里没有链接都要
     *  刷一条 ERROR，而**真**失败在 Rust 侧另有 `jlog`（`opt_str` 读入失败、`ret_str` 建串失败两条
     *  路径都留痕，且随包进 release）。把「正常」也报成错误，代价是下次没人看这条链
     *  （2026-10-15 外部审查 L3）。 */
    private inline fun <T> guard(op: String, default: T, nullIsResult: Boolean = false, block: () -> T?): T {
        if (!available) return default
        return try {
            block() ?: default.also {
                if (!nullIsResult) {
                    runCatching { android.util.Log.e("CoreNative", "JNI '$op' returned null; fallback applied") }
                }
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
        guard("resolveInfo", null, nullIsResult = true) { resolveInfoImpl(input, template) }

    /** 从分享文本提取第一个 http(s) 链接；null = 无链接 */
    fun extractUrlFromText(text: String): String? =
        guard("extractUrlFromText", null, nullIsResult = true) { extractUrlFromTextImpl(text) }

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

    /** 降级默认值是 **true**（fail-closed）：Kotlin `UrlUtils.isNeverWebContentScheme` 对
     *  `file:`/`moz-extension:`/`javascript:` 返回 true。核心缺失时若默认 false，同一道安全闸会
     *  静默变成「这三个 scheme 都不算危险」⇒ `window.open` 那条最终走应用自己 `loadUri` 的链会放行
     *  `file:`/脚本注入。判不出来就应当「当危险」（与同文件 `dlIsExecutableName` 的 fail-closed 同旨）。 */
    fun isNeverWebContentScheme(scheme: String?): Boolean =
        guard("isNeverWebContentScheme", true) { isNeverWebContentSchemeImpl(scheme) }

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
     * @param engineId 引擎 id（`SearchEngine.id`）——**必须由调用方给**：Rust 侧的别名表
     * `HOST_ALIASES` 以真 id 为键，桥层写死占位 id 会让 `bing.com` / `sm.cn` 这类
     * 「模板主机之外」的官方结果页恒不命中（2026-10-15 外部审查 M5）。自定义引擎照常传它的
     * `custom_*` id —— Kotlin 侧同样不给它配别名，口径一致。
     * @return 关键词；非该引擎结果页返回 null
     */
    fun displayForUrl(url: String, template: String, queryParam: String?, engineId: String): String? =
        guard("displayForUrl", null, nullIsResult = true) {
            displayForUrlImpl(url, template, queryParam, engineId)
        }

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

    /** 降级默认值是 **true**（fail-closed）：Kotlin `HistoryRepo` 跳过 about:/chrome:/data:/
     *  blob:/javascript:/moz-extension: 等前缀。核心缺失时若默认 false，等于「什么都写进历史」，
     *  内部页与只在当前会话有效的 blob:/data: 会静默落盘（隐私面）。判不出来就应当「当要跳过」。 */
    fun shouldSkipHistoryUrl(url: String): Boolean =
        guard("shouldSkipHistoryUrl", true) { shouldSkipHistoryUrlImpl(url) }

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
        guard("dlParseFileName", null, nullIsResult = true) { dlParseFileNameImpl(contentDisposition, url) }

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
        guard("upNormalizeVersion", null, nullIsResult = true) { upNormalizeVersionImpl(raw) }

    /** 远端版本是否更新（逐段整数比较，缺失段补 0；任一侧不可解析 → false） */
    fun upIsNewer(remote: String, local: String): Boolean =
        guard("upIsNewer", false) { upIsNewerImpl(remote, local) }

    /** 可信跳转目标：原文不含反斜杠 + https + host 严格等于 expectedHost（均忽略大小写） */
    fun upIsTrustedTarget(raw: String, scheme: String?, host: String?, expectedHost: String): Boolean =
        guard("upIsTrustedTarget", false) { upIsTrustedTargetImpl(raw, scheme, host, expectedHost) }

    /** ABI 资产匹配；名单以 '\n' 连接传入。null = 无命中 */
    fun upPickAsset(assetNamesJoined: String, supportedAbisJoined: String): String? =
        guard("upPickAsset", null, nullIsResult = true) { upPickAssetImpl(assetNamesJoined, supportedAbisJoined) }

    /** 自定义搜索引擎/快捷方式 URL 规范化；null = 非法输入 */
    fun prefsNormalizeUrl(input: String): String? =
        guard("prefsNormalizeUrl", null, nullIsResult = true) { prefsNormalizeUrlImpl(input) }

    // ---------------------------------------------------------------- 网页表单时间

    /**
     * 时间提示默认值的宽松解析（SMART 口径）。kind ∈ {time, datetime, date, month, week}；
     * 返回 "规范化值|秒形态标记"，null = 不可解析。WEEK 归一为该周周一。
     */
    fun dtParseLoose(kind: String, raw: String): String? =
        guard("dtParseLoose", null, nullIsResult = true) { dtParseLooseImpl(kind, raw) }

    /** 选择器产出按解析时记录的形态格式化回交；null = 字段越界。 */
    fun dtFormatChosen(
        kind: String,
        withSeconds: Boolean,
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ): String? = guard("dtFormatChosen", null, nullIsResult = true) {
        dtFormatChosenImpl(kind, withSeconds, year, month, day, hour, minute)
    }

    // ================================================================
    // 以下为 native 实现（JNI 符号 = CoreNative_xxxImpl，Rust 侧已同步）。
    // 全部 private —— 调用方只能走上面的防护网包装。
    //
    // ⚠️ 签名与随包 `.so` 的关系：`displayForUrlImpl` 在 2026-10-15 多了 `engineId` 形参
    // （M5），同批还有 Rust 侧的 CESU-8 解码修正（H3）。随包这三颗是 2026-10-10 那批、在另一台机器上
    // 交叉编译的；本机 2026-10-18 起**已装**三个 android target 并能重编三 ABI（按裁定**只产、只比对、
    // 不替换** flavor 里那三颗）⇒ **重建之前**它们在真机上的表现**不是**报错：
    // 导出是短名（非重载方法的 JNI 查找不带描述符），4 参声明会**成功绑定**到 3 参 callee，
    // 多余实参由各 ABI 的调用方清理 ⇒ **静默跑旧语义**（别名表不命中、关键词不回显，
    // 无异常无日志）；H3 那条同理是旧编码行为。只有符号整体缺失才 UnsatisfiedLinkError。
    // ⇒ 这类漂移不能指望真机给信号：签名一致性由 `BridgeSignatureParityTest` 钉、
    //   「本机能不能重编 / 16K 对齐 / profile 拷贝」由 `ReleaseGateConsistencyTest` 钉，都不靠人回忆。
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
    private external fun displayForUrlImpl(url: String, template: String, queryParam: String?, engineId: String): String?
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
