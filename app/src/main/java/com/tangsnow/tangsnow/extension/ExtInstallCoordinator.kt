package com.tangsnow.tangsnow.extension

import android.content.Context
import com.tangsnow.tangsnow.data.AppHttp
import com.tangsnow.tangsnow.util.awaitResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 扩展安装统一通道（三路：目录 / 官方链接 / 本地 .xpi）。
 *
 * ## 安装调用与「已验证可安装成功」的版本逐字一致
 * 远端来源一律走内核原生通道：
 * ```
 * controller.install(官方直链, INSTALLATION_METHOD_MANAGER)
 * ```
 * 下载、签名校验、安装确认**全部交给内核**——这正是旧版本能够装成功的路径。
 * 本地导入来源才用 `install(file://, INSTALLATION_METHOD_FROM_FILE)`，同样与旧版一致。
 *
 * ## 现行链路（由真机实测决定，不是猜的）
 * 1. **首选**：把官方直链交给内核 `install(url, INSTALLATION_METHOD_MANAGER)`，
 *    本步受 [KERNEL_STEP_MS] 单独封顶；
 * 2. **兜底**：本步未出结果时，用 [AppHttp] 自建下载官方 xpi 到 `cacheDir/exts/`
 *    （按 [Source.candidateUrls] 逐候选尝试，每个 `callTimeout(45s)`），
 *    再 `install(file://, INSTALLATION_METHOD_FROM_FILE)`（Mozilla 签名仍由内核校验，合规不变）。
 *
 * ## 真正踩过的坑是「地址形式」，不是安装通道
 * AMO 有两种供包地址，真机实测差异极大：
 *  - `…/downloads/latest/<slug>/addon-<id>-latest.xpi` —— AMO 直接供包，**可下载**；
 *  - API 返回的 `…/downloads/file/<id>/<name>.xpi` —— 会跳 CDN，某些网络下**黑洞**
 *    （连接既不返回也不断开）⇒ 内核下载零回应、自建下载连超时都出不来。
 * 因此 [ExtensionCatalog.bestUrl] **优先 latest 形式**，API 形式仅作最后备选。
 *
 * ## 三条必须守住的排障纪律（血泪换来的）
 * 1. **排查网络问题必须用出问题的那台设备验证** —— PC 端 curl 通不代表手机能通；
 * 2. 黑洞/慢滴响应用 `readTimeout` 拦不住，必须 `callTimeout`；阻塞式 `execute()`
 *    无法被 `withTimeout` 打断；
 * 3. **链路上每一步都必须有独立超时** —— 某一步无上限就会吃光总预算，
 *    让后面已验证可用的兜底永远轮不到（表现为"等满时长才失败、但其实能装上"）。
 *
 * > ⚠️ 以后再动这一块：**先拿真机实测证据**，不要凭推测切换安装通道。
 */
class ExtInstallCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    /** 安装来源：三路统一的输入抽象 */
    sealed class Source {
        /** 同一来源的幂等键（并发去重） */
        abstract val key: String

        /** 用于界面提示的来源名 */
        abstract val label: String

        /** 官方直链；本地文件来源为 null */
        abstract val remoteUrl: String?

        /** 已就绪的本地包；远端来源为 null */
        abstract val localFile: File?

        /** 目录条目对应的 slug；仅目录来源非空（供界面的「安装中」集合使用） */
        open val slug: String? = null

        /**
         * 自建下载可依次尝试的官方直链（按优先级）。
         *
         * 为什么要"多个候选"：AMO 有两种供包地址——
         * 目录里拼出的 `…/downloads/latest/<slug>/addon-<id>-latest.xpi`（AMO 直接供包），
         * 与 API 返回的 `…/downloads/file/<id>/<name>.xpi`（常跳 CDN）。
         * 实测某些网络下后者会**慢滴/黑洞**（连着既不返回也不断开），而前者直连可用，
         * 故 **latest 形式优先**、API 形式作备选。
         */
        open val candidateUrls: List<String> = emptyList()

        /** 目录「获取」 */
        class Catalog(val entry: ExtensionCatalog.Entry) : Source() {
            override val key: String = catalogKey(entry.slug)
            override val label: String get() = entry.name
            override val remoteUrl: String get() = ExtensionCatalog.bestUrl(entry)
            override val localFile: File? = null
            override val slug: String get() = entry.slug
            override val candidateUrls: List<String>
                get() = listOfNotNull(entry.xpiUrl, ExtensionCatalog.resolvedUrl(entry)).distinct()
        }

        /** 自定义官方链接安装 */
        class Remote(override val label: String, override val remoteUrl: String) : Source() {
            override val key: String = "__remote__"
            override val localFile: File? = null
            override val candidateUrls: List<String> get() = listOf(remoteUrl)
        }

        /** 本地 .xpi 导入（已完成复制与基础校验，直接安装） */
        class LocalFile(override val label: String, val file: File) : Source() {
            override val key: String = "__localfile__"
            override val remoteUrl: String? = null
            override val localFile: File get() = file
        }
    }

    interface Callback {
        fun onSuccess(source: Source, ext: WebExtension?)

        fun onFailure(source: Source, err: Throwable?)
    }

    private val jobs = activeJobs

    /** 该来源是否已有在途安装（并发去重） */
    fun isWorking(key: String): Boolean = jobs[key]?.isActive == true

    /**
     * 发起一次安装。同一来源已在安装中时返回 `false`，调用方据此提示「正在进行中」。
     * 回调一律在主线程投递；**成功与失败必然恰好回调一次**。
     */
    fun install(source: Source, controller: WebExtensionController, cb: Callback): Boolean {
        if (isWorking(source.key)) {
            return false
        }
        // LAZY 启动：先登记 job 再启动，避免协程体在登记前跑完而留下残留条目
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                withTimeout(TOTAL_TIMEOUT_MS) {
                    onDone(source, cb, performInstall(source, controller))
                }
            } catch (e: TimeoutCancellationException) {
                settle(source.key)
                withContext(Dispatchers.Main) { cb.onFailure(source, InstallTimeoutException()) }
            } catch (e: CancellationException) {
                // 宿主销毁：静默收尾（孤儿态由列表页 onResume 的自愈逻辑清理）
                settle(source.key)
                throw e
            } catch (e: Throwable) {
                settle(source.key)
                withContext(Dispatchers.Main) { cb.onFailure(source, e) }
            }
        }
        jobs[source.key] = job
        job.start()
        return true
    }

    private suspend fun onDone(source: Source, cb: Callback, ext: WebExtension?) {
        settle(source.key)
        withContext(Dispatchers.Main) { cb.onSuccess(source, ext) }
    }

    /** 宿主销毁时调用：取消全部在途安装 */
    fun cancelAll() {
        jobs.values.forEach { it.cancel() }
        jobs.keys.toList().forEach { settle(it) }
    }

    /**
     * 用应用自建网络栈把官方 xpi 下到缓存：**按优先级依次尝试 [Source.candidateUrls]**，
     * 全部失败才返回 null（调用方据此回退内核内置通道）。
     * 只接受 addons.mozilla.org 官方域名——与"扩展只走 Mozilla 官方源"的产品口径一致。
     *
     * ⚠️ 这里必须用**带 callTimeout 的 client**：实测某些网络对下载地址做"慢滴/黑洞"
     * （连着既不返回也不断开），此时 OkHttp 的 readTimeout 不会触发，而 `withTimeout`
     * 又无法打断阻塞式 `execute()` —— 结果就是"永远停在安装中、连超时弹窗都没有"。
     */
    private suspend fun downloadPackage(urls: List<String>): File? = withContext(Dispatchers.IO) {
        if (urls.isEmpty()) return@withContext null
        val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        // 只清**陈旧**残包，不做无条件清空。
        //
        // 为什么：不同 slug 的安装作业互不相斥（作业表按 key 去重），可以并发。
        // 早先这里无条件删除所有 `install-` 前缀文件，于是 A 的清理会把 B **正在写入**的
        // 临时包删掉 —— B 随即在体积校验处失败并跳到下一个候选，最终报「官方直链与自建
        // 下载均不可用」，用户看到的是"这次装不上"，重试又好了（极难复现）。
        // 阈值取得远大于单次安装的最长耗时（TOTAL_TIMEOUT_MS = 150s），
        // 既能清掉上次崩溃留下的残包，又不会碰到在途文件。
        val staleBefore = System.currentTimeMillis() - STALE_TMP_MS
        dir.listFiles { f -> f.name.startsWith(TMP_PREFIX) && f.lastModified() < staleBefore }
            ?.forEach { runCatching { it.delete() } }
        val client = AppHttp.client.newBuilder()
            .callTimeout(DOWNLOAD_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
        for ((index, url) in urls.withIndex()) {
            val n = index + 1
            if (!url.startsWith("https://addons.mozilla.org/", ignoreCase = true)) {
                continue
            }
            val out = File(dir, "$TMP_PREFIX${System.currentTimeMillis()}-$index.xpi")
            try {
                client.newCall(AppHttp.get(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    resp.body.byteStream().use { input ->
                        out.outputStream().buffered().use { sink ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val r = input.read(buf)
                                if (r <= 0) break
                                done += r
                                // 体积闸门：官方扩展包远小于此，超出即为异常响应
                                if (done > MAX_XPI_BYTES) throw IllegalStateException("体积异常，已中止")
                                sink.write(buf, 0, r)
                            }
                        }
                    }
                }
                // 基础校验：真正的 Mozilla 签名校验由内核在安装时完成
                if (out.length() < MIN_XPI_BYTES) throw IllegalStateException("包体过小")
                return@withContext out
            } catch (e: CancellationException) {
                runCatching { out.delete() }
                throw e
            } catch (e: Throwable) {
                runCatching { out.delete() }
            }
        }
        null
    }

    /**
     * 安装顺序（由 2026-09-12 用户实测确定）：
     *  ① 本地包：直接 `file://` + FROM_FILE；
     *  ② 远端包：**首选把官方直链交给内核** `install(url, MANAGER)` —— 用户实测
     *     「从官方链接安装」这条通道可用；最简单，且不多下一次下载。
     *     本步受 [KERNEL_STEP_MS] 单独封顶，超时即视为"这一步没走通"；
     *  ③ ①/② 失败才用应用自建网络栈下载（按 [Source.candidateUrls] 逐候选试），
     *     再走 `file://` + FROM_FILE。
     *
     * 每一步都有独立上限、且三段之和小于 [TOTAL_TIMEOUT_MS]，保证兜底链一定跑得完。
     */
    private suspend fun performInstall(
        source: Source,
        controller: WebExtensionController,
    ): WebExtension? {
        val local = source.localFile
        if (local != null) {
            return installFromFile(local, controller)
        }
        val url = source.remoteUrl
        if (url != null) {
            // ⚠️ 必须用 withTimeoutOrNull 给**这一步**单独设上限，不能用 withTimeout：
            // 内核这一步实测可能零回应（内核自己的下载器就是用户看到"自带的下载失败"的那一环），
            // 若让它无上限地跑，它会吃光总超时预算，导致下方**已验证可用**的自建下载兜底
            // 根本没机会执行 —— 用户等满总时长后拿到失败，而其实明明能装上。
            // 本步超时只代表"这一步没走通"，必须继续往下走；只有**外层总超时**或宿主销毁
            // 才允许终止整条链（外部取消会原样抛出，不被 withTimeoutOrNull 吞掉）。
            val ext = try {
                withTimeoutOrNull(KERNEL_STEP_MS) {
                    controller
                        .install(url, WebExtensionController.INSTALLATION_METHOD_MANAGER)
                        .awaitResult()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                null
            }
            if (ext != null) return ext
        }
        val pkg = downloadPackage(source.candidateUrls)
            ?: throw IllegalStateException("官方直链与自建下载均不可用")
        try {
            return installFromFile(pkg, controller)
        } finally {
            runCatching { pkg.delete() }
        }
    }

    /** 把一个本地 .xpi 交给内核安装（Mozilla 签名仍由内核校验） */
    private suspend fun installFromFile(file: File, controller: WebExtensionController): WebExtension? =
        controller
            .install(
                android.net.Uri.fromFile(file).toString(),
                WebExtensionController.INSTALLATION_METHOD_FROM_FILE,
            )
            .awaitResult()

    /** 一次安装的终态收口：摘除作业记录 */
    private fun settle(key: String) {
        jobs.remove(key)
    }

    companion object {
        /** 目录来源的幂等键（界面侧判断「该 slug 是否仍在安装中」时复用，避免键格式两处各写一遍） */
        fun catalogKey(slug: String): String = "catalog:$slug"

        /**
         * **进程级**作业表（所有实例共用）。
         *
         * 刻意不放在实例字段里：ExtensionsActivity 一旦重建，新实例的作业表会是空的，
         * `pruneStaleInstalls` 就会把"其实仍在进行"的安装误判成"上次被中断"而清掉状态，
         * 于是放行重复点击 → 同一扩展并发起多条安装（实测出现过 4 条并行作业）。
         * 提到进程级后，重建的新实例仍能看见在途作业，判定不再失真。
         */
        private val activeJobs = ConcurrentHashMap<String, Job>()

        /** 自建下载的缓存目录与临时包前缀（与本地导入的 `import-` 前缀区分开） */
        private const val CACHE_DIR = "exts"
        private const val TMP_PREFIX = "install-"
        /** 体积闸门：下限过滤残包，上限远超任何合法 AMO 扩展，纯为防滥用 */
        private const val MIN_XPI_BYTES = 1024L
        private const val MAX_XPI_BYTES = 200L * 1024 * 1024

        /**
         * 自建下载临时包的「陈旧」阈值：超过它才被视为上次中断留下的残包并清理。
         * 必须显著大于单次安装最长耗时（[TOTAL_TIMEOUT_MS]），否则会误删并发作业的在途文件。
         */
        private const val STALE_TMP_MS = 30L * 60 * 1000

        /**
         * 「内核直链安装」这一步的独立上限。
         *
         * 存在的理由：内核内置下载器在某些网络下对下载地址零回应（既不返回也不断开），
         * 而它**没有自己的超时**。若不给这一步封顶，它会把整条链的总预算吃光，
         * 让后面已验证可用的「自建下载 → file:// 安装」兜底永远轮不到 —— 正是
         * "等满时长才失败、但其实能装上"的成因。
         */
        private const val KERNEL_STEP_MS = 45_000L

        /**
         * 单个候选地址的**硬性**下载超时。
         * 必须存在：某些网络对下载地址"慢滴/黑洞"时 readTimeout 不会触发，
         * 只有 callTimeout 能强制断开，否则会永远卡在阻塞读里（连超时弹窗都没有）。
         */
        private const val DOWNLOAD_TIMEOUT_MS = 45_000L

        /**
         * 全链路总超时：覆盖「内核直链安装（≤45s）」+「自建下载（最多两个候选、每个 45s）」
         * +「file:// 安装」。
         * 取 150s = 45×3 + 15s 余量：**每一环都有独立上限，且三段加起来确实塞得进总时长**，
         * 于是最坏情况下兜底链也一定跑得完。超时必然给出失败原因，不会无声卡死。
         */
        private const val TOTAL_TIMEOUT_MS = 150_000L
    }

    /** 超时：安装请求已交给内核，但内核在期限内没有任何回应 */
    class InstallTimeoutException : Exception("install timeout: engine gave no response")
}
