package io.github.tan_sno.tangsnow.data

import io.github.tan_sno.tangsnow.update.UpdateChecker
import io.github.tan_sno.tangsnow.util.CrashLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **隐私政策与实现的一致性单测** —— 把「政策里的事实性声明」与代码常量绑在一起。
 *
 * ## 为什么要有这个文件
 *
 * 政策文本与实现脱节过两次，两次都是靠**人读**出来的：
 *
 *  1. 政策 §7 用穷尽性措辞「**仅含**崩溃时间、应用版本、设备型号与错误堆栈」，
 *     而 `CrashLogger` 实际还写了进程、线程与系统版本 —— 后来补齐了枚举；
 *  2. 政策 §2 列了权限，而 `aapt dump permissions` 扫出来的比列的多一项。
 *
 * 这类问题不是「坏代码」，而是**文本落后于实现**：代码改对了、文档没跟上。
 * 靠人去读，下一次未必有人读；写成断言，`testDebugUnitTest` 会直接变红。
 *
 * ## 为什么直接读文件，而不是放进 test resources
 *
 * 政策的单一事实来源就是 `app/src/main/res/values/` 与 `values-en/` 下的 `strings.xml`
 * —— **构建真正使用的那份**。
 * 复制一份到测试资源里，等于又制造了一个会漂移的副本，恰好背离本测试的目的。
 * 因此这里按相对路径定位仓库文件（工作目录可能是模块目录也可能是仓库根，故向上查找）。
 *
 * ## 覆盖范围与边界（别高估它）
 *
 * ✅ 能查：**数字类**（保留多少条）、**枚举类**（列了哪些权限、哪些字段）
 * ❌ 查不了：**纯声明**（「不向第三方共享」「不上传」）—— 那些仍只能靠人审。
 * 本测试的作用是把漂移从「看不见」变成「跑测试就能发现」，而不是让它不可能发生。
 */
class PolicyConsistencyTest {

    // ------------------------------------------------------------------ 基础设施

    /**
     * 定位仓库根。工作目录在本地与 CI 上可能不同（模块目录 / 仓库根），
     * 故以 `settings.gradle.kts` 为标记向上查找，两种情形都能命中。
     */
    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError(
            "定位不到仓库根（没找到 settings.gradle.kts）。当前目录：" +
                File(".").absolutePath
        )
    }

    /** 取某语言的隐私政策正文（`strings.xml` 里被 `\n` 转义成一行的那个字符串）。 */
    private fun policyText(localeDir: String): String {
        val f = File(repoRoot(), "app/src/main/res/$localeDir/strings.xml")
        assertTrue("找不到 $localeDir/strings.xml：${f.absolutePath}", f.isFile)
        val xml = f.readText()
        val m = Regex("""<string name="privacy_policy_text">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml) ?: throw AssertionError("$localeDir/strings.xml 里没有 privacy_policy_text")
        return m.groupValues[1]
    }

    /** 从正文里抠出一个数字，抠不到就直接失败并提示去看措辞。 */
    private fun numberIn(text: String, regex: Regex, what: String): Int {
        val m = regex.find(text)
            ?: throw AssertionError(
                "在政策正文里找不到「$what」。\n" +
                    "若不是措辞被改写，就是这句话被删了 —— 两种情况都要人工确认后，\n" +
                    "同步更新本测试里的正则（正则即「政策承诺了什么」的机器可读形式）。"
            )
        return m.groupValues[1].toInt()
    }

    // ------------------------------------------------------------------ ① 数字类

    @Test
    fun `政策承诺的历史保留条数与代码常量一致`() {
        val expected = BrowserDb.HISTORY_KEEP

        // 中文：「浏览历史仅保留最近 200 条」
        val zh = policyText("values")
        assertEquals(
            "中文政策承诺的历史保留条数与 BrowserDb.HISTORY_KEEP 不一致",
            expected,
            numberIn(zh, Regex("""浏览历史仅保留最近\s*(\d+)\s*条"""), "浏览历史仅保留最近 N 条"),
        )

        // 英文：「history keeps only the most recent 200 entries」
        val en = policyText("values-en")
        assertEquals(
            "英文政策承诺的历史保留条数与 BrowserDb.HISTORY_KEEP 不一致",
            expected,
            numberIn(en, Regex("""most recent (\d+) entries"""), "the most recent N entries"),
        )
    }

    @Test
    fun `政策承诺的崩溃日志保留条数与代码常量一致`() {
        val expected = CrashLogger.MAX_KEEP

        val zh = policyText("values")
        assertEquals(
            "中文政策承诺的崩溃日志保留条数与 CrashLogger.MAX_KEEP 不一致",
            expected,
            numberIn(zh, Regex("""日志自动保留最近\s*(\d+)\s*条"""), "日志自动保留最近 N 条"),
        )

        val en = policyText("values-en")
        assertEquals(
            "英文政策承诺的崩溃日志保留条数与 CrashLogger.MAX_KEEP 不一致",
            expected,
            numberIn(en, Regex("""capped at the (\d+) most recent"""), "capped at the N most recent"),
        )
    }

    // ------------------------------------------------------------------ ② 枚举类：权限

    @Test
    fun `应用声明的每一项权限都在政策里被提及`() {
        val manifest = File(repoRoot(), "app/src/main/AndroidManifest.xml")
        assertTrue("找不到 AndroidManifest.xml", manifest.isFile)
        val declared = Regex("""<uses-permission\s+android:name="([^"]+)"""")
            .findAll(manifest.readText())
            .map { it.groupValues[1] }
            .toList()

        assertTrue("清单里一项权限都没解析到，正则可能失效了", declared.isNotEmpty())

        val zh = policyText("values")
        val en = policyText("values-en")
        for (full in declared) {
            // 政策里用的是简名（INTERNET / CAMERA），故取最后一段
            val short = full.substringAfterLast('.')
            assertTrue(
                "应用声明了权限 $full，但中文政策第 2 条没有提到「$short」。\n" +
                    "新增权限必须同步写进政策（并更新 tools/verify_release.py 的权限基准）。",
                zh.contains(short),
            )
            assertTrue("应用声明了权限 $full，但英文政策没有提到「$short」", en.contains(short))
        }
    }

    @Test
    fun `政策里列出的每个外部主机名也都出现在 README 的隐私说明中`() {
        // 为什么单独查 README：README 的「隐私」段是一份**手抄的摘要**，不随政策文本走。
        // 实测漂移过一次 —— 2.1.1 为「检查更新」新增了 api.github.com，
        // 政策 §4 与同意页摘要都同步了，README 却仍写着「仅访问 Mozilla 官方服务」。
        // 政策与 README 是两份文本，只有把这条写成断言才拦得住下一次。
        val hosts = hostsInPolicyNetworkSection()
        assertTrue("政策第 4 条里没解析出任何主机名，正则可能失效了", hosts.isNotEmpty())

        val readme = File(repoRoot(), "README.md").readText()
        for (h in hosts) {
            assertTrue(
                "政策第 4 条披露了外部地址「$h」，但 README 的隐私说明里没有它。\n" +
                    "README 是对外第一眼看到的地方，漏一处就是一次失准 —— " +
                    "请同步 README.md 的「隐私」段。",
                readme.contains(h),
            )
        }
    }

    /**
     * 取政策「网络访问对象」一节里出现的所有主机名。
     *
     * 只取这一节而不是整篇政策：别处也会出现域名（如第 3 条列举默认搜索引擎 `cn.bing.com`），
     * 那是「用户选了哪个搜索引擎」的说明，不属于本应用主动联系的服务清单。
     */
    private fun hostsInPolicyNetworkSection(): Set<String> {
        val text = policyText("values")
        val start = text.indexOf("4. ")
        val end = text.indexOf("5. ", start + 1)
        val section = if (start >= 0 && end > start) text.substring(start, end) else text
        return Regex("""\b([a-z0-9][a-z0-9-]*(?:\.[a-z0-9-]+)+\.(?:com|org|net|io|dev))\b""")
            .findAll(section)
            .map { it.groupValues[1] }
            .toSet()
    }

    // ------------------------------------------------------------------ ③ 对外端点披露

    @Test
    fun `更新检查所用的对外端点已在政策中披露`() {
        // 「检查更新」会访问 api.github.com —— 它是本应用第三个对外端点，
        // 政策 §4 必须写明。这条断言把「代码里加了端点」与「政策里写了」绑在一起：
        // 以后若再引入新的对外请求而忘了改政策，这里会失败。
        //
        // 只针对 UpdateChecker 这一个已知端点做定点检查。若将来对外端点变多，
        // 可扩展为「扫描源码里所有字面量 https 主机名，逐个要求出现在政策中」——
        // 但那会引入注释/示例 URL 的误报，需先设计好白名单。
        val host = java.net.URI(UpdateChecker.LATEST_RELEASE_API).host
        assertTrue("解析不出对外端点的主机名", !host.isNullOrBlank())

        assertTrue(
            "应用会访问 $host，但中文政策第 4 条没有披露它。\n" +
                "新增对外端点属实质变化：除补政策文本外，还需把 POLICY_VERSION +1。",
            policyText("values").contains(host),
        )
        assertTrue("应用会访问 $host，但英文政策没有披露它", policyText("values-en").contains(host))
    }

    // ------------------------------------------------------------------ ④ 枚举类：崩溃日志字段

    @Test
    fun `崩溃日志写入的字段集合与预期一致`() {
        val src = File(
            repoRoot(),
            "app/src/main/java/io/github/tan_sno/tangsnow/util/CrashLogger.kt",
        )
        assertTrue("找不到 CrashLogger.kt", src.isFile)

        // 取 appendLine("标签: …") 里的标签；无冒号的行（应用名表头、分隔线）不算字段
        val actual = Regex("""appendLine\("([^":]+):""")
            .findAll(src.readText())
            .map { it.groupValues[1].trim() }
            .toSet()

        assertEquals(
            "崩溃日志写入的字段变了。\n" +
                "这不是「实现错了」，而是**政策 §7 的字段枚举很可能要同步改** ——\n" +
                "该条用的是穷尽性措辞（「仅含…」），代码多写一个字段就会让承诺不成立。\n" +
                "确认政策已跟上后，再更新本测试的预期集合。",
            EXPECTED_CRASH_LOG_FIELDS,
            actual,
        )
    }

    @Test
    fun `崩溃日志的每一类字段都在政策里有对应表述`() {
        val zh = policyText("values")
        val en = policyText("values-en")
        for ((label, keyword) in CRASH_LOG_FIELD_KEYWORDS) {
            assertTrue(
                "崩溃日志会写入「$label」，但中文政策 §7 里找不到「$keyword」。\n" +
                    "政策的措辞可能被改写、或这一项漏了披露。",
                zh.contains(keyword),
            )
        }
        // 英文对应项抽查两个最关键的
        assertTrue("英文政策未披露崩溃时间", en.contains("crash time"))
        assertTrue("英文政策未披露域名记录", en.contains("domain"))
    }

    private companion object {
        /**
         * `CrashLogger` 应当写入的字段标签全集。
         *
         * 这是**快照**：代码里增删字段时本测试会失败，从而强制回看政策 §7 ——
         * 那道「仅含…」的穷尽性承诺是否需要同步改。
         */
        val EXPECTED_CRASH_LOG_FIELDS = setOf(
            "时间", "进程", "线程", "版本", "设备", "系统", "最近访问站点（仅域名）",
        )

        /**
         * 字段标签 → 政策里应当出现的对应表述。
         * 两边用词本就不同（代码写「设备」，政策写「设备型号」），故需显式映射；
         * 映射本身也是「人工确认过两边说的是同一件事」的记录。
         */
        val CRASH_LOG_FIELD_KEYWORDS = mapOf(
            "时间" to "崩溃时间",
            "进程" to "进程",
            "线程" to "线程",
            "版本" to "应用名称与版本",
            "设备" to "设备型号",
            "系统" to "系统版本",
            "最近访问站点（仅域名）" to "最近访问站点的域名",
        )
    }
}
