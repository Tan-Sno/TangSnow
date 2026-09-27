package io.github.tan_sno.tangsnow.ui

import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream

/**
 * 把**已经生成好**的 PDF 文件交给系统打印框架。
 *
 * 为什么是「先落盘、再打印」，而不是直接用 GeckoView 的 `PrintDelegate`：
 * 后者的 PDF 由内核**异步回调**产出，而本类的 [onWrite] 必须在用户点「打印」后**当即**交出数据
 * —— 两者时序对不上。因此由调用方先把 PDF 流写进缓存文件，本类只负责把它复制给系统的
 * [ParcelFileDescriptor]，时序简单。
 * ⚠️ [onWrite] 本身是**主线程**回调（见其实现处的 AOSP 依据），故复制动作放在自己的 IO 域里做
 * —— 「不必阻塞主线程」这句话原先只是意图，现在才是事实。
 *
 * ⚠️ 生命周期约定：[onFinish] 一定会被框架调用一次（成功、失败、取消都算），
 * 调用方应在 [onDone] 里删除临时文件，避免缓存目录残留。
 *
 * @param pdf 已写好的 PDF 文件
 * @param noStreamMessage 「框架未提供输出流」时回给框架的失败原因
 *        （**由调用方传入已本地化的串**，故本类不需要持有 Context —— 避免为一条文案引入
 *        Activity 引用。⚠️ 该文案最终由**系统打印界面**展示，它按**系统**语言渲染，
 *        不是应用语言；这是平台接口的限制，本应用只能保证不再写死中文）
 * @param onDone 结束回调（用于清理临时文件）
 */
internal class PrintPdfAdapter(
    private val pdf: File,
    private val noStreamMessage: String,
    private val onDone: () -> Unit,
) : PrintDocumentAdapter() {

    /**
     * 复制用的后台协程域。
     *
     * 刻意**不在 [onFinish] 里取消**：`onFinish` 可能在复制仍在进行时到达，取消会把文件截断、
     * 交给框架一份残缺的 PDF。本协程只做「把已经落盘的 PDF 复制给框架」这一件事，自然结束；
     * 它不持有 Context / Activity，也不构成泄漏。
     */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
            callback.onWriteFailed(noStreamMessage)
            return
        }
        // ⚠️ `onWrite` 是**主线程**回调 —— 平台约定，已用 AOSP 源码坐实：
        // `PrintManager.PrintDocumentAdapterDelegate.write()`（是个 Binder Stub，从 Binder 线程进来）
        // 只是把 `MSG_ON_WRITE` 投给 `mHandler = new MyHandler(mActivity.getMainLooper())`。
        // 而这里要整份复制 PDF（数 MB 级），放主线程就是一次可感知的卡顿乃至 ANR。
        // 平台自带的同款实现（`PrintFileDocumentAdapter.onWrite`）也是把复制丢给 AsyncTask 的 ——
        // 本类照同一范式：复制放 IO，结果从后台线程回调（`WriteResultCallback` 是 Binder 回调，线程无关）。
        ioScope.launch {
            try {
                FileInputStream(pdf).use { input ->
                    // 用 AutoCloseOutputStream 而不是 FileOutputStream(fd)：
                    // 后者会让 fd 的关闭责任变得含糊，前者与 use{} 配合能确保写完后正确释放。
                    ParcelFileDescriptor.AutoCloseOutputStream(dest).use { output ->
                        // 分块复制，好让取消信号**在途中**也认（框架自带的那个把 cancellationSignal
                        // 直接交给 FileUtils.copy，同样不是只在开头查一次）
                        val buf = ByteArray(COPY_BUFFER_BYTES)
                        while (true) {
                            if (cancellationSignal?.isCanceled == true) {
                                callback.onWriteCancelled()
                                return@launch
                            }
                            val read = input.read(buf)
                            if (read <= 0) break
                            output.write(buf, 0, read)
                        }
                    }
                }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 本域目前不会被取消（见 ioScope 的说明），但按本仓既定口径：**取消要原样抛出**，
                // 不能被下面那个宽 catch 顺手吞掉。而框架又要求 onWriteFinished / onWriteFailed /
                // onWriteCancelled **恰好回调一次**，否则打印任务会一直等 —— onWriteCancelled
                // 正是为此留的出口。先应答再抛（与 ExtInstallCoordinator「settle() 后 rethrow」同一写法）。
                callback.onWriteCancelled()
                throw e
            } catch (t: Throwable) {
                // 不吞异常：把原因交给框架，系统打印界面会据此提示失败
                callback.onWriteFailed(t.message)
            }
        }
    }

    override fun onFinish() {
        onDone()
    }

    private companion object {
        /** 复制缓冲区（64 KB）：与 ExtInstallCoordinator 的下载缓冲同量级，够大以摊薄系统调用 */
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
