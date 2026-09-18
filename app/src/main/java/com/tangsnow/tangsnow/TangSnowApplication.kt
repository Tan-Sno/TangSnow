package com.tangsnow.tangsnow

import android.app.Application
import android.os.StrictMode
import com.tangsnow.tangsnow.data.repo.ApplicationScope
import com.tangsnow.tangsnow.data.ConsentGate
import com.tangsnow.tangsnow.data.LocaleManager
import com.tangsnow.tangsnow.data.PreferenceStore
import com.tangsnow.tangsnow.data.SessionStore

class TangSnowApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 仅 debug 构建开启主线程违规检测：主线程上的磁盘/网络操作会在 logcat 直接点名。
        // 这类问题（如设置页首次滑动卡顿）靠用户体感反馈成本太高，让工具自己报。
        // release 绝不开启：penaltyLog 仍有开销，且不应对线上行为产生任何影响。
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .detectLeakedSqlLiteObjects()
                    .penaltyLog()
                    .build()
            )
        }
        ApplicationScope.init(this)
        // 本地崩溃日志（仅落盘留存，供「关于 → 崩溃报告」查看/分享；不上传）
        com.tangsnow.tangsnow.util.CrashLogger.install(this)
        // 应用用户上次选择的语言（系统 / 中文 / English）。
        // 仅在偏好与"上次已应用值"不同时才调用系统接口，避免覆盖用户在
        // 系统设置里为本应用单独指定的语言（Android 13+ per-app language）。
        val prefs = PreferenceStore(this)
        LocaleManager.apply(this, prefs)
        // 会话快照后台预读：供 MainActivity 冷启动时同步取用，
        // 避免在主线程读盘 + 解析 JSON（快照越大首帧越慢）。
        //
        // ⚠️ 仅在**用户此前已同意**隐私政策时预读：首次安装或政策更新后，
        // 同意页上的承诺是「在您点击同意之前不加载网页、不处理任何数据」，
        // 此时读本机会话快照会破坏该承诺。首启场景本身也没有快照，无损失。
        if (!ConsentGate.needsConsent(prefs)) {
            SessionStore.preload(this)
        }
    }
}