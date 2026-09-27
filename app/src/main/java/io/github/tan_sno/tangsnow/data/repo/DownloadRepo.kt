package io.github.tan_sno.tangsnow.data.repo

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.github.tan_sno.tangsnow.BuildConfig
import io.github.tan_sno.tangsnow.R
import io.github.tan_sno.tangsnow.data.AppHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 下载记录。
 *
 * 棠雪内共有两条保存通道，[managed] 用于区分来源：
 *  - [managed]=false：经系统 DownloadManager 登记（[DownloadRepo.launch] 的 enqueue，
 *    或 [DownloadRepo.saveFromStream] 成功落盘后 addCompletedDownload 的登记），
 *    以 DownloadManager 的 id 为准；
 *  - [managed]=true：文件由本应用直接写盘（MediaStore 公共「下载」目录，或
 *    API 26-28 的应用专属目录），但没有得到 DownloadManager 认可，此时以
 *    [localUri]（content:// 或 file://）自管理，保证「下载」页永远可见。
 */
object DownloadRepo {

    data class Item(
        val id: Long,
        val title: String,
        val stateText: String,
        val progressPercent: Int,
        val localUri: String?,
        val mime: String? = null,
        val managed: Boolean = false,
    )

    /** 自管下载在偏好里的单条记录 */
    private data class Managed(val uri: String, val title: String, val mime: String?)

    /** 下载记录所用的私有 SharedPreferences 文件名（集中一处，避免散落字面量改一处漏一处） */
    private const val PREFS_NAME = "tangsnow_downloads"

    private const val KEY_IDS = "tangsnow_download_ids"

    /** 自管下载记录（content:// 或 file:// 的 uri） */
    private const val KEY_MANAGED = "tangsnow_download_managed"

    /**
     * 「转正失败待重试」队列（N13 选项 A）：writeToDownloads 的转正阶段失败时，
     * 内容已完整但对外不可见 —— 记到此处，由 [sweepStalePromotions] 在下次
     * list() 时重试一次转正，成功即登记进下载列表。
     * **刻意不进** [KEY_MANAGED]（自管下载列表）：队列里的行 IS_PENDING=1，
     * 非 owner 应用打不开——登记进去只会制造「打开必失败」的条目。
     */
    private const val KEY_STALE_PROMOTIONS = "tangsnow_stale_promotions"

    /**
     * 本文件持久化的**全部**记录键（N1）：新增持久化键时必须加进来 ——
     * [clearRecords] 靠它保证「清空」不漏清，并由 `DownloadRecordKeysTest` 的反射哨兵守着。
     */
    private val ALL_RECORD_KEYS = setOf(KEY_IDS, KEY_MANAGED, KEY_STALE_PROMOTIONS)

    /**
     * 待重试条目的保留时长：pending 行约 7 天后被系统按 DATE_EXPIRES 回收，
     * 多留 1 天缓冲后出队（内容与行都已消失，继续保留没有意义）。
     */
    private const val STALE_KEEP_MS = 8L * 24 * 60 * 60 * 1000

    /**
     * 下载记录（[PREFS_NAME] 里的两个 JSON）**读-改-写**序列的互斥锁。
     *
     * 为什么必须加锁：这些操作发生在线程池 IO 线程上，而**多个下载可以同时进行**。
     * SharedPreferences 只保证单次读写原子，不保证「读→算→写」这个整体原子：
     * 两个并发下载各自读到同一份旧列表、各自加一条再写回，后写者会覆盖前者 ——
     * 表现是**某个已下完的文件在「下载」页里查不到**（文件在磁盘上，记录却丢了），
     * 且完全静默。列表清理由同一把锁保护，避免清理时把并发新增的记录一起抹掉。
     */
    private val recordsLock = Any()

    // ------------------------------------------------------------- 结果与常量

    /**
     * 「打开 / 分享」的结果。
     * 之所以不用 Boolean：调用方需要区分「没下完」「下载失败」「文件没了」「没应用能处理」，
     * 才能给出准确提示（此前一律提示"无法打开该文件"，用户无从判断该怎么办）。
     */
    enum class Result {
        /** 已成功交给外部应用 */
        OK,

        /** 尚未下载完成 */
        NOT_READY,

        /** 该下载以失败告终，无文件可用 */
        FAILED,

        /** 记录存在但文件/内容已不存在（被清理或删除） */
        MISSING,

        /** 文件可用，但设备上没有能处理它的应用 */
        NO_APP,
    }

    /** 下载状态标记（与 [Item.stateText] 共用，避免散落魔法字符串） */
    private const val STATE_SUCCESS = "suc"
    private const val STATE_RUNNING = "run"
    private const val STATE_PAUSED = "pause"
    private const val STATE_PENDING = "wait"
    private const val STATE_FAILED = "fail"

    /**
     * 状态 → 本地化文案资源 id（含 [Item.progressPercent] 占位参数）。
     *
     * 状态字符串是**本对象私有**的实现细节，界面层不该自己复制一份 `"suc"` / `"run"` 去判断
     * （改一处漏一处，且编译器不会提醒）。这里把「状态 → 文案」的映射留在数据层，
     * 界面层只负责 `getString(res, item.progressPercent)`（无占位符的状态会忽略多余参数）。
     */
    fun stateLabelRes(item: Item): Int = when (item.stateText) {
        STATE_SUCCESS -> R.string.download_state_success
        STATE_RUNNING -> R.string.download_state_running
        STATE_PAUSED -> R.string.download_state_paused
        STATE_PENDING -> R.string.download_state_pending
        else -> R.string.download_state_failed
    }

    /** FileProvider authority：与 AndroidManifest 中声明保持一致 */
    val FILE_PROVIDER_AUTHORITY: String = BuildConfig.APPLICATION_ID + ".fileprovider"

    /**
     * 把持久化的 `localUri` 转成可安全交给外部应用的 URI。
     *
     * 存量记录存的是 `file://`（API 26-28 的应用专属下载目录），Android 7.0 起
     * 直接外传会抛 FileUriExposedException，故此处统一转成 FileProvider 的
     * `content://`；MediaStore 与存量的 `content://` 记录原样返回。
     *
     * 返回 **null** 表示「**文件在、但本应用无法为它签发授权**」—— 与「文件已不存在」
     * 是两回事。这里绝不退回 file:// 兜底：那会让 FileUriExposedException 被调用方的
     * `runCatching` 吞成 NO_APP，把「交不出去」谎报成「没有应用能打开它」。
     *
     * ⚠️ 该 null 分支**当前不可达**：唯一产生 `file://` 记录的落点是
     * `getExternalFilesDir(DIRECTORY_DOWNLOADS)`（即 `Download/`），已被
     * `file_paths.xml` 的 `ext_downloads` 覆盖。将来若新增共享落点，必须同步白名单 ——
     * `FileProviderPathConsistencyTest` 守着这条不变式。
     * 调用方目前把 null 一并归为 `Result.MISSING`（文件已不存在）；因不可达，
     * 二者尚未区分，若哪天真放开了落点，需先把这一档单独拆出来。
     */
    private fun externalizableUri(context: Context, raw: String): android.net.Uri? {
        val uri = runCatching { android.net.Uri.parse(raw) }.getOrNull() ?: return null
        return when (uri.scheme?.lowercase()) {
            "content" -> uri
            "file" -> runCatching {
                val path = uri.path ?: return@runCatching null
                FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, File(path))
            }.getOrNull()
            else -> uri
        }
    }

    /**
     * 解析下载文件名（Content-Disposition 优先，其次 URL 末段）。
     *
     * 相比早期实现补齐三点：
     *  - 支持 `filename*=UTF-8''百分号编码`（RFC 5987）与 `filename="..."` 两种形式；
     *  - 兜底取 URL 末段时**先剥离查询串**（`a.pdf?token=x` 不再把 token 写进文件名）；
     *  - 统一做百分号解码与非法字符清洗，杜绝路径穿越与非法文件名落盘。
     */
    fun parseFileName(contentDisposition: String?, url: String?): String {
        val fromHeader = contentDisposition?.let { header ->
            RFC5987_FILENAME.find(header)?.groupValues?.getOrNull(1)
                ?: PLAIN_FILENAME.find(header)?.groupValues?.getOrNull(1)?.trim()?.trim('"')
        }?.takeIf { it.isNotBlank() }

        val raw = fromHeader ?: lastPathSegmentOf(url.orEmpty()).orEmpty()

        return sanitizeFileName(percentDecode(raw.ifBlank { DEFAULT_FILE_NAME }))
    }

    /**
     * 可执行 / 安装类扩展名。
     *
     * 为什么单独判定：这类文件运行后会**改变设备状态**（安装应用、执行脚本），
     * 属「不该被一句『不再询问』永久跳过确认」的类别 —— 用户对普通文档的免打扰偏好，
     * 不应顺带把安装包的确认也免掉。故对它们单独强警示。
     *
     * 边界（是取舍过的结论，别照抄别处的清单）：
     *  · `aab` 与 `apk` 同属 Android 应用包，必须在内；
     *  · `xpi` / `crx` **刻意不收**：它们是浏览器扩展包，自身不可执行、下载后不会自动安装；
     *    而本应用安装 .xpi 走的是内核 **Mozilla 签名校验**（未签名一律拒绝）。多套一道
     *    「安装包」警示只会吓到正常下载扩展的用户，并不增加安全性。
     */
    private val EXECUTABLE_EXTENSIONS = setOf(
        "apk", "aab", "apks", "xapk", "apkm", "dex", "ipa",
        "exe", "msi", "msp", "bat", "cmd", "com", "scr",
        "jar", "vbs", "vbe", "ps1", "sh", "deb", "rpm", "dmg", "pkg", "appimage",
    )

    /** 文件名是否为可执行 / 安装类（取最后一段扩展名，大小写不敏感） */
    internal fun isExecutableName(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext.isNotEmpty() && ext in EXECUTABLE_EXTENSIONS
    }

    /** 文件名非法字符（含控制字符）：提到文件级，避免每次下载都重新编译正则 */
    private val ILLEGAL_FILE_CHARS = Regex("""[/\\:*?"<>|\u0000-\u001F]""")

    /** 结尾的空白与点（Windows 会静默丢弃，或造成路径穿越） */
    private val TRAILING_SPACE_OR_DOT = Regex("""[\s.]+$""")

    private const val DEFAULT_FILE_NAME = "download"

    // 注：曾在此处的「大文件路由系统下载器」阈值（BIG_FILE_ROUTE_BYTES）已于
    // 2026-09-26 审查撤销 —— CL 已知的登录态大附件交系统下载器二次 GET 时没有
    // Cookie，会把登录页 HTML 存成目标文件名还报成功（错误内容比中断更糟）。
    // 两全方案（GeckoWebExecutor 带 Cookie 流式）留待 javap/真机验证后另行实施。

    /**
     * RFC 5987：`filename*=UTF-8''%E4%B8%AD.pdf`。
     *
     * ⚠️ `IGNORE_CASE` 不是可选项：**参数名**与**MIME charset 名**都大小写不敏感
     * （RFC 6266 §4.1 沿用 RFC 2616 §2.2 的「参数名不敏感」；charset 按 MIME 规则亦然）。
     * 服务端写 `FILENAME=` / `Filename*=` / `Utf-8''` 全都合法；漏掉这一位就会**静默回退**
     * 到 URL 末段或兜底名 `download` —— 用户拿到名字不对的文件，且没有任何提示。
     * 同仓 `BookmarkHtml.HREF_ATTR` 早已这么处理，此处此前是漏网。
     */
    private val RFC5987_FILENAME =
        Regex("""filename\*\s*=\s*UTF-8''\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE)

    /** 普通形式：filename="a.pdf" / filename=a.pdf（参数名同样大小写不敏感） */
    private val PLAIN_FILENAME =
        Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE)

    /**
     * 百分号解码（自实现，替代 `android.net.Uri.decode`）。
     *
     * 为什么不用框架方法：`android.net.Uri` 在 JVM 单元测试里不可用（方法体是抛异常的桩），
     * 而文件名解析正是最需要单测的边界逻辑（RFC 5987、查询串剥离、路径穿越）。
     * 语义保持一致：只解码 `%XX`，**不**把 `+` 当空格（那是 URLDecoder 的语义）；
     * 非法序列原样保留。
     */
    internal fun percentDecode(raw: String): String {
        if (raw.indexOf('%') < 0) return raw
        val out = java.io.ByteArrayOutputStream(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '%' && i + 2 < raw.length) {
                val hi = Character.digit(raw[i + 1], 16)
                val lo = Character.digit(raw[i + 2], 16)
                if (hi >= 0 && lo >= 0) {
                    out.write((hi shl 4) or lo)
                    i += 3
                    continue
                }
            }
            out.write(ch.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /**
     * 取 URL 路径的最后一段（`Uri.lastPathSegment` 的纯实现）。
     * 先剥离 `#片段` 与 `?查询`，再剥离 `scheme://authority`，最后取 `/` 后剩余部分的末段。
     * 无路径时返回 null，路径恰为 `/` 时返回空串（与框架行为一致，由调用方 ifBlank 兜底）。
     */
    internal fun lastPathSegmentOf(url: String): String? {
        if (url.isBlank()) return null
        val noFragment = url.substringBefore('#')
        val noQuery = noFragment.substringBefore('?')
        val afterScheme = if (noQuery.contains("://")) noQuery.substringAfter("://") else noQuery
        val pathStart = afterScheme.indexOf('/')
        if (pathStart < 0) return null
        return afterScheme.substring(pathStart + 1).substringAfterLast('/')
    }

    /**
     * 一条媒体记录此刻的占位状态。
     *
     * 为什么必须是四态而不是布尔：调用方要据此分别回答两个**判据不同**的问题 ——
     * 「算不算成功」与「能不能删内容」。把「行已消失」与「行还在、内容已写完整、只是没转正」
     * 压成一个 `false`（本函数的前一版就是这么写的），就会在第二种情形下删掉已经写好的内容；
     * 那正是 [SaveOutcome] 注释里批评过的"把两件性质完全不同的事混成一件"——
     * 同一条原则只能有一套写法。
     */
    private enum class PendingState {
        /** 行还在，且 `IS_PENDING` 已为 0（对其它应用已可见） */
        CLEARED,

        /** 行还在，但仍是占位：**内容已完整落盘，只是对外不可见** */
        STILL_PENDING,

        /** 该行已不存在（并发删除等）：没有可删的东西，也算不上成功 */
        GONE,

        /** 查不出来（provider 抛异常 / 返回 null）：不猜、不动手 */
        UNKNOWN,
    }

    /**
     * 查一次事实：`update` 的返回值在个别实现上可能不表示受影响行数，只凭它决定删文件
     * 有**误删已写好内容**的风险，故这里直接读该行的 `IS_PENDING`；查不出来时返回
     * [PendingState.UNKNOWN]，由调用方保持"不动手"。
     */
    private fun pendingState(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
    ): PendingState = runCatching {
        resolver.query(
            uri,
            arrayOf(android.provider.MediaStore.MediaColumns.IS_PENDING),
            null, null, null,
        )?.use { c ->
            when {
                !c.moveToFirst() -> PendingState.GONE
                // 列值 NULL（个别 provider 缺列）**有意**按「仍占位」处理：
                // 后果只是多试一次清零 update（无害），而"当作 GONE"会有误删风险
                !c.isNull(0) && c.getInt(0) == 0 -> PendingState.CLEARED
                else -> PendingState.STILL_PENDING
            }
        } ?: PendingState.UNKNOWN
    }.getOrDefault(PendingState.UNKNOWN)

    /** 一条「转正失败待重试」记录：uri + 登记所需信息 + 入队时间 */
    private data class StalePromotion(
        val uri: String,
        val name: String,
        val mime: String,
        val register: Boolean,
        val at: Long,
    )

    /** 转正失败的行记入待重试队列（[recordsLock] 同锁；JSON 与既有记录同文件） */
    private fun recordStalePromotion(context: Context, entry: StalePromotion) =
        synchronized(recordsLock) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val arr = org.json.JSONArray(prefs.getString(KEY_STALE_PROMOTIONS, "[]"))
            arr.put(
                org.json.JSONObject()
                    .put("uri", entry.uri)
                    .put("name", entry.name)
                    .put("mime", entry.mime)
                    .put("register", entry.register)
                    .put("at", entry.at)
            )
            prefs.edit().putString(KEY_STALE_PROMOTIONS, arr.toString()).apply()
        }

    /**
     * 扫尾：对每条「转正失败」的记录重试一次转正（N13 选项 A）。
     *
     * 触发点：[list()]（下载页每次打开都会跑）。处置与 [writeToDownloads] 的
     * 两段语义对齐：
     *  · CLEARED（已被转正——可能是上一轮扫尾成功但写回丢失）⇒ 补登记并出队；
     *  · STILL_PENDING ⇒ 再试一次清零，成功同上；仍失败 ⇒ 留在队列下次再试；
     *  · GONE（行已被系统回收）⇒ 出队；
     *  · UNKNOWN（查询失败）⇒ **不动**，留在队列下次再核。
     * 超过 [STALE_KEEP_MS] 的条目出队（pending 行此刻也已被系统按 DATE_EXPIRES 回收）。
     *
     * 线程：只在 IO 上下文调用（[list] 已在 IO）；写回在 [recordsLock] 内、
     * 且写前重读，与既有 read-modify-write 同口径。
     */
    private fun sweepStalePromotions(context: Context) {
        // 快路径：锁外先看一眼队列是否为空（绝大多数情况）—— 空就不必进锁，
        // 更不会在锁内做 Binder，主线程的「清空」也就不会在此久等（N5）。
        // getString 带了 "[]" 默认值 ⇒ raw 不会是 null/空白，只判 "[]" 即可。
        // ⚠️ 这是**乐观**判断：真正干活的读取仍在锁内重做一遍（见下）。
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_STALE_PROMOTIONS, "[]")
        if (raw == "[]") return
        // 整体持 recordsLock：读、重试、写回原子化 —— 否则扫尾期间并发「转正失败再登记」
        // 的写入会被本次写回覆盖（与 downloadManagerItems 的锁内重读同一考量）。
        // synchronized 可重入：扫尾内 registerPromoted → registerSavedFile → rememberId
        // 再次拿同一把锁不会有问题。
        synchronized(recordsLock) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val arr = runCatching { org.json.JSONArray(prefs.getString(KEY_STALE_PROMOTIONS, "[]")) }
                .getOrElse {
                    // 队列内容损坏 ⇒ **清掉**它。原先只 `return`：坏值会一直留在 prefs 里，
                    // 每次扫尾都在这里失败并返回，**永远**不会被清理，队列也就永久失效。
                    // 出队是安全的：这里存的只是「转正待重试」的候选，丢了最多是那几行不再自动
                    // 转正，**不会删任何文件**（内容与行都还在）。
                    prefs.edit().remove(KEY_STALE_PROMOTIONS).apply()
                    return
                }
            if (arr.length() == 0) return
            val resolver = context.contentResolver
            val now = System.currentTimeMillis()
            val survivors = org.json.JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val uri = o.optString("uri")
                // 超期：pending 行此刻也已被系统按 DATE_EXPIRES 回收 ⇒ 出队
                if (uri.isBlank() || now - o.optLong("at") > STALE_KEEP_MS) continue
                val parsed = runCatching { android.net.Uri.parse(uri) }.getOrNull() ?: continue
                val values = ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                }
                when (pendingState(resolver, parsed)) {
                    PendingState.CLEARED -> registerPromoted(context, o, parsed)
                    PendingState.STILL_PENDING -> {
                        runCatching { resolver.update(parsed, values, null, null) }
                        // 与 pendingState 同口径（见其 KDoc）：update 的返回值在个别实现上
                        // 不表示受影响行数，**不能**只凭它登记 ⇒ 再查一次，以事实为准。
                        if (pendingState(resolver, parsed) == PendingState.CLEARED) {
                            registerPromoted(context, o, parsed)
                        } else {
                            survivors.put(o) // 没确认清零 ⇒ 留队下次再核
                        }
                    }
                    // GONE：行已消失，内容无从谈起 ⇒ 出队
                    PendingState.GONE -> Unit
                    // UNKNOWN：查不出来 ⇒ 不动手，留在队列下次再核
                    PendingState.UNKNOWN -> survivors.put(o)
                }
            }
            prefs.edit().putString(KEY_STALE_PROMOTIONS, survivors.toString()).apply()
        }
    }

    /** 扫尾转正成功后的登记（仅 register=true 的来源；与落盘成功路径同款） */
    private fun registerPromoted(
        context: Context,
        o: org.json.JSONObject,
        promoted: android.net.Uri,
    ) {
        if (!o.optBoolean("register")) return
        registerSavedFile(
            context, o.optString("name"), o.optString("mime"),
            filePath = queryDataPath(context.contentResolver, promoted),
            fallbackUri = promoted.toString(),
        )
    }

    /**
     * 交给系统下载器（`DownloadManager`）下载。
     *
     * ⚠️ **唯一允许的入口是 [SaveOutcome.NO_BODY]**（内核响应没有 body，手上本就没有内容，
     * 这是唯一一次"回退不会把错内容给用户"的场合）。除此之外**不要**调用它：它是另一次
     * 独立的 GET，只带 UA/Referer，**拿不到**本次响应的 Cookie / 登录态 —— 登录态附件
     * （NAS / 私有云）会被下成登录页 HTML，而 `DownloadManager` 还会把它报成「下载成功」。
     * 本仓库为此撤销过「按体积路由大文件」与「写盘失败即回退」两条设计
     * （见 `MainActivity.startDownload` 的注释）；**别再加第三个调用点**。
     *
     * @return DownloadManager 的任务 id；入队失败（URL scheme 不受支持等）返回 -1
     */
    suspend fun launch(
        context: Context,
        url: String,
        fileName: String,
        referer: String? = null,
    ): Long =
        withContext(Dispatchers.IO) {
            try {
                val safeName = sanitizeFileName(fileName)
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val req = DownloadManager.Request(android.net.Uri.parse(url))
                    .setTitle(safeName)
                    .setDescription(url)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    // 落公共「下载」目录：用户在系统文件管理里可见；同名文件由系统
                    // 自动追加序号（不会覆盖旧文件）。无需任何存储权限。
                    .setDestinationInExternalPublicDir(
                        android.os.Environment.DIRECTORY_DOWNLOADS, safeName
                    )
                // 尽力带上页面上下文（Cookie 拿不到——Gecko 不暴露；Referer/UA 可带）
                req.addRequestHeader("User-Agent", AppHttp.userAgent)
                referer?.let { req.addRequestHeader("Referer", it) }
                val id = dm.enqueue(req)
                rememberId(context, id)
                id
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 取消必须原样抛：本函数是 suspend，调用方（lifecycleScope）在 Activity
                // 销毁时会取消协程。若这里被下方 catch 吞成 -1L，调用方会把「已取消」
                // 误当成「下载失败」而继续走失败分支，在已取消的协程里发起后续动作。
                throw e
            } catch (e: Exception) {
                -1L
            }
        }

    /**
     * 内核响应流落盘的结果。
     *
     * 为什么不是 Boolean：`false` 把两件性质完全不同的事混成一件 ——「内核没给响应体」
     * （手上本就没有内容，此时退回系统下载器是唯一出路）与「写盘失败」（内容在手上但没落
     * 下去，退回系统下载器等于另一次**不带 Cookie** 的 GET）。调用方必须能分辨，故用三态。
     */
    enum class SaveOutcome {
        /** 已落盘并登记 */
        SAVED,

        /** 内核响应没有 body：无内容可写，调用方可退回系统下载器 */
        NO_BODY,

        /**
         * 有内容但没能交付给用户：打不开流 / 写入中途失败（占位行已清）；或**内容已完整落盘
         * 但未能转正**（占位行被保留，约 7 天后由系统回收，见 [writeToDownloads] 的第 ② 段）。
         * 两种情形对调用方是同一件事：这次没有可用文件，且**不要**退回系统下载器。
         */
        FAILED,
    }

    /** 落盘落点：成功时的路径与可回退的 uri（供登记进下载列表） */
    internal data class Placement(val filePath: String?, val fallbackUri: String)

    /**
     * 把内容写进公共「下载」目录 —— 下载文件 / 书签导出 / 存为 PDF 三类产物**共用**本函数。
     *
     * 为什么必须收敛成一处：这段 MediaStore 代码此前在三个地方各写了一份，且已漂成三种语义
     * （两处把「`openOutputStream` 返回 null」判成了成功 ⇒ 0 字节占位行被清零转正成可见文件；
     * 另一处把 IS_PENDING 清零的 `update` 留在 try 外 ⇒ 它抛异常就留下 IS_PENDING=1 的幽灵行）。
     * 同构副本的漂移不会自己停，只有单一实现守得住。
     *
     * 处置分两段，**判据不同、动作也不同**（核心约定，改前先读）：
     *  ① **写流阶段**（能打开流 ∧ 写入无异常）：失败 ⇒ 内容不完整 ⇒ 清掉占位行 / 占位文件，
     *     不留残骸。**这是唯一会删内容的路径。**
     *  ② **转正阶段**（把 `IS_PENDING` 清零）：失败 ⇒ 内容**已经完整落盘**、只是对外不可见
     *     ⇒ **保留**它并返回 null（如实报失败），绝不再删。平台语义支持这么做：`IS_PENDING=1`
     *     期间只有本应用能打开该文件（内容没丢），而 `DATE_EXPIRES` 规定 pending 项默认约
     *     7 天后过期、由系统在 idle 时自动删除（也不会长期堆垃圾）。
     *     代价：这一行不进自管列表，应用内「下载」页看不到、也删不到 —— 刻意的取舍，
     *     与「错误内容比中断更糟」同一套排序（宁可留一个不可见的行，也不删掉可能已写完整的内容）。
     *
     * 线程：内部切到 [Dispatchers.IO]，调用方不必再自己包一层。
     *
     * @param register 是否登记进应用「下载」列表。**没有默认值**是刻意的：默认值会让
     *        调用方「忘记表态」，把用户主动导出的产物（书签 / PDF）变成应用内永远
     *        不可见；每个调用点都必须逐个显式决定。
     * @return 成功返回落点；失败返回 null
     */
    internal suspend fun writeToDownloads(
        context: Context,
        fileName: String,
        mime: String,
        register: Boolean,
        write: (java.io.OutputStream) -> Unit,
    ): Placement? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
                put(
                    android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS
                )
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: return@withContext null
            // 两段分开处置（本函数的核心约定，见上方 KDoc）：
            //  · 写流阶段失败 ⇒ 内容不完整 ⇒ 清占位（**唯一**会删内容的路径）
            //  · 转正阶段失败 ⇒ 内容已完整落盘、只是对外不可见 ⇒ **保留**，如实报失败
            try {
                // ⚠️ 判空不能丢：openOutputStream 返回 null 时**不抛异常**。只看异常会把
                //    「打不开流」当成写入成功，于是 0 字节的占位行被清零转正成可见文件，
                //    界面还报「下载完成 / 已导出 N 条」—— 比留下幽灵行更糟（用户以为拿到了东西）。
                val stream = resolver.openOutputStream(uri) ?: error("no output stream for $uri")
                stream.use(write)
            } catch (e: kotlinx.coroutines.CancellationException) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            } catch (_: Exception) {
                // 写入没跑完：文件是半截的，占位行里没有可用内容 ⇒ 清掉，不让它成为幽灵
                runCatching { resolver.delete(uri, null, null) }
                return@withContext null
            }
            // —— 到这里内容已完整落盘：此后无论转正是否成功，都**不再删**它 ——
            values.clear()
            values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
            val promoted = try {
                if (resolver.update(uri, values, null, null) > 0) {
                    true
                } else {
                    // 行数为 0 有两种可能：那行真没被改到，或个别实现不返回受影响行数。
                    // 不能只凭返回值下结论（更不能就此删文件）⇒ 查一次事实再定。
                    when (pendingState(resolver, uri)) {
                        PendingState.CLEARED -> true
                        // 行还在、只是没转正：内容已完整，**再试一次**清零（幂等、代价极小，
                        // 成功就直接把它从「看不见」救成可见文件）
                        PendingState.STILL_PENDING ->
                            resolver.update(uri, values, null, null) > 0 ||
                                pendingState(resolver, uri) == PendingState.CLEARED
                        // 行已消失 / 查不出来：没有可删的东西，也不再猜
                        PendingState.GONE, PendingState.UNKNOWN -> false
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // 取消：内容已完整，保留（系统按 DATE_EXPIRES 回收）
            } catch (_: Exception) {
                false
            }
            if (!promoted) {
                // 内容完整但没能转正 ⇒ 返回失败，**保留**那一行与文件，并记入「转正失败
                // 待重试」队列：[sweepStalePromotions] 在下次 list() 时重试一次清零，
                // 成功即按来源登记、对用户可见。
                // 平台依据（SDK 源码 MediaStore.java 列文档）：IS_PENDING=1 期间只有本应用
                // 能打开该文件（内容没丢）；pending 项默认约 7 天后过期、由系统在 idle 时
                // 自动删除 ⇒ 队列超期的条目由扫尾出队，不会无限堆积。
                // 口径（与 writeToDownloads 的 @param register 对应）：register = true 的
                // 来源是「暂时不可见」—— 扫尾重试成功即登记、对用户可见；register = false
                // 的来源扫尾也不登记，对应用内是**最终**不可见（文件本身在系统公开
                // 「下载」目录，系统文件管理器可见，不是「文件丢失」）。
                recordStalePromotion(
                    context,
                    StalePromotion(uri.toString(), fileName, mime, register, System.currentTimeMillis()),
                )
                return@withContext null
            }
            Placement(filePath = queryDataPath(resolver, uri), fallbackUri = uri.toString())
        } else {
            // API 26-28：未声明存储权限，写应用专属「下载」目录
            val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                ?: return@withContext null
            // uniqueFile 用 createNewFile 原子占位；写失败必须把占位文件删掉，否则 Downloads
            // 目录里留下 0 字节 / 半截文件，而且从未登记 ⇒ 应用内「下载」页看不到、也删不掉。
            val out = uniqueFile(dir, fileName)
            try {
                out.outputStream().use(write)
            } catch (e: Throwable) {
                runCatching { out.delete() }
                throw e
            }
            // 记录里存 **file://**，而不是 FileProvider 的 content://。
            //
            // 对外交付（打开/分享）由 externalizableUri 统一把 file:// 转成
            // content://（规避 FileUriExposedException），所以存 file:// 不影响使用；
            // 但**删除**必须存 file://：FileProvider 未实现 delete，对它调
            // resolver.delete 会抛 UnsupportedOperationException 并被 runCatching 吞掉，
            // 结果是「记录删了、文件还在」。存 file:// 后 remove 走文件删除分支，真能删掉。
            // （存量 content:// 记录仍能正常打开/分享，只是删除仍受此限制，不做数据迁移。）
            Placement(
                filePath = out.absolutePath,
                fallbackUri = android.net.Uri.fromFile(out).toString(),
            )
        }
    }

    /**
     * 「落盘 + 按来源登记」的**唯一对外出口**（`register = true` 的那一类）。
     *
     * 为什么必须有它：`writeToDownloads` 收到 `register = true` 时**自己并不登记** ——
     * 那个参数只决定「转正失败时入队后，将来重试成功要不要登记」。于是调用方若只调
     * `writeToDownloads(register = true)` 就以为已登记，导出书签 / 存 PDF 的文件会**从不**
     * 出现在系统「下载」与「应用内下载列表」里（只有文件落在公开下载目录，靠系统文件管理器才能找到）。
     * 本函数把「落盘成功 ⇒ 登记」绑成一步，避免每个调用点各写一遍、也避免再漏。
     *
     * @return 落盘成功且已尝试登记返回 true；落盘失败返回 false
     */
    internal suspend fun saveAndRegister(
        context: Context,
        fileName: String,
        mime: String,
        write: (java.io.OutputStream) -> Unit,
    ): Boolean {
        val placement = writeToDownloads(context, fileName, mime, register = true, write = write)
            ?: return false
        registerSavedFile(
            context, fileName, mime,
            filePath = placement.filePath,
            fallbackUri = placement.fallbackUri,
        )
        return true
    }

    /**
     * 直接消费 GeckoView 的内核响应流存盘：
     * Cookie/Referer/登录态都已包含在这次响应里，是登录态附件的唯一可靠路径。
     *
     * 落盘成功后尽力把记录补进系统 DownloadManager（addCompletedDownload），
     * 使文件在系统「下载」通知/应用里可见；若系统不认可（Android 11+ 取不到
     * DATA 路径、addCompletedDownload 失败等），则降级为本应用自管记录，
     * 保证应用内「下载」页仍然可见、可打开、可删除，绝不静默丢失。
     *
     * 落盘本身走 [writeToDownloads]（与书签导出、存为 PDF 同一出口，判据完全一致）。
     *
     * @return [SaveOutcome]；调用方**只应**在 [SaveOutcome.NO_BODY] 时退回系统下载器
     *         （理由见该枚举的说明：其余失败若也退回，等于用一次不带 Cookie 的 GET
     *         去换一个可能完全错误的内容）
     */
    @Suppress("DEPRECATION") // addCompletedDownload 暂无替代 API，仍是登记自有下载的官方途径
    suspend fun saveFromStream(
        context: Context,
        response: org.mozilla.geckoview.WebResponse,
        fileName: String,
    ): SaveOutcome = withContext(Dispatchers.IO) {
        // 外层这次 IO 切换只为「解析 response.headers + 清洗文件名」这一段；
        // 真正落盘的线程由 [writeToDownloads] 自己保证（它内部也切 IO）。
        // 两处都切是刻意的：出口不假设调用方已经站在 IO 线程上。
        val input = response.body ?: return@withContext SaveOutcome.NO_BODY
        val safeName = sanitizeFileName(fileName)
        // HTTP 头名大小写不敏感，统一查找 Content-Type
        val mime = response.headers.entries
            .firstOrNull { it.key.equals("content-type", ignoreCase = true) }?.value
            ?: android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(safeName.substringAfterLast('.', ""))
            ?: "application/octet-stream"
        try {
            // input.use 保证内核给的响应体在任何早退路径上都会被关闭
            // （insert 失败时压根走不到 write 里，那里没有机会关它）。
            input.use { body ->
                val saved = saveAndRegister(context, safeName, mime) { out -> body.copyTo(out) }
                if (!saved) return@withContext SaveOutcome.FAILED
                SaveOutcome.SAVED
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 同 launch()：取消要原样传播。此处若吞成 FAILED，调用方 startDownload 会在
            // 已取消的协程里接着弹「下载失败」，把「用户自己退出」说成「下载出错」。
            throw e
        } catch (_: Exception) {
            SaveOutcome.FAILED
        }
    }

    /**
     * 落盘成功后补登记：
     *  - [filePath] 非空 → addCompletedDownload 交给系统 DownloadManager（系统内可见）；
     *  - 系统登记失败/路径不可得 → 记入自管列表，应用内「下载」页仍可列出。
     */
    @Suppress("DEPRECATION") // addCompletedDownload 暂无替代 API，仍是登记自有下载的官方途径
    private fun registerSavedFile(
        context: Context,
        safeName: String,
        mime: String,
        filePath: String?,
        fallbackUri: String,
    ) {
        val registered = filePath?.let { path ->
            runCatching {
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val id = dm.addCompletedDownload(
                    safeName, safeName, true, mime, path, java.io.File(path).length(), true
                )
                if (id >= 0) {
                    rememberId(context, id)
                    true
                } else false
            }.getOrDefault(false)
        } ?: false
        if (!registered) rememberManaged(context, Managed(fallbackUri, safeName, mime))
    }

    /** 查询 MediaStore 条目对应的真实路径（addCompletedDownload 需要路径而非 Uri） */
    @Suppress("DEPRECATION") // Android 11+ 常返回 null——返回 null 时走自管降级，属预期路径
    private fun queryDataPath(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
    ): String? =
        runCatching {
            resolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()

    /** 同目录下不重复的文件名（a.ext → a (1).ext）。原子占位式：并发下载不会互相截断 */
    private fun uniqueFile(dir: java.io.File, name: String): java.io.File {
        // createNewFile 是原子操作：先占位者得。旧的「exists() 检查 → 创建」两步间
        // 有 TOCTOU 窗口 —— API 26-28 两个并发同名下载会选中同一路径，后者截断
        // 前者正在写的文件造成损坏（API 29+ 走 MediaStore 由系统去重，无此问题）。
        var f = java.io.File(dir, name)
        if (!f.exists() && f.createNewFile()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").takeIf { it.isNotBlank() }?.let { ".$it" } ?: ""
        var i = 1
        while (true) {
            f = java.io.File(dir, "$base ($i)$ext")
            if (f.createNewFile()) return f
            i++
        }
    }

    /** 文件名清洗：防路径穿越 / 非法字符注入，仅保留安全的基本文件名 */
    internal fun sanitizeFileName(raw: String): String {
        val cleaned = raw
            .replace(ILLEGAL_FILE_CHARS, "_")
            // 结尾的点与空格在部分文件系统（exFAT/SD 卡）上非法，一并去掉
            .replace(TRAILING_SPACE_OR_DOT, "")
            .trim()
            .let { if (it.length > 150) it.take(150) else it }
        return cleaned.ifBlank { DEFAULT_FILE_NAME }
    }

    // ------------------------------------------------------------- 查询 / 打开 / 删除

    suspend fun list(context: Context): List<Item> = withContext(Dispatchers.IO) {
        // 扫尾：上次「转正失败待重试」的条目先试一次转正（成功即登记进下载列表）
        sweepStalePromotions(context)
        buildList {
            addAll(downloadManagerItems(context))
            addAll(managedItems(context))
        }
    }

    /**
     * 单条记录的存在性判定。
     *
     * 为什么必须是三态而不是 Boolean：`query` **抛异常**（provider 抖动、备份恢复期、Binder 抖动）
     * 与「确实查不到这一行」在调用方看来都是「没拿到东西」，可处置**完全相反** ——
     * 前者必须**保留**记录（下次再试），后者才可以摘除。压成一个布尔，一次瞬时故障就会被
     * 当成「已失效」写上删除，于是**永久丢记录**：文件还躺在系统下载目录里，本应用却再也
     * 列不出来，而且没有任何提示。
     * 与写入侧的 `PendingState` 四态是同一思路（见 [writeToDownloads]）。
     */
    private enum class Presence { ALIVE, GONE, UNKNOWN }

    /** 系统 DownloadManager 里的记录 */
    private fun downloadManagerItems(context: Context): List<Item> {
        val ids = rememberIds(context)
        if (ids.isEmpty()) return emptyList()
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val gone = mutableListOf<Long>()
        val items = ids.mapNotNull { id ->
            val (item, presence) = probeDownloadManager(dm, id)
            if (presence == Presence.GONE) gone += id
            item
        }
        // 清理已被系统下载器遗忘的记录，避免 id 列表只增不减。
        // 只摘掉**本次确认已失效**（GONE）的 id；UNKNOWN（查询失败）一律保留、下次再核。
        // 且在锁内基于最新列表重算 —— 否则会把清理期间并发新增的 id 一起覆盖掉（见 [recordsLock]）。
        if (gone.isNotEmpty()) {
            synchronized(recordsLock) {
                writeIds(context, rememberIds(context).filterNot { it in gone })
            }
        }
        return items
    }

    /**
     * 查一条系统下载器记录。
     * @return 可展示项（不存在 / 查询失败时为 null）与其 [Presence]
     */
    private fun probeDownloadManager(dm: DownloadManager, id: Long): Pair<Item?, Presence> =
        try {
            dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                // 游标能开、但一行都没有 ⇒ 下载器确实已把它遗忘，可以摘除
                if (!c.moveToFirst()) return null to Presence.GONE
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val title = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)).orEmpty()
                val bytes = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val localUri = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                val (stateText, percent) = when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> STATE_SUCCESS to 100
                    DownloadManager.STATUS_RUNNING -> {
                        val p = if (total > 0) ((bytes * 100) / total).toInt() else 0
                        STATE_RUNNING to p
                    }
                    DownloadManager.STATUS_PAUSED -> STATE_PAUSED to 0
                    DownloadManager.STATUS_PENDING -> STATE_PENDING to 0
                    else -> STATE_FAILED to 0
                }
                Item(id, title, stateText, percent, localUri) to Presence.ALIVE
            }
        } catch (_: Exception) {
            // 查询**抛异常** = 什么也没探测到，不等于「不存在」：保留记录，下次 [list] 再试
            null to Presence.UNKNOWN
        }

    /** 本应用自管记录（MediaStore content uri / 应用目录 file uri）；**确认失效**的才剔除 */
    private fun managedItems(context: Context): List<Item> {
        val records = rememberManaged(context)
        if (records.isEmpty()) return emptyList()
        val resolver = context.contentResolver
        val kept = mutableListOf<Managed>()
        val items = records.mapNotNull { m ->
            val uri = runCatching { android.net.Uri.parse(m.uri) }.getOrNull() ?: return@mapNotNull null
            when (managedPresence(resolver, uri)) {
                // ALIVE 与 UNKNOWN **一视同仁**：记录是「写入成功之后」才登记的，一次查询失败
                // 不足以否定它。此前把「查询失败」与「确实不存在」压成一个 Boolean ⇒ 查询一抖
                // 记录就永久消失（文件还在系统下载目录里）。宁可多显示一次，也不要丢记录。
                Presence.ALIVE, Presence.UNKNOWN -> {
                    kept += m
                    Item(-1L, m.title, "suc", 100, m.uri, m.mime, managed = true)
                }
                Presence.GONE -> null
            }
        }
        // 只摘掉**确认失效**的记录；ALIVE / UNKNOWN 都保留（[kept] 同时决定「保留」与「展示」）
        val keptSet = kept.toSet()
        val dead = records.filterNot { it in keptSet }
        if (dead.isNotEmpty()) {
            // 同 [downloadManagerItems]：锁内基于最新列表只摘失效项，避免覆盖并发新增
            synchronized(recordsLock) {
                writeManaged(context, rememberManaged(context).filterNot { it in dead })
            }
        }
        return items
    }

    /** 判定一条自管记录的 URI 是否仍指向真实文件（三态，理由见 [Presence]） */
    private fun managedPresence(resolver: android.content.ContentResolver, uri: android.net.Uri): Presence =
        when (uri.scheme) {
            "content" -> {
                // 三态不许压成两态：`query` 返回 **null** 与「抛异常」同为「没探测到」，
                // 只有「游标能开且确实 0 行」才是 GONE。此前 `?.use { … } == true` 把 null
                // 折叠进 GONE ⇒ 一次 provider 抖动就把用户记录永久摘除（文件还在磁盘上）。
                val cursor = try {
                    resolver
                        .query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                } catch (_: Exception) {
                    null // 抛异常 = 没探测到
                }
                if (cursor == null) {
                    Presence.UNKNOWN // 返回 null 也是「没探测到」—— 与抛异常同类
                } else {
                    cursor.use { if (it.moveToFirst()) Presence.ALIVE else Presence.GONE }
                }
            }
            "file" -> if (java.io.File(uri.path.orEmpty()).exists()) Presence.ALIVE else Presence.GONE
            else -> Presence.GONE
        }

    /**
     * [open] / [share] 的共享前置：就绪判定 + 解析可对外交付的 URI 与 MIME。
     *
     * 为什么抽出来并放到 IO 线程：[resolvedUri] 对自管 file:// 记录要做 `File.exists()`、
     * 经 FileProvider 做路径换算，[share] 还要向 DownloadManager 查 MIME —— 都是
     * 磁盘 / Binder I/O。debug 构建开启了 StrictMode detectAll，主线程 I/O 会被点名，
     * 故统一在此完成，调用方恢复主线程后再真正唤起外部应用。
     */
    private class ExternalTarget(
        /** 非 null 表示可直接返回的分级结果（未下完 / 已失败 / 文件缺失） */
        val earlyResult: Result?,
        val uri: android.net.Uri?,
        val mime: String?,
    )

    private fun prepareExternal(context: Context, item: Item, forShare: Boolean): ExternalTarget {
        readiness(item).takeIf { it != Result.OK }?.let { return ExternalTarget(it, null, null) }
        val uri = resolvedUri(context, item) ?: return ExternalTarget(Result.MISSING, null, null)
        val mime = when {
            // open 沿用原行为：只看记录自带 MIME
            !forShare -> item.mime ?: "*/*"
            item.managed -> item.mime ?: "*/*"
            else -> {
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                runCatching { dm.getMimeTypeForDownloadedFile(item.id) }.getOrNull()
                    ?: (item.mime ?: "*/*")
            }
        }
        return ExternalTarget(null, uri, mime)
    }

    /**
     * 打开下载文件。
     * 返回分级结果而非 Boolean：调用方据此给出准确提示（未下完 / 下载失败 / 文件已失效 / 无可用应用），
     * 不再像早期实现那样一律提示"无法打开该文件"而让用户无从判断。
     *
     * `suspend`：前置检查在 IO 线程完成，`startActivity` 在调用方（lifecycleScope，主线程）恢复后执行。
     */
    suspend fun open(context: Context, item: Item): Result {
        val target = withContext(Dispatchers.IO) { prepareExternal(context, item, forShare = false) }
        target.earlyResult?.let { return it }
        val uri = target.uri ?: return Result.MISSING
        return viewIntent(context, uri, target.mime ?: "*/*")
    }

    /** 分享下载文件；结果分级与线程约定同 [open] */
    suspend fun share(context: Context, item: Item): Result {
        val target = withContext(Dispatchers.IO) { prepareExternal(context, item, forShare = true) }
        target.earlyResult?.let { return it }
        val uri = target.uri ?: return Result.MISSING
        return runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = target.mime ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, context.getString(R.string.share_via)))
            Result.OK
        }.getOrDefault(Result.NO_APP)
    }

    /** 下载是否已就绪：未完成 / 已失败都在此处拦下并给出准确原因 */
    private fun readiness(item: Item): Result = when {
        item.managed -> Result.OK
        item.stateText == STATE_SUCCESS -> Result.OK
        item.stateText == STATE_FAILED -> Result.FAILED
        else -> Result.NOT_READY
    }

    /**
     * 取得可对外交付的 URI：
     *  - DownloadManager 记录 → 系统内容提供器 URI；
     *  - 自管记录 → content:// 原样使用，file:// 经 FileProvider 转换（并先校验文件仍在）。
     */
    private fun resolvedUri(context: Context, item: Item): android.net.Uri? {
        if (!item.managed) {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            return runCatching { dm.getUriForDownloadedFile(item.id) }.getOrNull()
        }
        val raw = item.localUri ?: return null
        val parsed = runCatching { android.net.Uri.parse(raw) }.getOrNull() ?: return null
        // 文件已被清理（外部删除 / 系统回收）时给出 MISSING，而不是把坏 URI 交给外部应用
        if (parsed.scheme.equals("file", ignoreCase = true) &&
            !File(parsed.path.orEmpty()).exists()
        ) {
            return null
        }
        return externalizableUri(context, raw)
    }

    private fun viewIntent(context: Context, uri: android.net.Uri, mime: String): Result =
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            Result.OK
        }.getOrDefault(Result.NO_APP)

    /**
     * 删除下载记录与文件。
     *
     * `suspend` + IO：对系统下载器 / 内容提供器的删除与 `File.delete()` 都是磁盘级操作，
     * 不能留在主线程（此前由 LibraryActivity 在 lifecycleScope 主线程协程里直接调用，
     * StrictMode 会点名磁盘写）。
     *
     * ⚠️ 删除结果**必须确认**再摘记录：此前 `File.delete()` 的布尔返回值被忽略、`resolver.delete`
     * 的异常被 `runCatching` 吞掉，之后**无条件** `dropManaged`，于是「记录删了、文件还在」
     * —— 磁盘上的孤儿文件再也无法从应用内删掉（本文件 :476-483 的注释刚批评过这个失败模式）。
     *
     * @return 是否**确认已删掉**。false 表示文件仍在（或探测不到）⇒ **记录被保留**，调用方须如实提示。
     */
    suspend fun remove(context: Context, item: Item): Boolean = withContext(Dispatchers.IO) {
        if (!item.managed) {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val removed = runCatching { dm.remove(item.id) }.getOrDefault(0)
            if (removed > 0) return@withContext true
            // remove 返回 0 的两种可能：「本来就不在了」（用户已从系统下载 UI 删过，或
            // 重复调用）与「真的没删掉」。复核一次事实再定（与 managed 分支同构）：
            //  · GONE ⇒ 与「已删除」对用户是同一结果，如实返回成功——否则会弹
            //    「删除失败」、条目却随即被 list() 收掉，反馈自相矛盾（审查 N23-①）；
            //  · ALIVE ⇒ 真没删掉 ⇒ 如实返回失败；UNKNOWN ⇒ 不猜，按失败处理。
            return@withContext probeDownloadManager(dm, item.id).second == Presence.GONE
        }
        val uri = runCatching { android.net.Uri.parse(item.localUri) }.getOrNull()
            ?: return@withContext false
        val deleted = when (uri.scheme) {
            // 返回删掉的行数：0 意味着这一行本来就不在（可能已被系统清掉）
            "content" -> runCatching { context.contentResolver.delete(uri, null, null) }.getOrDefault(0) > 0
            // delete() 失败**不抛异常**、只返回 false —— 此前忽略它，于是删不掉也照样摘记录
            "file" -> java.io.File(uri.path.orEmpty()).delete()
            else -> false
        }
        // 删除调用没成功时**再复核一次事实**（与 writeToDownloads 里"再查一次"同一口径）：
        //  · 文件确实已经不在了（用户从系统文件管理器删掉、或 MediaStore 行已消失）⇒ 算删成功，
        //    记录该摘就摘，否则会留下一条**永远删不掉**的幽灵记录；
        //  · 文件仍在 / 探测不到 ⇒ **保留记录**并如实回报失败。
        if (!deleted && managedPresence(context.contentResolver, uri) != Presence.GONE) {
            return@withContext false
        }
        dropManaged(context, uri.toString())
        true
    }

    /**
     * 清空全部下载**记录**（系统下载器 id 列表 + 自管条目 + 转正待重试队列）。
     *
     * 与逐行 [remove] 的关键区别：**不删除磁盘上的文件**。下载文件是用户资产，
     * 一键清空若连文件一起删，误操作不可逆；这里只让「下载」页回到空态。
     * 需要删除具体文件时仍走逐行 remove。
     *
     * `suspend` + IO：等 [recordsLock] 与 SharedPreferences 落盘都是磁盘级操作，
     * 而持锁方（[sweepStalePromotions]）锁内会做 Binder（query / update /
     * addCompletedDownload）⇒ **等锁的人绝不能是主线程**（N5）。
     */
    suspend fun clearRecords(context: Context) = withContext(Dispatchers.IO) {
        synchronized(recordsLock) {
            // 与 rememberId/rememberManaged 同锁：否则「清空」与并发下载的完成登记
            // 读-改-写交错时，在途写入会用旧列表把刚清掉的记录整体写回
            // 逐键清，而不是点名两个：新增键只要登记进 [ALL_RECORD_KEYS] 就必然被清到
            // （此前只 remove 了 KEY_IDS/KEY_MANAGED，漏了后加的 KEY_STALE_PROMOTIONS
            // ⇒ 「清空下载记录」之后，转正待重试队列仍带着旧条目复活）。
            val e = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            ALL_RECORD_KEYS.forEach { e.remove(it) }
            e.apply()
        }
    }

    // ------------------------------------------------------------- 记录持久化

    private fun rememberId(context: Context, id: Long) = synchronized(recordsLock) {
        writeIds(context, rememberIds(context) + id)
    }

    /** 覆盖写入 id 列表（调用方负责持锁，或确保无并发） */
    private fun writeIds(context: Context, ids: List<Long>) {
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_IDS, arr.toString()).apply()
    }

    private fun rememberIds(context: Context): List<Long> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_IDS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getLong(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun rememberManaged(context: Context, entry: Managed) = synchronized(recordsLock) {
        writeManaged(context, rememberManaged(context) + entry)
    }

    private fun dropManaged(context: Context, uri: String) = synchronized(recordsLock) {
        writeManaged(context, rememberManaged(context).filterNot { it.uri == uri })
    }

    private fun rememberManaged(context: Context): List<Managed> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MANAGED, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Managed(o.optString("uri"), o.optString("title"), o.optString("mime", "").ifBlank { null })
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeManaged(context: Context, list: List<Managed>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("uri", it.uri)
                    .put("title", it.title)
                    .put("mime", it.mime ?: "")
            )
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_MANAGED, arr.toString()).apply()
    }
}
