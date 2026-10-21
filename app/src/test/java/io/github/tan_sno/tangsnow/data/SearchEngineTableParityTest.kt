package io.github.tan_sno.tangsnow.data

import io.github.tan_sno.tangsnow.TestPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **搜索引擎的两份手抄表必须一模一样**（Kotlin `data/SearchEngines.kt` ↔ Rust `search_engines.rs`）。
 *
 * ## 为什么需要它
 * `builtins`（引擎表）与 `HOST_ALIASES`（同一引擎「模板主机之外」的官方主机）在两侧**各写了一份**，
 * 此前没有任何东西比对过：Rust 侧是靠人工从 Kotlin 逐行移植的，而「移植时漏一行」既不会编译失败、
 * 也不会被任何单测发现 —— 本仓已经为这类事记过血泪（`about:` 分支整个漏移植、在变体线存在 11 天）。
 * 2026-10-16 正是本测试先写出、第一次跑就抓到一条真实漂移：Rust 少登记了 `"bing" → bing.com`。
 *
 * 与 [io.github.tan_sno.tangsnow.data.ReleaseGateConsistencyTest] 同一套纪律：
 * 靠人记着的同步就是没同步；解析不出期望形状就**红**，不静默跳过。
 *
 * ## 覆盖与边界（别高估）
 * ✅ 能查：内置引擎的 **id / 模板 / queryParam / 出现顺序**、别名表的 **id → 别名集合**、
 * `DEFAULT_ID` 与 `CUSTOM_ID_PREFIX` 两个常量、默认 id 是否真在表里。
 * ❌ 查不了：`labelRes`（Android 资源，Rust 侧本就不移植，属**有意差异**）、
 * 以及"这张表对不对"（引擎官网换了主机这种事实问题只能人查）。
 */
class SearchEngineTableParityTest {

    private val kotlinText: String by lazy {
        val f = File(TestPaths.repoRoot(), "app/src/main/java/io/github/tan_sno/tangsnow/data/SearchEngines.kt")
        assertTrue("找不到 Kotlin 引擎表：${f.absolutePath}", f.isFile)
        f.readText()
    }

    private val rustText: String by lazy {
        val f = File(TestPaths.repoRoot(), "tangsnow-rs/tangsnow-core/src/search_engines.rs")
        assertTrue("找不到 Rust 引擎表：${f.absolutePath}", f.isFile)
        f.readText()
    }

    @Test
    fun 内置引擎表的_id_模板_参数与顺序两侧一致() {
        val kt = kotlinBuiltins()
        val rs = rustBuiltins()
        assertEquals(
            "内置引擎**条数**就不一致（一侧加了/删了引擎）：\n  Kotlin=${kt.map { it.id }}\n  Rust  =${rs.map { it.id }}",
            kt.map { it.id },
            rs.map { it.id },
        )
        kt.forEachIndexed { i, k ->
            val r = rs[i]
            assertEquals(
                "第 ${i + 1} 个引擎「${k.id}」的**模板**两侧不同（顺序也在校验范围内：它决定 UI 列表次序，" +
                    "也是 find() 退化到 builtins[0] 时的落点）：\n  Kotlin=${k.template}\n  Rust  =${r.template}",
                k.template,
                r.template,
            )
            assertEquals(
                "引擎「${k.id}」的 queryParam（结果页关键词参数名）两侧不同 ⇒ 关键词回显在一侧静默失效：\n" +
                    "  Kotlin=${k.queryParam}   Rust=${r.queryParam}",
                k.queryParam,
                r.queryParam,
            )
        }
    }

    @Test
    fun 主机别名表两侧一致() {
        // 这条在本测试初稿跑通前就该红：Rust 侧漏了 `"bing" -> bing.com`。
        // 后果不是崩溃，而是**静默功能缺失**：国际版模板主机是 global.bing.com，
        // 结果页常在 www.bing.com 上 —— 没有这条别名，`host_matches` 的子域规则也覆盖不到
        // （"bing.com" 比 "global.bing.com" 短，后缀匹配不成立），关键词回显就此失效。
        val kt = kotlinAliases()
        val rs = rustAliases()
        assertEquals(
            "别名表登记的引擎 id 集合不一致（一侧多/少一行）：\n  Kotlin=${kt.keys.sorted()}\n  Rust  =${rs.keys.sorted()}",
            kt.keys.sorted(),
            rs.keys.sorted(),
        )
        kt.keys.sorted().forEach { id ->
            assertEquals(
                "引擎「$id」的别名集合两侧不同：\n  Kotlin=${kt[id]!!.sorted()}\n  Rust  =${rs[id]!!.sorted()}",
                kt[id]!!.sorted(),
                rs[id]!!.sorted(),
            )
        }
        // 别名表的键必须是**内置引擎的 id**：写错 id 不会被编译器发现，只是永不命中
        val builtinIds = kotlinBuiltins().map { it.id }.toSet()
        val orphans = kt.keys.filter { it !in builtinIds }
        assertTrue("别名表里登记了不是内置引擎的 id（永不命中，白占一行）：$orphans", orphans.isEmpty())
    }

    @Test
    fun 两个常量与默认引擎在表内() {
        assertEquals(
            "DEFAULT_ID 两侧不同 ⇒ 一侧的「未知 id 回退」会落到另一个引擎",
            constOf(Regex("""const val DEFAULT_ID = "([^"]+)"""), kotlinText, "Kotlin 侧找不到 DEFAULT_ID"),
            constOf(Regex("""pub const DEFAULT_ID: &str = "([^"]+)"""), rustText, "Rust 侧找不到 DEFAULT_ID"),
        )
        assertEquals(
            "CUSTOM_ID_PREFIX 两侧不同 ⇒ 自定义引擎的 id 回推（removeCustomEngine 那条）会错位",
            constOf(Regex("""const val CUSTOM_ID_PREFIX = "([^"]+)"""), kotlinText, "Kotlin 侧找不到 CUSTOM_ID_PREFIX"),
            constOf(Regex("""pub const CUSTOM_ID_PREFIX: &str = "([^"]+)"""), rustText, "Rust 侧找不到 CUSTOM_ID_PREFIX"),
        )
        val default = constOf(Regex("""const val DEFAULT_ID = "([^"]+)"""), kotlinText, "Kotlin 侧找不到 DEFAULT_ID")
        assertTrue(
            "DEFAULT_ID「$default」不在内置引擎表里 —— find() 的回退分支会再退到 builtins[0]，" +
                "于是「默认引擎」变成一个没人声明过的东西",
            kotlinBuiltins().any { it.id == default },
        )
        assertTrue(
            "DEFAULT_ID「$default」不在 Rust 内置表里（同上，Rust 侧回退到 builtins()[0]）",
            rustBuiltins().any { it.id == default },
        )
    }

    // ------------------------------------------------------------------ 解析

    private data class Engine(val id: String, val template: String, val queryParam: String?)

    /** Kotlin：`SearchEngine("id", "template", labelRes = R.string.x, queryParam = "q",)` */
    private fun kotlinBuiltins(): List<Engine> = Regex(
        """SearchEngine\(\s*"([^"]+)",\s*"([^"]+)",\s*labelRes\s*=\s*R\.string\.\w+,\s*queryParam\s*=\s*(?:"([^"]+)"|null)\s*,?\s*\)""",
    ).findAll(kotlinText).map { Engine(it.groupValues[1], it.groupValues[2], it.groupValues[3].ifEmpty { null }) }.toList()
        .also { assertTrue("Kotlin 侧一个内置引擎都没解析出来（改了写法就同步改本测试）", it.isNotEmpty()) }

    /** Rust：`SearchEngine::new("id", "template", Some("q"))` */
    private fun rustBuiltins(): List<Engine> = Regex(
        """SearchEngine::new\(\s*"([^"]+)",\s*"([^"]+)",\s*(?:Some\("([^"]*)"\)|None)\s*,?\s*\)""",
    ).findAll(rustText).map { Engine(it.groupValues[1], it.groupValues[2], it.groupValues[3].ifEmpty { null }) }.toList()
        // 注意：`builtins()` 之外，JNI 桥也会构造引擎 —— 这里只取表，桥层那处用 template_only/new 显式带 id，
        // 由 jni_boundary_parity 与 BridgeSignatureParityTest 各自负责。
        .also { assertTrue("Rust 侧一个内置引擎都没解析出来（同上）", it.isNotEmpty()) }

    private fun kotlinAliases(): Map<String, List<String>> =
        Regex(""""([^"]+)"\s+to\s+listOf\(([^)]*)\)""").findAll(kotlinText).associate { m ->
            m.groupValues[1] to stringList(m.groupValues[2])
        }.also { assertTrue("Kotlin 侧别名表解析为空", it.isNotEmpty()) }

    private fun rustAliases(): Map<String, List<String>> =
        Regex("""\("([^"]+)",\s*&\[([^\]]*)\]\)""").findAll(rustText).associate { m ->
            m.groupValues[1] to stringList(m.groupValues[2])
        }.also { assertTrue("Rust 侧别名表解析为空", it.isNotEmpty()) }

    private fun stringList(text: String): List<String> =
        Regex(""""([^"]+)"""").findAll(text).map { it.groupValues[1] }.toList()

    private fun constOf(regex: Regex, text: String, miss: String): String =
        (regex.find(text) ?: throw AssertionError(miss)).groupValues[1]

}
