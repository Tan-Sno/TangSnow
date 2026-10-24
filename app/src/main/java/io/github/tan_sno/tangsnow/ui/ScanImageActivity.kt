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
            val outcome = withContext(Dispatchers.IO) { decode(uri) }
            if (isFinishing || isDestroyed) return@launch
            // 如实归因：内存不足 ≠ 图读不出来 ≠ 图里没有码 —— 三者给三种提示。
            // 归因随**本次调用**的结果对象走（不再用共享 @Volatile 标志跨线程传，
            // 连续两次识别并发时不会互相冲掉归因，2026-10-24 复核）。
            when (outcome) {
                is DecodeOutcome.Ok -> returnResult(outcome.text)
                DecodeOutcome.OutOfMemory -> toast(R.string.scan_image_too_large)
                DecodeOutcome.Unreadable -> toast(R.string.scan_image_unreadable)
                null -> toast(R.string.scan_no_qr_found)
            }
        }
    }

    /**
     * [decode] 的结果：把旧实现里两个 `@Volatile` 归因标志与二义的 `null` 收进类型 ——
     * 标志是**单份**实例字段且在 `decode` 开头复位，两次相册识别并发时（第一次仍在解码、
     * 用户又选了第二张），后一次的复位会把前一次的归因冲掉，提示语张冠李戴。
     * `null` = 载入成功但图里没有码（正常否定，不是错误）。
     */
    private sealed interface DecodeOutcome {
        data class Ok(val text: String) : DecodeOutcome
        /** 图片载入阶段内存不足 ⇒ 提示「图过大」（2026-10-01 CR-014） */
        data object OutOfMemory : DecodeOutcome
        /** 流打不开（文件已删）/ 损坏 / 格式不受支持 ⇒ 提示「读不出图」（外部审查 P4-17） */
        data object Unreadable : DecodeOutcome
    }

    private fun decode(uri: android.net.Uri): DecodeOutcome? {
        // 第一步：把**图片载入**与**码点解码**分开——载入失败（流打不开 / 损坏 / 格式不支持）
        // 属「读不出图」；载入成功但 zxing 不认属「图里没有码」。此前混在一个宽 catch 里，
        // `openInputStream` 抛的 FileNotFound 等会被误报成「图里没有码」（外部审查 P4）。
        val bmp = try {
            io.github.tan_sno.tangsnow.util.Bitmaps.decodeSampled(contentResolver, uri, 1600, 1600)
        } catch (_: OutOfMemoryError) {
            return DecodeOutcome.OutOfMemory
        } catch (_: Throwable) {
            // 流打不开（云端文档未落地 / 文件已被删）/ 解码器拒绝
            return DecodeOutcome.Unreadable
        }
        if (bmp == null) {
            // 读不出图（损坏 / 格式不受支持）：与「读出来了但没码」分开归因
            return DecodeOutcome.Unreadable
        }
        // CR-010：位图用完必须回收 —— 1600×1600 的 ARGB_8888 约 10 MB，此前靠 GC 才释放，
        // 内存峰值被无谓抬高。`try/finally` 保证任何早退路径（宽高非法、解码失败）都回收到。
        // 第二步：码点解码。OOM 单独归因；其余异常=「图里没有码」（正常否定，非 Unreadable）。
        val text: String? = try {
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
        } catch (_: OutOfMemoryError) {
            return DecodeOutcome.OutOfMemory
        } catch (_: Throwable) {
            // 载入成功但认不出码（含 zxing 的 NotFoundException 等）=「图里没有码」，
            // **不**归为 Unreadable——那会把正常否定误报成「图片损坏」
            null
        } finally {
            // CR-010：位图用完立刻回收（1600² ARGB_8888 ≈ 10 MB），不靠 GC；
            // try/finally 保证任何早退路径（宽高非法、解码抛异常）都会回收。
            runCatching { bmp.recycle() }
        }
        return text?.let { DecodeOutcome.Ok(it) }
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