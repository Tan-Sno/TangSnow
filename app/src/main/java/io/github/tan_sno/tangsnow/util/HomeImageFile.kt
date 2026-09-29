package io.github.tan_sno.tangsnow.util

import android.content.Context
import java.io.File

/**
 * 主页背景图**应用内副本**的目录约定与删除出口。
 *
 * ## 为什么必须收在一个出口里
 *
 * 副本在选图时创建，但**文件不会随偏好一起消失**：偏好被清掉或被替换后，文件仍旧留在
 * `filesDir` 里。因此删除必须由调用方显式触发 —— 而触发点有三个（清除、替换、
 * 主界面发现副本已不可读时的回退）。各写一份必然漏一处，漏掉的后果是**无主副本悄悄累积**：
 * 用户以为「清除图片」已经把它删了，其实还在盘上。
 *
 * ## 归属守卫（绝不删到别人的文件）
 *
 * 只删「`file://` 且路径确实落在本应用 `filesDir/[DIR_NAME]` 之下」的目标。存量偏好里可能是
 * 相册的 `content://`（旧版本写法），也可能是被外部写入的任意路径，一律不动。
 * 判定抽成纯函数 [ownCopyPath]，便于单测把 `..` 穿越这类输入也钉住。
 *
 * ## 为什么是一次性线程而不是协程
 *
 * 三个调用点都不需要删除的结果（拿完就往下走），而且其中两处可能发生在生命周期作用域
 * **正在取消**的时刻（「清除图片后立刻退出」「副本复制完但宿主已销毁」）—— 挂在
 * `lifecycleScope` 上的清理会随 onDestroy 一起被取消，副本就永久留下了。
 * 这与 ExtensionsActivity 的残包清理是同一处置。
 */
internal object HomeImageFile {

    /** 副本所在子目录名（`filesDir/<此名>`） */
    const val DIR_NAME = "home_bg"

    private const val FILE_SCHEME = "file://"
    private const val THREAD_NAME = "home-image-cleanup"

    /**
     * 副本目录。创建与删除共用它，保证与 [ownCopyPath] 的归属判定同源 ——
     * 两处各写一个字面量，改一处漏一处就会让守卫失效。
     */
    fun dir(context: Context): File = File(context.filesDir, DIR_NAME)

    /**
     * [uriString] 是否指向 [dir] 里的自有副本；是则返回待删文件，否则 null。
     *
     * 纯函数（只做前缀与路径归一化，不读写文件）：
     * - 只认 `file://` 前缀（本项目由 `Uri.fromFile` 生成，恒为三斜杠形式）；
     * - 用 `canonicalPath` 归一化后再比前缀 ⇒ `file://<dir>/../x` 这类穿越会被判出界；
     * - 要求带上目录分隔符 ⇒ 目录本身（`<dir>`）以及同前缀的兄弟目录
     *   （`<dir>_extra/x`）都不会被误命中。
     */
    internal fun ownCopyPath(dir: File, uriString: String?): File? {
        val raw = uriString?.takeIf { it.startsWith(FILE_SCHEME) } ?: return null
        val path = raw.removePrefix(FILE_SCHEME)
        if (path.isEmpty()) return null
        val target = File(path)
        val insideDir = runCatching {
            target.canonicalPath.startsWith(dir.canonicalPath + File.separator)
        }.getOrDefault(false)
        return if (insideDir) target else null
    }

    /**
     * 后台线程删除副本（尽力而为：失败静默 —— 留下一个文件的后果有界，
     * 而抛出去会打断调用方正在做的用户可见动作）。
     */
    fun deleteOnBackgroundThread(context: Context, uriString: String?) {
        val appContext = context.applicationContext
        Thread({ deleteCopyBlocking(appContext, uriString) }, THREAD_NAME)
            .apply { isDaemon = true }
            .start()
    }

    /** 删除本体。**只由 [deleteOnBackgroundThread] 调用**，故「必须在后台线程」这条不会漏到调用方。 */
    private fun deleteCopyBlocking(context: Context, uriString: String?) {
        val target = ownCopyPath(dir(context), uriString) ?: return
        runCatching { target.delete() }
    }
}
