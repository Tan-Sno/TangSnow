package io.github.tan_sno.tangsnow

import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.core.view.isVisible
import org.mozilla.geckoview.GeckoSession

/**
 * 页内查找（内核 SessionFinder）控制器。
 * 从 MainActivity 抽出：解析查找条视图、管理查找生命周期（打开/关闭/防抖查找），
 * 查找结果经内核异步回传后刷新计数。
 */
class FindBarController(private val activity: MainActivity) {

    private val handler = Handler(Looper.getMainLooper())
    private var debounce: Runnable? = null
    private var bar: android.widget.LinearLayout? = null
    private var input: android.widget.EditText? = null
    private var count: android.widget.TextView? = null

    /** 从主布局解析查找条（findViewById 比 viewBinding include 更直接，避免绑定类型差异） */
    fun resolve() {
        val b = activity.binding.root.findViewById<android.widget.LinearLayout>(R.id.findBar) ?: return
        bar = b
        input = b.findViewById(R.id.findInput)
        count = b.findViewById(R.id.findCount)
        b.findViewById<View>(R.id.btnFindPrev)?.setOnClickListener {
            runFind(input?.text?.toString().orEmpty(), forward = false)
        }
        b.findViewById<View>(R.id.btnFindNext)?.setOnClickListener {
            runFind(input?.text?.toString().orEmpty(), forward = true)
        }
        b.findViewById<View>(R.id.btnFindClose)?.setOnClickListener { close() }
        input?.setOnEditorActionListener { _, _, _ ->
            runFind(input?.text?.toString().orEmpty(), forward = true)
            true
        }
        input?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                debounce?.let { handler.removeCallbacks(it) }
                val task = Runnable {
                    runFind(s?.toString().orEmpty(), forward = true)
                }
                debounce = task
                handler.postDelayed(task, 250L)
            }
        })
    }

    fun open() {
        val tab = activity.sessionManager.activeTab ?: return
        val url = tab.url ?: return
        if (url.isBlank() || url.startsWith("about:")) {
            activity.toast(R.string.more_need_page)
            return
        }
        activity.hideMoreSheet()
        runCatching {
            val finder = tab.session.finder
            finder.setDisplayFlags(GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL)
            finder.clear()
        }
        val b = bar ?: return
        val i = input ?: return
        b.isVisible = true
        count?.text = ""
        i.setText("")
        i.requestFocus()
        activity.showKeyboard(i)
    }

    fun close(clear: Boolean = true) {
        debounce?.let { handler.removeCallbacks(it) }
        debounce = null
        if (clear) runCatching { activity.sessionManager.activeTab?.session?.finder?.clear() }
        if (bar?.isVisible == true) bar?.isVisible = false
        activity.hideKeyboard()
    }

    /** Activity 销毁前摘除防抖任务，避免销毁后仍触发查找访问已回收的视图 */
    fun cancelPending() {
        debounce?.let { handler.removeCallbacks(it) }
        debounce = null
    }

    private fun runFind(query: String, forward: Boolean) {
        val finder = activity.sessionManager.activeTab?.session?.finder ?: return
        if (query.isEmpty()) {
            finder.clear()
            count?.text = ""
            return
        }
        val flags = if (forward) GeckoSession.FINDER_FIND_FORWARD else GeckoSession.FINDER_FIND_BACKWARDS
        finder.find(query, flags).accept(
            { res ->
                activity.runOnUiThread {
                    val text = if (res != null && res.found && res.total > 0) {
                        activity.getString(R.string.find_result, res.current + 1, res.total)
                    } else {
                        activity.getString(R.string.find_empty)
                    }
                    count?.text = text
                }
            },
            { _ -> }
        )
    }
}
