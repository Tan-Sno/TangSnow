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
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
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
            // 限制单条日志体积，防止超大堆栈撑爆
            val stack = sw.toString()
            append(if (stack.length > 40_000) stack.take(40_000) + "\n…(堆栈过长已截断)" else stack)
        }
        val out = File(dir, "crash-$stamp.txt")
        runCatching { out.writeText(sb.toString()) }
        // 写入之后再裁剪：保证磁盘上恰好保留最近 MAX_KEEP 条。
        // （旧实现"先判断再写"，实际会多留 1 条，与注释声明的保留条数不一致。）
        runCatching {
            val files = dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") }
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
