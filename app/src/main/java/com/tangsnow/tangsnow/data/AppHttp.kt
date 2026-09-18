package com.tangsnow.tangsnow.data

import com.tangsnow.tangsnow.BuildConfig
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

    /** 构造带 UA 的 GET 请求；acceptJson = true 时追加 Accept: application/json。
     *  注意：不要手动设 Accept-Encoding —— 手动设置会关掉 OkHttp 的透明 gzip，
     *  服务器若返回压缩数据将拿到原始 gzip 字节（JSON 解析失败 / xpi 校验失败）。 */
    fun get(url: String, acceptJson: Boolean = false): Request.Builder {
        val b = Request.Builder().url(url)
            .header("User-Agent", userAgent)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        if (acceptJson) b.header("Accept", "application/json")
        return b
    }
}