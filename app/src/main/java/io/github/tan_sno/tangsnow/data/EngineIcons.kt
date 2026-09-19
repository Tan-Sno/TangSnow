package io.github.tan_sno.tangsnow.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.tan_sno.tangsnow.R
import java.util.concurrent.ConcurrentHashMap

/**
 * 搜索引擎快捷键图标：
 *  - 全部内置引擎使用 APK 内嵌的官方 PNG（drawable-nodpi），无网络依赖、瞬时呈现；
 *  - 自定义引擎与「无官方图标」的引擎（Google/DuckDuckGo 在国内不可达，且第三方 favicon 多为 .ico，
 *    Android 系统无法解码）保持放大镜兜底；
 *  - 内存缓存仅缓存已解码的 Bitmap，进程销毁即释放，无磁盘缓存浪费。
 */
object EngineIcons {

    /** id → 内嵌官方图标资源（值均为 R.drawable 资源 id）。bing 与 bing_cn 共用 Bing 全球品牌图标。 */
    private val embedded: Map<String, Int> = mapOf(
        "bing" to R.drawable.ic_engine_bing,
        "bing_cn" to R.drawable.ic_engine_bing,
        "baidu" to R.drawable.ic_engine_baidu,
        "sogou" to R.drawable.ic_engine_sogou,
        "so360" to R.drawable.ic_engine_360,
        "sm" to R.drawable.ic_engine_shenma,
    )

    private val decoded = ConcurrentHashMap<String, Bitmap>()

    /** 同步返回已解码位图；无内嵌资源或解码失败返回 null。 */
    fun get(context: Context, engine: SearchEngine): Bitmap? {
        val res = embedded[engine.id] ?: return null
        decoded[engine.id]?.let { return it }
        // 解码失败时保持 null 兜底，绝不能把 null put 进 ConcurrentHashMap（会 NPE）
        val bmp = runCatching {
            BitmapFactory.decodeResource(context.resources, res)
        }.getOrNull() ?: return null
        decoded[engine.id] = bmp
        return bmp
    }
}