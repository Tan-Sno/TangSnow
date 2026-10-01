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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.github.tan_sno.tangsnow.browser.PopupAnswer
import androidx.core.content.ContextCompat
import io.github.tan_sno.tangsnow.GeckoHolder
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
 * **带秒**的变体：页面把 `step` 设成 1（或任意非 60 的整数）时，`<input type=time>` /
 * `datetime-local` 的 value 形如 `10:30:00` / `2026-09-30T10:30:00`。
 *
 * 为什么要单独列：拿 `HH:mm` 去解析带秒的整串**必然**抛 `DateTimeParseException`，被
 * `runCatching` 吞掉后回落到「此刻」⇒ 页面用 `defaultValue` 指定的默认值被**静默丢弃**。
 * 这与 `datetime-local`、更早的 `week` 是**同一个坑的第三个形态**（见 [parseTimeLoose]）。
 */
private val DT_TIME_SEC_FMT = DateTimeFormatter.ofPattern("HH:mm:ss")
private val DT_DATETIME_SEC_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

/** 时间默认值的解析结果：值 + 原始串是否带秒（回交时保持同一形态，否则页面会当成非法值丢掉） */
private data class TimeDefault(val time: LocalTime, val withSeconds: Boolean)

/**
 * 宽松解析时间默认值：`HH:mm` 与 `HH:mm:ss` **都收**。
 * - 无秒 ⇒ 回交 `HH:mm`（与旧行为一致）；
 * - 有秒 ⇒ 回交 `HH:mm:00`（选择器只到分钟，秒位取 0）—— 关键是**格式必须与页面一致**。
 */
private fun parseTimeLoose(raw: String): TimeDefault? {
    val t = raw.trim()
    runCatching { LocalTime.parse(t, DT_TIME_SEC_FMT) }.getOrNull()
        ?.let { return TimeDefault(it, withSeconds = true) }
    runCatching { LocalTime.parse(t, DT_TIME_FMT) }.getOrNull()
        ?.let { return TimeDefault(it, withSeconds = false) }
    return null
}

/** 把用户选定的时:分按 [d] 的形态格式化回交 */
private fun formatChosenTime(d: TimeDefault, hh: Int, mm: Int): String =
    if (d.withSeconds) LocalTime.of(hh, mm).format(DT_TIME_SEC_FMT)
    else LocalTime.of(hh, mm).format(DT_TIME_FMT)

/** `datetime-local` 默认值的解析结果：值 + 是否带秒 */
private data class DateTimeLocalDefault(val value: LocalDateTime, val withSeconds: Boolean)

/**
 * 解析 `<input type=datetime-local>` 的默认值，`yyyy-MM-ddTHH:mm` 与**带秒**的
 * `yyyy-MM-ddTHH:mm:ss` 都收。
 *
 * ⚠️ 必须用对应格式解析。此前 `DATETIME_LOCAL` 与 `DATE` 共用同一个 `else` 分支，
 * 用 `yyyy-MM-dd` 去解析带 `T` 的整串**必然**抛 `DateTimeParseException`，被 `runCatching`
 * 吞掉后回落到「今天」；紧接着时间选择器又拿 `HH:mm` 去解析同一整串，同样失败回落到「此刻」。
 * 结果是：**页面用 `defaultValue` 指定的默认日期与时间被静默丢弃**，用户每次打开都停在今天/现在。
 * 这与 `WEEK` 分支踩过的是同一个坑（见 `onDateTimePrompt` 里那处注释），故解析统一收在这里。
 */
private fun parseDateTimeLocal(raw: String): DateTimeLocalDefault? {
    val t = raw.trim()
    runCatching { LocalDateTime.parse(t, DT_DATETIME_SEC_FMT) }.getOrNull()
        ?.let { return DateTimeLocalDefault(it, withSeconds = true) }
    runCatching { LocalDateTime.parse(t, DT_DATETIME_FMT) }.getOrNull()
        ?.let { return DateTimeLocalDefault(it, withSeconds = false) }
    return null
}

/** 把选定的日期 + 时:分按 [d] 的形态格式化回交 */
private fun formatChosenDateTimeLocal(
    d: DateTimeLocalDefault,
    chosen: LocalDate,
    hh: Int,
    mm: Int,
): String {
    val dt = chosen.atTime(hh, mm)
    return if (d.withSeconds) dt.format(DT_DATETIME_SEC_FMT) else dt.format(DT_DATETIME_FMT)
}

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
    /**
     * 单选文件：契约必须收 **MIME 数组** —— 页面可以把 `accept` 写成多个 MIME（如图片 + PDF），
     * 只收单个 MIME 字符串的旧契约会让用户根本选不到后面的类型。
     *
     * ⚠️ 注释里**不要**写 MIME 通配符的字面量（形如 图片类型斜杠星号）：Kotlin 的块注释是**可嵌套**的，
     * 那个斜杠星号会开启一段嵌套注释，把后续代码整段吞掉（实测：报 "Unclosed comment"）。
     */
    private val pickSingleFile: ActivityResultLauncher<Array<String>>,
    private val pickMultiFile: ActivityResultLauncher<Array<String>>,
    private val requestAndroidPermissions: ActivityResultLauncher<Array<String>>,
) : PromptHandler, PermissionHandler {

    // ⚠️ 这三个挂起回调**存在进程级 companion 里，不是实例字段**（2026-10-01 外部审查 M1）：
    // 配置变更（切主题 / 切语言）会重建 Activity、连带重建 WebPrompts，而系统对话框（文件选择、
    // 权限申请）的结果随后派发给**新实例** —— 回调若随实例消失，用户点了"允许 / 已选文件"就无人应答，
    // 页面收到的是"拒绝"且零提示。放进程级后新实例的入口能直接取到旧实例登记的回调并完成它
    //（这些回调只做 GeckoResult 结算、不碰 Activity，故跨实例调用安全）。
    // 真销毁才由 MainActivity 调 cancelPending() 收口（配置变更时不收）。
    private var pendingFileDone: ((List<Uri>?) -> Unit)?
        get() = Pending.file
        set(value) { Pending.file = value }

    /** 进行中的 Android 权限申请回调 */
    private var pendingPermDone: ((Boolean) -> Unit)?
        get() = Pending.perm
        set(value) { Pending.perm = value }

    /** 进行中的文件夹上传回调（true=已选目录 / null=取消） */
    private var pendingFolderDone: ((Boolean?) -> Unit)?
        get() = Pending.folder
        set(value) { Pending.folder = value }

    private companion object {
        /** 跨实例存活的挂起回调（理由见上）。@Volatile：读写可能来自不同线程的回调入口。 */
        object Pending {
            @Volatile var file: ((List<Uri>?) -> Unit)? = null
            @Volatile var perm: ((Boolean) -> Unit)? = null
            @Volatile var folder: ((Boolean?) -> Unit)? = null
        }
    }

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
        // 页面**没给**预选项时（`items` 里没有任何 `third == true`）取 -1，表示"无选中"。
        // ⚠️ 不能再用 `coerceAtLeast(0)` 退回第一项：那样点「确定」会提交一个用户从未点过的选项。
        var chosen = items.indexOfFirst { it.third }
        if (!multiple) {
            builder
                .setSingleChoiceItems(labels, chosen) { _, which -> chosen = which }
                .setPositiveButton(R.string.dlg_ok) { _, _ ->
                    if (chosen >= 0) once(listOf(items[chosen].first))
                }
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
        val dialog = builder
            .setNegativeButton(R.string.dlg_cancel) { _, _ -> once(null) }
            .setOnCancelListener { once(null) }
            .create()
        if (!multiple) {
            // 未预选时把「确定」置灰，直到用户真的选了一项。
            // ⚠️ 必须挂在**创建出来的 dialog** 上：`AlertDialog.Builder` 没有 setOnShowListener。
            dialog.setOnShowListener {
                dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                    .isEnabled = chosen >= 0
            }
        }
        tracked(dialog)
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
                // 单选也要把**整个** `accept` 数组交给选择器（注释里不写 MIME 通配符字面量，
                // 见构造参数处的说明）：只取第一个 MIME 会让「图片 + PDF」这类页面选不到 PDF，
                // 而多选那条路本来传的就是整个数组 —— 两条路此前并不对称。
                pickSingleFile.launch(
                    if (mimeTypes.isEmpty()) arrayOf("*/*") else mimeTypes
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
        // 内核给的当前色（如有）统一成大写再比对：色板与输入框都要用它
        val current = defaultValue.trim().uppercase()
        COLOR_PALETTE.forEach { hex ->
            grid.addView(
                Button(activity).apply {
                    // 色板选中态（审查②补全）：与内核当前色一致的色块加 2dp 深色描边，
                    // 让用户看得出「正在改的是哪个颜色」；描边色近黑，浅色/深色块上皆可辨
                    val isCurrent = hex.equals(current, ignoreCase = true)
                    background = if (isCurrent) {
                        android.graphics.drawable.GradientDrawable().apply {
                            setColor(runCatching { Color.parseColor(hex) }.getOrDefault(Color.BLACK))
                            setStroke(activity.dp(2), 0xFF1F1F1F.toInt())
                        }
                    } else {
                        ColorDrawable(runCatching { Color.parseColor(hex) }.getOrDefault(Color.BLACK))
                    }
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = size
                        height = size
                        setMargins(pad / 3, pad / 3, pad / 3, pad / 3)
                    }
                    // 无障碍：色块没有文字，读屏用户只能靠它知道这是什么
                    contentDescription = activity.getString(R.string.prompt_color_swatch, hex)
                }
            )
        }
        val custom = EditText(activity).apply {
            hint = activity.getString(R.string.prompt_color_custom)
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(pad, pad / 2, pad, 0)
            // 回填当前值：点开对话框看到的是「现在的颜色」，而不是一个空框
            // （此前 defaultValue 被完全忽略 ⇒ 用户每次都要从头输入）
            if (current.isNotEmpty()) setText(current)
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(grid)
            addView(custom)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.prompt_color_title)
            .setView(box)
            // 正按钮传 null：Builder 自带的监听器会**无条件**关闭对话框，于是「输入不合法」
            // 就等价于「点确定 = 取消」。真正的校验在下面的 OnShowListener 里接管正按钮，
            // 非法时只就地报错、窗口留着让用户改完再提交。
            .setPositiveButton(R.string.dlg_ok, null)
            .setNegativeButton(R.string.dlg_cancel) { _, _ -> once(null) }
            .setOnCancelListener { once(null) }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = custom.text.toString().trim()
                if (COLOR_RE.matches(text)) {
                    once(text.uppercase())
                    dialog.dismiss()
                } else {
                    // 就地报错并**保持窗口打开**。这里绝不能应答 —— 应答即代表已裁决，
                    // 页面侧的 GeckoResult 会就此完成，用户再改也没人接。
                    custom.error = activity.getString(R.string.prompt_color_invalid)
                }
            }
        }
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
            // 仅时间：直接弹 TimePickerDialog。
            // 默认值用**宽松**解析（`HH:mm` 与 `HH:mm:ss` 都收）：页面设了 `step=1` 时给的是
            // 带秒形态，用 `HH:mm` 去解析必然失败并静默回落到「此刻」（同族第三个形态）。
            val parsed = parseTimeLoose(defaultValue)
            val lt = parsed?.time ?: LocalTime.now()
            val timeDialog = TimePickerDialog(activity, { _, hh, mm ->
                once(
                    if (parsed != null) formatChosenTime(parsed, hh, mm)
                    else LocalTime.of(hh, mm).format(DT_TIME_FMT)
                )
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
            // DATETIME_LOCAL 的默认值是 yyyy-MM-ddTHH:mm（页面设了 step 时还带秒），
            // 必须用对应格式解析，否则必然失败并回落到「今天」（见 parseDateTimeLocal 的说明）
            DT_TYPE_DATETIME_LOCAL -> parseDateTimeLocal(defaultValue)?.value?.toLocalDate()
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
                    // 日期选定后再弹时间选择器，二者拼成 yyyy-MM-dd'T'HH:mm（带秒形态见下）。
                    // 时间默认值也要从整串里取 —— 直接拿 `HH:mm` 解析整串同样会失败并回落「此刻」
                    val parsedDt = parseDateTimeLocal(defaultValue)
                    val lt = parsedDt?.value?.toLocalTime() ?: LocalTime.now()
                    val timeDialog = TimePickerDialog(activity, { _, hh, mm ->
                        once(
                            if (parsedDt != null) formatChosenDateTimeLocal(parsedDt, chosen, hh, mm)
                            else chosen.atTime(hh, mm).format(DT_DATETIME_FMT)
                        )
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

    override fun onPopupPrompt(targetUri: String, done: (PopupAnswer) -> Unit) {
        var called = false
        fun once(v: PopupAnswer) {
            if (!called) { called = true; done(v) }
        }
        tracked(
            AlertDialog.Builder(activity)
                .setTitle(R.string.app_name)
                .setMessage(activity.getString(R.string.prompt_popup_message, targetUri))
                .setPositiveButton(R.string.perm_allow) { _, _ -> once(PopupAnswer.ALLOW) }
                .setNegativeButton(R.string.perm_deny) { _, _ -> once(PopupAnswer.DENY) }
                // 中性按钮 = 阻止并记住（仅本标签，Tab.popupDenyAlways）：页面循环
                // window.open 时的降噪出口，否则用户只能逐个点掉刷屏的对话框
                .setNeutralButton(R.string.perm_deny_always) { _, _ -> once(PopupAnswer.DENY_ALWAYS) }
                // 用户关闭对话框 = 未选择：按「本次阻止」处理（不记「不再询问」）
                .setOnCancelListener { once(PopupAnswer.DENY) }
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

    override fun onContentPermission(
        perm: GeckoSession.PermissionDelegate.ContentPermission,
        isPrivate: Boolean,
        done: (Boolean) -> Unit,
    ) {
        val uri = perm.uri.orEmpty()
        val host = Uri.parse(uri).host?.takeIf { it.isNotBlank() } ?: uri
        val messageRes = when (perm.permission) {
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
        // 「记住我的选择」：勾选后把授权决定持久化到内核，同站点同权限此后不再询问。
        // 仅普通会话提供 —— 无痕会话不得持久化任何授权决定（与隐私政策承诺一致）。
        var remember = false
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = activity.dp(20)
            setPadding(pad, pad / 2, pad, 0)
            addView(TextView(activity).apply { text = activity.getString(messageRes, host) })
            if (!isPrivate) {
                addView(CheckBox(activity).apply {
                    text = activity.getString(R.string.perm_remember)
                    setOnCheckedChangeListener { _, checked -> remember = checked }
                })
            }
        }
        tracked(
            AlertDialog.Builder(activity)
                .setTitle(host)
                .setView(box)
                .setPositiveButton(R.string.perm_allow) { _, _ ->
                    rememberPermission(perm, allow = true, persist = remember && !isPrivate)
                    once(true)
                }
                .setNegativeButton(R.string.perm_deny) { _, _ ->
                    rememberPermission(perm, allow = false, persist = remember && !isPrivate)
                    once(false)
                }
                .setOnCancelListener { once(false) }
                .create()
        )
    }

    /**
     * 持久化授权决定（「记住我的选择」勾选时调用；普通会话限定，见调用点）。
     * 经内核 StorageController.setPermission 写入；同站点同权限此后由内核在回调前
     * 自查存储、不再走到弹窗。清除出口 = 「清除浏览数据 → Cookie 与站点数据」
     * （位掩码含 PERMISSIONS，政策 §7 已披露），不提供逐条管理界面。
     */
    private fun rememberPermission(
        perm: GeckoSession.PermissionDelegate.ContentPermission,
        allow: Boolean,
        persist: Boolean,
    ) {
        if (!persist) return
        runCatching {
            GeckoHolder.runtime?.storageController?.setPermission(
                perm,
                if (allow) GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                else GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY,
            )
        }
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
