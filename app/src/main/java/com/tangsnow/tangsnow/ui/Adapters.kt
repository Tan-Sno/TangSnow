package com.tangsnow.tangsnow.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.tangsnow.tangsnow.browser.Tab
import com.tangsnow.tangsnow.data.Bookmark
import com.tangsnow.tangsnow.data.repo.DownloadRepo
import com.tangsnow.tangsnow.data.HistoryItem
import com.tangsnow.tangsnow.databinding.ItemLibraryBinding
import com.tangsnow.tangsnow.databinding.ItemTabBinding

/** 资料库通用行（历史 / 书签 / 下载共用） */
class LibRow(val title: String, val sub: String, val tag: Any)

class LibraryAdapter(
    private val onOpen: (LibRow) -> Unit,
    private val onRemove: (LibRow) -> Unit,
    private val onLongClick: ((LibRow) -> Boolean)? = null,
) : RecyclerView.Adapter<LibraryAdapter.VH>() {

    private val items = mutableListOf<LibRow>()

    fun submit(list: List<LibRow>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val binding: ItemLibraryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemLibraryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = items[position]
        holder.binding.txtTitle.text = row.title.ifBlank { row.sub }
        holder.binding.txtSub.text = row.sub
        holder.binding.root.setOnClickListener { onOpen(row) }
        holder.binding.btnRemove.setOnClickListener { onRemove(row) }
        holder.binding.root.setOnLongClickListener {
            onLongClick?.invoke(row) == true
        }
    }

    companion object {
        fun historyRow(item: HistoryItem) = LibRow(item.title.ifBlank { item.url }, item.url, item.id)
        fun bookmarkRow(item: Bookmark) = LibRow(item.title.ifBlank { item.url }, item.url, item.url)
        fun downloadRow(item: DownloadRepo.Item, context: android.content.Context): LibRow {
            // 状态 → 文案的映射由数据层给出（见 DownloadRepo.stateLabelRes）：
            // 界面层不再复制 "suc"/"run" 这类状态字面量，改状态时不会漏改这里
            val state = context.getString(DownloadRepo.stateLabelRes(item), item.progressPercent)
            return LibRow(item.title, state, item)
        }
    }
}

/** 标签页网格适配器 */
class TabsAdapter(
    private val onOpen: (Tab) -> Unit,
    private val onClose: (Tab) -> Unit,
    private val onLongClick: ((Tab) -> Unit)? = null,
) : RecyclerView.Adapter<TabsAdapter.VH>() {

    private val items = mutableListOf<Tab>()

    fun submit(list: List<Tab>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    /** 单个标签缩略图更新（抓图回调 / 切换后刷新，避免整表刷新闪烁） */
    fun updateTab(tab: Tab) {
        val index = items.indexOfFirst { it.id == tab.id }
        if (index >= 0) notifyItemChanged(index)
    }

    class VH(val binding: ItemTabBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemTabBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val tab = items[position]
        val ctx = holder.binding.root.context
        val newTabLabel = ctx.getString(com.tangsnow.tangsnow.R.string.tabs_new)
        holder.binding.tabTitle.text = tab.title.ifBlank { tab.url ?: newTabLabel }
        holder.binding.tabUrl.text = tab.url ?: ""
        holder.binding.root.setOnClickListener { onOpen(tab) }
        holder.binding.root.setOnLongClickListener {
            onLongClick?.invoke(tab)
            true
        }
        holder.binding.tabClose.setOnClickListener { onClose(tab) }

        // 页面视图：优先展示内核截图；未截图时显示首字母头像
        val preview = tab.preview
        if (preview != null) {
            holder.binding.tabPreview.isVisible = true
            holder.binding.tabPreview.setImageBitmap(preview)
            holder.binding.tabGlyph.isVisible = false
        } else {
            holder.binding.tabPreview.isVisible = false
            holder.binding.tabPreview.setImageDrawable(null)
            holder.binding.tabGlyph.isVisible = true
            val fallback = ctx.getString(com.tangsnow.tangsnow.R.string.app_name)
            val text = tab.title.ifBlank { tab.url ?: fallback }
            holder.binding.tabGlyph.text = text.take(1).uppercase()
            holder.binding.tabGlyph.backgroundTintList =
                ColorStateList.valueOf(HomeTilesAdapter.colorFor(ctx, text))
        }
    }
}