# R8 / ProGuard 保留规则（GeckoView 155）

# GeckoView 通过 JNI/反射加载自身大量类，须整体保留。
# 注：AAR 自带 consumer 规则（解包其 proguard.txt，约 5.6KB、39 条 keep，其中就
# 包含对本类的全量 keep），下面这行是**冗余但显式**的声明 —— 不依赖「consumer
# 规则一定生效」这一隐含行为；实际合并结果见
# app/build/outputs/mapping/release/configuration.txt。
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

# 调试级日志（v/d/i）在 release 一律剥离 —— **防回归闸门**。
#
# 当前事实口径（2026-09-26 全仓清点，别把这段当成「已经剥了什么」）：
#  · 全仓**没有任何** Log.v / Log.d 调用点；唯一的 Log.i（SessionManager 的权限
#    拒绝留痕）在 `if (BuildConfig.DEBUG)` 内 —— R8 常量折叠后连同字符串一起消失。
#    因此本规则在当前代码上是 **no-op**，保留它是为了防回归：将来新增 v/d/i 调用点
#    时无需再想起这条纪律。
#  · release 里实际保留的是 Log.w ×3（MainActivity 主页图解码两处、
#    ClearDataUseCase 本地清除失败一处）与 Log.e ×2（LegalActivity），消息均不含
#    URL / 搜索词；ExtensionsActivity 的 Log.w 在 BuildConfig.DEBUG 门内，
#    release 不存在。
#  · **约定**：网络类异常的 message（常带完整下载 URL）不得进 w/e —— 网络失败
#    统一走「分类 → 人话」的路径（见 ExtensionsActivity.describeInstallError），
#    原始异常只在 debug 构建落日志。
#
# 为什么仍须显式写：AGP 的默认规则并不剥离 Log —— 本仓库 v2.1.1 曾用
# `dexdump -d` 反汇编实测 release 产物里 Log.i 真的在执行。本应用对外的隐私承诺
# 是「不把您访问的网址发送给第三方」，日志同理。
#
# 作用范围与代价：
#  · 本文件只挂在 release 构建类型上，**debug 构建日志照常**，排障能力不受影响；
#  · 保留 w/e 两级（见上方约定）；内核自身的日志不受本规则影响 —— 下方 -keep
#    会连带关闭这些类的优化，刻意为之，不去改动第三方行为。
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}
