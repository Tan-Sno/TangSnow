package com.tangsnow.tangsnow.extension

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.tangsnow.tangsnow.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import com.tangsnow.tangsnow.browser.BrowserSessionManager
import com.tangsnow.tangsnow.browser.Tab
import com.tangsnow.tangsnow.data.repo.ApplicationScope
import com.tangsnow.tangsnow.data.PreferenceStore

/**
 * WebExtensionController.PromptDelegate 的共享实现：
 * 让 AMO 官方网页上的「添加到 Firefox」等安装/权限请求弹出确认框（默认实现会静默拒绝）。
 *
 * 由 ExtensionsActivity 与 MainActivity 各自创建实例并挂载到
 * runtime.webExtensionController 上（后到者覆盖先到者）；
 * 销毁时按 === 引用比对只解绑自己的实例，互不抢占。
 */
object ExtensionPrompts {

    /**
     * 展示中的扩展提示弹窗。Activity 销毁（切主题 / 切语言 / 进程回收）时必须关闭，
     * 否则会有两个后果：① 对应的 GeckoResult 永不完成 → 安装或权限流程**永久挂起**；
     * ② 对话框挂在已销毁的 Activity 上造成窗口泄漏（WindowLeaked）。
     *
     * 关闭一律走 `Dialog.cancel()`：它会触发 OnCancelListener，而那里正是
     * `once.complete(拒绝)`，因此应答恰好完成一次、窗口同时被移除。
     */
    /**
     * 展示中的提示弹窗，连带记录其归属 Activity。
     *
     * 记录归属是必要的：本对象是**进程级**的，MainActivity 与 ExtensionsActivity 共用
     * 同一份列表。若注销时不按归属过滤，MainActivity 被销毁（切主题 / 切语言 / 内存回收）
     * 就会把 ExtensionsActivity 正在展示的安装或权限确认框一并取消，等于把一个用户
     * 从未表达过的「拒绝」塞给内核 —— 安装流程会因此莫名失败。
     */
    private class Owned(val owner: AppCompatActivity, val dialog: AlertDialog)

    private val openDialogs = mutableListOf<Owned>()

    /**
     * 由宿主 Activity 在 onDestroy 中调用：关闭并应答**属于该 Activity** 的提示。
     * [owner] 传 null 表示关闭全部（仅供进程级收尾使用）。
     */
    fun cancelAllPending(owner: AppCompatActivity? = null) {
        val snapshot = openDialogs.toList()
        snapshot.forEach { item ->
            if (owner == null || item.owner === owner) runCatching { item.dialog.cancel() }
        }
        openDialogs.removeAll { item -> owner == null || item.owner === owner }
    }

    /** 展示弹窗并登记，关闭后自动摘除 */
    private fun track(activity: AppCompatActivity, dialog: AlertDialog): AlertDialog {
        val owned = Owned(activity, dialog)
        openDialogs += owned
        dialog.setOnDismissListener { openDialogs.remove(owned) }
        dialog.show()
        return dialog
    }

    /**
     * 一次性应答闸：用户点击与"销毁兜底取消"可能竞争同一次提示，
     * 而 GeckoResult 只允许完成一次，故统一经此收口。
     */
    private class Once<T>(private val result: GeckoResult<T>) {
        private val done = java.util.concurrent.atomic.AtomicBoolean(false)
        fun complete(value: T) {
            if (done.compareAndSet(false, true)) result.complete(value)
        }
    }

    fun createDelegate(activity: AppCompatActivity): WebExtensionController.PromptDelegate =
        object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(
                extension: WebExtension,
                permissions: Array<String>,
                origins: Array<String>,
                dataCollection: Array<String>,
            ): GeckoResult<WebExtension.PermissionPromptResponse>? {
                val result = GeckoResult<WebExtension.PermissionPromptResponse>()
                val once = Once(result)
                val denied = WebExtension.PermissionPromptResponse(false, false, false)
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) {
                        once.complete(denied)
                        return@runOnUiThread
                    }
                    track(
                        activity,
                        AlertDialog.Builder(activity)
                            .setTitle(
                                activity.getString(
                                    R.string.extension_install_confirm,
                                    extension.metaData.name.orEmpty().ifBlank { extension.id },
                                )
                            )
                            .setMessage(permissionSummary(activity, permissions, origins))
                            .setNegativeButton(R.string.dlg_cancel) { _, _ ->
                                once.complete(denied)
                            }
                            .setPositiveButton(R.string.dlg_ok) { _, _ ->
                                once.complete(WebExtension.PermissionPromptResponse(true, false, false))
                            }
                            .setOnCancelListener {
                                once.complete(denied)
                            }
                            .create()
                    )
                }
                return result
            }

            override fun onUpdatePrompt(
                extension: WebExtension,
                permissions: Array<String>,
                origins: Array<String>,
                dataCollection: Array<String>,
            ): GeckoResult<AllowOrDeny>? {
                val result = GeckoResult<AllowOrDeny>()
                val once = Once(result)
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) {
                        once.complete(AllowOrDeny.DENY)
                        return@runOnUiThread
                    }
                    track(
                        activity,
                        AlertDialog.Builder(activity)
                            .setTitle(R.string.extension_update_confirm)
                            .setMessage(
                                activity.getString(
                                    R.string.extension_update_confirm_message,
                                    extension.metaData.name.orEmpty().ifBlank { extension.id },
                                    extension.metaData.version,
                                )
                            )
                            .setNegativeButton(R.string.dlg_cancel) { _, _ ->
                                once.complete(AllowOrDeny.DENY)
                            }
                            .setPositiveButton(R.string.dlg_ok) { _, _ ->
                                once.complete(AllowOrDeny.ALLOW)
                            }
                            // 此前缺失取消监听：用户点提示外部 / 按返回键离开时，
                            // 结果永不完成，扩展更新流程会一直卡住
                            .setOnCancelListener { once.complete(AllowOrDeny.DENY) }
                            .create()
                    )
                }
                return result
            }

            override fun onOptionalPrompt(
                extension: WebExtension,
                permissions: Array<String>,
                origins: Array<String>,
                dataCollection: Array<String>,
            ): GeckoResult<AllowOrDeny>? {
                val result = GeckoResult<AllowOrDeny>()
                val once = Once(result)
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) {
                        once.complete(AllowOrDeny.DENY)
                        return@runOnUiThread
                    }
                    track(
                        activity,
                        AlertDialog.Builder(activity)
                            .setTitle(R.string.extension_optional_confirm)
                            .setMessage(
                                activity.getString(
                                    R.string.extension_optional_confirm_message,
                                    extension.metaData.name.orEmpty().ifBlank { extension.id },
                                ) + "\n\n" + permissionSummary(activity, permissions, origins, dataCollection)
                            )
                            .setNegativeButton(R.string.dlg_cancel) { _, _ ->
                                once.complete(AllowOrDeny.DENY)
                            }
                            .setPositiveButton(R.string.dlg_ok) { _, _ ->
                                once.complete(AllowOrDeny.ALLOW)
                            }
                            // 同 onUpdatePrompt：补上缺失的取消监听，避免可选权限流程卡死
                            .setOnCancelListener { once.complete(AllowOrDeny.DENY) }
                            .create()
                    )
                }
                return result
            }
        }

    /**
     * 把扩展申请的技术权限翻译成用户能读懂的话，并以编号排列（未知项保留原名兜底）。
     * 来源按顺序合并去重：申请权限 + 数据用途说明 + 站点访问（origins）。
     */
    private fun permissionSummary(
        activity: AppCompatActivity,
        permissions: Array<String>,
        origins: Array<String>,
        dataCollection: Array<String> = emptyArray(),
    ): String {
        val apiNames = (permissions.toList() + dataCollection.toList())
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val knownPerms = apiPermissionMap(activity)
        val permLines = apiNames.map { name -> knownPerms[name] ?: name }
        val hostLines = origins
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .map { origin -> hostLine(activity, origin) }

        val lines = (permLines + hostLines)
        if (lines.isEmpty()) {
            return activity.getString(R.string.extension_no_permissions)
        }
        val sb = StringBuilder(activity.getString(R.string.extension_permissions_intro)).append('\n')
        val show = lines.take(MAX_PERM_LINES)
        show.forEachIndexed { i, text ->
            sb.append(i + 1).append(". ").append(text).append('\n')
        }
        if (lines.size > show.size) {
            sb.append('…').append(
                activity.getString(R.string.extension_permissions_more, lines.size - show.size)
            )
        } else {
            sb.setLength(sb.length - 1) // 去掉末尾多余换行
        }
        return sb.toString()
    }

    private fun hostLine(activity: AppCompatActivity, origin: String): String {
        val pathless = origin.substringAfter("://", origin).substringBefore('/')
        if (pathless == "*" || pathless == "*.*") {
            return activity.getString(R.string.extension_perm_host_any)
        }
        val host = pathless.removePrefix("*.").removeSuffix("*").trim()
        return activity.getString(
            R.string.extension_perm_host,
            host.ifBlank { origin }
        )
    }

    /**
     * 常见 WebExtension 权限的技术名 → 人话；未收录项保留原名展示。
     *
     * 文案放在 `arrays.xml` 的 `extension_api_permissions`（`api|说明` 成对，中英各一份），
     * 而不是写死在 Kotlin 里 —— 否则这些说明在英文版会永久缺失。
     * 每次调用重建映射（35 条，开销可忽略），以免缓存到进程里后跨语言切换失效。
     */
    private fun apiPermissionMap(activity: AppCompatActivity): Map<String, String> =
        activity.resources.getStringArray(R.array.extension_api_permissions)
            .mapNotNull { entry ->
                val sep = entry.indexOf('|')
                if (sep <= 0) null else entry.substring(0, sep) to entry.substring(sep + 1)
            }
            .toMap()

    private const val MAX_PERM_LINES = 12

    // ------------------------------------------------------------- 扩展 Action / Tab 委托

    /**
     * 扩展 UI 宿主：扩展工具栏按钮弹窗、tabs.create、打开选项页 都需要落到具体 Activity 上。
     * 由前台 Activity（本项目为 MainActivity）在 onResume 注入、onDestroy 清空；
     * 委托实现只持有本接口引用，因此始终作用在当前前台界面，不会泄露已销毁的 Activity。
     */
    interface ExtensionUi {
        /** 把一个弹窗会话挂到可见的 GeckoView 上（会话由内核 open，这里只负责展示） */
        fun showPopup(session: GeckoSession)

        /**
         * 让前台主界面切到给定标签并展示，返回其会话（供 tabs.create 回交内核）。
         * 返回 null 表示前台无主界面可承载：内核仍会按返回的会话加载，只是暂不显示。
         */
        fun focusExtensionTab(tab: Tab): GeckoSession?

        /** 打开扩展选项页（options_ui） */
        fun openOptionsPage(url: String?)
    }

    /** 当前扩展 UI 宿主（前台 Activity）；无前台界面时为 null */
    @Volatile
    var popupHost: ExtensionUi? = null

    /** 扩展 Tab 委托里创建标签走 IO 线程（需要等会话管理器与数据库），结果经 GeckoResult 回交 */
    private val extScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 共享的扩展 Action 委托工厂。
     * 解决“装扩展后工具栏按钮/面板点击完全无响应”：实现 onBrowserAction/onPageAction
     * （action.click() 触发扩展逻辑）与 onTogglePopup/onOpenPopup（弹窗）。
     * UI 行为集中在此，避免每个 Activity 各写一份；弹窗展示交给 [popupHost]。
     */
    fun createActionDelegate(): WebExtension.ActionDelegate =
        object : WebExtension.ActionDelegate {
            override fun onBrowserAction(
                extension: WebExtension,
                session: GeckoSession?,
                action: WebExtension.Action,
            ) {
                // 无弹窗的浏览器动作：点击即触发扩展逻辑（默认实现不动作，会导致“点了没反应”）
                action.click()
            }

            override fun onPageAction(
                extension: WebExtension,
                session: GeckoSession?,
                action: WebExtension.Action,
            ) {
                action.click()
            }

            override fun onTogglePopup(
                extension: WebExtension,
                action: WebExtension.Action,
            ): GeckoResult<GeckoSession> = openPopupSession(action)

            override fun onOpenPopup(
                extension: WebExtension,
                action: WebExtension.Action,
            ): GeckoResult<GeckoSession> = openPopupSession(action)
        }

    /**
     * 共享的扩展 Tab 委托工厂：让扩展 tabs.create 与“打开选项页”可用。
     */
    fun createTabDelegate(): WebExtension.TabDelegate =
        object : WebExtension.TabDelegate {
            override fun onNewTab(
                extension: WebExtension,
                details: WebExtension.CreateTabDetails,
            ): GeckoResult<GeckoSession> {
                val result = GeckoResult<GeckoSession>()
                extScope.launch {
                    runCatching {
                        val sm = BrowserSessionManager.get(
                            ApplicationScope.context, PreferenceStore(ApplicationScope.context)
                        )
                        // CreateTabDetails 无“无痕”字段，统一普通标签（与扩展管理页口径一致）
                        val tab = sm.newTab(isPrivate = false)
                        val url = details.url
                        if (!url.isNullOrBlank()) tab.session.loadUri(url)
                        sm.switchTo(tab)
                        // 让前台主界面切到该标签并展示（best-effort；无前台界面则暂不显示）
                        val shown = popupHost?.focusExtensionTab(tab)
                        result.complete(shown ?: tab.session)
                    }.onFailure { result.completeExceptionally(it) }
                }
                return result
            }

            override fun onOpenOptionsPage(extension: WebExtension) {
                val url = extension.metaData.optionsPageUrl
                if (!url.isNullOrBlank()) popupHost?.openOptionsPage(url)
            }
        }

    /**
     * 创建【未 open】的弹窗会话并返回：内核在 onOpenPopup 回调收到该会话后，会自行调用
     * 包级私有的 action.openPopup(...) 完成 setExtensionPopup 标记并 loadUri(弹窗地址)。
     * 因此这里**绝不能**自己 open 或调用 openPopup（二者包级私有，且先 open 会触发内核断言
     * “Must use an unopened GeckoSession instance”崩溃）。showPopup 仅负责把会话挂到可见 GeckoView。
     */
    private fun openPopupSession(action: WebExtension.Action): GeckoResult<GeckoSession> {
        val session = GeckoSession(GeckoSessionSettings.Builder().build())
        popupHost?.showPopup(session)
        return GeckoResult.fromValue(session)
    }

    // ------------------------------------------------------------- Action / Tab 委托挂载

    /**
     * 共享的 Action/Tab 委托单例：两个工厂都只捕获进程级 [popupHost]，不持有任何 Activity，
     * 故全扩展共用同一实例即可；挂载时按引用比对解绑也不会误伤其他来源设置的委托。
     */
    @Volatile
    private var actionDelegateSingleton: WebExtension.ActionDelegate? = null

    @Volatile
    private var tabDelegateSingleton: WebExtension.TabDelegate? = null

    private fun actionDelegate(): WebExtension.ActionDelegate =
        actionDelegateSingleton ?: createActionDelegate().also { actionDelegateSingleton = it }

    private fun tabDelegate(): WebExtension.TabDelegate =
        tabDelegateSingleton ?: createTabDelegate().also { tabDelegateSingleton = it }

    /**
     * 把 Action/Tab 委托挂到当前所有已安装扩展上。
     *
     * 为什么遍历列表：setActionDelegate/setTabDelegate 在 **WebExtension 实例**上（而非
     * WebExtensionController），所以必须对每个扩展单独挂载。
     *
     * 幂等：重复挂载只是覆盖委托，不会重复注册回调。
     *
     * 为什么持有方用「集合」而不是计数：[owner] 是持有方自身（Activity），而 onResume 在
     * 一个 Activity 生命周期内会触发多次、onDestroy 只触发一次 —— 用自增计数会只增不减、
     * 永远回不到 0，导致委托**再也无法解绑**（进程级 controller 会一直持有已销毁的宿主）。
     * 集合对同一持有方的重复挂载天然幂等，且只有**最后一个持有方退出**时才真正解绑，
     * 避免某一个 Activity 销毁时把另一个仍存活 Activity 的委托一并摘掉。
     */
    fun mountExtensionDelegates(controller: WebExtensionController, owner: Any) {
        val ad = actionDelegate()
        val td = tabDelegate()
        delegateOwners.add(owner)
        controller.list().accept(
            { exts -> exts?.forEach { ext -> ext.setActionDelegate(ad); ext.setTabDelegate(td) } },
            { _ -> }
        )
    }

    fun unmountExtensionDelegates(controller: WebExtensionController, owner: Any) {
        // remove 自带「是否原本就存在」的原子判断：非持有方或已解绑过都会被忽略，不会误减
        if (!delegateOwners.remove(owner)) return
        if (delegateOwners.isNotEmpty()) return
        controller.list().accept(
            { exts ->
                // 解绑期间若又有前台 Activity 重新挂载，则不清除，避免误摘新挂载方委托
                if (delegateOwners.isNotEmpty()) return@accept
                exts?.forEach { ext ->
                    // 按引用比对：仅当仍是本进程设置的同一 Tab 委托才置空（Action 无 getter，
                    // 但委托为进程级单例，直接置空无副作用）
                    if (ext.getTabDelegate() === tabDelegateSingleton) ext.setTabDelegate(null)
                    ext.setActionDelegate(null)
                }
            },
            { _ -> }
        )
    }

    /** 已挂载 Action/Tab 委托的持有方集合（Activity 自身）；集合清空才真正解绑 */
    private val delegateOwners: MutableSet<Any> =
        Collections.newSetFromMap(ConcurrentHashMap<Any, Boolean>())
}
