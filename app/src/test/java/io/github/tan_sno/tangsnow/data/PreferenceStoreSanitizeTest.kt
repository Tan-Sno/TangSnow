package io.github.tan_sno.tangsnow.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 偏好里「用户输入 → 安全值」的纯函数测试。
 *
 * 这两个函数是**安全边界**：主页快捷方式与自定义搜索模板都会被写进偏好并在后续
 * 加载/跳转时使用，放行 `javascript:` / `file:` 就等于给了一条执行脚本或读本地文件的入口。
 */
class PreferenceStoreSanitizeTest {

    // ------------------------------------------------------------- normalizeUrl（主页快捷方式）

    @Test
    fun `http 与 https 原样保留`() {
        assertEquals("https://example.com", PreferenceStore.normalizeUrl("https://example.com"))
        assertEquals("http://example.com", PreferenceStore.normalizeUrl("http://example.com"))
    }

    @Test
    fun `裸域名补 https`() {
        assertEquals("https://example.com", PreferenceStore.normalizeUrl("example.com"))
        assertEquals("https://a.b.c/d", PreferenceStore.normalizeUrl("a.b.c/d"))
    }

    @Test
    fun `自定义 scheme 一律拒绝`() {
        assertNull(PreferenceStore.normalizeUrl("javascript:alert(1)"))
        assertNull(PreferenceStore.normalizeUrl("file:///etc/passwd"))
        assertNull(PreferenceStore.normalizeUrl("intent://x#Intent;scheme=http;end"))
        assertNull(PreferenceStore.normalizeUrl("data:text/html,<script>1</script>"))
    }

    @Test
    fun `不含点的单词被拒绝`() {
        assertNull(PreferenceStore.normalizeUrl("hello"))
        assertNull(PreferenceStore.normalizeUrl("百度"))
    }

    @Test
    fun `含空白或为空被拒绝`() {
        assertNull(PreferenceStore.normalizeUrl(""))
        assertNull(PreferenceStore.normalizeUrl("   "))
        assertNull(PreferenceStore.normalizeUrl("exa mple.com"))
    }

    @Test
    fun `前后空白被裁剪后才判断`() {
        assertEquals("https://example.com", PreferenceStore.normalizeUrl("  example.com  "))
    }

    // ------------------------------------------------------------- isHttpTemplate（自定义搜索）

    @Test
    fun `只接受 http 与 https 模板`() {
        assertTrue(PreferenceStore.isHttpTemplate("https://s.test/?q={q}"))
        assertTrue(PreferenceStore.isHttpTemplate("http://s.test/?q={q}"))
        assertTrue(PreferenceStore.isHttpTemplate("  HTTPS://S.TEST/?q={q}  "))
    }

    @Test
    fun `非 http 模板被拒绝`() {
        assertFalse(PreferenceStore.isHttpTemplate("javascript:alert(1)"))
        assertFalse(PreferenceStore.isHttpTemplate("file:///sdcard/x"))
        assertFalse(PreferenceStore.isHttpTemplate("ftp://s.test/?q={q}"))
        assertFalse(PreferenceStore.isHttpTemplate("s.test/?q={q}"))
        assertFalse(PreferenceStore.isHttpTemplate(""))
    }

    // ------------------------------------------------------------- 跟踪保护档位

    @Test
    fun `档位取值集合与常量一致`() {
        assertEquals(
            listOf("off", "standard", "strict", "custom"),
            PreferenceStore.TRACKING_MODES,
        )
    }

    // ------------------------------------------------------------- removeCustomEngine 的下标重映射

    @Test
    fun `非自定义引擎的选中项不受删除影响`() {
        assertEquals("bing_cn", PreferenceStore.remapSelectedEngine("bing_cn", 0))
        assertEquals(
            SearchEngines.DEFAULT_ID,
            PreferenceStore.remapSelectedEngine(SearchEngines.DEFAULT_ID, 3),
        )
    }

    @Test
    fun `删掉被选中的那个自定义引擎时回退默认`() {
        assertEquals(SearchEngines.DEFAULT_ID, PreferenceStore.remapSelectedEngine("custom_2", 2))
        assertEquals(SearchEngines.DEFAULT_ID, PreferenceStore.remapSelectedEngine("custom_0", 0))
    }

    @Test
    fun `删掉选中项之前的引擎时下标前移一位`() {
        // 自定义 id 是 `custom_<下标>`：删掉前面的条目后，同一个引擎的下标会前移
        assertEquals("custom_1", PreferenceStore.remapSelectedEngine("custom_2", 1))
        assertEquals("custom_0", PreferenceStore.remapSelectedEngine("custom_1", 0))
        // 删的是 index 1，原来在下标 3 的引擎落到下标 2（不是回退默认）
        assertEquals("custom_2", PreferenceStore.remapSelectedEngine("custom_3", 1))
    }

    @Test
    fun `删掉选中项之后的引擎时选中项原样不变`() {
        assertEquals("custom_1", PreferenceStore.remapSelectedEngine("custom_1", 2))
        assertEquals("custom_0", PreferenceStore.remapSelectedEngine("custom_0", 5))
    }

    @Test
    fun `自定义 id 后缀不可解析时回退默认`() {
        assertEquals(SearchEngines.DEFAULT_ID, PreferenceStore.remapSelectedEngine("custom_", 0))
        assertEquals(SearchEngines.DEFAULT_ID, PreferenceStore.remapSelectedEngine("custom_x", 0))
    }
}
