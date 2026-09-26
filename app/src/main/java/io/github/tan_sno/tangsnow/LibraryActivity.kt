package io.github.tan_sno.tangsnow

import android.graphics.Typeface
import android.os.Bundle
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.tan_sno.tangsnow.data.BookmarkHtml
import io.github.tan_sno.tangsnow.data.repo.BookmarkRepo
import io.github.tan_sno.tangsnow.data.repo.DownloadRepo
import io.github.tan_sno.tangsnow.data.repo.HistoryRepo
import io.github.tan_sno.tangsnow.databinding.ActivityLibraryBinding
import io.github.tan_sno.tangsnow.ui.LibraryAdapter
import io.github.tan_sno.tangsnow.ui.LibRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 资料库：历史 / 书签 / 下载（三个页签同处一行） */
class LibraryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLibraryBinding
    private var currentTab = TAB_HISTORY
    private val adapter = LibraryAdapter(
        onOpen = { openRow(it) },
        onRemove = { removeRow(it) },
        onLongClick = { row -> onLongPressRow(row) },
    )

    /** 刷新序号：快速切页签时只提交最新一次查询，避免慢查询晚到覆盖新页签的列表 */
    private var refreshSeq = 0L

    /** 当前搜索关键词（只作用于当前页签；切页签时清空，见 [switchTo]） */
    private var keyword = ""

    /** 搜索输入去抖：避免每敲一个字符都重新查库并整表重绑 */
    private val searchHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var searchTask: Runnable? = null

    /** 书签导入：系统文件选择器（Netscape HTML，各浏览器「导出书签」的标准格式） */
    private val importBookmarksLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importBookmarks(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnClear.setOnClickListener { confirmClear() }
        binding.btnBookmarkTools.setOnClickListener { showBookmarkTools() }

        // 搜索：输入去抖后重新过滤（只作用于当前页签）
        binding.searchInput.doAfterTextChanged { text ->
            val next = text?.toString().orEmpty()
            // 与当前关键词相同就直接返回 —— 「清空」按钮触发的 setText("") 也走这里，
            // 由于 switchTo/清空处都是「先改 keyword 再 setText」，这里会早退，不会重复刷新。
            if (next == keyword) return@doAfterTextChanged
            keyword = next
            scheduleSearch()
        }
        binding.btnClearSearch.setOnClickListener {
            binding.searchInput.setText("") // 监听里会同步 keyword 并触发一次刷新
        }

        // hasFixedSize：列表尺寸是 match_parent，不随条目内容变化。资料库可能有上千条历史，
        // 声明后每次刷新（切换页签 / 删除条目）都能跳过 requestLayout() 带来的整树测量。
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.setHasFixedSize(true)
        binding.recycler.addItemDecoration(
            DividerItemDecoration(this, DividerItemDecoration.VERTICAL)
        )
        binding.recycler.adapter = adapter

        binding.tabHistory.setOnClickListener { switchTo(TAB_HISTORY) }
        binding.tabBookmarks.setOnClickListener { switchTo(TAB_BOOKMARKS) }
        binding.tabDownloads.setOnClickListener { switchTo(TAB_DOWNLOADS) }

        // 键名唯一定义在 BrowserOpener.EXTRA_LIBRARY_TAB（值为 "library_tab"）
        val fromIntent = intent?.getIntExtra(BrowserOpener.EXTRA_LIBRARY_TAB, -1) ?: -1
        val fromState = savedInstanceState?.getInt(KEY_TAB, TAB_HISTORY) ?: TAB_HISTORY
        switchTo(if (fromIntent in 0..2) fromIntent else fromState)
        // 恢复搜索词 —— 必须在 switchTo 之后：switchTo 里的「清空关键词」是给
        // 「用户主动切页签」用的语义，而这里是 Activity 重建，不是用户切页签。
        // 同步 keyword 才能与系统已恢复的搜索框文本保持一致（见 onSaveInstanceState）。
        savedInstanceState?.getString(KEY_SEARCH)?.takeIf { it.isNotEmpty() }?.let { saved ->
            keyword = saved
            binding.searchInput.setText(saved) // 触发输入监听 → 按恢复的词过滤
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, currentTab)
        // 搜索词也要存：EditText 的文本由系统自行恢复（EditText.getFreezesText() 恒为 true），
        // 而 keyword 是普通字段 —— 不存就会出现「搜索框里有字、列表却没过滤」的不一致。
        outState.putString(KEY_SEARCH, keyword)
    }

    override fun onDestroy() {
        // 摘掉待执行的搜索任务：否则 Handler 仍持有它，回调会摸到已失效的 binding
        searchTask?.let { searchHandler.removeCallbacks(it) }
        searchTask = null
        super.onDestroy()
    }

    private fun switchTo(tab: Int) {
        currentTab = tab
        // 书签导入/导出仅对书签页签有意义：其余页签隐藏该入口
        binding.btnBookmarkTools.isVisible = tab == TAB_BOOKMARKS
        // 切页签时清空搜索：否则用户会看到「明明有内容却空着」，无从判断是没匹配还是没数据。
        // ⚠️ 顺序必须是「先改 keyword 再 setText」：这样输入监听里的 next == keyword 判断能早退，
        // 不会因为这次程序性赋值再触发一次多余的刷新。
        if (keyword.isNotEmpty()) {
            keyword = ""
            binding.searchInput.setText("")
        }
        refreshTabStyles()
        refresh()
    }

    /** 输入去抖：等用户停手后再查库，避免逐字符刷新整个列表（量级与 FindBarController 一致） */
    private fun scheduleSearch() {
        searchTask?.let { searchHandler.removeCallbacks(it) }
        val task = Runnable { refresh() }
        searchTask = task
        searchHandler.postDelayed(task, SEARCH_DEBOUNCE_MS)
    }

    private fun refreshTabStyles() {
        fun style(tv: TextView, active: Boolean) {
            tv.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (active) R.color.accent_text else R.color.accent_muted
                )
            )
            tv.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            tv.setBackgroundResource(
                if (active) R.drawable.bg_tab_active else android.R.color.transparent
            )
        }
        style(binding.tabHistory, currentTab == TAB_HISTORY)
        style(binding.tabBookmarks, currentTab == TAB_BOOKMARKS)
        style(binding.tabDownloads, currentTab == TAB_DOWNLOADS)
    }

    private fun refresh() {
        val seq = ++refreshSeq
        // 进协程前取一次关键词快照：查询期间用户可能又改了输入，
        // 用快照才能保证「过滤用的词」与「这批数据」是同一时刻的。
        val query = keyword.trim()
        lifecycleScope.launch {
            val rows: List<LibRow> = when (currentTab) {
                TAB_HISTORY -> {
                    HistoryRepo.list().map { LibraryAdapter.historyRow(it) }
                }
                TAB_BOOKMARKS -> {
                    BookmarkRepo.list().map { LibraryAdapter.bookmarkRow(it) }
                }
                else -> {
                    DownloadRepo.list(this@LibraryActivity)
                        .map { LibraryAdapter.downloadRow(it, this@LibraryActivity) }
                }
            }
            // 期间用户又切了页签：丢弃本次过期结果，避免错位覆盖
            if (seq != refreshSeq) return@launch
            // 关键词过滤（大小写不敏感，匹配「标题 + 网址 / 文件名」）。
            // 数据源本身很小 —— 历史上限 HISTORY_KEEP = 200，书签与下载记录更少 ——
            // 在内存里过滤即可；改仓库层的 SQL LIKE 还要连带改测试，收益不成比例。
            val shown = if (query.isEmpty()) {
                rows
            } else {
                rows.filter {
                    it.title.contains(query, ignoreCase = true) ||
                        it.sub.contains(query, ignoreCase = true)
                }
            }
            adapter.submit(shown)
            binding.emptyView.text = if (shown.isEmpty() && query.isNotEmpty()) {
                // 区分「还没有内容」与「有内容但没匹配」：后者要提示用户改关键词，
                // 而不是让他以为数据丢了。
                getString(R.string.library_no_match)
            } else {
                when (currentTab) {
                    TAB_HISTORY -> getString(R.string.history_empty)
                    TAB_BOOKMARKS -> getString(R.string.bookmarks_empty)
                    else -> getString(R.string.downloads_empty)
                }
            }
            binding.emptyView.isVisible = shown.isEmpty()
        }
    }

    /**
     * 打开某一行。
     *
     * ⚠️ 按**行自身的类型**分派，不能按 `currentTab` 分派：切换页签后新列表要等一次
     * 数据库查询才返回（下载页还要查 DownloadManager + MediaStore，可达数百毫秒），
     * 这期间界面上仍然是**旧页签的行**。此时点它会走进新页签的分支并强转错误类型
     * （历史 tag 是 `Long`、书签是 `String`、下载是 `Item`），直接抛 `ClassCastException`
     * 崩在 lifecycleScope 协程里（无 CoroutineExceptionHandler → 崩溃）。
     */
    private fun openRow(row: LibRow) {
        when (val tag = row.tag) {
            // 下载行需要按 id 交给系统/FileProvider 打开
            is DownloadRepo.Item -> openDownload(tag)
            // 历史与书签的行都带 URL（row.sub），开法一致
            is Long, is String -> BrowserOpener.open(this, row.sub)
            // 未知类型：不动作（防御，正常不会发生）
            else -> Unit
        }
    }

    private fun removeRow(row: LibRow) {
        lifecycleScope.launch {
            // 同上：按 tag 类型分派，避免「切页签后点旧行」被强转成错误类型而崩溃
            when (val tag = row.tag) {
                is DownloadRepo.Item -> DownloadRepo.remove(this@LibraryActivity, tag)
                is Long -> HistoryRepo.delete(tag)
                is String -> BookmarkRepo.remove(tag)
                else -> return@launch
            }
            refresh()
        }
    }

    /**
     * 顶栏「清空」：对当前页签执行一键清空（历史 / 书签 / 下载记录），先二次确认。
     * 下载只清记录不删文件 —— 文件是用户资产，误删不可逆，与逐行删除的语义分开。
     */
    private fun confirmClear() {
        val message = when (currentTab) {
            TAB_HISTORY -> getString(
                R.string.library_clear_confirm, getString(R.string.library_clear_history)
            )
            TAB_BOOKMARKS -> getString(
                R.string.library_clear_confirm, getString(R.string.library_clear_bookmarks)
            )
            else -> getString(
                R.string.library_clear_confirm, getString(R.string.library_clear_downloads)
            ) + "\n\n" + getString(R.string.library_clear_downloads_hint)
        }
        val title = when (currentTab) {
            TAB_HISTORY -> R.string.library_clear_history
            TAB_BOOKMARKS -> R.string.library_clear_bookmarks
            else -> R.string.library_clear_downloads
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(R.string.dlg_cancel, null)
            .setPositiveButton(R.string.dlg_ok) { _, _ -> clearCurrentTab() }
            .show()
    }

    private fun clearCurrentTab() {
        lifecycleScope.launch {
            when (currentTab) {
                TAB_HISTORY -> HistoryRepo.clear()
                TAB_BOOKMARKS -> BookmarkRepo.clear()
                else -> DownloadRepo.clearRecords(this@LibraryActivity)
            }
            toast(R.string.toast_cleared)
            refresh()
        }
    }

    // ------------------------------------------------------------- 书签导入 / 导出

    /**
     * 书签导入/导出入口（仅书签页签可见）。导入/导出均为 Netscape 书签 HTML ——
     * Chrome / Firefox / Edge「导出书签」的统一格式，互相可交换。
     */
    private fun showBookmarkTools() {
        AlertDialog.Builder(this)
            .setTitle(R.string.bookmark_tools_title)
            .setItems(
                arrayOf(
                    getString(R.string.bookmark_menu_export),
                    getString(R.string.bookmark_menu_import),
                )
            ) { _, which ->
                when (which) {
                    0 -> exportBookmarks()
                    else -> importBookmarksLauncher.launch(
                        arrayOf("text/html", "text/plain", "*/*")
                    )
                }
            }
            .show()
    }

    /** 导出：全部书签 → Netscape HTML → 公共「下载」目录（零存储权限，与存为 PDF 同路） */
    private fun exportBookmarks() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val list = BookmarkRepo.list()
                if (list.isEmpty()) return@withContext null
                list.size to writeBookmarksHtml(BookmarkHtml.export(list))
            }
            if (isFinishing || isDestroyed) return@launch
            when {
                result == null -> toast(R.string.bookmark_export_empty)
                result.second -> toast(
                    // 复数文案（en 下 1 条与多条不同）；第一个参数选 quantity，第二个进格式化
                    resources.getQuantityString(
                        R.plurals.bookmark_export_done, result.first, result.first
                    )
                )
                else -> toast(R.string.bookmark_export_failed)
            }
        }
    }

    /** 写盘；文件名带时间戳避免覆盖既有导出。@return 是否成功 */
    private fun writeBookmarksHtml(html: String): Boolean {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val name = "tangsnow_bookmarks_$stamp.html"
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val resolver = contentResolver
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/html")
                    put(
                        android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    )
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: return false
                // 写流与「IS_PENDING 清零 update」整体入 try：update 抛异常也清占位行
                val written = try {
                    resolver.openOutputStream(uri)?.use { out ->
                        out.write(html.toByteArray(Charsets.UTF_8))
                    }
                    values.clear()
                    values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    true
                } catch (e: kotlinx.coroutines.CancellationException) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw e
                } catch (_: Exception) {
                    false
                }
                if (!written) {
                    runCatching { resolver.delete(uri, null, null) }
                    return false
                }
                true
            } else {
                // API 26-28：无存储权限，写应用专属下载目录（文件管理器仍可见）
                val dir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                    ?: return false
                java.io.File(dir, name).writeText(html, Charsets.UTF_8)
                true
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /** 导入：Netscape HTML → 解析 → 清洗（scheme 白名单/去重/上限）→ 逐条入库 */
    private fun importBookmarks(uri: android.net.Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val text = readImportText(uri) ?: return@withContext null
                val raw = BookmarkHtml.parseImport(text)
                val existing = BookmarkRepo.list().mapTo(HashSet()) { it.url }
                val (entries, skipped) = BookmarkHtml.sanitize(raw, existing)
                BookmarkRepo.addAll(entries.map { it.url to it.title })
                entries.size to skipped
            }
            if (isFinishing || isDestroyed) return@launch
            if (result == null) {
                toast(R.string.bookmark_import_failed)
                return@launch
            }
            toast(
                resources.getQuantityString(
                    R.plurals.bookmark_import_done, result.first, result.first, result.second
                )
            )
            refresh()
        }
    }

    /**
     * 读入导入文件全文（UTF-8）。
     * 超过 [BookmarkHtml.MAX_IMPORT_CHARS] 视为无效返回 **null**（由调用方按
     * 「导入失败」提示）—— 注意 `return@use` 只能退出内层 `reader.use`，
     * 故这里用 over 标志穿透两层 use，而不是带标签 return。
     */
    private fun readImportText(uri: android.net.Uri): String? = try {
        contentResolver.openInputStream(uri)?.use { ins ->
            val buf = StringBuilder()
            val chunk = CharArray(8192)
            var overLimit = false
            java.io.InputStreamReader(ins, Charsets.UTF_8).use { reader ->
                while (!overLimit) {
                    val n = reader.read(chunk)
                    if (n <= 0) break
                    buf.append(chunk, 0, n)
                    if (buf.length > BookmarkHtml.MAX_IMPORT_CHARS) overLimit = true
                }
            }
            if (overLimit) null else buf.toString()
        }
    } catch (_: Exception) {
        null
    }

    /**
     * 打开下载文件：按仓库返回的分级结果给出准确提示（不再一律"无法打开"）。
     * open 为 suspend（文件存在性等检查在 IO 线程），经 lifecycleScope 挂起等待后回主线程提示；
     * 期间页面已销毁则静默放弃，不再对已失效的界面做任何动作。
     */
    private fun openDownload(item: DownloadRepo.Item) {
        lifecycleScope.launch {
            val result = DownloadRepo.open(this@LibraryActivity, item)
            if (isFinishing || isDestroyed) return@launch
            showDownloadResult(result, share = false)
        }
    }

    /** 长按下载行：分享已完成的文件（含 URI 读取授权，一次会话内有效）；线程约定同 [openDownload] */
    private fun onLongPressRow(row: LibRow): Boolean {
        // 只认「行自己是下载行」，不再依赖 currentTab（同上：切页签的滚动窗口内
        // currentTab 与界面上显示的行可能不一致）
        val item = row.tag as? DownloadRepo.Item ?: return false
        lifecycleScope.launch {
            val result = DownloadRepo.share(this@LibraryActivity, item)
            if (isFinishing || isDestroyed) return@launch
            showDownloadResult(result, share = true)
        }
        return true
    }

    /**
     * 把仓库的分级结果翻译成用户可据以行动的提示：
     * 未下完 / 下载失败 / 文件已失效 / 没有应用能处理，四种原因各不相同。
     */
    private fun showDownloadResult(result: DownloadRepo.Result, share: Boolean) {
        val msg = when (result) {
            DownloadRepo.Result.OK -> return
            DownloadRepo.Result.NOT_READY -> R.string.toast_file_not_ready
            DownloadRepo.Result.FAILED -> R.string.toast_file_download_failed
            DownloadRepo.Result.MISSING -> R.string.toast_file_missing
            DownloadRepo.Result.NO_APP ->
                if (share) R.string.toast_share_file_failed else R.string.toast_open_file_failed
        }
        // msg 是 R.string.* 资源 id，与 Context.toast(resId) 扩展（同包，无需 import）语义一致
        toast(msg)
    }

    companion object {
        private const val KEY_TAB = "lib_tab"
        private const val KEY_SEARCH = "lib_search"
        const val TAB_HISTORY = 0
        const val TAB_BOOKMARKS = 1
        const val TAB_DOWNLOADS = 2

        /** 搜索输入去抖时长（与 FindBarController 的 250ms 同量级） */
        private const val SEARCH_DEBOUNCE_MS = 250L
    }
}