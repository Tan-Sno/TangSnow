package io.github.tan_sno.tangsnow.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **内核默认出网点覆盖文件的哨兵**（配套 `SessionManager.GECKO_EGRESS_OVERRIDE_PREFS`）。
 *
 * ## 为什么需要它
 * 这份覆盖清单来自对 geckoview 157 `greprefs.js` / `geckoview-prefs.js` 的逐条取证
 * （依据写在常量注释里），它决定「政策 §4 封闭式枚举之外，内核还会不会自己出网」。三类漂移都必须红灯：
 *  1. **格式错了**（最容易发生、也最隐蔽的一类）：geckoview 只认 **YAML**（顶层键 `prefs`），
 *     写成 `pref("k", v);` 的 JS 语法会被**整体忽略且只在 logcat 记一条错误** —— 于是清单看起来还在、
 *     实际什么都没关。（2026-10-01 实际踩过：文件当时就叫 `gecko-egress-overrides.js`。）
 *  2. 加了/删了一条 pref（取证依据与实际下发不一致 ⇒ 枚举可能失真）；
 *  3. 值被改成非收窄方向（本文件**只允许** false / 空串两种取值 —— 它的职责是「关」不是「配」；
 *     要开新能力请走公开 API，别把这里当后门）。
 */
class GeckoEgressOverrideConsistencyTest {

    /** 逐条预期（pref 名 → 值）。取证依据见 SessionManager 常量注释，改动须两边同步。 */
    private val expected = mapOf(
        "network.connectivity-service.enabled" to "false",   // 明文连通性探测（firefox-portal-detection.com）
        "captivedetect.canonicalURL" to "\"\"",              // 同上的探测目标（双保险置空）
        "network.connectivity-service.IPv4.url" to "\"\"",
        "network.connectivity-service.IPv6.url" to "\"\"",
        "browser.region.network.url" to "\"\"",              // location.services.mozilla.com 区域探测
        "extensions.systemAddon.update.enabled" to "false",  // aus5.mozilla.org（无系统扩展）
        "extensions.systemAddon.update.url" to "\"\"",
        "media.gmp-manager.url" to "\"\"",                   // aus5.mozilla.org（无内置 GMP）
    )

    private val text: String get() = BrowserSessionManager.GECKO_EGRESS_OVERRIDE_PREFS

    /**
     * 极简 YAML 解析：只认本文件用的那一种形状 —— 顶层 `prefs:` + **两空格缩进**的 `键: 值`。
     * 刻意不引 YAML 依赖（项目刻意不引 Robolectric/额外库）；形状不对就**抛错**，
     * 让"格式写错"变成红灯，而不是悄悄返回空表。
     */
    private fun parsePrefs(): Map<String, String> {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.trim() == "prefs:" }
        if (start < 0) {
            throw AssertionError("覆盖文件缺少 `prefs:` 顶层键 —— geckoview 的配置只认 YAML（键 prefs）")
        }
        val out = LinkedHashMap<String, String>()
        for (raw in lines.drop(start + 1)) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            // 缩进结束（回到顶格）即 prefs 段结束
            if (!raw.startsWith("  ") || raw.startsWith("   ")) break
            val idx = trimmed.indexOf(": ")
            if (idx <= 0) throw AssertionError("覆盖文件里出现无法解析的 YAML 行：$trimmed")
            out[trimmed.substring(0, idx)] = trimmed.substring(idx + 2)
        }
        return out
    }

    @Test
    fun `必须是 YAML 形状：有 prefs 段、无 JS 语法、无 args 与 env 段`() {
        assertTrue("覆盖文件必须含 `prefs:` 段", text.lines().any { it.trim() == "prefs:" })
        assertTrue("覆盖文件里出现 JS 语法 pref( —— 内核按 YAML 解析会整体忽略", !text.contains("pref("))
        // args / env 是**扩大**能力的两把钥匙（传给 Gecko 进程的命令行与环境变量），本文件不该出现
        assertTrue("覆盖文件不该出现 args 段", !text.lines().any { it.trim() == "args:" })
        assertTrue("覆盖文件不该出现 env 段", !text.lines().any { it.trim() == "env:" })
        assertTrue("prefs 段必须解析出非空清单", parsePrefs().isNotEmpty())
    }

    @Test
    fun `覆盖清单与取证结论逐条一致`() {
        assertEquals(expected, parsePrefs())
    }

    @Test
    fun `覆盖值只允许收窄方向（false 或空串）`() {
        for ((name, value) in parsePrefs()) {
            assertTrue(
                "$name 的覆盖值不是收窄方向（只允许 false / \"\"）：$value",
                value == "false" || value == "\"\"",
            )
        }
    }

    @Test
    fun `覆盖文件必须真的被交给内核（注入路径哨兵）`() {
        // CR-006：上面三条只校验**常量内容与形状**，测不到"接线"—— 把
        // `writeEgressOverrideFile()?.let { b.configFilePath(...) }` 删掉（文件照写、永不注入内核）时，
        // 上面三条仍全绿，而隐私承诺「政策 §4 之外内核不再自动出网」就失去了回归保护。
        // 这里用**源码扫描**做廉价但有效的接线检查（与 `ProguardLogRuleConsistencyTest` 同一手法）。
        val src = File(repoRoot(), SESSION_MANAGER_SRC).readText()
        assertTrue(
            "SessionManager 里找不到 `.configFilePath(`：覆盖文件不会被注入内核运行时设置",
            src.contains(".configFilePath("),
        )
        assertTrue(
            "configFilePath 必须接在 writeEgressOverrideFile() 的结果上（否则注入的是别的路径）",
            Regex("""writeEgressOverrideFile\(\)\?\.let\s*\{\s*\w+\.configFilePath\(""").containsMatchIn(src),
        )
        assertTrue(
            "覆盖文件名必须是 .yaml：原先的 .js（JS 语法）会被内核整体忽略",
            src.contains("\"$EXPECTED_FILE_NAME\""),
        )
    }

    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("定位不到仓库根（没找到 settings.gradle.kts）")
    }

    private companion object {
        const val SESSION_MANAGER_SRC =
            "app/src/main/java/io/github/tan_sno/tangsnow/browser/SessionManager.kt"

        /** 与 `SessionManager.GECKO_EGRESS_OVERRIDE_FILE` 同值；改了那边这条会红（刻意的双写） */
        const val EXPECTED_FILE_NAME = "gecko-egress-overrides.yaml"
    }
}
