package io.github.tan_sno.tangsnow.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.tan_sno.tangsnow.BuildConfig
import io.github.tan_sno.tangsnow.GeckoHolder
import io.github.tan_sno.tangsnow.data.PreferenceStore
import io.github.tan_sno.tangsnow.data.SessionStore
import io.github.tan_sno.tangsnow.data.repo.HistoryRepo
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 一个标签页：id + 独立 [GeckoSession] + 展示信息。 */
class Tab(val id: Int, val session: GeckoSession, val isPrivate: Boolean) {
    var title: String = ""
    var url: String? = null

    /** 页面是否处于视频/图片全屏（决定是否隐藏浏览器框架与系统栏） */
    var fullScreen: Boolean = false

    /** 是否可以后退（由 NavigationDelegate.onCanGoBack 维护） */
    var canGoBack: Boolean = false

    /** 是否可以前进（由 NavigationDelegate.onCanGoForward 维护） */
    var canGoForward: Boolean = false

    /** 标签页缩略图（内核截图，仅保留小图）；null = 尚未截取 */
    @Volatile
    var preview: android.graphics.Bitmap? = null

    /**
     * 页面是否有媒体在播放（由 MediaSession delegate 维护）。
     * 供「切后台自动画中画」判断依据；无痕标签不触发画中画（内容不进悬浮窗）。
     */
    @Volatile
    var mediaPlaying: Boolean = false
}

/**
 * 标签页事件（均在主线程派发）。
 * 由界面层订阅；Activity 重建时重新绑定，避免回调持有已销毁的 Activity。
 */
interface TabEvents {
    fun onTitleChanged(tab: Tab, title: String)
    fun onLocationChanged(tab: Tab, url: String?, isReload: Boolean)
    fun onFullScreen(tab: Tab, full: Boolean)
    fun onPageStart(tab: Tab)
    fun onPageProgress(tab: Tab, progress: Int)
    fun onPageStop(tab: Tab)
    /**
     * 连接安全状态变化（内核 ProgressDelegate.onSecurityChange）。
     *
     * 为什么必须接：本应用**刻意关闭了内核的「安全浏览」远程查询**（见隐私政策第 4 条），
     * 于是用户失去了「恶意站点拦截」这层保护。那么「一眼看出当前连接是否加密」就成了
     * 最重要的补偿手段 —— 它完全在本机完成、不产生任何网络请求，也不影响隐私承诺。
     *
     * @param secure 内核判定该页面是否为安全上下文（有效证书的 HTTPS）
     * @param mixedActiveLoaded 页面上**已加载**（而非被阻断）的主动混合内容；为 true 时
     *        即便 [secure] 为 true 也应提示风险（外壳加密但内容被中间人替换）
     */
    fun onSecurityChange(tab: Tab, secure: Boolean, mixedActiveLoaded: Boolean)
    fun onExternalResponse(tab: Tab, response: WebResponse)
    /**
     * window.open / target=_blank：不新开标签，改为在当前标签页加载目标 URL
     * （对齐移动端浏览器惯例，避免桌面 UA 下「点链接跳回主页、视频跑到新标签」的观感）。
     */
    fun onOpenInCurrentTab(uri: String)
    /** window.close()：页面请求关闭自己所在标签 */
    fun onCloseRequest(tab: Tab)
    /**
     * 前进/后退可用性变化（内核 onCanGoBack / onCanGoForward 回调）。
     * 后退状态由系统返回键直接读取 [Tab.canGoBack]，前进状态则由界面上的
     * 前进按钮读取 [Tab.canGoForward]——若只写字段不通知，按钮点亮/置灰会滞后。
     */
    fun onNavStateChanged(tab: Tab)
    /**
     * 标签页的媒体播放状态变化（内核 MediaSession 回调）。
     * 界面层据此更新画中画参数：Android 12+ 需要在**媒体开始播放时**就
     * `setAutoEnterEnabled(true)`，系统才能在用户返回桌面时接管转场；
     * 若等 `onUserLeaveHint` 再设置就已经晚了。
     */
    fun onMediaStateChanged(tab: Tab, playing: Boolean)

    /**
     * 内容进程崩溃（GeckoSession.ContentDelegate.onCrash）。
     * 内核会自动恢复会话，这里仅用于通知界面层给出可见提示并尝试重载当前页。
     */
    fun onCrash(tab: Tab)

    /**
     * 遇到非 http(s) 的“外部协议”跳转（如 intent://、market://、mailto:、tel:、geo:），
     * 由界面层用系统 Intent 尝试打开；内核侧的本次导航已被本管理器拒绝，避免页面被带走。
     */
    fun onExternalProtocol(url: String)
}

/**
 * 网页弹窗类交互（alert/confirm/prompt/选择列表/文件选择/HTTP 认证/离开确认），
 * 由界面层实现并注入。所有回调都在主线程触发；done 必须恰好调用一次，
 * null 表示用户取消。未注入时一切按「取消」处理（与无委托的默认拒绝一致）。
 */
interface PromptHandler {
    /** alert()：仅展示，done 任意值均可 */
    fun onAlert(title: String?, message: String?, done: () -> Unit)
    /** confirm()：done(true)=确定 */
    fun onConfirm(title: String?, message: String?, done: (Boolean) -> Unit)
    /** prompt()：done(文本) 或 done(null)=取消 */
    fun onTextPrompt(title: String?, message: String?, defaultValue: String, done: (String?) -> Unit)
    /** <select> 下拉：单选 done(id) / 多选 done(ids)；取消 done(null) */
    fun onChoice(
        title: String?,
        multiple: Boolean,
        items: List<Triple<String, String, Boolean>>, // (id, label, selected)
        done: (List<String>?) -> Unit,
    )
    /** <input type=file>：done(uri 列表) 或 done(null)=取消 */
    fun onFilePrompt(mimeTypes: Array<String>, multiple: Boolean, done: (List<android.net.Uri>?) -> Unit)
    /** HTTP Basic/Digest 认证：done(用户名 to 密码) 或 done(null)=取消 */
    fun onAuthPrompt(title: String?, message: String?, done: (Pair<String, String>?) -> Unit)
    /** beforeunload：done(true)=允许离开 */
    fun onBeforeUnload(title: String?, done: (Boolean) -> Unit)
    /**
     * <input type=color>：done(色值 #RRGGBB) 或 done(null)=取消。
     * 色板与自定义输入由界面层提供；确认后把色值字符串回交内核即可。
     */
    fun onColorPrompt(defaultValue: String, done: (String?) -> Unit)
    /**
     * <input type=date/time/datetime-local/month/week>：done(格式化串) 或 done(null)=取消。
     * [type] 为 [GeckoSession.PromptDelegate.DateTimePrompt.Type] 常量，仅用于界面决定
     * 日期/时间控件与字符串格式；内核 confirm 只收格式化后的字符串（本版本 confirm(String) 不带 type）。
     */
    fun onDateTimePrompt(type: Int, defaultValue: String, done: (String?) -> Unit)
    /**
     * window.open 弹出窗口请求：done(true)=允许 / false=拒绝 / null=取消（dismiss）。
     * 内核区分「明确拒绝(confirm DENY)」与「用户关闭(dismiss)」，故用三态表达。
     */
    fun onPopupPrompt(targetUri: String, done: (Boolean?) -> Unit)
    /**
     * 重新提交表单确认：done(true)=确认重提 / false=取消。
     * 内核 confirm 只收 AllowOrDeny（无空参重载），故取消即 dismiss。
     */
    fun onRepostConfirmPrompt(done: (Boolean) -> Unit)
    /**
     * 页面跳转确认：done(true)=继续 / false=取消（dismiss）。
     * 内核 confirm 只收 AllowOrDeny，取消走 dismiss。
     */
    fun onRedirectPrompt(targetUri: String, done: (Boolean) -> Unit)
    /**
     * 系统分享：界面层唤起系统分享选择器后即 done()（无拒绝语义）。
     * 内核 SharePrompt.confirm(int) 没有「用户拒绝」分支，无论是否真正分享都按成功确认。
     */
    fun onSharePrompt(text: String, uri: String, done: () -> Unit)
    /**
     * 上传整个文件夹：done(true)=已选目录 / null=取消（dismiss）。
     * 内核 FolderUploadPrompt.confirm 只收 AllowOrDeny（无 confirm(Uri) 重载），故选目录即确认允许。
     */
    fun onFolderUploadPrompt(done: (Boolean?) -> Unit)
}

/**
 * 站点权限交互（定位/通知/摄像头/麦克风/Android 运行时权限），由界面层注入。
 * 策略性放行（静音自动播放等）由 SessionManager 内部决定，不打扰界面层。
 */
interface PermissionHandler {
    /**
     * 需要弹窗征询的内容权限（定位/通知/本地设备/本地网络）：done(true)=允许。
     * [perm] 是内核递来的完整授权对象（uri / permission / privateMode 等齐全），
     * 供界面展示与「记住我的选择」的持久化（StorageController.setPermission）使用；
     * [isPrivate] 即 perm.privateMode —— 无痕会话不得持久化任何授权决定。
     */
    fun onContentPermission(
        perm: GeckoSession.PermissionDelegate.ContentPermission,
        isPrivate: Boolean,
        done: (Boolean) -> Unit,
    )
    /** getUserMedia：done(true)=授予首个可用音视频源 */
    fun onMediaPermission(host: String, needsVideo: Boolean, needsAudio: Boolean, done: (Boolean) -> Unit)
    /** Gecko 请求的 Android 运行时权限（CAMERA/RECORD_AUDIO 等）：done(true)=已授予 */
    fun onAndroidPermissions(permissions: Array<String>, done: (Boolean) -> Unit)
}

/**
 * 长按选中文字时的操作处理器（复制 / 分享 / 搜索）。
 * 由界面层实现并注入；未注入时保持 Gecko 默认行为，不产生副作用。
 */
interface SelectionHandler {
    fun onSelection(
        session: org.mozilla.geckoview.GeckoSession,
        text: String,
        availableActions: Collection<String>,
        performAction: (String) -> Unit,
        hide: () -> Unit,
    )

    fun onHide()

    /**
     * 网页请求读取剪贴板（如 navigator.clipboard.readText / 粘贴）：界面层弹框询问用户。
     * [respond] 必须恰好调用一次（allow=true 允许 / false 拒绝），供内核决策。
     */
    fun onClipboardPermissionRequest(uri: String, respond: (Boolean) -> Unit)

    /** 内核 dismiss 剪贴板权限请求（如页面已导航走）：界面层应关闭相关弹框 */
    fun onClipboardPermissionDismissed()
}

/**
 * 标签页 + GeckoRuntime 管理（应用级单例，标签页可跨 Activity 重建存活）：
 *  - 每个 [Tab] 拥有独立 [GeckoSession]，可在同一 [org.mozilla.geckoview.GeckoView] 上切换。
 *  - 会话级设置（无痕/JS/UA/视口）在创建会话时生效；可热改项通过 [applyLiveSettings] 重套。
 *  - [shutdown] 仅应在应用真正退出（isFinishing）时调用。
 */
class BrowserSessionManager private constructor(
    private val appContext: Context,
    val prefs: PreferenceStore,
) {

    companion object {
        /** 日志标记（权限决策等需要留痕的路径使用） */
        private const val TAG = "TangSnow.Session"

        @Volatile
        private var instance: BrowserSessionManager? = null

        fun get(appContext: Context, prefs: PreferenceStore): BrowserSessionManager =
            instance ?: synchronized(this) {
                instance ?: BrowserSessionManager(appContext.applicationContext, prefs)
                    .also { instance = it }
            }

        /**
         * 清空单例。与 [get] 落在**同一把锁**（companion 实例）上：
         * 否则实例字段的置空与双重检查创建之间没有 happens-before 关系，
         * 并发 `get()` 可能拿到旧引用或重复创建。
         */
        private fun clearInstance() {
            synchronized(this) { instance = null }
        }

        /**
         * 预热 GeckoRuntime（必须在主线程调用）。
         * 用于「用户点击同意」后的 2 秒过渡期内把最重的内核初始化提前做掉，
         * 从而让主界面首帧不再卡在引擎启动上。调用前必须已征得用户同意。
         */
        fun warmUp(appContext: Context, prefs: PreferenceStore) {
            runCatching { get(appContext, prefs).runtime() }
        }
    }

    // ── 线程模型：本管理器全部可变状态都**只在主线程**读写 ─────────────────────
    //
    // 涉及：`tabs` / `active` / `nextId` / `lastLiveKey` / `stateCache` / `saveTask`。
    // 因此其中唯一的容器 `stateCache` 用普通 `HashMap` 即可（与 `ExtensionCatalog`
    // 那类真正跨线程的全局状态不同，那边一律 ConcurrentHashMap）。各写入点的线程来源：
    //   · GeckoSession 委托回调（onHistoryStateChange / onVisited / onLocationChange …）
    //     —— GeckoView 在打开该 session 的线程上投递，本应用即主线程；
    //   · `scheduleSave()` 经 `mainHandler`（主 Looper）延时投递；
    //   · `saveState()` 由 `MainActivity.onPause()` 调用。
    //
    // ⚠️ 特别提醒：`saveState()` **看起来**是「后台安全」的——它把落盘交给
    // `SessionStore.write()`，而那里面确实跑在串行 IO 线程上。但**拼快照这一步仍在
    // 主线程**（`normalTabs.map { … stateCache[tab.id] }`）。切勿为了「省主线程」把这段
    // `stateCache` 读取搬进 `SessionStore.write` 的 lambda 里：那会让 `HashMap` 与
    // 主线程的写入（`onHistoryStateChange`）并发命中，轻则丢条目（恢复出的标签丢会话），
    // 重则在扩容期间读到环形链表而**死循环**（JDK7 式 HashMap 死循环的经典成因）。
    // 真要移到后台，先把 `stateCache` 换成 `ConcurrentHashMap`。
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 历史读写协程域（与进程同生命周期的单例，用 SupervisorJob 隔离单个失败不影响其它）。
     * 喂内核访问历史（onVisited 写入、getVisited 读取）走 IO 线程，避免阻塞历史委托回调。
     */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 界面层事件接收者；由 Activity 在 onCreate 里设置 */
    var events: TabEvents? = null

    /** 长按选中文字的界面处理器；未注入时不产生行为（Gecko 默认） */
    var selectionHandler: SelectionHandler? = null

    /** 网页弹窗处理器；未注入时按取消处理（不产生任何 UI） */
    var promptHandler: PromptHandler? = null

    /** 站点权限处理器；未注入时按拒绝处理 */
    var permissionHandler: PermissionHandler? = null

    /** 上一次 live 设置的指纹；未变化时跳过整段循环，减少无谓的系统调用 */
    private var lastLiveKey: String? = null

    private var nextId = 1
    private var active: Tab? = null

    /** 全部标签（按创建先后），首元素最先创建 */
    val tabs: MutableList<Tab> = mutableListOf()

    /** 每个标签最新的会话状态 JSON（tabId → SessionState.toString()），供持久化使用 */
    private val stateCache = HashMap<Int, String>()

    /** 会话状态去抖保存任务（避免 onHistoryStateChange 高频触发频繁写盘） */
    private var saveTask: Runnable? = null

    val activeTab: Tab?
        get() = active

    val tabCount: Int
        get() = tabs.size

    /**
     * 该标签是否仍由本管理器持有。
     *
     * 用途：重建（桌面/移动切换）或关闭标签后，旧 [GeckoSession] 上的委托仍可能投递
     * **迟到事件**（onLocationChange / onExternalResponse / onCloseRequest 等）。
     * 这些事件若不过滤，会往历史里写入已关闭页面的 URL、或为已消失的标签弹下载确认框。
     * 界面层在每个回调入口先做此判定再决定是否继续。
     */
    fun isAlive(tab: Tab): Boolean = tabs.contains(tab)

    /**
     * 新建并激活一个标签页（委托在创建时挂一次，不随 Activity 重建反复挂载）。
     * @param isPrivate 显式指定无痕与否；默认跟随偏好设置
     */
    fun newTab(isPrivate: Boolean = prefs.privateMode): Tab {
        val tab = newTabInternal(isPrivate)
        active = tab
        SessionStore.clearPurged()  // 新浏览活动：恢复正常的会话落盘
        return tab
    }

    /**
     * 新建一个标签但不改变 [active]（供批量重建等需要自行决定活动标签的场景使用）。
     * 其余与 [newTab] 一致：创建已打开会话、加入 [tabs]、挂委托。
     */
    private fun newTabInternal(isPrivate: Boolean): Tab {
        val session = buildSession(isPrivate)
        val tab = Tab(nextId++, session, isPrivate)
        tabs += tab
        attachDelegates(tab)
        return tab
    }

    fun switchTo(tab: Tab) {
        if (tab in tabs) active = tab
    }

    /**
     * 用当前 UA/视口偏好重建**所有**标签的会话（桌面/移动切换全局生效），
     * 消除「只重建活动标签导致多标签下视口不一致」的问题。
     * 每个标签保留 url/标题/无痕/活动身份；旧会话延迟安静关闭。
     * @return 重建后的活动标签（无标签时为 null）
     */
    fun rebuildAllTabs(): Tab? {
        val oldTabs = tabs.toList()
        val oldActive = active
        tabs.clear()
        var newActive: Tab? = null
        for (old in oldTabs) {
            val rebuilt = newTabInternal(isPrivate = old.isPrivate)
            rebuilt.title = old.title
            val url = old.url?.takeIf { it.isNotBlank() && !it.startsWith("about:") }
            if (url != null) {
                rebuilt.url = url
                rebuilt.session.loadUri(url)
            }
            if (old === oldActive) newActive = rebuilt
        }
        active = newActive
        // 旧会话延迟关闭：它们的文档可能仍在加载/合成中，立刻 close 会触发
        // 引擎迟到事件断言；给一个收尾窗口（即 closeSessionLater 的 400ms）。
        oldTabs.forEach { old ->
            stateCache.remove(old.id)
            releasePreview(old)
            closeSessionLater(old.session)
        }
        return newActive
    }

    /**
     * 关闭除 [keep] 外的所有标签，[keep] 成为活动标签。
     * 批量路径不走逐个 [closeTab]（避免频繁 detach/setSession 闪烁），一次性清空再保留。
     */
    fun closeOtherTabs(keep: Tab) {
        if (tabs.size <= 1 || keep !in tabs) return
        val toClose = tabs.filter { it !== keep }
        toClose.forEach {
            stateCache.remove(it.id)
            releasePreview(it)
            post { runCatching { it.session.close() } }
        }
        tabs.clear()
        tabs.add(keep)
        active = keep
    }

    /**
     * 关闭全部标签，并新建一个空白普通标签作为当前标签。
     * 供「关闭全部标签」批量操作使用（本应用始终保留一个标签页）。
     *
     * 会话关闭一律走 post 延迟（与 [closeTab] 同口径）：正在加载中的会话若被同步
     * close，引擎会用迟到事件断言成 "Must use an unopened GeckoSession instance" 而崩溃。
     */
    fun closeAllThenNew(): Tab {
        tabs.toList().forEach {
            stateCache.remove(it.id)
            releasePreview(it)
            post { runCatching { it.session.close() } }
        }
        tabs.clear()
        active = null
        val tab = newTab(isPrivate = false)
        active = tab
        return tab
    }

    /** 关闭指定标签；若关闭的是活动标签，则回退到最后一个剩余标签 */
    fun closeTab(tab: Tab): Tab? {
        val wasActive = active === tab
        tabs.remove(tab)
        stateCache.remove(tab.id)
        releasePreview(tab)
        if (wasActive) active = tabs.lastOrNull()
        // 会话关闭延后一拍执行：标签若刚被释放或仍在加载，立刻 close() 会让引擎把
        // 迟到事件断言成 Must use an unopened GeckoSession instance（桌面版/关闭加载页闪退）
        post { runCatching { tab.session.close() } }
        return active
    }

    /**
     * 安静地移除一个“非活动”标签并延迟关闭其会话（桌面/移动重建时的旧会话收尾）。
     * 与 [closeTab] 的区别：不做活动标签回退、不触碰 UI；只在该标签确实存在且
     * 仍非活动时才执行，避免误关新会话或重复关闭。
     */
    fun closeTabQuiet(tab: Tab) {
        if (tab === active) return
        if (tab !in tabs) return
        tabs.remove(tab)
        stateCache.remove(tab.id)
        releasePreview(tab)
        post { runCatching { tab.session.close() } }
    }

    /**
     * 关闭全部无痕标签。
     * @return 关闭后的活动标签（普通标签）；若已无任何标签则返回 null
     */
    fun closePrivateTabs(): Tab? {
        val privateTabs = tabs.filter { it.isPrivate }
        if (privateTabs.isEmpty()) return active
        privateTabs.forEach {
            tabs.remove(it)
            stateCache.remove(it.id)
            releasePreview(it)
            post { runCatching { it.session.close() } }
        }
        if (active?.isPrivate == true) {
            active = tabs.lastOrNull { !it.isPrivate } ?: tabs.lastOrNull()
        }
        return active
    }

    /** 关闭全部会话（进程退出路径；同步关闭）。运行中批量关闭请用 [closeAllThenNew]。 */
    fun closeAll() {
        tabs.forEach {
            stateCache.remove(it.id)
            releasePreview(it)
            runCatching { it.session.close() }
        }
        tabs.clear()
        active = null
    }

    /**
     * 应用真正退出时释放：关闭全部会话、取消后台协程、关闭内核并清除单例。
     *
     * **幂等且线程安全**（`@Synchronized`）：本项目有两个退出入口会走到这里 ——
     * 「更多 → 退出浏览器」的 `confirmExit()` 先调一次，随后 `onDestroy()`（isFinishing）
     * 再调一次；重复执行必须是无害的。清空单例的动作也必须与 [get] 的 `synchronized`
     * 落在同一把锁上，否则并发 `get()` 可能绕过单例判定再建出一个管理器。
     */
    @Synchronized
    fun shutdown() {
        if (shutDown) return
        shutDown = true
        // 先摘掉待执行的落盘任务，避免关机后回调再访问已关闭的会话/内核
        saveTask?.let { mainHandler.removeCallbacks(it) }
        saveTask = null
        closeAll()
        // 取消后台协程域：不取消则退出的瞬间仍可能有在途的历史写入/查询落到已关闭的进程上
        ioScope.cancel()
        clearInstance()
        // 显式关闭 GeckoRuntime：否则 finishAffinity 后 Gecko 主线程与
        // content 子进程仍在后台驻留（占内存/耗电）。仅在真正退出路径调用。
        GeckoHolder.runtime?.shutdown()
        GeckoHolder.runtime = null
    }

    /** 已执行过 [shutdown]，用于保证关机路径幂等（重复调用直接返回） */
    private var shutDown = false

    /** 重套会话级可热改设置（onResume 调用；值未变化时跳过以省开销） */
    fun applyLiveSettings() {
        val key = "${prefs.javaScript}|${prefs.fontSize}"
        if (key == lastLiveKey) return
        lastLiveKey = key

        tabs.forEach { tab ->
            val s = tab.session.settings
            // 只热改会话级“安全可热改”项；UA/视口模式与跟踪保护档位必须在会话/内核
            // 创建时确定（会话级布尔 + 内核运行时级 ContentBlocking 需一并生效）：
            //   - UA/视口中途修改会输入错乱（“桌面版后点不动”成因），切换由 MainActivity 重建会话；
            //   - 跟踪保护分档属运行时级配置，中途只翻会话布尔会出现“关了但指纹/挖矿仍拦”的半生效，
            //     故一律不热改，等待下次启动统一生效（设置页文案已如此声明）。
            s.allowJavascript = prefs.javaScript
            s.setSuspendMediaWhenInactive(true)
        }
        runtime().settings.setFontSizeFactor(prefs.fontSize)
    }

    /**
     * 构建会话级设置（UA/视口/无痕/JS/跟踪保护/媒体挂起）。
     * 这些项只在会话创建时生效一次。
     */
    private fun buildSessionSettings(isPrivate: Boolean): GeckoSessionSettings =
        GeckoSessionSettings.Builder()
            .usePrivateMode(isPrivate)
            .useTrackingProtection(prefs.trackingProtection)
            .allowJavascript(prefs.javaScript)
            .userAgentMode(prefs.desktopMode.toUserAgentMode())
            .viewportMode(prefs.desktopMode.toViewportMode())
            // 后台标签的媒体自动挂起（省电；主流浏览器默认行为，GeckoView 原生支持）
            .suspendMediaWhenInactive(true)
            .build()

    private fun buildSession(isPrivate: Boolean): GeckoSession {
        val rt = runtime()
        val session = GeckoSession(buildSessionSettings(isPrivate))
        session.open(rt)
        rt.settings.setFontSizeFactor(prefs.fontSize)
        return session
    }

    /** 把 Gecko 回调一次性挂到会话上，经主线程转发给 [events]，不捕获任何 Activity */
    private fun attachDelegates(tab: Tab) {
        val session = tab.session
        session.contentDelegate = contentDelegate(tab)
        session.navigationDelegate = navigationDelegate(tab)
        session.selectionActionDelegate = selectionActionDelegate(tab)
        session.progressDelegate = progressDelegate(tab)
        session.historyDelegate = historyDelegate(tab)
        attachPromptDelegate(tab)
        attachPermissionDelegate(session)
        session.mediaSessionDelegate = mediaSessionDelegate(tab)
    }

    private fun contentDelegate(tab: Tab): GeckoSession.ContentDelegate = object : GeckoSession.ContentDelegate {
        override fun onTitleChange(session: GeckoSession, title: String?) {
            tab.title = title.orEmpty()
            post { events?.onTitleChanged(tab, tab.title) }
        }

        override fun onFullScreen(session: GeckoSession, full: Boolean) {
            tab.fullScreen = full
            post { events?.onFullScreen(tab, full) }
        }

        override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
            post { events?.onExternalResponse(tab, response) }
        }

        override fun onCloseRequest(session: GeckoSession) {
            // window.close()：页面请求关闭自己
            post { events?.onCloseRequest(tab) }
        }

        override fun onCrash(session: GeckoSession) {
            // 内容进程崩溃：内核会自动重建并恢复当前会话；这里上报界面层，
            // 由其给出可见提示并主动重载，避免用户面对一个“卡死”的页面。
            post { events?.onCrash(tab) }
        }
    }

    private fun navigationDelegate(tab: Tab): GeckoSession.NavigationDelegate = object : GeckoSession.NavigationDelegate {
        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            permissions: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
            isReload: Boolean
        ) {
            // about:blank 等内部地址视为「无页面」，避免分享/收藏/恢复逻辑踩到假 URL
            tab.url = url?.takeUnless { it.startsWith("about:") }
            // 真实页面导航 = 新的浏览活动：恢复正常的会话落盘（抵消 purgePending）
            if (!url.isNullOrBlank() && !url.startsWith("about:")) {
                SessionStore.clearPurged()
            }
            post { events?.onLocationChanged(tab, url, isReload) }
        }

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            tab.canGoBack = canGoBack
            post { events?.onNavStateChanged(tab) }
        }

        override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
            tab.canGoForward = canGoForward
            post { events?.onNavStateChanged(tab) }
        }

        override fun onNewSession(
            session: GeckoSession,
            uri: String
        ): GeckoResult<GeckoSession>? {
            // window.open / target=_blank：返回 null 拒绝内核的新窗口请求，同时让
            // 当前标签页跳转到目标 URL（移动端惯例）。这样桌面 UA 下点视频/外链
            // 不会「新开标签 + 回主页」，而是在当前页直接打开。
            //
            // ⚠️ 归属判定：只让**当前活动标签**的 window.open 接管本标签导航。
            // 此前不区分来源 —— 后台标签的弹窗会把用户正在浏览的活动标签整个
            // 劫走（地址栏与页面全被换掉），已关闭标签的迟到请求同样会驱动跳转
            // （同文件其它事件都有 isAlive 守卫，唯独这条链漏了）。后台标签的
            // window.open 按弹窗拦截处理：直接忽略（多数移动浏览器的同款行为）。
            if (!isAlive(tab) || tab !== active) return null
            post { events?.onOpenInCurrentTab(uri) }
            return null
        }

        override fun onLoadRequest(
            session: GeckoSession,
            request: GeckoSession.NavigationDelegate.LoadRequest
        ): GeckoResult<AllowOrDeny>? {
            val uri = request.uri
            val scheme = runCatching { android.net.Uri.parse(uri).scheme }
                .getOrNull().orEmpty().lowercase()
            // 白名单：内核自身能处理的**网页导航**一律放行（返回 null 让内核继续），不拦截。
            val webScheme = scheme == "http" || scheme == "https" || scheme == "about" ||
                scheme == "data" || scheme == "blob"
            // 特权 scheme：只放行**应用自己发起**的导航。
            //
            // 判据用内核**文档化**的 `LoadRequest.isDirectNavigation`（javap 实测
            // geckoview 155 已具备该 public final 字段），其官方语义为
            //   "This load request was initiated by a direct navigation from the
            //    application. E.g. when calling GeckoSession.load(...)"
            // —— 正是本处要表达的意思。
            //
            // 为什么不再用 `triggerUri` 反推（旧写法）：官方 javadoc 明确写着
            //   "The URI of the origin page that triggered the load request.
            //    null for initial loads and loads originating from data: URIs."
            // 即「初始加载」与「源自 data: URI 的加载」两种情况它都是 null，而 `data:`
            // 正在上面的白名单里 —— 于是「data: 文档里放一个指向 file:// 的链接」会被
            // 判成「非网页内容发起」而放行 file:。安全闸门不该架在一个会漏判的字段上。
            //
            //  - `moz-extension:` 必须保留：扩展选项页/管理页经 BrowserOpener 的**进程内
            //    通道**送到 MainActivity，再由 `session.loadUri()` 加载 —— 属直接导航
            //    （isDirectNavigation = true）；若一律禁止会重现「装完扩展打不开自己的
            //    设置页」的回归事故。
            //  - `file:` 网页内容永远没有正当理由触发它（地址栏输入 file:// 会被
            //    UrlUtils 当作搜索词），故对网页内容一律拒绝。
            val privilegedScheme = scheme == "moz-extension" || scheme == "file"
            val internal = webScheme || (privilegedScheme && request.isDirectNavigation)
            if (internal) return null
            // 外部协议（intent://、market://、mailto:、tel:、geo: 等）：先让界面层用系统
            // Intent 尝试打开，再拒绝内核本次跳转（否则内核会尝试自行处理、带走当前页）。
            post { events?.onExternalProtocol(uri) }
            return GeckoResult.deny()
        }
    }

    private fun selectionActionDelegate(tab: Tab): GeckoSession.SelectionActionDelegate = object : GeckoSession.SelectionActionDelegate {
        override fun onShowActionRequest(
            session: GeckoSession,
            selection: GeckoSession.SelectionActionDelegate.Selection,
        ) {
            val text = selection.text.orEmpty()
            val actions = selection.availableActions
            post {
                val handler = selectionHandler ?: return@post
                handler.onSelection(
                    session,
                    text,
                    actions,
                    { action ->
                        runCatching { selection.execute(action) }
                            .onFailure { selection.hide() }
                    },
                    { runCatching { selection.hide() } },
                )
            }
        }

        override fun onHideAction(session: GeckoSession, reason: Int) {
            post { selectionHandler?.onHide() }
        }

        override fun onShowClipboardPermissionRequest(
            session: GeckoSession,
            permission: GeckoSession.SelectionActionDelegate.ClipboardPermission,
        ): GeckoResult<AllowOrDeny>? {
            // 网页请求读取剪贴板（粘贴）：转发给界面层弹框询问，用户决定后 complete。
            // 未注入处理器时一律拒绝（安全默认，等价 GeckoView 默认 deny）。
            val result = GeckoResult<AllowOrDeny>()
            post {
                val handler = selectionHandler
                if (handler == null) {
                    result.complete(AllowOrDeny.DENY)
                    return@post
                }
                handler.onClipboardPermissionRequest(permission.uri.orEmpty()) { allow ->
                    result.complete(if (allow) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
                }
            }
            return result
        }

        override fun onDismissClipboardPermissionRequest(session: GeckoSession) {
            post { selectionHandler?.onClipboardPermissionDismissed() }
        }
    }

    private fun progressDelegate(tab: Tab): GeckoSession.ProgressDelegate = object : GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            post { events?.onPageStart(tab) }
        }

        override fun onProgressChange(session: GeckoSession, progress: Int) {
            post { events?.onPageProgress(tab, progress) }
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            post { events?.onPageStop(tab) }
        }

        override fun onSecurityChange(
            session: GeckoSession,
            securityInfo: GeckoSession.ProgressDelegate.SecurityInformation,
        ) {
            // mixedModeActive 只有 CONTENT_LOADED（已加载，未被阻断）才算真风险；
            // CONTENT_BLOCKED 表示内核已把混合内容拦掉，页面仍是安全的。
            val mixedLoaded = securityInfo.mixedModeActive ==
                GeckoSession.ProgressDelegate.SecurityInformation.CONTENT_LOADED
            post { events?.onSecurityChange(tab, securityInfo.isSecure, mixedLoaded) }
        }
    }

    // 会话状态：内核每次历史变化都会回调最新 HistoryList（即 SessionState），
    // 缓存其序列化 JSON 用于进程回收后的恢复；无痕标签由 saveState 过滤不落盘。
    private fun historyDelegate(tab: Tab): GeckoSession.HistoryDelegate = object : GeckoSession.HistoryDelegate {
        override fun onHistoryStateChange(
            session: GeckoSession,
            historyList: GeckoSession.HistoryDelegate.HistoryList,
        ) {
            // 已脱离 tabs 的旧标签（关闭/重建后的延迟收尾期）迟到的历史事件：
            // 不写 stateCache —— 否则已删条目被复活，且此后没有任何路径再清理它，
            // 每关一个标签就泄漏一份会话状态 JSON（长历史栈可达几十 KB）。
            if (!isAlive(tab)) return
            // 这里**当场**取 JSON 字符串（值拷贝），不是留引用。
            //
            // 依据（javap 实测 geckoview 155 的 classes.jar，非推测）：
            //   HistoryList 只是一个接口，其实现就是 `GeckoSession.SessionState`
            //   —— 声明的类型层次为
            //   `SessionState extends AbstractSequentialList<HistoryItem>
            //                    implements HistoryList, Parcelable`；
            //   而它有一个包内方法 `void updateSessionState(GeckoBundle)`，说明这个
            //   对象由内核侧**就地刷新**：同一个实例会被后续回调反复改写。
            // 因此若把 `historyList` 引用存进 `stateCache` 留到落盘时再读，读到的将是
            // 「最后一次改写后」的内容，而非本次回调时刻的状态；extended 场景下还会
            // 遇到内核已释放其 GeckoBundle 支撑的情况。存 String 才是快照。
            val json = runCatching { historyList.toString() }.getOrNull() ?: return
            stateCache[tab.id] = json
            scheduleSave()
        }

        override fun onVisited(
            session: GeckoSession,
            url: String,
            lastVisitedUrl: String?,
            flags: Int
        ): GeckoResult<Boolean>? {
            // ⚠️ 私有会话一律不落盘：应用侧**自己**保证「无痕不留痕迹」，不把承诺寄托在
            // 引擎行为上 —— GeckoView 官方 javadoc 对 onVisited 是否会在私有会话下回调
            // **未作任何说明**（已查证），因此这里必须显式拦掉，否则一旦引擎回调，
            // 无痕访问就会被写进应用自有历史表，与隐私政策「无痕标签不落盘」直接冲突。
            if (tab.isPrivate) return GeckoResult.fromValue(false)

            // 内核告知某 URL 被访问：写进应用自有历史表（应用写、内核读），
            // 供 getVisited 反向喂给内核，使网页内“已访问链接”正确着色。
            //
            // ⚠️ 第三个参数是 **lastVisitedURL**（本会话上一次访问的 URL，用于识别重定向/刷新），
            // **不是标题也不是 referrer**（已核对 GeckoView API 文档）。
            //
            // 这里刻意传**空标题**：`HistoryRepo.add` 会把空白标题归一成 `null` 再交给
            // `BrowserDb.touchHistory`，其语义是「本次没有可用标题 → 只刷新 visited_at、
            // 不碰 title 列」，因此绝不会用空值覆盖界面层（`onLocationChanged`）已写入的真实标题。
            //
            // 用 try/catch 而不是 runCatching：**runCatching 会一并吞掉 CancellationException**，
            // 破坏协作式取消语义（本仓库坑清单里写明的规则）—— 域被取消后不该再继续跑收尾语句。
            ioScope.launch {
                try {
                    HistoryRepo.add(url, "")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // 历史写入失败只影响「已访问」着色，绝不能影响本次导航的内核应答
                }
            }
            return GeckoResult.fromValue(true)
        }

        override fun getVisited(
            session: GeckoSession,
            urls: Array<out String>
        ): GeckoResult<BooleanArray>? {
            // ⚠️ 私有会话一律按「未访问」应答：否则页面可借助 `:visited` 样式推断出
            // **你在无痕之前访问过哪些站点** —— 无痕模式的意义就是不暴露既有浏览历史。
            // 返回全 false 与「无痕下内核本就不该有历史」的语义一致，且不会写入任何数据。
            if (tab.isPrivate) return GeckoResult.fromValue(BooleanArray(urls.size))

            // 内核询问这批 URL 哪些“已访问”：从应用自有历史表（只读）查，
            // 返回与 urls 等长的布尔数组。纯查询，不改写任何数据。
            //
            // ⚠️ 本回调被 GeckoView 标注为 **@UiThread**（javap 实测 HistoryDelegate 全部回调
            // 均为 UiThread），故**不能**在这里同步读 SQLite —— 那是主线程磁盘 I/O，
            // 页面每次加载都会走一次，数据库被占用时会直接卡顿甚至 ANR。
            // 改为：先返回一个未完成的 GeckoResult，查询在 IO 线程跑完后再 complete。
            val result = GeckoResult<BooleanArray>()
            ioScope.launch {
                // 同样用 try/catch 而非 runCatching：取消必须原样传播（见 onVisited）。
                // 这里被取消只可能是 `shutdown()` 取消了整个 ioScope —— 内核随即一并关闭，
                // 不再需要这次应答，故中止查询即可。
                val visited = try {
                    HistoryRepo.areVisited(urls)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // 查询失败按「全部未访问」应答：绝不能让 GeckoResult 悬空（页面会一直等这批结果）
                    BooleanArray(urls.size)
                }
                result.complete(visited)
            }
            return result
        }
    }

    // 媒体播放状态：供「切后台自动画中画」判断（无痕标签跳过见 MainActivity）。
    // 每次变化都通知界面层——Android 12+ 的自动进入画中画必须在播放开始时就把
    // 参数准备好，否则用户返回桌面时系统不会接管转场。
    private fun mediaSessionDelegate(tab: Tab): org.mozilla.geckoview.MediaSession.Delegate = object : org.mozilla.geckoview.MediaSession.Delegate {
        override fun onActivated(
            session: GeckoSession,
            mediaSession: org.mozilla.geckoview.MediaSession,
        ) {
            setMediaPlaying(tab, true)
        }

        override fun onDeactivated(
            session: GeckoSession,
            mediaSession: org.mozilla.geckoview.MediaSession,
        ) {
            setMediaPlaying(tab, false)
        }

        override fun onPlay(session: GeckoSession, mediaSession: org.mozilla.geckoview.MediaSession) {
            setMediaPlaying(tab, true)
        }

        override fun onPause(session: GeckoSession, mediaSession: org.mozilla.geckoview.MediaSession) {
            setMediaPlaying(tab, false)
        }

        override fun onStop(session: GeckoSession, mediaSession: org.mozilla.geckoview.MediaSession) {
            setMediaPlaying(tab, false)
        }
    }

    /** 更新媒体播放标记并在**状态真正变化**时通知界面层（去重，避免重复设置画中画参数） */
    private fun setMediaPlaying(tab: Tab, playing: Boolean) {
        if (tab.mediaPlaying == playing) return
        tab.mediaPlaying = playing
        post { events?.onMediaStateChanged(tab, playing) }
    }

    // --------------------------------------------------------- 网页弹窗（A2）

    /**
     * 弹窗归属的**执行瞬间复查** —— 守卫不能只查投递瞬间。
     *
     * 为什么必须复查：入口那次判定发生在内核回调的时刻，而对话框是 `post{}` 真正执行时
     * 才创建的；两者之间（主线程消息队列里可能还排着用户的操作）用户完全可能切走、关掉
     * 这个标签，甚至让 Activity 进入销毁。只查投递瞬间的话，弹窗照样弹、应答还给已经关闭
     * 的会话 —— 注释里宣称的「不抢用户焦点」只成立一半。
     *
     * @return true 表示此刻已不该弹，并且**已经**按「后台/已关标签」的同一口径应答完成
     *         （调用方直接 `return@post` 即可，不要再应答一次）
     */
    private fun promptStale(
        tab: Tab,
        result: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>,
        prompt: GeckoSession.PromptDelegate.BasePrompt,
    ): Boolean {
        if (isAlive(tab) && tab === active) return false
        result.complete(prompt.dismiss())
        return true
    }

    private fun attachPromptDelegate(tab: Tab) {
        tab.session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onAlertPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AlertPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                // 后台/已关标签的模态弹窗一律自动 dismiss：不抢用户焦点
                //（isAlive 拦死标签迟到回调；active 拦后台标签 setInterval 型无节流弹窗）。
                // ⚠️ 这里只是**投递瞬间**的判定：对话框在下方 post{} 里才创建，故那里用 promptStale 复查。
                if (!isAlive(tab) || tab !== active) {
                    return GeckoResult.fromValue(prompt.dismiss())
                }
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                post {
                    if (promptStale(tab, result, prompt)) return@post
                    val h = promptHandler
                    if (h == null) result.complete(prompt.dismiss())
                    else h.onAlert(prompt.title, prompt.message) {
                        // AlertPrompt 无公开 confirm()，看完后一律 dismiss 应答
                        result.complete(prompt.dismiss())
                    }
                }
                return result
            }

            override fun onButtonPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.ButtonPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                // 后台/已关标签的模态弹窗一律自动 dismiss：不抢用户焦点
                //（isAlive 拦死标签迟到回调；active 拦后台标签 setInterval 型无节流弹窗）。
                // ⚠️ 这里只是**投递瞬间**的判定：对话框在下方 post{} 里才创建，故那里用 promptStale 复查。
                if (!isAlive(tab) || tab !== active) {
                    return GeckoResult.fromValue(prompt.dismiss())
                }
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                post {
                    if (promptStale(tab, result, prompt)) return@post
                    val h = promptHandler
                    if (h == null) result.complete(prompt.dismiss())
                    else h.onConfirm(prompt.title, prompt.message) { ok ->
                        result.complete(
                            if (ok) prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE)
                            else prompt.dismiss()
                        )
                    }
                }
                return result
            }

            override fun onTextPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.TextPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                // 后台/已关标签的模态弹窗一律自动 dismiss：不抢用户焦点
                //（isAlive 拦死标签迟到回调；active 拦后台标签 setInterval 型无节流弹窗）。
                // ⚠️ 这里只是**投递瞬间**的判定：对话框在下方 post{} 里才创建，故那里用 promptStale 复查。
                if (!isAlive(tab) || tab !== active) {
                    return GeckoResult.fromValue(prompt.dismiss())
                }
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                post {
                    if (promptStale(tab, result, prompt)) return@post
                    val h = promptHandler
                    if (h == null) result.complete(prompt.dismiss())
                    else h.onTextPrompt(prompt.title, prompt.message, prompt.defaultValue.orEmpty()) { text ->
                        result.complete(if (text == null) prompt.dismiss() else prompt.confirm(text))
                    }
                }
                return result
            }

            override fun onChoicePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.ChoicePrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                // 后台/已关标签的模态弹窗一律自动 dismiss：不抢用户焦点
                //（isAlive 拦死标签迟到回调；active 拦后台标签 setInterval 型无节流弹窗）。
                // ⚠️ 这里只是**投递瞬间**的判定：对话框在下方 post{} 里才创建，故那里用 promptStale 复查。
                if (!isAlive(tab) || tab !== active) {
                    return GeckoResult.fromValue(prompt.dismiss())
                }
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                val choices = prompt.choices
                val multiple = prompt.type == GeckoSession.PromptDelegate.ChoicePrompt.Type.MULTIPLE
                post {
                    if (promptStale(tab, result, prompt)) return@post
                    val h = promptHandler
                    if (h == null) result.complete(prompt.dismiss())
                    else h.onChoice(
                        prompt.title,
                        multiple,
                        choices.map { Triple(it.id.orEmpty(), it.label.orEmpty(), it.selected) },
                    ) { picked ->
                        when {
                            picked == null -> result.complete(prompt.dismiss())
                            multiple -> result.complete(
                                prompt.confirm(picked.toTypedArray())
                            )
                            else -> result.complete(prompt.confirm(picked.firstOrNull().orEmpty()))
                        }
                    }
                }
                return result
            }

            override fun onFilePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.FilePrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                // 后台/已关标签的模态弹窗一律自动 dismiss：不抢用户焦点
                //（isAlive 拦死标签迟到回调；active 拦后台标签 setInterval 型无节流弹窗）。
                // ⚠️ 这里只是**投递瞬间**的判定：对话框在下方 post{} 里才创建，故那里用 promptStale 复查。
                if (!isAlive(tab) || tab !== active) {
                    return GeckoResult.fromValue(prompt.dismiss())
                }
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                val multiple = prompt.type != GeckoSession.PromptDelegate.FilePrompt.Type.SINGLE
                post {
                    if (promptStale(tab, result, prompt)) return@post
                    val h = promptHandler
                    if (h == null) result.complete(prompt.dismiss())
                    else h.onFilePrompt(prompt.mimeTypes ?: emptyArray(), multiple) { uris ->
                        when {
                            uris == null -> result.complete(prompt.dismiss())
                            multiple -> result.complete(
                                prompt.confirm(appContext, uris.toTypedArray())
                            )
                            else -> result.complete(
                                uris.firstOrNull()?.let { prompt.confirm(appContext, it) }
                                    ?: prompt.dismiss()
                            )
                        }
                    }
                }
                return result
            }

            override fun onAuthPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AuthPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                // 后台/已关标签的模态弹窗一律自动 dismiss：不抢用户焦点
                //（isAlive 拦死标签迟到回调；active 拦后台标签 setInterval 型无节流弹窗）。
                // ⚠️ 这里只是**投递瞬间**的判定：对话框在下方 post{} 里才创建，故那里用 promptStale 复查。
                if (!isAlive(tab) || tab !== active) {
                    return GeckoResult.fromValue(prompt.dismiss())
                }
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                post {
                    if (promptStale(tab, result, prompt)) return@post
                    val h = promptHandler
                    if (h == null) result.complete(prompt.dismiss())
                    else h.onAuthPrompt(prompt.title, prompt.message) { cred ->
                        result.complete(
                            if (cred == null) prompt.dismiss()
                            else prompt.confirm(cred.first, cred.second)
                        )
                    }
                }
                return result
            }

            override fun onBeforeUnloadPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.BeforeUnloadPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                // 后台/已关标签的模态弹窗一律自动 dismiss：不抢用户焦点
                //（isAlive 拦死标签迟到回调；active 拦后台标签 setInterval 型无节流弹窗）。
                // ⚠️ 这里只是**投递瞬间**的判定：对话框在下方 post{} 里才创建，故那里用 promptStale 复查。
                if (!isAlive(tab) || tab !== active) {
                    return GeckoResult.fromValue(prompt.dismiss())
                }
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                post {
                    if (promptStale(tab, result, prompt)) return@post
                    val h = promptHandler
                    if (h == null) result.complete(prompt.confirm(AllowOrDeny.DENY))
                    else h.onBeforeUnload(prompt.title) { leave ->
                        result.complete(
                            prompt.confirm(if (leave) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
                        )
                    }
                }
                return result
            }

            override fun onColorPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.ColorPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                // 后台/已关标签的模态弹窗一律自动 dismiss：不抢用户焦点
                //（isAlive 拦死标签迟到回调；active 拦后台标签 setInterval 型无节流弹窗）。
                // ⚠️ 这里只是**投递瞬间**的判定：对话框在下方 post{} 里才创建，故那里用 promptStale 复查。
                if (!isAlive(tab) || tab !== active) {
                    return GeckoResult.fromValue(prompt.dismiss())
                }
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                post {
                    if (promptStale(tab, result, prompt)) return@post
                    val h = promptHandler
                    if (h == null) result.complete(prompt.dismiss())
                    else h.onColorPrompt(prompt.defaultValue.orEmpty()) { value ->
                        // ColorPrompt.confirm(String) 无 type 参数：色值字符串即为内核所需全部信息
                        result.complete(if (value == null) prompt.dismiss() else prompt.confirm(value))
                    }
                }
                return result
            }

            override fun onDateTimePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.DateTimePrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                // 后台/已关标签的模态弹窗一律自动 dismiss：不抢用户焦点
                //（isAlive 拦死标签迟到回调；active 拦后台标签 setInterval 型无节流弹窗）。
                // ⚠️ 这里只是**投递瞬间**的判定：对话框在下方 post{} 里才创建，故那里用 promptStale 复查。
                if (!isAlive(tab) || tab !== active) {
                    return GeckoResult.fromValue(prompt.dismiss())
                }
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                // type 是 int 常量，提前取出避免 lambda 内二次引用 prompt
                val type = prompt.type
                post {
                    if (promptStale(tab, result, prompt)) return@post
                    val h = promptHandler
                    if (h == null) result.complete(prompt.dismiss())
                    else h.onDateTimePrompt(type, prompt.defaultValue.orEmpty()) { value ->
                        // DateTimePrompt.confirm(String) 只收格式化串；type 仅供界面决定控件
                        result.complete(if (value == null) prompt.dismiss() else prompt.confirm(value))
                    }
                }
                return result
            }

            override fun onPopupPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.PopupPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                // 后台/已关标签的模态弹窗一律自动 dismiss：不抢用户焦点
                //（isAlive 拦死标签迟到回调；active 拦后台标签 setInterval 型无节流弹窗）。
                // ⚠️ 这里只是**投递瞬间**的判定：对话框在下方 post{} 里才创建，故那里用 promptStale 复查。
                if (!isAlive(tab) || tab !== active) {
                    return GeckoResult.fromValue(prompt.dismiss())
                }
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                post {
                    if (promptStale(tab, result, prompt)) return@post
                    val h = promptHandler
                    if (h == null) result.complete(prompt.dismiss())
                    else h.onPopupPrompt(prompt.targetUri.orEmpty()) { allow ->
                        // 三态：允许=ALLOW，拒绝=DENY，用户关闭=dismiss（语义有别，不能混为一谈）
                        result.complete(
                            when (allow) {
                                true -> prompt.confirm(AllowOrDeny.ALLOW)
                                false -> prompt.confirm(AllowOrDeny.DENY)
                                null -> prompt.dismiss()
                            }
                        )
                    }
                }
                return result
            }

            override fun onRepostConfirmPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.RepostConfirmPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                // 后台/已关标签的模态弹窗一律自动 dismiss：不抢用户焦点
                //（isAlive 拦死标签迟到回调；active 拦后台标签 setInterval 型无节流弹窗）。
                // ⚠️ 这里只是**投递瞬间**的判定：对话框在下方 post{} 里才创建，故那里用 promptStale 复查。
                if (!isAlive(tab) || tab !== active) {
                    return GeckoResult.fromValue(prompt.dismiss())
                }
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                post {
                    if (promptStale(tab, result, prompt)) return@post
                    val h = promptHandler
                    if (h == null) result.complete(prompt.dismiss())
                    else h.onRepostConfirmPrompt { ok ->
                        // 内核 confirm 只收 AllowOrDeny（无空参重载）：确认=ALLOW，取消=dismiss
                        result.complete(if (ok) prompt.confirm(AllowOrDeny.ALLOW) else prompt.dismiss())
                    }
                }
                return result
            }

            override fun onRedirectPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.RedirectPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                // 后台/已关标签的模态弹窗一律自动 dismiss：不抢用户焦点
                //（isAlive 拦死标签迟到回调；active 拦后台标签 setInterval 型无节流弹窗）。
                // ⚠️ 这里只是**投递瞬间**的判定：对话框在下方 post{} 里才创建，故那里用 promptStale 复查。
                if (!isAlive(tab) || tab !== active) {
                    return GeckoResult.fromValue(prompt.dismiss())
                }
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                post {
                    if (promptStale(tab, result, prompt)) return@post
                    val h = promptHandler
                    if (h == null) result.complete(prompt.dismiss())
                    else h.onRedirectPrompt(prompt.targetUri.orEmpty()) { ok ->
                        result.complete(if (ok) prompt.confirm(AllowOrDeny.ALLOW) else prompt.dismiss())
                    }
                }
                return result
            }

            override fun onSharePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.SharePrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                // 后台/已关标签的模态弹窗一律自动 dismiss：不抢用户焦点
                //（isAlive 拦死标签迟到回调；active 拦后台标签 setInterval 型无节流弹窗）。
                // ⚠️ 这里只是**投递瞬间**的判定：对话框在下方 post{} 里才创建，故那里用 promptStale 复查。
                if (!isAlive(tab) || tab !== active) {
                    return GeckoResult.fromValue(prompt.dismiss())
                }
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                post {
                    if (promptStale(tab, result, prompt)) return@post
                    val h = promptHandler
                    if (h == null) result.complete(prompt.dismiss())
                    else h.onSharePrompt(prompt.text.orEmpty(), prompt.uri.orEmpty()) {
                        // SharePrompt.confirm(int) 无「用户拒绝」语义：唤起系统选择器后即确认成功
                        result.complete(
                            prompt.confirm(GeckoSession.PromptDelegate.SharePrompt.Result.SUCCESS)
                        )
                    }
                }
                return result
            }

            override fun onFolderUploadPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.FolderUploadPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                // 后台/已关标签的模态弹窗一律自动 dismiss：不抢用户焦点
                //（isAlive 拦死标签迟到回调；active 拦后台标签 setInterval 型无节流弹窗）。
                // ⚠️ 这里只是**投递瞬间**的判定：对话框在下方 post{} 里才创建，故那里用 promptStale 复查。
                if (!isAlive(tab) || tab !== active) {
                    return GeckoResult.fromValue(prompt.dismiss())
                }
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                post {
                    if (promptStale(tab, result, prompt)) return@post
                    val h = promptHandler
                    if (h == null) result.complete(prompt.dismiss())
                    else h.onFolderUploadPrompt { picked ->
                        // FolderUploadPrompt.confirm(AllowOrDeny) 无 confirm(Uri) 重载：选目录即确认允许
                        result.complete(if (picked == true) prompt.confirm(AllowOrDeny.ALLOW) else prompt.dismiss())
                    }
                }
                return result
            }
        }
    }

    // --------------------------------------------------------- 站点权限（A3）

    private fun attachPermissionDelegate(session: GeckoSession) {
        session.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(
                session: GeckoSession,
                perm: GeckoSession.PermissionDelegate.ContentPermission
            ): GeckoResult<Int>? {
                val result = GeckoResult<Int>()
                val allow = GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                val deny = GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                // 三类决策必须**逐项显式列出**，不能靠「不在征询名单里就拒绝」来兜底：
                // 后者会把「有意拒绝」和「忘了处理」混为一谈，新权限出现时会静默失效。
                when (perm.permission) {
                    // ① 策略放行：不影响用户、且拒绝会直接破坏站点基础功能
                    //   - 静音自动播放：主流浏览器默认允许（无声音、不打扰）
                    //   - 持久存储：站点被授予的存储配额（不授予会让离线应用反复申请）
                    //   - DRM 媒体密钥（EME / Widevine）：拒绝则所有需要 DRM 的视频
                    //     （流媒体站点的清晰度档、会员内容）直接无法播放，属功能性缺失
                    GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE,
                    GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE,
                    GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> {
                        result.complete(allow)
                        return result
                    }
                    // ② 必然弹窗征询：涉及位置、通知、本机设备与局域网访问
                    GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION,
                    GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION,
                    GeckoSession.PermissionDelegate.PERMISSION_LOCAL_DEVICE_ACCESS,
                    GeckoSession.PermissionDelegate.PERMISSION_LOCAL_NETWORK_ACCESS -> {
                        post {
                            val h = permissionHandler
                            if (h == null) {
                                result.complete(deny)
                            } else h.onContentPermission(perm, perm.privateMode) { granted ->
                                result.complete(if (granted) allow else deny)
                            }
                        }
                        return result
                    }
                    // ③ 明确拒绝，并记录原因（便于排障；不写日志会变成"静默失效"）
                    else -> {
                        // 只记**主机名**，不记完整 URL；且**只在 debug 构建里记**。
                        // 本应用对外的隐私承诺包含「不把您访问的网址发送给第三方」，而日志会留在
                        // 设备 Logcat。release 由 BuildConfig.DEBUG 拦在这里（另有
                        // proguard-rules.pro 的 `-assumenosideeffects` 兜底剥离 Log.v/d/i），
                        // 所以正式版既不会把站点域名写进 Logcat，排障能力在 debug 构建里也不打折。
                        // 主机名已足够定位「哪个站点触发了预期外的权限类型」，路径与查询串对排障
                        // 没有增量价值、却会完整落进日志。
                        // 注：perm.uri 是 Java 侧字段（javap: `public final String uri`），
                        // Kotlin 视为平台类型、此处推为非空，故不加 `?.`（加了会触发
                        // Unnecessary safe call 警告）。若 Java 侧真传 null，Uri.parse 抛的 NPE
                        // 会被外层 runCatching 捕获 → host 为 null → 记 "(unknown)"，同样安全。
                        val host = runCatching { android.net.Uri.parse(perm.uri).host }.getOrNull()
                        if (BuildConfig.DEBUG) {
                            android.util.Log.i(
                                TAG,
                                "content permission denied by policy: " +
                                    "type=${perm.permission} host=${host ?: "(unknown)"}",
                            )
                        }
                        result.complete(deny)
                        return result
                    }
                }
            }

            override fun onAndroidPermissionsRequest(
                session: GeckoSession,
                permissions: Array<out String>?,
                callback: GeckoSession.PermissionDelegate.Callback
            ) {
                post {
                    val h = permissionHandler
                    if (h == null) callback.reject()
                    else h.onAndroidPermissions(
                        permissions?.toList()?.toTypedArray() ?: emptyArray()
                    ) { ok ->
                        if (ok) callback.grant() else callback.reject()
                    }
                }
            }

            override fun onMediaPermissionRequest(
                session: GeckoSession,
                uri: String,
                video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                callback: GeckoSession.PermissionDelegate.MediaCallback
            ) {
                val host = android.net.Uri.parse(uri).host ?: uri
                post {
                    val h = permissionHandler
                    if (h == null) callback.reject()
                    else h.onMediaPermission(
                        host,
                        needsVideo = !video.isNullOrEmpty(),
                        needsAudio = !audio.isNullOrEmpty(),
                    ) { ok ->
                        if (ok) callback.grant(video?.firstOrNull(), audio?.firstOrNull())
                        else callback.reject()
                    }
                }
            }
        }
    }

    private fun post(block: () -> Unit) {
        mainHandler.post(block)
    }

    private fun runtime(): GeckoRuntime {
        GeckoHolder.runtime?.let { return it }
        return synchronized(this) {
            GeckoHolder.runtime ?: run {
                // 跟踪保护档位属于运行时级配置：只能在首次创建内核时注入，
                // 因此设置页对档位的改动标注为「下次启动生效」
                val settings = GeckoRuntimeSettings.Builder()
                    .contentBlocking(buildContentBlocking())
                    // 内核自带的「全球隐私控制(GPC)」信号：向支持的网站声明“请勿出售/分享我的数据”。
                    // 默认开启，可在 设置→隐私与安全 关闭；仅发送声明，网站可自行决定是否尊重。
                    .globalPrivacyControlEnabled(prefs.gpcEnabled)
                    .build()
                // 运行时级「指纹保护」(javap 实测：GeckoRuntimeSettings
                // .setFingerprintingProtection(boolean)，非 Builder 方法)。
                // 注意区分：本项是运行时级防护能力，独立于上面 AntiTracking 的
                // FINGERPRINTING 分类——后者只拦截“指纹类跟踪脚本/资源”，本项是在更底层
                // 打乱/削弱可被用来指纹化的 API 表面。两者叠加而非互斥。
                //
                // 取值**跟随跟踪保护档位**（而不是无条件开启），理由有两条：
                //  1. 语义一致：用户选「关闭跟踪保护」就不能还偷偷开着指纹保护，
                //     否则设置页上的开关是假的（这是可被用户直接感知的失信）；
                //  2. 兼容性：本项等价内核的 privacy.resistFingerprinting，会改变
                //     时区 / Canvas / 字体 / 媒体能力等 JS 可见面，是已知的站点兼容性
                //     风险源。标准档不开、严格档与自定义档（勾了「拦截指纹跟踪」）才开，
                //     把破坏性能力限定在用户明确要求的档位上。
                settings.setFingerprintingProtection(fingerprintingProtectionEnabled())
                val rt = GeckoRuntime.create(appContext, settings)
                GeckoHolder.runtime = rt
                rt
            }
        }
    }

    /**
     * 运行时级指纹保护是否开启（跟随跟踪保护档位，见 [runtime] 中的说明）。
     *  - 关闭 / 标准：不开（标准档只拦“指纹类跟踪资源”，不改动 JS 可见面）；
     *  - 严格：开；
     *  - 自定义：由「拦截指纹跟踪」分项决定。
     */
    private fun fingerprintingProtectionEnabled(): Boolean = when (prefs.trackingMode) {
        PreferenceStore.TRACKING_STRICT -> true
        PreferenceStore.TRACKING_CUSTOM -> prefs.trackingCustomFingerprint
        else -> false
    }

    /**
     * 把偏好里的跟踪保护档位翻译成内核的 ContentBlocking 配置。
     *
     * 语义对齐 Firefox 本体（不使用 AD/ANALYTIC 名单，避免进入「广告拦截」定性）：
     *  - 标准：拦截跟踪内容（含社交跟踪器）、指纹跟踪、挖矿脚本；拒绝已知跟踪器 Cookie；
     *  - 严格：在标准基础上对全部跨站 Cookie 按来源隔离 + 收紧社交跟踪 + 过期 Cookie 清理；
     *  - 自定义：由用户勾选的分类与 Cookie 策略组合；
     *  - 关闭：不启用任何内容拦截、接受全部 Cookie。
     * 「链接跟踪参数清理」不属于档位，由独立开关控制，统一在函数末尾套用。
     */
    private fun buildContentBlocking(): ContentBlocking.Settings {
        val builder = ContentBlocking.Settings.Builder()
        val standardMask = ContentBlocking.AntiTracking.CONTENT or
            ContentBlocking.AntiTracking.SOCIAL or
            ContentBlocking.AntiTracking.CRYPTOMINING or
            ContentBlocking.AntiTracking.FINGERPRINTING
        when (prefs.trackingMode) {
            PreferenceStore.TRACKING_OFF -> builder
                .antiTracking(ContentBlocking.AntiTracking.NONE)
                .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_ALL)
                .cookieBehaviorPrivateMode(ContentBlocking.CookieBehavior.ACCEPT_ALL)

            PreferenceStore.TRACKING_STANDARD -> builder
                .antiTracking(standardMask)
                .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS)
                .cookieBehaviorPrivateMode(ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS)

            PreferenceStore.TRACKING_STRICT -> builder
                .antiTracking(standardMask)
                .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
                .cookieBehaviorPrivateMode(
                    ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS
                )
                .strictSocialTrackingProtection(true)
                .cookiePurging(true)

            else -> {
                // 自定义：只放行用户勾选的分类；至少无任何分类时按关闭处理
                var mask = 0
                if (prefs.trackingCustomContent) {
                    mask = mask or ContentBlocking.AntiTracking.CONTENT
                    mask = mask or ContentBlocking.AntiTracking.SOCIAL
                }
                if (prefs.trackingCustomFingerprint) {
                    mask = mask or ContentBlocking.AntiTracking.FINGERPRINTING
                }
                if (prefs.trackingCustomCryptominer) {
                    mask = mask or ContentBlocking.AntiTracking.CRYPTOMINING
                }
                builder.antiTracking(mask)
                val cookie = if (prefs.trackingCustomCookieIsolate) {
                    ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS
                } else {
                    ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS
                }
                builder.cookieBehavior(cookie).cookieBehaviorPrivateMode(cookie)
            }
        }
        // 安全浏览（钓鱼 / 恶意软件 / 潜在有害程序）：**刻意关闭**。
        //
        // 为什么关：该能力不是本地判断 —— 内核会向第三方服务发起请求。已通过解包
        // geckoview-155 的 assets/omni.ja 取出 greprefs.js 实测确认：
        //   browser.safebrowsing.id = "navclient-auto-ffox"
        //   urlclassifier.malwareTable = "goog-harmful-proto,goog-unwanted-proto,…"
        //   browser.safebrowsing.provider.mozilla.gethashURL = "https://shavar.services.mozilla.com/gethash?…"
        //   browser.safebrowsing.downloads.remote.url     = "https://sb-ssl.google.com/safebrowsing/clientreport/download?key=…"
        // 即：名单需从 Mozilla 服务端更新、命中判断走远程 gethash、下载还上报 Google。
        // 这与本应用对外承诺的「数据不出设备、不存在向第三方共享或跨境传输」直接冲突，
        // 也与「权限极简、无追踪 SDK」的产品定位不一致。
        // 取舍：放弃钓鱼/恶意软件防护，换取承诺为真（替代防线是只从官方源装扩展 + 用户自辨）。
        builder.safeBrowsing(ContentBlocking.SafeBrowsing.NONE)
        // 弹跳跟踪保护（Bounce Tracking）与 AntiTracking 名单同属「跟踪保护」范畴，
        // 因此**跟随档位**：用户选「关闭跟踪保护」即一并关闭，否则会出现
        // “我明明关了跟踪保护，它还在拦”的半生效状态。
        builder.bounceTrackingProtectionMode(
            if (prefs.trackingMode == PreferenceStore.TRACKING_OFF) {
                ContentBlocking.BounceTrackingProtectionMode.BOUNCE_TRACKING_PROTECTION_MODE_DISABLED
            } else {
                ContentBlocking.BounceTrackingProtectionMode.BOUNCE_TRACKING_PROTECTION_MODE_ENABLED
            }
        )
        // 链接跟踪参数清理：独立于档位，由「隐私与安全」里的开关统一控制
        val stripping = prefs.paramStrippingEnabled
        builder
            .queryParameterStrippingEnabled(stripping)
            .queryParameterStrippingPrivateBrowsingEnabled(stripping)
        return builder.build()
    }

    /** 延迟关闭某个已脱离 tabs 的旧会话（批量重建收尾；不做成员/活动校验） */
    private fun closeSessionLater(session: GeckoSession, delayMs: Long = 400L) {
        mainHandler.postDelayed({
            runCatching { session.close() }
        }, delayMs)
    }

    /** 断开标签缩略图引用，让 Bitmap 尽早被 GC 回收（关闭/重建标签时调用） */
    private fun releasePreview(tab: Tab) {
        tab.preview = null
    }

    // --------------------------------------------------------- 会话持久化

    /** 去抖保存会话状态：合并高频的 onHistoryStateChange，避免频繁写盘 */
    private fun scheduleSave() {
        saveTask?.let { mainHandler.removeCallbacks(it) }
        val task = Runnable { saveState() }
        saveTask = task
        mainHandler.postDelayed(task, SAVE_DEBOUNCE_MS)
    }

    /**
     * 保存当前所有普通标签的快照（URL/标题/会话状态）到本地，供进程回收后恢复。
     * 无痕标签一律不落盘；无任何普通标签时清空已存快照。
     * 用户在设置中关闭「恢复上次的标签页」时不保存，并清掉此前已存的快照。
     */
    fun saveState() {
        saveTask?.let { mainHandler.removeCallbacks(it) }
        saveTask = null
        // 已关停（正在退出）：一律不再落盘。
        //
        // 退出路径是 performExit 里的「先 saveState() 存真实快照 → markPurged() → shutdown()」，
        // 而 shutdown() 会 closeAll() 清空 tabs —— 紧随其后的 onPause → saveState 若继续走到
        // 下方「无普通标签 → 清空快照」分支，就会把刚存的那份删掉（表现：开着「恢复上次的
        // 标签页」，一次正常退出就丢掉全部标签）。
        // 此前只靠 SessionStore.markPurged 压制那一笔，但该标志会被 closeAll() 期间迟到的
        // onLocationChange 复位（见本文件 navigationDelegate 里的 clearPurged），挡不住这种
        // 时序；shutDown 只在本对象内、由主线程置位，不受内核回调影响。
        if (shutDown) return
        // 刚被"清除浏览数据"清掉快照、且尚未产生新浏览活动：跳过落盘，
        // 否则会把用户刚清掉的标签快照立刻又写回来
        if (SessionStore.shouldSkipSave()) return
        if (!prefs.sessionRestoreEnabled) {
            stateCache.clear()
            SessionStore.clear(appContext)
            return
        }
        val normalTabs = tabs.filter { !it.isPrivate }
        if (normalTabs.isEmpty()) {
            stateCache.clear()
            SessionStore.clear(appContext)
            return
        }
        val activeIndex = normalTabs.indexOfFirst { it === active }.coerceAtLeast(0)
        val snapshot = SessionStore.Snapshot(
            activeIndex = activeIndex,
            tabs = normalTabs.map { tab ->
                SessionStore.TabSnapshot(
                    url = tab.url,
                    title = tab.title,
                    sessionState = stateCache[tab.id],
                )
            },
        )
        SessionStore.write(appContext, snapshot)
    }

    /**
     * 从快照恢复标签会话（应用冷启动、无活动标签时调用）。
     * 逐个创建普通标签并 restoreState（失败则退回 loadUri 恢复 URL），
     * 最后把活动标签设为快照记录的 activeIndex。
     * @return 恢复后的活动标签；快照为空或已有标签时返回 null/当前活动标签
     */
    fun restoreSession(snapshot: SessionStore.Snapshot): Tab? {
        if (tabs.isNotEmpty()) return active
        if (snapshot.tabs.isEmpty()) return null
        var restoredActive: Tab? = null
        snapshot.tabs.forEachIndexed { i, snap ->
            val tab = newTab(isPrivate = false)
            tab.title = snap.title
            tab.url = snap.url
            val stateJson = snap.sessionState
            if (stateJson != null) {
                val ok = runCatching {
                    val state = GeckoSession.SessionState.fromString(stateJson)
                        ?: throw IllegalArgumentException("empty session state")
                    tab.session.restoreState(state)
                }.isSuccess
                if (ok) {
                    // 回填本次恢复所用的会话状态。saveState 读的就是 stateCache，而
                    // onHistoryStateChange 是**异步**才来的 —— 若不回填，「刚恢复就切后台/
                    // 被杀」会写出 sessionState = null 的快照，下次冷启动只能退回 loadUri，
                    // **前进/后退整个历史栈丢失**（表现为「恢复后回不到上一页」）。
                    // 这里写入的正是发起 restoreState 的那份 JSON，随后即便被
                    // onHistoryStateChange 覆盖，也只是覆盖成同一状态的更新版本。
                    stateCache[tab.id] = stateJson
                } else {
                    snap.url?.let { tab.session.loadUri(it) }
                }
            } else {
                snap.url?.let { tab.session.loadUri(it) }
            }
            if (i == snapshot.activeIndex) restoredActive = tab
        }
        active = restoredActive ?: tabs.firstOrNull()
        return active
    }

    private fun Boolean.toUserAgentMode(): Int =
        if (this) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
        else GeckoSessionSettings.USER_AGENT_MODE_MOBILE

    private fun Boolean.toViewportMode(): Int =
        if (this) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
        else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
}

/** 会话状态去抖保存间隔（毫秒） */
private const val SAVE_DEBOUNCE_MS = 500L
