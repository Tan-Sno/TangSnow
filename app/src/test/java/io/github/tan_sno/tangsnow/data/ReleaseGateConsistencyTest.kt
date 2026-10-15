package io.github.tan_sno.tangsnow.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **发布链路口径的一致性单测**（构建脚本 ↔ 门禁脚本 ↔ 实际工具文件）。
 *
 * ## 为什么需要它
 * 第 ⑩ 项门禁（2026-10-15）和 `core` flavor 维度把三样东西**抄成了两份**：
 * ABI 集合与它的 versionCode 序号同时存在于 `app/build.gradle.kts` 的 `abiCodes` 与
 * `tools/verify_release.py` 的 `ABI_ORDER`；flavor 名同时存在于 `productFlavors` 与
 * 门禁的 `DEFAULT_FLAVOR`；而 `verify_release.py` 要调用的 `tools/check_zip_padding.py`
 * 是个独立文件。原来那处代码只能留一句「请同步本表与 tools/verify_release.py」——
 * **靠人记着的同步就是没同步**，本仓已经吃过两次这类亏（`ProguardLogRuleConsistencyTest`
 * 与 `TestCountCommentTest` 都是为此而生的）。
 *
 * ## 口径
 * 一律 fail-closed：解析不出期望形状就**红**，不允许「看不懂就跳过」演变成漏检。
 * ❌ 查不了：两处的**语义**是否都对（例如序号 1/2/3 是否与真机 ABI 优先级一致）——
 * 那要产物级验证；本测试只保证「改了一处忘了改另一处」当场可见。
 */
class ReleaseGateConsistencyTest {

    private val buildText: String by lazy { File(repoRoot(), "app/build.gradle.kts").readText() }
    private val gateText: String by lazy { File(repoRoot(), "tools/verify_release.py").readText() }

    @Test
    fun `ABI 集合与 versionCode 序号在构建脚本与门禁脚本里完全一致`() {
        // 构建侧：abiCodes = mapOf("arm64-v8a" to 1, "armeabi-v7a" to 2, "x86_64" to 3)
        val gradleAbi = Regex(
            """abiCodes\s*=\s*mapOf\((.*?)\)""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(buildText)?.groupValues?.get(1)
            ?: error("app/build.gradle.kts 里找不到 abiCodes = mapOf(...) 这张表（改了写法就同步改本测试）")
        val gradlePairs = pairsOf(gradleAbi)
        // 门禁侧：ABI_ORDER = {"arm64-v8a": 1, "armeabi-v7a": 2, "x86_64": 3}
        val pyBlock = Regex("""ABI_ORDER\s*=\s*\{([^}]*)\}""").find(
            gateText
        )?.groupValues?.get(1)
            ?: error("tools/verify_release.py 里找不到 ABI_ORDER = {...}（改了写法就同步改本测试）")
        val pyPairs = pairsOf(pyBlock)

        assertEquals(
            "ABI → versionCode 序号这两张表已经飘了（顺序不算差异，成员或序号差了才是）：" +
                "构建侧 $gradlePairs，门禁侧 $pyPairs",
            gradlePairs.toSet(),
            pyPairs.toSet(),
        )
        // splits 里 include 的 ABI 必须是同一集合（否则某个 flavor 打出来的包不在校验链里）
        val included = Regex("""include\(([^)]*)\)""").find(
            buildText
        )?.groupValues?.get(1)?.split(",")?.map { it.trim().trim('"') }?.filter { it.isNotEmpty() }
            ?: error("app/build.gradle.kts 里找不到 splits.abi.include(...)")
        assertEquals(
            "splits.include 与 abiCodes 的 ABI 集合不一致（新 ABI 忘了登记偏移，或登记了不打的 ABI）",
            gradlePairs.map { it.first }.toSet(),
            included.toSet(),
        )
    }

    @Test
    fun `flavor 名与门禁的产物文件名口径一致`() {
        val flavors = Regex("""create\("([^"]+)"\)""").findAll(buildText)
            .map { it.groupValues[1] }.toSet()
        assertTrue("app/build.gradle.kts 里没解析出任何 productFlavor", flavors.isNotEmpty())
        assertTrue("构建脚本里应有 coreKotlin flavor（默认发布通道）", "coreKotlin" in flavors)

        val defaultFlavor = Regex("""DEFAULT_FLAVOR\s*=\s*"([^"]+)"""").find(
            gateText
        )?.groupValues?.get(1)
            ?: error("tools/verify_release.py 里找不到 DEFAULT_FLAVOR = \"...\"")
        assertTrue(
            "门禁默认校验的 flavor「$defaultFlavor」不在构建脚本声明的 flavor 集合 $flavors 里 —— " +
                "改名只改一边就会让发布目录永远对不上",
            defaultFlavor in flavors,
        )
        // 文件名形状：app-<flavor>-<abi>-release.apk（与 AGP 的 flavor+ABI 产物命名同构）
        assertTrue(
            "门禁的产物名形状必须是 app-<flavor>-<abi>-release.apk",
            "app-%s-%s-release.apk" in gateText,
        )
    }

    @Test
    fun `第 ⑩ 项依赖的 padding 检查脚本存在且真被门禁调用`() {
        val script = File(repoRoot(), "tools/check_zip_padding.py")
        assertTrue(
            "tools/check_zip_padding.py 不见了，但 verify_release.py 第 ⑩ 项依赖它（缺文件必须红，" +
                "不能让 -113 那道门静默消失）",
            script.isFile,
        )
        assertTrue(
            "verify_release.py 不再调用 check_zip_padding.py —— 第 ⑩ 项已形同虚设",
            "check_zip_padding.py" in gateText,
        )
        // 反向也要成立：脚本自己声明的阈值不能悄悄改动（8192 是 4K/16K padding 的分界）
        val limit = Regex("""PADDING_LIMIT\s*=\s*(\d+)""").find(script.readText())
            ?.groupValues?.get(1)?.toInt()
            ?: error("tools/check_zip_padding.py 里找不到 PADDING_LIMIT = <整数>")
        assertEquals(
            "padding 阈值被改动：4K 对齐 padding ≤4095、官方包实测 1878、16K padding 约 14000，" +
                "8192 这个分界改动前请连同本注释与 STATUS 一起改",
            8192,
            limit,
        )
    }

    @Test
    fun `包形态修复项仍在位且带着它的说明`() {
        val m = Regex("""useLegacyPackaging\s*=\s*true""").find(buildText)
        if (m == null) {
            // 少了修复项本身：给出可执行的结论，而不是让人去猜是哪一轮掉的
            error(
                "app/build.gradle.kts 里没有 useLegacyPackaging = true —— AGP 会把 16K 对齐 padding 写进 " +
                    ".so 的 local header extra，安装器 libziparchive 拒收（res=-113，「解析包出现问题」），" +
                    "而 ①–⑨ 全绿看不出来。移除它必须连着改 tools/verify_release.py 第 ⑩ 项与 STATUS 血泪条目。"
            )
        }
        assertTrue(
            "useLegacyPackaging 旁边那段「删前必读」说明不见了 —— 这行是被删过又加回来时最容易丢的上下文",
            buildText.contains("删前必读"),
        )
    }

    /** 把 `"a" to 1`（Kotlin）与 `"a": 1`（Python 字典）都解析成有序对；形状不符直接失败（不静默跳过）。 */
    private fun pairsOf(text: String): List<Pair<String, Int>> =
        text.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { entry ->
            val m = Regex(""""([^"]+)"\s*(?:to|:)\s*(\d+)""").find(entry)
                ?: error("解析不出 ABI 序号对，条目原文：「$entry」（改了写法就同步改本测试）")
            m.groupValues[1] to m.groupValues[2].toInt()
        }.also { assertTrue("ABI 表解析结果为空（形状不符）", it.isNotEmpty()) }

    /** 工作目录可能是模块目录也可能是仓库根，故向上找 `settings.gradle.kts`。 */
    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("定位不到仓库根（没找到 settings.gradle.kts）")
    }
}
