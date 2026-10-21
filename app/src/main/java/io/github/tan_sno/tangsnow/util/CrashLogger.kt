package io.github.tan_sno.tangsnow.util

import android.content.Context
import io.github.tan_sno.tangsnow.core.CoreImpl
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

    /**
     * 单条日志里堆栈正文的体积上限（防止超大堆栈撑爆磁盘）。
     *
     * 原先这个 40000 在 [write] 与 [recordFault] 里各写了一遍 —— 两处都得改才对得上，
     * 而漏改一处就会出现「崩溃日志截断了、桥接故障日志没截断」的不一致。
     */
    private const val MAX_STACK_CHARS = 40_000

    /** 堆栈段的标题（两种来源同形状 ⇒ 政策第 7 条那份封闭字段清单才对得上） */
    private const val STACK_HEADER = "---- 堆栈 ----"

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
        // W2 接缝：Rust 可用时由其接管（同口径，见 crash_logger_parity.rs / CrashLoggerDisplayTest）。
        // null = 不接管 ⇒ 回落下面的共享实现。
        CoreImpl.crashDisplayStamp(fileName)?.let { return it }
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
        // W2 接缝：只接这一**单层**（Rust 侧没有 redactSchemeless/redactCredentials 的对应，
        // 其余两层仍留在 Kotlin）。null = 不接管 ⇒ 回落共享实现。
        CoreImpl.crashRedactUrls(text) ?: URL_IN_TEXT.replace(text) { m ->
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

    /**
     * 无 `//` 的 scheme 脱敏：`mailto:alice@example.com` → `mailto:…`。
     *
     * [redactUrls] 只认 `scheme://…`（要求双斜杠），而 `mailto:` / `data:` / `javascript:` 这类
     * **冒号后直接跟内容**的形态一字不落地进了日志 —— `mailto:` 会把完整邮箱地址写盘，
     * `data:` 甚至可能内嵌整段用户内容。二者都撞政策第 7 条的「不含…账号或其它可识别您个人的浏览信息」。
     *
     * 用**白名单**而非黑名单：只收敛已知的「冒号后无 `//` 且可能含个人信息」的 scheme，
     * 避免把正文里偶然出现的 `foo:` 也一并吃掉（脱敏不得破坏排障价值）。
     * `(?!//)` 前瞻把 `content://…` / `file://…` 排除在外 —— 那些已由 [redactUrls] 处理成 host 形态，
     * 不能被这里二次吃掉。
     */
    internal fun redactSchemeless(text: String): String =
        SCHEMELESS.replace(text) { m -> "${m.groupValues[1]}:…" }

    /**
     * 冒号后**不接** `//` 的 scheme 白名单（接 `//` 的交给 [redactUrls]）。
     *
     * 末尾的 `\S*` 是**必需的**：只管到冒号就只能把 `mailto:` 换成 `mailto:…`，
     * 后面的 `alice@example.com?subject=…` 会原样留在日志里 —— 等于白脱敏。
     * 故把冒号之后、到空白为止的整段内容一并吃掉。
     */
    private val SCHEMELESS = Regex(
        """(?<![A-Za-z0-9+.\-])(mailto|data|javascript|blob|tel|sms|intent|about):(?!//)\S*""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 凭据脱敏：既处理 `token=…` / `password: …` 这类**键值对**，也处理 `Bearer …` 这类
     * **认证方案 + 凭据**（无 `=` / `:` 分隔）。
     *
     * 为什么要单独一层：[redactUrls] 只管 URL，而异常 message 里更常见的恰恰是**不构成 URL** 的凭据
     * —— `ExtensionsActivity` 自己就记着「网络类异常的 message 常带完整下载 URL」，而同一份 message
     * 在 debug 走 Logcat（已被 `BuildConfig.DEBUG` 拦住）、在 release 走本对象落盘（此前只过 URL 层）。
     * 两条出口强度不一致，等于「debug 看不见的凭据，在 release 的崩溃日志里留下」，且用户还能一键分享。
     *
     * 键名**保留**（`token=…` 而不是整段删掉）：排障时仍能看出"这里原本有个 token"，
     * 只把值收敛掉，把对排障价值的损失压到最低。
     *
     * ⚠️ 判据是「**含敏感词的标识符**」而不是固定词表（2026-10-19 收紧）：早期写法
     * `(?<![A-Za-z0-9_])(token|password|…)` 会把**前缀为 `_` 或直接粘连**的复合名整类漏掉 ——
     * `client_secret=` / `id_token=` / `session_token=` / `csrf_token=` / `secret_key=` / `clientsecret=`
     * 都不匹配（`token` 前是 `_` ⇒ 左侧边界失败；而词表里又没有这些复合名），实测 13 种形态一字不落落盘。
     * 现改为「标识符中含敏感词」：`[A-Za-z0-9_]*敏感词[A-Za-z0-9_]*`，左侧边界放宽到 `(?<![A-Za-z0-9])`。
     * 分隔符另容许一层 `["']?`，以收敛 JSON（`{"client_secret":"…"}`）里带引号的键。
     * 误伤面由**分隔符**兜住：类名/普通词之后没有 `[:=]`（如 `SessionManager.kt:1603`、`session is not open`）
     * 故不会被碰 —— 见 `CrashLoggerRedactTest` 的「含敏感词的类名与普通词不被误伤」用例。
     */
    internal fun redactCredentials(text: String): String =
        AUTH_SCHEME.replace(CREDENTIAL_PAIRS.replace(text) { m -> "${m.groupValues[1]}${m.groupValues[2]}…" }) { m ->
            "${m.groupValues[1]}${m.groupValues[2]}…"
        }

    /**
     * `键名 :|= 值` 形态（值吃到空白为止）—— 键名是**含敏感词的标识符**，不是固定词表。
     * `key` 单独太泛（会误伤 `keyboard=`），故只显式纳入常见的 `<前缀>_key` 形态。
     */
    private val CREDENTIAL_PAIRS = Regex(
        """(?<![A-Za-z0-9])([A-Za-z0-9_]*(?:token|secret|passwd|password|passphrase|apikey|api[_-]?key|access[_-]?key|private[_-]?key|cookie|session)[A-Za-z0-9_]*)(["']?\s*[:=]\s*)\S+""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * `认证方案 空格 凭据` 形态（无分隔符）。
     *
     * 顺序上必须**先于** [CREDENTIAL_PAIRS] 生效：若让键值层先吃掉 `Authorization: Bearer`，
     * 后面的 `eyJ…` 就成了无主的裸 token 留在日志里 —— 恰好是这条要治的东西。
     * 词表里的 `basic` / `token` 是常见英文词，但正则要求其后紧跟空白与一个非空白串，
     * 故 `BasicFileAttributes`（`basic` 后是字母）不会被误伤。
     */
    private val AUTH_SCHEME = Regex(
        """(?<![A-Za-z0-9_])(bearer|basic|digest|negotiate|apikey|api-key|token)(\s+)\S+""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 落盘前的完整脱敏链（[write] 的唯一入口）。
     *
     * 顺序：**无 `//` 的 scheme → 凭据 → `scheme://…` URL**。
     *  - scheme 层最先：先把 `data:` / `mailto:` 整体收掉，避免其内容被后面几层误判；
     *  - URL 层最后兜底：其余带 `//` 的 URL 统一收敛成 `scheme://host[:port]/…`。
     *
     * 具备**幂等性**（`redact(redact(x)) == redact(x)`）：各层的产物（`scheme:…` / `key=…` /
     * `scheme://host/…`）都不会再被同层或后续层二次改写，故重复调用结果稳定。
     */
    internal fun redact(text: String): String =
        redactUrls(redactCredentials(redactSchemeless(text)))

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

    /**
     * 落盘文件的公共头部：应用名称与版本、时间、进程、线程、设备与系统信息。
     *
     * 为什么抽出来：[write]（进程崩溃）与 [recordFault]（内核桥接故障）必须落**同一种形状**的文件。
     * 政策第 7 条给崩溃日志的是一份**封闭的字段清单**（应用名称与版本、崩溃时间、设备型号与
     * 系统版本、进程与线程标识、错误堆栈）—— 两种来源的文件若字段各不相同，「关于」页里那份
     * 清单就与实际内容对不上，披露随之失真；而 [PolicyConsistencyTest] 的「崩溃日志写入的字段
     * 集合与预期一致」正是按这一份清单把关的。
     *
     * @param lastHost 最近访问站点（仅域名）；null = 本条不记（桥接故障与网页内容无关，
     *   少记一个字段就少一份可识别信息，且政策第 7 条给的是「**可能**记录」）。
     */
    private fun buildHeader(
        context: Context,
        time: String,
        threadName: String,
        lastHost: String?,
    ): String = buildString {
        appendLine("棠雪 / TangSnow")
        appendLine("时间: $time")
        appendLine("进程: ${context.packageName} (pid=${android.os.Process.myPid()})")
        appendLine("线程: $threadName")
        val pm = context.packageManager
        runCatching {
            val pkg = pm.getPackageInfo(context.packageName, 0)
            appendLine("版本: ${pkg.versionName} (${if (android.os.Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else @Suppress("DEPRECATION") pkg.versionCode})")
        }
        appendLine("设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        appendLine("系统: Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        lastHost?.let { appendLine("最近访问站点（仅域名）: $it") }
    }

    /**
     * 记录一条**内核桥接故障**（不是进程崩溃：进程还活着，只是某个 JNI 调用失败了）。
     *
     * 为什么不另开一个 `diag/` 目录：那会多出一类「政策未枚举、用户在「关于」页既看不到
     * 也删不掉」的本地文件 —— 与隐私承诺的方向**相反**（政策第 7 条只披露了崩溃日志这一类，
     * 且那一条的措辞是「程序异常时，若应用进程发生**可被捕获的错误**，会留存在设备本地的崩溃
     * 日志」—— 桥接故障正是「可被捕获的错误」）。写进同一个 crash 目录、同一套命名 ⇒ 复用既有的
     * 披露、既有的可见性（「关于」页可列出、可查看、可删除、可分享）与既有的条数上限（[MAX_KEEP]）。
     *
     * ⚠️ 只在**崩溃日志已安装**（= 用户已通过同意门禁）时才落盘：与 [install] 同一口径 ——
     * 同意页承诺「在您点击同意之前不处理任何数据」。
     *
     * @param threadName **出错时**所在线程的名字。必须由调用方在异常发生的那个线程上取 ——
     *   本方法可能已在后台线程执行（写盘不阻塞调用方），那时取到的是 IO 线程，不是案发现场。
     */
    internal fun recordFault(context: Context, op: String, t: Throwable, threadName: String) {
        if (!installed) return
        val app = context.applicationContext
        val dir = crashDir(app)
        runCatching {
            dir.mkdirs()
            val sb = StringBuilder()
            sb.append(buildHeader(app, displayTime(), threadName, lastHost = null))
            sb.appendLine(STACK_HEADER)
            // 这一行是「错误堆栈」这一项的**说明文字**，刻意**不**写成「标签: 值」形式的独立字段：
            // 政策第 7 条给的是封闭的字段清单，多一个字段就得改政策 + 改 PolicyConsistencyTest 的
            // 预期集合；而本行不含任何可识别用户的信息，落在「错误堆栈」项内不构成新的数据类别。
            sb.appendLine("【内核桥接故障】未导致进程退出；出错操作：$op")
            sb.append(renderStack(t))
            File(dir, "crash-${fileStamp()}.txt").writeText(sb.toString())
        }
        trimOld(dir)
    }

    /** 裁剪：保证磁盘上恰好保留最近 [MAX_KEEP] 条（写入之后再裁，避免多留 1 条）。 */
    private fun trimOld(dir: File) {
        runCatching {
            val files = crashFiles(dir)
            if (files.size > MAX_KEEP) {
                files.drop(MAX_KEEP).forEach { it.delete() }
            }
        }
    }

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val dir = crashDir(context)
        dir.mkdirs()
        val sb = StringBuilder()
        sb.append(buildHeader(context, displayTime(), thread.name, lastHost))
        sb.appendLine(STACK_HEADER)
        sb.append(renderStack(throwable))
        val out = File(dir, "crash-${fileStamp()}.txt")
        runCatching { out.writeText(sb.toString()) }
        // 写入之后再裁剪：保证磁盘上恰好保留最近 MAX_KEEP 条。
        // （旧实现"先判断再写"，实际会多留 1 条，与注释声明的保留条数不一致。）
        trimOld(dir)
    }

    // ------------------------------------------------------------------ 共用件

    /** 崩溃日志目录（崩溃与桥接故障两种来源共用 ⇒ 同一目录、同一套命名与裁剪） */
    private fun crashDir(context: Context): File = File(context.filesDir, DIR_NAME)

    /** 头部里的显示时间 */
    private fun displayTime(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    /**
     * 文件名里的时间戳（精确到**毫秒**）。
     *
     * 毫秒后缀保证同一秒内的第二次崩溃不会覆盖掉第一条（崩溃日志恰恰是连崩时最需要的那条）。
     */
    private fun fileStamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())

    /**
     * 堆栈正文：**脱敏 + 体积上限**（崩溃与桥接故障两处必须同口径）。
     *
     * 堆栈里理论上不带 URL，但同一把尺子没有额外成本；上限则防止超大堆栈撑爆磁盘。
     */
    private fun renderStack(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        val stack = redact(sw.toString())
        return if (stack.length > MAX_STACK_CHARS) {
            stack.take(MAX_STACK_CHARS) + "\n…(堆栈过长已截断)"
        } else {
            stack
        }
    }

    /** 崩溃日志文件（按时间从新到旧） */
    private fun crashFiles(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?.toList().orEmpty()

    /** 崩溃日志文件（按时间从新到旧排序） */
    fun list(context: Context): List<File> =
        runCatching { crashFiles(crashDir(context)) }.getOrDefault(emptyList())

    fun content(file: File): String =
        runCatching { file.readText() }.getOrDefault("")
}
