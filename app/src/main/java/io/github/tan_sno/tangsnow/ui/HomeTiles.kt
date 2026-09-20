package io.github.tan_sno.tangsnow.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import io.github.tan_sno.tangsnow.R
import io.github.tan_sno.tangsnow.data.HomeShortcut
import io.github.tan_sno.tangsnow.databinding.ItemHomeTileBinding

/** 首页快捷方式网格适配器 */
class HomeTilesAdapter(
    private val onOpen: (HomeShortcut) -> Unit,
) : RecyclerView.Adapter<HomeTilesAdapter.VH>() {

    companion object {
        /**
         * 快捷方式头像底色（冷色系，与蓝白基调协调）。
         *
         * 这里只存**资源 id**，色值统一在 `colors.xml` 定义 —— 配色要保持单一事实来源，
         * 否则改一次主题色又要在 Kotlin 里再找一遍硬编码色值。
         */
        private val PALETTE_RES = intArrayOf(
            R.color.tile_1, R.color.tile_2, R.color.tile_3, R.color.tile_4,
            R.color.tile_5, R.color.tile_6, R.color.tile_7, R.color.tile_8,
        )

        /** Math.abs(Int.MIN_VALUE) 仍为负，会越界；改用掩码取非负值 */
        fun colorFor(context: Context, name: String): Int = ContextCompat.getColor(
            context,
            PALETTE_RES[(name.hashCode() and Int.MAX_VALUE) % PALETTE_RES.size],
        )
    }

    private val items = mutableListOf<HomeShortcut>()

    /**
     * 标签是否压在**自定义图片**背景上（由首页按当前风格设置）。
     *
     * 为 true 时给标签加一层跟随主题的半透明底色：自定义背景是用户任意一张照片，
     * 对比度不可预知，而标签文字随明暗主题变化，裸文字压上去可能读不清。
     * 其余风格的背景是应用自己的版面，标签本就清晰，故不加底色、保持原来的轻盈观感。
     */
    private var labelOnImage = false

    /** 供首页在切换风格时调用；值没变就不重绑（该方法每次 onResume 都会被调到）。 */
    fun setLabelOnImage(onImage: Boolean) {
        if (labelOnImage == onImage) return
        labelOnImage = onImage
        notifyItemRangeChanged(0, items.size)
    }

    fun submit(list: List<HomeShortcut>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val binding: ItemHomeTileBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemHomeTileBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.binding.tileAvatar.text = item.name.take(1).uppercase()
        holder.binding.tileAvatar.backgroundTintList =
            ColorStateList.valueOf(colorFor(holder.itemView.context, item.name))
        holder.binding.tileLabel.text = item.name
        // 压在自定义图片上时给标签加底色；其余风格清掉，避免回收复用后残留
        holder.binding.tileLabel.setBackgroundResource(
            if (labelOnImage) R.drawable.bg_tile_label else 0
        )
        holder.binding.root.setOnClickListener { onOpen(item) }
    }
}
