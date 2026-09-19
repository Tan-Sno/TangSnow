package io.github.tan_sno.tangsnow.data

import io.github.tan_sno.tangsnow.R
import io.github.tan_sno.tangsnow.extension.ExtensionCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用语言标签与扩展目录的单元测试。
 *
 * 扩展目录部分同时充当**合规回归网**：目录里不得再出现首要定位为广告拦截 / 去广告的扩展
 * （uBlock Origin / AdGuard / Ghostery / SponsorBlock），一旦有人加回来，这里立刻失败。
 */
class LocaleAndCatalogTest {

    // ------------------------------------------------------------- LocaleManager

    @Test
    fun `受支持的语言被规范化`() {
        assertEquals("zh", LocaleManager.normalize("zh"))
        assertEquals("en", LocaleManager.normalize("en"))
    }

    @Test
    fun `跟随系统与不支持的语言都归为空标签`() {
        assertEquals("", LocaleManager.normalize(""))
        assertEquals("", LocaleManager.normalize(null))
        assertEquals("", LocaleManager.normalize("fr"))
        assertEquals("", LocaleManager.normalize("zh-Hans"))
    }

    // ------------------------------------------------------------- 扩展目录

    private fun entry(slug: String, id: Int) =
        ExtensionCatalog.Entry(slug, id, "Test", R.string.app_name, R.color.accent, null)

    @Test
    fun `直链与官方页地址按 AMO 官方形式拼装`() {
        val e = entry("demo", 42)
        assertEquals(
            "https://addons.mozilla.org/firefox/downloads/latest/demo/addon-42-latest.xpi",
            e.xpiUrl,
        )
        assertEquals("https://addons.mozilla.org/firefox/addon/demo/", e.officialPage)
        // bestUrl 必须优先用 latest 直链（实测该形式可直接下载）
        assertEquals(e.xpiUrl, ExtensionCatalog.bestUrl(e))
    }

    @Test
    fun `已安装判定按 slug 精确匹配，不按名称兜底`() {
        val e = entry("demo", 42)
        assertFalse(ExtensionCatalog.isInstalled(e, emptySet()))
        assertTrue(ExtensionCatalog.isInstalled(e, setOf("demo")))
        // 同名但不同 slug 的扩展不得被认成已安装
        assertFalse(ExtensionCatalog.isInstalled(e, setOf("other-slug")))
    }

    @Test
    fun `卸载后会话内记录被清除，该扩展重新变为可安装`() {
        val e = entry("demo", 42)
        ExtensionCatalog.installedIdsBySlug["demo"] = "demo@ext"
        try {
            assertTrue(ExtensionCatalog.isInstalled(e, emptySet()))
            ExtensionCatalog.forgetInstalled("demo@ext")
            assertFalse(ExtensionCatalog.isInstalled(e, emptySet()))
        } finally {
            ExtensionCatalog.installedIdsBySlug.remove("demo")
        }
    }

    @Test
    fun `目录不得收录广告拦截类扩展（合规回归）`() {
        val forbidden = setOf("ublock-origin", "ublock", "adguard", "ghostery", "adblock-plus", "adblock")
        val hits = ExtensionCatalog.all.map { it.slug }.filter { it.lowercase() in forbidden }
        assertTrue("精选目录出现了广告拦截类扩展：$hits", hits.isEmpty())
    }

    @Test
    fun `目录条目自身必须完整且 slug 唯一`() {
        val slugs = ExtensionCatalog.all.map { it.slug }
        assertEquals("slug 不得重复：$slugs", slugs.size, slugs.toSet().size)
        ExtensionCatalog.all.forEach { e ->
            assertTrue("${e.slug} 的 addonId 必须为正", e.addonId > 0)
            assertTrue("${e.slug} 的 descRes 未设置", e.descRes != 0)
            assertTrue("${e.slug} 的 colorRes 未设置", e.colorRes != 0)
        }
    }
}
