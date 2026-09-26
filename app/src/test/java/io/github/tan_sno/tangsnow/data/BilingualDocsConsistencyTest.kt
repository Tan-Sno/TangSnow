package io.github.tan_sno.tangsnow.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **中英双语文档的一致性单测**。
 *
 * ## 为什么需要它
 *
 * 仓库的对外文档以「英文文件（GitHub 首页/链接默认渲染）+ `.zh-CN.md`（切换后）」成对存在 ——
 * GitHub 没有官方的文档语言切换，双文件加顶部语言链接是通行做法。
 * 双份文档的必然风险是**漂移**：改了一边忘了另一边、某节被整段删掉、语言链接指错。
 * 这类问题不报错也不崩溃，只能靠人碰巧发现；本仓库在政策文本与代码注释上已多次吃过这个亏。
 *
 * 因此把它写成断言，让 `testDebugUnitTest` 直接变红。
 *
 * ## 覆盖范围与边界（别高估它）
 *
 * ✅ 能查：文档对是否齐全、语言链接是否互指、**章节标题序列**是否对齐、
 *    关键**内容标识符**（权限名 / 对外主机名 / 应用标识 / 内核版本）是否**两侧对称**。
 * ❌ 查不了：正文是否真的译得对、译得全、语气是否得当 —— 那仍然只能靠人审。
 *
 * 刻意**不用文件名**做标识符（英文版是 `X.md`、中文版是 `X.zh-CN.md`，天然不对称）；
 * 只用两侧写法相同的**内容**标识符。
 */
class BilingualDocsConsistencyTest {

    /** 英文（默认）↔ 中文（切换后）成对的仓库级文档 */
    private val pairs = listOf(
        "README.md" to "README.zh-CN.md",
        "SECURITY.md" to "SECURITY.zh-CN.md",
        "CONTRIBUTING.md" to "CONTRIBUTING.zh-CN.md",
        "CODE_OF_CONDUCT.md" to "CODE_OF_CONDUCT.zh-CN.md",
        "THIRD_PARTY_NOTICES.md" to "THIRD_PARTY_NOTICES.zh-CN.md",
    )

    /** 工作目录在本地与 CI 上可能不同（模块目录 / 仓库根），以 `settings.gradle.kts` 为标记向上找 */
    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("定位不到仓库根（没找到 settings.gradle.kts）")
    }

    private fun read(name: String): String {
        val f = File(repoRoot(), name)
        assertTrue("找不到文档：$name", f.isFile)
        return f.readText()
    }

    /** 一到三级标题的「级别」序列，用于比对两份文档的结构骨架 */
    private fun headingShape(markdown: String): List<Int> =
        Regex("""^(#{1,3}) .+$""", RegexOption.MULTILINE)
            .findAll(markdown)
            .map { it.groupValues[1].length }
            .toList()

    @Test
    fun `每份英文文档都有对应的中文版`() {
        for ((en, zh) in pairs) {
            assertTrue("缺少英文版：$en", File(repoRoot(), en).isFile)
            assertTrue("缺少中文版：$zh（英文版是 $en）", File(repoRoot(), zh).isFile)
        }
    }

    @Test
    fun `两份文档的章节结构对齐`() {
        for ((en, zh) in pairs) {
            assertEquals(
                "$en 与 $zh 的标题级别序列不一致 —— 常见原因：改了一边忘了另一边，" +
                    "或某节被整段删掉/新增。",
                headingShape(read(en)),
                headingShape(read(zh)),
            )
        }
    }

    @Test
    fun `两份文档顶部互相链接`() {
        for ((en, zh) in pairs) {
            val enText = read(en)
            val zhText = read(zh)
            assertTrue("$en 顶部应链到 $zh", enText.contains("]($zh)"))
            assertTrue("$zh 顶部应链到 $en", zhText.contains("]($en)"))
            // 不该出现「链到自己」的写法（多半是从另一份复制过来忘了改）
            assertTrue("$en 里出现了指向自身的语言链接", !enText.contains("]($en)"))
            assertTrue("$zh 里出现了指向自身的语言链接", !zhText.contains("]($zh)"))
        }
    }

    @Test
    fun `关键内容标识符在两份文档里出现次数一致`() {
        // 这些标识符在中文版里也保持原文写法，因此可以直接按字符串比对次数。
        // 只要求**两侧对称**（都 0 次也无妨 —— 有些事实只在个别文档里讲），
        // 不允许单边：单边出现就是漏译或漏改的直接信号。
        val identifiers = listOf(
            // 权限（README 的「权限」一节）
            "INTERNET", "CAMERA", "ACCESS_LOCAL_NETWORK",
            // 对外主机名（README 的「隐私」一节；改这里前先看 PolicyConsistencyTest 的要求）
            "addons.mozilla.org", "firefox.settings.services.mozilla.com", "api.github.com",
            // 应用标识（README 的 2.0.x 升级说明）
            "io.github.tan_sno.tangsnow", "com.tangsnow.tangsnow",
            // 内核版本与许可证（README 与 THIRD_PARTY_NOTICES）
            "155.0.20260903215306", "Apache License 2.0", "MPL 2.0",
        )
        for ((en, zh) in pairs) {
            val enText = read(en)
            val zhText = read(zh)
            for (id in identifiers) {
                assertEquals(
                    "标识符「$id」在 $en 与 $zh 中出现的次数不同（前者 ${enText.split(id).size - 1} 次、" +
                        "后者 ${zhText.split(id).size - 1} 次）—— 要么漏译，要么一边改了另一边没改。",
                    enText.split(id).size,
                    zhText.split(id).size,
                )
            }
        }
    }
}
