package io.github.tan_sno.tangsnow.data

import io.github.tan_sno.tangsnow.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 应用统一网络栈：单例 OkHttpClient（连接池 + HTTP/2 + gzip）。
 * UA 包含版本号便于上游过滤合法客户端；超时配置在体验与宽容之间取平衡。
 */
object AppHttp {

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * UA：包含版本号便于上游按已知客户端过滤；与浏览器指纹一致降低被识别为脚本的风险。
     */
    val userAgent: String =
        "Mozilla/5.0 (Android; TangSnow) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/130.0 Mobile Safari/537.36 TangSnow/" + BuildConfig.VERSION_NAME

    /**
     * `Accept-Language`：**按设备语言动态生成**，不再写死一串。
     *
     * 为什么改：写死 `zh-CN,zh;q=0.9,en;q=0.8` 有两个问题 —— 一是所有用户的请求头完全一致，
     * 本身就是一个稳定指纹；二是非中文设备也会被当成中文客户端，与浏览器本体「UA / 语言跟随
     * 设备」的做法自相矛盾。这里取设备语言列表逐个降 q，末尾补 `en` 兜底
     * （AMO 与 GitHub 的元数据以英文最全）。
     *
     * 用 [android.os.LocaleList.getDefault]（API 24 起，本工程 minSdk 26）而不是 Context：
     * 本对象是纯单例、不持有 Context，也就没有「持有谁 / 会不会泄漏」的问题。
     * ⚠️ 必须是 `android.os.LocaleList`，**不是** `java.util.LocaleList` —— 后者只存在于 AOSP
     * 源码里，公开 SDK 的 android.jar 中没有它，写了会直接编译不过（实测 `Unresolved reference`）。
     * 结果只算一次：进程存活期内语言变化属系统级事件，不值得为它引入监听。
     */
    val acceptLanguage: String by lazy {
        val out = ArrayList<String>(4)
        val seen = HashSet<String>()
        fun add(tag: String) {
            if (tag.isBlank()) return
            if (seen.add(tag.lowercase())) out.add(tag)
        }
        val locales = android.os.LocaleList.getDefault()
        for (i in 0 until locales.size()) {
            val locale = locales.get(i)
            if (locale.language.isBlank()) continue
            // 先给带地区的完整标签（zh-CN），再给基础语言（zh）：服务端的匹配粒度并不统一
            add(locale.toLanguageTag())
            add(locale.language)
        }
        add("en")
        val q = arrayOf("", ";q=0.9", ";q=0.8", ";q=0.7", ";q=0.6", ";q=0.5")
        out.take(q.size).mapIndexed { i, tag -> tag + q[i] }.joinToString(",")
    }

    /** 构造带 UA 的 GET 请求；acceptJson = true 时追加 Accept: application/json。
     *  注意：不要手动设 Accept-Encoding —— 手动设置会关掉 OkHttp 的透明 gzip，
     *  服务器若返回压缩数据将拿到原始 gzip 字节（JSON 解析失败 / xpi 校验失败）。 */
    fun get(url: String, acceptJson: Boolean = false): Request.Builder {
        val b = Request.Builder().url(url)
            .header("User-Agent", userAgent)
            .header("Accept-Language", acceptLanguage)
        if (acceptJson) b.header("Accept", "application/json")
        return b
    }
}