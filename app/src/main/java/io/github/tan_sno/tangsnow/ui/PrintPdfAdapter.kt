package io.github.tan_sno.tangsnow.ui

import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import java.io.File
import java.io.FileInputStream

/**
 * 把**已经生成好**的 PDF 文件交给系统打印框架。
 *
 * 为什么是「先落盘、再打印」，而不是直接用 GeckoView 的 `PrintDelegate`：
 * 后者的 PDF 由内核**异步回调**产出，而本类的 [onWrite] 必须在用户点「打印」后**当即**交出数据
 * —— 两者时序对不上。因此由调用方先把 PDF 流写进缓存文件，本类只负责把它复制给系统的
 * [ParcelFileDescriptor]，时序简单，也不必阻塞主线程。
 *
 * ⚠️ 生命周期约定：[onFinish] 一定会被框架调用一次（成功、失败、取消都算），
 * 调用方应在 [onDone] 里删除临时文件，避免缓存目录残留。
 *
 * @param pdf 已写好的 PDF 文件
 * @param onDone 结束回调（用于清理临时文件）
 */
internal class PrintPdfAdapter(
    private val pdf: File,
    private val onDone: () -> Unit,
) : PrintDocumentAdapter() {

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes?,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        // PDF 已经是最终成品，框架无需再分页渲染；页数从流里无法预知，故声明为未知。
        val info = PrintDocumentInfo.Builder(pdf.name)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
            .build()
        // ⚠️ 第二个参数是「布局是否发生变化」，**只在真的变了时**才报 true。
        // 此前恒传 true：那等于每次都告诉框架「布局变了、请重新布局」，会让框架
        // 反复重排、并可能取消正在进行的写入 —— 表现为打印界面出现「已取消」而
        // 用户并没有取消。
        callback.onLayoutFinished(info, oldAttributes != newAttributes)
    }

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor?,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback,
    ) {
        // 只有框架**真的**取消了这次写入，才报「已取消」。
        if (cancellationSignal?.isCanceled == true) {
            callback.onWriteCancelled()
            return
        }
        val dest = destination
        if (dest == null) {
            // ⚠️ 框架未提供输出流属于**失败**，不是用户取消。
            // 此前与取消合并处理、报 onWriteCancelled()，会让系统打印界面显示
            // 「已取消」—— 而用户并没有取消（本次修复的正是这个误报）。
            callback.onWriteFailed("打印框架未提供输出流")
            return
        }
        try {
            FileInputStream(pdf).use { input ->
                // 用 AutoCloseOutputStream 而不是 FileOutputStream(fd)：
                // 后者会让 fd 的关闭责任变得含糊，前者与 use{} 配合能确保写完后正确释放。
                ParcelFileDescriptor.AutoCloseOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (t: Throwable) {
            // 不吞异常：把原因交给框架，系统打印界面会据此提示失败
            callback.onWriteFailed(t.message)
        }
    }

    override fun onFinish() {
        onDone()
    }
}
