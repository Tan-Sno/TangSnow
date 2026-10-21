package io.github.tan_sno.tangsnow.util

import io.github.tan_sno.tangsnow.TestPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **百分号编码的「未保留字符集」在本仓有三份拷贝，必须一致**（2026-10-16，同类扩散扫描轮）。
 *
 * ## 三处分别在哪
 * 1. `app/src/main/java/.../util/UrlUtils.kt` 的 `kotlinPercentEncode` —— 当前**发布通道**（coreKotlin）
 *    真正在用的实现（顶层 `percentEncode` 自 2026-10-19 W1 接线起只做「外部内核优先」的分派：
 *    `CoreImpl.percentEncode` 接管则用它的结果，否则回落本实现），语义对齐 `android.net.Uri.encode`；
 * 2. `tangsnow-rs/tangsnow-core/src/percent_encode.rs` —— Rust 侧移植；
 * 3. `app/src/coreRust/java/.../core/CoreNative.kt` 里的 `kotlinPercentEncode` —— **native 不可用时的
 *    功能级兜底**（它必须与 (1) 同口径，否则"降级"本身就是行为改变）。
 *
 * ## 为什么要钉
 * 三处各自都有单测，但那是"每侧自证"：有人改其中一份，**它自己的测试照样绿**，
 * 漂移只会在"同一个查询词在两条通道上编出不同 URL"时暴露 —— 而那是 parity 的地基，
 * `percentEncode` 更是 PLAN-2026-10-15 里 W1 接线的第一批函数。与 10-16 抓到
 * `HOST_ALIASES` 少一行（见 `SearchEngineTableParityTest`）是同一格，所以这轮把它并排钉住。
 *
 * ## 判据与边界
 * ✅ 比的是**集合**（不看书写顺序）：单引号字符字面量全收，先剔掉 `'A'..'Z'` 这类区间端点；
 * 顺带比两侧的十六进制表 `0123456789ABCDEF`（大写 —— 小写会改变产物字节）。
 * ❌ 不判字母/数字区间本身（三处都写成 `A-Z`/`a-z`/`0-9` 或 `is_ascii_uppercase` 等等价形式，
 * 形状差太多，硬解析只会得到一条脆测试）；字母数字的一致性靠各自的编码用例保证。
 * 一律 fail-closed：任一处解析不出字符集就**红**，不静默跳过。
 */
class PercentEncodeSetParityTest {

    private val urlUtils: String by lazy { TestPaths.read("app/src/main/java/io/github/tan_sno/tangsnow/util/UrlUtils.kt") }

    private val coreNative: String by lazy {
        TestPaths.read("app/src/coreRust/java/io/github/tan_sno/tangsnow/core/CoreNative.kt")
    }

    private val rust: String by lazy { TestPaths.read("tangsnow-rs/tangsnow-core/src/percent_encode.rs") }

    @Test
    fun 三处未保留字符集完全相同() {
        val kt = specials(
            region(urlUtils, "private fun kotlinPercentEncode", "sb.append('%')"),
            "UrlUtils.kotlinPercentEncode",
        )
        val bridge = specials(
            region(coreNative, "private fun kotlinPercentEncode", "sb.append('%')"),
            "CoreNative.kotlinPercentEncode（native 不可用时的兜底）",
        )
        val rs = specials(region(rust, "pub fn percent_encode", "if keep"), "percent_encode.rs")

        assertEquals(
            "UrlUtils 与 CoreNative 的兜底实现字符集不同 ⇒ 「native 不可用」本身就成了一次行为改变" +
                "（同一查询词在降级路径上编出不同 URL）：\n  主实现=$kt\n  兜底  =$bridge",
            kt,
            bridge,
        )
        assertEquals(
            "Kotlin 主实现与 Rust 的字符集不同 ⇒ parity 的地基裂了（W1 第一个函数就是它）：\n" +
                "  Kotlin=$kt\n  Rust  =$rs",
            kt,
            rs,
        )
        // 常识自检：这个集合就是 RFC 3986 的 unreserved（去掉字母数字）+ `*`，
        // 少一个字符会改变产物字节，多一个（比如把 `&`/`=` 放进来）就是查询参数注入面。
        val expected = setOf("_", "-", "!", ".", "~", "'", "(", ")", "*")
        assertEquals(
            "未保留标点集不再是 `_-!.~'()*`（RFC 3986 unreserved + `*`，与 android.net.Uri.encode 一致）。" +
                "真的换集合是大事：它会改变每个含这些字符的搜索 URL 的字节 ⇒ 必须连同三处实现、" +
                "两侧单测与本断言一起改。实测：UrlUtils=$kt",
            expected,
            kt,
        )
    }

    @Test
    fun 两侧的十六进制表都是大写且一致() {
        val kt = hex(urlUtils, "UrlUtils 的 HEX")
        val rs = hex(rust, "Rust 的 HEX")
        assertEquals("两侧的十六进制表不一致（编码结果会逐字节不同）", kt, rs)
        assertEquals(
            "十六进制表必须是大写 —— `Uri.encode` 产出 `%2F` 而不是 `%2f`，改成小写就是产物字节漂移",
            "0123456789ABCDEF",
            kt,
        )
    }

    // ------------------------------------------------------------------ 解析

    /** 取 [from] 到 [until] 之间的源码片段；任一端找不到就直接红。 */
    private fun region(text: String, from: String, until: String): String {
        val a = text.indexOf(from)
        assertTrue("找不到「$from」—— 实现改名/改写法就同步改本测试", a >= 0)
        val b = text.indexOf(until, a)
        assertTrue("在「$from」之后找不到「$until」—— 实现形状变了，本测试的切分需要同步", b >= 0)
        return text.substring(a, b)
    }

    /** 先剔掉 `'A'..'Z'` 这类区间端点，再收单引号字符字面量（含 `'\''` 这种转义）。 */
    private fun specials(snippet: String, label: String): Set<String> {
        val noRanges = snippet.replace(Regex("""'[^']+'\s*\.\.\s*'[^']+'"""), " ")
        val found = Regex("""'(\\.|[^'\\\n])'""").findAll(noRanges)
            .map { m ->
                val raw = m.groupValues[1]
                if (raw.startsWith("\\")) raw.substring(1) else raw
            }.toSet()
        assertTrue(
            "$label 里一个字符字面量都没解析出来 —— 判据大概被改写成了查表/when，" +
                "本测试需要同步（不许静默跳过：那等于这道门消失）",
            found.isNotEmpty(),
        )
        return found
    }

    private fun hex(text: String, label: String): String {
        val m = Regex(""""([0-9A-Fa-f]{16})"""").find(text)
            ?: throw AssertionError("$label 里找不到 16 位的十六进制表字面量（改写法就同步改本测试）")
        return m.groupValues[1]
    }


}
