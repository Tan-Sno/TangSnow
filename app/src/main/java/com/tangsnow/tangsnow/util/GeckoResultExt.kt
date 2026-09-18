package com.tangsnow.tangsnow.util

import kotlinx.coroutines.suspendCancellableCoroutine
import org.mozilla.geckoview.GeckoResult
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [GeckoResult] 的挂起桥。
 *
 * GeckoView 155 的 AAR 内不含 Kotlin 扩展（javap 已核验无 `*Kt.class`，即没有官方的
 * `GeckoResult.await()`）。故自建：成功回调 resume 值、异常回调 resumeWithException、
 * 协程取消时连带 cancel 内核任务。
 *
 * 注意：[GeckoResult] 允许 `complete(null)`——例如 `GeckoResult<Void>` 成功时值就是 null。
 * 因此本函数**以"正常返回"表示成功、"抛异常"表示失败**，返回值本身可能为 null。
 */
suspend fun <T> GeckoResult<T>.awaitResult(): T? = suspendCancellableCoroutine { cont ->
    accept(
        { value -> if (cont.isActive) cont.resume(value) },
        { error ->
            // Consumer<Throwable> 的参数在 Kotlin 侧是可空平台类型，null 时兜底一个通用异常
            if (cont.isActive) cont.resumeWithException(error ?: RuntimeException("GeckoResult failed"))
        }
    )
    cont.invokeOnCancellation { runCatching { cancel() } }
}
