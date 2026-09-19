package io.github.tan_sno.tangsnow.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import io.github.tan_sno.tangsnow.R
import io.github.tan_sno.tangsnow.browser.PermissionHandler
import io.github.tan_sno.tangsnow.browser.PromptHandler
import io.github.tan_sno.tangsnow.util.dp
import org.mozilla.geckoview.GeckoSession
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.WeekFields
import java.util.Locale

/** <input type=color> 预设色板（语义色，选中后回交 #RRGGBB 字符串） */
private val COLOR_PALETTE = arrayOf(
    "#000000", "#FFFFFF", "#E91E63", "#9C27B0",
    "#3F51B5", "#2196F3", "#009688", "#4CAF50",
    "#FFEB3B", "#FF9800", "#795548", "#9E9E9E",
)

/** 颜色自定义输入的合法性（仅接受 #RRGGBB 六位十六进制） */
private val COLOR_RE = Regex("^#[0-9A-Fa-f]{6}$")

/** <input type=datetime> 各子类型的目标字符串格式（与内核约定一致） */
private val DT_DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val DT_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")
private val DT_DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
private val DT_MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM")

/**
 * <input type=week>：HTML 规范要求 `yyyy-Www`（ISO 周：周一为一周之首、第 1 周含 1 月 4 日）。
 *
 * 这里用 [WeekFields.ISO] 的字段**显式**构造，而不用模式字母 `w`/`Y`：
 * 模式字母取的是 `WeekFields.of(locale)`，而默认区域（如 en_US）以周日为一周之首、
 * 第 1 周的判定也不一致 —— 同一日期会算出与 ISO 不同的周号，回交内核后
 * 用户看到的周会偏移一周。ISO_WEEK_DATE 用于反向解析（见 onDateTimePrompt）。
 */
private val DT_WEEK_FMT: DateTimeFormatter = DateTimeFormatterBuilder()
    .appendValue(WeekFields.ISO.weekBasedYear(), 4)
    .appendLiteral("-W")
    .appendValue(WeekFields.ISO.weekOfWeekBasedYear(), 2)
    .toFormatter(Locale.ROOT)

/** <input type=datetime> 子类型常量（GeckoView DateTimePrompt.Type 是嵌套类，内部为静态 int） */
private val DT_TYPE_DATE = GeckoSession.PromptDelegate.DateTimePrompt.Type.DATE
private val DT_TYPE_TIME = GeckoSession.PromptDelegate.DateTimePrompt.Type.TIME
private val DT_TYPE_DATETIME_LOCAL = GeckoSession.PromptDelegate.DateTimePrompt.Type.DATETIME_LOCAL
private val DT_TYPE_MONTH = GeckoSession.PromptDelegate.DateTimePrompt.Type.MONTH
private val DT_TYPE_WEEK = GeckoSession.PromptDelegate.DateTimePrompt.Type.WEEK

/**
 * 解析 `<input type=datetime-local>` 的默认值（内核给的是 `yyyy-MM-ddTHH:mm`）。
 *
 * ⚠️ 必须用 [DT_DATETIME_FMT] 解析。此前 `DATETIME_LOCAL` 与 `DATE` 共用同一个 `else`
 * 分支，用 `yyyy-MM-dd` 去解析带 `T` 的整串**必然**抛 `DateTimeParseException`，
 * 被 `runCatching` 吞掉后回落到「今天」；紧接着时间选择器又拿 `HH:mm` 去解析同一整串，
 * 同样失败回落到「此刻」。结果是：**页面用 `defaultValue` 指定的默认日期与时间被静默丢弃**，
 * 用户每次打开都停在今天/现在。
 *
 * 这与 `WEEK` 分支踩过的是同一个坑（见 `onDateTimePrompt` 里那处注释）：
 * 新增子类型时忘了给它配对应的解析格式，就会静默退化。故这里抽成一处解析，两处共用。
 */
private fun parseDateTimeLocal(raw: String): LocalDateTime? =
    runCatching { LocalDateTime.parse(raw.trim(), DT_DATETIME_FMT) }.getOrNull()

/**
 * 网页弹窗与站点权限的界面层实现（[PromptHandler] + [PermissionHandler]）：
 *  - alert/confirm/prompt/select/文件选择/HTTP 认证/离开确认 → AlertDialog 或系统文件选择器；
 *  - 定位/通知/摄像头/麦克风 → 明确允许或拒绝，绝不静默挂起；
 *  - 所有 done 回调保证恰好调用一次（按钮与取消监听共用 once 闸）。
 * 文件选择与 Android 权限申请经由 MainActivity 注册的 ActivityResultLauncher 回转。
 *
 * ## 生命周期兜底
 * 每个网页弹窗的应答都会喂给一个 GeckoResult。若 Activity 在弹窗展示期间被销毁
 * （切主题、切语言、进程回收等），而弹窗既不关闭也不应答，会造成两个后果：
 *  1. **页面永久挂起**——JS 侧永远等不到 prompt 返回；
 *  2. **窗口泄漏**——对话框仍挂在已销毁的 Activity 上（WindowLeaked）。
 *
 * 因此所有弹窗都经 [tracked] 登记，[cancelPending] 在销毁前对其调用 `Dialog.cancel()`：
 * `cancel()` 会触发各自已挂好的 `OnCancelListener`，而那里正是 `once(取消值)`，
 * 于是应答恰好完成一次、窗口同时被移除——一处修复同时解决两个问题。
 * （注意不能用 `dismiss()`：它不触发 OnCancelListener，应答仍会悬空。）
 */
class WebPrompts(
    private val activity: AppCompatActivity,
    private val pickSingleFile: ActivityResultLauncher<String>,
    private val pickMultiFile: ActivityResultLauncher<Array<String>>,
    private val requestAndroidPermissions: ActivityResultLauncher<Array<String>>,
) : PromptHandler, PermissionHandler {

    /** 进行中的文件选择回调（同时只允许一个；新的请求会取消旧的） */
    private var pendingFileDone: ((List<Uri>?) -> Unit)? = null

    /** 进行中的 Android 权限申请回调 */
    private var pendingPermDone: ((Boolean) -> Unit)? = null

    /** 进行中的文件夹上传回调（true=已选目录 / null=取消） */
    private var pendingFolderDone: ((Boolean?) -> Unit)? = null

    /**
     * 上传整个文件夹：复用 [ActivityResultContracts.OpenDocumentTree] 让用户选目录。
     * 在 WebPrompts 构造期（MainActivity.onCreate 内）注册，满足 registerForActivityResult
     * 须在 Activity 进入 STARTED 前调用的约束；回调只关心「是否选到了目录」，目录本身由
     * 内核 FolderUploadPrompt.confirm(AllowOrDeny) 决定——本版本无 confirm(Uri) 重载。
     */
    private val pickFolder: ActivityResultLauncher<Uri?> =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            pendingFolderDone?.invoke(uri != null)
            pendingFolderDone = null
        }

    /** 当前展示中的网页弹窗，供 Activity 销毁时统一取消 */
    private val openDialogs = mutableListOf<android.app.Dialog>()

    /**
     * 展示弹窗并登记；`OnDismissListener` 负责在关闭后自动摘除。
     * 摘除与 [cancelPending] 的遍历都以副本进行，避免边遍历边修改。
     * 形参放宽到 [android.app.Dialog]：日期/时间选择器（[DatePickerDialog]/[TimePickerDialog]）
     * 同为 Dialog 子类，需一并纳入销毁兜底，否则 Activity 销毁时窗口泄漏、页面挂起。
     */
    private fun tracked(dialog: android.app.Dialog): android.app.Dialog {
        openDialogs += dialog
        dialog.setOnDismissListener { openDialogs.remove(dialog) }
        dialog.show()
        return dialog
    }

    /** 由 MainActivity 在文件选择结果回调里调用（null = 用户取消） */
    fun onPickedFiles(uris: List<Uri>?) {
        pendingFileDone?.invoke(uris)
        pendingFileDone = null
    }

    /** 由 MainActivity 在权限申请结果回调里调用 */
    fun onAndroidPermissionsResult(allGranted: Boolean) {
        pendingPermDone?.invoke(allGranted)
        pendingPermDone = null
    }

    /**
     * Activity 销毁前兜底：
     *  - 未完成的文件选择 / 权限申请按取消处理，避免 JS 侧永久挂起；
     *  - 仍在展示的网页弹窗逐个 `cancel()`，触发其取消监听完成一次应答并释放窗口。
     */
    fun cancelPending() {
        pendingFileDone?.invoke(null)
        pendingFileDone = null
        pendingPermDone?.invoke(false)
        pendingPermDone = null
        pendingFolderDone?.invoke(null)
        pendingFolderDone = null
        openDialogs.toList().forEach { runCatching { it.cancel() } }
        openDialogs.clear()
    }

    // ------------------------------------------------------------- PromptHandler

    override fun onAlert(title: String?, message: String?, done: () -> Unit) {
        var called = false
        fun once() {
            if (!called) { called = true; done() }
        }
        tracked(
            AlertDialog.Builder(activity)
                .setTitle(title.orEmpty().ifBlank { activity.getString(R.string.app_name) })
                .setMessage(message.orEmpty())
                .setPositiveButton(R.string.dlg_ok) { _, _ -> once() }
                .setOnCancelListener { once() }
                .create()
        )
    }

    override fun onConfirm(title: String?, message: String?, done: (Boolean) -> Unit) {
        var called = false
        fun once(v: Boolean) {
            if (!called) { called = true; done(v) }
        }
        tracked(
            AlertDialog.Builder(activity)
                .setTitle(title.orEmpty().ifBlank { activity.getString(R.string.app_name) })
                .setMessage(message.orEmpty())
                .setPositiveButton(R.string.dlg_ok) { _, _ -> once(true) }
                .setNegativeButton(R.string.dlg_cancel) { _, _ -> once(false) }
                .setOnCancelListener { once(false) }
                .create()
        )
    }

    override fun onTextPrompt(
        title: String?,
        message: String?,
        defaultValue: String,
        done: (String?) -> Unit,
    ) {
        var called = false
        fun once(v: String?) {
            if (!called) { called = true; done(v) }
        }
        val input = EditText(activity).apply {
            setText(defaultValue)
            setSelection(defaultValue.length)
            inputType = InputType.TYPE_CLASS_TEXT
            val pad = activity.dp(20)
            setPadding(pad, pad / 2, pad, 0)
        }
        tracked(
            AlertDialog.Builder(activity)
                .setTitle(title.orEmpty().ifBlank { activity.getString(R.string.app_name) })
                .setMessage(message.orEmpty())
                .setView(input)
                .setPositiveButton(R.string.dlg_ok) { _, _ -> once(input.text.toString()) }
                .setNegativeButton(R.string.dlg_cancel) { _, _ -> once(null) }
                .setOnCancelListener { once(null) }
                .create()
        )
    }

    override fun onChoice(
        title: String?,
        multiple: Boolean,
        items: List<Triple<String, String, Boolean>>,
        done: (List<String>?) -> Unit,
    ) {
        var called = false
        fun once(v: List<String>?) {
            if (!called) { called = true; done(v) }
        }
        // 空选项（异常页面）直接按取消处理，避免单选路径越界崩溃
        if (items.isEmpty()) {
            once(null)
            return
        }
        val labels = items.map { it.second }.toTypedArray()
        val builder = AlertDialog.Builder(activity)
            .setTitle(title.orEmpty().ifBlank { activity.getString(R.string.app_name) })
        if (!multiple) {
            var chosen = items.indexOfFirst { it.third }.coerceAtLeast(0)
            builder
                .setSingleChoiceItems(labels, chosen) { _, which -> chosen = which }
                .setPositiveButton(R.string.dlg_ok) { _, _ -> once(listOf(items[chosen].first)) }
        } else {
            val checked = BooleanArray(items.size) { items[it].third }
            builder
                .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setPositiveButton(R.string.dlg_ok) { _, _ ->
                    once(items.filterIndexed { i, _ -> checked[i] }.map { it.first })
                }
        }
        tracked(
            builder
                .setNegativeButton(R.string.dlg_cancel) { _, _ -> once(null) }
                .setOnCancelListener { once(null) }
                .create()
        )
    }

    override fun onFilePrompt(
        mimeTypes: Array<String>,
        multiple: Boolean,
        done: (List<Uri>?) -> Unit,
    ) {
        // 前一个未完成的选择按取消处理，避免回调悬空
        pendingFileDone?.invoke(null)
        pendingFileDone = done
        val launch = runCatching {
            if (multiple) {
                pickMultiFile.launch(
                    if (mimeTypes.isEmpty()) arrayOf("*/*") else mimeTypes
                )
            } else {
                pickSingleFile.launch(
                    mimeTypes.firstOrNull()?.takeIf { it.isNotBlank() } ?: "*/*"
                )
            }
        }
        launch.onFailure { onPickedFiles(null) }
    }

    override fun onAuthPrompt(
        title: String?,
        message: String?,
        done: (Pair<String, String>?) -> Unit,
    ) {
        var called = false
        fun once(v: Pair<String, String>?) {
            if (!called) { called = true; done(v) }
        }
        val pad = activity.dp(20)
        val user = EditText(activity).apply {
            hint = activity.getString(R.string.prompt_username)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val pass = EditText(activity).apply {
            hint = activity.getString(R.string.prompt_password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(user)
            addView(pass)
        }
        tracked(
            AlertDialog.Builder(activity)
                .setTitle(title.orEmpty().ifBlank { activity.getString(R.string.prompt_login_title) })
                .setMessage(message.orEmpty())
                .setView(box)
                .setPositiveButton(R.string.dlg_ok) { _, _ ->
                    once(user.text.toString() to pass.text.toString())
                }
                .setNegativeButton(R.string.dlg_cancel) { _, _ -> once(null) }
                .setOnCancelListener { once(null) }
                .create()
        )
    }

    override fun onBeforeUnload(title: String?, done: (Boolean) -> Unit) {
        var called = false
        fun once(v: Boolean) {
            if (!called) { called = true; done(v) }
        }
        tracked(
            AlertDialog.Builder(activity)
                .setTitle(R.string.prompt_leave_title)
                .setMessage(R.string.prompt_leave_message)
                .setPositiveButton(R.string.perm_allow) { _, _ -> once(true) }
                .setNegativeButton(R.string.dlg_cancel) { _, _ -> once(false) }
                .setOnCancelListener { once(false) }
                .create()
        )
    }

    override fun onColorPrompt(defaultValue: String, done: (String?) -> Unit) {
        var called = false
        fun once(v: String?) {
            if (!called) { called = true; done(v) }
        }
        val pad = activity.dp(16)
        // 色板：固定一组语义色，选中即回交 #RRGGBB（内核只收字符串，不需要额外类型参数）
        val grid = GridLayout(activity).apply {
            columnCount = 4
            setPadding(pad, pad / 2, pad, 0)
        }
        val size = activity.dp(52)
        COLOR_PALETTE.forEach { hex ->
            grid.addView(
                Button(activity).apply {
                    background = ColorDrawable(runCatching { Color.parseColor(hex) }.getOrDefault(Color.BLACK))
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = size
                        height = size
                        setMargins(pad / 3, pad / 3, pad / 3, pad / 3)
                    }
                }
            )
        }
        val custom = EditText(activity).apply {
            hint = activity.getString(R.string.prompt_color_custom)
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(pad, pad / 2, pad, 0)
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(grid)
            addView(custom)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.prompt_color_title)
            .setView(box)
            .setPositiveButton(R.string.dlg_ok) { _, _ ->
                val text = custom.text.toString().trim()
                if (COLOR_RE.matches(text)) {
                    once(text.uppercase())
                } else {
                    // 自定义输入不合法：提示并视作取消，避免把脏字符串回交内核
                    Toast.makeText(activity, R.string.prompt_color_invalid, Toast.LENGTH_SHORT).show()
                    once(null)
                }
            }
            .setNegativeButton(R.string.dlg_cancel) { _, _ -> once(null) }
            .setOnCancelListener { once(null) }
            .create()
        // 色块点击直接确认并关闭：dialog.dismiss() 触发 tracked 的 OnDismissListener 摘除，
        // 不会触发 OnCancelListener，once(hex) 已先完成，故安全
        for (i in 0 until grid.childCount) {
            (grid.getChildAt(i) as Button).setOnClickListener {
                once(COLOR_PALETTE[i])
                dialog.dismiss()
            }
        }
        tracked(dialog)
    }

    override fun onDateTimePrompt(type: Int, defaultValue: String, done: (String?) -> Unit) {
        var called = false
        fun once(v: String?) {
            if (!called) { called = true; done(v) }
        }
        val dateType = type == DT_TYPE_DATE || type == DT_TYPE_MONTH || type == DT_TYPE_WEEK || type == DT_TYPE_DATETIME_LOCAL
        val timeType = type == DT_TYPE_TIME || type == DT_TYPE_DATETIME_LOCAL

        if (!dateType) {
            // 仅时间：直接弹 TimePickerDialog
            val lt = runCatching { LocalTime.parse(defaultValue, DT_TIME_FMT) }.getOrDefault(LocalTime.now())
            val timeDialog = TimePickerDialog(activity, { _, hh, mm ->
                once(LocalTime.of(hh, mm).format(DT_TIME_FMT))
            }, lt.hour, lt.minute, true)
            timeDialog.setOnCancelListener { once(null) }
            tracked(timeDialog)
            return
        }

        // 日期类（DATE / MONTH / WEEK / DATETIME_LOCAL）：先弹 DatePickerDialog
        val ld = when (type) {
            DT_TYPE_MONTH -> runCatching { YearMonth.parse(defaultValue).atDay(1) }.getOrDefault(LocalDate.now())
            // WEEK 的默认值形如 2026-W37（ISO 周日期），用 ISO_WEEK_DATE 解析星期一并得到该周周一；
            // 早期用 DT_DATE_FMT 解析必然失败 → 选择器每次都停在今天，与页面给的默认值不符
            DT_TYPE_WEEK -> runCatching {
                LocalDate.parse(defaultValue.trim() + "-1", DateTimeFormatter.ISO_WEEK_DATE)
            }.getOrDefault(LocalDate.now())
            // DATETIME_LOCAL 的默认值是 yyyy-MM-ddTHH:mm，必须用对应格式解析，
            // 否则必然失败并回落到「今天」（见 parseDateTimeLocal 的说明）
            DT_TYPE_DATETIME_LOCAL -> parseDateTimeLocal(defaultValue)?.toLocalDate()
                ?: LocalDate.now()
            else -> runCatching { LocalDate.parse(defaultValue, DT_DATE_FMT) }.getOrDefault(LocalDate.now())
        }
        val dateListener = DatePickerDialog.OnDateSetListener { _, y, m, d ->
            val chosen = LocalDate.of(y, m + 1, d)
            when (type) {
                // MONTH / WEEK 只需年月 / 年周：丢弃具体日，按内核约定格式回交
                DT_TYPE_MONTH -> once(chosen.format(DT_MONTH_FMT))
                DT_TYPE_WEEK -> once(chosen.format(DT_WEEK_FMT))
                DT_TYPE_DATETIME_LOCAL -> {
                    // 日期选定后再弹时间选择器，二者拼成 yyyy-MM-dd'T'HH:mm。
                    // 时间默认值也要从 `yyyy-MM-ddTHH:mm` 整串里取 —— 直接拿 `HH:mm`
                    // 解析整串同样会失败并回落「此刻」（见 parseDateTimeLocal）
                    val lt = parseDateTimeLocal(defaultValue)?.toLocalTime() ?: LocalTime.now()
                    val timeDialog = TimePickerDialog(activity, { _, hh, mm ->
                        once(chosen.atTime(hh, mm).format(DT_DATETIME_FMT))
                    }, lt.hour, lt.minute, true)
                    timeDialog.setOnCancelListener { once(null) }
                    tracked(timeDialog)
                }
                else -> once(chosen.format(DT_DATE_FMT)) // DATE
            }
        }
        val dateDialog = DatePickerDialog(activity, dateListener, ld.year, ld.monthValue - 1, ld.dayOfMonth)
        dateDialog.setOnCancelListener { once(null) }
        tracked(dateDialog)
    }

    override fun onPopupPrompt(targetUri: String, done: (Boolean?) -> Unit) {
        var called = false
        fun once(v: Boolean?) {
            if (!called) { called = true; done(v) }
        }
        tracked(
            AlertDialog.Builder(activity)
                .setTitle(R.string.app_name)
                .setMessage(activity.getString(R.string.prompt_popup_message, targetUri))
                .setPositiveButton(R.string.perm_allow) { _, _ -> once(true) }
                .setNegativeButton(R.string.perm_deny) { _, _ -> once(false) }
                .setOnCancelListener { once(null) }
                .create()
        )
    }

    override fun onRepostConfirmPrompt(done: (Boolean) -> Unit) {
        var called = false
        fun once(v: Boolean) {
            if (!called) { called = true; done(v) }
        }
        tracked(
            AlertDialog.Builder(activity)
                .setTitle(R.string.prompt_repost_title)
                .setMessage(R.string.prompt_repost_message)
                .setPositiveButton(R.string.dlg_ok) { _, _ -> once(true) }
                .setNegativeButton(R.string.dlg_cancel) { _, _ -> once(false) }
                .setOnCancelListener { once(false) }
                .create()
        )
    }

    override fun onRedirectPrompt(targetUri: String, done: (Boolean) -> Unit) {
        var called = false
        fun once(v: Boolean) {
            if (!called) { called = true; done(v) }
        }
        tracked(
            AlertDialog.Builder(activity)
                .setTitle(R.string.app_name)
                .setMessage(activity.getString(R.string.prompt_redirect_message, targetUri))
                .setPositiveButton(R.string.dlg_ok) { _, _ -> once(true) }
                .setNegativeButton(R.string.dlg_cancel) { _, _ -> once(false) }
                .setOnCancelListener { once(false) }
                .create()
        )
    }

    override fun onSharePrompt(text: String, uri: String, done: () -> Unit) {
        var called = false
        fun once() {
            if (!called) { called = true; done() }
        }
        // 系统分享：SharePrompt 无 title 字段，用 uri 作主题，为空回退应用名
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, uri.ifBlank { activity.getString(R.string.app_name) })
        }
        val chooser = Intent.createChooser(intent, activity.getString(R.string.prompt_share_chooser))
        // 唤起系统选择器即视为已处理：该 API 无「用户拒绝」分支，成败由系统负责，
        // 无论是否真正分享都必须完成 once()，否则页面会永久挂起。
        runCatching { activity.startActivity(chooser) }.onFailure {
            // 没有任何可分享的应用：同样确认完成，避免 JS 侧挂起
        }
        once()
    }

    override fun onFolderUploadPrompt(done: (Boolean?) -> Unit) {
        // 前一个未完成的选择按取消处理，避免回调悬空
        pendingFolderDone?.invoke(null)
        pendingFolderDone = done
        runCatching { pickFolder.launch(null) }
            .onFailure { pendingFolderDone = null; done(null) }
    }

    // ------------------------------------------------------------- PermissionHandler

    override fun onContentPermission(uri: String, permission: Int, done: (Boolean) -> Unit) {
        val host = Uri.parse(uri).host?.takeIf { it.isNotBlank() } ?: uri
        val messageRes = when (permission) {
            GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION ->
                R.string.perm_location_message
            GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION ->
                R.string.perm_notification_message
            GeckoSession.PermissionDelegate.PERMISSION_LOCAL_DEVICE_ACCESS ->
                R.string.perm_local_device_message
            else -> R.string.perm_local_network_message
        }
        var called = false
        fun once(v: Boolean) {
            if (!called) { called = true; done(v) }
        }
        tracked(
            AlertDialog.Builder(activity)
                .setTitle(host)
                .setMessage(activity.getString(messageRes, host))
                .setPositiveButton(R.string.perm_allow) { _, _ -> once(true) }
                .setNegativeButton(R.string.perm_deny) { _, _ -> once(false) }
                .setOnCancelListener { once(false) }
                .create()
        )
    }

    override fun onMediaPermission(
        host: String,
        needsVideo: Boolean,
        needsAudio: Boolean,
        done: (Boolean) -> Unit,
    ) {
        val whatRes = when {
            needsVideo && needsAudio -> R.string.perm_media_camera_mic
            needsVideo -> R.string.perm_media_camera
            else -> R.string.perm_media_mic
        }
        var called = false
        fun once(v: Boolean) {
            if (!called) { called = true; done(v) }
        }
        tracked(
            AlertDialog.Builder(activity)
                .setTitle(host)
                .setMessage(activity.getString(R.string.perm_media_message, host, activity.getString(whatRes)))
                .setPositiveButton(R.string.perm_allow) { _, _ -> once(true) }
                .setNegativeButton(R.string.perm_deny) { _, _ -> once(false) }
                .setOnCancelListener { once(false) }
                .create()
        )
    }

    override fun onAndroidPermissions(permissions: Array<String>, done: (Boolean) -> Unit) {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            done(true)
            return
        }
        pendingPermDone?.invoke(false)
        pendingPermDone = done
        runCatching { requestAndroidPermissions.launch(missing.toTypedArray()) }
            .onFailure { onAndroidPermissionsResult(false) }
    }
}
