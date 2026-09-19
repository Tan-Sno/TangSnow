package io.github.tan_sno.tangsnow

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.github.tan_sno.tangsnow.data.repo.BookmarkRepo
import io.github.tan_sno.tangsnow.data.repo.HistoryRepo
import io.github.tan_sno.tangsnow.data.SearchEngines
import io.github.tan_sno.tangsnow.util.dp
import io.github.tan_sno.tangsnow.util.selectableRipple
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 地址栏联想（本地历史 / 书签）。
 * 从 MainActivity 抽出，职责单一：监听地址栏输入，防抖加载本地历史与书签，
 * 渲染联想列表并处理点击（打开网页 / 用当前引擎搜索）。
 * 通过 [MainActivity] 引用访问其 internal 成员与导航方法。
 */
class SuggestionsController(private val activity: MainActivity) {

    private var panel: View? = null
    private var list: LinearLayout? = null
    private var seq = 0L

    fun resolve() {
        panel = activity.binding.root.findViewById(R.id.suggestionPanel)
        list = activity.binding.root.findViewById(R.id.suggestionList)
        val address = activity.binding.toolbar.addressBar
        address.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val text = s?.toString().orEmpty()
                // 联想只在“首页搜索态”出现：加载中的网页/已打开的页面不弹层，避免遮挡操作
                if (address.hasFocus() && text.isNotBlank() && allowed()) {
                    schedule(text)
                } else {
                    hide()
                }
            }
        })
        address.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) hide()
        }
    }

    /** 联想可展示条件：仅当回到空白首页搜索框（非浏览态、非无痕下内容页） */
    private fun allowed(): Boolean {
        if (!activity.homeVisible) return false
        val url = activity.sessionManager.activeTab?.url
        return url.isNullOrBlank() || url.orEmpty().startsWith("about:")
    }

    /** 防抖 + 序号比对，只渲染“最后一次输入”的结果 */
    private fun schedule(text: String) {
        val current = ++seq
        // ⚠️ 「是否无痕」必须在**主线程**读取后带进 IO：`sessionManager.activeTab` 是主线程状态，
        // 在 Dispatchers.IO 里读可能拿到过期值。一旦因此把无痕标签误判成普通标签，
        // 就会把本地历史 / 书签联想渲染到无痕会话上 —— 与「无痕不留痕迹」的承诺直接冲突。
        val isPrivate = activity.sessionManager.activeTab?.isPrivate == true
        activity.lifecycleScope.launch {
            val matches = withContext(Dispatchers.IO) { load(text, isPrivate) }
            if (current != seq) return@launch
            if (!activity.binding.toolbar.addressBar.hasFocus()) return@launch
            if (activity.binding.toolbar.addressBar.text.toString() != text) return@launch
            render(matches, text)
        }
    }

    /** 本地历史 + 书签模糊匹配，去重后最多取 4 条 */
    private suspend fun load(text: String, isPrivate: Boolean): List<Pair<String, String>> {
        // 无痕浏览不做任何本地联想（含历史与书签）：与 Firefox 无痕行为一致，避免泄露
        if (isPrivate) return emptyList()
        val kw = text.trim().lowercase()
        if (kw.isEmpty()) return emptyList()
        val items = mutableListOf<Pair<String, String>>()
        val seen = HashSet<String>()
        fun consider(title: String, url: String) {
            if (url.isBlank() || url.startsWith("about:")) return
            if (!seen.add(url)) return
            items += title.ifBlank { url } to url
        }
        try {
            // 快捷联想只取最近 10 条历史（完整 200 条留在「历史记录」页）
            HistoryRepo.recent(10).forEach { consider(it.title, it.url) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 取消必须**原样传播**：吞掉它会让已取消的协程继续跑完剩下的查询
            //（本仓库坑清单里记着这条，`ClearDataUseCase` 同此写法）
            throw e
        } catch (_: Throwable) {
            // 单侧读取失败不影响另一侧联想：静默降级为「这一类没有结果」
        }
        try {
            BookmarkRepo.list().forEach { consider(it.title, it.url) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            // 同上一处：单侧读取失败不影响另一侧联想，静默降级为「这一类没有结果」
        }
        return items
            .filter { (t, u) -> t.lowercase().contains(kw) || u.lowercase().contains(kw) }
            .distinctBy { it.second }
            .take(4)
    }

    private fun render(matches: List<Pair<String, String>>, query: String) {
        val listView = list ?: return
        val panelView = panel ?: return
        listView.removeAllViews()
        val subtitleColor = activity.getColor(R.color.accent_muted)
        val textColor = activity.getColor(R.color.accent)
        matches.forEach { (title, url) ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(activity.dp(18), activity.dp(6), activity.dp(18), activity.dp(6))
                selectableRipple()
                setOnClickListener {
                    hide()
                    activity.binding.toolbar.addressBar.clearFocus()
                    activity.navigateToUrl(url)
                }
            }
            row.addView(TextView(activity).apply {
                text = title
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(textColor)
                textSize = 15f
            })
            row.addView(TextView(activity).apply {
                text = url
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setTextColor(subtitleColor)
                textSize = 12f
            })
            listView.addView(row)
        }
        // 末行：用当前引擎搜索该关键词
        val engine = SearchEngines.current(activity.prefs)
        val searchRow = TextView(activity).apply {
            text = activity.getString(
                R.string.suggestion_search,
                SearchEngines.localizedLabel(activity, engine),
                query.trim(),
            )
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(activity.dp(18), activity.dp(14), activity.dp(18), activity.dp(14))
            setTextColor(activity.getColor(R.color.accent))
            textSize = 15f
            setOnClickListener {
                hide()
                activity.binding.toolbar.addressBar.clearFocus()
                activity.navigate(query.trim())
            }
        }
        listView.addView(searchRow)
        panelView.isVisible = true
    }

    fun hide() {
        panel?.isVisible = false
    }
}
