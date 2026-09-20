package io.github.tan_sno.tangsnow.update

import android.content.Context

/**
 * 版本信息与官方发布页。
 *
 * ## 为什么这里不自动联网比对版本
 *
 * 早前留了一套「自托管更新清单（返回 versionCode 的 JSON）」的脚手架，但一直没有、
 * 也不打算为此维护一台服务端；而本项目实际的分发渠道是 **GitHub Releases**，
 * 它**只给 tag 名（`v2.1.0`）而不给 `versionCode`** —— 清单那套的比对基准与真实渠道
 * 对不上。既然外壳无人填充、口径也不匹配，就整体删掉了（历史仍可在 git 里查到），
 * 只保留真正需要的两件事：**当前版本**与**官方发布页地址**。
 *
 * ## 为什么也不直接调 GitHub API
 *
 * `api.github.com` 会成为本应用第三个对外端点，而隐私政策第 4 条目前只列举了
 * Mozilla 官方服务（AMO 与名单服务），并明确「除此之外不发请求」。要加就得同步
 * 修订政策文本（属产品/法律决定，不能悄悄做）。
 *
 * ## 现在的做法
 *
 * 用**应用自身的浏览器**打开官方发布页。这一动作在政策里已被覆盖（第 4 条列有
 * 「您主动访问的网站」），零新增对外端点，用户还能直接看到最新版本号、发布说明，
 * 并在同一页面下载 —— 不假装「已是最新」，也不假装置灰不可用。
 */
object UpdateChecker {

    /** 官方发布页。`latest` 由 GitHub 重定向到最新一版，故无需随版本改动。 */
    const val RELEASES_URL: String = "https://github.com/Tan-Sno/TangSnow/releases/latest"

    data class Current(val versionCode: Long, val versionName: String)

    fun current(context: Context): Current = try {
        val app = context.applicationContext
        val pkg = app.packageManager.getPackageInfo(app.packageName, 0)
        Current(
            versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) pkg.longVersionCode
            else @Suppress("DEPRECATION") pkg.versionCode.toLong(),
            versionName = pkg.versionName.orEmpty(),
        )
    } catch (e: Exception) {
        // 取不到版本信息不该让调用方崩溃：返回占位值，界面用 pref_version_unknown 兜底
        Current(0, "")
    }
}
