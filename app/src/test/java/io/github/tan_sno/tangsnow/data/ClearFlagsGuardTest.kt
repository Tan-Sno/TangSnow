package io.github.tan_sno.tangsnow.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 内核常量守卫：把「清除浏览数据」所依赖的位值事实绑成断言。
 *
 * ## 为什么值得单独一条测试
 *
 * `ClearDataUseCase` 用 `COOKIES | SITE_DATA | AUTH_SESSIONS` 表达「登出这些站点」，
 * 其中**站点权限（PERMISSIONS）是刻意不单独列举的** —— 依据是 GeckoView 的 `SITE_DATA`
 * 位掩码本身已包含它。这层依赖目前写在代码注释里（注释称已用 `javap -v` 核对过）。
 *
 * 但注释拦不住内核升级后事实变化：上游一旦重排位值，「清除浏览数据」会**静默**漏掉站点
 * 权限或 HTTP 认证态，而用户看到的是「已清除」—— 这正是最不该出错的那类承诺。
 *
 * ## 为什么用反射而不是直接引用字段
 *
 * 反射读的是**当前制品里的真实值**：不受编译期内联影响，也不会因为字段被删而变成编译错误
 * 之外的另一种沉默。同时它顺带证明了 geckoview 确实在单元测试的运行时类路径上。
 * 内核一升级，这条测试就替我们重新问了一遍。
 */
class ClearFlagsGuardTest {

    private fun clearFlag(name: String): Long {
        val cls = Class.forName("org.mozilla.geckoview.StorageController\$ClearFlags")
        return cls.getField(name).getLong(null)
    }

    @Test
    fun `SITE_DATA 必须包含 PERMISSIONS 与 DOM_STORAGES`() {
        val siteData = clearFlag("SITE_DATA")
        // 这三位是 ClearDataUseCase「不单独列举也仍然清得掉」的全部依据
        assertEquals(
            "SITE_DATA 不再包含 PERMISSIONS：ClearDataUseCase 必须显式 or 上它",
            clearFlag("PERMISSIONS"),
            siteData and clearFlag("PERMISSIONS"),
        )
        assertEquals(
            "SITE_DATA 不再包含 DOM_STORAGES：站点存储会残留",
            clearFlag("DOM_STORAGES"),
            siteData and clearFlag("DOM_STORAGES"),
        )
        assertEquals(
            "SITE_DATA 不再包含 COOKIES：登出承诺会失效",
            clearFlag("COOKIES"),
            siteData and clearFlag("COOKIES"),
        )
    }

    @Test
    fun `AUTH_SESSIONS 必须在 SITE_DATA 之外 因此必须单独补上`() {
        // 若哪天上游把它并进 SITE_DATA，ClearDataUseCase 里的显式补充就该删掉（冗余 but 无害）
        assertEquals(
            "AUTH_SESSIONS 已被并入 SITE_DATA：ClearDataUseCase 的显式补充可以删了",
            0L,
            clearFlag("SITE_DATA") and clearFlag("AUTH_SESSIONS"),
        )
    }

    @Test
    fun `SITE_DATA 覆盖两个缓存位 所以只清 Cookie 不清缓存是做不到的`() {
        // 这是一个**对用户可见**的耦合，不是缺陷：内核的位定义决定了 SITE_DATA 内含
        // NETWORK_CACHE/IMAGE_CACHE。勾了「Cookie 与站点数据」就必然连带清缓存，
        // 因此界面上「取消勾选缓存」并不会阻止缓存被清 —— ClearDataUseCase 的注释里写明了这点。
        assertEquals(
            "SITE_DATA 与 ALL_CACHES 不再重叠：注释里那条『会连带清缓存』的说明需要更新",
            clearFlag("ALL_CACHES"),
            clearFlag("SITE_DATA") and clearFlag("ALL_CACHES"),
        )
    }

    @Test
    fun `实际下发的位组合必须含 AUTH_SESSIONS（登出承诺的回归网）`() {
        // CR-005：上面三条只校验**内核位值关系**，从不引用 ClearDataUseCase ⇒ 把
        // `kernelMaskFor` 里的 `AUTH_SESSIONS` 删掉，三条仍全绿，而「勾了 Cookie 与站点数据
        // 就等于登出这些站点」的承诺就失效了（HTTP Basic/Digest 登录态残留、用户以为已登出）。
        // 这里断言**实际下发的组合**（纯函数，JVM 可测）。
        val onlyCookies = ClearDataUseCase.kernelMaskFor(
            ClearDataUseCase.Options(cookiesAndSiteData = true, cache = false)
        )
        assertEquals(
            "勾「Cookie 与站点数据」时必须显式补上 AUTH_SESSIONS：否则登出承诺失效",
            clearFlag("AUTH_SESSIONS"),
            onlyCookies and clearFlag("AUTH_SESSIONS"),
        )
        assertEquals("必须含 COOKIES", clearFlag("COOKIES"), onlyCookies and clearFlag("COOKIES"))
        assertEquals("必须含 SITE_DATA", clearFlag("SITE_DATA"), onlyCookies and clearFlag("SITE_DATA"))
        // ⚠️ 与我第一版写法相反（2026-10-01 实测纠正）：`SITE_DATA` 自带两个缓存位（2|4），
        // 所以"只勾 Cookie 不动缓存"**在当前的位定义下根本做不到** —— 这是内核决定的已知耦合
        //（上面第三条测试与 ClearDataUseCase 的注释都写明了）。这里把这条耦合也钉住：
        assertEquals(
            "SITE_DATA 自带缓存位 ⇒ 勾「Cookie 与站点数据」必然连带清缓存（已知耦合，不是缺陷）",
            clearFlag("ALL_CACHES"),
            onlyCookies and clearFlag("ALL_CACHES"),
        )

        val withCache = ClearDataUseCase.kernelMaskFor(
            ClearDataUseCase.Options(cookiesAndSiteData = true, cache = true)
        )
        assertEquals("勾缓存时必须含 ALL_CACHES", clearFlag("ALL_CACHES"), withCache and clearFlag("ALL_CACHES"))

        assertEquals(
            "两个内核项都没勾时不应下发任何位（调用方据此跳过 clearData）",
            0L,
            ClearDataUseCase.kernelMaskFor(
                ClearDataUseCase.Options(cookiesAndSiteData = false, cache = false)
            ),
        )
    }
}
