package io.github.tan_sno.tangsnow

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.tan_sno.tangsnow.GeckoHolder
import io.github.tan_sno.tangsnow.databinding.ActivityExtensionsBinding
import io.github.tan_sno.tangsnow.databinding.ItemExtensionCatalogBinding
import io.github.tan_sno.tangsnow.databinding.ItemExtensionInstalledBinding
import io.github.tan_sno.tangsnow.extension.ExtInstallCoordinator
import io.github.tan_sno.tangsnow.extension.ExtensionCatalog
import io.github.tan_sno.tangsnow.extension.ExtensionPrompts
import io.github.tan_sno.tangsnow.util.awaitResult
import io.github.tan_sno.tangsnow.util.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import java.io.File

/**
 * 扩展（Firefox 式双页签）：
 *  - 「可安装」：精选 Mozilla 官方签名扩展；图标/名称/直链实时对接 AMO 官方 API，
 *    点击「获取」即通过 GeckoView 真实安装；官方直链失败时提供「打开官方扩展页」兜底；
 *  - 「已安装」：列出运行时真实加载的 WebExtensions，支持启用/停用/卸载；
 *  - 「自定义安装」：粘贴任意官方 .xpi 直链安装；
 *  - 已实现 WebExtensionController.PromptDelegate：从官方网页发起的安装/权限请求
 *    会弹出确认框，不会被静默拒绝；
 *  - 下载阶段：使用 OkHttp 流式读取，UI 实时显示「下载 %d%%」；3 分钟总时长兜底；
 *    失败/超时 100% 通过看门狗反馈，绝不卡在「安装中」。
 */
class ExtensionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExtensionsBinding
    private val catalogAdapter = CatalogAdapter()
    private val installedAdapter = InstalledAdapter()

    /** 已安装扩展行 */
    private data class InstalledRow(
        val ext: WebExtension,
        val name: String,
        val version: String,
        val enabled: Boolean,
    )

    private val installedRows = mutableListOf<InstalledRow>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityExtensionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.tabRecommend.setOnClickListener { switchTo(TAB_RECOMMEND) }
        binding.tabInstalled.setOnClickListener { switchTo(TAB_INSTALLED) }

        binding.recommendRecycler.layoutManager = LinearLayoutManager(this)
        binding.recommendRecycler.adapter = catalogAdapter
        binding.installedRecycler.layoutManager = LinearLayoutManager(this)
        binding.installedRecycler.adapter = installedAdapter

        binding.btnInstallUrl.setOnClickListener { installFromUrl() }
        binding.btnImportFile.setOnClickListener { launchFileImport() }
        setupCustomInstallCollapse()

        setupPromptDelegate()
        // 扩展 Action/Tab 委托：让工具栏按钮/面板、tabs.create、选项页可用；
        // 主界面也会挂，故销毁时按「持有方集合」解绑，最后一个持有方退出才真正清
        controller()?.let { ExtensionPrompts.mountExtensionDelegates(it, this) }
        switchTo(TAB_RECOMMEND)
        refreshInstalled()
        hydrateCatalog()
        // 孤儿安装态在 onResume 统一清理（可提示用户“中断可重试”）
    }

    override fun onResume() {
        super.onResume()
        // 进程被回收/Activity 重建后，清理上轮遗留的“安装中”孤儿状态；
        // 回到页面时若发现中断的安装则提示用户可重试
        pruneStaleInstalls(notifyInterrupted = true)
    }

    /**
     * 自愈清理“安装中”孤儿状态：
     *  - 有启动时间但无进行中作业 = 上个实例被销毁中断 → 清除并（回到页面时）提示；
     *  - 无时间戳/超时过久 = 陈旧孤儿 → 静默清除，避免按钮永远卡在“安装中”。
     */
    private fun pruneStaleInstalls(notifyInterrupted: Boolean = false) {
        val slugs = ExtensionCatalog.installing.toList()
        for (slug in slugs) {
            // 作业表由协调器持有；本页新实例的作业表里不会有上个实例的条目
            val resultPending =
                runCatching { installCoordinator.isWorking(ExtInstallCoordinator.catalogKey(slug)) }
                    .getOrDefault(false)
            val started = ExtensionCatalog.installStartedAt[slug]
            val tooOld = started == null ||
                android.os.SystemClock.elapsedRealtime() - started > STALE_PRUNE_MS
            if (!resultPending && started != null && !tooOld) {
                // 上次安装仍“进行中”却被中断（作业已随旧实例销毁）
                ExtensionCatalog.installing.remove(slug)
                ExtensionCatalog.installStartedAt.remove(slug)
                catalogAdapter.notifyItemChanged(slugIndex(slug))
                if (notifyInterrupted) toast(R.string.extension_install_interrupted)
            } else if (!resultPending && tooOld) {
                ExtensionCatalog.installing.remove(slug)
                ExtensionCatalog.installStartedAt.remove(slug)
                catalogAdapter.notifyItemChanged(slugIndex(slug))
            }
        }
    }

    override fun onDestroy() {
        destroyed = true
        // 取消全部在途安装：协程挂在 lifecycleScope 上，这里显式收口并清理临时包，
        // 否则它们会持有本 Activity 引用并在销毁后继续回调（BadToken 崩溃/泄漏）
        runCatching { installCoordinatorRef?.cancelAll() }
        importingFile = false
        urlInstalling = false
        runCatching {
            File(cacheDir, "exts").listFiles { f -> f.name.startsWith("import-") }
                ?.forEach { it.delete() }
        }
        // 展示中的安装/权限确认框必须先关闭并应答：否则页面侧 GeckoResult 永不完成
        //（安装流程永久挂起），且对话框会作为泄漏窗口留在已销毁的 Activity 上。
        // 只关闭本 Activity 持有的弹窗 —— MainActivity 销毁时不得误取消本页的活动弹窗。
        ExtensionPrompts.cancelAllPending(this)
        // PromptDelegate 属于本 Activity：无论是否有进行中任务都必须解绑，
        // 否则 controller（进程级）将永久持有已销毁的 Activity
        forceReleasePromptDelegate()
        // 解绑本页挂载的扩展 Action/Tab 委托（与 mount 配对；持有方集合清空才真正清）
        controller()?.let { ExtensionPrompts.unmountExtensionDelegates(it, this) }
        super.onDestroy()
    }

    // ------------------------------------------------------------- 页签

    private fun switchTo(tab: Int) {
        fun style(tv: TextView, active: Boolean) {
            tv.setTextColor(
                ContextCompat.getColor(
                    this, if (active) R.color.accent else R.color.accent_muted
                )
            )
            tv.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            tv.setBackgroundResource(
                if (active) R.drawable.bg_tab_active else android.R.color.transparent
            )
        }
        style(binding.tabRecommend, tab == TAB_RECOMMEND)
        style(binding.tabInstalled, tab == TAB_INSTALLED)
        binding.recommendPage.isVisible = tab == TAB_RECOMMEND
        binding.installedPage.isVisible = tab == TAB_INSTALLED
        if (tab == TAB_INSTALLED) refreshInstalled()
        if (tab == TAB_RECOMMEND) {
            // 每次回到「可安装」页都整体重绑：安装/受限状态可能在别处或后台已经变化，
            // 只靠局部 notifyItemChanged 会留下「按钮看着能点、点下去却没反应」的陈旧态
            catalogAdapter.notifyDataSetChanged()
            // 复查受限项（网络变化后自动恢复可装状态）
            refreshBlockedStatus()
        }
    }

    // ------------------------------------------------------------- 官方数据对接

    /** 并行拉取各扩展的官方图标 / 名称 / 当前版本直链（只访问 AMO 官方域名） */
    private fun hydrateCatalog() {
        lifecycleScope.launch {
            supervisorScope {
                ExtensionCatalog.all.map { entry ->
                    async { ExtensionCatalog.hydrate(entry) }
                }.awaitAll()
            }
            runOnUiThread { catalogAdapter.notifyDataSetChanged() }
        }
    }

    /**
     * 复查“地区受限”项：只对被标记过的 slug 重新探测一次 AMO（网络环境变化后自动恢复），
     * 从不开全量目录请求。
     */
    private fun refreshBlockedStatus() {
        val slugs = ExtensionCatalog.regionBlocked.toList()
        if (slugs.isEmpty()) return
        val entries = slugs.mapNotNull { slug ->
            ExtensionCatalog.all.firstOrNull { it.slug == slug }
        }
        if (entries.isEmpty()) return
        lifecycleScope.launch {
            supervisorScope {
                entries.map { entry -> async { ExtensionCatalog.hydrate(entry) } }.awaitAll()
            }
            runOnUiThread { catalogAdapter.notifyDataSetChanged() }
        }
    }

    // ------------------------------------------------------------- 安装

    private fun controller(): WebExtensionController? =
        GeckoHolder.runtime?.webExtensionController

    /**
     * 三路安装（目录 / 官方链接 / 本地 .xpi）统一交给 [ExtInstallCoordinator]：
     * 它持有唯一的作业表与总超时兜底，并补上此前缺失的「先下载到本地再交给内核安装」环节
     * （内核内置下载器在部分网络下会长时间无响应，正是「点「获取」没反应」的成因）。
     *
     * 惰性创建但持有引用：onDestroy 只对**已创建**的实例收尾，绝不在销毁路径上新建对象。
     */
    private var installCoordinatorRef: ExtInstallCoordinator? = null

    private val installCoordinator: ExtInstallCoordinator
        get() = installCoordinatorRef
            ?: ExtInstallCoordinator(this, lifecycleScope).also { installCoordinatorRef = it }

    // ------------------------------------------------------------- 自定义链接安装（与目录安装同一内核通道）

    /** 自定义链接安装的界面忙态（真正的并发去重由协调器按来源键负责） */
    private var urlInstalling = false

    // ------------------------------------------------------------- 本地 .xpi 文件导入

    /** 本地 xpi 选择器（系统文件选择器）；选择后复制进缓存再交给内核安装 */
    private val importXpiLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importXpi(uri)
    }

    /** 本地导入的界面忙态（仅复制阶段；安装阶段由协调器统一管理） */
    private var importingFile = false

    /**
     * 目录「获取」：统一交给 [ExtInstallCoordinator]。
     * 链路 = 官方直链 → OkHttp 流式下载到缓存（界面实时显示「下载 n%」）→ 内核校验 Mozilla
     * 签名并安装；自建下载不可用时由协调器自动回退内核内置下载通道。
     *
     * 注意：**每个分支都必须给出可见反馈**。旧实现在「该 slug 已在安装中」时直接 return，
     * 一旦界面态未及时刷新，用户看到的就是「点了「获取」毫无反应」。
     */
    private fun installEntry(entry: ExtensionCatalog.Entry) {
        val extController = controller() ?: run {
            toast(R.string.extension_need_runtime)
            return
        }
        if (entry.slug in ExtensionCatalog.regionBlocked) {
            showRegionBlocked(entry)
            return
        }
        if (entry.slug in ExtensionCatalog.installing) {
            toast(R.string.extension_installing)
            return
        }
        ensurePromptDelegate(extController)
        startInstall(entry, extController)
    }

    /**
     * 幂等确保内核上挂着**本页**的安装确认委托。
     *
     * 为什么必须在安装前确认：内核在下载并校验签名之后，会通过
     * `PromptDelegate.onInstallPromptRequest` 征求权限；若此刻 controller 上没有委托，
     * 内核就永远等不到这次裁决 —— 外部表现就是「一直安装中」且**没有任何弹窗**。
     * （controller 是进程级的，委托可能被主界面覆盖或被销毁路径解绑，故安装前复核一次。）
     */
    private fun ensurePromptDelegate(extController: WebExtensionController) {
        val current = runCatching { extController.promptDelegate }.getOrNull()
        if (current != null && current === promptDelegate) {
            return
        }
        val delegate = ExtensionPrompts.createDelegate(this)
        promptDelegate = delegate
        runCatching { extController.promptDelegate = delegate }
    }

    private fun showRegionBlocked(entry: ExtensionCatalog.Entry) {
        // 销毁守卫：Activity 已销毁/正在销毁时弹窗会抛 BadTokenException
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle(R.string.extension_region_blocked_title)
            .setMessage(
                getString(
                    R.string.extension_region_blocked_message,
                    entry.name,
                )
            )
            .setNegativeButton(R.string.dlg_ok, null)
            .setNeutralButton(R.string.extension_open_official) { _, _ ->
                BrowserOpener.open(this, entry.officialPage)
            }
            .show()
    }

    /** 目录安装：由协调器「下载 → 内核校验签名 → 安装」，界面按阶段/进度实时刷新 */
    private fun startInstall(entry: ExtensionCatalog.Entry, extController: WebExtensionController) {
        ExtensionCatalog.installing.add(entry.slug)
        ExtensionCatalog.installStartedAt[entry.slug] = android.os.SystemClock.elapsedRealtime()
        catalogAdapter.notifyItemChanged(slugIndex(entry.slug))

        val accepted = installCoordinator.install(
            ExtInstallCoordinator.Source.Catalog(entry),
            extController,
            object : ExtInstallCoordinator.Callback {
                override fun onSuccess(source: ExtInstallCoordinator.Source, ext: WebExtension?) {
                    finishInstall(entry)
                    ext?.let { ExtensionCatalog.installedIdsBySlug[entry.slug] = it.id }
                    toast(R.string.extension_install_success)
                    refreshInstalled()
                    catalogAdapter.notifyItemChanged(slugIndex(entry.slug))
                    // 装完打开扩展自己的管理界面（先取最新元数据定位）
                    scheduleManagePage(ext?.id)
                }

                override fun onFailure(source: ExtInstallCoordinator.Source, err: Throwable?) {
                    finishInstall(entry)
                    catalogAdapter.notifyItemChanged(slugIndex(entry.slug))
                    showInstallFailure(entry, err)
                }
            }
        )
        if (!accepted) {
            // 协调器已有同一来源在途：撤销刚置的界面态并如实提示，绝不留「无反应」
            finishInstall(entry)
            toast(R.string.extension_installing)
        }
    }

    /** 收尾：退出「安装中」状态（作业表与临时包由协调器负责摘除/清理） */
    private fun finishInstall(entry: ExtensionCatalog.Entry) {
        ExtensionCatalog.installing.remove(entry.slug)
        ExtensionCatalog.installStartedAt.remove(entry.slug)
        releasePromptDelegateIfIdle()
    }

    /** 安装失败：说明原因 + 打开官方扩展页 / 重试（一切以官方渠道为准） */
    private fun showInstallFailure(entry: ExtensionCatalog.Entry, err: Throwable?) {
        if (isFinishing || isDestroyed) return
        val reason = describeInstallError(err)
        val body = getString(R.string.extension_install_failed_message, entry.name, reason)
        AlertDialog.Builder(this)
            .setTitle(R.string.extension_install_failed_title)
            .setMessage(body)
            .setNegativeButton(R.string.dlg_cancel, null)
            .setNeutralButton(R.string.extension_open_official) { _, _ ->
                BrowserOpener.open(this, entry.officialPage)
            }
            .setPositiveButton(R.string.extension_retry) { _, _ -> installEntry(entry) }
            .show()
    }

    private fun describeInstallError(err: Throwable?): String {
        // 一律先落日志：界面给中文可行动提示，排障靠日志拿到原始异常
        android.util.Log.w(TAG, "extension install failed: ${err?.javaClass?.name}: ${err?.message}", err)
        if (err == null) {
            return getString(R.string.extension_error_download)
        }
        if (err is ExtInstallCoordinator.InstallTimeoutException) {
            // 安装请求已交给内核，但内核在期限内没有任何回应
            return getString(R.string.extension_error_engine_no_response)
        }
        if (err is java.util.concurrent.TimeoutException ||
            err is kotlinx.coroutines.TimeoutCancellationException
        ) {
            return getString(R.string.extension_error_timeout)
        }
        if (err !is WebExtension.InstallException) {
            // 网络栈 / IO 等非内核异常，message 多为英文技术串，直接摊给中文用户没有意义
            return getString(R.string.extension_error_download)
        }
        return when (err.code) {
            WebExtension.InstallException.ErrorCodes.ERROR_NETWORK_FAILURE ->
                getString(R.string.extension_error_network)
            WebExtension.InstallException.ErrorCodes.ERROR_INVALID_DOMAIN ->
                getString(R.string.extension_error_domain)
            WebExtension.InstallException.ErrorCodes.ERROR_SIGNEDSTATE_REQUIRED ->
                getString(R.string.extension_error_signature)
            WebExtension.InstallException.ErrorCodes.ERROR_CORRUPT_FILE,
            WebExtension.InstallException.ErrorCodes.ERROR_INCORRECT_HASH ->
                getString(R.string.extension_error_corrupt)
            WebExtension.InstallException.ErrorCodes.ERROR_BLOCKLISTED,
            WebExtension.InstallException.ErrorCodes.ERROR_SOFT_BLOCKED ->
                getString(R.string.extension_error_blocklisted)
            else -> getString(R.string.extension_error_code, err.code)
        }
    }

    /**
     * “自定义安装”收口为二级：先展开方式列表，再选 xpi 导入 / 官方链接（互斥展开），
     * 收起方式后各自的输入不残留，界面更干净。
     */
    private fun setupCustomInstallCollapse() {
        binding.customMethods.isVisible = false
        binding.customHeader.setOnClickListener {
            val open = !binding.customMethods.isVisible
            binding.customMethods.isVisible = open
            binding.arrowCustom.text = if (open) "▾" else "▸"
        }
        // 前往官方扩展商店：独立可点，不触发上面那一行的展开/收起（子 View 可点会拦下事件）
        binding.linkOfficialStore.setOnClickListener {
            BrowserOpener.open(this, ExtensionCatalog.OFFICIAL_STORE_URL)
        }
        fun setPanel(panel: View?, arrow: TextView, open: Boolean) {
            panel?.isVisible = open
            arrow.text = if (open) "▾" else "▸"
        }
        binding.rowXpiImport.setOnClickListener {
            val open = !binding.xpiPanel.isVisible
            setPanel(binding.xpiPanel, binding.arrowXpi, open)
            if (open) setPanel(binding.linkPanel, binding.arrowLink, false)
        }
        binding.rowLinkInstall.setOnClickListener {
            val open = !binding.linkPanel.isVisible
            setPanel(binding.linkPanel, binding.arrowLink, open)
            if (open) setPanel(binding.xpiPanel, binding.arrowXpi, false)
        }
    }

    private fun installFromUrl() {
        if (urlInstalling) {
            toast(R.string.extension_installing)
            return
        }
        val url = binding.urlInput.text.toString().trim()
        // 与产品口径一致：扩展只走 Mozilla 官方源（AMO），不接受任意第三方地址
        val host = android.net.Uri.parse(url).host.orEmpty()
        if (!url.startsWith("https://") || !host.equals("addons.mozilla.org", ignoreCase = true)) {
            toast(R.string.extension_url_invalid)
            return
        }
        val extController = controller() ?: run {
            toast(R.string.extension_need_runtime)
            return
        }
        binding.urlInput.setText("")
        urlInstalling = true
        // 自定义链接没有进度控件：至少给一次「已开始」的即时反馈，消除「点了没反应」的观感
        toast(R.string.extension_install_started)
        val accepted = installCoordinator.install(
            ExtInstallCoordinator.Source.Remote(getString(R.string.extension_custom_url), url),
            extController,
            object : ExtInstallCoordinator.Callback {
                override fun onSuccess(source: ExtInstallCoordinator.Source, ext: WebExtension?) {
                    urlInstalling = false
                    toast(R.string.extension_install_success)
                    refreshInstalled()
                    // 自定义安装同样在完成后打开该扩展自己的管理界面
                    scheduleManagePage(ext?.id)
                }

                override fun onFailure(source: ExtInstallCoordinator.Source, err: Throwable?) {
                    urlInstalling = false
                    toast(describeInstallError(err))
                }
            }
        )
        if (!accepted) {
            urlInstalling = false
            toast(R.string.extension_installing)
        }
    }

    // ------------------------------------------------------------- 本地 .xpi 文件导入

    /** 打开系统文件选择器挑选 .xpi（任何类型都放行，真正的校验在复制后做） */
    private fun launchFileImport() {
        if (controller() == null) {
            toast(R.string.extension_need_runtime)
            return
        }
        importXpiLauncher.launch(arrayOf("*/*"))
    }

    /** 导入流程：复制进缓存 → 校验包 → file:// 交给内核（与网页安装同一签名校验） */
    private fun importXpi(uri: android.net.Uri) {
        if (importingFile) return
        importingFile = true
        setImportButtonBusy(true)
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { copyImportedXpi(uri) }
            if (file == null) {
                importingFile = false
                setImportButtonBusy(false)
                toast(R.string.extension_import_invalid)
                return@launch
            }
            installImportedFile(file)
        }
    }

    /** 把所选文件复制为应用缓存里的 .xpi 并校验（PK 头 + 体积下限）；失败返回 null */
    private fun copyImportedXpi(uri: android.net.Uri): File? {
        val dir = File(cacheDir, "exts")
        dir.mkdirs()
        // 清掉历史遗留的导入残包（上次导入若中途被销毁会留下）
        dir.listFiles { f -> f.name.startsWith("import-") }?.forEach { runCatching { it.delete() } }
        val out = File(dir, "import-${System.currentTimeMillis()}.xpi")
        return try {
            // 手动流式复制并设体积上限：本地文件来源不受控，防超大文件/解压炸弹类填满缓存
            val copied = contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().buffered().use { sink ->
                    val buf = ByteArray(64 * 1024)
                    var size = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        size += n
                        if (size > MAX_XPI_BYTES) {
                            throw java.io.IOException("xpi exceeds size limit")
                        }
                        sink.write(buf, 0, n)
                    }
                }
                true
            } == true
            if (!copied) return null
            val isZip = out.inputStream().use { ins ->
                val head = ByteArray(2)
                ins.read(head) == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
            }
            if (out.length() < 1024 || !isZip) {
                out.delete()
                null
            } else out
        } catch (e: Exception) {
            out.delete()
            null
        }
    }

    /** 把缓存里的 .xpi 交给统一协调器安装（file:// + 内核 Mozilla 签名校验） */
    private fun installImportedFile(file: File) {
        val extController = controller() ?: run {
            file.delete()
            finishFileImport()
            toast(R.string.extension_need_runtime)
            return
        }
        val accepted = installCoordinator.install(
            ExtInstallCoordinator.Source.LocalFile(file.name, file),
            extController,
            object : ExtInstallCoordinator.Callback {
                override fun onSuccess(source: ExtInstallCoordinator.Source, ext: WebExtension?) {
                    file.delete()
                    finishFileImport()
                    toast(R.string.extension_install_success)
                    refreshInstalled()
                    // 装完打开扩展自己的管理页（先取最新元数据）
                    scheduleManagePage(ext?.id)
                }

                override fun onFailure(source: ExtInstallCoordinator.Source, err: Throwable?) {
                    file.delete()
                    finishFileImport()
                    showFileImportFailure(err)
                }
            }
        )
        if (!accepted) {
            file.delete()
            finishFileImport()
            toast(R.string.extension_installing)
        }
    }

    private fun finishFileImport() {
        importingFile = false
        setImportButtonBusy(false)
    }

    private fun setImportButtonBusy(busy: Boolean) {
        if (::binding.isInitialized) {
            binding.btnImportFile.isEnabled = !busy
            binding.btnImportFile.setText(
                if (busy) R.string.extension_importing else R.string.extension_import_file
            )
        }
    }

    /** 导入失败：说明原因（含内核拒签等），提示用户换官方签名的包 */
    private fun showFileImportFailure(err: Throwable?) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle(R.string.extension_install_failed_title)
            .setMessage(getString(R.string.extension_import_failed_message, describeInstallError(err)))
            .setPositiveButton(R.string.dlg_ok, null)
            .show()
    }

    // ------------------------------------------------------------- 官方网页安装 / 权限确认

    /** 让 AMO 官方网页上的「添加到 Firefox」等安装请求弹出确认框（默认实现会静默拒绝） */
    private fun setupPromptDelegate() {
        val extController = controller() ?: return
        // 共享实现见 extension/ExtensionPrompts；MainActivity 也挂同一套，
        // 销毁时按引用比对只解绑自己的实例，互不抢占
        val delegate = ExtensionPrompts.createDelegate(this)
        promptDelegate = delegate
        extController.promptDelegate = delegate
    }

    private var promptDelegate: WebExtensionController.PromptDelegate? = null
    @Volatile
    private var destroyed = false

    private fun releasePromptDelegateIfIdle() {
        // 仅在 Activity 已销毁后解绑（存活的页面还需要它弹出权限确认）
        if (!destroyed) return
        forceReleasePromptDelegate()
    }

    /** 仅当 controller 上挂的仍是本 Activity 的 delegate 时解绑（不抢后续页面的） */
    private fun forceReleasePromptDelegate() {
        val delegate = promptDelegate ?: return
        promptDelegate = null
        runCatching {
            val controller = controller() ?: return@runCatching
            if (controller.promptDelegate === delegate) {
                controller.setPromptDelegate(null)
            }
        }
    }

    // ------------------------------------------------------------- 已安装列表

    private fun refreshInstalled() {
        val extController = controller() ?: run {
            renderInstalled(emptyList())
            return
        }
        extController.list().accept(
            { extensions ->
                runOnUiThread {
                    // 与失败分支一致地守卫：内核回调可能在 Activity 销毁后才回到主线程，
                    // 此时不该再刷新列表/通知适配器（早期只有失败分支做了守卫，不对称）
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val rows = (extensions ?: emptyList())
                        .filter { !it.id.isNullOrBlank() }
                        .map { ext ->
                            val md = ext.metaData
                            InstalledRow(
                                ext = ext,
                                name = md.name.orEmpty().ifBlank { ext.id },
                                version = md.version.orEmpty(),
                                enabled = (md.disabledFlags) == 0,
                            )
                        }
                    renderInstalled(rows)
                    catalogAdapter.notifyDataSetChanged()
                }
            },
            { _ ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    // 不再清空列表：内核/系统抖动导致的读取失败会把已安装的扩展
                    // 误显示为“暂无已安装扩展”，那是假信息。保留上一次结果并如实说明失败。
                    toast(R.string.extension_list_load_failed)
                    binding.installedEmpty.isVisible = installedRows.isEmpty()
                }
            }
        )
    }

    private fun renderInstalled(rows: List<InstalledRow>) {
        installedRows.clear()
        installedRows.addAll(rows)
        installedAdapter.notifyDataSetChanged()
        // 名称集合在此一次性算好交给目录适配器，避免每次绑定都重建（滚动路径上的重复计算）
        // 已安装 slug 集合在此一次性算好交给目录适配器，避免每次绑定都重建（滚动路径上的重复计算）；
        // 按 slug（解析自 AMO 列表页地址）精确匹配，杜绝同名扩展被误判为「已安装」
        catalogAdapter.setInstalledSlugs(rows.mapNotNull { ExtensionCatalog.slugOf(it.ext) }.toSet())
        binding.installedEmpty.isVisible = rows.isEmpty()
    }

    private fun toggleExtension(row: InstalledRow, enable: Boolean) {
        val extController = controller() ?: return
        val source = WebExtensionController.EnableSource.USER
        val result = if (enable) extController.enable(row.ext, source)
        else extController.disable(row.ext, source)
        result.accept(
            { _ -> runOnUiThread { refreshInstalled() } },
            { _ -> runOnUiThread { refreshInstalled() } }
        )
    }

    private fun uninstall(row: InstalledRow) {
        val extController = controller() ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.extension_uninstall_confirm)
            .setMessage(getString(R.string.extension_uninstall_message, row.name))
            .setNegativeButton(R.string.dlg_cancel, null)
            .setPositiveButton(R.string.dlg_ok) { _, _ ->
                extController.uninstall(row.ext).accept(
                    // 卸载成功：清会话内 slug 记录并同时刷新“已安装”与“可安装”两页状态
                    { _ ->
                        runOnUiThread {
                            ExtensionCatalog.forgetInstalled(row.ext.id)
                            refreshInstalled()
                        }
                    },
                    { _ -> runOnUiThread { refreshInstalled() } }
                )
            }
            .show()
    }

    // ------------------------------------------------------------- 扩展管理（齿轮 / 装后直达）

    /**
     * 齿轮菜单：每次点击都先按 id 拉取最新扩展元数据，再给出两项：
     *  1) 扩展信息 —— 版本/开发者/权限等只读信息；
     *  2) 扩展设置 —— 对接扩展自带的设置/管理页（options_ui），该扩展没有时置灰。
     * 之所以要现场刷新：安装回调、启停切换后行对象持有的元数据可能缺 options_ui，
     * 直接按旧对象判断会表现为「只有刚下载完能进设置」。
     */
    private fun showManageMenu(row: InstalledRow) {
        if (isFinishing || isDestroyed) return
        val controller = controller() ?: run {
            toast(R.string.extension_need_runtime)
            return
        }
        controller.list().accept(
            { list ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val fresh = list?.firstOrNull { it.id == row.ext.id }
                    if (fresh != null) renderManageMenu(fresh) else renderManageMenu(row.ext)
                }
            },
            { _ ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) renderManageMenu(row.ext)
                }
            }
        )
    }

    private fun renderManageMenu(ext: WebExtension) {
        if (isFinishing || isDestroyed) return
        val md = ext.metaData
        val name = md.name.orEmpty().ifBlank { ext.id }
        val optionsUrl = md.optionsPageUrl?.takeIf { it.isNotBlank() }

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.extension_manage_title))
            .setNegativeButton(R.string.dlg_cancel, null)
            .create()
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(
            menuRow(R.drawable.ic_extension, getString(R.string.extension_manage_info)) {
                dialog.dismiss()
                showExtensionInfo(ext, optionsUrl != null)
            }
        )
        list.addView(
            menuRow(
                R.drawable.ic_settings,
                getString(R.string.extension_manage_settings),
                enabled = optionsUrl != null,
            ) {
                dialog.dismiss()
                openExtensionSettings(ext, optionsUrl.orEmpty())
            }
        )
        if (optionsUrl == null) {
            list.addView(TextView(this).apply {
                text = getString(R.string.extension_no_settings_hint, name)
                textSize = 12f
                setTextColor(getColor(R.color.accent_muted))
                setPadding(dp(20), 0, dp(20), dp(14))
            })
        }
        dialog.setView(list)
        dialog.show()
    }

    /** 菜单里的一行（图标 + 文字）；[enabled]=false 时整体置灰且不可点 */
    private fun menuRow(
        iconRes: Int,
        label: String,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ): View {
        val typed = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, typed, true)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(13), dp(20), dp(13))
            setBackgroundResource(typed.resourceId)
            alpha = if (enabled) 1f else 0.38f
            if (enabled) setOnClickListener { onClick() }
        }
        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(getColor(R.color.accent))
        }
        row.addView(
            icon,
            LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(16) }
        )
        val text = TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(getColor(R.color.accent))
        }
        row.addView(text)
        return row
    }

    /** 扩展信息弹窗：版本 / 开发者 / 描述 / 权限，无内置设置页时如实提示 */
    private fun showExtensionInfo(ext: WebExtension, hasOptions: Boolean) {
        if (isFinishing || isDestroyed) return
        val md = ext.metaData
        val name = md.name.orEmpty().ifBlank { ext.id }
        val body = buildString {
            append(getString(R.string.extension_version, md.version.orEmpty().ifBlank { ext.id }))
            md.creatorName?.takeIf { it.isNotBlank() }?.let { creator ->
                append("\n").append(getString(R.string.extension_creator, creator))
            }
            md.description?.takeIf { it.isNotBlank() }?.let { desc ->
                append("\n\n").append(desc)
            }
            if (!hasOptions) {
                append("\n\n").append(getString(R.string.extension_no_settings_hint, name))
            }
            val perms = md.requiredPermissions.toList().filter { it.isNotBlank() }
            if (perms.isNotEmpty()) {
                append("\n\n").append(getString(R.string.extension_permissions_intro))
                perms.take(10).forEach { append("\n· ").append(it) }
            }
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(name)
            .setMessage(body)
            .setPositiveButton(R.string.dlg_ok, null)
        officialPageOf(ext)?.let { official ->
            builder.setNeutralButton(R.string.extension_open_official) { _, _ ->
                BrowserOpener.openNewTab(this@ExtensionsActivity, official)
            }
        }
        builder.show()
    }

    /** 打开扩展设置页；停用态先启用再开（停用态内置页不可达）。一律开新标签。 */
    private fun openExtensionSettings(ext: WebExtension, url: String) {
        if (isFinishing || isDestroyed || url.isBlank()) return
        if (ext.metaData.disabledFlags == 0) {
            BrowserOpener.openNewTab(this, url)
            return
        }
        val extController = controller() ?: run {
            toast(R.string.extension_need_runtime)
            return
        }
        extController.enable(ext, WebExtensionController.EnableSource.USER).accept(
            { _ ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) BrowserOpener.openNewTab(this@ExtensionsActivity, url)
                }
            },
            { _ -> runOnUiThread { toast(R.string.extension_manage_enable_failed) } }
        )
    }

    /**
     * 安装完成后稍候再打开扩展自己的功能管理界面。
     * 安装回调里返回的 WebExtension 元数据往往尚未完整就绪（常见缺 options_ui），
     * 直接按它回退会错误地跳到 AMO 商店页；这里等列表刷新后取最新元数据再定位。
     */
    private fun scheduleManagePage(extId: String?) {
        if (extId.isNullOrBlank() || isFinishing || isDestroyed) return
        binding.root.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            controller()?.list()?.accept(
                { list ->
                    runOnUiThread {
                        val fresh = list?.firstOrNull { it.id == extId }
                        if (fresh != null && !isFinishing && !isDestroyed) openManagePage(fresh)
                    }
                },
                { _ -> }
            )
        }, 400L)
    }

    /**
     * 打开扩展的官方功能管理界面（新标签）。
     * 只有扩展真正提供内置管理/设置页（options_ui，各扩展自己实现）时才自动打开；
     * 没有该页的扩展不打扰用户——商店页并非“功能管理”界面，留给齿轮菜单按需选择。
     */
    private fun openManagePage(ext: WebExtension?) {
        if (ext == null || isFinishing || isDestroyed) return
        val url = ext.metaData.optionsPageUrl?.takeIf { it.isNotBlank() } ?: return
        BrowserOpener.openNewTab(this, url)
    }

    /** 官方页地址：精选目录命中优先（本地化 AMO 详情），其次元数据里的 AMO 列表页 */
    private fun officialPageOf(ext: WebExtension): String? {
        val slug = ExtensionCatalog.installedIdsBySlug.entries
            .firstOrNull { it.value == ext.id }?.key
        if (slug != null) {
            ExtensionCatalog.all.firstOrNull { it.slug == slug }?.officialPage?.let { return it }
        }
        return ext.metaData.amoListingUrl?.takeIf { it.isNotBlank() }
    }

    private fun toast(res: Int) =
        android.widget.Toast.makeText(this, res, android.widget.Toast.LENGTH_SHORT).show()

    private fun slugIndex(slug: String): Int =
        ExtensionCatalog.all.indexOfFirst { it.slug == slug }


    // ------------------------------------------------------------- 可安装目录适配器

    inner class CatalogAdapter : RecyclerView.Adapter<CatalogAdapter.VH>() {

        /**
         * 已安装扩展名集合：由 [renderInstalled] 一次性算好后注入。
         * 早期在 onBindViewHolder 内每次 `installedRows.map { it.name }.toSet()`，
         * 列表滚动时是 O(条目数 × 已安装数) 的重复计算。
         */
        private var installedSlugs: Set<String> = emptySet()

        fun setInstalledSlugs(slugs: Set<String>) {
            installedSlugs = slugs
        }

        inner class VH(val binding: ItemExtensionCatalogBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH =
            VH(ItemExtensionCatalogBinding.inflate(layoutInflater, parent, false))

        override fun getItemCount(): Int = ExtensionCatalog.all.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = ExtensionCatalog.all[position]
            val installing = entry.slug in ExtensionCatalog.installing
            val installed = ExtensionCatalog.isInstalled(entry, installedSlugs)
            val blocked = entry.slug in ExtensionCatalog.regionBlocked
            val displayName = ExtensionCatalog.displayNames[entry.slug] ?: entry.name

            with(holder.binding) {
                if (entry.iconRes != null) {
                    avatarLetter.isVisible = false
                    avatarIcon.isVisible = true
                    avatarIcon.setImageResource(entry.iconRes)
                } else {
                    avatarLetter.isVisible = true
                    avatarIcon.isVisible = false
                    avatarLetter.text = entry.name.take(1)
                    avatarLetter.backgroundTintList =
                        ColorStateList.valueOf(ContextCompat.getColor(root.context, entry.colorRes))
                }

                txtName.text = displayName
                txtDesc.text = getString(entry.descRes)

                when {
                    blocked -> {
                        btnAction.isEnabled = false
                        btnAction.text = getString(R.string.extension_region_blocked)
                        btnAction.setBackgroundResource(R.drawable.bg_btn_pill_disabled)
                        btnAction.setTextColor(ContextCompat.getColor(root.context, R.color.accent_muted))
                    }
                    installing -> {
                        // 内核原生安装：无百分比，仅显示进行中（无本地下载阶段）
                        btnAction.isEnabled = false
                        btnAction.text = getString(R.string.extension_installing)
                        btnAction.setBackgroundResource(R.drawable.bg_btn_pill_disabled)
                        btnAction.setTextColor(ContextCompat.getColor(root.context, R.color.accent_muted))
                    }
                    installed -> {
                        btnAction.isEnabled = false
                        btnAction.text = getString(R.string.extension_installed_label)
                        btnAction.setBackgroundResource(R.drawable.bg_btn_pill_outline)
                        btnAction.setTextColor(ContextCompat.getColor(root.context, R.color.accent))
                    }
                    else -> {
                        btnAction.isEnabled = true
                        btnAction.text = getString(R.string.extension_get)
                        btnAction.setBackgroundResource(R.drawable.bg_btn_pill)
                        btnAction.setTextColor(ContextCompat.getColor(root.context, android.R.color.white))
                    }
                }
                btnAction.setOnClickListener {
                    if (!installing && !installed && !blocked) installEntry(entry)
                }
            }
        }
    }

    // ------------------------------------------------------------- 已安装适配器

    inner class InstalledAdapter : RecyclerView.Adapter<InstalledAdapter.VH>() {

        inner class VH(val binding: ItemExtensionInstalledBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH =
            VH(ItemExtensionInstalledBinding.inflate(layoutInflater, parent, false))

        override fun getItemCount(): Int = installedRows.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = installedRows[position]
            with(holder.binding) {
                txtName.text = row.name
                txtVersion.text = if (row.version.isBlank()) row.ext.id
                else getString(R.string.extension_version, row.version)
                switchOn.setOnCheckedChangeListener(null)
                switchOn.isChecked = row.enabled
                switchOn.setOnCheckedChangeListener { _, checked ->
                    toggleExtension(row, checked)
                }
                // 齿轮 = 扩展信息 / 扩展设置入口；整行点击同效
                btnManage.setOnClickListener { showManageMenu(row) }
                root.setOnClickListener { showManageMenu(row) }
                btnUninstall.setOnClickListener { uninstall(row) }
            }
        }
    }

    private companion object {
        const val TAG = "ExtensionsActivity"
        const val TAB_RECOMMEND = 0
        const val TAB_INSTALLED = 1
        /** 单个扩展包体积上限（约 200MB）：远超任何合法 AMO 扩展，纯为防滥用 */
        const val MAX_XPI_BYTES = 200L * 1024 * 1024
        /** 孤儿安装态自愈阈值（须大于协调器的总超时，留足余量） */
        const val STALE_PRUNE_MS = 200_000L
    }
}