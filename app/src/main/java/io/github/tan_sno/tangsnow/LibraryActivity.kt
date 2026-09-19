package io.github.tan_sno.tangsnow

import android.graphics.Typeface
import android.os.Bundle
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.tan_sno.tangsnow.data.repo.BookmarkRepo
import io.github.tan_sno.tangsnow.data.repo.DownloadRepo
import io.github.tan_sno.tangsnow.data.repo.HistoryRepo
import io.github.tan_sno.tangsnow.databinding.ActivityLibraryBinding
import io.github.tan_sno.tangsnow.ui.LibraryAdapter
import io.github.tan_sno.tangsnow.ui.LibRow
import kotlinx.coroutines.launch

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnClear.setOnClickListener { confirmClear() }

        binding.recycler.layoutManager = LinearLayoutManager(this)
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
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, currentTab)
    }

    private fun switchTo(tab: Int) {
        currentTab = tab
        refreshTabStyles()
        refresh()
    }

    private fun refreshTabStyles() {
        fun style(tv: TextView, active: Boolean) {
            tv.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (active) R.color.accent else R.color.accent_muted
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
            adapter.submit(rows)
            binding.emptyView.text = when (currentTab) {
                TAB_HISTORY -> getString(R.string.history_empty)
                TAB_BOOKMARKS -> getString(R.string.bookmarks_empty)
                else -> getString(R.string.downloads_empty)
            }
            binding.emptyView.isVisible = rows.isEmpty()
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
            android.widget.Toast.makeText(
                this@LibraryActivity, R.string.toast_cleared, android.widget.Toast.LENGTH_SHORT
            ).show()
            refresh()
        }
    }

    /** 打开下载文件：按仓库返回的分级结果给出准确提示（不再一律"无法打开"） */
    private fun openDownload(item: DownloadRepo.Item) {
        showDownloadResult(DownloadRepo.open(this, item), share = false)
    }

    /** 长按下载行：分享已完成的文件（含 URI 读取授权，一次会话内有效） */
    private fun onLongPressRow(row: LibRow): Boolean {
        // 只认「行自己是下载行」，不再依赖 currentTab（同上：切页签的滚动窗口内
        // currentTab 与界面上显示的行可能不一致）
        val item = row.tag as? DownloadRepo.Item ?: return false
        showDownloadResult(DownloadRepo.share(this, item), share = true)
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
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val KEY_TAB = "lib_tab"
        const val TAB_HISTORY = 0
        const val TAB_BOOKMARKS = 1
        const val TAB_DOWNLOADS = 2
    }
}