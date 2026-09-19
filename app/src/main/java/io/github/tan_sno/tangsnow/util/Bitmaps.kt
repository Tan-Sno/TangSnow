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

    /** 把源图按 center-crop 缩放到目标尺寸，保证无拉伸失真 */
    fun cover(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        if (targetW <= 0 || targetH <= 0) return src
        val w = src.width.toFloat()
        val h = src.height.toFloat()
        val scale = maxOf(targetW / w, targetH / h)
        val scaledW = (w * scale).toInt().coerceAtLeast(1)
        val scaledH = (h * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true)
        val x = ((scaledW - targetW) / 2).coerceAtLeast(0)
        val y = ((scaledH - targetH) / 2).coerceAtLeast(0)
        val safeW = scaledW.coerceAtMost(targetW)
        val safeH = scaledH.coerceAtMost(targetH)
        return runCatching {
            Bitmap.createBitmap(scaled, x, y, safeW, safeH)
        }.getOrDefault(scaled)
    }
}