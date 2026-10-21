package io.github.tan_sno.tangsnow.util

import io.github.tan_sno.tangsnow.data.SharedFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网页导航 scheme 白名单（C 批 / P2-3）与「可携带 URL」的判定。
 *
 * 这**一份**判定同时被两处引用：内核侧的 `onLoadRequest`（拦网页内容发起的外链）与
 * 应用侧的 `onOpenInCurrentTab`（拦 window.open 经 loadUri 绕过内核闸门）。因此本文件里的
 * 每一条断言都同时钉住了「不留缺口」与「不误挡」两侧。
 *
 * ## 期望值来自**两侧共享的 JSON 夹具**（2026-10-20 审查报告 C-14）
 *
 * 此前这里与 Rust 侧 `tests/url_utils_parity.rs` **各写一份期望值** —— 那是**镜像**：
 * 改实现 + 同步改本侧的镜像期望值 ⇒ 两侧照样全绿，而两边的口径已经漂了。
 * 现在两边读**同一份**夹具（`tangsnow-rs/tangsnow-core/tests/fixtures/url_utils_cases.json`）
 * ⇒ 改夹具必然两侧一起红。
 *
 * ## 本文件负责哪几个 kind
 * `schemeOf` / `schemeClass` / `isPortableUrl`；另几个 kind 归 [UrlUtilsTest] 与 [PercentEncodeTest]。
 *
 * 其中 `schemeClass` 是**性质**而非逐点期望：它断言的是「网页档 / 绝不放行档 / 交系统档」
 * 这**三分法互不重叠且覆盖全部输入**（`none` = 压根没有 scheme）。
 */
class SchemeGateTest {

    @Test
    fun `夹具里的每条用例与Rust侧同口径`() {
        val cases = SharedFixtures.cases(FIXTURE)
        val seen = mutableMapOf<String, Int>()
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val kind = c.getString("kind")
            if (kind !in OWNED_KINDS) continue
            seen[kind] = (seen[kind] ?: 0) + 1
            val id = c.getString("id")
            val a = c.getJSONArray("args")
            when (kind) {
                "schemeOf" -> assertEquals(
                    "夹具用例「$id」",
                    c.getString("expected"),
                    UrlUtils.schemeOf(a.getString(0)),
                )
                "schemeClass" -> {
                    // 三分法：网页档照常加载、绝不放行档静默丢弃、其余交回「外部打开」。
                    // 判据一乱，两侧同时出问题：把 file 划进"其余"= 安全缺口；
                    // 把 mailto 划进"绝不放行档"= 功能回归（target=_blank 的邮件按钮点了没反应）。
                    val scheme: String? = if (a.isNull(0)) null else a.getString(0)
                    val got = when {
                        scheme == null || scheme.isEmpty() -> "none"
                        UrlUtils.isWebNavigationScheme(scheme) -> "web"
                        UrlUtils.isNeverWebContentScheme(scheme) -> "never"
                        else -> "external"
                    }
                    assertEquals("夹具用例「$id」", c.getString("expected"), got)
                }
                "isPortableUrl" -> {
                    val url: String? = if (a.isNull(0)) null else a.getString(0)
                    assertEquals(
                        "夹具用例「$id」",
                        c.getBoolean("expected"),
                        UrlUtils.isPortableUrl(url),
                    )
                }
                else -> throw AssertionError("夹具里的 kind「$kind」本文件不认（改夹具要同步两侧的派发）")
            }
        }
        for (k in OWNED_KINDS) {
            assertTrue("kind「$k」在夹具里一条用例都没有 ⇒ 门禁空转", (seen[k] ?: 0) > 0)
        }
    }

    @Test
    fun `有意分歧：罕见 Unicode 数字的 scheme 在本侧判为没有 scheme`() {
        // Kotlin 的 isLetterOrDigit 只认 L*+Nl+Nd（**不含** No），Rust 的 is_numeric 含 No
        // ⇒ 「a²://x」在 Kotlin 侧判「没有 scheme」、在 Rust 侧判「有 scheme」。
        // 这是 `tangsnow-rs/README.md`「已知口径差异」表里「字母判定」那一行记的**有意保留**的差异
        // （只影响 scheme 合法性判定中的罕见 Unicode 数字）
        // ⇒ 不进共享夹具：只有**两侧同口径**的语料才进夹具。
        assertEquals("", UrlUtils.schemeOf("a²://x"))
    }

    companion object {
        /** 两侧共享的夹具（Rust 侧读同一个文件） */
        const val FIXTURE = "url_utils_cases.json"

        /** 本文件负责的 kind（其余归兄弟文件；并集必须与夹具里的 kind 集合相等） */
        val OWNED_KINDS: Set<String> = setOf(
            "schemeOf",
            "schemeClass",
            "isPortableUrl",
        )
    }
}
