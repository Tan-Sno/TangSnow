package io.github.tan_sno.tangsnow.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新检查里**纯逻辑**部分的单测。
 *
 * 为什么只测这三个函数：它们是这个功能里唯一「会算错却看不出来」的地方 ——
 * 版本比错的后果是**误报有新版本**或**漏报不提示**，两者都很难在真机上发现；
 * 而 ABI 挑错会让人下到不能装的包。其余部分（网络、弹窗）依赖 Android 运行时，
 * 按项目一贯做法不引入 Robolectric，靠真机验证。
 */
class UpdateCheckerTest {

    // ---------------------------------------------------------- normalizeVersion

    @Test
    fun `tag 前缀 v 会被去掉`() {
        assertEquals("2.1.0", UpdateChecker.normalizeVersion("v2.1.0"))
        assertEquals("2.1.0", UpdateChecker.normalizeVersion("V2.1.0"))
        assertEquals("2.1.0", UpdateChecker.normalizeVersion("  2.1.0  "))
    }

    @Test
    fun `预发布后缀会被截掉`() {
        assertEquals("2.1.0", UpdateChecker.normalizeVersion("v2.1.0-beta1"))
        assertEquals("2.1.0", UpdateChecker.normalizeVersion("2.1.0+build7"))
    }

    @Test
    fun `无法识别时返回空串而不是抛异常`() {
        assertEquals("", UpdateChecker.normalizeVersion(""))
        assertEquals("", UpdateChecker.normalizeVersion("v"))
        assertEquals("", UpdateChecker.normalizeVersion("release-latest"))
    }

    // ---------------------------------------------------------------- isNewer

    @Test
    fun `常规的新旧判断`() {
        assertTrue(UpdateChecker.isNewer("2.2.0", "2.1.0"))
        assertTrue(UpdateChecker.isNewer("2.1.1", "2.1.0"))
        assertTrue(UpdateChecker.isNewer("3.0.0", "2.9.9"))
        assertFalse(UpdateChecker.isNewer("2.1.0", "2.1.0"))
        assertFalse(UpdateChecker.isNewer("2.0.9", "2.1.0"))
    }

    @Test
    fun `逐段按整数比较而不是按字符串`() {
        // 字符串比较会得出 "2.10.0" < "2.9.0"（因为 '1' < '9'），必须按整数比
        assertTrue(UpdateChecker.isNewer("2.10.0", "2.9.0"))
        assertFalse(UpdateChecker.isNewer("2.9.0", "2.10.0"))
        assertTrue(UpdateChecker.isNewer("2.1.10", "2.1.9"))
    }

    @Test
    fun `段数不同时缺失段按 0 处理`() {
        assertFalse(UpdateChecker.isNewer("2.1", "2.1.0"))
        assertTrue(UpdateChecker.isNewer("2.1.1", "2.1"))
        assertTrue(UpdateChecker.isNewer("2.2", "2.1.9"))
    }

    @Test
    fun `带 tag 前缀与预发布后缀也能正确比较`() {
        assertTrue(UpdateChecker.isNewer("v2.2.0", "2.1.0"))
        assertFalse(UpdateChecker.isNewer("v2.1.0-beta", "2.1.0"))
    }

    @Test
    fun `任一侧无法解析时一律返回 false（宁可不提示，也不误报）`() {
        assertFalse(UpdateChecker.isNewer("", "2.1.0"))
        assertFalse(UpdateChecker.isNewer("2.2.0", ""))
        assertFalse(UpdateChecker.isNewer("vNext", "2.1.0"))
        assertFalse(UpdateChecker.isNewer("2.x.0", "2.1.0"))
    }

    // -------------------------------------------------------------- pickAsset

    private val realAssets = listOf(
        "app-arm64-v8a-release.apk",
        "app-armeabi-v7a-release.apk",
        "app-x86_64-release.apk",
    )

    @Test
    fun `按设备 ABI 优先级挑包，64 位设备优先拿 arm64`() {
        // Build.SUPPORTED_ABIS 在 arm64 设备上通常长这样
        assertEquals(
            "app-arm64-v8a-release.apk",
            UpdateChecker.pickAsset(realAssets, listOf("arm64-v8a", "armeabi-v7a", "armeabi")),
        )
    }

    @Test
    fun `只有 32 位支持的设备拿到 v7a 包`() {
        assertEquals(
            "app-armeabi-v7a-release.apk",
            UpdateChecker.pickAsset(realAssets, listOf("armeabi-v7a", "armeabi")),
        )
    }

    @Test
    fun `模拟器拿到 x86_64 包`() {
        assertEquals(
            "app-x86_64-release.apk",
            UpdateChecker.pickAsset(realAssets, listOf("x86_64", "x86")),
        )
    }

    @Test
    fun `32 位设备拿到 v7a 包（真实设备的 ABI 列表是 v7a 在前）`() {
        // Build.SUPPORTED_ABIS 按「首选架构优先」排列，32 位真机上报的是
        // ["armeabi-v7a", "armeabi"]，故先命中 v7a；顺序不能自行重排。
        assertEquals(
            "app-armeabi-v7a-release.apk",
            UpdateChecker.pickAsset(realAssets, listOf("armeabi-v7a", "armeabi")),
        )
    }

    @Test
    fun `匹配按分隔符卡边界，不会命中无关资产名`() {
        // 说明（别高估这条检查）：仅报告 `armeabi` 的设备仍会命中 `app-armeabi-v7a-…`，
        // 因为 "armeabi" 恰好是 "armeabi-v7a" 的前缀且后面紧跟连字符 —— 单靠**文件名**
        // 无法区分二者。这**不构成实际问题**：`armeabi`-only 的设备年代早于本应用的
        // minSdk 26，根本装不上。真正被这里防住的是「ABI 串出现在无关资产名里」。
        assertNull(UpdateChecker.pickAsset(realAssets, listOf("mips")))
        assertNull(UpdateChecker.pickAsset(listOf("app-release.apk"), listOf("arm64-v8a")))
    }

    @Test
    fun `完整 ABI 串仍能正常命中`() {
        assertEquals(
            "app-armeabi-v7a-release.apk",
            UpdateChecker.pickAsset(realAssets, listOf("armeabi-v7a")),
        )
    }

    @Test
    fun `没有匹配项时返回 null（交给调用方回退到打开发布页）`() {
        assertNull(UpdateChecker.pickAsset(realAssets, listOf("mips")))
        assertNull(UpdateChecker.pickAsset(realAssets, emptyList()))
    }

    @Test
    fun `忽略非 apk 资产与空 ABI`() {
        val mixed = listOf("mapping.txt", "notes.md", "app-arm64-v8a-release.apk")
        assertEquals(
            "app-arm64-v8a-release.apk",
            UpdateChecker.pickAsset(mixed, listOf("", "arm64-v8a")),
        )
        assertNull(UpdateChecker.pickAsset(listOf("mapping.txt", "notes.md"), listOf("arm64-v8a")))
    }
}
