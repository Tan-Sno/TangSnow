package io.github.tan_sno.tangsnow

import android.graphics.Bitmap
import androidx.core.view.isVisible
import io.github.tan_sno.tangsnow.browser.Tab

/**
 * 标签页缩略图捕获控制器。
 * 抓取当前活动标签的截图并缩成小图缓存（供标签切换页展示）；仅当 GeckoView 可见且有页面时执行。
 *
 * ## 为什么走 `GeckoView.capturePixels()` 而不是 `session.acquireDisplay()`
 *
 * `GeckoView.setSession()` **内部就已经 acquire 了该会话的 display**（GeckoView 157 字节码实测：
 * `setSession` → `invokevirtual GeckoSession.acquireDisplay()`），而 `GeckoSession.acquireDisplay()`
 * 的第一条指令是 `assertOnUiThread()`、紧接着 `if (mDisplay != null) throw
 * IllegalStateException("Display already acquired")`。
 *
 * 于是对「正挂在 GeckoView 上的活动标签」再 acquire 一次**必然抛**；原实现把这一步包在
 * `catch (_: Throwable)` 里直接 return，于是三个触发点全是死路 —— **缩略图从来没生成过**。
 *
 * 改用视图自己的 `capturePixels()`：它复用的正是已经持有的那个 display。连带好处是不再需要
 * acquire/release 配对，也就不需要那套 8 秒看门狗（看门狗的存在理由恰恰是"怕 display 漏释放"）。
 */
class TabPreviewController(private val activity: MainActivity) {

    /** 抓取当前活动标签页的截图并缩成小图缓存 */
    fun captureCurrentPreview() {
        // 生命周期安全：Activity 正在销毁/已完成时不做任何视图与内核操作
        if (activity.isFinishing || activity.isDestroyed) return
        val view = activity.binding.geckoView
        if (activity.homeVisible || !view.isVisible) return
        val tab = activity.sessionManager.activeTab ?: return
        // 无痕标签不做缩略图：截图会在内存留存无痕内容，与无痕承诺（不留痕迹）不一致
        if (tab.isPrivate) return
        val url = tab.url ?: return
        if (url.isBlank() || url.startsWith("about:")) return
        // 只截「视图此刻正在显示的那个会话」：视图还挂在别的标签上时（切换中途）截出来是别人的画面
        if (view.session !== tab.session) return

        val shot = try {
            view.capturePixels()
        } catch (_: Throwable) {
            return
        }
        shot.accept(
            { bmp -> if (bmp != null) onBitmap(tab, bmp) },
            // 取不到就算了：缩略图是可选装饰，不该有任何可见反馈，也不该影响任何主流程
            { _ -> },
        )
    }

    private fun onBitmap(tab: Tab, bmp: Bitmap) {
        if (bmp.width <= 0 || activity.isDestroyed) return
        // 回调线程不由我们决定 ⇒ 写字段与刷新适配器都回 UI 线程
        activity.runOnUiThread {
            if (!activity.isDestroyed) {
                tab.preview = scaleDownPreview(bmp)
                activity.tabsAdapter.updateTab(tab)
            }
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
