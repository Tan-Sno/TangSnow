package io.github.tan_sno.tangsnow.browser

import io.github.tan_sno.tangsnow.TestPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **App 主题与内核颜色方案的接线必须留在位**。
 *
 * ## 为什么要钉
 * `Theme`（`data/Theme.kt`）只驱动 Android 视图层（`ThemeController.apply` →
 * `AppCompatDelegate.setDefaultNightMode`）。内核（GeckoView）**不会自动知道** App 主题：
 * 不显式告知时它按系统值渲染，于是用户选「浅色」而系统是深色时，网页仍走深色
 * （`prefers-color-scheme` 不跟随 App），表现为「App 浅色 / 网页深色」的割裂。
 *
 * 这条链路没有任何编译期约束：删掉 `runtime()` 的 Builder 项、或删掉 `applyLiveSettings()`
 * 里的重套调用，工程照常编译、单测照常绿 —— 只有真机换个主题才看得出。故用读源码的方式
 * 把它变成红灯（与 `ReleaseGateConsistencyTest` 同一手法）。
 *
 * ## 判据的边界
 * 只断**源码文本形状**（三档映射逐档对应哪个常量、两处调用在位、热改 key 含主题），
 * **不加载 GeckoView 类型**（JVM 单测加载不了；且 `COLOR_SCHEME_*` 的取值由内核定义，
 * 不是本应用的口径）。因此本测试防的是「接线被删/被串档」，不是「内核渲染对不对」。
 */
class ColorSchemeWiringTest {

    @Test
    fun `主题三档到内核颜色方案的映射与两处接线都在位`() {
        val src = File(TestPaths.repoRoot(), SRC_PATH).readText()

        // 映射：三档逐一对应，不允许漏档或串档
        assertMapping(src, "SYSTEM", "COLOR_SCHEME_SYSTEM")
        assertMapping(src, "LIGHT", "COLOR_SCHEME_LIGHT")
        assertMapping(src, "DARK", "COLOR_SCHEME_DARK")

        // 首次创建：Builder 链里必须设一次，否则首启（冷启动）就割裂
        assertTrue(
            "runtime() 的 GeckoRuntimeSettings.Builder 链里找不到 preferredColorScheme(...)",
            Regex("""\.preferredColorScheme\s*\(""").containsMatchIn(src),
        )

        // 热改：主题切换只重建 Activity、不重建内核（GeckoRuntime 是应用级单例），
        //       故 applyLiveSettings() 必须重套一次；且变化守卫的 key 要含主题，
        //       否则「其余值都没变」时会被提前 return 掉。
        val live = src.substringAfter("fun applyLiveSettings()", "").substringBefore("\n    /**")
        assertTrue(
            "applyLiveSettings() 里找不到 setPreferredColorScheme(...)",
            Regex("""setPreferredColorScheme\s*\(""").containsMatchIn(live),
        )
        assertTrue(
            "applyLiveSettings() 的变化守卫 key 里没有主题（改主题后会被当成「值未变化」跳过）",
            Regex("""\$\{prefs\.theme[.\w]*\}""").containsMatchIn(live),
        )
    }

    private fun assertMapping(src: String, theme: String, constant: String) {
        assertTrue(
            "找不到 `Theme.$theme -> GeckoRuntimeSettings.$constant` 的映射（漏档或串档）",
            Regex("""Theme\.$theme\s*->\s*GeckoRuntimeSettings\.$constant""").containsMatchIn(src),
        )
        assertEquals(
            "`Theme.$theme` 的映射分支不止一处（串档）",
            1,
            Regex("""Theme\.$theme\s*->""").findAll(src).count(),
        )
    }

    private companion object {
        const val SRC_PATH =
            "app/src/main/java/io/github/tan_sno/tangsnow/browser/SessionManager.kt"
    }
}