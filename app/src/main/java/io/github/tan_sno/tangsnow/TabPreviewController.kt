package io.github.tan_sno.tangsnow

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.core.view.isVisible
import org.mozilla.geckoview.GeckoDisplay
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 标签页缩略图捕获控制器。
 * 从 MainActivity 抽出：抓取当前活动标签的内核截图并缩成小图缓存（供标签切换页展示）。
 * 仅当 GeckoView 可见且有页面时执行；截图完成后立即释放 display，防止句柄泄漏。
 */
class TabPreviewController(private val activity: MainActivity) {

    /**
     * 抓取当前活动标签页的内核截图并缩成小图缓存。
     * capturePixels 为空或失败时回退 display.screenshot()。
     */
    fun captureCurrentPreview() {
        // 生命周期安全：Activity 正在销毁/已完成时不做任何视图与内核操作
        if (activity.isFinishing || activity.isDestroyed) return
        if (activity.homeVisible || !activity.binding.geckoView.isVisible) return
        val tab = activity.sessionManager.activeTab ?: return
        // 无痕标签不做缩略图：截图会在内存留存无痕内容，与无痕承诺（不留痕迹）不一致
        if (tab.isPrivate) return
        val url = tab.url ?: return
        if (url.isBlank() || url.startsWith("about:")) return

        val display = try {
            tab.session.acquireDisplay()
        } catch (_: Throwable) {
            null
        } ?: return

        val released = AtomicBoolean(false)
        fun releaseOnce() {
            if (released.compareAndSet(false, true)) {
                runCatching { tab.session.releaseDisplay(display) }
            }
        }
        fun onBitmap(bmp: Bitmap?) {
            releaseOnce()
            if (bmp != null && bmp.width > 0 && !activity.isDestroyed) {
                activity.runOnUiThread {
                    if (!activity.isDestroyed) {
                        tab.preview = scaleDownPreview(bmp)
                        activity.tabsAdapter.updateTab(tab)
                    }
                }
            }
        }
        // 兜底：若 GeckoResult 迟迟不回调，8 秒后强制释放 display，防止句柄泄漏
        val watchdog = Runnable { releaseOnce() }
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed(watchdog, 8_000L)
        try {
            display.capturePixels().accept(
                { bmp ->
                    handler.removeCallbacks(watchdog)
                    if (bmp != null) onBitmap(bmp) else tryScreenshotBuilder(display, ::onBitmap)
                },
                { _ ->
                    handler.removeCallbacks(watchdog)
                    tryScreenshotBuilder(display, ::onBitmap)
                }
            )
        } catch (_: Throwable) {
            handler.removeCallbacks(watchdog)
            tryScreenshotBuilder(display, ::onBitmap)
        }
    }

    /** 通过 GeckoDisplay.screenshot 显式构造器获取位图，作为 capturePixels 失败的兜底 */
    private fun tryScreenshotBuilder(
        display: GeckoDisplay,
        onBitmap: (Bitmap?) -> Unit,
    ) {
        try {
            display.screenshot().capture().accept(
                { bmp -> onBitmap(bmp) },
                { _ -> onBitmap(null) }
            )
        } catch (_: Throwable) {
            onBitmap(null)
        }
    }

    /**
     * 预览只保留小尺寸，避免多标签页占用大量内存。
     *
     * 注意：这里**不回收**内核返回的源位图。`capturePixels()` 交出的 Bitmap 生命周期
     * 归属 Gecko 侧，回收它有与合成器共享实例而崩溃的风险；只丢引用、交给 GC 即可。
     */
    private fun scaleDownPreview(src: Bitmap): Bitmap {
        val maxWidth = 420
        if (src.width <= maxWidth) return src
        val height = (src.height.toLong() * maxWidth / src.width).toInt().coerceAtLeast(1)
        return runCatching {
            Bitmap.createScaledBitmap(src, maxWidth, height, true)
        }.getOrNull() ?: src
    }
}
