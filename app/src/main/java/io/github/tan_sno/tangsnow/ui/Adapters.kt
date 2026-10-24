package io.github.tan_sno.tangsnow.ui

import android.content.res.ColorStateList
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.TouchDelegate
import android.view.ViewGroup
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import io.github.tan_sno.tangsnow.browser.Tab
import io.github.tan_sno.tangsnow.data.Bookmark
import io.github.tan_sno.tangsnow.data.repo.DownloadRepo
import io.github.tan_sno.tangsnow.data.HistoryItem
import io.github.tan_sno.tangsnow.databinding.ItemLibraryBinding
import io.github.tan_sno.tangsnow.util.CodePoints
import io.github.tan_sno.tangsnow.databinding.ItemTabBinding

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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemTabBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        // 「删除」是全仓最小的触摸目标（32×32dp），而且是**破坏性**操作。
        // 刻意**不改布局尺寸**：卡片高度由内容驱动，尺寸变大既会盖住更多预览图，
        // 又需要连带加大内层的 marginEnd（否则标题/URL 被压住）—— 两处都会动视觉。
        // TouchDelegate 只扩大命中区，视觉与布局都不动。
        // ⚠️ 命中区向卡片外扩的部分会被父容器裁掉（tabClose 距卡片边只有 6dp），
        // 所以内侧扩到 48dp、外侧约 40dp；布局节点本身仍是 32dp，
        // 因此 `TouchTargetSentinelTest` 的豁免表里保留这一条并注明原因。
        //
        // ⚠️⚠️ 命中矩形必须在**布局完成之后**算（2026-10-20 审查报告 C-8）：
        // 此前用的是 `binding.root.post { … }` —— 视图尚未 attach 时 `post` 会把任务排进
        // **attach 阶段**的队列，那时**还没测量**，`close.left/top/right/bottom` 全是 0
        // ⇒ 命中矩形退化成卡片左上角 (-8,-8)-(8,8) 的**隐形热区**（点中即关闭标签 = 破坏性操作），
        // 而真正想要的「外扩到 48dp」压根没发生。
        // `doOnLayout` 在**下一次布局**之后回调（已布局则立即执行）⇒ 拿到的是真实坐标。
        binding.tabClose.doOnLayout {
            val close = binding.tabClose
            val pad = (8 * close.resources.displayMetrics.density).toInt()
            val rect = Rect(
                close.left - pad, close.top - pad,
                close.right + pad, close.bottom + pad,
            )
            binding.root.setTouchDelegate(TouchDelegate(rect, close))
        }
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val tab = items[position]
        val ctx = holder.binding.root.context
        val newTabLabel = ctx.getString(io.github.tan_sno.tangsnow.R.string.tabs_new)
        holder.binding.tabTitle.text = tab.title.ifBlank { tab.url ?: newTabLabel }
        holder.binding.tabUrl.text = tab.url ?: ""
        // 无痕标签必须与普通标签肉眼可分：预览/首字母之外的显式角标
        holder.binding.tabPrivateBadge.isVisible = tab.isPrivate
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
            val fallback = ctx.getString(io.github.tan_sno.tangsnow.R.string.app_name)
            val text = tab.title.ifBlank { tab.url ?: fallback }
            // take(1) 按 UTF-16 码元截取会把 emoji/扩展汉字劈成孤立代理（渲染成替换符），
            // 必须按码点取首字符（CodePoints.takeCodePoints，2026-10-24 复核）。
            holder.binding.tabGlyph.text = CodePoints.takeCodePoints(text, 1).uppercase()
            holder.binding.tabGlyph.backgroundTintList =
                ColorStateList.valueOf(HomeTilesAdapter.colorFor(ctx, text))
        }
    }
}