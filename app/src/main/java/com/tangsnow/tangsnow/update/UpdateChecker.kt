package com.tangsnow.tangsnow.update

import android.content.Context
import com.tangsnow.tangsnow.data.AppHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 应用内更新检查（脚手架就绪，更新源待接入）。
 *
 * 接入方式：把 [MANIFEST_URL] 换成一个返回如下 JSON 的 https 地址即可，无需改其它代码：
 *
 * ```json
 * {
 *   "versionCode": 3,
 *   "versionName": "1.2.0",
 *   "apkUrl": "https://example.com/tangsnow-1.2.0.apk",
 *   "notes": "本次更新内容…"
 * }
 * ```
 *
 *  - [MANIFEST_URL] 为空串时视为「尚未接入」：设置页会置灰「检查更新」入口并提示
 *    不可用，绝不假装“已是最新”（[isConfigured] 供界面判断）；
 *  - APK 下载交由系统浏览器 / 下载器处理（ACTION_VIEW），应用自身无需存储权限。
 */
object UpdateChecker {

    /** 空串 = 更新服务尚未接入（故意未启用，接入方式见类注释）；接入后填 https 清单地址。 */
    const val MANIFEST_URL: String = ""

    data class Current(val versionCode: Long, val versionName: String)

    data class Info(
        val versionCode: Long,
        val versionName: String,
        val apkUrl: String,
        val notes: String?,
    )

    fun current(context: Context): Current = try {
        val pm = context.applicationContext.packageManager
        val pkg = pm.getPackageInfo(context.applicationContext.packageName, 0)
        Current(
            versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) pkg.longVersionCode
            else @Suppress("DEPRECATION") pkg.versionCode.toLong(),
            versionName = pkg.versionName.orEmpty(),
        )
    } catch (e: Exception) {
        Current(0, "")
    }

    /**
     * 拉取更新清单。
     * @return 清单信息；未配置更新源或网络失败/非 https 时为 null
     *
     * 安全要求：更新源必须为 HTTPS 且由开发者控制；正式接入时建议配合
     * 强校验（如清单内嵌 SHA-256，下载后校验签名再安装），
     * 切勿静默更新或使用未加密/第三方更新源（防投毒）。
     */
    suspend fun fetchManifest(): Info? = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext null
        if (!MANIFEST_URL.startsWith("https://")) return@withContext null
        try {
            val req = AppHttp.get(MANIFEST_URL, acceptJson = true).build()
            AppHttp.client.newCall(req).execute().use { resp ->
                val body = resp.body.string()
                val json = JSONObject(body)
                Info(
                    versionCode = json.optLong("versionCode", 0),
                    versionName = json.optString("versionName", ""),
                    apkUrl = json.optString("apkUrl", ""),
                    notes = json.optString("notes", "").ifBlank { null },
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 取消要原样传播，不能降级成 null：「null = 未配置/网络失败」会让调用方
            // 把已取消的协程当成一次真实失败（例如弹出「检查更新失败」）。
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** 是否已配置更新源（清单地址非空） */
    fun isConfigured(): Boolean = MANIFEST_URL.isNotBlank()
}