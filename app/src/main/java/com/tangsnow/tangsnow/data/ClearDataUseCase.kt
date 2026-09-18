package com.tangsnow.tangsnow.data

import android.content.Context
import com.tangsnow.tangsnow.GeckoHolder
import com.tangsnow.tangsnow.data.repo.DownloadRepo
import com.tangsnow.tangsnow.data.repo.HistoryRepo
import com.tangsnow.tangsnow.util.awaitResult
import org.mozilla.geckoview.StorageController

/**
 * 「清除浏览数据」用例。
 *
 * 把设置页的勾选项翻译成具体动作，并**等待内核真正完成**后再返回——修复早期
 * "刚发起就报成功"的假反馈。数据分两类：
 *  - 内核数据（Cookie / 站点数据 / 缓存）→ [StorageController.clearData]，挂起等待；
 *  - 应用自管数据（历史 / 会话快照 / 下载记录）→ 直接操作本地存储。
 *
 * 书签**永不**纳入清除（与主流浏览器一致，避免一键误删用户收藏）。
 */
object ClearDataUseCase {

    data class Options(
        val cookiesAndSiteData: Boolean = true,
        val cache: Boolean = true,
        val history: Boolean = false,
        val sessionSnapshot: Boolean = false,
        val downloadRecords: Boolean = false,
    )

    /**
     * 为什么不能只返回一个 Boolean：本地数据的清除是**逐项独立**的（历史 / 会话快照 / 下载记录），
     * 任一项失败若被并进「成功」里，用户就会看到「已清除」而数据其实还在。
     * 所以把「内核结果」与「本地失败项数」分开回报，让界面能如实提示「部分未清除」。
     */
    suspend fun clear(context: Context, options: Options): Result {
        var kernelOk = true

        val runtime = GeckoHolder.runtime
        val flags = mutableListOf<Long>()
        if (options.cookiesAndSiteData) {
            // 已核对内核常量位值（javap -v 看 ConstantValue）：
            //   COOKIES=1、DOM_STORAGES=16、AUTH_SESSIONS=32、PERMISSIONS=64、
            //   SITE_DATA=471（= 1|2|4|16|64|128|256，**已含 PERMISSIONS 与 DOM 存储**）。
            // 因此 SITE_DATA 覆盖了站点权限；**唯 AUTH_SESSIONS(32) 不在任何已选项内**
            // —— 即 HTTP Basic/Digest 认证的登录态会在「清除浏览数据」后残留。
            // 用户勾选「Cookie 与站点数据」时的预期是「登出这些站点」，故一并清除。
            flags += StorageController.ClearFlags.COOKIES
            flags += StorageController.ClearFlags.SITE_DATA
            flags += StorageController.ClearFlags.AUTH_SESSIONS
        }
        if (options.cache) {
            flags += StorageController.ClearFlags.ALL_CACHES
        }
        if (runtime != null && flags.isNotEmpty()) {
            val mask = flags.reduce(Long::or)
            // awaitResult 正常返回即成功（GeckoResult<Void> 成功值为 null）；抛异常即失败
            kernelOk = runCatching {
                runtime.storageController.clearData(mask).awaitResult()
                true
            }.getOrDefault(false)
        }

        // 应用自管数据（独立于内核 storageController）：逐项清除并统计失败项，不静默吞掉。
        // 这里刻意用 try/catch 而非 runCatching：**runCatching 会把 CancellationException
        // 一并吞掉**，让被取消的协程继续往下跑，破坏取消语义；而本用例确实是 suspend 上下文，
        // 必须让取消原样传播（取消后接着去清下一项是明确错误的）。
        // 顺带纠正一处早期注释的误述：runCatching 的内联位置**可以**包 suspend 调用
        // （同仓库 `SessionManager.onVisited` 的 `launch { runCatching { ... } }` 即如此），
        // 不用它的真正理由是上面这条取消语义。
        var localFailed = 0
        suspend fun locally(block: suspend () -> Unit) {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                localFailed++
                android.util.Log.w("ClearDataUseCase", "local clear failed", e)
            }
        }

        if (options.history) locally { HistoryRepo.clear() }
        if (options.sessionSnapshot) locally {
            SessionStore.clear(context)
            // 置位后，下一次 saveState 会跳过——否则"清掉快照"紧接着 onPause 又写回
            SessionStore.markPurged()
        }
        if (options.downloadRecords) locally { DownloadRepo.clearRecords(context) }

        return Result(kernelOk = kernelOk, localFailedCount = localFailed)
    }

    /**
     * 清除结果：内核结果与本地失败项数分开回报。
     * @param kernelOk 内核清除是否成功；未请求内核清除时为 true
     * @param localFailedCount 请求清除但失败的**本地**数据项数量；0 表示全部成功
     */
    data class Result(val kernelOk: Boolean, val localFailedCount: Int) {
        val allOk: Boolean get() = kernelOk && localFailedCount == 0
    }
}
