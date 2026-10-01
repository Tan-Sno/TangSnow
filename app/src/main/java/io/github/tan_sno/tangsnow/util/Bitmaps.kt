package io.github.tan_sno.tangsnow.util

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

/**
 * 位图统一工具（采样解码 / center-crop）：
 * 首页背景与扫码取景页共用同一实现，避免重复与口径漂移。
 */
object Bitmaps {

    /**
     * 单张位图像素上限（约 4MP，ARGB_8888 下 ≈16MB）。
     * 采样只保证"不小于目标尺寸"，不约束绝对像素数：一张 12000×8000 的相册图
     * 在"目标 1080×2400"下仍会解到 6000×4000（≈96MB）而 OOM。故此处再加一道
     * 内存闸门——两个约束冲突时**优先保内存**（宁可略降清晰度，也不让进程崩掉）。
     */
    private const val MAX_PIXELS = 4_000_000L

    /**
     * 按目标尺寸采样解码（两个方向都不小于需求值时降采样），
     * 避免把 12MP+ 相册图整张读入内存造成 OOM。返回 null 表示失败。
     */
    fun decodeSampled(
        resolver: ContentResolver,
        uri: Uri,
        reqW: Int,
        reqH: Int,
    ): Bitmap? {
        val probe = resolver.openInputStream(uri) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        probe.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = requiredSampleSize(bounds.outWidth, bounds.outHeight, reqW, reqH)
        val full = resolver.openInputStream(uri) ?: return null
        return full.use { ins ->
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                // 明确不做密度缩放：流没有 density 信息，显式关掉避免不同 ROM 上的尺寸漂移
                inScaled = false
            }
            BitmapFactory.decodeStream(ins, null, opts)
        }
    }

    /**
     * 计算 `inSampleSize`：既满足「不小于目标尺寸」，又满足「总像素不超过 [maxPixels]」。
     *
     * 两条约束冲突时**优先保内存**（宁可略降清晰度，也不让进程 OOM）——
     * 这就是 [MAX_PIXELS] 这道闸门的用意：单纯按目标尺寸采样时，
     * 一张 12000×8000 的相册图在「目标 1080×2400」下仍会解到 6000×4000（≈96MB）。
     *
     * 纯整数运算，无框架依赖，故单独抽出并由单元测试覆盖（边界与收敛性）。
     */
    internal fun requiredSampleSize(
        outWidth: Int,
        outHeight: Int,
        reqW: Int,
        reqH: Int,
        maxPixels: Long = MAX_PIXELS,
    ): Int {
        if (outWidth <= 0 || outHeight <= 0) return 1
        // CR-014：负数/零预算下 `maxPixels` 会让下面的循环除零或永不下降 —— 生产调用点传的是
        // 常量 [MAX_PIXELS]，不可达；但这是纯函数、可被测试直接调用，加一行守卫成本为零。
        if (maxPixels <= 0) return 1
        // 下界守卫：reqW / reqH 非正时「不小于目标尺寸」恒真，采样会一路翻倍到 Int 溢出，
        // 下一轮 `sample * 2 == 0` 触发除零（ArithmeticException）。当前三个调用点都传正值，
        // 这里防的是将来新增调用点时踩到。
        if (reqW <= 0 || reqH <= 0) return 1
        var sample = 1
        while (outWidth / (sample * 2) >= reqW && outHeight / (sample * 2) >= reqH) {
            sample *= 2
        }
        // 内存闸门：整数除法下 sample 超过边长时结果为 0，循环必然收敛
        while ((outWidth / sample).toLong() * (outHeight / sample) > maxPixels) {
            sample *= 2
        }
        return sample
    }

    /**
     * 把源图按 center-crop 缩放到目标尺寸，保证无拉伸失真。
     *
     * ⚠️ 顺序是**先裁后缩**，而不是「先缩后裁」：`createScaledBitmap` 为了填满目标，会把
     * 长边按 `max(targetW/w, targetH/h)` 放大 —— 一张 8000×500 的宽幅图配 1080×2400 的目标
     * 会先被放大到 9600×2400（≈23MP / 92MB），极端长宽比下可达数亿像素。而 [MAX_PIXELS]
     * 只约束 [decodeSampled] 那一步，**管不到这里**，于是内存闸门被绕开。
     * 先按目标宽高比在源图上裁出居中的一小块、再缩放到目标：中间图不会超过目标尺寸量级。
     */
    fun cover(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        if (targetW <= 0 || targetH <= 0) return src
        val rect = centerCropRect(src.width, src.height, targetW, targetH)
        val cropped = runCatching {
            Bitmap.createBitmap(src, rect[0], rect[1], rect[2], rect[3])
        }.getOrDefault(src)
        // 裁剪后的宽高比已与目标一致（取整误差 ≤1px），直接缩放到目标即可，无需二次裁剪
        val scaled = runCatching {
            Bitmap.createScaledBitmap(cropped, targetW, targetH, true)
        }.getOrDefault(cropped)
        // 中间图不再需要；但绝不动调用方传进来的 src（裁剪失败时 cropped 就是 src 本身）
        if (cropped !== src && scaled !== cropped) runCatching { cropped.recycle() }
        return scaled
    }

    /**
     * 目标宽高比下、在源图里居中的最大内接矩形（`[x, y, w, h]`）。
     *
     * 抽成纯函数：这是「无拉伸」的唯一依据，边界（极端长宽比、1px 源图、宽高比恰好相等）
     * 必须能被单测钉住。比较一律用整数乘法，避免浮点误差把边界判反。
     */
    internal fun centerCropRect(srcW: Int, srcH: Int, targetW: Int, targetH: Int): IntArray {
        if (srcW <= 0 || srcH <= 0 || targetW <= 0 || targetH <= 0) {
            return intArrayOf(0, 0, srcW.coerceAtLeast(1), srcH.coerceAtLeast(1))
        }
        // 目标更「宽」（含相等）⇒ 源相对更高 ⇒ 裁掉上下；否则裁掉左右
        return if (targetW.toLong() * srcH >= targetH.toLong() * srcW) {
            val h = (srcW.toLong() * targetH / targetW).toInt().coerceIn(1, srcH)
            intArrayOf(0, (srcH - h) / 2, srcW, h)
        } else {
            val w = (srcH.toLong() * targetW / targetH).toInt().coerceIn(1, srcW)
            intArrayOf((srcW - w) / 2, 0, w, srcH)
        }
    }
}