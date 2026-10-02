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
 *  5. **条目惰性回收**：`track()` 每次登记前先摘掉所有 `!isShowing` 的条目 ⇒ 表恒等于
 *     「当前显示中」的集合，天然有界。为什么不在 dismiss 时挂钩回收：`OnDismissListener`
 *     不可链式（见第 2 点两条理由），而本类的收口（[cancelAll]）本就只需 isShowing 判定。
 *
 *
 * ## 用法
 * ```
 * private val dialogs = DialogTracker()          // Activity / Fragment 的字段
 * dialogs.track(AlertDialog.Builder(this)…create())   // 展示 + 纳管
 * dialogs.cancelAll()                            // onDestroy / onDestroyView 里调用
 * ```
 *
 * ## 范围（哪些对话框**不**纳管）
 *
 * 只纳管「会长时间驻留，或关闭时有应用语义要执行」的弹窗——扩展弹窗（关闭要
 * releaseSession）、协议全文兜底、清空确认、书签工具菜单、新增引擎/快捷方式等表单。
 * MainActivity 里的**短确认框**（退出确认、批量菜单、扫码结果）刻意**不**纳管：
 * 它们模态、用户点一下就消失，宿主销毁时框架强摘即可，纳管只会让本表无谓变长。
 * ⚠️ **两个下载确认不在此列**（2026-10-02 外部审查 P3-11 更正）：它们的取消语义是
 * 「关掉内核响应流」，而框架强摘**不触发**取消回调 ⇒ 不主动收口就没人关流。现由
 * MainActivity 的 `shownDownloadConfirms` 专门登记并在 onDestroy 分派（配置变更回队 /
 * 真销毁关流），不走本类。下轮复查若报「下载确认未收口」，先读这一段与那边的实现。
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
        // 惰性回收：每次登记前先摘掉已关闭的条目（N3）。
        // 此前只增不减 ⇒ 长寿命宿主（MainActivity 的扩展弹窗）每开一次就多一条**永久**登记，
        // 引用链 Dialog → popupView(GeckoView) → Activity 会一直拴到 onDestroy。
        // 为什么不做 OnDismissListener 挂钩：见类 KDoc 第 2 点的两条硬约束。
        tracked.removeAll { !it.isShowing }
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
