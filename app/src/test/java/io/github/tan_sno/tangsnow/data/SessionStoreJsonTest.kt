package io.github.tan_sno.tangsnow.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SessionStore JSON 序列化的往返与容错测试。
 *
 * 依赖 testImplementation 引入的真实 org.json 实现：android.jar 里的 org.json 在
 * JVM 单元测试中是抛 Stub! 异常的桩，Maven 版覆盖 test classpath 后纯函数才可测。
 * 这两个函数承载两件必须钉死的事：进程回收后的会话恢复（丢字段 = 丢标签历史栈）、
 * 以及「清除浏览数据后快照不得复活」的隐私承诺（空串归 null 的归一语义）。
 */
class SessionStoreJsonTest {

    private fun snap(
        vararg tabs: SessionStore.TabSnapshot,
        activeIndex: Int = 0,
    ) = SessionStore.Snapshot(activeIndex, tabs.toList())

    @Test
    fun `往返保留全部字段`() {
        val s = snap(
            SessionStore.TabSnapshot("https://example.com/a", "示例标题", "{\"x\":1}"),
            SessionStore.TabSnapshot(null, "空白标签", null),
            activeIndex = 1,
        )
        val parsed = SessionStore.parse(SessionStore.buildJson(s))!!
        assertEquals(1, parsed.activeIndex)
        assertEquals(2, parsed.tabs.size)
        assertEquals("https://example.com/a", parsed.tabs[0].url)
        assertEquals("示例标题", parsed.tabs[0].title)
        assertEquals("{\"x\":1}", parsed.tabs[0].sessionState)
        assertNull(parsed.tabs[1].url)
        assertNull(parsed.tabs[1].sessionState)
    }

    @Test
    fun `中文与引号等特殊字符往返无损`() {
        val s = snap(
            SessionStore.TabSnapshot("https://例子.测试/路径?q=中文", "标题带\"引号<尖>", "{}"),
        )
        val parsed = SessionStore.parse(SessionStore.buildJson(s))!!
        assertEquals("https://例子.测试/路径?q=中文", parsed.tabs[0].url)
        assertEquals("标题带\"引号<尖>", parsed.tabs[0].title)
    }

    @Test
    fun `空串字段归一为 null`() {
        // buildJson 落盘时空串 → parse 读回 null 的归一是「空白标签恢复时回退 loadUri」的前提
        val parsed = SessionStore.parse(SessionStore.buildJson(snap(SessionStore.TabSnapshot("", "", ""))))!!
        assertNull(parsed.tabs[0].url)
        assertEquals("", parsed.tabs[0].title)
        assertNull(parsed.tabs[0].sessionState)
    }

    @Test
    fun `空标签列表合法`() {
        val parsed = SessionStore.parse(SessionStore.buildJson(snap()))!!
        assertEquals(0, parsed.tabs.size)
        assertEquals(0, parsed.activeIndex)
    }

    @Test
    fun `损坏输入无法得到快照`() {
        // parse 本身不吞异常（损坏输入抛 JSONException），read() 层用 runCatching 兜成 null ——
        // 这里按调用方口径断言「runCatching 之后拿不到快照」
        assertNull(runCatching { SessionStore.parse("not json") }.getOrNull())
        assertNull(runCatching { SessionStore.parse("") }.getOrNull())
        // tabs 不是数组：optJSONArray 返回 null → parse 返回 null（不抛）
        assertNull(SessionStore.parse("{\"active\":0,\"tabs\":3}"))
    }
}
