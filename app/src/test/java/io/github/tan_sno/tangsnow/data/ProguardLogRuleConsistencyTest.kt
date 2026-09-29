package io.github.tan_sno.tangsnow.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **proguard 说明里「哪些日志会进 release」的口径与源码事实的一致性单测**。
 *
 * ## 为什么需要它
 *
 * `app/proguard-rules.pro` 里那段注释是「release 会保留哪些日志」的**唯一书面依据**
 * （自动扫描 / 应用商店审查都只会看它）。而这个数字是**手工清点**出来的：
 * 2026-09-29 复核时就发现两处失真 —— 注释写「Log.w ×3、实际 4 处」，
 * 且「全仓没有任何 Log.v / Log.d 调用点」这句也已不成立（实际有 2 处 Log.d）。
 * 两次都不是崩溃，而是**合规口径失真**：读注释的人会得到错误结论。
 *
 * 故把这条不变式写成断言：改日志调用点却忘同步那段注释，`testDebugUnitTest` 直接红。
 *
 * ## 判定规则（明确写出来，因为它用了启发式）
 *
 * 逐个 `Log.w(` / `Log.e(` 调用点：若**同一行或其上方 6 行内**出现 `BuildConfig.DEBUG`，
 * 视为「debug 门内、release 不存在」；否则视为会进 release。
 *
 * 这是启发式，不是编译器级证明 —— 故意如此：它只要求「守卫贴在被守卫的调用旁边」，
 * 而这恰好也是本仓的一贯写法。真出现误报，修法二选一：把 `BuildConfig.DEBUG` 守卫挪到
 * 该调用旁边，或者更新 proguard 注释里的数字（两者都等于把事实重新对齐）。
 *
 * ## 覆盖范围与边界（别高估它）
 *
 * ✅ 能查：**数字类** —— release 保留的 w / e 数量；以及 d / i / v 仍在剥离规则内。
 * ❌ 查不了：**日志内容里有没有 URL / 搜索词** —— 那仍只能靠人审（注释里的「约定」段）。
 */
class ProguardLogRuleConsistencyTest {

    /** 定位仓库根：以 `settings.gradle.kts` 为标记向上查找（工作目录可能是模块目录）。 */
    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("定位不到仓库根（没找到 settings.gradle.kts）")
    }

    private fun proguardText(): String {
        val f = File(repoRoot(), "app/proguard-rules.pro")
        assertTrue("找不到 proguard-rules.pro：${f.absolutePath}", f.isFile)
        return f.readText()
    }

    /**
     * 抠出注释声明的数字（如「Log.w ×5」里的 5）。
     * 正则即「注释承诺了什么」的机器可读形式；措辞被改写就该同步改这里。
     */
    private fun declared(text: String, level: String): Int {
        val m = Regex("""Log\.$level ×(\d+)""").find(text)
            ?: throw AssertionError(
                "proguard-rules.pro 里找不到「Log.$level ×N」的口径说明。\n" +
                    "若该段被改写，请同步本测试的正则。"
            )
        return m.groupValues[1].toInt()
    }

    /** 会进入 release 的调用点（见类注释的判定规则），返回 `相对路径:行号` 便于定位。 */
    private fun releaseCallSites(level: String): List<String> {
        val sourceRoot = File(repoRoot(), "app/src/main/java")
        assertTrue("找不到主源码目录：${sourceRoot.absolutePath}", sourceRoot.isDirectory)
        val needle = "Log.$level("
        return sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { f ->
                val lines = f.readLines()
                lines.asSequence().mapIndexedNotNull { i, line ->
                    if (!line.contains(needle)) return@mapIndexedNotNull null
                    val from = (i - 6).coerceAtLeast(0)
                    val guarded = lines.subList(from, i + 1).any { it.contains("BuildConfig.DEBUG") }
                    if (guarded) null else "${f.relativeTo(sourceRoot)}:${i + 1}"
                }
            }
            .sorted()
            .toList()
    }

    @Test
    fun `proguard 说明声明的 release 日志数量与代码一致`() {
        val declaredText = proguardText()
        for (level in listOf("w", "e")) {
            val sites = releaseCallSites(level)
            assertEquals(
                "release 保留的 Log.$level 数量与 proguard 说明不一致。\n" +
                    "实际会进 release 的调用点：\n" +
                    sites.joinToString("\n") { "  $it" } +
                    "\n（新增调用点请给它加 BuildConfig.DEBUG 守卫，或同步那段注释里的数字）",
                declared(declaredText, level),
                sites.size,
            )
        }
    }

    @Test
    fun `d i v 三级仍在剥离规则内`() {
        val block = Regex(
            """-assumenosideeffects class android\.util\.Log \{(.*?)\}""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(proguardText())?.groupValues?.get(1)
            ?: throw AssertionError(
                "proguard-rules.pro 里找不到 android.util.Log 的 -assumenosideeffects 规则。\n" +
                    "没有它，Log.d / Log.i / Log.v 会连同字符串一起进 release。"
            )
        for (level in listOf("v", "d", "i")) {
            assertTrue(
                "剥离规则里缺少 Log.$level —— 该级的调用点会被带进 release",
                Regex("""\b$level\(\.\.\.\)""").containsMatchIn(block),
            )
        }
    }
}
