package io.github.tan_sno.tangsnow.util

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地崩溃日志（合规：只存设备 filesDir，绝不自动上传；用户在「关于」页可查看/分享/删除）。
 *
 * 工作方式：
 *  - [install] 在 Application 启动时注册为默认未捕获异常处理器；
 *  - 发生未捕获异常时把时间、应用版本、设备与堆栈写入 filesDir/crash/crash-*.txt；
 *  - 仅保留最近 [MAX_KEEP] 条，更早自动清理，避免无限增长；
 *  - 写完后仍交给系统原处理器，保持系统原生崩溃行为不变。
 */
object CrashLogger {

    private const val DIR_NAME = "crash"

    /**
     * 崩溃日志保留条数。
     *
     * 声明为 `internal` 而非 `private`：隐私政策第 7 条明确写了「日志自动保留最近 10 条」，
     * 而 `PolicyConsistencyTest` 要把这句话与这个常量对上 —— 两边一旦不一致（比如这里
     * 改成 20 而政策没改），单测直接失败。改动本值时**必须同步改政策文本**。
     */
    internal const val MAX_KEEP = 10

    @Volatile
    private var installed = false

    /** 最近访问站点（仅记录 host，不含路径/查询等可能含个人信息的片段），崩溃时随日志留存便于复现 */
    @Volatile
    private var lastHost: String? = null

    /** 主界面每次页面跳转时调用：只保留“scheme://host”，丢弃路径、查询与账号片段 */
    fun noteVisit(url: String?) {
        if (url.isNullOrBlank()) return
        val host = runCatching {
            val uri = android.net.Uri.parse(url)
            when (uri.scheme?.lowercase()) {
                "http", "https" -> uri.host
                else -> null
            }
        }.getOrNull()
        if (!host.isNullOrBlank()) lastHost = host
    }

    /**
     * 清空「最近访问站点」。
     *
     * 为什么必须有清除出口：这个值住在**进程内存**里，一旦写下就留到进程结束 ——
     * 用户退出无痕、甚至执行「清除浏览数据」之后它仍在内存中，此后任何一次崩溃都会把
     * 一个**早已结束的会话**的域名写进持久化日志，与「无痕不留痕」直接冲突。
     * 调用点两处：退出无痕浏览、清除浏览数据（勾选历史）。
     */
    fun clearHost() {
        lastHost = null
    }

    /**
     * 「最近访问站点」的当前值。**仅供单测读取**（业务路径上没有任何读取点，
     * 唯一消费者是 [write] 里的那一行）。
     */
    internal fun lastHostOrNull(): String? = lastHost

    /** 崩溃日志文件名的时间戳段（`yyyyMMdd-HHmmss`，毫秒 `-SSS` 可选）；解析见 [displayStamp] */
    private val CRASH_NAME_STAMP = Regex("""^(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})(\d{2})(?:-\d{3})?$""")

    /**
     * 崩溃日志**文件名** → 展示用时间戳：`crash-20261001-121500-123.txt` → `2026-10-01 12:15:00`。
     *
     * 写名（本对象 [write] 里的 `stamp`）与读名同源；含毫秒（2212d96 起）与不含毫秒两代都能解析，
     * 毫秒在展示中丢弃。解析不了时返回「去掉前缀/后缀的原始串」（与旧回退一致）。
     */
    internal fun displayStamp(fileName: String): String {
        val core = fileName.removePrefix("crash-").removeSuffix(".txt")
        val m = CRASH_NAME_STAMP.find(core) ?: return core
        return "${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]} " +
            "${m.groupValues[4]}:${m.groupValues[5]}:${m.groupValues[6]}"
    }

    /**
     * 写盘前的 URL 脱敏：把文本里的 **`scheme://` 形式的 URL** 收敛成 `scheme://host[:port]/…`。
     *
     * 政策第 7 条对崩溃日志给的是**封闭式**承诺——「可能记录最近访问站点的域名（仅域名、
     * 不含完整网址）」。而异常 message 里可能带完整 URL（`FileNotFoundException: https://…`），
     * 原样落盘就打破了该承诺。类名/行号等非 URL 文本一律不动（脱敏不能破坏排障价值）。
     *
     * ⚠️ 覆盖范围刻意**不限于 http(s)**（2026-09-30 外部审查指出）：`file:///data/user/0/<pkg>/…`
     * 这类本地路径同样是"完整位置"，一并收敛；`data:` 这类**不带 `//`** 的形式不会被匹配（无副作用）。
     * 而**裸路径**（`/data/user/0/…`，无 scheme）刻意保留 —— 它不是 URL，且对排障价值更高。
     *
     * 纯字符串函数（不碰 Android 类型），故可直接被 JVM 单测覆盖。
     */
    internal fun redactUrls(text: String): String =
        URL_IN_TEXT.replace(text) { m ->
            val scheme = m.groupValues[1]
            // 先截 authority（第一个 '/'|'?'|'#' 之前），**再**剥 userinfo：
            // 顺序不能反 —— userinfo 的 `@` 只在 authority 里合法；若先对整段做
            // substringAfterLast('@')，路径里含 `@` 的 URL（如 /docs/@user）会把
            // host 与路径前缀整个误剥掉，收敛成 scheme://user/…，白丢排障信息。
            val authority = m.groupValues[2].takeWhile { it != '/' && it != '?' && it != '#' }
            val hostPort = authority.substringAfterLast('@')
            "$scheme://$hostPort/…"
        }

    /** 文本里任意 `scheme://…` 形式的 URL（`\S+` 到空白为止；userinfo 由 [redactUrls] 剥掉） */
    private val URL_IN_TEXT = Regex("""([a-z][a-z0-9+.\-]*)://(\S+)""", RegexOption.IGNORE_CASE)

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            installed = true
        }
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(app, thread, throwable) }
            // 保持系统默认行为（结束进程）；若没有前序处理器则兜底退出
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val dir = File(context.filesDir, DIR_NAME)
        dir.mkdirs()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        // 文件名精确到**毫秒**：原先只到秒，同一秒内的第二次崩溃会覆盖掉第一条（崩溃日志恰恰是
        // 连崩时最需要的那条）。列表按修改时间（lastModified）倒序；毫秒后缀保证同秒两条也有稳定先后。
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val sb = StringBuilder().apply {
            appendLine("棠雪 / TangSnow")
            appendLine("时间: $time")
            appendLine("进程: ${context.packageName} (pid=${android.os.Process.myPid()})")
            appendLine("线程: ${thread.name}")
            val pm = context.packageManager
            runCatching {
                val pkg = pm.getPackageInfo(context.packageName, 0)
                appendLine("版本: ${pkg.versionName} (${if (android.os.Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else @Suppress("DEPRECATION") pkg.versionCode})")
            }
            appendLine("设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("系统: Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            lastHost?.let { appendLine("最近访问站点（仅域名）: $it") }
            appendLine("---- 堆栈 ----")
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            // 限制单条日志体积，防止超大堆栈撑爆；同时做 URL 脱敏（见 redactUrls 的说明）
            val stack = redactUrls(sw.toString())
            append(if (stack.length > 40_000) stack.take(40_000) + "\n…(堆栈过长已截断)" else stack)
        }
        val out = File(dir, "crash-$stamp.txt")
        runCatching { out.writeText(sb.toString()) }
        // 写入之后再裁剪：保证磁盘上恰好保留最近 MAX_KEEP 条。
        // （旧实现"先判断再写"，实际会多留 1 条，与注释声明的保留条数不一致。）
        runCatching {
            val files = dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") && f.name.endsWith(".txt") }
                ?.toList().orEmpty()
            if (files.size > MAX_KEEP) {
                files.sortedByDescending { it.lastModified() }
                    .drop(MAX_KEEP)
                    .forEach { it.delete() }
            }
        }
    }

    /** 崩溃日志文件（按时间从新到旧排序） */
    fun list(context: Context): List<File> =
        runCatching {
            File(context.filesDir, DIR_NAME)
                .listFiles { f -> f.isFile && f.name.startsWith("crash-") && f.name.endsWith(".txt") }
                ?.sortedByDescending { it.lastModified() }
                ?.toList() ?: emptyList()
        }.getOrDefault(emptyList())

    fun content(file: File): String =
        runCatching { file.readText() }.getOrDefault("")
}
