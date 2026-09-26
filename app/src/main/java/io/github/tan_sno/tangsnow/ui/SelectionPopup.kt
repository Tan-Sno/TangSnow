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

    val width = (context.resources.displayMetrics.widthPixels - context.dp(32)).coerceAtLeast(280)
    val pw = PopupWindow(content, width, ViewGroup.LayoutParams.WRAP_CONTENT)
    // 需要可聚焦：非聚焦弹窗不拦截外部点击，配 isOutsideTouchable=true 也无法点外关闭。
    // 聚焦后点击弹窗外区域会同时关闭弹窗并把该次点击交给下层页面（选中随之收起）。
    pw.isFocusable = true
    pw.isOutsideTouchable = true
    pw.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
    pw.setOnDismissListener { onDismiss() }
    pw.elevation = 8f
    if (bottomMarginPx != null) {
        // 地址栏在底部：锚在整窗底边并上移“底栏+地址栏”的高度，使操作条压在地址栏之上
        pw.showAtLocation(anchor.rootView, Gravity.BOTTOM, 0, bottomMarginPx)
    } else {
        pw.showAsDropDown(anchor, 0, 6)
    }
    return pw
}
