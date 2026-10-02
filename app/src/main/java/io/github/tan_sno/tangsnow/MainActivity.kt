package io.github.tan_sno.tangsnow

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.tan_sno.tangsnow.browser.BrowserSessionManager
import io.github.tan_sno.tangsnow.browser.Tab
import io.github.tan_sno.tangsnow.browser.TabEvents
import io.github.tan_sno.tangsnow.data.repo.BookmarkRepo
import io.github.tan_sno.tangsnow.data.ClearDataUseCase
import io.github.tan_sno.tangsnow.data.ConsentGate
import io.github.tan_sno.tangsnow.data.repo.DownloadRepo
import io.github.tan_sno.tangsnow.data.EngineIcons
import io.github.tan_sno.tangsnow.data.repo.HistoryRepo
import io.github.tan_sno.tangsnow.data.PreferenceStore
import io.github.tan_sno.tangsnow.data.SearchEngines
import io.github.tan_sno.tangsnow.data.SessionStore
import io.github.tan_sno.tangsnow.data.ThemeController
import io.github.tan_sno.tangsnow.BrowserOpener
import io.github.tan_sno.tangsnow.databinding.ActivityMainBinding
import io.github.tan_sno.tangsnow.extension.ExtensionPrompts
import io.github.tan_sno.tangsnow.ui.DialogTracker
import io.github.tan_sno.tangsnow.ui.HomeTilesAdapter
import io.github.tan_sno.tangsnow.ui.TabsAdapter
import io.github.tan_sno.tangsnow.ui.addSheetDivider
import io.github.tan_sno.tangsnow.ui.makeSheetActionCell
import io.github.tan_sno.tangsnow.ui.makeSheetLibraryRow
import io.github.tan_sno.tangsnow.ui.makeSheetRow
import io.github.tan_sno.tangsnow.ui.makeSheetTextRow
import io.github.tan_sno.tangsnow.ui.makeThinDivider
import io.github.tan_sno.tangsnow.ui.showSelectionPopup
import io.github.tan_sno.tangsnow.util.HomeImageFile
import io.github.tan_sno.tangsnow.util.SecureScreen
import io.github.tan_sno.tangsnow.util.UrlUtils
import io.github.tan_sno.tangsnow.util.dp
import io.github.tan_sno.tangsnow.data.repo.ApplicationScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.WebResponse
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/**
 * 棠雪浏览器主界面
 *  - 顶部工具栏常驻：快捷搜索引擎键(官方图标+字)|地址栏 / 扫码键
 *  - 底部工具栏常驻：收藏/分享/标签页/更多（全屏时两栏隐藏）
 *  - 首页支持「定制主页」：背景风格 + 快捷方式网格
 *
 *  会话管理器是应用级单例：Activity 因主题切换/系统回收而重建时，标签页保持不变。
 */
// `ACCESS_LOCAL_NETWORK` —— Android 17 引入的运行时权限。刻意写字符串字面量而不是
// `android.Manifest.permission.ACCESS_LOCAL_NETWORK`：后者只在 compileSdk ≥ 37 的 SDK 里存在，
// 写字面量可避免将来调整 compileSdk 时编译失败。
// （用行注释而非 KDoc：紧贴在类 KDoc 之后会形成「两个连续 KDoc」，落单的那个不进文档。）
private const val PERM_ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

/**
 * [android.app.Activity.onSaveInstanceState] 里存放「等待局域网授权时被暂缓的导航目标」的键。
 *
 * 抽成常量而不是在存/取两处各写一遍字面量：键名写法一多，改一处漏一处就是**静默失效**
 * ——存了读不到 ⇒ 授权回调那侧拿到 null，导航目标凭空消失，且不会有任何报错。
 */
private const val KEY_PENDING_LOCAL_NETWORK_URL = "pending_local_network_url"

class MainActivity : AppCompatActivity(), ExtensionPrompts.ExtensionUi {

    internal lateinit var binding: ActivityMainBinding
    internal lateinit var prefs: PreferenceStore
    internal lateinit var sessionManager: BrowserSessionManager
    internal lateinit var tabsAdapter: TabsAdapter
    private lateinit var homeTilesAdapter: HomeTilesAdapter

    // 从 MainActivity 拆出的职责控制器（地址栏联想 / 页内查找 / 标签缩略图）
    private lateinit var suggestionsController: SuggestionsController
    private lateinit var findBarController: FindBarController
    private lateinit var tabPreviewController: TabPreviewController

    internal var homeVisible = true
        private set
    /** 画中画（PiP）进行中：隐藏框架只留网页画面，退出自动恢复 */
    private var pipActive = false
    /**
     * 本实例的窗口是否**在屏幕上** —— 由 `onStart..onStop` 维护。
     *
     * 为什么不用 `onResume..onPause`（2026-10-02 外部审查 P2-6）：**分屏/多窗口下失焦的那一侧
     * 只走到 `onPause`**（生命周期停在 STARTED），窗口仍在屏幕上、用户看得见。按 onResume 判可见性
     * 会把那一侧当成"后台" ⇒ 网页弹窗被自动 dismiss、权限被静默拒，用户明明看得见却什么也没弹。
     * PiP 同理（系统必走 onPause 而窗口仍可见），故 [pipActive] 那一维继续保留。
     */
    private var startedVisible = false
    private val topBarH get() = dp(70)
    private val bottomBarH get() = dp(78)

    /**
     * 把「宿主可见性」的两个来源（常规可见、PiP）收敛成**一处**下发。
     *
     * 为什么必须收敛而不是在各个回调里各自 set：PiP 的进入/退出与 onResume/onPause
     * 的相对时序在不同路径（返回键退出、展开全屏、直接回桌面）下并不一致，
     * 分散赋值总有一条路径会把可见性留在错误的状态。凡可见性变化一律走这里。
     */
    private fun syncHostVisible() {
        if (::sessionManager.isInitialized) {
            sessionManager.setHostVisible(hostVisibleNow())
        }
    }

    /** 此刻窗口是否在屏幕上（常规可见、分屏失焦、或 PiP）。弹窗类副作用（下载确认等）据此决定建不建窗 */
    private fun hostVisibleNow(): Boolean = startedVisible || pipActive

    /** 本应用主动写剪贴板的时间戳（用于区分「外部静默写入」与「用户主动复制」） */
    private var selfClipboardWriteAt = 0L

    /**
     * 本次前台周期是否已提示过剪贴板变化。
     *
     * 为什么需要：系统剪贴板变化是**全局事件**，无法区分来源应用。分屏、悬浮窗、
     * 别的应用复制文本都会触发这里。若每次都弹提示，用户会被无意义地反复打断
     * （早期版本正是如此）。因此限定为「应用处于前台且持有窗口焦点」时、每个前台
     * 周期最多提示一次，做到既不漏掉“页面悄悄写剪贴板”，也不把别人的复制算成网页行为。
     */
    private var clipboardNotifiedThisForeground = false

    /** 首页返回键上一次按下的时间戳（双击退出用） */
    private var lastBackPressAt = 0L

    /** 监听系统剪贴板变化：网页 JS 静默写入时提醒用户，并可一键清除 */
    private val clipboardChangedListener = ClipboardManager.OnPrimaryClipChangedListener {
        onClipboardChanged()
    }

    /** 自定义主页背景的内存缓存（解码 + cover 裁切只做一次） */
    private var homeImageCacheKey: String? = null
    private var homeImageCache: android.graphics.Bitmap? = null
    /** 上次提交给首页瓦片适配器的快捷方式，用于避免每次 onResume 都整表重绑 */
    private var lastHomeShortcuts: List<io.github.tan_sno.tangsnow.data.HomeShortcut>? = null

    /** 历史去重节流：最近一次写入的 URL 与时间 */
    private var lastHistoryUrl: String? = null
    private var lastHistoryAt: Long = 0L

    /**
     * 与 [lastHistoryUrl] 配套：落库时**实际用到**的标题。
     * 供 [backfillHistoryTitle] 判断「标题是否真的变了」，避免每次加载都多写一次库。
     */
    private var lastHistoryTitle: String? = null

    /** 收藏图标查询的单调序号：丢弃过期 DB 查询的返回，避免快速导航时图标被写回旧态 */
    private var bookmarkQuerySeq = 0

    /**
     * 本实例曾被**另一个 MainActivity 实例**压到后台 ⇒ 活动会话的显示被它接管。
     *
     * 只记在**自己身上**（在 [onStop] 记、在 [onResume] 消费）—— 不跨实例传信号。
     * 理由与踩坑记录见 `SessionManager.hasOtherHost` 的注释：进程级一次性标志会被**置位者自己**消费掉，
     * 导致真正需要的那个实例永远读不到。
     */
    private var coveredByOtherHost = false

    // ------------------------------------------------------------- 网页弹窗 / 站点权限

    /**
     * 网页 `<input type=file>` 单选。
     *
     * 用 `OpenDocument`（契约收 **MIME 数组**）而不是 `GetContent`（只收单个 MIME 字符串）：
     * 页面把 `accept` 写成多个 MIME（如图片 + PDF）时，旧实现只能把**第一个**交给选择器，
     * 用户根本选不到后面的类型 —— 而多选那条路本来就传整个数组，两条路不对称。
     *
     * ⚠️ 注释里**不要**写 MIME 通配符的字面量（图片类型斜杠星号）：Kotlin 的块注释**可嵌套**，
     * 那个斜杠星号会开启嵌套注释、把后续代码整段吞掉（实测报 "Unclosed comment"）。
     */
    private val pickSingleFile =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            webPrompts.onPickedFiles(uri?.let(::listOf))
        }

    /** 网页 <input type=file multiple> 多选 */
    private val pickMultiFile =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            webPrompts.onPickedFiles(uris)
        }

    /** 站点摄像头/麦克风所需的 Android 运行时权限 */
    private val requestAndroidPermissions =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            webPrompts.onAndroidPermissionsResult(grants.values.all { it })
        }

    /**
     * 本地网络访问权限（`ACCESS_LOCAL_NETWORK`）。
     *
     * Android 17（API 37）起，`targetSdk ≥ 37` 的应用访问局域网必须有它，否则连接会被
     * 内核直接拦掉（TCP 超时 / UDP 报 EPERM）—— 表现就是浏览器打不开路由器 / NAS /
     * 打印机的管理页，而且没有任何提示。授权成功后自动续跑那次被暂缓的导航。
     *
     * ⚠️ 本 launcher 在**属性初始化**（构造期）就注册了，早于 `onCreate` —— 于是审查会问
     *    「待决结果会不会在注册瞬间同步派发、抢在 onCreate 恢复 `pendingLocalNetworkUrl` 之前」。
     *    实测不会（本机 sources jar：androidx.activity 1.8.0 Java / 1.13.0 Kotlin 均为同一形态；
     *    2026-09-26 取证）：
     *    待决结果是在 `Lifecycle.Event.ON_START` 的观察者里投递的，而 ON_START 必然晚于 onCreate
     *    ⇒ onCreate 内恢复即已足够，**无需**把恢复提到 `super.onCreate()` 之后（那样也挡不住
     *    "注册瞬间派发"，因为注册发生在构造期）。
     */
    private val requestLocalNetworkAccess =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            localNetworkPromptInFlight = false
            val pending = pendingLocalNetworkUrl
            val origin = pendingLocalNetworkTab
            pendingLocalNetworkUrl = null
            pendingLocalNetworkTab = null
            if (granted && !pending.isNullOrBlank()) {
                // 打回**发起标签**（C1）：跨重建后标签引用已失效（isAlive=false）
                // ⇒ 退回当前活动标签 —— 那时标签集合刚从快照重建，活动标签就是用户看到的那个
                val target = origin?.takeIf { sessionManager.isAlive(it) }
                    ?: sessionManager.activeTab
                target?.let { loadInTab(it, pending) }
            } else if (!granted) {
                // 用户 deny（或被系统直接回绝）时给一次说明 —— 不给的话表现就是
                // 「点开局域网地址什么都不发生」，与本次改动的初衷（别静默失败）相悖。
                // 每个 Activity 实例只提示一次：否则在局域网页面上反复操作会持续打扰。
                if (!localNetworkDeniedNotified) {
                    localNetworkDeniedNotified = true
                    toast(R.string.toast_local_network_denied)
                }
            }
        }

    /**
     * 因等待本地网络权限而暂缓的导航：**发起标签**与目标地址（授权后自动续跑；只由 [loadInTab] 写入）。
     *
     * ⚠️ 必须记下**发起标签**（C1）：授权是异步的，期间用户完全可能切到别的标签；若授权后灌进
     * 「当时的活动标签」，就会把 A 标签请求的局域网地址打开在 B 标签里 —— 写入时发起者
     * 本就是活动标签，竞态发生在**写入之后、授权之前**，只判「写入时是不是活动标签」拦不住它。
     *
     * 跨重建时标签集合整体重建、引用必然失效（[sessionManager.isAlive] 为 false）
     * ⇒ 那时只恢复 URL、由恢复后的活动标签接手（见 [onSaveInstanceState] 与 onCreate 恢复处）。
     *
     * 后到的导航**覆盖**先到的（最后写入者胜）：提示框弹出期间地址栏已经显示新输入的目标，
     * 授权后加载旧目标反而与所见不符。
     */
    private var pendingLocalNetworkTab: Tab? = null
    private var pendingLocalNetworkUrl: String? = null

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 等待局域网授权的导航目标跨重建保留：授权回调发生在**重建后的新实例**上，
        // 不存的话旋转/折叠后授权完成却丢失导航目标
        pendingLocalNetworkUrl?.let {
            outState.putString(KEY_PENDING_LOCAL_NETWORK_URL, it)
        }
    }

    /** 权限请求进行中：同一个 launcher 不能并发 launch（第二次会抛异常），据此去重 */
    private var localNetworkPromptInFlight = false

    /** 本次界面生命周期内是否已就「无法访问局域网」提示过（避免反复打扰） */
    private var localNetworkDeniedNotified = false

    private lateinit var webPrompts: io.github.tan_sno.tangsnow.ui.WebPrompts

    /**
     * 本页展示中的对话框（目前是扩展弹窗会话）。
     *
     * 为什么必须纳管：未在销毁前收掉的对话框会被框架**强摘窗口**并打 `WindowLeaked`，
     * 而框架摘窗**不触发** `OnDismissListener` ⇒ [showPopup] 里关闭时要做的
     * `releaseSession()` 永不执行，弹窗会话就此泄漏（见 DialogTracker 的类注释）。
     * 收口在 [onDestroy]。
     */
    private val dialogs = DialogTracker()

    /**
     * 历史标题补写。
     *
     * 历史行是在 `onLocationChanged` 落库的，而那一句读的是 `tab.title` —— 内核的标题事件
     * （`ContentDelegate.onTitleChange`）与导航提交**谁先谁后没有文档承诺**（`omni.ja` 是内核资源包，
     * 证明不了 Java 侧委托的投递顺序）。若标题确实晚于提交到达，那一行记下的就是**上一页的标题**，
     * 且此后没有任何路径回写。
     *
     * 为什么挂在 `onPageStop` 而不是 `onTitleChanged`：
     *  - `onPageStop` 时 `tab.url` 与 `tab.title` 都已是**本次文档**的最终值，两者天然配对；
     *  - `onTitleChanged` 会在标题每次变化时触发，而那时 `tab.url` 属于哪一页并不确定
     *    （标题事件早于导航提交时，`tab.url` 还是**上一页**的）—— 照它回写会把新标题写到旧 URL 上，
     *    比现状更糟。
     *
     * 三道护栏（缺一都会误伤，尤其第一道）：
     *  ① 只补**刚刚记录过的那一条**（URL 相等）—— 否则后台标签（邮件 / 聊天站）频繁改标题会把
     *     历史行反复顶到最前，那是比「标题错一页」更明显的回归；
     *  ② 标题必须真的变了（与落库时用的标题不同）—— 避免每次加载都多写一次库；
     *  ③ 无痕 / 空白页不写，与 `onLocationChanged` 同口径。
     *
     * 副作用有界：`HistoryRepo.add` 是按 URL 的 upsert，命中已存在的行只刷新 `visited_at` 与标题，
     * 不会新增历史行。
     */
    private fun backfillHistoryTitle(tab: Tab) {
        if (!sessionManager.isAlive(tab)) return
        if (prefs.privateMode || tab.isPrivate) return
        val url = tab.url
        if (url.isNullOrBlank() || url.startsWith("about:")) return
        if (url != lastHistoryUrl) return
        val title = tab.title
        if (title.isBlank() || title == lastHistoryTitle) return
        lastHistoryTitle = title
        lifecycleScope.launch { HistoryRepo.add(url, title) }
    }

    /**
     * 把界面层的四个处理器挂到（**进程级单例**）会话管理器上。
     *
     * 为什么抽成一处并在 `onCreate` 与 `onResume` **都**调用（幂等）：管理器是应用级单例，而处理器
     * 引用由**具体的 Activity 实例**持有 —— 双实例场景（singleTop + 外部深链/分享）下后创建者会覆盖
     * 它们，先创建者必须在重新可见时无条件夺回，否则地址栏 / 进度 / 权限回调全部失联。
     * 配套的另一半在 [onDestroy]：那里按**引用比对**解绑，绝不无条件置空（置空会摘掉另一个实例的引用）。
     */
    private fun bindSessionHandlers() {
        if (!::sessionManager.isInitialized) return
        sessionManager.events = tabEvents
        sessionManager.selectionHandler = selectionHandlerImpl
        sessionManager.promptHandler = webPrompts
        sessionManager.permissionHandler = webPrompts
    }

    /** 主界面侧扩展安装确认委托（AMO 页面「添加到 Firefox」弹窗），与扩展页共用实现 */
    private var extPromptDelegate: org.mozilla.geckoview.WebExtensionController.PromptDelegate? = null

    /**
     * 挂载主界面的扩展 PromptDelegate：扩展页打开时会覆盖为自己的实例，
     * 因此每次 onResume 都重新挂（幂等）；销毁时按引用比对只解绑自己的。
     */
    private fun attachExtensionPromptDelegate() {
        val controller = GeckoHolder.runtime?.webExtensionController ?: return
        if (controller.promptDelegate === extPromptDelegate && extPromptDelegate != null) return
        val delegate = io.github.tan_sno.tangsnow.extension.ExtensionPrompts.createDelegate(this)
        extPromptDelegate = delegate
        runCatching { controller.promptDelegate = delegate }
    }

    private fun releaseExtensionPromptDelegate() {
        val delegate = extPromptDelegate ?: return
        extPromptDelegate = null
        runCatching {
            val controller = GeckoHolder.runtime?.webExtensionController ?: return@runCatching
            if (controller.promptDelegate === delegate) controller.setPromptDelegate(null)
        }
    }

    // ------------------------------------------------------------- 扩展 UI 宿主（ExtensionPrompts.ExtensionUi）

    /**
     * 扩展弹窗（browser_action 默认弹窗）展示：用独立 Dialog 承载，不覆盖正在浏览的主标签。
     * session 已由内核 open 并加载弹窗内容（见 ExtensionPrompts.openPopupSession），这里只负责
     * 把它挂到一个可见的 GeckoView 上；关闭时 `releaseSession()` **解绑** GeckoView 与会话，
     * 避免 GeckoView 继续持有它。
     * ⚠️ 解绑**不等于**关闭会话：releaseSession 之后会话本身仍是 open 状态。
     * 进程退出时的统一收口是 `SessionManager.closeAll()`（只遍历 tabs）——
     * 扩展弹窗会话的显式 `close()` 属内核契约风险项，是施工计划里待真机验证后补的第 2 步，
     * 本注释随该步落地时同步更新（在那之前，不许把「释放绑定」写成「释放会话」）。
     */
    override fun showPopup(session: GeckoSession) {
        if (isFinishing || isDestroyed) return
        val popupView = GeckoView(this)
        popupView.setSession(session)
        val dialog = AlertDialog.Builder(this)
            .setNegativeButton(R.string.extension_popup_close, null)
            .setOnDismissListener { runCatching { popupView.releaseSession() } }
            .create()
        dialog.setView(popupView)
        // 纳入纳管：**不能**只靠 OnDismissListener —— 宿主销毁时框架强摘窗口并不触发它，
        // releaseSession() 就漏了。cancelAll() 走的是 cancel() → dismissDialog()，
        // 上面的监听器会正常触发，会话得以及时释放。
        dialogs.track(dialog)
    }

    /**
     * tabs.create 回交内核：让主界面切到该标签并展示，返回其会话供内核加载。
     * 返回 null 表示界面已不可用：内核仍按返回会话加载，只是暂不显示。
     */
    override fun focusExtensionTab(tab: Tab): GeckoSession? {
        if (isFinishing || isDestroyed) return null
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                sessionManager.switchTo(tab)
                binding.geckoView.setSession(tab.session)
                syncViewWithTab(tab)
                hideTabsPanel()
            }
        }
        return tab.session
    }

    /** 打开扩展选项页（options_ui）：统一走新标签，与“管理页”入口口径一致 */
    override fun openOptionsPage(url: String?) {
        if (url.isNullOrBlank() || isFinishing || isDestroyed) return
        BrowserOpener.openNewTab(this, url)
    }

    // ------------------------------------------------------------- 扫码

    private val qrLauncher: ActivityResultLauncher<ScanOptions> =
        registerForActivityResult(ScanContract()) { result ->
            val content = result.contents ?: return@registerForActivityResult
            handleScanResult(content)
        }

    /** 会话事件 → 界面更新；只有活动标签才驱动 UI，历史记录在这里统一落库 */
    private val tabEvents = object : TabEvents {
        private fun alive() = !(isFinishing || isDestroyed)

        override fun onTitleChanged(tab: Tab, title: String) {
            if (alive() && sessionManager.activeTab === tab) updateBookmarkIcon()
        }

        override fun onLocationChanged(tab: Tab, url: String?) {
            if (!alive()) return
            // 已释放标签的迟到事件：不再影响地址栏、历史与崩溃日志的"最近访问站点"
            if (!sessionManager.isAlive(tab)) return
            if (!url.isNullOrBlank()) {
                // 供本地崩溃日志记录“最近访问站点”（仅域名），便于复现定位；不上传。
                // ⚠️ 口径必须与下方写历史那一处（:363）**完全一致**：两条通道都属「浏览痕迹」，
                // 一处守卫一处不守卫，就等于把无痕会话的域名留在了崩溃日志里（见 CrashLogger.clearHost）。
                if (!prefs.privateMode && !tab.isPrivate) {
                    io.github.tan_sno.tangsnow.util.CrashLogger.noteVisit(url)
                }
            }
            // 页面内跳转到的局域网地址（不是 loadInTab 发起的，那里拦不到）：同样按需申请权限。
            // 这里**既不挂起也不续跑**导航 —— 内核此刻已经在加载了，让用户授权后自行刷新即可，
            // 免得与进行中的加载抢标签。目的只是别让「连不上」变成毫无解释的静默失败。
            // 只对**当前活动标签**的导航请求：后台标签的跳转不该弹窗打断用户。
            if (!url.isNullOrBlank() && sessionManager.activeTab === tab && needsLocalNetworkGrant(url)) {
                requestLocalNetworkPromptIfIdle()
            }
            if (sessionManager.activeTab === tab && url != null) {
                // 地址栏正在输入时不被页面跳转打断；搜索结果页显示关键词而非完整 URL
                if (!binding.toolbar.addressBar.hasFocus()) {
                    val display = if (url.startsWith("about:")) ""
                    else SearchEngines.displayForUrl(url, SearchEngines.all(prefs)) ?: url
                    binding.toolbar.addressBar.setText(display)
                }
                updateBookmarkIcon()
                updateNavCells()
            }
            if (!url.isNullOrBlank() && !prefs.privateMode && !tab.isPrivate) {
                // 合并密集的重定向/同 URL 跳转：极短时间内同一地址只落库一次。
                // ⚠️ 这里**不再**看「是否刷新」：内核 157 起该回调的第 4 参语义是 hasUserGesture
                //（Mozilla 官方 javadoc 实证，见 TabEvents.onLocationChanged 的注释），继续当 isReload 用
                // 会把地址栏输入 / 应用内跳转 / 服务端重定向整类导航漏记；去重改由下面的同 URL + 时间窗负责。
                val now = SystemClock.elapsedRealtime()
                if (url != lastHistoryUrl || now - lastHistoryAt > 1500L) {
                    lastHistoryUrl = url
                    lastHistoryAt = now
                    // 记下落库时实际用的标题：此刻的 `tab.title` 未必已是**本页**的标题
                    //（内核的标题事件可能晚于导航提交到达），onPageStop 会据此补写一次
                    lastHistoryTitle = tab.title
                    lifecycleScope.launch { HistoryRepo.add(url, tab.title) }
                }
            }
        }

        override fun onFullScreen(tab: Tab, full: Boolean) {
            if (alive() && sessionManager.activeTab === tab) refreshChrome()
        }

        override fun onPageStart(tab: Tab) {
            if (alive() && sessionManager.activeTab === tab) {
                setReloadButtonLoading(true)
                showProgress(5)
                // 新页面尚未上报安全状态前，不保留上一页的结论（避免"锁"停留在 http 页上）
                hideSecurityIndicator()
            }
        }

        override fun onPageProgress(tab: Tab, progress: Int) {
            if (alive() && sessionManager.activeTab === tab) showProgress(progress)
        }

        override fun onPageStop(tab: Tab) {
            if (alive() && sessionManager.activeTab === tab) {
                setReloadButtonLoading(false)
                hideProgress()
                // 页面加载完成且当前可见时刷新缩略图
                binding.root.postDelayed({ tabPreviewController.captureCurrentPreview() }, 200)
            }
            // 加载结束 = 本次文档的 URL 与标题都已定型，是补写历史标题的唯一可靠时机
            if (alive()) backfillHistoryTitle(tab)
        }

        override fun onExternalResponse(tab: Tab, response: WebResponse) {
            // 已释放标签的响应不再处理：否则会为已经消失的标签弹出下载确认框。
            // 带上发起下载的页面地址：系统下载器回退路径要拿它当 Referer（见 startDownload）
            if (alive() && sessionManager.isAlive(tab)) handleDownload(response, tab.url)
        }

        override fun onOpenInCurrentTab(uri: String) {
            // window.open / target=_blank：当前标签页直接加载目标 URL（移动端惯例），
            // 不新开标签、也不回主页
            if (!alive()) return
            // window.open() 无参/空串时没有可导航的目标，忽略即可（新窗口本就被拒）
            if (uri.isBlank()) return
            // 安全闸门 + 外部协议分流（P2-3）。这条链最终调用 `session.loadUri()`，而那是**应用发起的
            // 直接导航**（isDirectNavigation=true）⇒ 内核侧的特权 scheme 闸门（SessionManager.onLoadRequest）
            // 会放行 `file:` / `moz-extension:`。不在这里收口，恶意页面就能用
            // `window.open('file:///data/data/<pkg>/files/session_store.json')` 把应用私有文件
            // （会话快照、崩溃日志）渲染到屏幕上。
            // 三分法（与内核侧共用同一份判定，避免两处漂移）：
            //  · 网页导航 → 照常在当前标签加载；
            //  · file:/moz-extension:/javascript: → **静默丢弃**（既不放行、也**不给提示** ——
            //    否则页面循环 window.open 就能拿 toast 刷屏）；
            //  · 其余（mailto/tel/market/intent/geo…）→ 交回既有的「外部打开」实现，
            //    与普通链接同款走系统 Intent。**不能一律丢弃**：`target="_blank"` 的 mailto
            //    链接原本能唤起邮件应用，误挡即功能回归。
            val scheme = UrlUtils.schemeOf(uri)
            if (UrlUtils.isNeverWebContentScheme(scheme)) return
            if (!UrlUtils.isWebNavigationScheme(scheme)) {
                openExternalUrl(uri)
                return
            }
            val tab = sessionManager.activeTab ?: return
            binding.toolbar.addressBar.setText(uri)
            // 走统一入口：让局域网地址也触发按需的权限申请。
            // 此前直连 loadUri，若权限未授予会被内核拦掉、且很可能不产生
            // onLocationChanged（弹窗兜底也落空），用户只会看到「点了没反应」。
            loadInTab(tab, uri)
            showBrowser()
        }

        override fun onCloseRequest(tab: Tab) {
            // window.close()：关闭发起请求的标签（不再打开标签面板）
            // 已释放标签的迟到 window.close() 不处理，避免误触活动标签的收尾逻辑
            if (alive() && sessionManager.isAlive(tab)) closeTab(tab, showPanel = false)
        }

        override fun onNavStateChanged(tab: Tab) {
            // 前进/后退可用性变化：立即刷新导航格状态，避免滞后一拍
            if (alive() && sessionManager.activeTab === tab) updateNavCells()
        }

        override fun onSecurityChange(tab: Tab, secure: Boolean, mixedActiveLoaded: Boolean) {
            if (!alive() || sessionManager.activeTab !== tab) return
            // about: / 空白页没有「连接」可言，不显示指示；否则会给出误导性的“不安全”
            val url = tab.url.orEmpty()
            if (url.isBlank() || url.startsWith("about:")) {
                hideSecurityIndicator()
                return
            }
            // 混合内容只有「已加载（未被内核拦掉）」才算真风险
            val ok = secure && !mixedActiveLoaded
            binding.toolbar.securityIndicator.apply {
                isVisible = true
                setImageResource(if (ok) R.drawable.ic_lock_secure else R.drawable.ic_warning_insecure)
                imageTintList = ColorStateList.valueOf(getColor(if (ok) R.color.accent else R.color.error))
                contentDescription =
                    getString(if (ok) R.string.security_secure_desc else R.string.security_insecure_desc)
            }
        }

        override fun onMediaStateChanged(tab: Tab, playing: Boolean) {
            // 媒体起止时重算画中画参数：Android 12+ 靠 setAutoEnterEnabled 让系统
            // 在返回桌面时接管转场，必须在播放**开始**时就位
            if (alive() && sessionManager.activeTab === tab) updatePipParams()
        }

        override fun onCrash(tab: Tab) {
            // 内容进程崩溃：内核会自动重建会话内容，这里给可见提示并显式重载当前页，
            // 让用户第一时间知道页面出错而非卡在白屏
            if (!alive() || !sessionManager.isAlive(tab)) return
            toast(R.string.toast_page_crashed)
            runCatching { tab.session.reload() }
        }

        override fun onExternalProtocol(url: String) {
            // 非 http(s) 的“外部协议”跳转（intent://、market://、mailto:、tel:、geo: 等）：
            // 内核侧本次导航已被管理器拒绝，这里交给统一的「外部打开」实现处理
            // （含 intent:// 解析、回退地址与失败反馈，见 openExternalUrl）。
            if (!alive()) return
            openExternalUrl(url)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = PreferenceStore(this)
        // 崩溃日志**只在通过同意门禁之后**才安装。原先装在 Application.onCreate，于是"同意页
        // 还没走完（用户点不同意、或同意前就崩）"也会往盘里写一条日志 —— 与同意页写的
        // "同意前不做任何数据处理"字面冲突。放在这里：主界面只在同意后才创建。
        if (!ConsentGate.needsConsent(prefs)) {
            io.github.tan_sno.tangsnow.util.CrashLogger.install(applicationContext)
        }
        // 防截屏（可选，默认关闭）：在设置内容视图**之前**应用，避免首帧就被系统拍进最近任务快照
        SecureScreen.apply(this, prefs)
        // 同意门禁：首次安装 / 政策版本更新后，任何进入浏览器的路径都先回到同意页。
        // 在同意前绝不初始化 GeckoRuntime / 会话 / 网页内容。
        if (ConsentGate.needsConsent(prefs)) {
            val go = Intent(this, ConsentActivity::class.java).apply {
                flags = BrowserOpener.FLAGS_BRING_TO_FRONT
            }
            // 外部链接（ACTION_VIEW）与应用内跳转（EXTRA_OPEN_URL）都要随门禁转发
            externalUrl(intent)?.let { go.putExtra(BrowserOpener.EXTRA_OPEN_URL, it) }
            // SEND 分享同样不能在门禁处断链：提取文本里的 http(s) 链接随门禁转发
            // （ ConsentActivity 会原样转交回主界面，同意后照常打开）
            if (intent?.action == Intent.ACTION_SEND) {
                intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?.let { UrlUtils.extractUrlFromText(it) }
                    ?.let { go.putExtra(BrowserOpener.EXTRA_OPEN_URL, it) }
            }
            if (intent?.getBooleanExtra(BrowserOpener.EXTRA_OPEN_NEW_TAB, false) == true) {
                go.putExtra(BrowserOpener.EXTRA_OPEN_NEW_TAB, true)
            }
            val libTab = intent?.getIntExtra(BrowserOpener.EXTRA_LIBRARY_TAB, -1) ?: -1
            if (libTab in 0..2) go.putExtra(BrowserOpener.EXTRA_LIBRARY_TAB, libTab)
            startActivity(go)
            finish()
            return
        }

        enableEdgeToEdge()
        ThemeController.apply(prefs.theme)
        sessionManager = BrowserSessionManager.get(applicationContext, prefs)
        webPrompts = io.github.tan_sno.tangsnow.ui.WebPrompts(
            this, pickSingleFile, pickMultiFile, requestAndroidPermissions,
        )
        bindSessionHandlers()
        // 记一个前台宿主：关停判据是「本进程已无任何 MainActivity 实例」，见 hostDetached 的说明。
        // （「显示被接管」不再在这里置位 —— 那是**后来者**的视角，而它自己会立刻消费掉该标志；
        //   改由被压下去的那个实例在 onStop 自己记账，见 coveredByOtherHost。）
        sessionManager.hostAttached()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        suggestionsController = SuggestionsController(this)
        findBarController = FindBarController(this)
        tabPreviewController = TabPreviewController(this)

        setupHomeTiles()
        setupTabsRecycler()
        setupEngineSwitcher()
        setupToolbar()
        applyToolbarPosition()
        findBarController.resolve()
        suggestionsController.resolve()
        setupBottomBar()
        setupPrivateButton()
        buildMoreSheet()
        setupBackPress()

        val active = sessionManager.activeTab
        if (active == null) {
            // 冷启动的两条路径（2026-10-01 CR-012 起）：
            //  ① **快路径**（绝大多数）：应用启动时已在后台预读（`SessionStore.preload`），
            //     到这一刻通常已就绪 ⇒ 与原先完全一致：首帧就是恢复好的标签。
            //  ② **慢路径**（极慢盘 / 串行队列被在途写占住）：预读没赶上 ⇒ **不再在主线程读盘**
            //     （Mozilla 官方把"启动期主线程 IO"列为禁止项），改为先建空白标签 + 首页让首帧
            //     立刻可见，快照随后在 IO 线程到位后整表恢复 —— 这正是 Fenix 的实际行为。
            val snapshot = if (prefs.sessionRestoreEnabled) {
                runCatching { SessionStore.consume() }.getOrNull()
            } else null
            val restored = snapshot?.let { sessionManager.restoreSession(it) }
            if (restored != null) {
                binding.geckoView.setSession(restored.session)
                syncViewWithTab(restored)
                updateTabsBadge()
            } else {
                createTab(url = null) // 首个空白标签
                showHome()
                // 预读没赶上且开关是开的：交给后台补恢复（顺序与活动标签与快路径一致）
                if (prefs.sessionRestoreEnabled) restoreSnapshotAsync()
            }
        } else {
            // Activity 重建：恢复到原有活动标签
            binding.geckoView.setSession(active.session)
            syncViewWithTab(active)
        }
        // ⚠️ 只在**首次创建**时处理入口 intent：重建（切主题/切语言/暗色切换等，
        //    uiMode 不在 configChanges 里）会带着**同一个 intent** 走到这里 ——
        //    无条件重放会让 VIEW 深链整页重载、SEND 分享凭空多开一个标签
        //    （重放的深链页面本就已随会话快照恢复，跳过不丢内容）。
        //    进程存活期的后续入口由 onNewIntent 覆盖。
        //
        // 🔍 这里**不会**漏掉「实例已被系统回收、随后又有新请求」那种情况（外部审查提过三次，
        //    在此留痕以免重复排查）：记录被复用时，新 intent 不靠 onCreate 重放，而是由框架
        //    排队后**在 onResume 之前以 onNewIntent 投递** ——
        //    ⚠️ 下列符号名取自 **AOSP main（`services/core/java/com/android/server/wm/`）
        //    与本机 SDK 37.2 源码**、2026-09-26 取证；括号里的行号属于**当时版本**，
        //    只有符号名才是稳定锚（`ActivityTaskSupervisor` 是 R 之后才叫这个名字）。
        //    `ActivityStarter.complyActivityFlags`(CLEAR_TOP) → `deliverNewIntent`
        //    → `ActivityRecord.deliverNewIntentLocked`（非 RESUMED/PAUSED 时入 `newIntents`，
        //    该字段的注释原文即 "any pending new intents for single-top mode"）
        //    → `ActivityTaskSupervisor.realStartActivityLocked` 把它与 `r.getSavedState()`
        //    **一起**塞进 `LaunchActivityItem` → `ActivityThread.performResumeActivity` 里
        //    `deliverNewIntents` → `onNewIntent`（本类 :529 即 setIntent + handleIntent）。
        //    记录若已从任务中移除，则是**新记录**（`mIcicle` 只由 `activityStopped` 写入）
        //    ⇒ `savedInstanceState == null` ⇒ handleIntent 照跑。两条路都会处理入口，
        //    不存在「既复用记录、又把新 intent 丢掉」的第三种，故这里的判据够用。
        //
        // 跨重建恢复：等待局域网授权时被暂缓的导航目标（见 onSaveInstanceState）。
        // ⚠️ 顺序是硬要求 —— 必须在 handleIntent **之前**恢复，且只在真的存过该键时赋值：
        //    handleIntent → loadInTab 会把本次入口的局域网地址写进同一个字段，而冷启动时
        //    savedInstanceState == null；若把恢复放在它之后并无条件赋值（`= savedInstanceState?.getString(...)`），
        //    就会把刚写进去的目标**覆盖成 null** —— 表现是「从外部点开局域网地址 → 用户点
        //    「允许」→ 什么都不加载」，正是本文件 requestLocalNetworkAccess 注释里要消灭的
        //    那种静默失败（授权回调只读这个字段，它空了就什么也不做）。
        savedInstanceState?.getString(KEY_PENDING_LOCAL_NETWORK_URL)?.let {
            // 只恢复 URL、不恢复发起标签：标签集合此刻刚从快照重建，无从对应 ——
            // 授权后由恢复后的活动标签接手（见 pendingLocalNetworkTab 的注释）
            pendingLocalNetworkUrl = it
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 同步当前 intent，后续任何地方再读 getIntent() 拿到的都是最新值
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // 窗口出现在屏幕上（分屏失焦时同样成立）：权限/弹窗的「用户看不看得见」这一维以它为准
        startedVisible = true
        syncHostVisible()
    }

    override fun onResume() {
        super.onResume()
        // 无条件夺回处理器（幂等）：本实例可能刚被另一个实例压在栈下又恢复前台
        bindSessionHandlers()
        // 同一件事的另一半：后创建的实例会把活动会话挂到它自己的 GeckoView 上，本实例恢复前台时
        // 必须把画面重新挂回来 —— 否则内核在跑、屏幕却是空的。
        // 判据是**本实例自己的** coveredByOtherHost（在 onStop 记的），不是跨实例标志：
        // GeckoView 的会话是逐视图字段、类里没有视图注册表（javap 实测），后来者改的是它自己的字段，
        // 本视图的 `session` 仍指向同一会话 ⇒ 单看引用比对（`!==`）恒假，只能靠"我被压下去过"这件事。
        if (::sessionManager.isInitialized) {
            val active = sessionManager.activeTab
            val reattach = coveredByOtherHost ||
                (active != null && ::binding.isInitialized && binding.geckoView.session !== active.session)
            if (reattach && active != null && ::binding.isInitialized) {
                coveredByOtherHost = false
                binding.geckoView.setSession(active.session)
                syncViewWithTab(active)
            }
        }
        // 从「设置」返回时同步防截屏开关（开关是即时生效的窗口级标志）
        SecureScreen.apply(this, prefs)
        sessionManager.applyLiveSettings()
        applyDesktopModeIfChanged()
        attachExtensionPromptDelegate()
        // 本 Activity 是扩展 UI 宿主（弹窗 / tabs.create / 选项页）；切回前台即接管 popupHost
        ExtensionPrompts.popupHost = this
        // 重新挂扩展 Action/Tab 委托（幂等；覆盖被其他路径替换的情况）
        GeckoHolder.runtime?.webExtensionController?.let { ExtensionPrompts.mountExtensionDelegates(it, this) }
        updateEngineChipText()
        applyToolbarPosition()
        refreshHome()
        updateBookmarkIcon()
        updateTabsBadge()
        updatePrivateButton()
        updateNavCells()
        // 补弹后台挂起的下载确认（handleDownload 在不可见时不建窗，见其注释）
        flushPendingDownloadConfirms()
        // 前台监听剪贴板变化，捕捉页面静默写入（提示频率与归因见 onClipboardChanged）
        clipboardNotifiedThisForeground = false
        runCatching {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .addPrimaryClipChangedListener(clipboardChangedListener)
        }
    }

    override fun onPause() {
        super.onPause()
        // ⚠️ 这里**不**动可见性：onPause 只说明"失去焦点"，窗口可能仍在屏幕上（分屏失焦、PiP）。
        // 真正的"离开屏幕"由 onStop 判定（见 startedVisible）。
        // 进入后台前立即保存会话快照：进程可能在后台被系统回收，这是最后的落盘时机
        if (::sessionManager.isInitialized) sessionManager.saveState()
        runCatching {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .removePrimaryClipChangedListener(clipboardChangedListener)
        }
    }

    override fun onStop() {
        // 被另一个 MainActivity 实例压到后台 ⇒ 活动会话的显示归它了。**必须在这里判**：
        // 系统顺序是 `本实例.onPause → 新实例.onCreate/onStart/onResume → 本实例.onStop`，
        // 走到 onStop 时宿主计数必然 ≥2；放在 onPause 判则永远是 1（那时新实例还没创建）。
        if (::sessionManager.isInitialized && sessionManager.hasOtherHost()) {
            coveredByOtherHost = true
        }
        // 窗口离开屏幕：此后由后台标签触发的权限征询一律拒绝（不弹框、不抢焦点）
        startedVisible = false
        syncHostVisible()
        super.onStop()
    }

    override fun onDestroy() {
        // 无论退出还是重建都要解除对旧 Activity 的引用，避免回调悬空与内存泄漏。
        // ⚠️ 必须**按引用比对**逐项解绑：无条件置空会摘掉**另一个仍存活的实例**刚登记的处理器，
        // 那正是「双实例」事故里先创建的实例沦为活死状态的直接成因（另一半在 onResume 的重新挂载）。
        if (::sessionManager.isInitialized) {
            if (sessionManager.events === tabEvents) sessionManager.events = null
            if (sessionManager.selectionHandler === selectionHandlerImpl) {
                sessionManager.selectionHandler = null
            }
            if (::webPrompts.isInitialized && sessionManager.promptHandler === webPrompts) {
                sessionManager.promptHandler = null
                sessionManager.permissionHandler = null
            }
        }
        // ⚠️ 配置变更（切主题 / 切语言等）会重建 Activity，此时**不能**把挂起的弹窗应答成"拒绝"
        // （外部审查 M1）：系统对话框的结果随后会派发给新实例，而应答已被提前消耗 ⇒ 用户点了
        // "允许 / 已选文件"却被当成拒绝且零提示。与下方"下载确认"同一口径：只在真销毁时收口。
        // 但**应用内已展示的弹窗**两码事：它们随本实例一起被系统强拆，强拆不触发取消回调 ⇒
        // 那条 GeckoResult 再也没人结算（页面 JS 永久挂起 + 窗口泄漏）⇒ 两种情形都必须关它们
        // （2026-10-02 外部审查 P2-4：M1 当时把这两件事一起跳过了）。
        if (::webPrompts.isInitialized) {
            webPrompts.closeShownDialogs()
            if (!isChangingConfigurations) webPrompts.cancelPending()
        }
        // 挂起的下载确认：队列在进程级 companion，**配置变更重建**时由新实例的 onResume
        // 接手补弹，不能在这里关流；**真销毁**（返回键退出等，内核仍在后台）时必须逐个
        // 关掉，否则内核连接悬挂到超时（外部审查 M4）。
        if (!isChangingConfigurations) {
            pendingDownloadConfirms.forEach { abandonDownload(it.response) }
            pendingDownloadConfirms.clear()
        }
        // 扩展安装/权限提示同样必须在销毁前关闭并应答：否则页面侧的安装或权限请求
        // 对应的 GeckoResult 永不完成（流程永久挂起），且对话框会泄漏窗口。
        // 传入 this 只关闭本 Activity 持有的提示，不会误取消扩展页正在展示的弹窗。
        io.github.tan_sno.tangsnow.extension.ExtensionPrompts.cancelAllPending(this)
        releaseExtensionPromptDelegate()
        // 退出时清空扩展 UI 宿主并解绑 Action/Tab 委托；按引用比对只清本 Activity 设置的。
        // 这里**不**用 `runtime?.webExtensionController?.let { … }` 包住整个调用：runtime 已被
        // shutdown 时那样会连「把本 Activity 从持有方集合里摘掉」一起跳过，导致进程级集合
        // 强引用已销毁的 Activity、且集合再也回不到空集（见 unmountExtensionDelegates 的注释）。
        if (ExtensionPrompts.popupHost === this) ExtensionPrompts.popupHost = null
        ExtensionPrompts.unmountExtensionDelegates(
            GeckoHolder.runtime?.webExtensionController, this
        )
        dismissSelectionPopup()
        clipboardPermissionDialog?.dismiss()
        clipboardPermissionDialog = null
        exitConfirmDialog?.dismiss()
        exitConfirmDialog = null
        // 弹窗会话（showPopup 的对话框）必须在此收掉：框架摘窗不触发 OnDismissListener，
        // 不收就等于既不释放会话又打 WindowLeaked
        dialogs.cancelAll()
        if (::findBarController.isInitialized) findBarController.cancelPending()
        if (::suggestionsController.isInitialized) suggestionsController.cancelPending()
        // 先摘除本实例的宿主计数（**与 isFinishing 解耦**：配置变更也要减，否则计数只增不减、
        // 之后正常的「划掉任务」就再也不关停内核）；只有真正退出应用、**且本进程已无其它
        // MainActivity 实例**时才销毁会话 —— 双实例场景下先创建的实例只是被压到栈下，
        // 用户返回时还要继续用它，不能因为另一个实例结束就被连带关停。
        val lastHost = if (::sessionManager.isInitialized) sessionManager.hostDetached() else false
        // `!exiting`：performExit 一旦接管，关停的**顺序**就由它负责 —— 「退出并清除」那条路
        // 必须先等内核 `clearData` 往返结束才 shutdown（先关内核会把清理踩断，承诺就失效了）。
        // 这里抢跑就是把它踩断：用户点了「退出并清除」，界面一退、内核被拆，数据只清了一半。
        // 两条退出路径都会自己调 shutdown()（不清除的那条在 performExit 内、清除的那条在清完之后），
        // 故这里跳过不会漏关停。
        if (isFinishing && lastHost && !exiting) {
            detachActiveSession()
            sessionManager.shutdown()
        }
        super.onDestroy()
    }

    /** 内存紧张时释放可再生成的缓存（标签缩略图、主页背景图） */
    @Suppress("DEPRECATION") // API 37 起 TRIM_MEMORY_* 常量标记弃用，语义与阈值不变
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) {
            homeImageCache = null
            homeImageCacheKey = null
            if (::sessionManager.isInitialized) {
                sessionManager.tabs.forEach { it.preview = null }
                if (::tabsAdapter.isInitialized) tabsAdapter.notifyDataSetChanged()
            }
            // EngineIcons 与缓存属微小位图（≤64px），无需清空以避免无谓重解码
        }
    }

    /**
     * 用户按下 Home / 切到其它应用时自动画中画：媒体（视频/音频）播放中离开浏览器时，
     * 进入悬浮小窗继续播放（对齐 Firefox/Chrome 移动端行为）。无痕标签不触发——
     * 无痕内容不进可被系统截屏/录屏捕获的悬浮窗。
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPipIfMediaPlaying()
    }

    private fun enterPipIfMediaPlaying() {
        // 不再有 SDK_INT 版本判断：本应用 minSdk = 26，画中画所需 API（24）必然可用，
        // 原 `SDK_INT < O` 判断永远为假（lint: ObsoleteSdkInt 已确认）。真正需要判断的是
        // **设备是否具备画中画特性** —— 部分设备（含某些定制 ROM / 车机 / 电视）虽 API 达标
        // 也不支持，故下面这条特性检查才是有效闸门。
        if (!packageManager.hasSystemFeature(
                android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE
            )
        ) return
        if (pipActive || isFinishing || isDestroyed) return
        val tab = sessionManager.activeTab ?: return
        if (tab.isPrivate || !tab.mediaPlaying) return
        runCatching {
            enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().build())
        }
    }

    /**
     * 重算并下发画中画参数（Android 12+）。
     *
     * 对应 lint 的 PictureInPictureIssue：targetSdk ≥ 31 且声明 supportsPictureInPicture 时，
     * 推荐 `setAutoEnterEnabled(true)` 让系统在返回桌面时接管转场，并用 `setSourceRectHint`
     * 给出视频区域做平滑缩放——否则切后台再进 PiP 会闪烁 / 转场不跟手。
     *
     * 自动进入仅在「活动标签有媒体播放且非无痕」时开启（无痕内容不进可被系统
     * 截屏 / 录屏捕获的悬浮窗，与 [enterPipIfMediaPlaying] 判定一致）。GeckoView 不对外
     * 暴露具体视频元素矩形，故源矩形退化为网页内容整体区域。
     */
    private fun updatePipParams() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return
        val tab = sessionManager.activeTab
        val autoEnter = tab != null && !tab.isPrivate && tab.mediaPlaying
        val builder = android.app.PictureInPictureParams.Builder()
            .setAutoEnterEnabled(autoEnter)
        if (::binding.isInitialized && binding.geckoView.width > 0) {
            builder.setSourceRectHint(
                android.graphics.Rect(0, 0, binding.geckoView.width, binding.geckoView.height)
            )
        }
        runCatching { setPictureInPictureParams(builder.build()) }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipActive = isInPictureInPictureMode
        // PiP 期间窗口仍可见，宿主可见性要补回 true：否则网页弹窗（alert/confirm/HTTP 认证）
        // 会被 promptStale 的 hostVisible 判据就地拒绝 —— 用户在小窗看视频时页面的
        // 「会话已过期，请重新登录」这类框会被静默吞掉。退出 PiP 后的各种走向
        // （展开全屏 / 回桌面 / 被覆盖）由 onStart/onStop 经 startedVisible 如实接管。
        syncHostVisible()
        // PiP 时只留网页画面（隐藏顶/底栏 + 进度），恢复后自动还原
        if (::binding.isInitialized) refreshChrome()
    }

    /**
     * 从 intent 提取待打开的链接：系统默认浏览器调起走 ACTION_VIEW data，应用内走 EXTRA_OPEN_URL。
     *
     * 安全边界：MainActivity 是 exported 入口，**任何应用**都能显式调起并附带 extras，
     * 因此这里只接受 http/https 两种 scheme。被拒的包括：
     *  - `javascript:` / `data:` / `file:` —— 可把脚本或本地文件注入当前页面上下文；
     *  - `moz-extension:` —— 扩展内部页（含扩展的选项页与后台上下文）。旧实现在此放行，
     *    属最小权限原则下的越界：扩展内部页只应由应用**自己**在进程内跳转
     *    （`BrowserOpener.open` 等直接调用），不该开出一个可从外部驱动的入口。
     */
    private fun externalUrl(intent: Intent?): String? {
        val raw = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data?.toString()
            ?: intent?.getStringExtra(BrowserOpener.EXTRA_OPEN_URL)
        return raw?.trim()?.takeIf { it.isNotBlank() }?.takeIf { url ->
            val colon = url.indexOf(':')
            val scheme = if (colon > 0) url.substring(0, colon).lowercase() else ""
            scheme == "http" || scheme == "https"
        }
    }

    private fun handleIntent(intent: Intent?) {
        // 先取进程内特权通道：这是应用自己发起的跳转（含扩展的 moz-extension 选项页），
        // 外部应用无法写入它，所以此处不受下面 exported 入口的 scheme 限制。
        BrowserOpener.consumePending()?.let { (url, newTab) ->
            if (newTab) navigateInNewTab(url) else navigateToUrl(url)
            return
        }
        val url = externalUrl(intent)
        if (!url.isNullOrBlank()) {
            val newTab = intent?.getBooleanExtra(BrowserOpener.EXTRA_OPEN_NEW_TAB, false) == true
            if (newTab) navigateInNewTab(url) else navigateToUrl(url)
            return
        }
        // 分享入口（ACTION_SEND text/plain，见 Manifest）：其它应用「分享」文本进来时，
        // 提取其中的第一个 http(s) 链接开新标签。与 VIEW 走同一套 scheme 白名单的下游
        // 校验（navigateInNewTab → loadInTab 只管加载，scheme 闸门在 externalUrl 同源
        // 的 UrlUtils/内核导航策略里），纯文本分享（无链接）给明确提示、不导航。
        if (intent?.action == Intent.ACTION_SEND) {
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT)
                ?.let { UrlUtils.extractUrlFromText(it) }
            if (shared != null) {
                navigateInNewTab(shared)
            } else {
                toast(R.string.share_no_link_found)
            }
            return
        }
        val libTab = intent?.getIntExtra(BrowserOpener.EXTRA_LIBRARY_TAB, -1) ?: -1
        if (libTab in 0..2) {
            startActivity(
                Intent(this, LibraryActivity::class.java)
                    .putExtra(BrowserOpener.EXTRA_LIBRARY_TAB, libTab)
            )
        }
    }

    /**
     * 应用内请求「新标签页打开」（扩展管理/设置页等）：不覆盖正在浏览的页面，
     * 始终开在普通（非无痕）标签，避免扩展内置页在无痕下不可达。
     */
    private fun navigateInNewTab(url: String) {
        hideMoreSheet()
        val active = sessionManager.activeTab
        // 冷启动/刚进入时系统已建好一个唯一的空白标签：此时“开新标签”应复用它，
        // 避免留下多余空标签（行业惯例：首个未使用标签可被新请求接管）
        val blankSole = active != null &&
            sessionManager.tabCount == 1 &&
            !active.isPrivate &&
            (active.url.isNullOrBlank() || active.url.orEmpty().startsWith("about:"))
        if (blankSole) {
            binding.geckoView.setSession(active.session)
            loadInTab(active, url)
        } else {
            createTab(url = url, isPrivate = false)
        }
        binding.toolbar.addressBar.setText(url)
        showBrowser()
        updatePrivateButton()
        updateNavCells()
    }

    // ------------------------------------------------------------- 定制主页

    private fun setupHomeTiles() {
        homeTilesAdapter = HomeTilesAdapter(onOpen = { shortcut -> navigateToUrl(shortcut.url) })
        binding.home.homeTiles.layoutManager = GridLayoutManager(this, 4)
        binding.home.homeTiles.adapter = homeTilesAdapter
    }

    private fun refreshHome() {
        applyHomeStyle()
        // 只在快捷方式**真的变化**时才提交：submit 会触发整表重绑，而本函数在每次 onResume 都会跑
        //（每个瓦片重算首字母与底色并分配 ColorStateList）。与 applyLiveSettings 的 key 守卫同一思路。
        val shortcuts = prefs.homeShortcuts
        if (shortcuts != lastHomeShortcuts) {
            lastHomeShortcuts = shortcuts
            homeTilesAdapter.submit(shortcuts)
        }
    }

    private fun applyHomeStyle() {
        val style = prefs.homeStyle

        // 自定义图片作背景时，品牌区（标记 + 品牌名 + 标语）整体隐藏 —— 背景是用户
        // 自己的画面，再压一行 46sp 的品牌名上去既遮挡也不得体；其余风格保持显示。
        //
        // 注意：这句话**以前只写在下面 STYLE_IMAGE 分支的注释里，代码并没有实现**，
        // 于是品牌名一直浮在自定义图片上（注释承诺了、实现没跟上）。现在收到这里统一
        // 裁决，不再分散在各分支里，避免同一个意图两处各说一遍又各自走偏。
        val onImage = style == PreferenceStore.STYLE_IMAGE
        binding.home.homeBrand.isVisible = !onImage
        // 图片上的文字对比度不可预知（背景是任意照片），故让快捷方式标签带上底色
        homeTilesAdapter.setLabelOnImage(onImage)

        when (style) {
            PreferenceStore.STYLE_MIST -> {
                binding.home.root.setBackgroundResource(R.drawable.bg_home_mist)
                binding.home.homeMotif.isVisible = true
            }
            PreferenceStore.STYLE_PLAIN -> {
                binding.home.root.setBackgroundColor(getColor(R.color.page_bg))
                binding.home.homeMotif.isVisible = false
            }
            PreferenceStore.STYLE_IMAGE -> {
                binding.home.homeMotif.isVisible = false
                applyHomeImageBackground()
            }
            else -> {
                binding.home.root.setBackgroundResource(R.drawable.bg_home)
                binding.home.homeMotif.isVisible = true
            }
        }
    }

    /**
     * 用户自定义图片背景：把持久化的 content URI 异步解码并缓存为首页底色。
     * 读不到图时区分「永久失效」与「瞬时错误」——前者清配置、回退极简并告知用户，
     * 后者保留用户配置（判据见函数内注释）。
     */
    private fun applyHomeImageBackground() {
        val uriString = prefs.homeImageUri
        val container = binding.home.root
        val motif = binding.home.homeMotif
        if (uriString.isNullOrBlank()) {
            container.setBackgroundColor(getColor(R.color.page_bg))
            motif.isVisible = false
            return
        }
        // 尺寸以屏幕为准（避免布局未完成时拿到 0 宽高后反复解码）
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        if (w <= 0 || h <= 0) return
        val key = "$uriString#$w#$h"
        if (key == homeImageCacheKey && homeImageCache != null) {
            container.background = android.graphics.drawable.BitmapDrawable(resources, homeImageCache)
            motif.isVisible = false
            return
        }
        lifecycleScope.launch {
            // 读不到图必须分三类处理，否则要么一次瞬时错误就悄悄清掉用户刻意设置的主页风格，
            // 要么把一个**永久失效**的图片配置一直留着：
            //  - URI 已不可读（权限被收回 / 文件被删 / 不是图片）→ **永久失效**，再等等也不会好
            //    → 清配置 + 回退极简 + 告知用户；
            //  - 其它异常（瞬时 I/O、OOM、provider 抖动）→ **保留用户配置**，仅本次不换背景；
            // 早期版本只区分「抛异常」与「返回 null」，于是「权限被收回」这一永久失效被归进
            // 「瞬时错误」并静默保留：主页会长期停在「品牌区被隐藏、背景却不是用户那张图」的
            // 破相状态，且用户无从知道原因、也不会自愈（HomeCustomizeActivity 里那句
            // 「下次启动若失败会落到 PLAIN 风格（MainActivity 已处理）」正是这个意思，
            // 但原实现并没有处理，本次补齐）。
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val uri = android.net.Uri.parse(uriString)
                    // 持久权限在选图时已获取；这里只是补登记。部分 provider 不支持
                    // 持久权限会抛 SecurityException —— 属正常情况，不能当作失效判据。
                    runCatching {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                    io.github.tan_sno.tangsnow.util.Bitmaps.decodeSampled(contentResolver, uri, w, h)
                        ?.let { src ->
                            // CR-010：`cover` 刻意不回收入参（它可能原样返回 src）⇒ 回收责任在调用方。
                            // 仅当它返回的是**新图**时才回收源图，否则会把正在用的位图回收掉。
                            val out = io.github.tan_sno.tangsnow.util.Bitmaps.cover(src, w, h)
                            if (out !== src) runCatching { src.recycle() }
                            out
                        }
                }
            }
            if (isDestroyed) return@launch
            val cover = outcome.getOrNull()
            if (cover == null) {
                val cause = outcome.exceptionOrNull()
                val unreachable = cause is SecurityException ||
                    cause is java.io.FileNotFoundException
                if (cause != null && !unreachable) {
                    android.util.Log.w(
                        "MainActivity",
                        "home image decode threw; keeping user's setting",
                        cause,
                    )
                    return@launch
                }
                if (unreachable) {
                    android.util.Log.w(
                        "MainActivity",
                        "home image uri is no longer readable; falling back to plain",
                        cause,
                    )
                }
                // 失效（含「无异常但拿不到位图」= 该 URI 不是图片）：清配置并回退极简。
                // 直接重跑 applyHomeStyle()，让品牌区显隐与瓦片标签底色一并回到极简的一致状态
                // （此前只改背景色，品牌区仍按 onImage 隐藏着，属另一处不一致）。
                val stale = prefs.homeImageUri
                prefs.homeImageUri = null
                prefs.homeStyle = PreferenceStore.STYLE_PLAIN
                applyHomeStyle()
                // 副本也要删：只清偏好会把文件永久留在 filesDir/home_bg（用户以为已经没了）。
                // 走与定制页**同一个**出口 —— 否则归属守卫会出现第二份实现，迟早漏一处。
                HomeImageFile.deleteOnBackgroundThread(this@MainActivity, stale)
                toast(R.string.home_image_unavailable)
                return@launch
            }
            homeImageCache = cover
            homeImageCacheKey = key
            // 异步返回期间用户可能已切到其它风格
            if (prefs.homeStyle == PreferenceStore.STYLE_IMAGE) {
                container.background = android.graphics.drawable.BitmapDrawable(resources, cover)
                motif.isVisible = false
            }
        }
    }

    // 相册识别入口位于扫码取景页右下角（见 ui.ScanImageActivity）

    private fun openScanner() {
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt(getString(R.string.scan_hint))
            .setBeepEnabled(false)
            .setOrientationLocked(false)
            // 自带取景页（右下角含“相册识别”入口）
            .setCaptureActivity(io.github.tan_sno.tangsnow.ui.ScanImageActivity::class.java)
        qrLauncher.launch(options)
    }

    /**
     * 扫码结果处理，分三类而不是两类：
     *  1. 网址 / 裸域名 / 纯文本搜索词 → 交给地址栏解析并导航；
     *  2. **非 http(s) 的 scheme**（file:、mailto:、tel:、intent: 等）→ 不能拿去做搜索
     *     （旧实现会把 `file:///sdcard/x` 当关键词丢给搜索引擎，用户看到的是
     *     “这条路径被拿去搜了”这种莫名其妙的结果），改为明确提示不支持并给出
     *     「用其它应用打开」（若系统有处理器）与「复制」；
     *  3. 其它纯文本 → 直接展示 + 可复制。
     */
    private fun handleScanResult(content: String) {
        val scheme = runCatching { Uri.parse(content).scheme?.lowercase() }.getOrNull()
        val isWeb = scheme == null || scheme == "http" || scheme == "https"
        if (isWeb && UrlUtils.looksLikeUri(content)) {
            val engine = SearchEngines.current(prefs)
            navigateToUrl(UrlUtils.resolve(content, engine) ?: content)
            return
        }
        // 非空且不是 http(s) ⇒ 明确告知“这类链接浏览器打不开”，而不是悄悄拿去搜索
        val unsupportedScheme = scheme?.takeIf { !isWeb }
        AlertDialog.Builder(this)
            .setTitle(R.string.scan_result_title)
            .setMessage(
                if (unsupportedScheme != null) {
                    getString(R.string.scan_scheme_unsupported, unsupportedScheme, content)
                } else content
            )
            .setNegativeButton(R.string.dlg_cancel, null)
            .setNeutralButton(R.string.scan_copy) { _, _ -> copyToClipboardSafely(content, R.string.scan_copied) }
            .apply {
                if (unsupportedScheme != null) {
                    setPositiveButton(R.string.scan_open_external) { _, _ -> openExternalUrl(content) }
                }
            }
            .show()
    }

    /** `intent://` 链接里由页面指定的「回退网页地址」extra（该 scheme 的既有约定） */
    private val INTENT_FALLBACK_EXTRA = "browser_fallback_url"

    /** `intent://` 的 scheme 名（需按 URI_INTENT_SCHEME 解析，不能直接 ACTION_VIEW） */
    private val INTENT_SCHEME = "intent"

    /**
     * 由网页触发、但语义是**系统动作**（而非"某应用处理内容"）的 scheme —— `openExternalUrl`
     * 的非 `intent:` 分支对它一律不放行。
     *
     * 目前只收 `package`：它的既有语义是打开应用详情 / 卸载界面，给网页开放没有正当用途。
     * 刻意**不**扩大：其余 scheme 的泄露路径逐条核过均不成立（见该分支注释）。
     */
    private val SYSTEM_ACTION_SCHEMES = setOf("package")

    /**
     * 把非 http(s) 的外部链接交给系统 / 其它应用打开，并**如实反馈结果**。
     *
     * 修掉两个真实问题：
     *  1. 原先「先弹『正在用其他应用打开…』→ 再尝试」且**从不反馈失败**：没有任何应用能
     *     处理该协议时，用户看到的是「提示说要打开，然后什么都没发生」。现在改为
     *     **先尝试、成功才提示**，失败必须给出明确原因。
     *  2. `intent://`（国内页面 / 广告最常见的外链形式）原先直接走 `ACTION_VIEW` —— 该 scheme
     *     必须用 [Intent.parseUri] 以 `URI_INTENT_SCHEME` 解析出真正的目标 Intent，直接
     *     `ACTION_VIEW` **必然失败**。解析不出可用目标时，按该 scheme 的既有约定回退到
     *     页面自带的 `browser_fallback_url`（只接受 http/https）。
     *
     * 安全加固：解析出的 Intent 一律清掉 `component` / `selector`、只保留 `BROWSABLE` 分类，
     * 避免网页借 `intent://` 拉起本应用或其它应用内部的任意组件（该 scheme 的经典滥用面）。
     *
     * @return true 表示已经交出去（或已回退到网页）
     */
    private fun openExternalUrl(url: String): Boolean {
        val scheme = runCatching { Uri.parse(url).scheme?.lowercase() }.getOrNull()
        var fallbackUrl: String? = null
        val intent: Intent? = if (scheme == INTENT_SCHEME) {
            runCatching {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                    fallbackUrl = getStringExtra(INTENT_FALLBACK_EXTRA)
                    // 加固（只清「指定组件」这一类）：`intent://` 整串是**页面可控**的，
                    // component / selector 能直接指向某应用的内部组件 —— 该 scheme 的经典滥用面，必须清。
                    // ⚠️ 刻意**不清** `package` 与 extras：
                    //  · `package` 是该 scheme 规范里的**合法提示**（`…/market…#Intent;package=…` 这类
                    //    页面正是靠它直达目标应用）；清掉会让「两个应用都能处理」的链接退化成选择器，
                    //    而「探测某应用是否安装」的收益极低 —— 目标不存在时 startActivity 抛的
                    //    ActivityNotFoundException 本就由下方回退分支如实处理；
                    //  · extras（`S.` / `i.` 前缀）是**目标应用的载荷**，合法入口会依赖它。
                    // 一律清空属过度收口 —— 与「只放 http/https 会误挡 mailto」是同一类错误。
                    // （2026-09-30：`package` 曾按外部报告建议一并清掉，复核后**回退**，理由即上。）
                    component = null
                    selector = null
                    addCategory(Intent.CATEGORY_BROWSABLE)
                }
            }.getOrNull()
        } else {
            // 与 `intent:` 支**不对称是刻意的**，但也要挡住「网页触发系统动作」这一小类：
            //  - `intent:` 支的风险是「网页指定目标组件」，故清 component/selector；
            //  - 这一支是裸 ACTION_VIEW，风险面不同，但对 `package:` 这类**系统动作** scheme
            //    不该放行（`package:<包名>` 的既有语义是打开应用详情/卸载界面，属系统动作，
            //    不是"某个应用处理内容"）。
            // 可达路径（实测）：扫码结果的第二类（非 http(s) scheme）会给出「用其它应用打开」，
            // 用户点下即进入本支。
            // ⚠️ 只挡这一小类，不扩大黑名单：其余 scheme 逐条想过泄露路径均不成立
            //（file:// 指向本应用私有目录时接收方无权读取；content:// 未附 URI 授权同样受限）。
            if (scheme in SYSTEM_ACTION_SCHEMES) {
                null
            } else {
                runCatching { Intent(Intent.ACTION_VIEW, Uri.parse(url)) }.getOrNull()
            }
        }
        if (intent != null && runCatching { startActivity(intent) }.isSuccess) {
            // 确实交出去了才提示（原先在尝试之前就提示，成功与否都会说"正在打开"）
            toast(R.string.toast_external_opening)
            return true
        }
        // 没有应用能处理：优先按页面自带的回退地址回到网页，用户至少能到达那个地址。
        // 只接受 http/https —— 回退地址同样来自页面，不能让它塞进别的 scheme。
        val fallback = fallbackUrl
        val fallbackScheme = fallback?.let {
            runCatching { Uri.parse(it).scheme?.lowercase() }.getOrNull()
        }
        if (!fallback.isNullOrBlank() &&
            (fallbackScheme == "http" || fallbackScheme == "https")
        ) {
            navigateToUrl(fallback)
            return true
        }
        toast(R.string.scan_no_handler)
        return false
    }

    /** 写剪贴板并提示（先标记自写，避免被剪贴板监听当成外部写入而重复提示） */
    private fun copyToClipboardSafely(text: String, toastRes: Int) {
        markSelfClipboardWrite()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("tangsnow", text))
        toast(toastRes)
    }

    // ------------------------------------------------------------- 标签页

    private fun setupTabsRecycler() {
        tabsAdapter = TabsAdapter(
            onOpen = { tab -> openTabFromSwitcher(tab) },
            onClose = { tab -> closeTab(tab) },
            onLongClick = { tab -> showTabBatchMenu(tab) },
        )
        // hasFixedSize：网格尺寸是 match_parent，不随标签数量变化。标签面板每次打开/切换 /
        // 关标签都会整体刷新，声明后可跳过 requestAdapter 后的 requestLayout() 整树测量。
        binding.tabsContent.tabsRecycler.layoutManager = GridLayoutManager(this, 2)
        binding.tabsContent.tabsRecycler.setHasFixedSize(true)
        binding.tabsContent.tabsRecycler.adapter = tabsAdapter
        binding.tabsContent.btnCloseTabs.setOnClickListener { hideTabsPanel() }
        binding.tabsContent.btnPrivateTab.setOnClickListener { onPrivateTabFromPanel() }
        binding.tabsContent.btnAddTab.setOnClickListener { newTabFromTray() }
        binding.tabsContent.fabNewTab.setOnClickListener { newTabFromTray() }
    }

    /** 长按标签卡：弹出批量操作菜单（关闭其余 / 关闭全部） */
    private fun showTabBatchMenu(tab: Tab) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.tabs_batch_title))
            .setItems(
                arrayOf(
                    getString(R.string.tabs_close_others),
                    getString(R.string.tabs_close_all),
                )
            ) { _, which ->
                when (which) {
                    0 -> closeOtherTabs(keep = tab)
                    1 -> closeAllTabs()
                }
            }
            .show()
    }

    /** 关闭除 [keep] 外的所有标签：keep 保留并成为当前页 */
    private fun closeOtherTabs(keep: Tab) {
        if (sessionManager.tabCount <= 1) return
        hideTabsPanel()
        val keepIsActive = sessionManager.activeTab === keep
        if (keepIsActive) {
            // keep 仍在挂载：直接批量关其余即可
            sessionManager.closeOtherTabs(keep)
            updateTabsBadge()
            updatePrivateButton()
            updateNavCells()
        } else {
            // keep 非当前页：关其余会连带关掉当前活动标签 → 先解绑再挂 keep
            detachActiveSession()
            sessionManager.closeOtherTabs(keep)
            binding.geckoView.setSession(keep.session)
            syncViewWithTab(keep)
            updateTabsBadge()
            updatePrivateButton()
            updateNavCells()
        }
        // 用复数形式取文案：英文下 "Kept 1 tab" / "Kept 2 tabs"，
        // 早期固定写 "tab(s)" 属于语法上的将就写法
        val kept = sessionManager.tabCount
        toast(resources.getQuantityString(R.plurals.tabs_close_others_done, kept, kept))
    }

    /** 关闭全部标签并落到一个全新的空白首页 */
    private fun closeAllTabs() {
        hideTabsPanel()
        detachActiveSession()
        val tab = sessionManager.closeAllThenNew()
        binding.geckoView.setSession(tab.session)
        syncViewWithTab(tab)
        updateTabsBadge()
        updatePrivateButton()
        updateNavCells()
        toast(R.string.tabs_closed_all)
    }

    /** 标签页面板：右下角「+」/ 顶栏「+」都新建一个初始页（空白新标签） */
    private fun newTabFromTray() {
        hideTabsPanel()
        createTab(url = null)
        showHome()
    }

    /**
     * 标签面板顶栏的「无痕」开关：浏览中也能切换无痕，不再只有首页墨镜一个入口。
     * 已在无痕 → 关闭全部无痕、退回普通标签；普通浏览 → 新建一个无痕标签。
     */
    private fun onPrivateTabFromPanel() {
        hideTabsPanel()
        if (sessionManager.activeTab?.isPrivate == true) {
            exitPrivateMode()
        } else {
            createTab(url = null, isPrivate = true)
            showHome()
            toast(R.string.toast_private_on)
        }
    }

    /**
     * 退出无痕浏览：关闭全部无痕标签并回到普通标签；若已无普通标签可回，就新建一个空白页。
     *
     * 抽成一处的原因：标签面板的「无痕」开关与首页墨镜开关走的是**同一段逻辑**，
     * 此前两份完全相同的代码各写了一遍。以后若要改「退出无痕该做什么」
     * （例如还要恢复滚动位置、或补某个收尾动作），漏改一处就会出现两条入口行为不一致。
     */
    private fun exitPrivateMode() {
        val normal = sessionManager.closePrivateTabs()
        if (normal != null) {
            binding.geckoView.setSession(normal.session)
            syncViewWithTab(normal)
        } else {
            createTab(url = null, isPrivate = false)
            showHome()
        }
        // 无痕浏览结束 = 那一段浏览痕迹的终点。CrashLogger 里那个「最近访问站点」住在内存里、
        // 不会自己过期，不清掉的话此后任何一次崩溃都会把它写进持久化日志（与「无痕不留痕」冲突）。
        io.github.tan_sno.tangsnow.util.CrashLogger.clearHost()
        toast(R.string.toast_private_off)
    }

    private fun createTab(url: String?, isPrivate: Boolean = prefs.privateMode): Tab {
        val tab = sessionManager.newTab(isPrivate)
        binding.geckoView.setSession(tab.session)
        if (!url.isNullOrBlank()) loadInTab(tab, url)
        updateTabsBadge()
        return tab
    }

    private fun openTabFromSwitcher(tab: Tab) {
        sessionManager.switchTo(tab)
        binding.geckoView.setSession(tab.session)
        hideTabsPanel()
        syncViewWithTab(tab)
        // 延迟一小段，待合成器挂上新会话后刷新该标签预览
        binding.root.postDelayed({ tabPreviewController.captureCurrentPreview() }, 350)
    }

    private fun closeTab(tab: Tab, showPanel: Boolean = true) {
        val wasActive = tab === sessionManager.activeTab
        // 关键：先释放与 GeckoView 的绑定再 close()，否则会出现错误页
        if (wasActive) detachActiveSession()
        if (sessionManager.tabCount <= 1) {
            sessionManager.closeTab(tab)
            createTab(url = null)
            showHome()
        } else {
            val remain = sessionManager.closeTab(tab)
            // 只有关闭的是活动标签才需要重绑；关非活动标签时活动会话未变，
            // 重复 setSession 会白白闪一次
            if (wasActive && remain != null) {
                binding.geckoView.setSession(remain.session)
                syncViewWithTab(remain)
            }
        }
        updateTabsBadge()
        if (showPanel) showTabsPanel()
    }

    private fun detachActiveSession() {
        runCatching { binding.geckoView.releaseSession() }
    }

    private fun syncViewWithTab(tab: Tab) {
        binding.toolbar.addressBar.setText(tab.url.orEmpty())
        // 切标签时按“未在加载”复位按钮：新标签的真实加载态由其 onPageStart 重新置位，
        // 若沿用上一个标签的状态，会出现「切过来就显示停止」的错误提示
        setReloadButtonLoading(false)
        // 安全指示同样复位：新标签的页面会各自上报自己的安全状态
        hideSecurityIndicator()
        // 切标签必须收掉查找条、并清掉**上一个标签**的页内高亮：查找条属于「标签内的界面状态」，
        // 留着会显示上一个页面的结果计数，高亮还残留在原标签上（用户切回去会以为还在查找中）。
        // 位置刻意在 showHome/showBrowser **之前** —— 那两处内部也会 close(clear = false)
        // （只隐藏、不清高亮），顺序反了就是先被它们收掉条子，这里的 clear 反而无从下手。
        findBarController.close(clear = true)
        if (tab.url.isNullOrBlank()) showHome() else showBrowser()
        updateBookmarkIcon()
        updatePrivateButton()
        updateNavCells()
        updatePipParams()
    }

    private fun showTabsPanel() {
        tabPreviewController.captureCurrentPreview()
        tabsAdapter.submit(sessionManager.tabs)
        binding.tabsContent.tabsEmpty.isVisible = sessionManager.tabs.isEmpty()
        binding.tabsPanel.isVisible = true
        // 快速淡入（不明显但顺滑）；先取消在途动画避免快速开关叠加闪烁
        binding.tabsPanel.animate().cancel()
        binding.tabsPanel.alpha = 0f
        binding.tabsPanel.animate().alpha(1f).setDuration(120).start()
    }

    private fun hideTabsPanel() {
        if (!binding.tabsPanel.isVisible) return
        binding.tabsPanel.animate().cancel()
        binding.tabsPanel.animate().alpha(0f).setDuration(100)
            .withEndAction {
                binding.tabsPanel.isVisible = false
                binding.tabsPanel.alpha = 1f
            }
            .start()
    }

    private fun updateTabsBadge() {
        // 玻璃图标常显；页数以半透明数字呈现在玻璃中间，仅一页时不显示数字
        val count = sessionManager.tabCount
        binding.bottomBar.tabsCount.isVisible = count > 1
        binding.bottomBar.tabsCount.text = getString(R.string.tabs_count_format, count)
    }

    // ------------------------------------------------------------- 搜索引擎

    private fun setupEngineSwitcher() {
        updateEngineChipText()
        binding.toolbar.engineChip.setOnClickListener { showEngineMenu(it) }
    }

    private fun showEngineMenu(anchor: View) {
        val engines = SearchEngines.all(prefs)
        val pop = PopupMenu(this, anchor)
        // 展开期间显示当前引擎全名，收起后回到仅图标
        binding.toolbar.engineLabel.isVisible = true
        pop.setOnDismissListener { binding.toolbar.engineLabel.isVisible = false }
        engines.forEachIndexed { index, engine ->
            // 列表用文本前缀标记当前引擎（popup 系统默认不渲染图标，前缀更直观）
            val marker = if (engine.id == prefs.searchEngineId) "✓ " else ""
            pop.menu.add(0, index, index, "$marker${SearchEngines.localizedLabel(this, engine)}")
        }
        val manageId = engines.size
        pop.menu.add(0, manageId, manageId, getString(R.string.engine_manage))
        pop.setOnMenuItemClickListener { item ->
            if (item.itemId == manageId) {
                startActivity(Intent(this, EngineSettingsActivity::class.java))
            } else {
                val engine = engines[item.itemId]
                prefs.searchEngineId = engine.id
                updateEngineChipText()
                toast(SearchEngines.localizedLabel(this, engine))
            }
            true
        }
        pop.show()
    }

    /** 快捷引擎键：默认仅官方图标；无官方图标时用放大镜兜底（名称随菜单展开显示） */
    private fun updateEngineChipText() {
        val engine = SearchEngines.current(prefs)
        binding.toolbar.engineLabel.text = SearchEngines.localizedLabel(this, engine)

        val iconView = binding.toolbar.engineIcon
        val bmp = EngineIcons.get(this, engine)
        if (bmp != null) {
            iconView.setImageBitmap(bmp)
            iconView.imageTintList = null
        } else {
            iconView.setImageResource(R.drawable.ic_magnifier)
            iconView.imageTintList = ColorStateList.valueOf(getColor(R.color.accent))
        }
    }

    // ------------------------------------------------------------- 顶部 / 底部

    private fun setupToolbar() {
        binding.toolbar.btnScan.setOnClickListener { openScanner() }
        binding.toolbar.btnReload.setOnClickListener { onReloadOrStopClicked() }
        binding.toolbar.addressBar.setOnEditorActionListener { _, actionId, event ->
            if (isSubmit(actionId, event)) {
                navigate(binding.toolbar.addressBar.text.toString())
                true
            } else false
        }
    }

    /** 当前活动标签是否正在加载：决定地址栏右侧按钮显示「停止」还是「刷新」 */
    private var pageLoading = false

    /**
     * 地址栏「刷新 / 停止」。
     * 加载中 → 中断本次加载；空闲 → 重新加载当前页。
     * 这条高频动作此前埋在「更多」面板的快捷行里，现提升到一级入口（对齐主流浏览器）。
     */
    private fun onReloadOrStopClicked() {
        val tab = sessionManager.activeTab ?: return
        if (pageLoading) {
            runCatching { tab.session.stop() }
            // 停止后内核不一定再回 onPageStop（有些页面直接静默中断），
            // 这里主动复位，避免按钮永远停在「停止」
            setReloadButtonLoading(false)
            hideProgress()
        } else {
            reloadCurrent()
        }
    }

    /** 同步「刷新 / 停止」图标与无障碍描述，随页面加载状态切换 */
    private fun setReloadButtonLoading(loading: Boolean) {
        if (!::binding.isInitialized) return
        pageLoading = loading
        binding.toolbar.btnReload.setImageResource(
            if (loading) R.drawable.ic_close else R.drawable.ic_refresh
        )
        binding.toolbar.btnReload.contentDescription =
            getString(if (loading) R.string.btn_stop else R.string.more_refresh)
    }

    /**
     * 隐藏地址栏的连接安全指示。
     * 调用时机：新页面开始加载、切换标签、进入 about:/空白页 —— 这些时刻都不能沿用
     * 上一页的安全结论（否则会出现"http 页面顶着锁图标"的误导）。
     */
    private fun hideSecurityIndicator() {
        if (!::binding.isInitialized) return
        binding.toolbar.securityIndicator.isVisible = false
    }

    private fun setupBottomBar() {
        binding.bottomBar.cellBookmark.setOnClickListener { toggleBookmark() }
        binding.bottomBar.cellShare.setOnClickListener { shareCurrentPage() }
        binding.bottomBar.cellTabs.setOnClickListener { showTabsPanel() }
        binding.bottomBar.cellMore.setOnClickListener { showMoreSheet() }
        // 后退与系统返回键共用 handleBack()：只有一套判定，不会出现
        // "按屏幕上的后退回上一页、按系统返回却直接回首页"这类分叉
        binding.bottomBar.cellBack.setOnClickListener { handleBack() }
        binding.bottomBar.cellForward.setOnClickListener {
            val tab = sessionManager.activeTab
            if (tab?.canGoForward == true) tab.session.goForward()
        }
        updateNavCells()
    }

    /**
     * 导航格（后退 / 前进）可用态。
     *
     * 两个格子必须一起刷新：它们读的是同一份导航状态（`Tab.canGoBack/canGoForward`
     * 由内核回调维护），分开刷新极易出现"后退亮了、前进还灰着"的不一致。
     *
     * 后退的可用判据比前进宽：除了有可退历史，只要**当前还有可退的界面层级**
     * （覆盖层面板打开、或不在首页）就算可用 —— 因为后退键与系统返回键同源，
     * 它的语义是"回到上一层"，而不只是"回上一页历史"。首页且无面板时置灰，
     * 那种情况下"返回"没有可去之处（退出仍由系统返回键的连按两次完成）。
     */
    private fun updateNavCells() {
        if (!::binding.isInitialized) return

        val tab = sessionManager.activeTab
        val canForward = tab?.canGoForward == true
        binding.bottomBar.cellForward.isEnabled = canForward
        binding.bottomBar.cellForward.alpha = if (canForward) 1.0f else 0.38f

        val canBack = binding.tabsPanel.isVisible ||
            binding.moreSheet.isVisible ||
            !homeVisible ||
            tab?.canGoBack == true
        binding.bottomBar.cellBack.isEnabled = canBack
        binding.bottomBar.cellBack.alpha = if (canBack) 1.0f else 0.38f
    }

    // ------------------------------------------------------------- 无痕浏览（墨镜）

    private fun setupPrivateButton() {
        binding.home.btnPrivate.setOnClickListener { togglePrivateBrowsing() }
    }

    /** 墨镜开关：开启时新建无痕标签；退出时关闭全部无痕标签并回到普通标签 */
    private fun togglePrivateBrowsing() {
        val active = sessionManager.activeTab
        if (active?.isPrivate == true) {
            exitPrivateMode()
        } else {
            // 新建空白无痕标签后**同步视图**（与「标签面板的无痕开关」「+ 新建标签」一致）：
            // 空白标签会被 syncViewWithTab 判定为「应显示首页」。
            //
            // 原先这里只调 showBrowser() 且不做任何同步，后果有三：
            //  ① 首页被淡出隐藏、而新会话是空白的 → 用户点「无痕」后只剩一片空白页；
            //  ② 地址栏 / 导航格 / 收藏图标 / PiP 参数仍停留在上一个标签的状态；
            //  ③ 与另外两个入口（都走 showHome()）行为不一致。
            syncViewWithTab(createTab(url = null, isPrivate = true))
            toast(R.string.toast_private_on)
        }
        updatePrivateButton()
    }

    private fun updatePrivateButton() {
        val isPrivate = sessionManager.activeTab?.isPrivate == true
        binding.home.btnPrivate.setColorFilter(
            getColor(if (isPrivate) R.color.accent else R.color.accent_muted)
        )
    }

    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBack()
            }
        })
    }

    /**
     * 统一的「返回」判定 —— 系统返回键与底栏后退格**共用同一实现**。
     *
     * 顺序即优先级：先收最上层的覆盖层，再处理首页退出，最后才是网页历史与回首页。
     * ⚠️ 「覆盖层」这份清单**必须完整** —— 漏掉任何一档，返回键就会**穿透**到下一档：
     *  · 查找条漏了 → 直接去走网页历史 / 回首页，条子与页内高亮都留着；
     *  · 首页联想面板漏了 → 落到 `homeVisible` 档，一次误按提示「再按一次退出」，
     *    连按两下就把应用退掉（连输入法的候选都没收掉）。
     * 之所以抽成一个函数：两处入口若各写一份判定，只要有一处忘记同步，就会出现
     * "屏幕后退与系统返回行为不一致"的诡异手感。
     */
    private fun handleBack() {
        when {
            binding.tabsPanel.isVisible -> hideTabsPanel()
            binding.moreSheet.isVisible -> hideMoreSheet()
            // 下面两档必须排在 homeVisible **之前**，否则首页上的联想态会直接走双击退出
            findBarController.isOpen -> findBarController.close()
            suggestionsController.isShowing -> suggestionsController.hide()
            // 首页返回键：连续两下才退出（业界惯例），避免误触直接退出
            homeVisible -> confirmExitByDoubleBack()
            // 对齐主流浏览器：网页内先回退历史，无历史再回主页
            sessionManager.activeTab?.canGoBack == true ->
                sessionManager.activeTab?.session?.goBack()
            else -> showHome()
        }
    }

    /** 首页双击返回键退出：第一次提示「再按一次退出」，2 秒内再按才真正退出 */
    private fun confirmExitByDoubleBack() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBackPressAt < 2000L) {
            performExit()
        } else {
            lastBackPressAt = now
            toast(R.string.toast_exit_again)
        }
    }

    // ------------------------------------------------------------- 视图状态

    internal fun navigate(input: String) {
        val engine = SearchEngines.current(prefs)
        val resolved = UrlUtils.resolveInfo(input, engine) ?: return
        // 搜索时地址栏保持用户输入的关键词（如输入「1」就显示「1」）
        navigateToUrl(resolved.url, if (resolved.isSearch) input.trim() else null)
    }

    internal fun navigateToUrl(url: String, keepAddress: String? = null) {
        val tab = sessionManager.activeTab ?: return
        binding.toolbar.addressBar.setText(keepAddress ?: url)
        loadInTab(tab, url)
        showBrowser()
    }

    /**
     * 导航前的最后一站 —— 所有**应用内发起**的导航都经过这里
     * （地址栏 / 外部 intent / 书签 / 历史 / 扫码结果）。
     *
     * Android 17 起访问局域网需要 `ACCESS_LOCAL_NETWORK`，故在真正 `loadUri` 之前
     * **按需**申请：只在目标确实落在局域网时才弹，与「权限极简」的定位一致；
     * 授权后由 [requestLocalNetworkAccess] 的回调自动续跑这次被暂缓的导航。
     *
     * 页面内自己点出来的局域网链接不经过这里（由内核直接发起），那条路由
     * [tabEvents] 的 `onLocationChanged` 兜底提示。
     */
    private fun loadInTab(tab: Tab, url: String) {
        if (needsLocalNetworkGrant(url)) {
            pendingLocalNetworkTab = tab
            pendingLocalNetworkUrl = url
            requestLocalNetworkPromptIfIdle()
            return
        }
        tab.session.loadUri(url)
    }

    /** 该地址落在局域网、且当前尚未取得本地网络权限 */
    private fun needsLocalNetworkGrant(url: String): Boolean =
        UrlUtils.isLocalNetworkAddress(url) && !hasLocalNetworkAccess()

    /**
     * 是否已具备本地网络访问权限。
     *
     * `ACCESS_LOCAL_NETWORK` 是 Android 17 才引入的权限：更低版本上 `checkSelfPermission`
     * 必然返回「未授予」，但那些系统本就不限制局域网，故低于 37 一律按已授予处理 ——
     * 否则会在旧机型上弹一个系统里根本不存在的权限，用户点「允许」也不会有任何反应。
     * （Android 16 上该权限是可选的 opt-in，本应用未选择加入，同样不受限。）
     */
    private fun hasLocalNetworkAccess(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 37) return true
        return androidx.core.content.ContextCompat.checkSelfPermission(
            this, PERM_ACCESS_LOCAL_NETWORK
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** 去重地发起权限请求：同一 launcher 并发 launch 会抛异常 */
    private fun requestLocalNetworkPromptIfIdle() {
        if (localNetworkPromptInFlight) return
        localNetworkPromptInFlight = true
        try {
            requestLocalNetworkAccess.launch(PERM_ACCESS_LOCAL_NETWORK)
        } catch (e: Throwable) {
            // 启动失败（launcher 已销毁 / 并发 launch）：必须在此复位去重标志，否则标志卡死、
            // 之后再也发起不了请求。
            // ⚠️ 刻意**不**用 finally 复位：launch() 只负责派发，结果走 requestLocalNetworkAccess
            //    的回调 —— 在 launch() 返回时就复位会把「去重」窗口缩到 0，弹窗还挂着时的第二次
            //    调用会真的并发 launch，正是本守卫要挡的那件事（见上方 KDoc）。成功路径一律由回调复位。
            localNetworkPromptInFlight = false
            android.util.Log.w("MainActivity", "local network permission launch failed", e)
        }
    }

    private fun showBrowser() {
        homeVisible = false
        binding.geckoView.isVisible = true
        // 进入浏览态即收起联想/查找残留，避免遮挡网页内容与操作
        suggestionsController.hide()
        findBarController.close(clear = false)
        // 首页快速淡出（80ms，不明显但顺滑；GeckoView 是 SurfaceView 不做 alpha）
        binding.home.root.animate().cancel()
        binding.home.root.animate().alpha(0f).setDuration(80).withEndAction {
            binding.home.root.isVisible = false
            binding.home.root.alpha = 1f
        }.start()
        refreshChrome()
        hideKeyboard()
    }

    private fun showHome() {
        homeVisible = true
        findBarController.close(clear = false)
        suggestionsController.hide()
        // 回到首页时把刷新键复位：首页没有“正在加载的页面”，按钮应显示刷新
        setReloadButtonLoading(false)
        binding.geckoView.isVisible = false
        binding.home.root.animate().cancel()
        binding.home.root.isVisible = true
        binding.home.root.alpha = 0f
        binding.home.root.animate().alpha(1f).setDuration(80).start()
        binding.toolbar.addressBar.setText("")
        refreshChrome()
        hideKeyboard()
    }

    /** 顶底两栏在非全屏时始终显示；全屏时隐藏框架 + 系统栏，网页边距拉满 */
    private fun refreshChrome() {
        val pip = pipActive
        val bottom = prefs.toolbarBottom
        val full = pip || sessionManager.activeTab?.fullScreen == true
        binding.toolbar.root.isVisible = !full
        binding.bottomBar.root.isVisible = !full
        if (full) hideProgress()

        val lp = binding.geckoView.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        // 顶部模式：网页上让位地址栏、下让位操作栏；底部模式：上方无栏、下方让位操作栏+地址栏
        lp.topMargin = if (full || bottom) 0 else topBarH
        lp.bottomMargin = if (full) 0 else if (bottom) bottomBarH + topBarH else bottomBarH
        binding.geckoView.layoutParams = lp
        // PiP 悬浮窗由系统管理系统栏，宿主不再主动隐藏，避免冲突
        if (!pip) applyImmersive(full)
    }

    /**
     * 依据「地址栏在底部」偏好布置地址胶囊、进度条、联想与查找条的位置。
     * 顶部为默认（对齐原布局）；底部模式把胶囊沉到操作栏上方，联想/查找从底部向上展开。
     */
    private fun applyToolbarPosition() {
        val bottom = prefs.toolbarBottom
        fun place(view: View, gravity: Int, topMargin: Int, bottomMargin: Int) {
            val lp = view.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
            lp.gravity = gravity
            lp.topMargin = topMargin
            lp.bottomMargin = bottomMargin
            view.layoutParams = lp
        }
        // 地址胶囊：底部模式时悬浮在底部操作栏（bottomBar）上方
        place(
            binding.toolbar.root,
            if (bottom) Gravity.BOTTOM else Gravity.TOP,
            0,
            if (bottom) bottomBarH else 0,
        )
        // 进度条：顶部模式贴地址栏下沿；底部模式移到内容区顶部（仍清晰可见）
        place(binding.loadProgress, Gravity.TOP, if (bottom) 0 else topBarH, 0)
        // 联想面板与查找条：始终贴着地址胶囊展开（顶部向下 / 底部向上）
        val anchoredGap = bottomBarH + topBarH
        place(
            findViewById(R.id.suggestionPanel),
            if (bottom) Gravity.BOTTOM else Gravity.TOP,
            if (bottom) 0 else topBarH + 2,
            if (bottom) anchoredGap else 0,
        )
        place(
            findViewById(R.id.findBar),
            if (bottom) Gravity.BOTTOM else Gravity.TOP,
            if (bottom) 0 else topBarH + 4,
            if (bottom) anchoredGap else 0,
        )
        refreshChrome()
    }

    private fun applyImmersive(full: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (full) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ------------------------------------------------------------- 加载进度

    private fun showProgress(progress: Int) {
        if (sessionManager.activeTab?.fullScreen == true || homeVisible) return
        binding.loadProgress.isVisible = true
        binding.loadProgress.progress = progress.coerceIn(0, 100)
    }

    private fun hideProgress() {
        binding.loadProgress.isVisible = false
        binding.loadProgress.progress = 0
    }

    // ------------------------------------------------------------- 收藏 / 分享

    /** 收藏切换进行中：防双击重入（两次 toggle 净效果=没收藏，还会甩出两条互相矛盾的 toast） */
    private var bookmarkToggling = false

    private fun toggleBookmark() {
        val tab = sessionManager.activeTab ?: return
        val url = tab.url ?: return
        // 空白页 / 内部页无可收藏内容：给出反馈而不是静默无响应
        if (url.isBlank() || url.startsWith("about:")) {
            toast(R.string.more_need_page)
            return
        }
        if (bookmarkToggling) return
        bookmarkToggling = true
        lifecycleScope.launch {
            try {
                val added = BookmarkRepo.toggle(url, tab.title)
                toast(if (added) R.string.toast_bookmarked else R.string.toast_bookmark_removed)
                updateBookmarkIcon()
            } finally {
                // 取消（销毁）也要复位：latch 只防抖，不能把收藏键锁死
                bookmarkToggling = false
            }
        }
    }

    private fun updateBookmarkIcon() {
        val icon = binding.bottomBar.iconBookmark
        // 快速导航（如连点前进/后退）会触发多次 updateBookmarkIcon，每次各开一个 DB 查询。
        // 若不防竞态，旧 URL 的查询结果后返回时会把图标写回旧态。用单调递增序号，
        // 只接受「最新一次」查询的结果，丢弃过期返回。
        val seq = ++bookmarkQuerySeq
        lifecycleScope.launch {
            val url = sessionManager.activeTab?.url
            val saved = url != null && !url.isBlank() && BookmarkRepo.isBookmarked(url)
            if (seq != bookmarkQuerySeq) return@launch
            val activeColor = getColor(R.color.accent)
            val idleColor = getColor(R.color.accent_muted)
            icon.setImageResource(if (saved) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border)
            icon.imageTintList = ColorStateList.valueOf(if (saved) activeColor else idleColor)
        }
    }

    private fun shareCurrentPage() {
        val tab = sessionManager.activeTab ?: return
        val url = tab.url ?: return
        // about:/data:/blob: 都没有可分享的内容（超长/临时/内部页）—— 判据统一在 UrlUtils
        if (!UrlUtils.isPortableUrl(url)) {
            toast(R.string.toast_share_empty)
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, if (tab.title.isBlank()) url else "${tab.title}\n$url")
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_via)))
    }

    // ------------------------------------------------------------- 下载

    /** 需要确认但宿主不可见时挂起的下载请求（onResume 补弹）。上限见 [MAX_PENDING_DOWNLOADS] */
    private data class PendingDownloadConfirm(val response: WebResponse, val pageUrl: String?)

    private companion object {
        const val MAX_PENDING_DOWNLOADS = 4

        /**
         * 挂起队列与丢弃计数放**进程级**：Activity 因主题/语言切换重建时，新实例能接手
         * 这些内核响应（[WebResponse] 本就是进程级对象），onResume 照常补弹；
         * 留在实例字段上只会「队列随旧实例消失、连接悬挂到超时」（外部审查 M4，2026-10-01）。
         */
        val pendingDownloadConfirms = ArrayDeque<PendingDownloadConfirm>()

        @Volatile
        var pendingDownloadDropped = 0
    }

    /** 放弃一次下载：关掉内核响应流——不消费不关闭 = 连接悬挂到内核超时（外部审查 M4） */
    private fun abandonDownload(response: WebResponse) {
        runCatching { response.body?.close() }
    }

    /**
     * 网页触发的下载。**宿主不可见时不建确认窗**（与权限征询同口径）：
     * 需要询问的请求挂起，onResume 补弹；静默下载（已关「下载前询问」）不受影响。
     * 代价：挂起期间内核连接可能超时断开，补弹后开始下载会**如实**报失败 ——
     * 好过对不可见窗口建 AlertDialog（部分 ROM 会给「后台弹窗」提示，且用户毫无上下文）。
     */
    private fun handleDownload(response: WebResponse, pageUrl: String?) {
        // HTTP 头名大小写不敏感，统一查找 Content-Disposition，避免对两种大小写各查一次
        val disposition = response.headers.entries
            .firstOrNull { it.key.equals("content-disposition", ignoreCase = true) }?.value
        // 文件名统一由仓库解析：Content-Disposition（含 RFC 5987）优先，
        // 兜底取 URL 末段并自动剥离查询串 / 百分号解码 / 非法字符清洗
        val fileName = DownloadRepo.parseFileName(disposition, response.uri)
        // ⚠️ 可执行 / 安装类文件：**无条件**先确认，且刻意不提供「不再询问」选项。
        // 理由：这类文件运行后会改变设备状态（安装应用、执行脚本），一句永久开关
        // 不应把它的确认一并免掉；而普通文档仍尊重用户的「不再询问」偏好。
        val needsConfirm = DownloadRepo.isExecutableName(fileName) || prefs.askBeforeDownload
        if (needsConfirm && !hostVisibleNow()) {
            if (pendingDownloadConfirms.size >= MAX_PENDING_DOWNLOADS) {
                // 被挤掉的请求必须**显式关掉**内核流：只丢引用，连接会挂到内核超时
                abandonDownload(pendingDownloadConfirms.removeFirst().response)
                pendingDownloadDropped++
            }
            pendingDownloadConfirms.addLast(PendingDownloadConfirm(response, pageUrl))
            return
        }
        confirmDownload(response, fileName, pageUrl)
    }

    /** [handleDownload] 的后半段：真正弹确认框或直接开始（此时已判定不需要挂起） */
    private fun confirmDownload(response: WebResponse, fileName: String, pageUrl: String?) {
        val url = response.uri
        if (DownloadRepo.isExecutableName(fileName)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.dl_executable_title)
                .setMessage(getString(R.string.dl_executable_message, fileName))
                .setNegativeButton(R.string.dlg_cancel) { _, _ -> abandonDownload(response) }
                .setPositiveButton(R.string.dl_executable_continue) { _, _ ->
                    startDownload(response, url, fileName, pageUrl)
                }
                // 返回键/点外侧取消也要关流（取消键走 OnCancelListener，不走负按钮回调）
                .setOnCancelListener { abandonDownload(response) }
                .show()
            return
        }
        // 用户已关掉「下载前询问」：直接开始
        if (!prefs.askBeforeDownload) {
            startDownload(response, url, fileName, pageUrl)
            return
        }
        // 下载先征询用户，避免“页面偷偷开始下载”的体验与合规风险；
        // 勾选「不再询问」后本次起永久跳过（可在设置里重新打开）
        val noAsk = booleanArrayOf(false)
        AlertDialog.Builder(this)
            .setTitle(R.string.download_confirm_title)
            .setMessage(getString(R.string.download_confirm_message, fileName))
            .setMultiChoiceItems(
                arrayOf(getString(R.string.download_no_ask)), noAsk
            ) { _, _, isChecked -> noAsk[0] = isChecked }
            .setNegativeButton(R.string.dlg_cancel) { _, _ -> abandonDownload(response) }
            .setPositiveButton(R.string.download_confirm_ok) { _, _ ->
                if (noAsk[0]) prefs.askBeforeDownload = false
                startDownload(response, url, fileName, pageUrl)
            }
            .setOnCancelListener { abandonDownload(response) }
            .show()
    }

    /** onResume 补弹挂起的下载确认；被挤掉的请求按条数如实告知（不说「都还在」） */
    private fun flushPendingDownloadConfirms() {
        if (pendingDownloadConfirms.isEmpty() && pendingDownloadDropped == 0) return
        val queued = pendingDownloadConfirms.toList()
        pendingDownloadConfirms.clear()
        val dropped = pendingDownloadDropped
        pendingDownloadDropped = 0
        for (p in queued) confirmDownload(p.response, fileNameOf(p.response), p.pageUrl)
        if (dropped > 0) {
            toast(resources.getQuantityString(R.plurals.dl_background_dropped, dropped, dropped))
        }
    }

    /** 从响应重算文件名（补弹时不再持有挂起时的解析结果，保持单一解析入口） */
    private fun fileNameOf(response: WebResponse): String {
        val disposition = response.headers.entries
            .firstOrNull { it.key.equals("content-disposition", ignoreCase = true) }?.value
        return DownloadRepo.parseFileName(disposition, response.uri)
    }

    /**
     * 真正执行下载：一律优先消费内核响应流；**只有内核没给响应体时**才退回系统下载器。
     *
     * 为什么**不再**按体积路由到系统下载器（2026-09-26 审查撤销）：CL 已知的
     * 登录态大附件（NAS / 私有云场景）交系统下载器二次 GET 时没有 Cookie，
     * 会把登录页 HTML 存成目标文件名还报成功 —— 错误内容比中断更糟。
     * 大文件退后台被杀的旧风险重新成立，两全方案（GeckoWebExecutor 带
     * Cookie 流式 + 通知）留待 javap/真机验证后另行实施。
     *
     * 同理，**写盘失败也不再退回系统下载器**：那条路同样是"另一次不带 Cookie 的 GET"，
     * 撤销大文件路由的同一个理由对它一字不差地成立（此前 false 把「无响应体」与「写失败」
     * 混成一种，于是这两种情形共用了一条回退路径）。
     */
    private fun startDownload(
        response: WebResponse,
        url: String,
        fileName: String,
        pageUrl: String?,
    ) {
        lifecycleScope.launch {
            toast(R.string.toast_start_download)
            when (DownloadRepo.saveFromStream(this@MainActivity, response, fileName)) {
                // 已落盘并登记（落盘失败时的占位行清理由 DownloadRepo 收口）
                DownloadRepo.SaveOutcome.SAVED -> Unit

                // 内核没给响应体：手上本就没有内容，退回系统下载器是唯一出路（附 Referer/UA）
                DownloadRepo.SaveOutcome.NO_BODY -> {
                    // Referer 必须传**发起下载的页面地址**，而不是下载地址本身：此前传的是 `url`，
                    // 对服务端等于"自指"，防盗链校验与来源统计都会拿到错的值。
                    val id = DownloadRepo.launch(this@MainActivity, url, fileName, referer = pageUrl)
                    if (id < 0) {
                        // 系统下载器也拒绝（URL scheme 不受支持等）：如实提示失败，
                        // 不让上面那句「开始下载」变成空头支票
                        toast(R.string.download_start_failed)
                    }
                }

                // 有内容但没落下去：如实报失败，且**用另一条文案** —— 「启动失败」与
                // 「内容没写下去」是两件事；后者还要传达我们**刻意没**退回系统下载器
                // （那条路是另一次不带 Cookie 的 GET，会把登录页 HTML 冒充成用户要的文件，
                // 且系统下载器还会报「成功」）。
                DownloadRepo.SaveOutcome.FAILED -> toast(R.string.download_save_failed)
            }
        }
    }

    // ------------------------------------------------------------- 更多面板（半屏）

    /** 快捷操作行的宿主，每次展开时重建以反映最新状态（桌面版开关等） */
    private var quickRowHost: LinearLayout? = null

    private fun buildMoreSheet() {
        binding.moreScrim.setOnClickListener { hideMoreSheet() }
        val panel = binding.morePanel
        // 紧凑布局：所有行自然高，不再用权重均分，避免大段空白导致图标看起来"过小"
        // 每行选项上方都放一条半透明分隔线（左右两边各留 16dp，不触边），见 addSheetDivider
        addSheetDivider(this, panel)
        panel.addView(
            makeSheetRow(this, ::hideMoreSheet, R.drawable.ic_extension, getString(R.string.more_extensions)) {
                startActivity(Intent(this, ExtensionsActivity::class.java))
            }
        )
        // 内核能力 / 高频操作快捷行（展开面板时按最新状态重建）
        addSheetDivider(this, panel)
        val quick = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        quickRowHost = quick
        panel.addView(quick)
        // 页内查找（内核原生 SessionFinder）
        addSheetDivider(this, panel)
        panel.addView(
            makeSheetRow(this, ::hideMoreSheet, R.drawable.ic_magnifier, getString(R.string.more_find)) { findBarController.open() }
        )
        // 打印网页：先生成 PDF 落到缓存，再交给系统打印服务（含「保存为 PDF」选项）
        addSheetDivider(this, panel)
        panel.addView(
            makeSheetRow(this, ::hideMoreSheet, R.drawable.ic_print, getString(R.string.more_print)) { printCurrentPage() }
        )
        addSheetDivider(this, panel)
        panel.addView(makeSheetLibraryRow(this, ::hideMoreSheet))
        // 定制主页：去掉左侧图标，仅保留居中文字（更简洁）
        addSheetDivider(this, panel)
        panel.addView(
            makeSheetTextRow(this, ::hideMoreSheet, getString(R.string.more_customize_home)) {
                startActivity(Intent(this, HomeCustomizeActivity::class.java))
            }
        )
        addSheetDivider(this, panel)
        panel.addView(
            makeSheetRow(this, ::hideMoreSheet, R.drawable.ic_settings, getString(R.string.more_settings)) {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        )
        // 退出浏览器：与其它行同尺寸（图标/字号全面板统一）
        addSheetDivider(this, panel)
        panel.addView(
            makeSheetRow(this, ::hideMoreSheet, 
                R.drawable.ic_logout,
                getString(R.string.more_exit),
            ) { confirmExit() }
        )
    }


    /** 重绘快捷操作行（刷新 / 复制链接 / 桌面版 / 存为 PDF）。 */
    private fun refreshQuickRow() {
        val host = quickRowHost ?: return
        host.removeAllViews()
        // 图标与文字分色：图标用 accent（≥3:1），文字用 accent_text（面板背景 ≥4.5:1）
        val activeColor = getColor(R.color.accent)
        val activeTextColor = getColor(R.color.accent_text)
        val idleColor = getColor(R.color.accent_muted)
        val desktopOn = prefs.desktopMode

        // 「刷新」已提升到地址栏胶囊内的一级入口（随加载态在刷新/停止间切换），
        // 这里不再重复放置，避免同一动作两处入口、占据宝贵的一屏位置
        val copy = makeSheetActionCell(this, ::hideMoreSheet,
            R.drawable.ic_link, getString(R.string.more_copy_link),
            idleColor, idleColor
        ) { copyCurrentUrl() }
        val desktop = makeSheetActionCell(this, ::hideMoreSheet,
            R.drawable.ic_desktop, getString(R.string.more_desktop),
            if (desktopOn) activeColor else idleColor,
            if (desktopOn) activeTextColor else idleColor,
        ) { toggleDesktopMode() }
        val savePdf = makeSheetActionCell(this, ::hideMoreSheet, 
            R.drawable.ic_pdf, getString(R.string.more_save_pdf),
            idleColor, idleColor
        ) { savePageAsPdf() }

        listOf(copy, desktop, savePdf).forEachIndexed { i, cell ->
            host.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (i < 2) host.addView(makeThinDivider(this), LinearLayout.LayoutParams(1, dp(34)))
        }
    }







    // ------------------------------------------------------------- 更多面板快捷操作

    /** 刷新当前页；返回是否有可刷新页面 */
    private fun reloadCurrent(): Boolean {
        val tab = sessionManager.activeTab ?: return false
        val url = tab.url ?: return false
        if (url.isBlank() || url.startsWith("about:")) return false
        tab.session.reload()
        return true
    }

    // ------------------------------------------------------------- 长按文字操作（内核 SelectionActionDelegate）

    private var selectionPopup: android.widget.PopupWindow? = null

    /** 网页读取剪贴板权限的询问弹框（同一时刻最多一个） */
    private var clipboardPermissionDialog: AlertDialog? = null

    private val selectionHandlerImpl = object : io.github.tan_sno.tangsnow.browser.SelectionHandler {
        override fun onSelection(
            session: org.mozilla.geckoview.GeckoSession,
            text: String,
            availableActions: Collection<String>,
            performAction: (String) -> Unit,
            hide: () -> Unit,
        ) {
            showSelectionActions(text, hide)
        }

        override fun onHide() {
            dismissSelectionPopup()
        }

        override fun onClipboardPermissionRequest(uri: String, respond: (Boolean) -> Unit) {
            if (isFinishing || isDestroyed) {
                respond(false)
                return
            }
            // 从 uri 提取主机名展示给用户；解析失败则用原始串兜底
            val host = runCatching { android.net.Uri.parse(uri).host }.getOrNull()
                ?: uri.ifBlank { getString(R.string.clipboard_unknown_site) }
            clipboardPermissionDialog?.dismiss()
            var responded = false
            fun respondOnce(allow: Boolean) {
                if (responded) return
                responded = true
                respond(allow)
            }
            clipboardPermissionDialog = AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.clipboard_read_title)
                .setMessage(getString(R.string.clipboard_read_message, host))
                .setNegativeButton(R.string.clipboard_read_deny) { _, _ -> respondOnce(false) }
                .setPositiveButton(R.string.clipboard_read_allow) { _, _ -> respondOnce(true) }
                // 用 OnDismiss 而非 OnCancel 兜底：Activity 销毁等任何 dismiss 都会走到这里，
                // 保证 respond 恰好一次、GeckoResult 不挂起（点按钮路径已被 respondOnce 守卫拦截）
                .setOnDismissListener { respondOnce(false) }
                .show()
        }

        override fun onClipboardPermissionDismissed() {
            clipboardPermissionDialog?.dismiss()
            clipboardPermissionDialog = null
        }
    }

    private fun showSelectionActions(text: String, hide: () -> Unit) {
        dismissSelectionPopup()
        val safe = text.trim()
        if (safe.isEmpty()) {
            hide()
            return
        }
        selectionPopup = showSelectionPopup(
            context = this,
            anchor = binding.toolbar.root,
            actions = listOf(
                getString(R.string.selection_copy) to {
                    markSelfClipboardWrite()
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("tangsnow_selection", safe))
                    toast(R.string.toast_copied)
                },
                getString(R.string.selection_share) to {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, safe)
                    }
                    startActivity(Intent.createChooser(send, getString(R.string.share_via)))
                },
                getString(R.string.selection_search) to { navigate(safe) },
            ),
            onBeforeAction = { dismissSelectionPopup(); hide() },
            onDismiss = { selectionPopup = null },
            // 地址栏沉底时改为「锚在底部并上移」：否则弹窗按锚点向下展会被挤到屏幕外
            bottomMarginPx = if (prefs.toolbarBottom) bottomBarH + topBarH + 6 else null,
        )
    }

    private fun dismissSelectionPopup() {
        selectionPopup?.dismiss()
        selectionPopup = null
    }

    private fun copyCurrentUrl() {
        val tab = sessionManager.activeTab ?: return
        val url = tab.url ?: return
        // 与分享同一判据（about:/data:/blob: 不复制），见 UrlUtils.isPortableUrl
        if (!UrlUtils.isPortableUrl(url)) {
            toast(R.string.more_nothing_to_copy)
            return
        }
        markSelfClipboardWrite()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("tangsnow_url", url))
        toast(R.string.toast_copied)
    }

    /** 标记「本应用主动写剪贴板」，让监听器跳过，避免把用户主动复制误报成网页写入 */
    private fun markSelfClipboardWrite() {
        selfClipboardWriteAt = SystemClock.elapsedRealtime()
    }

    /**
     * 剪贴板变化：提示用户并可一键清除。
     *
     * 三点约束（早期版本缺一即误报或打扰）：
     *  1. 跳过本应用 1 秒内的主动写入（扫码复制 / 复制链接 / 选中复制）——那些已有 toast；
     *  2. 仅当应用**在前台且持有窗口焦点**时提示——剪贴板变化是全局事件，分屏或悬浮窗下
     *     别的应用复制也会触发，那种场景把提示归给网页是错的；
     *  3. 每个前台周期最多提示一次——否则连续复制会连续弹 Snackbar，把用户烦到关掉功能。
     *
     * 文案（`clipboard_external_write`）只说「剪贴板内容有更新」，**不再断言“网页写入”**：
     * 系统不提供来源信息，做确定性归因就是给用户假信息。隐私政策亦已如实披露本监听行为。
     */
    private fun onClipboardChanged() {
        if (SystemClock.elapsedRealtime() - selfClipboardWriteAt < 1000L) return
        if (!hasWindowFocus()) return
        if (clipboardNotifiedThisForeground) return
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            clipboardNotifiedThisForeground = true
            Snackbar.make(binding.root, R.string.clipboard_external_write, Snackbar.LENGTH_LONG)
                .setAction(R.string.clipboard_clear) { clearClipboard() }
                .show()
        }
    }

    /** 用户选择清除网页/外部写入的剪贴板内容 */
    private fun clearClipboard() {
        markSelfClipboardWrite()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("tangsnow_cleared", ""))
    }

/**
     * 桌面/移动版切换。
     * GeckoView 的 UA/视口模式应在会话创建时确定；对已显示会话中途修改会引发
     * 输入错乱（表现为“点不动”）。因此这里重建**所有**标签会话并按新模式重载，
     * 保证多标签下 UA/视口一致（而不是只有活动标签换档）。
     */
    private fun toggleDesktopMode() {
        val old = sessionManager.activeTab
        val url = old?.url
        val canApply = url != null && !url.isBlank() && !url.startsWith("about:")
        prefs.desktopMode = !prefs.desktopMode
        rebuildAllTabsForDesktop()
        toast(
            if (prefs.desktopMode) {
                if (canApply) R.string.toast_desktop_on else R.string.toast_desktop_on_blank
            } else {
                if (canApply) R.string.toast_desktop_off else R.string.toast_desktop_off_blank
            }
        )
    }

    /** 重建活动标签会话进行中（防重入：快速连点/多回调叠加会导致双重换会话） */
    private var rebuildingActive = false

    /**
     * 用当前 UA/视口偏好重建**所有**标签的会话（桌面/移动切换唯一路径）。
     * 先释放与旧 GeckoView 的绑定，再交给会话管理器批量重建（每个标签保留
     * url/标题/无痕/活动身份并重载）；旧会话由管理器延迟安静关闭，避免引擎
     * 把迟到事件断言成 Must use an unopened GeckoSession instance。
     */
    private fun rebuildAllTabsForDesktop() {
        if (rebuildingActive) return
        rebuildingActive = true
        try {
            detachActiveSession()
            val newActive = sessionManager.rebuildAllTabs()
            if (newActive != null) {
                binding.geckoView.setSession(newActive.session)
                syncViewWithTab(newActive)
            }
            updateTabsBadge()
        } finally {
            rebuildingActive = false
        }
    }

    /**
     * 设置页「桌面版网站」开关与更多面板快捷格是同一功能的两个入口：
     * 设置页改完回到主界面时，在这里检测变化并对所有标签重建会话（行为对齐）。
     * 判据用「会话实际构建时的值」（BrowserSessionManager.sessionsDesktopMode）：
     * 会话由进程级单例持有、Activity 重建不重建会话——用实例字段会在重建后漏判（曾经如此）。
     */
    private fun applyDesktopModeIfChanged() {
        val current = prefs.desktopMode
        val built = sessionManager.sessionsDesktopMode
        if (built == null || built == current) return
        rebuildAllTabsForDesktop()
        toast(if (current) R.string.toast_desktop_on else R.string.toast_desktop_off)
    }

    /** 存为 PDF：调用 GeckoView 内核原生 saveAsPdf，保存到公共「下载」目录 */
    private fun savePageAsPdf() {
        val tab = sessionManager.activeTab ?: return
        val url = tab.url ?: return
        if (url.isBlank() || url.startsWith("about:")) {
            toast(R.string.more_need_page)
            return
        }
        tab.session.saveAsPdf().accept(
            { input ->
                if (input == null) {
                    runOnUiThread { toast(R.string.pdf_failed) }
                    return@accept
                }
                // ⚠️ 判定必须放在**启动协程之前**：lifecycleScope 随 Activity 销毁而取消，
                // 那时 launch 的块体**根本不会执行** —— writePdf 里的 input.use 一行不跑，
                // 内核给的 PDF 管道流就永远不关（只能等 GC 的 Cleaner 兜）。
                // 放进 runOnUiThread 与 toast 分支同口径：本 Runnable 执行时若已销毁，就当场
                // 把流关掉、放弃这次保存（宁可这次没存成，也不漏一个 fd）。
                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        runCatching { input.close() }
                        return@runOnUiThread
                    }
                    lifecycleScope.launch {
                        val ok = writePdf(input)
                        toast(if (ok) R.string.pdf_saved else R.string.pdf_failed)
                    }
                }
            },
            { _ -> runOnUiThread { toast(R.string.pdf_failed) } }
        )
    }

    /**
     * 打印网页：先让内核把页面渲染成 PDF 落到缓存，再交给系统打印框架。
     *
     * 为什么不用 `printPageContent()` + `PrintDelegate`：那条路上的 PDF 是内核**异步回调**给的，
     * 而打印框架在用户点「打印」之后要求 adapter **当即**交出数据 —— 时序对不上，得额外兜一层。
     * `saveAsPdf()` 产出的同样是内核渲染的 PDF，且能在协程里直接取用，时序简单得多。
     *
     * 临时文件在 adapter 的 `onFinish()` 里删除（成功/失败/取消都会走到），不留缓存残留。
     */
    private fun printCurrentPage() {
        val tab = sessionManager.activeTab ?: return
        val url = tab.url ?: return
        if (url.isBlank() || url.startsWith("about:")) {
            toast(R.string.more_need_page)
            return
        }
        tab.session.saveAsPdf().accept(
            { input ->
                if (input == null) {
                    runOnUiThread { toast(R.string.print_failed) }
                    return@accept
                }
                // 与「存为 PDF」同口径：判定要在启动协程**之前**，否则 Activity 已销毁时
                // 协程块体不执行、这个 PDF 管道流没人关（见那处的详细说明）
                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        runCatching { input.close() }
                        return@runOnUiThread
                    }
                    lifecycleScope.launch {
                        val file = withContext(Dispatchers.IO) { cachePrintPdf(input) }
                        if (file == null) {
                            toast(R.string.print_failed)
                            return@launch
                        }
                        startPrint(file)
                    }
                }
            },
            { _ -> runOnUiThread { toast(R.string.print_failed) } }
        )
    }

    /** 把内核产出的 PDF 流写进缓存目录；失败返回 null 并清掉半截文件 */
    private fun cachePrintPdf(input: java.io.InputStream): java.io.File? {
        val dir = java.io.File(cacheDir, "print")
        if (!dir.exists() && !dir.mkdirs()) return null
        // 一次打印一份**独立文件**，绝不复用固定名（N4）：
        // 打印框架是**晚读** —— onWrite 时才按**路径**打开这份文件。若两次打印共用
        // "TangSnow_print.pdf"，第二次的「写入」会改掉第一次还没读的内容 ⇒ 打出错的
        // PDF，且两次都报成功。「fd 仍指向旧 inode」的论证救不了场：框架手里是**路径**、
        // 不是 fd——那个论证成立的前提（框架已提前打开）在这里恰恰不成立。
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        var file = java.io.File(dir, "TangSnow_print_$stamp.pdf")
        var seq = 1
        while (file.exists()) { // 同一秒内连发两次打印的兜底（几乎不可达，但成本为零）
            file = java.io.File(dir, "TangSnow_print_${stamp}_$seq.pdf")
            seq++
        }
        // 旧文件只清**陈旧**残包：adapter 的 onFinish() 正常会删自己那份，进程被杀才走不到；
        // 1 小时阈值远大于任何打印会话，碰不到在途文件。
        val staleBefore = System.currentTimeMillis() - 60 * 60 * 1000
        dir.listFiles { f -> f.lastModified() < staleBefore }
            ?.forEach { runCatching { it.delete() } }
        return try {
            input.use { ins -> java.io.FileOutputStream(file).use { out -> ins.copyTo(out) } }
            file
        } catch (_: Throwable) {
            runCatching { file.delete() }
            null
        }
    }

    /** 交给系统打印框架；调用时机必须在主线程 */
    private fun startPrint(file: java.io.File) {
        val manager = getSystemService(android.print.PrintManager::class.java)
        if (manager == null) {
            // 设备没有打印服务（极少数裁剪系统）：如实告知，并清掉刚生成的临时文件
            runCatching { file.delete() }
            toast(R.string.print_failed)
            return
        }
        val adapter = io.github.tan_sno.tangsnow.ui.PrintPdfAdapter(
            file,
            getString(R.string.print_write_no_stream),
        ) {
            runCatching { file.delete() }
        }
        val jobName = "TangSnow_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        manager.print(jobName, adapter, android.print.PrintAttributes.Builder().build())
    }

    /** @return 是否写入成功（Android 10+ 写入公共下载，旧系统写入应用外部下载目录） */
    private suspend fun writePdf(input: java.io.InputStream): Boolean {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val name = "TangSnow_$stamp.pdf"
        // 落盘走统一出口（IS_PENDING 占位、写流失败清理、转正失败保留待重试、update 行数判定都在那里收口）。
        // 此前这里自己抄了一份 MediaStore 代码，与书签导出、内核流下载并成三份同构副本，
        // 且已漂成三种语义（本处把 update 留在 try 外 ⇒ 它抛异常就留 IS_PENDING=1 的幽灵行）。
        // 副本越少，越不会被改漏。
        //
        // ⚠️ `input.use` 放在**最外层**：writeToDownloads 在 `insert` 返回 null、或协程被取消时
        // 根本不会调用 write 回调，那时只有这里能关掉内核给的 PDF 管道流（放进去就漏一个 fd，
        // 要等 GC 的 Cleaner 兜）。
        // ⚠️ 但它**只兜得住"协程跑起来了、中途取消"**这一种；"launch 之前 Activity 就已销毁、
        // 块体压根不执行"是**更大**的窗口 —— 那种情况下本函数根本不会被调用，靠的是两个调用点
        // （saveAsPdf 的 accept 回调）在启动协程前自行判 isFinishing/isDestroyed 并当场 close。
        return input.use { body ->
            // 用 saveAndRegister：writeToDownloads(register = true) **自己并不登记**，
            // 只传它会让存下来的 PDF 从不出现在系统「下载」与应用内列表里（X1）。
            DownloadRepo.saveAndRegister(this, name, "application/pdf") { out ->
                body.copyTo(out)
            }
        }
    }

    private fun showMoreSheet() {
        hideKeyboard()
        refreshQuickRow()
        // 高度改为自适应：内容有几行就多高，整体更紧凑
        val panelLp = binding.morePanel.layoutParams
        panelLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        binding.morePanel.layoutParams = panelLp

        // 先**显式测量**再读高度：此前把整段动画放进 post{}，首帧时视图尚未布局，
        // `morePanel.height` 仍是 0 → translationY 被设为 0 → 面板直接“闪现”，
        // 上弹动画在第一次打开时看不见（第二次才正常）。
        //
        // 取消在途动画必须发生在 `isVisible = true` **之前**：hideMoreSheet 的收起动画
        // 挂了 `withEndAction { isVisible = false }`，快速反复开关时若它仍在跑，
        // 结束回调会在我们置为可见之后触发，把刚显示的面板又设成不可见 ——
        // 结果是「只剩遮罩、面板不见、页面也点不动」。先 cancel 让该回调先跑完，
        // 随后再置可见，顺序上就不再有这个窗口（与 hideMoreSheet 的 cancel 对称）。
        binding.moreScrim.animate().cancel()
        binding.morePanel.animate().cancel()
        binding.moreSheet.isVisible = true
        val slide = measurePanelHeight().toFloat()
        binding.morePanel.translationY = slide
        binding.moreScrim.alpha = 0f
        binding.moreScrim.animate().alpha(1f).setDuration(180).start()
        // 上弹：底部面板从下方滑入（减速，快速且顺滑）
        binding.morePanel.animate()
            .translationY(0f)
            .setDuration(220)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.8f))
            .start()
    }

    /**
     * 面板滑入/滑出所需的位移量（面板高度）。
     * 优先用显式测量结果（未布局时也有效），测量失败再退回已布局高度。
     */
    private fun measurePanelHeight(): Int {
        val panel = binding.morePanel
        val width = binding.moreSheet.width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        panel.measure(widthSpec, heightSpec)
        return panel.measuredHeight.takeIf { it > 0 } ?: panel.height
    }

    internal fun hideMoreSheet() {
        if (!binding.moreSheet.isVisible) return
        // 先取消可能在途的上弹动画，避免快速开关时动画叠加闪烁
        binding.moreScrim.animate().cancel()
        binding.morePanel.animate().cancel()
        // 下滑收起：遮罩淡出 + 面板滑下（加速，快而干净）
        binding.moreScrim.animate().alpha(0f).setDuration(140).start()
        binding.morePanel.animate()
            .translationY(measurePanelHeight().toFloat())
            .setDuration(160)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.6f))
            .withEndAction { binding.moreSheet.isVisible = false }
            .start()
    }

    /** 退出确认框引用：面板收起动画有 160ms 窗口，快速双击「退出」行会叠出两个确认框 */
    private var exitConfirmDialog: androidx.appcompat.app.AlertDialog? = null

    private fun confirmExit() {
        if (exitConfirmDialog?.isShowing == true) return
        exitConfirmDialog = AlertDialog.Builder(this)
            .setTitle(R.string.dlg_exit_title)
            .setMessage(R.string.dlg_exit_message)
            .setNegativeButton(R.string.dlg_cancel, null)
            .setPositiveButton(R.string.dlg_ok) { _, _ -> performExit() }
            .show()
    }

    /** 退出流程进行中标记：异步清除 → 关停 → finishAffinity 期间屏蔽二次触发 */
    private var exiting = false

    /**
     * 统一的退出收口（[confirmExit] 与 [confirmExitByDoubleBack] 两条路径共用，
     * 避免「菜单退出清了数据、双击退出没清」这类分叉）：
     *  - 开关关闭：原语义直接退出（会话快照由 onPause 正常落盘）；
     *  - 开关开启：「退出即不留痕」——复用 [ClearDataUseCase] 清除 Cookie与站点数据、
     *    缓存、历史与标签页会话快照后退出。清除结果**如实**反馈：失败/部分失败照样
     *    退出，但绝不说「已清除」（本仓库禁假反馈）。markPurged 置位后，紧随其后的
     *    onPause → saveState 会跳过，刚清掉的快照不会被写回。
     * 退出动作不受清除结果影响：清除只负责如实报告，退出必然执行。
     *
     * ## 为什么跑在**进程级作用域**（2026-10-01，原施工待办 #27）
     *
     * 此前跑在 `lifecycleScope`，理由是"内核清除必须在 runtime 存活期间、先于 `finishAffinity()`
     * 完成"。那个约束仍然成立，但它漏了另一面：`lifecycleScope` **也会被配置变更取消**
     * （清单的 configChanges 不含 uiMode/locale）⇒ 清除被打断，且 `shutdown()`/`finishAffinity()`
     * 都不再执行 ⇒ **「退出被静默放弃」**（比"清了一半"更糟：应用既没退出、也没清完）。
     *
     * 现按 Mozilla 处理同一类问题的口径改（Fenix issue #5279「退出时删数据导致 ANR」的修法是
     * **不要在退出路径里阻塞/等待**；Fennec 的 Shutdown 文档同样强调"清理与 UI 关闭之间存在竞态"）：
     *  ① 清除 + 关停放进**进程级作用域**（不再被重建取消）；
     *  ② `finishAffinity()` **立刻**执行 —— 用户马上看到"已退出"，不产生 ANR；
     *  ③ 顺序仍是 **清 → shutdown()**（先关内核会把清理踩断）；
     *  ④ 残留风险如实记：进程若在清理完成前被系统杀掉，清理可能只完成一部分 —— 这与 Mozilla 的
     *    结论一致（"划掉最近任务"那条路他们也没有可靠解法）。本应用只有"应用内主动退出"走这里
     *    （设置文案已如实枚举）；系统最近任务划掉等外部退出不在此列。
     */
    private fun performExit() {
        if (exiting) return
        exiting = true
        if (!prefs.exitClearBrowsingData) {
            sessionManager.saveState()
            SessionStore.markPurged()
            detachActiveSession()
            sessionManager.shutdown()
            finishAffinity()
            return
        }
        val ctx = applicationContext
        ApplicationScope.scope.launch {
            val result: ClearDataUseCase.Result? = try {
                ClearDataUseCase.clear(
                    ctx,
                    ClearDataUseCase.Options(
                        cookiesAndSiteData = true,
                        cache = true,
                        history = true,
                        sessionSnapshot = true,
                    ),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
            // 提示与关停都要回主线程：Toast 需要主线程 Looper，内核关停也按主线程口径设计。
            // 用 application context：此时界面可能已经 finish。
            withContext(Dispatchers.Main) {
                ctx.toast(
                    when {
                        result == null || !result.kernelOk -> R.string.toast_data_clear_failed
                        result.localFailedCount > 0 -> R.string.toast_data_partially_cleared
                        else -> R.string.toast_data_cleared
                    }
                )
                detachActiveSession()
                sessionManager.shutdown()
            }
        }
        // 立刻退出界面：不等待清理（Fenix 的 ANR 修法就是这个方向）
        finishAffinity()
    }

    /**
     * 冷启动的**异步**会话恢复（Fenix 口径，2026-10-01 CR-012）。
     *
     * 只在前一条路径（内存里的预读结果）没赶上时调用：先在 IO 线程读快照，回到主线程后
     * **只在用户还没动过**的前提下整表恢复。判据取最保守的一种 —— 仍只有最初那个空白标签、
     * 它仍是活动标签、且它没有导航过；否则用户自己开的页面会被顶掉（这是异步化必须付的代价）。
     * 恢复顺序与活动标签由 [SessionManager.restoreSession] 保证，与快路径一致。
     */
    private fun restoreSnapshotAsync() {
        ApplicationScope.scope.launch {
            val snapshot = runCatching { SessionStore.readAsync(applicationContext) }.getOrNull()
                ?: return@launch
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                val only = sessionManager.tabs.singleOrNull() ?: return@withContext
                if (sessionManager.activeTab !== only) return@withContext
                val url = only.url
                if (!url.isNullOrBlank() && !url.startsWith("about:")) return@withContext
                // restoreSession 要求会话池为空 ⇒ 先关掉这个占位标签；恢复失败则补回空白标签，
                // 绝不留下"零标签"的空窗。
                sessionManager.closeTab(only)
                val restored = sessionManager.restoreSession(snapshot)
                if (restored == null) {
                    createTab(url = null)
                    showHome()
                    return@withContext
                }
                binding.geckoView.setSession(restored.session)
                syncViewWithTab(restored)
                updateTabsBadge()
            }
        }
    }

    // ------------------------------------------------------------- 工具

    private fun isSubmit(actionId: Int, event: KeyEvent?): Boolean =
        actionId == EditorInfo.IME_ACTION_SEARCH ||
            actionId == EditorInfo.IME_ACTION_GO ||
            (event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN)

    internal fun hideKeyboard() {
        val token = currentFocus?.windowToken ?: return
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(token, 0)
    }

    internal fun showKeyboard(view: View) {
        view.post {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                // 0 = 默认标志，等同已弃用的 SHOW_IMPLICIT
                ?.showSoftInput(view, 0)
        }
    }

    internal fun toast(res: Int) {
        android.widget.Toast.makeText(this, res, android.widget.Toast.LENGTH_SHORT).show()
    }

    internal fun toast(res: Int, vararg args: Any) {
        android.widget.Toast.makeText(
            this,
            getString(res, *args),
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }
}
