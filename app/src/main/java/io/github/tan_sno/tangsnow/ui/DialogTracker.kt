package io.github.tan_sno.tangsnow.ui

import android.app.Dialog

/**
 * 对话框纳管器：把宿主（Activity / Fragment）当前展示中的对话框登记起来，
 * 在**宿主销毁之前**统一收掉。
 *
 * ## 为什么必须有
 * 未在宿主销毁前 dismiss 的对话框，框架会打印 `WindowLeaked` 并**强摘窗口**：
 * `ActivityThread` 销毁路径调用 `WindowManagerGlobal.closeAll(token, …)`，
 * 后者对 token 匹配的每个 root 打印 `WindowLeaked` 再 `removeViewLocked`
 * （SDK 37.2 源码实测）。而 `OnDismissListener` **只由 `Dialog.dismissDialog()` →
 * `sendDismissMessage()` 触发** —— 框架直接摘窗**不走**它，于是应用自己的收尾
 * （最典型的是 `MainActivity.showPopup` 里关闭时要做的 `releaseSession()`）**永远不执行**，
 * 弹窗会话就这样泄漏。
 *
 * ## 设计（四处都是刻意的）
 *  1. **每宿主一个实例**，不做进程级单例：静态列表会强引用已销毁的宿主，正是本仓库一直在防的
 *     形态（见 `ExtensionPrompts.Owned` 的注释）。宿主自己持有，天然不需要 owner 过滤。
 *  2. **不注册 OnDismissListener / OnCancelListener**：调用方自己的监听器（例如关闭时
 *     `releaseSession()`、取消时给内核回一个"拒绝"）必须**原样保留**，本类若再设一次就是
 *     与调用方抢同一个回调。本类的收口不依赖它 —— [cancelAll] 用 `isShowing()` 判断即可。
 *  3. [cancelAll] 用 `cancel()` 而不是 `dismiss()`：`Dialog.cancel()` 等价于 `dismiss()`
 *     **外加**触发 `OnCancelListener`（有些对话框靠它在销毁时应答内核回调，
 *     见 `ExtensionPrompts` 的 `once.complete(denied)`），对纯对话框只是无害空转。
 *     而且它走的是 `dismissDialog()`，所以调用方的 `OnDismissListener` 同样会被触发 ——
 *     这正是"框架摘窗漏事、我们主动 cancel 补上"的关键差别。
 * 4. **仅主线程访问**（与 `WebPrompts` 的 `openDialogs` 同口径）：登记与收口都发生在生命周期回调里。
 *
 * ## 用法
 * ```
 * private val dialogs = DialogTracker()          // Activity / Fragment 的字段
 * dialogs.track(AlertDialog.Builder(this)…create())   // 展示 + 纳管
 * dialogs.cancelAll()                            // onDestroy / onDestroyView 里调用
 * ```
 */
class DialogTracker {

    private val tracked = ArrayList<Dialog>()

    /**
     * 展示 [dialog] 并纳管（返回同一个对象，便于调用方继续持有它做后续更新）。
     *
     * 本类**不碰**对话框的监听器，故调用方此前设好的 `setOnDismissListener` /
     * `setOnCancelListener` / 按钮监听全部保持有效。
     */
    fun track(dialog: Dialog): Dialog {
        tracked += dialog
        dialog.show()
        return dialog
    }

    /**
     * 收掉本宿主登记过的、**仍显示中**的对话框（宿主销毁前调用）。
     *
     * 已自行关闭过的条目会被 `isShowing()` 跳过 —— 否则会对一个已 dismiss 的对话框再走一次
     * `cancel()`，把调用方的 `OnCancelListener` 重复触发一遍。
     */
    fun cancelAll() {
        // 先快照再清空：cancel() 会同步跑调用方的监听器，期间不应再动本表
        val snapshot = tracked.toList()
        tracked.clear()
        snapshot.forEach { runCatching { if (it.isShowing) it.cancel() } }
    }
}
