package io.github.tan_sno.tangsnow.update

import android.content.Context
import io.github.tan_sno.tangsnow.data.AppHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 应用内更新检查：读取 GitHub Releases 的最新一版，与当前版本比对。
 *
 * ## 数据来源为什么是 GitHub API
 *
 * 早前留过一套「自托管更新清单（返回 `versionCode` 的 JSON）」的脚手架，但本项目
 * 既没有、也不打算维护那样一台服务端；而实际的分发渠道就是 GitHub Releases。
 * 直接读它，省掉一个需要长期维护的中间件。
 *
 * 代价是**新增一个对外端点** `api.github.com` —— 隐私政策第 4 条原本只列举了
 * Mozilla 官方服务，因此本功能落地时**同步修订了政策文本并把 `POLICY_VERSION` 提到 18**。
 * 这是有意的、被披露过的取舍，不是悄悄加上的。
 *
 * ## 三点刻意的设计
 *
 * 1. **只由用户发起，绝不后台自检。** 只有你点「检查更新」才发这一次请求；
 *    应用启动、切前后台、定时任务都不会调它。这样它就不构成「后台遥测」。
 * 2. **比对基准是 versionName 而非 versionCode。** GitHub 只给 tag 名（`v2.1.0`），
 *    没有 versionCode；而本应用的 versionCode 是「基准×10+ABI序号」（331/332/333），
 *    跨 ABI 本就不可比。故按 `x.y.z` 逐段数值比较。
 * 3. **失败一律返回 null，不猜。** 网络失败、非 200、JSON 异常都归为「检查失败」，
 *    界面如实提示，而不是回落成「已是最新」（那是假反馈）。
 */
object UpdateChecker {

    /** 官方发布页。`latest` 由 GitHub 重定向到最新一版，故无需随版本改动。 */
    const val RELEASES_URL: String = "https://github.com/Tan-Sno/TangSnow/releases/latest"

    /** 最新一版的 API。**这是本应用第三个对外端点**，政策第 4 条已披露。 */
    const val LATEST_RELEASE_API: String =
        "https://api.github.com/repos/Tan-Sno/TangSnow/releases/latest"

    /** 资产文件名以它结尾的才是 APK。 */
    private const val ASSET_SUFFIX = ".apk"

    /**
     * 从 tag 名里取版本号主体。
     *
     * 提为常量而非在函数里现建：`Regex` 构造即编译，而本函数在每次检查里会被调用
     * 若干次（`isNewer` 两侧各一次、`segments` 再各一次）。本仓库其它几处正则
     * （`UrlUtils` / `DownloadRepo` / `AboutActivity`）也都这么写，保持一致。
     */
    private val VERSION_PREFIX = Regex("""^(\d+(?:\.\d+)*)""")

    data class Current(val versionCode: Long, val versionName: String)

    /** 远端最新一版。 */
    data class Release(
        /** 已去掉 tag 前缀 `v`，如 `2.1.0` */
        val versionName: String,
        /** 发布说明原文（可能为空） */
        val notes: String?,
        /** 与本机 ABI 匹配的 APK 直链；没有匹配项时为 null */
        val apkUrl: String?,
    )

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

    /**
     * 拉取最新一版，并挑出与本机 ABI 匹配的 APK 直链。
     *
     * @param supportedAbis 本机支持的 ABI，按优先级排列（传 `Build.SUPPORTED_ABIS`）
     * @return 远端版本信息；网络失败 / 非 200 / 解析失败时为 null
     */
    suspend fun fetchLatest(supportedAbis: List<String>): Release? = withContext(Dispatchers.IO) {
        try {
            val req = AppHttp.get(LATEST_RELEASE_API, acceptJson = true).build()
            AppHttp.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val json = JSONObject(resp.body.string())

                val version = normalizeVersion(json.optString("tag_name", ""))
                if (version.isEmpty()) return@withContext null

                // 资产名 → 下载直链
                val byName = LinkedHashMap<String, String>()
                json.optJSONArray("assets")?.let { assets ->
                    for (i in 0 until assets.length()) {
                        val a = assets.optJSONObject(i) ?: continue
                        val name = a.optString("name", "")
                        val url = a.optString("browser_download_url", "")
                        if (name.isNotEmpty() && url.isNotEmpty()) byName[name] = url
                    }
                }
                val picked = pickAsset(byName.keys.toList(), supportedAbis)

                Release(
                    versionName = version,
                    notes = json.optString("body", "").trim().ifBlank { null },
                    apkUrl = picked?.let { byName[it] },
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 取消要原样传播，不能降级成 null：「null = 检查失败」会让调用方
            // 把已取消的协程当成一次真实失败（例如弹出「检查更新失败」）。
            throw e
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------- 纯逻辑（可单测）

    /**
     * 把 tag 名规整成可比较的版本号：去掉前缀 `v` / `V`，截掉首个非数字后缀。
     *
     * `v2.1.0` → `2.1.0`；`2.1.0-beta1` → `2.1.0`；无法识别时返回空串。
     */
    internal fun normalizeVersion(raw: String): String {
        val trimmed = raw.trim().removePrefix("v").removePrefix("V")
        return VERSION_PREFIX.find(trimmed)?.groupValues?.get(1) ?: ""
    }

    /**
     * 远端版本是否比本机新。
     *
     * 逐段按**整数**比较（`2.10.0` > `2.9.0`；若按字符串比会得出相反结论）。
     * 任一侧无法解析时返回 `false` —— 宁可不提示，也不要误报「有新版本」。
     */
    internal fun isNewer(remote: String, local: String): Boolean {
        val r = segments(remote) ?: return false
        val l = segments(local) ?: return false
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun segments(v: String): List<Int>? {
        val norm = normalizeVersion(v)
        if (norm.isEmpty()) return null
        val parts = norm.split(".")
        val out = ArrayList<Int>(parts.size)
        for (p in parts) out.add(p.toIntOrNull() ?: return null)
        return out
    }

    /**
     * 从资产名里挑出与本机 ABI 匹配的 APK。
     *
     * 按 [supportedAbis] 的**优先级**取第一个命中项（`Build.SUPPORTED_ABIS` 已按首选
     * 架构排好序），故 64 位设备会优先拿到 arm64 包，而不是让它退回 32 位。
     * 没有匹配项时返回 null —— 由调用方回退到「打开发布页」，让用户自己选。
     *
     * ⚠️ 匹配时**用分隔符卡住边界**（`-<abi>-`），而不是直接子串匹配 —— 这样 ABI 串
     * 出现在无关资产名（如 `my-arm64-v8a-notes.txt` 之类）里时不会误命中。
     *
     * 已知边界：仅报告 `armeabi` 的设备仍会命中 `app-armeabi-v7a-…` —— 因为 `armeabi`
     * 恰好是 `armeabi-v7a` 的前缀且后面紧跟连字符，**单靠文件名无法区分**。这不成问题：
     * 该类设备早于 minSdk 26，应用本来就装不上。故匹配顺序**必须**沿用
     * `Build.SUPPORTED_ABIS` 的「首选优先」，不要自行按长度重排（那会把 64 位设备
     * 误导向 32 位包）。
     */
    internal fun pickAsset(assetNames: List<String>, supportedAbis: List<String>): String? {
        val apks = assetNames.filter { it.endsWith(ASSET_SUFFIX, ignoreCase = true) }
        if (apks.isEmpty()) return null
        for (abi in supportedAbis) {
            if (abi.isBlank()) continue
            apks.firstOrNull { it.contains("-$abi-", ignoreCase = true) }?.let { return it }
        }
        return null
    }
}
