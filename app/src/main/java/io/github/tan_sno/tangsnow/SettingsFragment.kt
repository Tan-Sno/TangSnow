package io.github.tan_sno.tangsnow

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.RecyclerView
import io.github.tan_sno.tangsnow.data.ClearDataUseCase
import io.github.tan_sno.tangsnow.data.PreferenceStore
import io.github.tan_sno.tangsnow.data.Theme
import io.github.tan_sno.tangsnow.data.ThemeController
import io.github.tan_sno.tangsnow.update.UpdateChecker
import io.github.tan_sno.tangsnow.util.warmUpFirstRows
import kotlinx.coroutines.launch

/**
 * 设置面板：把偏好变化按行为分类绑定。
 *  - 主题：立即套用并重建当前 Activity
 *  - 无痕模式：受 GeckoView 限制只能在创建 Session 时指定，故提示下次启动生效
 *  - 清除浏览数据：调用 runtime.storageController（带 try / catch 兜底）
 *  - 检查更新：读取更新清单（未接入时提示已是最新），发现新版本可跳转下载
 *  - 关于：弹出对应长文本
 */
class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        // 本页所有设置项都不使用图标：关掉图标占位列，省掉每行一次 ImageView 处理与一段测量
        preferenceScreen?.let { dropIconSpace(it) }
        bindEngineManager()
        bindAbout()
        bindTheme()
        bindLanguage()
        bindPrivateMode()
        bindDesktopMode()
        bindSessionRestore()
        bindTracking()
        bindClearData()
        bindSecureScreen()
        bindDefaultBrowser()
        bindUpdate()
        bindInfoDialogs()
        showBuildVersion()
    }

    /** 递归关闭图标占位（本页无任何图标，占位纯属浪费） */
    private fun dropIconSpace(group: PreferenceGroup) {
        for (i in 0 until group.preferenceCount) {
            val child = group.getPreference(i)
            child.isIconSpaceReserved = false
            if (child is PreferenceGroup) dropIconSpace(child)
        }
    }

    /**
     * 列表滚动性能 —— 针对「**第一次**下拉奇卡、之后顺滑」这一现象。
     *
     * 成因：偏好行是**惰性创建**的（只在真正可见时才 `inflate` + `bind`）。而开关行的控件是
     * `SwitchCompat`，在 Material3 主题下由 `?attr/switchStyle` 解析到 Material 的开关样式
     * （矢量 drawable + ColorStateList），行本身还带 `?android:attr/selectableItemBackground`
     * 水波纹。这些**类与资源的首次加载**如果恰好落在用户第一次滑动的手势里，就会明显掉帧；
     * 之后类和资源都热了，同样的滑动就顺了 —— 这正是「只有第一次卡」的原因。
     * （本类早先那版注释里的 `scrollbars` 只是另一半：它影响的是打开耗时与每次滑动的开销。）
     *
     * 对策（纯优化，任何异常都不影响功能）：
     *  1. 首帧之后（`post`，不占用打开耗时）把「首屏 + 其后一屏」的行预建、预绑一次并入回收池，
     *     用户真正滑动时直接命中成品，不在手势里做首次加载；
     *  2. 关闭图标占位（本页无图标）、关掉拉伸回弹（与首页/同意页一致）、多缓存几行。
     */
    override fun onCreateRecyclerView(
        inflater: LayoutInflater,
        parent: ViewGroup,
        savedInstanceState: Bundle?,
    ): RecyclerView {
        val rv = super.onCreateRecyclerView(inflater, parent, savedInstanceState)
        rv.isVerticalScrollBarEnabled = false
        rv.isHorizontalScrollBarEnabled = false
        rv.setHasFixedSize(true)
        rv.itemAnimator = null
        // 与首页 / 同意页保持一致：去掉拉伸回弹效果，首次下拉不再触发过度滚动效果
        rv.overScrollMode = View.OVER_SCROLL_NEVER
        // 多缓存几行，减少上下滑动时的重复绑定
        rv.setItemViewCacheSize(6)
        rv.post { rv.warmUpFirstRows() }
        return rv
    }
    override fun onResume() {
        super.onResume()
        // 从“系统默认应用”页返回时同步“已设为默认”摘要
        refreshDefaultBrowserSummary()
        // 设置从别的页面返回时，按当前模式刷新自定义开关的可点态
        refreshTrackingCustomEnabled()
    }

    private fun refreshDefaultBrowserSummary() {
        val pref = findPreference<Preference>(KEY_DEFAULT_BROWSER) ?: return
        pref.summary = if (isDefaultBrowserHeld()) {
            getString(R.string.pref_default_browser_already)
        } else {
            getString(R.string.pref_default_browser_summary)
        }
    }

    /** RoleManager 仅在 Android 10+ 存在；低版本一律视为未持有，避免类加载崩溃 */
    private fun isDefaultBrowserHeld(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return false
        val roleManager = requireContext().getSystemService(
            android.app.role.RoleManager::class.java
        ) ?: return false
        return runCatching {
            roleManager.isRoleHeld(android.app.role.RoleManager.ROLE_BROWSER)
        }.getOrDefault(false)
    }

    /** 打开「搜索引擎」管理页（选择默认 / 添加自定义） */
    private fun bindEngineManager() {
        findPreference<Preference>(KEY_ENGINE_MANAGER)?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), EngineSettingsActivity::class.java))
            true
        }
    }

    /** 打开「关于棠雪」：隐私政策摘要 / 完整隐私政策 / 用户协议 */
    private fun bindAbout() {
        findPreference<Preference>(KEY_ABOUT)?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), AboutActivity::class.java))
            true
        }
    }

    /** 主题切换：setDefaultNightMode 本身就会触发 Activity 重建，无需再手动 recreate */
    private fun bindTheme() {
        findPreference<ListPreference>(PreferenceStore.KEY_THEME)?.setOnPreferenceChangeListener { _, newValue ->
            ThemeController.apply(Theme.fromKey(newValue as? String))
            true
        }
    }

    /**
     * 语言切换：AndroidX 的偏好变更监听器先于持久化执行（callChangeListener → setValue），
     * 因此必须用回调参数 newValue 直接应用，不能重读存储值（否则读到旧语言，要点两次）。
     * AppCompatDelegate.setApplicationLocales 会自动重建 Activity 刷新资源。
     */
    private fun bindLanguage() {
        findPreference<ListPreference>(PreferenceStore.KEY_APP_LOCALE)?.setOnPreferenceChangeListener { _, newValue ->
            io.github.tan_sno.tangsnow.data.LocaleManager.applyTag(requireContext(), newValue as? String)
            true
        }
    }

    /**
     * 设为默认浏览器：优先 RoleManager 官方弹窗（Android 10+）；
     * 国产 ROM 若未提供角色申请，则回退打开系统「默认应用」设置页，保证总有出路。
     */
    private fun bindDefaultBrowser() {
        findPreference<Preference>(KEY_DEFAULT_BROWSER)?.setOnPreferenceClickListener { pref ->
            val ctx = requireContext()

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                try {
                    val roleManager = ctx.getSystemService(
                        android.app.role.RoleManager::class.java
                    )
                    if (roleManager != null) {
                        if (roleManager.isRoleHeld(android.app.role.RoleManager.ROLE_BROWSER)) {
                            pref.summary = getString(R.string.pref_default_browser_already)
                            Toast.makeText(ctx, R.string.pref_default_browser_already, Toast.LENGTH_SHORT).show()
                            return@setOnPreferenceClickListener true
                        }
                        if (roleManager.isRoleAvailable(android.app.role.RoleManager.ROLE_BROWSER)) {
                            val intent = roleManager.createRequestRoleIntent(
                                android.app.role.RoleManager.ROLE_BROWSER
                            )
                            requestDefaultBrowserLauncher.launch(intent)
                            return@setOnPreferenceClickListener true
                        }
                    }
                } catch (_: Exception) {
                    // 某些 ROM 的角色申请受限，落入默认应用设置页
                }
            }
            // Android 9- 或角色申请不可用 → 打开系统「默认应用」设置页
            openDefaultAppsSettings(ctx)
            true
        }
    }

    /** 打开系统「默认应用」设置页；打不开时给出提示 */
    private fun openDefaultAppsSettings(ctx: android.content.Context) {
        val ok = try {
            ctx.startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            )
            true
        } catch (_: Exception) {
            false
        }
        if (!ok) {
            Toast.makeText(ctx, R.string.pref_default_browser_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private val requestDefaultBrowserLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // 即便用户拒绝也只刷新一下当前项摘要（效果由系统托盘提示）；
        // 该回调只会在 Android 10+ 的角色申请页返回，此处仍做防御性守卫
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            return@registerForActivityResult
        }
        val ctx = requireContext()
        val roleManager = ctx.getSystemService(android.app.role.RoleManager::class.java) ?: return@registerForActivityResult
        val pref = findPreference<Preference>(KEY_DEFAULT_BROWSER) ?: return@registerForActivityResult
        pref.summary = if (roleManager.isRoleHeld(android.app.role.RoleManager.ROLE_BROWSER))
            getString(R.string.pref_default_browser_already)
        else
            getString(R.string.pref_default_browser_summary)
    }

    private fun bindPrivateMode() {
        findPreference<SwitchPreferenceCompat>(PreferenceStore.KEY_PRIVATE_MODE)?.setOnPreferenceChangeListener { _, _ ->
            Toast.makeText(requireContext(), R.string.toast_private_mode_restart, Toast.LENGTH_SHORT).show()
            true
        }
    }

    /**
     * 桌面版网站开关：切换会**重建全部标签并重载**（UA/视口是会话级配置，无法热改），
     * 页面中未保存的内容会丢失，属有损操作，故先确认；取消则不动偏好。
     * 生效由 MainActivity 返回时统一重建（applyDesktopModeIfChanged）。
     */
    private fun bindDesktopMode() {
        findPreference<SwitchPreferenceCompat>(PreferenceStore.KEY_DESKTOP_MODE)?.setOnPreferenceChangeListener { pref, newValue ->
            val enable = newValue as? Boolean ?: return@setOnPreferenceChangeListener false
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.pref_desktop_mode_title)
                .setMessage(R.string.dlg_desktop_confirm)
                .setNegativeButton(R.string.dlg_cancel, null)
                .setPositiveButton(R.string.dlg_ok) { _, _ ->
                    // 直接写偏好 + 回显开关态；真正重建由主界面下次 onResume 完成
                    PreferenceStore(requireContext()).desktopMode = enable
                    (pref as? SwitchPreferenceCompat)?.isChecked = enable
                }
                .show()
            false
        }
    }

    /** 会话恢复开关：关闭时立即清掉本机已存的标签页快照，做到「关即不留痕」 */
    private fun bindSessionRestore() {
        findPreference<SwitchPreferenceCompat>(PreferenceStore.KEY_SESSION_RESTORE)?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as? Boolean ?: true
            if (!enabled) {
                // 立即清除已保存的快照，无需等下次启动
                io.github.tan_sno.tangsnow.data.SessionStore.clear(requireContext())
            }
            true
        }
    }

    /**
     * 跟踪保护分档：档位切换持久化并刷新自定义开关可点态；
     * 档位/自定义项属内核运行时级配置，改动标注「下次启动生效」，绝不虚报即时生效。
     */
    private fun bindTracking() {
        val store = PreferenceStore(requireContext())
        findPreference<ListPreference>(PreferenceStore.KEY_TRACKING_MODE)?.setOnPreferenceChangeListener { _, newValue ->
            store.trackingMode = newValue as? String ?: PreferenceStore.TRACKING_STANDARD
            refreshTrackingCustomEnabled()
            toastTrackingRestart()
            true
        }
        bindTrackingCustomSwitch(PreferenceStore.KEY_TRACKING_CUSTOM_CONTENT) { store.trackingCustomContent = it }
        bindTrackingCustomSwitch(PreferenceStore.KEY_TRACKING_CUSTOM_FINGERPRINT) { store.trackingCustomFingerprint = it }
        bindTrackingCustomSwitch(PreferenceStore.KEY_TRACKING_CUSTOM_CRYPTO) { store.trackingCustomCryptominer = it }
        bindTrackingCustomSwitch(PreferenceStore.KEY_TRACKING_CUSTOM_COOKIE) { store.trackingCustomCookieIsolate = it }
        // 内核自带、独立于档位的隐私能力开关（运行时级，同样下次启动生效）
        bindPrivacySwitch(PreferenceStore.KEY_GPC_ENABLED) { store.gpcEnabled = it }
        bindPrivacySwitch(PreferenceStore.KEY_PARAM_STRIPPING) { store.paramStrippingEnabled = it }
        refreshTrackingCustomEnabled()
    }

    private fun bindPrivacySwitch(key: String, apply: (Boolean) -> Unit) {
        findPreference<SwitchPreferenceCompat>(key)?.setOnPreferenceChangeListener { _, newValue ->
            apply(newValue as? Boolean ?: true)
            Toast.makeText(requireContext(), R.string.toast_privacy_restart, Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun bindTrackingCustomSwitch(key: String, apply: (Boolean) -> Unit) {
        findPreference<SwitchPreferenceCompat>(key)?.setOnPreferenceChangeListener { _, newValue ->
            apply(newValue as? Boolean ?: false)
            toastTrackingRestart()
            true
        }
    }

    /** 自定义档以外的模式里，自定义分项开关置灰（不产生“改了却没效果”的错觉） */
    private fun refreshTrackingCustomEnabled() {
        val store = PreferenceStore(requireContext())
        val custom = store.trackingMode == PreferenceStore.TRACKING_CUSTOM
        listOf(
            PreferenceStore.KEY_TRACKING_CUSTOM_CONTENT,
            PreferenceStore.KEY_TRACKING_CUSTOM_FINGERPRINT,
            PreferenceStore.KEY_TRACKING_CUSTOM_CRYPTO,
            PreferenceStore.KEY_TRACKING_CUSTOM_COOKIE,
        ).forEach { key ->
            findPreference<SwitchPreferenceCompat>(key)?.isEnabled = custom
        }
    }

    private fun toastTrackingRestart() {
        Toast.makeText(requireContext(), R.string.toast_tracking_restart, Toast.LENGTH_SHORT).show()
    }

    private fun bindClearData() {
        findPreference<Preference>(KEY_CLEAR_DATA)?.setOnPreferenceClickListener {
            showClearDataDialog()
            true
        }
    }

    /**
     * 防截屏开关（默认关闭）。
     *
     * 只写入偏好、不在这里改本窗口的 FLAG_SECURE：该设置按语义只作用于**浏览界面**
     * （见文案与 SecureScreen 的说明），回到浏览器时由 MainActivity.onResume 应用，
     * 避免"在设置页开了以后连设置页也截不了图"这种超出预期的行为。
     */
    private fun bindSecureScreen() {
        findPreference<SwitchPreferenceCompat>(PreferenceStore.KEY_SECURE_SCREEN)
            ?.setOnPreferenceChangeListener { _, newValue ->
                PreferenceStore(requireContext()).secureScreen = newValue as? Boolean ?: false
                Toast.makeText(requireContext(), R.string.toast_secure_screen_applied, Toast.LENGTH_SHORT).show()
                true
            }
    }

    /**
     * 清除浏览数据：带勾选项的多选对话框。
     * 书签**永不**纳入（与主流浏览器一致）；内核动作等待真正完成后再反馈，不再"秒报成功"。
     */
    private fun showClearDataDialog() {
        val items = arrayOf(
            getString(R.string.clear_option_cookies),
            getString(R.string.clear_option_cache),
            getString(R.string.clear_option_history),
            getString(R.string.clear_option_session),
            getString(R.string.clear_option_downloads),
        )
        val checked = booleanArrayOf(true, true, false, false, false)
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.dlg_clear_data_title)
            .setMultiChoiceItems(items, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setNegativeButton(R.string.dlg_cancel, null)
            .setPositiveButton(R.string.dlg_ok, null)
            .create()
        dialog.show()
        // 拦截确定按钮：先校验"至少勾一项"，通过再真正关闭并执行
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val options = ClearDataUseCase.Options(
                cookiesAndSiteData = checked[0],
                cache = checked[1],
                history = checked[2],
                sessionSnapshot = checked[3],
                downloadRecords = checked[4],
            )
            val nothing = !options.cookiesAndSiteData && !options.cache &&
                !options.history && !options.sessionSnapshot && !options.downloadRecords
            if (nothing) {
                Toast.makeText(requireContext(), R.string.toast_data_nothing_selected, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            clearBrowsingData(options)
        }
    }

    private fun clearBrowsingData(options: ClearDataUseCase.Options) {
        val ctx = requireContext()
        lifecycleScope.launch {
            val result = ClearDataUseCase.clear(ctx, options)
            if (!isAdded) return@launch
            // 三种结果分开提示：本地有一项没清掉时绝不能报「已清除」
            val msgRes = when {
                result.allOk -> R.string.toast_data_cleared
                result.kernelOk -> R.string.toast_data_partially_cleared
                else -> R.string.toast_data_clear_failed
            }
            Toast.makeText(ctx, msgRes, Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------- 检查更新

    private fun showBuildVersion() {
        val current = UpdateChecker.current(requireContext())
        findPreference<Preference>(KEY_VERSION)?.summary = current.versionName.ifBlank {
            getString(R.string.pref_version_unknown)
        }
    }

    private fun bindUpdate() {
        val pref = findPreference<Preference>(KEY_CHECK_UPDATE) ?: return
        // 未接入更新源时置灰并说明原因：绝不假装“已是最新”（假反馈）
        if (!UpdateChecker.isConfigured()) {
            pref.isEnabled = false
            pref.summary = getString(R.string.pref_check_update_unavailable)
        }
        pref.setOnPreferenceClickListener {
            checkUpdate()
            true
        }
    }

    private fun checkUpdate() {
        val context = requireContext()
        val current = UpdateChecker.current(context)
        // 积极按钮在 Builder 阶段就声明（下载新版本），结果返回后只做显示/隐藏，
        // 避免 show() 之后再 setButton 可能加不上按钮的问题。
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.update_dialog_title)
            .setMessage(getString(R.string.update_checking))
            .setNegativeButton(R.string.dlg_cancel, null)
            .setPositiveButton(R.string.update_download, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isVisible = false

        lifecycleScope.launch {
            val info = UpdateChecker.fetchManifest()
            // 弹窗可能已被用户取消 / Fragment 已销毁 / Activity 正在销毁（旋转、返回）
            val activity = activity
            if (!isAdded || activity == null || activity.isFinishing || activity.isDestroyed) {
                return@launch
            }
            if (!dialog.isShowing) return@launch

            when {
                info == null -> {
                    // 已配置更新源但网络失败/清单异常：如实提示检查失败
                    dialog.setMessage(getString(R.string.update_check_failed))
                }
                info.versionCode <= current.versionCode -> {
                    dialog.setMessage(
                        getString(R.string.update_latest, current.versionName, current.versionCode)
                    )
                }
                else -> {
                    val message = buildString {
                        append(getString(R.string.update_new_found, info.versionName))
                        info.notes?.let { append("\n\n").append(it) }
                    }
                    dialog.setMessage(message)
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.let { btn ->
                        btn.isVisible = true
                        btn.setOnClickListener {
                            openApk(info.apkUrl)
                            dialog.dismiss()
                        }
                    }
                }
            }
        }
    }

    private fun openApk(url: String) {
        if (url.isBlank()) return
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(requireContext(), R.string.update_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------- 关于文本

    /**
     * 「开源协议」入口（设置 → 关于棠雪，位于最下方）。
     *
     * 与隐私政策/用户协议走**同一个阅读页**（[LegalActivity]），而不是旧版的一次性弹窗：
     * 开源声明含各组件清单、源码获取途径（MPL 2.0 §3.2）与商标说明，长度已不适合弹窗；
     * 统一入口也避免出现「两处各存一份、内容可能不一致」。
     * 启动失败时回退到原地弹窗，与其它文档入口同一套兜底。
     */
    private fun bindInfoDialogs() {
        findPreference<Preference>(KEY_OPEN_SOURCE)?.setOnPreferenceClickListener { pref ->
            if (!LegalActivity.start(requireContext(), LegalActivity.DOC_LICENSES)) {
                showTextDialog(pref.title?.toString().orEmpty(), getString(R.string.open_source_text))
            }
            true
        }
    }

    private fun showTextDialog(title: CharSequence, text: CharSequence) {
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(text)
            .setPositiveButton(R.string.dlg_ok, null)
            .show()
    }

    private companion object {
        const val KEY_ENGINE_MANAGER = "engine_manager"
        const val KEY_ABOUT = "about_tangsnow"
        const val KEY_CLEAR_DATA = "clear_data"
        const val KEY_DEFAULT_BROWSER = "set_default_browser"
        const val KEY_CHECK_UPDATE = "check_update"
        const val KEY_VERSION = "version"
        const val KEY_OPEN_SOURCE = "open_source"
    }
}
