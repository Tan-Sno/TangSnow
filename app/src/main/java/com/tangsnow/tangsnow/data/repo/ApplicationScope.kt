package com.tangsnow.tangsnow.data.repo

import android.annotation.SuppressLint
import android.content.Context

/**
 * 全局可用的 Application context（由 [ApplicationScope.init] 注入）。
 *
 * 刻意**不用 `lateinit var`**：`lateinit` 在未初始化时抛的是
 * `UninitializedPropertyAccessException`，堆栈里只有 `getContext`，看不出「谁忘了初始化」。
 * 这里改成显式可空字段 + 带说明的取值器：
 *  - 正常路径行为不变（`TangSnowApplication.onCreate` 最先调用 [init]）；
 *  - 一旦真的被早于 Application.onCreate 访问（例如将来新增 ContentProvider —— 它的
 *    onCreate 先于 Application.onCreate 执行），会立刻抛出**写明原因与修法**的异常，
 *    而不是一个无从下手的空指针式报错。
 */
object ApplicationScope {
    @SuppressLint("StaticFieldLeak") // 仅缓存 applicationContext（非 Activity），进程级单例生命周期内安全
    @Volatile
    private var appContext: Context? = null

    /** 进程级 Application context；未初始化即抛错（见类注释） */
    val context: Context
        get() = appContext ?: error(
            "ApplicationScope 尚未初始化：请在 TangSnowApplication.onCreate 中先调用 " +
                "ApplicationScope.init(this)。若新增了 ContentProvider，注意其 onCreate 早于 " +
                "Application.onCreate，需改用 androidx.startup 或在该 Provider 内自行初始化。"
        )

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }
}
