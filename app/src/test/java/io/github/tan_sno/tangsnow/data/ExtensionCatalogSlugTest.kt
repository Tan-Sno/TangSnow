package io.github.tan_sno.tangsnow.data

import io.github.tan_sno.tangsnow.extension.ExtensionCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * AMO 列表页地址 → slug 的解析判据。
 *
 * 存在理由（2026-10-02 复扫）：这个正则此前是**裸的** `/addon/([^/?#]+)` —— 未锚定意味着
 * **任何**本地导入的 `.xpi` 只要把 `amoListingUrl` 写成含 `/addon/<别人的 slug>` 的串，就能让
 * `slugOf` 返回别人的 slug ⇒ 精选目录里的扩展"已安装"状态错乱（本地包冒充目录项）。
 * 上方 KDoc 一直写着"非 AMO 来源解析不到"，但代码做不到 —— 本文件把两者钉在一起。
 */
class ExtensionCatalogSlugTest {

    private fun slugOf(url: String): String? =
        ExtensionCatalog.ADDON_PAGE_PATTERN.find(url)?.groupValues?.get(1)

    @Test
    fun `AMO 官方列表页的各种形态都能取到 slug`() {
        // AMO 的实际形态：可选 locale 段 + 平台段（firefox/android）+ addon/<slug>
        assertEquals("ublock-origin", slugOf("https://addons.mozilla.org/firefox/addon/ublock-origin/"))
        assertEquals("ublock-origin", slugOf("https://addons.mozilla.org/en-US/firefox/addon/ublock-origin/"))
        assertEquals("ublock-origin", slugOf("https://addons.mozilla.org/zh-CN/android/addon/ublock-origin"))
        // 没有结尾斜杠、带查询串也要能取到（slug 到 `/`、`?`、`#` 为止）
        assertEquals("darkreader", slugOf("https://addons.mozilla.org/firefox/addon/darkreader?src=search"))
    }

    @Test
    fun `非 AMO 主机的地址一律解析不到——这正是防冒充的关键`() {
        listOf(
            // 本地导入的 .xpi 只要写出这样的 url，旧的正则就会把别人的 slug 交回来
            "https://evil.example/addon/ublock-origin/",
            "https://addons.mozilla.org.evil.example/firefox/addon/ublock-origin/",
            "https://example.com/?next=https://addons.mozilla.org/firefox/addon/ublock-origin/",
            "/firefox/addon/ublock-origin/", // 无主机
            "https://addons.mozilla.org/firefox/extension/ublock-origin/", // 路径不是 /addon/
        ).forEach { url ->
            assertNull("不应解析出 slug：$url", slugOf(url))
        }
    }

    @Test
    fun `正则同时锚定 scheme 与主机——大小写与 http 的处理是刻意的`() {
        // 主机大小写不敏感本应成立，但本正则刻意只接受小写主机：AMO 元数据是规范形式，
        // 放行大小写变体只会扩大"看起来像 AMO"的面，而解析不到的代价仅是判为"不在精选目录内"。
        assertNull(slugOf("https://ADDONS.MOZILLA.ORG/firefox/addon/ublock-origin/"))
        assertFalse(slugOf("http://addons.mozilla.org/firefox/addon/ublock-origin/") == null)
    }
}
