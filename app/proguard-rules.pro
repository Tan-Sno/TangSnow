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
