package io.github.tan_sno.tangsnow.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.CaptureActivity
import io.github.tan_sno.tangsnow.R
import io.github.tan_sno.tangsnow.toast
import io.github.tan_sno.tangsnow.util.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 相机扫码页：右下角加一个「从相册选图识别」按钮。
 * - 相机扫码成功由父类 CaptureManager 处理并返回原结果；
 * - 点按钮走系统图片选择器，识别成功后以与相机一致的 extra 返回给调用方
 *   （键名来自 zxing-android-embedded 4.3.0 CaptureManager 反查，勿猜改）。
 */
class ScanImageActivity : CaptureActivity() {

    /**
     * 本页需要的作用域。
     *
     * 刻意不用 `lifecycleScope`：`CaptureActivity` 直接继承自 `android.app.Activity`，
     * **不是** LifecycleOwner，拿不到 `lifecycleScope`。这里自建一个绑定本页生命周期的作用域，
     * 在 [onDestroy] 里取消，效果等价（页面销毁后后台解码随之取消）。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addGalleryButton()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun addGalleryButton() {
        val size = dp(56)
        val margin = dp(18)
        val btn = AppCompatImageView(this).apply {
            setImageResource(R.drawable.ic_image)
            // 按钮压在相机取景画面上（bg_gallery_button 是固定半透明黑），故图标固定用白
            setColorFilter(ContextCompat.getColor(context, android.R.color.white))
            setBackgroundResource(R.drawable.bg_gallery_button)
            contentDescription = getString(R.string.more_scan_image)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(size / 4, size / 4, size / 4, size / 4)
            setOnClickListener { pickFromGallery() }
        }
        val decor = window.decorView as? FrameLayout ?: return
        val lp = FrameLayout.LayoutParams(size, size)
        lp.gravity = Gravity.END or Gravity.BOTTOM
        lp.setMargins(0, 0, margin, margin)
        decor.addView(btn, lp)
    }

    private fun pickFromGallery() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(intent, REQ_PICK)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_PICK) {
            // 用 ?: 收口而非 !!：`data.data!!` 依赖上一行的非空守卫，守卫一旦被改动即成崩溃点
            val uri = data?.data?.takeIf { resultCode == RESULT_OK }
            if (uri != null) decodeAndFinish(uri)
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    /** 后台解码（含采样防 OOM），成功以相机同款 extra 结束本页 */
    private fun decodeAndFinish(uri: android.net.Uri) {
        // 用协程而非裸 Thread：随本页销毁自动取消（见 scope），不再需要 runOnUiThread 手工回主线程
        scope.launch {
            val text = withContext(Dispatchers.IO) { decode(uri) }
            if (isFinishing || isDestroyed) return@launch
            if (text == null) {
                // 如实归因：内存不足 ≠ 图读不出来 ≠ 图里没有码 —— 三者给三种提示
                toast(
                    when {
                        lastDecodeOutOfMemory -> R.string.scan_image_too_large
                        lastDecodeUnreadable -> R.string.scan_image_unreadable
                        else -> R.string.scan_no_qr_found
                    }
                )
            } else {
                returnResult(text)
            }
        }
    }

    /**
     * 上一次 [decode] 是否因**内存不足**失败。
     * 用途：把「图像过大导致内存不足」与「图里没有二维码」分开提示（2026-10-01 CR-014）——
     * 此前 `catch (_: Throwable)` 把 `OutOfMemoryError` 也吞成「未找到二维码」，
     * 用户会以为是图里没有码而反复换图重试。**仅在本页内使用**（IO 线程写、主线程读 ⇒ @Volatile）。
     */
    @Volatile
    private var lastDecodeOutOfMemory = false

    /**
     * 上一次 [decode] 是否**读不出图**（文件损坏 / 格式不受支持）。
     * 与 [lastDecodeOutOfMemory] 同型：`decode` 的 `null` 是二义的（读不出图 vs 读出来了但没码），
     * 不分清楚就会把「图片损坏」报成「图里没有码」，用户反复换图重试（2026-10-02 外部审查 P4-17）。
     */
    @Volatile
    private var lastDecodeUnreadable = false

    private fun decode(uri: android.net.Uri): String? {
        lastDecodeOutOfMemory = false
        lastDecodeUnreadable = false
        return try {
            val bmp = io.github.tan_sno.tangsnow.util.Bitmaps.decodeSampled(contentResolver, uri, 1600, 1600)
            if (bmp == null) {
                // 读不出图（损坏 / 格式不受支持）：与「读出来了但没码」分开归因
                lastDecodeUnreadable = true
                null
            } else {
                // CR-010：位图用完必须回收 —— 1600×1600 的 ARGB_8888 约 10 MB，此前靠 GC 才释放，
                // 内存峰值被无谓抬高。`try/finally` 保证任何早退路径（宽高非法、解码失败）都回收到。
                try {
                    val w = bmp.width
                    val h = bmp.height
                    if (w <= 0 || h <= 0) {
                        null
                    } else {
                        val pixels = IntArray(w * h)
                        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
                        val reader = com.google.zxing.MultiFormatReader()
                        reader.setHints(
                            mapOf(
                                com.google.zxing.DecodeHintType.POSSIBLE_FORMATS to
                                    listOf(com.google.zxing.BarcodeFormat.QR_CODE),
                                com.google.zxing.DecodeHintType.TRY_HARDER to true,
                            )
                        )
                        val source = com.google.zxing.RGBLuminanceSource(w, h, pixels)
                        reader.decodeWithState(
                            com.google.zxing.BinaryBitmap(
                                com.google.zxing.common.HybridBinarizer(source)
                            )
                        ).text
                    }
                } finally {
                    // CR-010：位图用完立刻回收（1600² ARGB_8888 ≈ 10 MB），不靠 GC；
                    // try/finally 保证任何早退路径（宽高非法、解码抛异常）都会回收。
                    runCatching { bmp.recycle() }
                }
            }
        } catch (_: OutOfMemoryError) {
            // 与"没找到二维码"分开（见 [lastDecodeOutOfMemory] 的说明）
            lastDecodeOutOfMemory = true
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun returnResult(text: String) {
        // 键名与 CaptureManager 一致：SCAN_RESULT / SCAN_RESULT_FORMAT / SCAN_RESULT_BYTES
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra("SCAN_RESULT", text)
                putExtra("SCAN_RESULT_FORMAT", "QR_CODE")
                putExtra("SCAN_RESULT_BYTES", text.toByteArray())
            }
        )
        finish()
    }

    private companion object {
        const val REQ_PICK = 4001
    }
}