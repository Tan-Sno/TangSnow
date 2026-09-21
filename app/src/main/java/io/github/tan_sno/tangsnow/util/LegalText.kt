package io.github.tan_sno.tangsnow.util

import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.StyleSpan
import androidx.annotation.StringRes

/**
 * 法律文本的**展示处理**：把 `**…**` 转成加粗。
 *
 * ## 为什么需要它
 *
 * `strings.xml` 里的隐私政策用 `**…**` 标注重点（例如「**不会**在后台自动发起」这种关键限定），
 * 导出到 `docs/PRIVACY.md` 时由 Markdown 渲染成加粗。但 Android 的 `TextView` 与
 * `AlertDialog` **都不做 Markdown** —— 原串直接喂进去，用户在应用里看到的是裸露的星号，
 * 而星号恰好会盖住它本想强调的那句话。
 *
 * 于是在**展示层**统一处理：正文照旧保留 `**`（对外 .md 仍要加粗），只在这里把成对的标记
 * 换成 `StyleSpan`。三个渲染点（阅读页、同意页兜底弹窗、关于页兜底弹窗）共用本对象，
 * 避免只修一处、其余两处照旧露星号。
 *
 * ## 结构：解析与渲染分开
 *
 * [parse] 是**纯函数**（不碰任何 Android 类型），因此能被 JVM 单测覆盖 —— 边界都在解析里
 * （落单的标记、空标记、无标记）；[emphasize] 只负责把结果套上 Span，薄到不需要测。
 *
 * ## 处理规则
 *
 * 只认**成对**的标记：先找到 `**`，再找它之后的 `**`，中间的正文加粗、标记本身丢弃。
 * 落单的 `**`（后面找不到配对）一律**原样保留**，绝不吞掉正文里可能出现的星号。
 */
object LegalText {

    private const val MARKER = "**"

    /** 解析结果：[plain] 为去掉标记后的正文，[boldRanges] 为其中应加粗的区间（闭区间，`first..last`） */
    internal data class Marked(val plain: String, val boldRanges: List<IntRange>)

    /** 取字符串资源并处理成可展示的 CharSequence */
    fun emphasize(context: Context, @StringRes resId: Int): CharSequence =
        emphasize(context.getString(resId))

    /** 把成对的 `**…**` 转成加粗；没有任何成对标记时原样返回 */
    fun emphasize(raw: String): CharSequence {
        val marked = parse(raw)
        if (marked.boldRanges.isEmpty()) return raw
        val out = SpannableString(marked.plain)
        for (range in marked.boldRanges) {
            out.setSpan(
                StyleSpan(Typeface.BOLD),
                range.first,
                range.last + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return out
    }

    /** 纯解析：拆出正文与加粗区间（可单测） */
    internal fun parse(raw: String): Marked {
        val plain = StringBuilder(raw.length)
        val boldRanges = ArrayList<IntRange>()
        var cursor = 0
        while (cursor < raw.length) {
            val open = raw.indexOf(MARKER, cursor)
            if (open < 0) break
            val close = raw.indexOf(MARKER, open + MARKER.length)
            if (close < 0) break            // 落单：连同后面的正文一起按原样收尾
            plain.append(raw, cursor, open)
            val start = plain.length
            plain.append(raw, open + MARKER.length, close)
            if (plain.length > start) boldRanges += start..plain.length - 1
            cursor = close + MARKER.length
        }
        if (cursor < raw.length) plain.append(raw, cursor, raw.length)
        return Marked(plain.toString(), boldRanges)
    }
}
