package io.github.tan_sno.tangsnow.util

import androidx.recyclerview.widget.RecyclerView

/**
 * 给列表做「首帧后预热」：把前 [warmPositions] 行预建、预绑一次并放进回收池。
 *
 * 背景：RecyclerView 的行只在真正可见时才 `inflate` + `bind`。第一屏之后的行，
 * 其**类加载、矢量 drawable 解析、ColorStateList 取值、自定义控件构造**的首次成本
 * 会全部落进用户第一次滑动的手势里 —— 表现为「只有第一次滑动卡，之后顺滑」。
 * 本应用无 baseline profile，首次交互成本全部由运行时承担。
 *
 * 用法：在 [RecyclerView.post] 里调用（即首帧之后）——既不推迟首帧，
 * 又赶在用户能滑动手之前把成本付掉。纯优化：整段 `runCatching`，异常不影响功能。
 *
 * ⚠️ 只适用于**行内无图片**的列表。行内含异步加载图片的列表不要预热：
 * 预绑会触发用户根本看不到的图片加载，白费流量与内存。
 *
 * 实现只用 `RecyclerView.Adapter` / `RecycledViewPool` 的公开 API。
 * 注意 `androidx.preference.PreferenceGroupAdapter` 是 `@RestrictTo`（库内私有），
 * 应用代码不可引用 —— `lintDebug` 的 `RestrictedApi` 会拦。
 */
fun RecyclerView.warmUpFirstRows(
    warmPositions: Int = 14,
    poolCapacityPerType: Int = 8,
) {
    val adapter = adapter ?: return
    val limit = minOf(adapter.itemCount, warmPositions)
    if (limit <= 0) return
    runCatching {
        val pool = recycledViewPool
        val types = HashSet<Int>()
        for (pos in 0 until limit) types += adapter.getItemViewType(pos)
        // 抬高每类回收上限，让下面预建的行真的留在池里备用（默认上限 5）
        types.forEach { pool.setMaxRecycledViews(it, poolCapacityPerType) }
        for (pos in 0 until limit) {
            val vh = adapter.createViewHolder(this, adapter.getItemViewType(pos))
            adapter.bindViewHolder(vh, pos)
            pool.putRecycledView(vh)
        }
    }
}
