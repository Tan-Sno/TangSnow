package io.github.tan_sno.tangsnow.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **Kotlin 的 `external fun` 声明与 Rust 的 JNI 导出必须是同一张表**（符号名 + 参数个数）。
 *
 * ## 为什么需要它
 * JNI 的绑定按**名字与描述符**在运行时匹配，编译期一声不响：Kotlin 多一个形参、Rust 少一个、
 * 或者包名里的下划线忘了转义成 `tan_1sno`，都是装包成功、一调用才 `UnsatisfiedLinkError`。
 * 本仓这类口径一直是**人肉数**出来的 —— `STATUS.md` 记着「`.so` 34 个导出 vs Kotlin 31 个
 * `external`（3 个孤儿）」这条就是 2026-10-15 靠人手数的；同一天 M5 修 `displayForUrlImpl`
 * 时又给签名加了一个形参，正是最容易只改一侧的那种改动。于是把它变成红灯：
 * 与 `TestCountCommentTest` / `ProguardLogRuleConsistencyTest` / `ReleaseGateConsistencyTest`
 * 同一套纪律 —— 靠人记着的同步就是没同步。
 *
 * ## 覆盖与边界（别高估）
 * ✅ 能查：符号名对不对得上、**形参个数**是否一致、有没有重复符号、`#[no_mangle]` 是否漏挂、
 * 有没有超出白名单的新孤儿。
 * ❌ 查不了：形参**类型**（`jint` 与 `jlong` 互换、`IntArray` 与 `IntArray` 写反）—— 那是方法
 * 描述符层面的事，要产物级校验（`javap` / `nm`）才能看见，本机做不到；类型对齐仍靠人审 + 真机冒烟。
 *
 * 一律 fail-closed：解析不出期望形状就**红**，不允许「看不懂就跳过」演变成漏检。
 */
class BridgeSignatureParityTest {

    private val kotlinBridgeText: String by lazy {
        val f = File(repoRoot(), "app/src/coreRust/java/io/github/tan_sno/tangsnow/core/CoreNative.kt")
        assertTrue("找不到 coreRust flavor 的桥：${f.absolutePath}", f.isFile)
        readText(f)
    }

    private val rustBridgeText: String by lazy {
        val f = File(repoRoot(), "tangsnow-rs/tangsnow-core/src/jni.rs")
        assertTrue("找不到 Rust 的 JNI 层：${f.absolutePath}", f.isFile)
        readText(f)
    }

    /**
     * Rust 侧有导出、Kotlin 侧还没有 `external fun` 的三个符号（2026-10-15 实测）。
     * 它们不是缺陷，是 W1/W3 接线时**预备**的导出：`hostMatches` 与 `templateHostOf` 是
     * `SearchEngines` 那两个内部纯函数、`toAsciiUrl` 是 IDNA 那条。
     * 名单之外的新增孤儿会红 —— 那时要么把 Kotlin 声明补上，要么把导出删掉，
     * 不能像此前那样靠人重数一遍。
     */
    private val knownRustOnly = setOf("hostMatchesImpl", "templateHostOfImpl", "toAsciiUrlImpl")

    @Test
    fun 每个_kotlin_external_fun_都有同名同参数个数的_rust_导出() {
        val kotlin = parseKotlin()
        val rust = parseRust()
        assertTrue("Kotlin 桥里一个 external fun 也没解析出来（写法变了就同步改本测试）", kotlin.isNotEmpty())
        assertTrue("Rust 层一个导出也没解析出来（同上）", rust.isNotEmpty())

        val missing = kotlin.keys.filter { it !in rust }
        assertTrue(
            "Kotlin 声明了、Rust 没导出 —— 真机上第一次调用就 UnsatisfiedLinkError（guard 会兜成降级，" +
                "但那就是一条静默的功能缺失）：\n" + missing.joinToString("\n") { "  $it" },
            missing.isEmpty(),
        )
        val wrongArity = kotlin
            .filter { (name, args) -> rust[name] != null && rust[name] != args }
            .map { (name, args) -> "  $name: Kotlin $args 个形参，Rust ${rust[name]} 个" }
        assertTrue(
            "形参个数两侧不一致（JNI 按名字+描述符匹配，编译期不会报）：\n${wrongArity.joinToString("\n")}",
            wrongArity.isEmpty(),
        )
    }

    @Test
    fun rust_侧多出来的导出只能是白名单里那三个() {
        val kotlin = parseKotlin()
        val rust = parseRust()
        val extras = rust.keys - kotlin.keys
        assertTrue(
            "Rust 新增了 Kotlin 没声明的导出 $extras。\n已知备用导出只有 ${knownRustOnly.sorted()}；" +
                "要么把 Kotlin 侧的 external fun 一起补上，要么删掉导出，别让它变成第三个「人手数出来的孤儿」",
            extras.all { it in knownRustOnly },
        )
    }

    @Test
    fun 两侧符号名各自唯一且转义口径一致() {
        // Kotlin 侧重名 = 一个签名都没法声明；Rust 侧重名 = 链接期重复符号。
        assertEquals("Kotlin 桥里有重名 external fun", parseKotlin().size, kotlinRegex().findAll(kotlinBridgeText).count())
        assertEquals("Rust 层里有重名导出", parseRust().size, rustRegex().findAll(rustBridgeText).count())

        // 包名 `io.github.tan_sno...` 的下划线在 JNI 里必须转义成 `tan_1sno`：
        // 写错一个字符，整张表就全部匹配不上（而报错要到运行时才看得见）。
        val prefix = "Java_io_github_tan_1sno_tangsnow_core_CoreNative_"
        val escapedWrong = Regex("""Java_io_github_tan_sno_\w+""").findAll(rustBridgeText).toList()
        assertTrue(
            "Rust 里出现了没转义的符号名 $escapedWrong（下划线在 JNI 名称里写作 `_1`）",
            escapedWrong.isEmpty(),
        )
        // `#[no_mangle]` 的个数必须与带正确前缀的导出数相等：少一个就是漏挂属性（会被 mangle 掉），
        // 多一个就是有别的 no_mangle 混进这个文件。
        val attributed = Regex("""#\[no_mangle\]""").findAll(rustBridgeText).count()
        assertEquals(
            "#[no_mangle] 的个数 ≠ 带 $prefix 前缀的导出个数：要么漏挂属性，要么混进了别的 no_mangle",
            attributed,
            rustRegex().findAll(rustBridgeText).count(),
        )
    }

    // ------------------------------------------------------------------ 解析

    /** Kotlin：`private external fun resolveInfoImpl(input: String, template: String): Array<String>?` */
    private fun kotlinRegex() =
        Regex("""private external fun\s+(\w+)\s*\(([^)]*)\)""")

    /** Rust：`pub extern "system" fn Java_..._CoreNative_resolveInfoImpl(mut env: JNIEnv, _class: &JClass, …)` */
    private fun rustRegex() = Regex(
        """fn Java_io_github_tan_1sno_tangsnow_core_CoreNative_(\w+)\s*\(([^)]*)\)""",
    )

    private fun parseKotlin(): Map<String, Int> = kotlinRegex().findAll(kotlinBridgeText)
        .associate { it.groupValues[1] to argsOf(it.groupValues[2], 0) }

    private fun parseRust(): Map<String, Int> = rustRegex().findAll(rustBridgeText)
        // 头两个形参固定是 JNIEnv 与 jclass（Kotlin 侧是 object 的静态方法），不计入
        .associate { it.groupValues[1] to argsOf(it.groupValues[2], 2) }

    /** 逗号计数 = 形参个数；`skip` 个是两侧结构性差异，泛型里带逗号则直接判红。 */
    private fun argsOf(params: String, skip: Int): Int {
        val text = params.trim()
        if (text.isEmpty()) return 0
        val parts = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        parts.forEach {
            val opens = it.count { c -> c == '<' }
            val closes = it.count { c -> c == '>' }
            assertTrue(
                "解析器不认识带泛型实参的形参「$it」（泛型里再含逗号会被拆错）。" +
                    "出现这种写法就同步改本测试的切分方式，别让它默默数错参数。",
                opens == closes,
            )
        }
        return (parts.size - skip).also { assertTrue("形参个数算出负数（$text）", it >= 0) }
    }

    private fun readText(f: File): String = f.readText()

    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("定位不到仓库根（没找到 settings.gradle.kts）")
    }
}
