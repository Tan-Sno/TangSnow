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

    /** RFC 5987：filename*=UTF-8''%E4%B8%AD.pdf（charset 部分允许任意大小写与引号） */
    private val RFC5987_FILENAME =
        Regex("""filename\*\s*=\s*(?:UTF-8|utf-8)''\s*"?([^";]+)"?""")

    /** 普通形式：filename="a.pdf" / filename=a.pdf */
    private val PLAIN_FILENAME =
        Regex("""filename\s*=\s*"?([^";]+)"?""")

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

        /** 有内容但没能落盘（打不开流 / 写入中途失败 / 占位行清零没改到行） */
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
     * 成功判据（三条缺一不可）：流能打开 ∧ 写入无异常 ∧ IS_PENDING 清零的 update 至少改到 1 行。
     * 任一不成立都会清掉占位行 / 占位文件并返回 null —— 绝不留下「用户看不见又删不掉」的残骸。
     *
     * 线程：内部切到 [Dispatchers.IO]，调用方不必再自己包一层。
     *
     * @return 成功返回落点；失败返回 null
     */
    internal suspend fun writeToDownloads(
        context: Context,
        fileName: String,
        mime: String,
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
            var written = false
            try {
                // ⚠️ 判空不能丢：openOutputStream 返回 null 时**不抛异常**。只看异常会把
                //    「打不开流」当成写入成功，于是 0 字节的占位行被清零转正成可见文件，
                //    界面还报「下载完成 / 已导出 N 条」—— 比留下幽灵行更糟（用户以为拿到了东西）。
                val stream = resolver.openOutputStream(uri) ?: error("no output stream for $uri")
                stream.use(write)
                values.clear()
                values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                // 行数为 0 = 那一行没被改到（并发删除等）：IS_PENDING 仍为 1 的行对其他应用
                // 不可见，报成功等于报了一个用户拿不到的文件，故同样按失败处理并清占位。
                written = resolver.update(uri, values, null, null) > 0
                if (!written) {
                    // 但**不能只凭返回值下结论**：个别实现可能不返回受影响行数。删文件之前先查
                    // 一次事实（该行的 IS_PENDING 是否真的清掉了）—— 免得把一次其实已经写成功的
                    // 文件删掉，那比留下幽灵行更糟（用户的下载凭空消失）。
                    written = runCatching {
                        resolver.query(
                            uri,
                            arrayOf(android.provider.MediaStore.MediaColumns.IS_PENDING),
                            null, null, null,
                        )?.use { c -> c.moveToFirst() && !c.isNull(0) && c.getInt(0) == 0 } == true
                    }.getOrDefault(false)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            } catch (_: Exception) {
                written = false
            }
            if (!written) {
                runCatching { resolver.delete(uri, null, null) }
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
                val placement = writeToDownloads(context, safeName, mime) { out ->
                    body.copyTo(out)
                } ?: return@withContext SaveOutcome.FAILED
                registerSavedFile(
                    context, safeName, mime,
                    filePath = placement.filePath,
                    fallbackUri = placement.fallbackUri,
                )
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
        buildList {
            addAll(downloadManagerItems(context))
            addAll(managedItems(context))
        }
    }

    /** 系统 DownloadManager 里的记录 */
    private fun downloadManagerItems(context: Context): List<Item> {
        val ids = rememberIds(context)
        if (ids.isEmpty()) return emptyList()
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val alive = mutableListOf<Long>()
        val items = ids.mapNotNull { id ->
            try {
                val c = dm.query(DownloadManager.Query().setFilterById(id))
                c.use {
                    if (!it.moveToFirst()) return@mapNotNull null
                    alive += id
                    val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val title = it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)).orEmpty()
                    val bytes = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val localUri = it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
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
                    Item(id, title, stateText, percent, localUri)
                }
            } catch (e: Exception) {
                null
            }
        }
        // 清理已被系统下载器遗忘的记录，避免 id 列表只增不减。
        // 只摘掉**本次确认已失效**的那些 id，且在锁内基于最新列表重算 ——
        // 否则会把清理期间并发新增的 id 一起覆盖掉（见 [recordsLock]）。
        val dead = ids.filterNot { it in alive }
        if (dead.isNotEmpty()) {
            synchronized(recordsLock) {
                writeIds(context, rememberIds(context).filterNot { it in dead })
            }
        }
        return items
    }

    /** 本应用自管记录（MediaStore content uri / 应用目录 file uri）；失效的自动剔除 */
    private fun managedItems(context: Context): List<Item> {
        val records = rememberManaged(context)
        if (records.isEmpty()) return emptyList()
        val resolver = context.contentResolver
        val alive = mutableListOf<Managed>()
        val items = records.mapNotNull { m ->
            val uri = runCatching { android.net.Uri.parse(m.uri) }.getOrNull() ?: return@mapNotNull null
            val exists = when (uri.scheme) {
                "content" -> runCatching {
                    resolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                        ?.use { it.moveToFirst() } == true
                }.getOrDefault(false)
                "file" -> java.io.File(uri.path.orEmpty()).exists()
                else -> false
            }
            if (!exists) return@mapNotNull null
            alive += m
            Item(-1L, m.title, "suc", 100, m.uri, m.mime, managed = true)
        }
        val aliveSet = alive.toSet()
        val dead = records.filterNot { it in aliveSet }
        if (dead.isNotEmpty()) {
            // 同 [downloadManagerItems]：锁内基于最新列表只摘失效项，避免覆盖并发新增
            synchronized(recordsLock) {
                writeManaged(context, rememberManaged(context).filterNot { it in dead })
            }
        }
        return items
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
     */
    suspend fun remove(context: Context, item: Item) = withContext(Dispatchers.IO) {
        if (!item.managed) {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            runCatching { dm.remove(item.id) }
            return@withContext
        }
        val uri = runCatching { android.net.Uri.parse(item.localUri) }.getOrNull()
            ?: return@withContext
        when (uri.scheme) {
            "content" -> runCatching { context.contentResolver.delete(uri, null, null) }
            "file" -> runCatching { java.io.File(uri.path.orEmpty()).delete() }
        }
        dropManaged(context, uri.toString())
    }

    /**
     * 清空全部下载**记录**（系统下载器 id 列表 + 自管条目）。
     *
     * 与逐行 [remove] 的关键区别：**不删除磁盘上的文件**。下载文件是用户资产，
     * 一键清空若连文件一起删，误操作不可逆；这里只让「下载」页回到空态。
     * 需要删除具体文件时仍走逐行 remove。
     */
    fun clearRecords(context: Context) = synchronized(recordsLock) {
        // 与 rememberId/rememberManaged 同锁：否则「清空」与并发下载的完成登记
        // 读-改-写交错时，在途写入会用旧列表把刚清掉的记录整体写回
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_IDS)
            .remove(KEY_MANAGED)
            .apply()
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
