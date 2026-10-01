package io.github.tan_sno.tangsnow.data.repo

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

    /**
     * **进程级**协程作用域：只给「必须跨界面存活的一次性操作」用。
     *
     * 首个（也是目前唯一）使用点：**清除浏览数据** —— 它在设置页发起，而清单的 `configChanges`
     * 不含 `uiMode`/`locale` ⇒ 切主题/语言会重建设置页，`lifecycleScope` 随之取消；又因为
     * [io.github.tan_sno.tangsnow.data.ClearDataUseCase.clear] **刻意原样传播取消**（不吞
     * `CancellationException`），结果是「内核已清、本地未清、零提示」。
     *
     * 为什么这样选（2026-10-01 查过 Android 官方与社区的一致口径）：
     *  · **不用 `NonCancellable`**：它只适合"小块清理"，用在长操作上会造成不可中断的循环、
     *    无法取消的测试与不可预测的错误处理；
     *  · **不用 `viewModelScope`**：本页是 `PreferenceFragmentCompat`（没有 ViewModel），
     *    而且 `viewModelScope` 在导航离开时同样会被取消；
     *  · **用应用级作用域**：这正是官方推荐给"必须跑完、与界面无关"的活儿的位置。
     *    （若将来需要"进程被杀也要跑完"，再换 `WorkManager`。）
     *
     * ⚠️ 普通 UI 相关协程仍用 `lifecycleScope` —— **别把这里当成随手可用的杂项作用域**，
     * 它会让协程活过发起它的界面。用 `SupervisorJob`：一个子任务失败不牵连其他。
     */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
