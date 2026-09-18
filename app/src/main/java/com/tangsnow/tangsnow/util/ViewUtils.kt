package com.tangsnow.tangsnow.util

import android.content.Context
import android.util.TypedValue
import android.view.View

/** 把 dp 值换算为像素（按当前屏幕密度）。供各 Activity / 控制器统一复用，避免各自重写。 */
fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

/** 给 View 设置波纹点击反馈背景（selectableItemBackground）。 */
fun View.selectableRipple() {
    val tv = TypedValue()
    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
    if (tv.resourceId != 0) setBackgroundResource(tv.resourceId)
}
