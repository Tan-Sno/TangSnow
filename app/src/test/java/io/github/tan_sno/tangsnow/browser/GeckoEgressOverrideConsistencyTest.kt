package io.github.tan_sno.tangsnow.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **内核默认出网点覆盖清单的哨兵**（配套 `GECKO_EGRESS_OVERRIDE_PREFS`）。
 *
 * ## 为什么需要它
 *
 * 这份覆盖清单来自对 geckoview 157 `greprefs.js` / `geckoview-prefs.js` 的逐条取证
 * （取证依据写在常量注释里），它决定「政策 §4 封闭式枚举之外，内核还会不会自己出网」。
 * 两类漂移都必须红灯：
 *  1. 有人加了/删了一条 pref（取证依据与实际下发不一致 ⇒ 枚举可能失真）；
 *  2. 有人把某条的值改成了非收窄方向（该文件**只允许** false / 空串两种取值 ——
 *     它的职责是「关」，不是「配」；要开新能力请走公开 API，别把这里当后门）。
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

    private fun actualPrefs(): Map<String, String> =
        BrowserSessionManager.GECKO_EGRESS_OVERRIDE_PREFS.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") }
            .associate { line ->
                val m = Regex("""pref\("([^"]+)", (.+)\);""").matchEntire(line)
                    ?: throw AssertionError("覆盖文件里出现无法解析的行：$line")
                m.groupValues[1] to m.groupValues[2]
            }

    @Test
    fun `覆盖清单与取证结论逐条一致`() {
        assertEquals(expected, actualPrefs())
    }

    @Test
    fun `覆盖值只允许收窄方向（false 或空串）`() {
        for ((name, value) in actualPrefs()) {
            assertTrue(
                "$name 的覆盖值不是收窄方向（只允许 false / \"\"）：$value",
                value == "false" || value == "\"\"",
            )
        }
    }
}
