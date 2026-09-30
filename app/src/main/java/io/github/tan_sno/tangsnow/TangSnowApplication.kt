package io.github.tan_sno.tangsnow

import android.app.Application
import android.os.StrictMode
import io.github.tan_sno.tangsnow.data.repo.ApplicationScope
import io.github.tan_sno.tangsnow.data.ConsentGate
import io.github.tan_sno.tangsnow.data.LocaleManager
import io.github.tan_sno.tangsnow.data.PreferenceStore
import io.github.tan_sno.tangsnow.data.SessionStore

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
        // 本地崩溃日志**不在这里装**：它曾无条件注册在 Application.onCreate，于是"同意门禁还没
        // 走完"时崩溃也会落盘，与同意页"同意前不做任何数据处理"的字面冲突。
        // 现在改由 MainActivity.onCreate 在**确认已同意**之后安装（见那里的注释）。
        // 应用用户上次选择的语言（系统 / 中文 / English）。
        // API 33+ 仅在偏好与"上次已应用值"不同时才调用系统接口，避免覆盖用户在系统设置里为本
        // 应用单独指定的语言（per-app language）；API ≤ 32 没有该系统级存储，每次冷启动都重放，
        // 否则用户选过的语言会在进程重启后静默回退（判定见 LocaleManager.needReapply）。
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