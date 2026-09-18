package com.tangsnow.tangsnow.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索引擎表与关键词回显的单元测试。
 *
 * 覆盖两处历史缺陷：
 *  - [SearchEngines.find] 的兜底：常量与内置列表不一致时不能让启动路径抛异常；
 *  - [SearchEngines.hostMatches] 的主机别名：在 `www.bing.com` 上搜索时，
 *    地址栏要显示关键词而不是整条 URL。
 */
class SearchEnginesTest {

    @Test
    fun `按 id 命中内置引擎`() {
        val all = SearchEngines.builtins
        assertEquals("baidu", SearchEngines.find(all, "baidu").id)
        assertEquals("bing_cn", SearchEngines.find(all, "bing_cn").id)
    }

    @Test
    fun `未知 id 回退默认引擎而不是抛异常`() {
        val all = SearchEngines.builtins
        assertEquals(SearchEngines.DEFAULT_ID, SearchEngines.find(all, "no-such-engine").id)
        assertEquals(SearchEngines.DEFAULT_ID, SearchEngines.find(all, null).id)
    }

    @Test
    fun `空列表也能兜底到第一个内置引擎`() {
        assertEquals(SearchEngines.builtins.first().id, SearchEngines.find(emptyList(), "baidu").id)
    }

    @Test
    fun `默认引擎必须存在于内置列表且带查询参数名`() {
        val def = SearchEngines.builtins.first { it.id == SearchEngines.DEFAULT_ID }
        assertEquals("q", def.queryParam)
        assertTrue(def.template.startsWith("https://"))
    }

    @Test
    fun `同一引擎的其它官方主机被认作同一引擎`() {
        // 模板主机 cn.bing.com；用户在 bing.com / www.bing.com 上搜索也算同一引擎
        assertTrue(SearchEngines.hostMatches("cn.bing.com", "bing_cn", "cn.bing.com"))
        assertTrue(SearchEngines.hostMatches("bing.com", "bing_cn", "cn.bing.com"))
        assertTrue(SearchEngines.hostMatches("www.bing.com", "bing_cn", "cn.bing.com"))
        assertTrue(SearchEngines.hostMatches("m.baidu.com", "baidu", "www.baidu.com"))
        assertTrue(SearchEngines.hostMatches("sm.cn", "sm", "m.sm.cn"))
    }

    @Test
    fun `子域被认作同一引擎`() {
        assertTrue(SearchEngines.hostMatches("images.baidu.com", "baidu", "www.baidu.com"))
    }

    @Test
    fun `无关主机不被误判`() {
        assertFalse(SearchEngines.hostMatches("evil.com", "baidu", "www.baidu.com"))
        // 后缀相似但不是子域（baidu.com.evil.com 的 bare 是 baidu.com.evil.com）
        assertFalse(SearchEngines.hostMatches("baidu.com.evil.com", "baidu", "www.baidu.com"))
        assertFalse(SearchEngines.hostMatches("google.com", "bing_cn", "cn.bing.com"))
    }

    // --------------------------------------- 模板主机提取（取代 Uri.parse，纯字符串）

    @Test
    fun `从模板取出主机`() {
        // 生产模板的真实形态
        assertEquals("cn.bing.com", SearchEngines.templateHostOf("https://cn.bing.com/search?q={q}"))
        assertEquals("www.baidu.com", SearchEngines.templateHostOf("https://www.baidu.com/s?wd={q}"))
        assertEquals("sm.cn", SearchEngines.templateHostOf("http://sm.cn/s?q={q}"))
        // 结尾无查询串 / 有片段 / 有端口
        assertEquals("a.com", SearchEngines.templateHostOf("https://a.com"))
        assertEquals("a.com", SearchEngines.templateHostOf("https://a.com/"))
        assertEquals("a.com", SearchEngines.templateHostOf("https://a.com#x"))
        assertEquals("a.com", SearchEngines.templateHostOf("https://a.com:8443/search?q={q}"))
        // 省略协议（自定义引擎可能这么写）
        assertEquals("a.com", SearchEngines.templateHostOf("a.com/search?q={q}"))
        // userinfo 要被去掉，只留主机
        assertEquals("a.com", SearchEngines.templateHostOf("https://u:p@a.com/x"))
        // 大小写归一
        assertEquals("cn.bing.com", SearchEngines.templateHostOf("HTTPS://CN.Bing.COM/search?q={q}"))
    }

    @Test
    fun `取不到主机时返回 null`() {
        assertNull(SearchEngines.templateHostOf(""))
        assertNull(SearchEngines.templateHostOf("https:///search?q={q}"))
        assertNull(SearchEngines.templateHostOf("https://:8080/x"))
    }
}
