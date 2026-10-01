package io.github.tan_sno.tangsnow.data

import android.content.Context
import io.github.tan_sno.tangsnow.GeckoHolder
import io.github.tan_sno.tangsnow.data.repo.DownloadRepo
import io.github.tan_sno.tangsnow.data.repo.HistoryRepo
import io.github.tan_sno.tangsnow.util.CrashLogger
import io.github.tan_sno.tangsnow.util.awaitResult
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
     * 「清除浏览数据」**实际下发给内核**的位组合（纯函数，便于 JVM 单测）。
     *
     * 位值已核对（`javap -p -constants` 读 geckoview 制品的 ClearFlags，不是推算）：
     *   COOKIES=1、NETWORK_CACHE=2、IMAGE_CACHE=4、DOM_STORAGES=16、
     *   AUTH_SESSIONS=32、PERMISSIONS=64、ALL_CACHES=6、SITE_SETTINGS=192、
     *   SITE_DATA=471（= 1|2|4|16|64|128|256）、ALL=512
     * 由此得出两条结论：
     *  ① SITE_DATA **已含 PERMISSIONS 与 DOM 存储** ⇒ 站点权限不必再单独列举；
     *     唯 AUTH_SESSIONS(32) 不在其中 —— HTTP Basic/Digest 登录态会残留，而用户勾
     *     「Cookie 与站点数据」时的预期就是「登出这些站点」，故显式补上它。
     *  ② SITE_DATA 同时含两个缓存位（2|4）⇒ 勾了它必然**连带清缓存**：在当前的位
     *     定义下「只清 Cookie 不起缓存」做不到。界面上取消勾选「缓存」只能保证
     *     「没勾 Cookie 时不动缓存」，拦不住这条连带。
     * 这两条事实由 `ClearFlagsGuardTest` 兜着：内核一改位值，单测立刻变红。
     *
     * ⚠️ 抽成函数的理由（2026-10-01 CR-005）：这段组合是「登出这些站点」这条**对用户的承诺**的
     * 实现，而原先它只存在于 [clear] 的协程体内 —— 于是删掉 `AUTH_SESSIONS`（HTTP Basic/Digest
     * 登录态）之后 `ClearFlagsGuardTest` 仍全绿（那条测试只校验内核位值关系、从不引用本类）。
     * 现在**组合本身可被断言**：少一位就红灯。
     */
    internal fun kernelMaskFor(options: Options): Long {
        var mask = 0L
        if (options.cookiesAndSiteData) {
            mask = mask or StorageController.ClearFlags.COOKIES or
                StorageController.ClearFlags.SITE_DATA or
                StorageController.ClearFlags.AUTH_SESSIONS
        }
        if (options.cache) {
            mask = mask or StorageController.ClearFlags.ALL_CACHES
        }
        return mask
    }

    /**
     * 清除完成后**尚未被界面取走**的结果（进程级；取即清）。
     *
     * 为什么需要它：清除跑在进程级作用域（见 `ApplicationScope.scope` 的说明，理由：不能因设置页
     * 重建而被取消），因此它可能在**发起它的界面已经重建/消失之后**才完成 —— 那时若只在旧
     * Fragment 里 toast，用户永远看不到结果，等于把"零提示"那半个缺陷从取消换成了丢失。
     * 这里把结果留在进程级，由设置页下一次 `onResume` 取走并如实提示：**结果不因界面重建而丢**。
     *
     * ⚠️ 只保留最后一次（清除是幂等操作，旧结果没有提示价值）。
     */
    @Volatile
    private var pendingOutcome: Result? = null

    /** 存放待提示的结果（覆盖旧的） */
    internal fun rememberOutcome(result: Result) {
        pendingOutcome = result
    }

    /** 取走并清空待提示的结果（取即清 ⇒ 只提示一次） */
    internal fun takeOutcome(): Result? = pendingOutcome.also { pendingOutcome = null }

    suspend fun clear(context: Context, options: Options): Result {
        var kernelOk = true

        val runtime = GeckoHolder.runtime
        // 位组合见 [kernelMaskFor]（纯函数，单测钉住"必须含 AUTH_SESSIONS"这条承诺）
        val mask = kernelMaskFor(options)
        if (mask != 0L) {
            kernelOk = if (runtime == null) {
                // 内核尚未初始化却勾了内核数据 ⇒ 这次清除**根本没有发生**。此前静默跳过、
                // kernelOk 仍为 true，界面会报「已清除」—— 典型假反馈。如实计入失败。
                // （runtime 为 null 只会出现在内核尚未 warmUp 的极早期；正常路径到不了这里。）
                false
            } else {
                // awaitResult 正常返回即成功（GeckoResult<Void> 成功值为 null）；抛异常即失败。
                // ⚠️ 刻意不用 runCatching：它会把 CancellationException 一并吞掉 ⇒ 被取消的
                // 协程不但不中止，还接着往下清本地数据 —— 与下方 locally{} 自己立的规矩
                // （见那段注释）直接矛盾。这里显式 try/catch，取消原样传播。
                try {
                    runtime.storageController.clearData(mask).awaitResult()
                    true
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    android.util.Log.w("ClearDataUseCase", "kernel clear failed: ${e.javaClass.simpleName}")
                    false
                }
            }
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
                android.util.Log.w("ClearDataUseCase", "local clear failed: ${e.javaClass.simpleName}")
            }
        }

        if (options.history) {
            locally { HistoryRepo.clear() }
            // 「最近访问站点」属浏览记录的一部分（崩溃日志会写它，政策第 7 条已披露）：
            // 历史被清掉后它就不该再留在内存里 —— 否则下一次崩溃会把「已被清除的访问记录」
            // 重新落进持久化的日志文件，与用户刚做出的清除动作相悖。
            // 放在 locally{} **外面**：历史清理失败时也要清（内存值不该因一次 DB 失败而保留）。
            CrashLogger.clearHost()
        }
        if (options.sessionSnapshot) locally {
            // 用可等待版本：删除失败要计入 localFailedCount，不能假报「已清除」（D2）
            if (!SessionStore.clearAwait(context)) {
                throw java.io.IOException("session snapshot could not be deleted")
            }
            // 置位后，下一次 saveState 会跳过——否则"清掉快照"紧接着 onPause 又写回
            SessionStore.markPurged()
        }
        if (options.downloadRecords) locally { DownloadRepo.clearRecords(context) }

        return Result(kernelOk = kernelOk, localFailedCount = localFailed)
    }

    /**
     * 清除结果：**内核结果**与**本地失败项数**分开回报。
     *
     * 为什么不能只返回一个 Boolean：本地数据的清除是**逐项独立**的（历史 / 会话快照 / 下载记录），
     * 任一项失败若被并进「成功」里，用户就会看到「已清除」而数据其实还在 —— 分开回报才能如实提示
     * 「部分未清除」。
     *
     * @param kernelOk 内核清除是否成功；未请求内核清除时为 true
     * @param localFailedCount 请求清除但失败的**本地**数据项数量；0 表示全部成功
     */
    data class Result(val kernelOk: Boolean, val localFailedCount: Int) {
        val allOk: Boolean get() = kernelOk && localFailedCount == 0
    }
}
