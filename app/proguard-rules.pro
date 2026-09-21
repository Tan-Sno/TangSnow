# R8 / ProGuard 保留规则（GeckoView 155）

# GeckoView 通过 JNI/反射加载自身大量类，须整体保留
-keep class org.mozilla.geckoview.** { *; }
-dontwarn org.mozilla.geckoview.**

# ZXing Android Embedded（扫码）通过反射装配 CaptureActivity 与解码器
-keep class com.journeyapps.barcodescanner.** { *; }
-dontwarn com.journeyapps.barcodescanner.**
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# OkHttp 内部基于 ServiceLoader 的拦截器按需保留（R8 一般自动处理，此处兜底）
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# kotlinx-coroutines / AndroidX：跟随 AGP 默认规则即可，无需额外 keep

# 调试级日志（v/d/i）在 release 一律剥离。
#
# 为什么必须显式写：AGP 9 的默认规则**并不**剥离 Log —— 本仓库的 release APK 用
# `dexdump -d` 反汇编后仍能读到 `invoke-static …, Landroid/util/Log;.i:` 真的在执行
# （实测于 v2.1.1 的 classes.dex）。也就是说，像「哪个站点触发了预期外的权限类型」
# 这类信息会真的落进设备 Logcat。本应用对外的隐私承诺是「不把您访问的网址发送给
# 第三方」，日志同理，故在 release 里彻底去掉。
#
# 作用范围与代价：
#  · 本文件只挂在 release 构建类型上，**debug 构建日志照常**，排障能力不受影响；
#  · 保留 w/e 两级：它们在 release 里只承载真实失败，且调用点自身已做内容收敛；
#  · `-keep class org.mozilla.geckoview.**` 会连带关闭这些类的优化，故内核自身的
#    日志不受本规则影响 —— 刻意为之，不去改动第三方行为。
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}
