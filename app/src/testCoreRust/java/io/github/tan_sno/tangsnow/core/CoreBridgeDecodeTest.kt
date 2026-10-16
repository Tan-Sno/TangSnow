package io.github.tan_sno.tangsnow.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * **Rust 边界吐回坏 JSON 时不许闪退**（2026-10-15 外部审查 H2）。
 *
 * ## 为什么文件在 `src/testCoreRust` 而不是共享 `src/test`
 * `CoreBridge` 只存在于 coreRust 这个 flavor 源集，共享测试一引用它，
 * `compileCoreKotlinDebugUnitTestKotlin` 就 `Unresolved reference`（PLAN-2026-10-15 §一
 * 的探针实测）。代价如实写明：coreKotlin 那条通道不跑本文件 —— 它也没有这个类可测。
 *
 * ## 为什么测 `decode` 而不是端到端
 * JVM 上 `CoreNative.available == false`，所有桥函数在进 native 之前就返回安全默认值，
 * 「调用成功但吐回坏 JSON」这一档在 JVM 里**只有**从 decode 这一层才能注入。
 * 端到端那一半要等 `.so` + 真机（STATUS 的冻结线）。
 */
class CoreBridgeDecodeTest {

    @Test
    fun 形状正确时解出条目与计数() {
        val ok = """{"entries":[{"url":"https://a.example","title":"甲","created_at_ms":1700000000000},""" +
            """{"url":"https://b.example","title":"乙"}],"dropped":2}"""
        val (entries, dropped) = CoreBridge.decode(ok, "dropped") ?: error("合法 JSON 不该被判成失败")
        assertEquals(2, entries.size)
        assertEquals("https://a.example", entries[0].url)
        assertEquals("甲", entries[0].title)
        assertEquals(1700000000000L, entries[0].createdAtMs ?: -1L)
        // 缺失的 created_at_ms = null（Rust 侧 skip_serializing_if = "Option::is_none"）
        assertNull(entries[1].createdAtMs)
        assertEquals("乙", entries[1].title)
        assertEquals(2, dropped)
    }

    @Test
    fun 空条目数组是合法结果而不是失败() {
        // 「这个文件里确实一条书签都没有」必须与「解不开」分得开 —— 这正是回落值不能取
        // `emptyList to 0` 的原因：那会让失败与空文件在调用方眼里长得一样（把失败报成成功）。
        val (entries, dropped) = CoreBridge.decode("""{"entries":[],"dropped":3}""", "dropped")
            ?: error("空数组是合法形状")
        assertEquals(0, entries.size)
        assertEquals(3, dropped)
    }

    @Test
    fun 坏_JSON_一律回落成失败而不是抛出异常() {
        // 下面每一条在改动前都会抛出未捕获的 JSONException（= 一触发就闪退），
        // 或穿透到 `entryFrom` 里抛。现在统一判成 null。
        val bad = listOf(
            "" to "空串",
            "not json at all" to "根本不是 JSON",
            "[]" to "顶层是数组不是对象",
            "{}" to "缺 entries",
            """{"entries":{},"dropped":0}""" to "entries 不是数组",
            """{"entries":null,"dropped":0}""" to "entries 是 null",
            """{"entries":[{}],"dropped":0}""" to "条目缺 url/title",
            """{"entries":[{"url":"u"}],"dropped":0}""" to "条目缺 title",
            """{"entries":[],"count":0}""" to "计数键名不对（应为 dropped / skipped）",
            """{"entries":[],"dropped":"很多"}""" to "计数不是数",
            """{"entries":[{"url":"u","title":"t","created_at_ms":"abc"}],"dropped":0}""" to "时间戳不是数",
            """{"entries":"abc","dropped":0}""" to "entries 是字符串",
        )
        for ((json, why) in bad) {
            assertNull("「$why」必须判成失败而不是抛出：$json", CoreBridge.decode(json, "dropped"))
        }
    }

    @Test
    fun 计数键随调用方而不同() {
        // sanitize 的输出键是 skipped，parse 的是 dropped：传错键必须判失败（而不是当成 0）。
        val skipped = """{"entries":[{"url":"u","title":"t"}],"skipped":1}"""
        val (entries, n) = CoreBridge.decode(skipped, "skipped") ?: error("skipped 形状应合法")
        assertEquals(1, entries.size)
        assertEquals(1, n)
        assertNull(CoreBridge.decode(skipped, "dropped"))
    }
}
