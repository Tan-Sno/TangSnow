package io.github.tan_sno.tangsnow.util

import io.github.tan_sno.tangsnow.data.SharedFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `UrlUtils.toAsciiUrl` 的**主机标签口径**回归钉（Kotlin 侧）。
 *
 * ## 为什么要有这个文件
 *
 * 这条口径此前**只被 Rust 侧钉着**：`toAsciiUrl` 是 `private`，JVM 侧**一个针对它的测试都没有**
 * ⇒ 改 Kotlin 实现不会红，改 Rust 实现也不会红，两边的口径可以无声地漂。
 * 现在两侧读**同一份** JSON 夹具（`tangsnow-rs/tangsnow-core/tests/fixtures/url_idna_cases.json`），
 * 改夹具必然两侧一起红。
 *
 * ## 期望值分两摊
 *
 * 1. **两侧同口径** —— 在共享夹具里。期望值由两侧**实测**得出（2026-10-21，JBR 25.0.3）：
 *    Kotlin 侧走 `java.net.IDN.toASCII(host, ALLOW_UNASSIGNED)`（IDNA2003），
 *    Rust 侧走 `idna` 1.x / UTS46（IDNA2008）。已分配码位的常用域名两者一致。
 * 2. **有意分歧** —— Java 的 IDNA2003 与 Rust 的 UTS46 本来就不同（映射表、大小写折叠、
 *    ACE 回环校验、码位是否已分配、长度校验）。这些**不进夹具**：进了就等于逼两侧互相迁就。
 *    两侧各留一条**单侧**用例把当前值钉死 —— 下半部分的期望值是**本机实测**的，
 *    不是从 Rust 侧抄的（两侧在这几组上本来就不同，抄过来必然错）。
 *
 * ⚠️ 差异的完整口径见 `tangsnow-rs/README.md` 的 IDN 差异表。
 *
 * ⚠️ 本文件里的非 ASCII 码位一律写成 `\uXXXX`：零宽字符、软连字符、全角形字面量写出来
 * **看不出来**（review 时等于没写），而它们恰恰是本文件要钉的那几组。
 */
class UrlIdnaParityTest {

    /** 两侧共享的夹具（Rust 侧读同一个文件） */
    private fun case(id: String) = SharedFixtures.pick(FIXTURE, id)

    private companion object {
        const val FIXTURE = "url_idna_cases.json"
    }

    /**
     * 夹具里的每一条都要过 —— 否则「加了新语料却没人读」会静默发生，
     * 而这正是本文件要根治的那类漏检（镜像的另一种形态）。
     */
    @Test
    fun `夹具全覆盖_每条用例都与共享夹具一致`() {
        val cases = SharedFixtures.cases(FIXTURE)
        assertTrue("夹具里一条用例都没有 —— 门禁会空转", cases.length() > 0)
        for (i in 0 until cases.length()) {
            val id = cases.getJSONObject(i).getString("id")
            val c = case(id)
            assertEquals(
                "用例「$id」与共享夹具不一致",
                c.expected,
                UrlUtils.toAsciiUrl(c.input),
            )
        }
    }

    // ------------------------------------------------------------------
    // 第一摊：两侧同口径（期望值全部来自共享夹具）
    // ------------------------------------------------------------------

    @Test
    fun `空白标签一律拒而尾点保留`() {
        for (id in listOf(
            "空标签在标签中间一律拒",
            "空标签在开头一律拒",
            "连续空标签一律拒",
            "退化输入两个点一律拒",
            "多段域名里的空标签同样拒",
            "尾点被保留",
            "尾点加端口与路径原样保留",
            "ACE 标签上的尾点同样保留",
        )) {
            val c = case(id)
            assertEquals(
                "用例「$id」与共享夹具不一致",
                c.expected,
                UrlUtils.toAsciiUrl(c.input),
            )
        }
    }

    @Test
    fun `合法与非法输入的分类一致`() {
        for (id in listOf(
            "普通域名原样保留",
            "子域原样保留",
            "域名带端口原样保留",
            "域名带路径原样保留",
            "查询串按原样拼回",
            "标签首的连字符不得收紧",
            "标签尾的连字符不得收紧",
            "含下划线的标签原样保留",
            "IPv4 字面量原样保留",
            "IPv4 加端口与路径原样保留",
            "含空格一律拒",
            "含制表符一律拒",
            "authority 段含 userinfo 一律拒",
            "路径里的 at 不得误伤",
            "子域路径里的 at 不得误伤",
            "不含点就不是域名",
            "全角句号不算域名分隔符",
            "全角句号在标签中间按分隔符处理",
            "全角句号（FF0E）在标签中间按分隔符处理",
            "IPv6 字面量不是域名",
            "非数字端口同拒（2026-10-24 收紧：端口段必须 1..65535，与三个 host 正则一致）",
            "多个冒号同拒（非合法端口段，2026-10-24 收紧）",
        )) {
            val c = case(id)
            assertEquals(
                "用例「$id」与共享夹具不一致",
                c.expected,
                UrlUtils.toAsciiUrl(c.input),
            )
        }
    }

    @Test
    fun `已分配码位的各文种域名同口径`() {
        for (id in listOf(
            "中文域名转 punycode",
            "中文域名带尾点",
            "德文变音域名转 punycode",
            "已是 ACE 的域名原样保留",
            "德文 \u00DF 的 ACE 形式原样保留",
            "日文域名转 punycode",
            "韩文域名转 punycode",
            "西里尔域名转 punycode",
            "阿拉伯域名转 punycode",
            "希腊域名转 punycode",
            "emoji 域名转 punycode",
        )) {
            val c = case(id)
            assertEquals(
                "用例「$id」与共享夹具不一致",
                c.expected,
                UrlUtils.toAsciiUrl(c.input),
            )
        }
    }

    @Test
    fun `大小写折叠与映射规则同口径`() {
        for (id in listOf(
            "带点的土耳其 I 转写一致",
            "无点小写 i 转写一致",
            "带点 o 转写一致",
            "带斜杠 o 转写一致",
            "连字 oe 转写一致",
            "微符号与希腊 Mu 同形",
            "希腊 Mu 与微符号同形",
            "预组合重音转写一致",
            "分解重音转写一致",
            "天城文短元音转写一致",
            "中点转写一致",
            "全角拉丁字母转半角",
            "零宽空格被抹掉后继续转",
            "软连字符被抹掉后继续转",
            "不换行空格按非法码位拒",
            "标签恰好 63 字符（上限内）原样保留",
        )) {
            val c = case(id)
            assertEquals(
                "用例「$id」与共享夹具不一致",
                c.expected,
                UrlUtils.toAsciiUrl(c.input),
            )
        }
    }

    // ------------------------------------------------------------------
    // 第二摊：有意分歧（本侧实测值，两侧本就不同，故不进共享夹具）
    // ------------------------------------------------------------------

    @Test
    fun `有意分歧_ace回环校验_java不验punycode`() {
        // Java 的 `IDN.toASCII` 完全不验 punycode，原样放行；Rust 侧 idna 要解码校验 ⇒ 拒。
        assertEquals("xn--.com", UrlUtils.toAsciiUrl("xn--.com"))
        assertEquals("xn--xn--a.com", UrlUtils.toAsciiUrl("xn--xn--a.com"))
        assertEquals("xn--a.com", UrlUtils.toAsciiUrl("xn--a.com"))
        assertEquals("xn--0.com", UrlUtils.toAsciiUrl("xn--0.com"))
    }

    @Test
    fun `有意分歧_未分配码位_java因allow_unassigned放行`() {
        // U+0378 / U+05FF 在 IDNA2003 里未分配 ⇒ Java 因 ALLOW_UNASSIGNED 放行并 punycode 出去；
        // UTS46 直接拒 ⇒ 两侧不同。
        assertEquals("xn--zva.com", UrlUtils.toAsciiUrl("\u0378.com"))
        assertEquals("xn--hfb.com", UrlUtils.toAsciiUrl("\u05FF.com"))
    }

    @Test
    fun `有意分歧_sharp_s过渡映射_java走过渡映射`() {
        // Java 走 IDNA2003 的过渡映射（\u00DF→ss），Rust 保留原字符并 punycode 出去。
        assertEquals("ss.de", UrlUtils.toAsciiUrl("\u00DF.de"))
        assertEquals("fass.de", UrlUtils.toAsciiUrl("FA\u00DF.de"))
        assertEquals("ass.com", UrlUtils.toAsciiUrl("a\u00DF.com"))
        assertEquals("xss.com", UrlUtils.toAsciiUrl("x\u00DF.com"))
        // 大写 \u1E9E（U+1E9E）：IDNA2003 不认这个码位（Unicode 3.2 里还没有）⇒ 原样 punycode；
        // UTS46 把它折叠成 \u00DF ⇒ 两侧转出的 ASCII 不同。
        assertEquals("xn--kkg.de", UrlUtils.toAsciiUrl("\u1E9E.de"))
    }

    @Test
    fun `有意分歧_行尾sigma_java不折叠`() {
        assertEquals("xn--4xa.com", UrlUtils.toAsciiUrl("\u03C2.com"))
    }

    @Test
    fun `有意分歧_全角拉丁_java只折叠前半段`() {
        // Java 把 \uFF21\uFF22 折成 ab，却保留 `CD` 原大小写；Rust 统一小写。
        assertEquals("ab.CD", UrlUtils.toAsciiUrl("\uFF21\uFF22.CD"))
    }

    @Test
    fun `退化输入单点_两侧同拒`() {
        // 2026-10-24 起 Kotlin 侧补上「剥尾点后空标签复检」：Java 的 IDN.toASCII 原样返回
        // "."，但没有复检就会导航到 https://.（DNS 错误页）；Rust 侧一直拒。
        // 原「已知口径差异」表 ⑦ 就此消除，两侧一致按搜索词处理（共享夹具已钉）。
        assertNull(UrlUtils.toAsciiUrl("."))
    }

    @Test
    fun `有意分歧_零宽连接符与不连接符_java抹掉继续转`() {
        assertEquals("ab.com", UrlUtils.toAsciiUrl("a\u200Db.com"))
        assertEquals("ab.com", UrlUtils.toAsciiUrl("a\u200Cb.com"))
    }

    @Test
    fun `有意分歧_ascii大小写_java不折叠`() {
        // Java 的 `IDN.toASCII` 对**纯 ASCII 标签**走快路径、不做大小写折叠 ⇒ 原样保留；
        // UTS46 折叠 ⇒ 两侧转出的主机大小写不同（键相等性受影响）。
        assertEquals("EXAMPLE.COM", UrlUtils.toAsciiUrl("EXAMPLE.COM"))
    }

    @Test
    fun `有意分歧_全角句点造成空标签_java拒`() {
        // 全角/半角句点映射后会让首标签为空 ⇒ Java 侧抛异常 → null；
        // Rust 侧的空标签预检跑在映射之前 ⇒ 漏检，转出 "..com" 这种非法主机。
        assertNull(UrlUtils.toAsciiUrl("\uFF0E.com"))
        assertNull(UrlUtils.toAsciiUrl("\uFF61.com"))
    }

    @Test
    fun `有意分歧_标签超长_java按63字符上限拒`() {
        assertNull(UrlUtils.toAsciiUrl("a".repeat(64) + ".com"))
        assertNull(UrlUtils.toAsciiUrl("a".repeat(128) + ".com"))
    }
}
