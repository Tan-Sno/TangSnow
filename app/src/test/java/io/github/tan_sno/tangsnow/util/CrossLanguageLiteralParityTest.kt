package io.github.tan_sno.tangsnow.util

import io.github.tan_sno.tangsnow.TestPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **跨语言手抄事实的逐条比对**（2026-10-16，同类扩散扫描轮）。
 *
 * ## 为什么需要它
 * `tangsnow-rs/` 是从 Kotlin 逐行移植的，于是同一份**清单**在两侧各存了一份。``tests/` 目录里的 `*_parity.rs``
 * 那些"差分用例"其实是**镜像**：把同输入同输出写死在测试里，两侧各一份。镜像防得住"改行为忘了改测试"，
 * **防不住"改了一侧的清单"** —— 那种改动下，本侧的镜像用例照抄本侧的新值，照样绿。
 * 2026-10-16 的 `HOST_ALIASES` 漏行（Rust 6 条 / Kotlin 7 条）就是这么漏掉的，
 * 于是这里把其余几组同类事实**并排读两侧源码**比对。
 *
 * 与 [io.github.tan_sno.tangsnow.data.SearchEngineTableParityTest] 同一套纪律：
 * 一律 fail-closed —— 找不到声明、括号不配对、解析出空集合，全都**红**，
 * 因为"看不懂就跳过"演变成漏检比误报更糟（这道门坏了的表现是静默放行）。
 *
 * ## 覆盖与边界
 * ✅ 能查：**集合是否相同**（不看书写顺序）。
 * ❌ 查不了：**判据是否等价** —— Kotlin 写 `scheme == "http" || …`、Rust 写 `matches!(…)`，
 * Kotlin 的 `isExecutableName` 取 `substringAfterLast('.')` 而 Rust 另有实现；这些靠 parity 用例
 * 与真机冒烟，不靠本测试。另有一处**已知有意差异**见 `tangsnow-rs/README.md` 的「已知口径差异」表。
 */
class CrossLanguageLiteralParityTest {

    private val urlUtils: String by lazy { TestPaths.read("app/src/main/java/io/github/tan_sno/tangsnow/util/UrlUtils.kt") }
    private val downloadRepo: String by lazy {
        TestPaths.read("app/src/main/java/io/github/tan_sno/tangsnow/data/repo/DownloadRepo.kt")
    }
    private val historyRepo: String by lazy {
        TestPaths.read("app/src/main/java/io/github/tan_sno/tangsnow/data/repo/HistoryRepo.kt")
    }
    private val rustUrl: String by lazy { TestPaths.read("tangsnow-rs/tangsnow-core/src/url_utils.rs") }
    private val rustName: String by lazy { TestPaths.read("tangsnow-rs/tangsnow-core/src/download_filename.rs") }
    private val rustHistory: String by lazy { TestPaths.read("tangsnow-rs/tangsnow-core/src/history.rs") }
    private val coreNative: String by lazy {
        TestPaths.read("app/src/coreRust/java/io/github/tan_sno/tangsnow/core/CoreNative.kt")
    }
    private val ktBookmark: String by lazy {
        TestPaths.read("app/src/main/java/io/github/tan_sno/tangsnow/data/BookmarkHtml.kt")
    }
    private val rustBookmark: String by lazy { TestPaths.read("tangsnow-rs/tangsnow-core/src/bookmark_html.rs") }
    private val crashLogger: String by lazy {
        TestPaths.read("app/src/main/java/io/github/tan_sno/tangsnow/util/CrashLogger.kt")
    }
    private val rustCrash: String by lazy { TestPaths.read("tangsnow-rs/tangsnow-core/src/crash_logger.rs") }
    private val libRs: String by lazy { TestPaths.read("tangsnow-rs/tangsnow-core/src/lib.rs") }

    @Test
    fun 网页导航白名单两侧一致() {
        assertSameSet(
            "网页导航 scheme 白名单（`SessionManager.onLoadRequest` 与 `MainActivity.onOpenInCurrentTab` 共用的那道闸）",
            literalsIn(urlUtils, "internal fun isWebNavigationScheme", "\n\n", "Kotlin UrlUtils"),
            literalsIn(rustUrl, "pub fn is_web_navigation_scheme", "}", "Rust url_utils"),
        )
    }

    @Test
    fun 绝不放行给网页内容的scheme两侧一致() {
        // 这一组是安全闸门的一半：少一个 scheme（比如哪天 Kotlin 加了 `filesystem:`）
        // 就是 coreRust 上"网页可以驱动它"而 coreKotlin 上不行 —— 同一个 app 两套安全边界。
        assertSameSet(
            "网页内容绝不该驱动的 scheme 集合",
            literalsIn(urlUtils, "internal fun isNeverWebContentScheme", "\n\n", "Kotlin UrlUtils"),
            literalsIn(rustUrl, "pub fn is_never_web_content_scheme", "}", "Rust url_utils"),
        )
    }

    @Test
    fun 可携带URL的排除集两侧一致() {
        // 分享 / 复制 / 写会话快照三处共用（`file` 在其中 ⇒ L7 那条「快照恢复能不能带出 file:」的依据）。
        assertSameSet(
            "NON_PORTABLE_SCHEMES（不进分享/复制/快照的 scheme）",
            collection(urlUtils, "private val NON_PORTABLE_SCHEMES = setOf", '(', "Kotlin UrlUtils"),
            collection(rustUrl, "const NON_PORTABLE_SCHEMES: [&str; 5] = ", '[', "Rust url_utils"),
        )
    }

    @Test
    fun 可执行扩展名表两侧一致() {
        // 这张表决定 `.apk`/`.aab` 这类下载要不要走「没有『不再询问』的强确认」。
        // 少一行 = 那一类文件在 coreRust 上静默不弹确认；多一行 = 正常下载被无端拦一道。
        assertSameSet(
            "EXECUTABLE_EXTENSIONS（安装包强确认判据；xpi/crx 是**刻意不收**的，两侧同口径）",
            collection(downloadRepo, "private val EXECUTABLE_EXTENSIONS = setOf", '(', "Kotlin DownloadRepo"),
            collection(rustName, "const EXECUTABLE_EXTENSIONS: &[&str] = ", '[', "Rust download_filename"),
        )
    }

    @Test
    fun 历史跳过前缀两侧一致() {
        // ⚠️ Rust 侧 `tests/history_parity.rs::前缀清单与_kotlin_侧一致` 是**镜像**：
        // 它把自己那份清单硬编码在测试里，Kotlin 侧改了它照绿。真正的跨语言比对在这里。
        assertSameSet(
            "HistoryRepo 不写历史的前缀（内部页与脚本协议）",
            collection(historyRepo, "private val SKIP_PREFIXES = listOf", '(', "Kotlin HistoryRepo"),
            collection(rustHistory, "pub const SKIP_PREFIXES: [&str; 6] = ", '[', "Rust history"),
        )
    }

    @Test
    fun 清洗后兜底文件名三处同一个字面量() {
        // 三份拷贝：Kotlin 主实现的常量、Rust 侧常量、以及 native 不可用时 guard 的降级默认值。
        // 三者不同名就是同一个"洗不出名字"的下载在三条路径上落成新名字，MediaStore 的重名序号
        // 也随之不同 —— 不是崩溃，但会毁掉「同一处兜底」这条已写进注释的承诺。
        val kt = single(Regex("""private const val DEFAULT_FILE_NAME = "([^"]+)" """.trim()), downloadRepo, "Kotlin DownloadRepo")
        val rs = single(Regex("""pub const DEFAULT_FILE_NAME: &str = "([^"]+)" """.trim()), rustName, "Rust download_filename")
        val bridge = single(Regex("""guard\("dlSanitizeFileName", "([^"]+)"\) """.trim()), coreNative, "CoreNative 的降级兜底")
        assertEquals("Kotlin 主实现与 Rust 的兜底文件名不同名", kt, rs)
        assertEquals(
            "native 不可用时的降级默认值与主实现不同名 ⇒ 「两条降级路径同口径」那句注释成了假话",
            kt,
            bridge,
        )
        assertTrue(
            "兜底文件名是空串/空白 ⇒ 落盘时会撞出无名字条目（`download` 是两侧注释里共同的锚点）",
            bridge.isNotBlank(),
        )
    }

    @Test
    fun 书签实体正则与崩溃文件名时间戳正则两侧逐字符一致() {
        // 实体集：少一个 `apos` 或 `nbsp`，书签标题里的这个实体就不再被解出（导出→导入往返性破损），
        // 而两侧各自镜像同一份期望的 parity 用例都看不见 —— 见类注释。
        assertEquals(
            "HTML 实体正则的两侧字面量不同 ⇒ 同一份 Netscape 书签在两条通道上解出不同标题",
            literal(ktBookmark, "private val ENTITY = Regex(", "Kotlin BookmarkHtml.ENTITY"),
            literal(rustBookmark, "Regex::new(", "Rust bookmark_html.ENTITY", from = rustBookmark.indexOf("static ENTITY")),
        )
        assertEquals(
            "崩溃日志文件名时间戳正则两侧不同 ⇒ 同一颗日志在一侧能显示成人话、另一侧显示原始文件名",
            literal(crashLogger, "private val CRASH_NAME_STAMP = Regex(", "Kotlin CrashLogger.CRASH_NAME_STAMP"),
            literal(rustCrash, "Regex::new(", "Rust crash_logger.CRASH_NAME_STAMP", from = rustCrash.indexOf("static CRASH_NAME_STAMP")),
        )
    }

    @Test
    fun URL提取正则的S口径两侧一致() {
        // ⚠️ Kotlin 侧有**两条同名 `URL_IN_TEXT` 而语义不同**的正则（本轮我自己的第一版就把它们配错了一次，
        // 红得正是那一次错 —— 记在这里，免得下次再配错）：
        //  · `UrlUtils.URL_IN_TEXT`    = `https?://\S+` —— 从分享文本里**提链接**，刻意只认 http(s)；
        //  · `CrashLogger.URL_IN_TEXT` = `([a-z][a-z0-9+.\-]*)://(\S+)` —— 崩溃日志**脱敏**（政策 §7 的落点），
        //    必须宽到吃下任意 scheme。
        // Rust 没有 `\S`，按本仓约定展开成 ASCII 空白补集 ⇒ "逐字符相同"要在**代回那个约定之后**判，
        // 而约定本身由 `lib.rs` 的 `JAVA_NON_WS_CLASS` 钉住（它变了，这里立刻可见）。
        val nonWs = literal(libRs, "pub const JAVA_NON_WS_CLASS: &str = ", "Rust lib.rs 的 JAVA_NON_WS_CLASS")

        val ktShare = literal(urlUtils, "private val URL_IN_TEXT = Regex(", "Kotlin UrlUtils.URL_IN_TEXT")
        val rsShare = literal(
            rustUrl, "Regex::new(", "Rust url_utils.URL_IN_TEXT_RE",
            from = rustUrl.indexOf("static URL_IN_TEXT_RE"),
        )
        assertEquals(
            "分享文本提链接的 URL 正则两侧不同（已代回 `\\\\S` 约定）⇒ 同一段文本在两条通道上提出不同链接",
            ktShare,
            normalizedRust(rsShare, nonWs),
        )

        val ktRedact = literal(crashLogger, "private val URL_IN_TEXT = Regex(", "Kotlin CrashLogger.URL_IN_TEXT")
        val rsRedact = literal(
            rustCrash, "Regex::new(", "Rust crash_logger.URL_IN_TEXT",
            from = rustCrash.indexOf("static URL_IN_TEXT"),
        )
        assertEquals(
            "崩溃日志**脱敏**用的 URL 正则两侧不同 ⇒ 脱敏覆盖面不一样：少一个 scheme 前缀就是漏脱敏，" +
                "而政策 §7 给的是封闭式承诺（仅域名、不含完整网址）",
            ktRedact,
            normalizedRust(rsRedact, nonWs),
        )

        assertTrue(
            "Kotlin 这两条同名正则是**有意不同**的：脱敏那条要宽到任意 scheme，提链接那条刻意只认 http(s)。" +
                "谁合并它们，就是在某一侧悄悄改了语义 —— 要合并就连同两处注释与本断言一起改",
            ktShare != ktRedact,
        )
        for ((file, label) in listOf(urlUtils to "UrlUtils", crashLogger to "CrashLogger")) {
            assertTrue(
                "$label.URL_IN_TEXT 必须带 RegexOption.IGNORE_CASE（关掉它 `HTTPS://`、`?TOKEN=` 就不被处理）",
                file.lines().first { it.contains("private val URL_IN_TEXT = Regex(") }
                    .contains("RegexOption.IGNORE_CASE"),
            )
        }
        assertTrue(
            "Rust 侧两条都必须带行内大小写不敏感标志 —— 与 Kotlin 的 IGNORE_CASE 同开同关。" +
                "提链接那条 2026-10-17 起是 `(?i-u)`（只折叠 ASCII），脱敏那条仍是 `(?i)`。",
            rsShare.startsWith("(?i-u)") && rsRedact.startsWith("(?i)"),
        )
        // 2026-10-17：提链接那条的 `(?i)` 收紧成 `(?i-u)`。Java 的 IGNORE_CASE（未开 UNICODE_CASE）
        // 只折叠 ASCII，而 Rust `(?i)` 默认走 Unicode 简单折叠 ⇒ 旧口径会把 `httpſ://`（U+017F 折成 s）
        // 当成 `https://` 提出来，凭空造一条链接（`-u` 必须存在已由上面那条断言钉住）。
        assertTrue(
            "取反类必须用 `(?u:…)` 包住：`-u` 下 `[^ …]` 退化成字节类、可匹配非法 UTF-8，" +
                "`Regex::new` 会直接报 “pattern can match invalid UTF-8”（bench_smoke 会红）",
            rsShare.contains("(?u:"),
        )
    }

    /** Rust 模式 → 可与 Kotlin 逐字符比对的形状：剥掉行内 `(?i)`，把展开的 `\\S` 约定代回去。 */
    private fun normalizedRust(rustPattern: String, nonWs: String): String =
        rustPattern
            // 2026-10-17：剥两类「书写手段」再比对（剥掉与语义无关的部分，否则比对会误红）：
            //  · `(?i-u)`：`-u` 把大小写折叠限制在 ASCII（Java IGNORE_CASE 未开 UNICODE_CASE 的口径）；
            //    少了它，Rust `(?i)` 会把 `ſ`↔`s` 也折叠 ⇒ `httpſ://` 被当成 `https://`。
            //  · `(?u:…)`：`-u` 下取反类会退化成字节类、可匹配非法 UTF-8 ⇒ `Regex::new` 直接拒，
            //    故取反类必须局部把 Unicode 开回来。
            // 这两者**必须存在**，由本测试后面的两条断言钉住。
            .removePrefix("(?i-u)")
            .removePrefix("(?i)")
            // 先把 `(?u:…)` 壳剥掉（与下面的 `\S` 代回互不依赖，顺序无关）。
            .replace(Regex("""\(\?u:([^()]*)\)"""), "\$1")
            .replace(nonWs, "\\S")

    @Test
    fun CJK截断字符类两侧逐字符一致() {
        // 从这些字符起硬截断（说明文字黏连的切点）。少一个标点 ⇒ 分享文本里提出来的链接带上尾巴。
        assertEquals(
            "CJK_URL_CUTTER 字符类两侧不同 ⇒ 同一段分享文本在两条通道上提出不同的链接",
            literal(urlUtils, "private val CJK_URL_CUTTER = Regex(", "Kotlin UrlUtils.CJK_URL_CUTTER"),
            literal(rustUrl, "Regex::new(", "Rust url_utils 的 CJK 截断类", from = rustUrl.indexOf("static CJK_URL_CUTTER_RE")),
        )
        // 上一版把 `TRAILING_URL_PUNCTUATION` 记成"未纳入比对（要个小解析器，不划算）"——
        // 那种"先判定不值再留下缺口"的写法本身就是缺口的来源，见 `尾随标点清理表两侧一致`。
    }

    @Test
    fun 尾随标点清理表两侧一致() {
        // 分享文本里链接尾随的英文/全角标点靠这张表连续剥掉。少一个字符 ⇒ 链接带着一截尾巴
        // （打不开或打开错页），多一个字符 ⇒ 真该保留的字符被啃掉。
        // 两侧的**字面量形状不同**：Kotlin 是三引号串 + 普通串拼接，Rust 是单个带 `\"` 转义的串
        // ⇒ 这里按"同一行里所有字面量按序拼接后的字符集"比，比的是内容不是书写形状。
        assertSameSet(
            "TRAILING_URL_PUNCTUATION（链接尾部连续剥除的标点集）",
            concatenated(urlUtils, "private val TRAILING_URL_PUNCTUATION = ", "Kotlin UrlUtils").map { it.toString() }.toSet(),
            concatenated(rustUrl, "const TRAILING_URL_PUNCTUATION: &str = ", "Rust url_utils").map { it.toString() }.toSet(),
        )
    }

    // ------------------------------------------------------------------ 解析

    /**
     * 取 [marker] 所在行里**所有**字符串字面量并按序拼接（手写小扫描器，不用正则）。
     *
     * 为什么需要它：同一个字符集在两侧可以写成不同形状 —— Kotlin 是「三引号串 + 普通串」拼接，
     * Rust 是单个带转义引号的串。只支持"一行一个字面量"，就会留下「人工对过、暂不纳入」的缺口
     * （本函数补的正是上一版自己留下的那条）。
     * 实现里一律用**码位常量**与模板拼出引号（0x22 / 0x5C），不写反斜杠转义 ——
     * 这个函数上一版就是被自家源码的转义规则绊倒两次的。
     */
    private fun concatenated(text: String, marker: String, label: String): String {
        val at = text.indexOf(marker)
        assertTrue("$label：找不到「$marker」（改了写法就同步改本测试）", at >= 0)
        val line = text.substring(at).lineSequence().first()
        val quote = 0x22.toChar()
        val backslash = 0x5C.toChar()
        val triple = "$quote$quote$quote"
        val out = StringBuilder()
        var i = 0
        while (i < line.length) {
            if (line.startsWith(triple, i)) {
                val close = line.indexOf(triple, i + 3)
                assertTrue("$label：三引号串没有闭合", close > i)
                out.append(line, i + 3, close)
                i = close + 3
                continue
            }
            if (line[i] == quote) {
                var j = i + 1
                while (j < line.length && line[j] != quote) {
                    if (line[j] == backslash && j + 1 < line.length) {
                        out.append(unescape(line[j + 1]))
                        j += 2
                    } else {
                        out.append(line[j])
                        j += 1
                    }
                }
                assertTrue("$label：普通串没有闭合（行尾片段：${line.takeLast(48)}）", j < line.length)
                i = j + 1
                continue
            }
            i++
        }

        val got = out.toString()
        assertTrue("$label：这一行没拼出任何字符（源码形状与解析器不符）", got.isNotEmpty())
        return got
    }

    /** 反斜杠转义：n/t/r 展开成控制字符，其余（引号本身、反斜杠本身）取字符。 */
    private fun unescape(next: Char): Char = when (next) {
        'n' -> 0x0A.toChar()
        't' -> 0x09.toChar()
        'r' -> 0x0D.toChar()
        else -> next
    }


    /** 取 [from] 到 [until] 之间出现的所有双引号字面量。 */
    private fun literalsIn(text: String, from: String, until: String, label: String): Set<String> {
        val a = text.indexOf(from)
        assertTrue("$label：找不到「$from」—— 实现改名/改写法就同步改本测试", a >= 0)
        val b = text.indexOf(until, a)
        assertTrue("$label：「$from」之后找不到结束锚「$until」", b > a)
        val found = quotes(text.substring(a, b))
        assertTrue("$label：没解析出任何字面量（判据被改写成了查表？本测试需同步，不许静默跳过）", found.isNotEmpty())
        return found
    }

    /** 取 `marker` 之后第一个平衡括号块里的所有双引号字面量（Rust 用 `[ ]`，Kotlin 用 `( )`）。 */
    private fun collection(text: String, marker: String, open: Char, label: String): Set<String> {
        val a = text.indexOf(marker)
        assertTrue("$label：找不到声明「$marker」—— 改名或换写法就同步改本测试", a >= 0)
        val start = text.indexOf(open, a + marker.length)
        assertTrue("$label：「$marker」之后找不到「$open」", start >= 0)
        val close = if (open == '[') ']' else ')'
        var depth = 0
        var end = -1
        var i = start
        while (i < text.length) {
            val c = text[i]
            if (c == open) depth++
            else if (c == close) {
                depth--
                if (depth == 0) {
                    end = i
                    break
                }
            }
            i++
        }
        assertTrue("$label：「$marker」的括号块没有配对的「$close」（源码形状与解析器不符）", end > start)
        val found = quotes(text.substring(start + 1, end))
        assertTrue("$label：集合解析为空（清单被挪走了？本测试需同步）", found.isNotEmpty())
        return found
    }

    private fun quotes(body: String): Set<String> =
        Regex(""""([^"]*)"""").findAll(body).map { it.groupValues[1] }.filter { it.isNotBlank() }.toSet()

    /**
     * 取「含 [marker] 的那一行」里的**单个**字符串字面量内容
     * （先试 Kotlin 的 `"""…"""`，再试 Rust 的 `r"…"` / 两侧的 `"…"`）。
     * 只适用于"一行一个字面量"的正则/常量声明 —— 这正是本轮这几组的形状；
     * 多字面量拼接那种（见 CJK 那条用例的注释）刻意不支持，而不是假装支持。
     */
    private fun literal(text: String, marker: String, label: String, from: Int = 0): String {
        val a = text.indexOf(marker, from)
        assertTrue("$label：找不到「$marker」（改了写法就同步改本测试）", a >= 0)
        val line = text.substring(a).lineSequence().first()
        val triple = Regex("\"\"\"(.*?)\"\"\"").find(line)
        val plain = triple ?: Regex("r?\"([^\"]*)\"").find(line)
        val got = (plain ?: throw AssertionError("$label：这一行里找不到字符串字面量：$line")).groupValues[1]
        assertTrue("$label：取到的字面量是空的（解析器与源码形状不符）", got.isNotEmpty())
        return got
    }

    private fun single(regex: Regex, text: String, label: String): String =
        (regex.find(text) ?: throw AssertionError("$label：找不到预期的字面量声明（改了写法就同步改本测试）"))
            .groupValues[1]

    private fun assertSameSet(what: String, kotlin: Set<String>, rust: Set<String>) {
        assertEquals(
            "$what 两侧集合不同。\n  只有 Kotlin 有：${(kotlin - rust).sorted()}\n" +
                "  只有 Rust 有：${(rust - kotlin).sorted()}\n" +
                "  Kotlin=${kotlin.sorted()}\n  Rust  =$rust.sorted()\n" +
                "⇒ 补齐另一侧（或说明为什么**有意**不同，并写进 tangsnow-rs/README.md 的「已知口径差异」表与本测试）",
            kotlin.sorted(),
            rust.sorted(),
        )
    }


}
