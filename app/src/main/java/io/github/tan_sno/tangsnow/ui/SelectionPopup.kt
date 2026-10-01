package io.github.tan_sno.tangsnow.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import io.github.tan_sno.tangsnow.R
import io.github.tan_sno.tangsnow.util.dp

/**
 * 长按选中文字后弹出的操作条（复制/分享/搜索）。
 *
 * 无状态构建：[actions] 的每个动作由调用方提供，点击时先 [onBeforeAction]（收起弹窗、
 * 收起选中），再执行动作；弹窗关闭时回调 [onDismiss]（清空持有引用）。
 * 返回 [PopupWindow] 由调用方持有，便于外部 dismiss。
 *
 * 定位有两种模式，由 [bottomMarginPx] 决定：
 *  - null（默认，地址栏在顶部）：以 [anchor] 为基准向下弹出，符合“操作条跟在地址栏下方”的直觉；
 *  - 非 null（地址栏沉到底部）：**锚在屏幕底部**并上移 [bottomMarginPx]。
 *    早期只按锚点向下弹出，地址栏置底时会把操作条弹到屏幕外（用户看不到也点不到）。
 */
fun showSelectionPopup(
    context: Context,
    anchor: View,
    actions: List<Pair<String, () -> Unit>>,
    onBeforeAction: () -> Unit,
    onDismiss: () -> Unit,
    bottomMarginPx: Int? = null,
): PopupWindow {
    val content = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = AppCompatResources.getDrawable(context, R.drawable.bg_search_bar)
        clipToOutline = true
        setPadding(context.dp(4), context.dp(4), context.dp(4), context.dp(4))
        elevation = 8f
    }
    fun action(label: String, onClick: () -> Unit) {
        val tv = TextView(context).apply {
            text = label
            textSize = 14f
            setTextColor(context.getColor(R.color.accent_text))
            setPadding(context.dp(18), context.dp(10), context.dp(18), context.dp(10))
            setOnClickListener { onBeforeAction(); onClick() }
        }
        content.addView(tv)
    }
    actions.forEach { (label, onClick) -> action(label, onClick) }

    // ⚠️ `coerceAtLeast` 的**单位必须与被比较的那一侧一致**：左边是 px（widthPixels - dp(32)），
    // 所以下限也要 px。原先写裸 `280` 是"280 像素" —— 低密度屏只有约 93dp，明显偏窄。
    val width = (context.resources.displayMetrics.widthPixels - context.dp(32)).coerceAtLeast(context.dp(280))
    val pw = PopupWindow(content, width, ViewGroup.LayoutParams.WRAP_CONTENT)
    // 需要可聚焦：非聚焦弹窗不拦截外部点击，配 isOutsideTouchable=true 也无法点外关闭。
    // 聚焦后点击弹窗外区域会同时关闭弹窗并把该次点击交给下层页面（选中随之收起）。
    pw.isFocusable = true
    pw.isOutsideTouchable = true
    pw.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
    // [onDismiss] 的合约是「收口回调恰好一次」：无论走正常关闭，还是下面因锚点脱离窗口
    // 而未展示的早退分支，都要恰好回调一次 —— 否则调用方挂在 onDismiss 里的清引用/复位
    // 逻辑会被跳过（未展示≠无状态需要收口）。单次守卫防两条路径叠加成两次。
    var dismissFired = false
    fun fireDismissOnce() {
        if (dismissFired) return
        dismissFired = true
        onDismiss()
    }
    pw.setOnDismissListener { fireDismissOnce() }
    pw.elevation = 8f
    // 宿主正在结束 / 已销毁（或锚点已脱离窗口）时不展示：PopupWindow.show* 与对话框一样需要有效的
    // 窗口令牌，拿不到就是 BadTokenException。判 `isAttachedToWindow` 是这里最省的等价判据
    //（锚点在、令牌就在），不必把 Activity 传进来。未展示也必须走一次收口回调（见上）。
    if (!anchor.isAttachedToWindow) {
        // 未展示也必须走一次收口回调（合约见上），但**必须延后**（2026-10-01 外部审查）：
        // 同步回调会跑在调用方"把返回值存进字段"**之前**，于是那个字段最终指向一个从未展示、
        // 且已收口的实例（倒挂）。post 一拍让调用方先完成赋值，再由回调把它清掉。
        anchor.post { fireDismissOnce() }
        return pw
    }
    if (bottomMarginPx != null) {
        // 地址栏在底部：锚在整窗底边并上移“底栏+地址栏”的高度，使操作条压在地址栏之上
        pw.showAtLocation(anchor.rootView, Gravity.BOTTOM, 0, bottomMarginPx)
    } else {
        pw.showAsDropDown(anchor, 0, 6)
    }
    return pw
}
