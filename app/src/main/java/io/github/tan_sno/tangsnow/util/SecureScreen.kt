package io.github.tan_sno.tangsnow.util

import android.app.Activity
import android.view.WindowManager
import io.github.tan_sno.tangsnow.data.PreferenceStore

/**
 * 防截屏 / 防最近任务预览。
 *
 * 作用：给窗口加 `FLAG_SECURE`，效果有两条 ——
 *  1. 系统截图、录屏、投屏拿到的画面为空白（Android 会拒绝对该窗口做截取）；
 *  2. 「最近任务」里的应用快照同样为空白，避免切到后台后页面内容留在系统预览里。
 *
 * 为什么做成设置项而不是默认开启：该标志**对用户自己也生效**（自己截不了图），
 * 对「想把页面存下来」的正常使用是负体验。隐私保护不应顺带剥夺用户的正当能力，
 * 因此默认关闭、按需开启。
 *
 * 注意：`FLAG_SECURE` 是**窗口级**的，各 Activity 需各自应用（本应用只在浏览界面应用，
 * 与设置项摘要的措辞保持一致 —— 不夸大保护范围）。
 */
object SecureScreen {

    fun apply(activity: Activity, enabled: Boolean) {
        if (enabled) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    fun apply(activity: Activity, prefs: PreferenceStore) {
        apply(activity, prefs.secureScreen)
    }
}
