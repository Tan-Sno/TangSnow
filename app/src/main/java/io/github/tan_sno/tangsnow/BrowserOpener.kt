package io.github.tan_sno.tangsnow

import android.content.Context
import android.content.Intent
import android.widget.Toast

/** 统一的应用内跳转入口：外部页面（历史/书签/下载…）带回 MainActivity 打开 */
object BrowserOpener {

    const val EXTRA_OPEN_URL = "open_url"
    const val EXTRA_LIBRARY_TAB = "library_tab"

    /** 置为 true 时 EXTRA_OPEN_URL 将在 MainActivity 的「新标签页」里打开 */
    const val EXTRA_OPEN_NEW_TAB = "open_new_tab"

    /**
     * 应用内**特权跳转**的进程内通道。
     *
     * 为什么需要它：MainActivity 是 exported 入口，任何应用都能用 `ACTION_VIEW` 或自造
     * extras 调起它，因此那条入口只接受 `http`/`https`。但应用自己需要打开
     * `moz-extension://`（扩展的选项页/管理页）这类特权页面 —— 它既不能被外部应用驱动，
     * 也不该依赖一个**可被伪造**的 extra 当信任标记（extras 对 exported Activity 没有保护）。
     * 于是改为进程内传递：只有本进程的代码能写入 [pendingUrl]，外部 Intent 永远读不到它。
     *
     * 存活期刻意很短（[PENDING_TTL_MS]）：它只是"紧接着要打开这个地址"的一次性便签，
     * 过期即作废，避免某个陈旧值在用户后来某次正常启动时把页面劫走。
     */
    private const val PENDING_TTL_MS = 5_000L

    private val lock = Any()
    private var pendingUrl: String? = null
    private var pendingNewTab = false
    private var pendingAt = 0L

    /** 取出并清空待打开请求；过期或没有则返回 null */
    fun consumePending(): Pair<String, Boolean>? = synchronized(lock) {
        val url = pendingUrl ?: return null
        val newTab = pendingNewTab
        val fresh = System.currentTimeMillis() - pendingAt <= PENDING_TTL_MS
        // 无论是否新鲜都要清空：过期值更不能留着劫持后续启动
        pendingUrl = null
        pendingNewTab = false
        if (fresh) url to newTab else null
    }

    private fun setPending(url: String, newTab: Boolean) = synchronized(lock) {
        pendingUrl = url
        pendingNewTab = newTab
        pendingAt = System.currentTimeMillis()
    }

    /** 在当前（或新建）标签页里打开 URL：清掉顶上的页面，把结果送还给 MainActivity */
    fun open(context: Context, url: String) {
        if (url.isEmpty()) return
        setPending(url, newTab = false)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            // extra 只对 http/https 有效（MainActivity 的 exported 入口仅收这两种）；
            // 特权 scheme 靠上面的进程内通道传递，见 consumePending 的注释。
            putExtra(EXTRA_OPEN_URL, url)
        }
        context.startActivity(intent)
    }

    /** 在新标签页里打开 URL（扩展设置/管理页等场景，不覆盖当前浏览的页面） */
    fun openNewTab(context: Context, url: String) {
        if (url.isEmpty()) return
        setPending(url, newTab = true)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_URL, url)
            putExtra(EXTRA_OPEN_NEW_TAB, true)
        }
        context.startActivity(intent)
    }

    fun openLibrary(context: Context, tab: Int) {
        // 注意：不要加 FLAG_ACTIVITY_NEW_TASK。资料库是浏览器内部的子页面，
        // 应与 MainActivity 处于同一任务栈，这样返回键才能正确回到浏览器主界面。
        val intent = Intent(context, LibraryActivity::class.java)
            .putExtra(EXTRA_LIBRARY_TAB, tab)
        context.startActivity(intent)
    }
}

/** 全局轻提示（无 private 副本的页面直接用；MainActivity/ExtensionsActivity 有各自的 Int 成员版） */
fun Context.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

fun Context.toast(resId: Int) {
    Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
}