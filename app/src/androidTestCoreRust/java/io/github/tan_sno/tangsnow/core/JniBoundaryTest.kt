package io.github.tan_sno.tangsnow.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Rust 核心在设备上的 JNI 边界硬证据**（2026-10-19，W1 接线的「设备侧那一半」）。
 *
 * ## 为什么必须有这个文件
 * JVM 单测里 `CoreNative.available` 恒为 `false` —— `.so` 是 Android 的 ELF，JVM 载不进来，
 * 于是所有桥函数在进 native 之前就返回安全默认值。差别在于：**两边都绿**，
 * 但它们证明的只是「降级路径没崩」，而不是「Rust 真的接上了」。
 *
 * 2026-10-15 的 M5（`displayForUrl` 多出 `engineId` 形参）与 H3（CESU-8 解码修正）都属于
 * **只有设备才看得见**的一档：JNI 对非重载方法按短名查找，形参错位**不报错**，
 * 只会静默绑定到旧 callee 跑旧语义（见 [CoreNative] 尾部那段注释）。
 * 所以本文件在设备上直接断言：库加载成功 + 7 个 W1 函数绑定到**当前**语义。
 *
 * ## 为什么不起 Activity
 * 仪器测试进程**不启动任何 Activity、不碰 GeckoView** —— 这是刻意的：本机 16KB 页 AVD 上
 * 「启动棠雪浏览器 UI」会让模拟器进程段错误（两种渲染模式都复现，见 coding_memory/android/
 * tangsnow-16k-avd-and-emulator.md）。本文件只做进程内纯函数调用，绕开那个触发点。
 *
 * ## 为什么文件在 `src/androidTestCoreRust` 而不是共享 `src/androidTest`
 * 同 `app/src/testCoreRust` 的既成理由（见 `CoreBridgeDecodeTest` 类注释）：[CoreNative] 只
 * 存在于 coreRust 源集，共享 androidTest 一旦引用它，coreKotlin 那条通道的仪器测试编译不过。
 * 代价如实写明：coreKotlin 通道不跑本文件 —— 它没有 `CoreNative` 可测（恒 `available=false`）。
 */
class JniBoundaryTest {

    @Test
    fun 核心库已加载而不是降级档() {
        // 这一条是其余断言的前提。失败时先查 `app/src/coreRust/jniLibs/<abi>/libtangsnow_core.so`
        // 有没有随包进这个 APK（`unzip -l app-coreRust-*-debug.apk | grep libtangsnow_core`）。
        assertTrue(
            "System.loadLibrary(\"tangsnow_core\") 失败：核心库没进包或 ABI 不匹配，" +
                "下面的断言全都失去意义。",
            CoreNative.available,
        )
    }

    @Test
    fun 地址栏三函数绑定到当前语义() {
        // 允许集与 android.net.Uri.encode 一致（A-Za-z0-9 _ - ! . ~ ' ( ) *）：
        // 空格 → %20，`&` → %26。恒等降级会把 `&` 原样插进模板（注入），故这里钉的是**真编码**。
        assertEquals("a%20b%26c", CoreNative.percentEncode("a b&c"))
        assertEquals("https", CoreNative.schemeOf("HTTPS://Example.com/x"))
        assertTrue(CoreNative.looksLikeUri("https://example.com/path"))
        assertFalse(CoreNative.looksLikeUri("two words"))
    }

    @Test
    fun 可携带判据绑定到当前语义() {
        assertTrue(CoreNative.isPortableUrl("https://example.com/path"))
        assertFalse(CoreNative.isPortableUrl("file:///etc/hosts"))
    }

    @Test
    fun 搜索构造与关键词回显绑定到当前语义() {
        assertEquals(
            "https://www.bing.com/search?q=cats",
            CoreNative.searchUrlFromTemplate("https://www.bing.com/search?q={q}", "cats"),
        )
        // ⚠️ M5 探针：4 参 + **真 engineId**。若绑定到旧的三参 callee，别名表不命中 ⇒
        // 这里拿到 null 而不是 "cats"（无异常、无日志），正是本断言要抓住的静默漂移。
        assertEquals(
            "cats",
            CoreNative.displayForUrl(
                "https://cn.bing.com/search?q=cats",
                "https://cn.bing.com/search?q={q}",
                "q",
                "bing_cn",
            ),
        )
        // 非本引擎的结果页 = null。null 在这里是**业务结果**不是失败（nullIsResult=true），
        // 所以它同样能证明调用真的走到了 Rust：桥没绑上时这一档也是 null，
        // 但上面那条正例已经把「绑定到当前语义」钉住了。
        assertNull(
            CoreNative.displayForUrl(
                "https://example.com/?q=cats",
                "https://cn.bing.com/search?q={q}",
                "q",
                "bing_cn",
            ),
        )
    }

    @Test
    fun 地址栏解析返回两元组() {
        val r: Array<String>? = CoreNative.resolveInfo("cats", "https://cn.bing.com/search?q={q}")
        assertNotNull("resolveInfo 返回 null = 桥没绑上或 Rust 侧失败", r)
        val pair = r!! // 上一行已断言非空
        assertEquals("搜索输入应被判为搜索而非 URL", "1", pair[1])
        assertTrue(
            "搜索 URL 应落在模板主机上，实际=${pair[0]}",
            pair[0].startsWith("https://cn.bing.com/search"),
        )
    }

    @Test
    fun 接缝真的接管了而不是回落共享实现() {
        // 上面几条证明**桥**能用；这条证明 CoreImpl 那道闸确实开着 ——
        // `available == true` ⇒ 不再返回 null 去回落 `src/main` 的纯 Kotlin 实现。
        // 二者一起才是「coreRust 通道真的在用 Rust 算」。
        assertEquals("a%20b", CoreImpl.percentEncode("a b"))
        assertEquals("https", CoreImpl.schemeOf("HTTPS://Example.com/x"))
        assertTrue(CoreImpl.looksLikeUri("https://example.com/path") == true)
        assertTrue(CoreImpl.isPortableUrl("file:///etc/hosts") == false)
    }
}