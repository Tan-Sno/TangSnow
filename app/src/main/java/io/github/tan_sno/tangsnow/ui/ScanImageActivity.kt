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
                toast(R.string.scan_no_qr_found)
            } else {
                returnResult(text)
            }
        }
    }

    private fun decode(uri: android.net.Uri): String? {
        return try {
            val bmp = io.github.tan_sno.tangsnow.util.Bitmaps.decodeSampled(contentResolver, uri, 1600, 1600)
            if (bmp == null) {
                null
            } else {
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
            }
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