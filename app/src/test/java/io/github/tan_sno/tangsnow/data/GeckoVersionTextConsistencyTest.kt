package io.github.tan_sno.tangsnow.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **「关于」页开源声明的 GeckoView 版本号与实际依赖版本的一致性单测**。
 *
 * ## 为什么需要它
 *
 * `open_source_text` 是 MPL 2.0 合规义务下用户可见的许可证声明，里面手写了引擎版本号
 * （如「Mozilla GeckoView（v157）」）。2026-09-30 引擎 155 → 157 升级时该文本被漏改
 * —— 升级提交自称「同步全部版本文本」，但这个**藏在超长单行字符串里的数字**没有任何
 * 测试钉住，全绿的门禁照常通过。版本声明失真属于事实性错误（不是排版问题），
 * 故把「文本里的主版本号 == 依赖声明的主版本号」写成断言。
 *
 * ## 判定规则
 *
 *  - 版本事实来源：`gradle/libs.versions.toml` 的 `geckoView = "…"`，取首个 `.` 前的主版本号；
 *  - 文本断言：中文（全角括号）与英文（半角括号）两侧的 `open_source_text` 都必须
 *    包含 `GeckoView（v<主版本>）` / `GeckoView (v<主版本>)`。
 *
 * 升级引擎时改了 toml 却忘改文案，`testDebugUnitTest` 直接红。
 */
class GeckoVersionTextConsistencyTest {

    /** 定位仓库根：以 `settings.gradle.kts` 为标记向上查找（工作目录可能是模块目录）。 */
    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("定位不到仓库根（没找到 settings.gradle.kts）")
    }

    /** toml 里 geckoView 声明的主版本号（`"157.0.20260924…"` → `"157"`） */
    private fun geckoMajorVersion(root: File): String {
        val toml = File(root, "gradle/libs.versions.toml").readText()
        val m = Regex("""geckoView\s*=\s*"(\d+)\.""").find(toml)
            ?: throw AssertionError("libs.versions.toml 里定位不到 geckoView 版本声明")
        return m.groupValues[1]
    }

    /** 从 strings.xml 取出 `open_source_text` 所在行（该文件全部是单行条目） */
    private fun openSourceLine(root: File, localeDir: String): String {
        val f = File(root, "app/src/main/res/$localeDir/strings.xml")
        val line = f.readLines().firstOrNull { it.contains("""name="open_source_text"""") }
            ?: throw AssertionError("$localeDir/strings.xml 里定位不到 open_source_text")
        return line
    }

    @Test
    fun `中文开源声明写的是实际依赖的引擎主版本`() {
        val major = geckoMajorVersion(repoRoot())
        val line = openSourceLine(repoRoot(), "values")
        assertTrue(
            "values/strings.xml 的 open_source_text 仍是旧版本号（应为 GeckoView（v$major））",
            line.contains("GeckoView（v$major）"),
        )
    }

    @Test
    fun `英文开源声明写的是实际依赖的引擎主版本`() {
        val major = geckoMajorVersion(repoRoot())
        val line = openSourceLine(repoRoot(), "values-en")
        assertTrue(
            "values-en/strings.xml 的 open_source_text 仍是旧版本号（应为 GeckoView (v$major)）",
            line.contains("GeckoView (v$major)"),
        )
    }

    @Test
    fun `两个语言侧声明的版本号彼此一致`() {
        val root = repoRoot()
        val zhNum = Regex("""GeckoView（v(\d+)）""").find(openSourceLine(root, "values"))
            ?.groupValues?.get(1)
            ?: throw AssertionError("values/strings.xml 的 open_source_text 里没有 GeckoView（vN）字样")
        val enNum = Regex("""GeckoView \(v(\d+)\)""").find(openSourceLine(root, "values-en"))
            ?.groupValues?.get(1)
            ?: throw AssertionError("values-en/strings.xml 的 open_source_text 里没有 GeckoView (vN) 字样")
        assertEquals("中英两侧声明的引擎版本号不一致", zhNum, enNum)
    }
}
