package io.github.tan_sno.tangsnow.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import io.github.tan_sno.tangsnow.BrowserOpener
import io.github.tan_sno.tangsnow.LibraryActivity
import io.github.tan_sno.tangsnow.R
import io.github.tan_sno.tangsnow.util.dp
import io.github.tan_sno.tangsnow.util.selectableRipple

/**
 * 「更多」面板与快捷操作行的视图构建器（从 MainActivity 抽出）。
 *
 * 全部是无状态顶层函数：只依赖 [Context] 与点击回调，不再捕获 MainActivity。
 * [dismiss] 由调用方传入（MainActivity 传 ::hideMoreSheet），让构建器与具体 Activity 解耦。
 * 每个条目点击后都会先 [dismiss] 收起面板，再执行 [onClick]——与抽离前的行为完全一致。
 */

/** 半透明分隔线：置于每行选项上方，左右各留 16dp（不触面板边缘） */
fun addSheetDivider(context: Context, panel: LinearLayout) {
    val line = View(context).apply {
        setBackgroundColor(
            ColorUtils.setAlphaComponent(context.getColor(R.color.accent_muted), 66)
        )
    }
    panel.addView(
        line,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            context.dp(1)
        ).apply {
            setMargins(context.dp(16), 0, context.dp(16), 0)
        }
    )
}

/** 网格内部的分隔：同样用半透明细线，与行间分隔线观感一致 */
fun makeThinDivider(context: Context): View =
    View(context).apply {
        setBackgroundColor(
            ColorUtils.setAlphaComponent(context.getColor(R.color.accent_muted), 66)
        )
    }

/** 图标 + 文字水平居中的一行；默认图标 34dp、文字 15sp */
fun makeSheetRow(
    context: Context,
    dismiss: () -> Unit,
    iconRes: Int,
    label: String,
    iconSizeDp: Int = 34,
    onClick: () -> Unit,
): View {
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        selectableRipple()
        setPadding(context.dp(16), context.dp(4), context.dp(16), context.dp(4))
        setOnClickListener { dismiss(); onClick() }
    }
    val icon = ImageView(context).apply {
        setImageResource(iconRes)
        setColorFilter(context.getColor(R.color.accent))
    }
    val text = TextView(context).apply {
        text = label
        textSize = 15f
        setTextColor(context.getColor(R.color.accent_text))
    }
    row.addView(icon, LinearLayout.LayoutParams(context.dp(iconSizeDp), context.dp(iconSizeDp)).apply { marginEnd = context.dp(12) })
    row.addView(text)
    return row
}

/** 仅文字的居中行（无图标；用于「定制主页」等） */
fun makeSheetTextRow(context: Context, dismiss: () -> Unit, label: String, onClick: () -> Unit): View {
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        selectableRipple()
        setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
        setOnClickListener { dismiss(); onClick() }
    }
    val text = TextView(context).apply {
        text = label
        textSize = 15f
        setTextColor(context.getColor(R.color.accent_text))
    }
    row.addView(text)
    return row
}

/** 历史 · 书签 · 下载：一行三格 */
fun makeSheetLibraryRow(context: Context, dismiss: () -> Unit): View {
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, context.dp(2), 0, context.dp(2))
    }
    val history = makeSheetLibraryCell(context, dismiss, R.drawable.ic_history, context.getString(R.string.more_history)) {
        BrowserOpener.openLibrary(context, LibraryActivity.TAB_HISTORY)
    }
    val bookmarks = makeSheetLibraryCell(context, dismiss, R.drawable.ic_bookmark_border, context.getString(R.string.more_bookmarks)) {
        BrowserOpener.openLibrary(context, LibraryActivity.TAB_BOOKMARKS)
    }
    val downloads = makeSheetLibraryCell(context, dismiss, R.drawable.ic_download, context.getString(R.string.more_downloads)) {
        BrowserOpener.openLibrary(context, LibraryActivity.TAB_DOWNLOADS)
    }
    row.addView(history, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    row.addView(makeThinDivider(context), LinearLayout.LayoutParams(1, context.dp(34)))
    row.addView(bookmarks, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    row.addView(makeThinDivider(context), LinearLayout.LayoutParams(1, context.dp(34)))
    row.addView(downloads, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    return row
}

fun makeSheetLibraryCell(context: Context, dismiss: () -> Unit, iconRes: Int, label: String, onClick: () -> Unit): View =
    // 图标用 accent（≥3:1），文字用 accent_text（面板背景上 ≥4.5:1）—— 两者别混
    makeSheetVerticalCell(
        context, dismiss, iconRes, label,
        context.getColor(R.color.accent), context.getColor(R.color.accent_text), onClick
    )

/** 快捷操作单元格：图标 28dp + 15sp 说明（次级视觉，紧凑） */
fun makeSheetActionCell(
    context: Context,
    dismiss: () -> Unit,
    iconRes: Int,
    label: String,
    iconColor: Int,
    labelColor: Int,
    onClick: () -> Unit,
): View = makeSheetVerticalCell(context, dismiss, iconRes, label, iconColor, labelColor, onClick)

/** 纵向「图标 + 文字」单元格（历史/书签/下载/快捷操作共用） */
fun makeSheetVerticalCell(
    context: Context,
    dismiss: () -> Unit,
    iconRes: Int,
    label: String,
    iconColor: Int,
    labelColor: Int,
    onClick: () -> Unit,
): View {
    val cell = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        selectableRipple()
        setPadding(0, context.dp(2), 0, context.dp(2))
        setOnClickListener { dismiss(); onClick() }
    }
    val icon = ImageView(context).apply {
        setImageResource(iconRes)
        setColorFilter(iconColor)
    }
    val text = TextView(context).apply {
        text = label
        textSize = 15f
        maxLines = 1
        setTextColor(labelColor)
    }
    cell.addView(icon, LinearLayout.LayoutParams(context.dp(28), context.dp(28)))
    cell.addView(
        text,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dp(4) }
    )
    return cell
}
