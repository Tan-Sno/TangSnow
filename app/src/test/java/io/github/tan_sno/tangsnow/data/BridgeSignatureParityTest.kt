package io.github.tan_sno.tangsnow.data

import io.github.tan_sno.tangsnow.TestPaths
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
 * （符号层的产物校验已由下方 `随包 so 的导出符号与 kotlin 声明一一对应` 补上 —— 它读的是三颗
 * 已提交 `.so` 的动态符号表，不是源码。）
 *
 * 一律 fail-closed：解析不出期望形状就**红**，不允许「看不懂就跳过」演变成漏检。
 */
class BridgeSignatureParityTest {

    private val kotlinBridgeText: String by lazy {
        val f = File(TestPaths.repoRoot(), "app/src/coreRust/java/io/github/tan_sno/tangsnow/core/CoreNative.kt")
        assertTrue("找不到 coreRust flavor 的桥：${f.absolutePath}", f.isFile)
        readText(f)
    }

    private val rustBridgeText: String by lazy {
        val f = File(TestPaths.repoRoot(), "tangsnow-rs/tangsnow-core/src/jni.rs")
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

    /**
     * **随包 `.so` 的导出符号必须与 Kotlin 声明一一对应**（产物层，不是源码层）。
     *
     * ## 为什么还需要这一条
     * 上面几条比的是**两份源码**（`CoreNative.kt` ↔ `jni.rs`）：两侧都改对了，源码层就是绿的。
     * 但 `jniLibs/` 里那三颗 `.so` 是**预先编译、随仓库提交**的二进制 —— 改了 `external fun`
     * 却忘了重编（它们是三 ABI 唯一的产出入口，而且跑在另一台机器上）时，源码层全绿、真机首次
     * 调用却是 `UnsatisfiedLinkError`，而 `CoreNative.guard` 会把它兜成**静默功能缺失**：
     * 不崩溃、无异常、日志里也没有线索。`CoreNative.kt` 末段自己写着这类漂移「不能指望真机给
     * 信号」⇒ 那就得有一条产物层的红灯。
     *
     * ## 覆盖与边界（别高估）
     * ✅ 能查：三颗 `.so` 各自导出的 `…_CoreNative_*` 集合 == Kotlin 声明 ∪ 已知孤儿；
     *    混进别的类（前缀不符）的 `Java_*` 导出同样会被查出。
     * ❌ 查不了：形参**个数与类型** —— JNI 对非重载方法按**短名**查找（符号里不含描述符），
     *    `displayForUrlImpl` 由 3 参变 4 参时符号名不变、仍能绑定成功（多余实参由各 ABI 的调用方
     *    清理）⇒ 这类漂移只能靠上面源码层的形参比对 + 重编替换，产物层看不见。
     * 一律 fail-closed：定位不到 `.so`、或解析不出任何符号，直接判红，不允许「读不懂就跳过」。
     */
    @Test
    fun `随包 so 的导出符号与 kotlin 声明一一对应`() {
        val kotlin = parseKotlin().keys
        assertTrue("Kotlin 桥里一个 external fun 也没解析出来", kotlin.isNotEmpty())
        val expected = kotlin + knownRustOnly
        for (abi in listOf("arm64-v8a", "armeabi-v7a", "x86_64")) {
            val so = File(TestPaths.repoRoot(), "app/src/coreRust/jniLibs/$abi/libtangsnow_core.so")
            assertTrue("找不到随包产物：${so.absolutePath}", so.isFile)
            val java = javaSymbols(so)
            assertTrue("$abi：一个 Java_* 符号都没解析出来（ELF 形态变了就同步改本测试）", java.isNotEmpty())
            val foreign = java.filter { !it.contains("_CoreNative_") }
            assertTrue("$abi：`.so` 里混进了别的类的 JNI 导出 $foreign", foreign.isEmpty())
            val exported = java.mapNotNull { it.substringAfter("_CoreNative_", "").ifEmpty { null } }.toSet()
            val missing = (expected - exported).sorted()
            assertTrue(
                "$abi：声明了、随包 .so 却没导出 $missing —— 忘了重编 .so。\n" +
                    "真机表现是静默降级（guard 兜成「功能缺失」）而不是报错，所以必须在这里红",
                missing.isEmpty(),
            )
            val extra = (exported - expected).sorted()
            assertTrue("$abi：随包 .so 导出了声明之外的符号 $extra（要么补 Kotlin 声明，要么重编产物）", extra.isEmpty())
        }
    }

    // ------------------------------------------------------------------ 产物解析（ELF 动态符号表）

    /** 读出 `.so` 动态符号表里全部 `Java_*` 名字；只认本仓三种 ABI 的形态（小端 ELF32/64）。 */
    private fun javaSymbols(so: File): Set<String> {
        val b = so.readBytes()
        assertTrue(
            "不是 ELF 文件：${so.name}",
            b.size > 64 && b[0].toInt() == 0x7F && b[1].toInt() == 'E'.code &&
                b[2].toInt() == 'L'.code && b[3].toInt() == 'F'.code,
        )
        assertTrue("${so.name} 不是小端 ELF（三种 ABI 均应为 LE）", b[5].toInt() == 1)
        val is64 = b[4].toInt() == 2
        val shoff = if (is64) u64le(b, 0x28) else u32le(b, 0x20)
        val shentsize = u16le(b, if (is64) 0x3A else 0x2E)
        val shnum = u16le(b, if (is64) 0x3C else 0x30)
        assertTrue("${so.name} 节表头异常（shentsize=$shentsize shnum=$shnum）", shentsize > 0 && shnum > 0)

        // [偏移, 大小, 表项长度]：SHT_DYNSYM(=11) 与它 sh_link 指向的字符串表
        val dynsym = ArrayList<LongArray>()
        var strOffset = 0L
        var strSize = 0L
        for (i in 0 until shnum) {
            val o = (shoff + i * shentsize).toInt()
            if (u32le(b, o + 4) != 11L) continue
            dynsym += longArrayOf(
                if (is64) u64le(b, o + 0x18) else u32le(b, o + 0x10),
                if (is64) u64le(b, o + 0x20) else u32le(b, o + 0x14),
                if (is64) 24L else 16L,
            )
            val link = u32le(b, o + if (is64) 0x28 else 0x18).toInt()
            val s = (shoff + link * shentsize).toInt()
            strOffset = if (is64) u64le(b, s + 0x18) else u32le(b, s + 0x10)
            strSize = if (is64) u64le(b, s + 0x20) else u32le(b, s + 0x14)
        }
        assertTrue("${so.name} 没有 .dynsym（SHT_DYNSYM）—— 形态变了就同步改本测试", dynsym.isNotEmpty())
        val strEnd = (strOffset + strSize).toInt()

        val out = LinkedHashSet<String>()
        for (d in dynsym) {
            var j = 0L
            while (j < d[1] / d[2]) {
                val stName = u32le(b, (d[0] + j * d[2]).toInt()).toInt()
                val abs = (strOffset + stName).toInt()
                if (stName > 0 && abs in 0 until strEnd) {
                    var end = abs
                    while (end < strEnd && b[end].toInt() != 0) end++
                    val name = String(b, abs, end - abs, Charsets.UTF_8)
                    if (name.startsWith("Java_")) out += name
                }
                j++
            }
        }
        return out
    }

    private fun u16le(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun u32le(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    private fun u64le(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
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

}
