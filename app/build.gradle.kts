import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// ============================================================
// release 签名凭据从项目根的 keystore.properties 读取（该文件已 gitignore，不入库）。
// 未提供该文件时回退 debug 证书并给出 warning —— 那种产物不可分发、不可上架。
//
// 需要在这里配置的原因：Android Studio 的签名向导只在那一次构建中注入
// android.injected.signing.*，不会写回本文件；命令行与 CI 构建要具备正式签名
// 能力，只能依赖下面这段配置。
// ============================================================
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    // 显式按 UTF-8 读取：Properties.load(InputStream) 默认按 ISO-8859-1 解码，
    // 会把含中文的路径（如 `D:/密钥库/xxx.jks`）变成乱码并报「keystore 不存在」。
    if (keystorePropsFile.exists()) keystorePropsFile.reader(Charsets.UTF_8).use { load(it) }
}
if (!keystorePropsFile.exists()) {
    logger.warn(
        "未找到 keystore.properties：release 将回退用 debug 证书签名，" +
            "该产物不可分发、不可上架。"
    )
} else {
    // 占位符检测必须在**配置期**硬失败，不能只警告：
    // 若放行，错误会推迟到几分钟后的 packageRelease 深处才炸，
    // 报错还会被包成难以定位的 "keystore password was incorrect"（密码错、别名错、
    // 占位符未替换三者在 keytool 层长得一模一样）。此处直接失败可把反馈压到几秒。
    //
    // 但**只在本次真的请求了 release 任务时才失败**：debug 构建不需要任何签名凭据，
    // 若一并挡住，贡献者 clone 下来连 assembleDebug 都跑不了，得不偿失。
    val pending = listOf("storePassword", "keyAlias", "keyPassword")
        .filter { keystoreProps.getProperty(it)?.contains("<<") == true }
    val wantsRelease = gradle.startParameter.taskNames.any {
        it.contains("Release", ignoreCase = true) && !it.contains("Debug", ignoreCase = true)
    }
    if (pending.isNotEmpty() && wantsRelease) {
        throw GradleException(
            "keystore.properties 中 ${pending.joinToString(" / ")} 仍为占位符，release 签名必定失败。" +
                "请填入真实值；别名可用 keytool -list -v -keystore <路径> -storepass <store 密码> 查询。"
        )
    }
}

android {
    namespace = "com.tangsnow.tangsnow"
    lint {
        // 仅关闭三类“风格/建议级”检查并写明理由（正确性类保留）：
        // UseKtx/Overdraw 为编码风格建议；NotifyDataSetChanged 对几十项小列表属可接受用法
        disable += setOf("UseKtx", "Overdraw", "NotifyDataSetChanged")
    }
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "com.tangsnow.tangsnow"
        minSdk = 26
        targetSdk = 37
        versionCode = 32
        versionName = "2.0.2"
        // 说明：本项目只有 JVM 单元测试（app/src/test），没有仪器测试（app/src/androidTest），
        // 因此**不声明** testInstrumentationRunner，也不引入 espresso / androidx.test 系列依赖 ——
        // 依赖表里留着一堆用不到的测试件，只会让「到底测了什么」变得不可信。
        // 将来真要加仪器测试时，再把 runner 与 espresso 加回来即可。
    }

    // 正式签名配置：仅在 keystore.properties 存在时创建；缺省时不创建，
    // 后续 release 回退到 debug 证书（保证任何人 clone 后都能构建出可安装的包）
    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                // 用 getProperty（Kotlin 侧可解析）；缺键时 requireNotNull 会给出明确报错
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 代码裁剪 + 资源裁剪（GeckoView 等关键类见 proguard-rules.pro）
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            optimization {
                enable = true
            }
            isShrinkResources = true
            // 有 keystore.properties → 正式证书；没有 → 回退 debug（并在配置阶段打 warning）
            signingConfig = if (keystorePropsFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    // ABI 拆分：GeckoView 原生库（.so，未压缩存储）约占包体 86%，按 ABI 独立出包可大幅瘦身；
    // 不生成包含全部 ABI 的 universal APK（体积巨大且无必要）。
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// ABI 各包必须拥有不同 versionCode：同设备切换 ABI 安装、商店多 APK 上架都要求
// 版本码唯一。规则：基准 * 10 + ABI 序号（arm64=1 / armv7=2 / x86_64=3）。
// AGP 9 新 Variant API：androidComponents.onVariants + 输出级 versionCode.set
// （旧的 applicationVariants / versionCodeOverride / OutputFile.ABI 均已移除）。
androidComponents {
    onVariants { variant ->
        val abiCodes = mapOf("arm64-v8a" to 1, "armeabi-v7a" to 2, "x86_64" to 3)
        variant.outputs.forEach { output ->
            val abi = output.filters.firstOrNull {
                it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI
            }?.identifier
            val offset = abiCodes[abi]
            if (offset != null) {
                output.versionCode.set(output.versionCode.get() * 10 + offset)
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.okhttp)
    implementation(libs.preference.ktx)
    implementation(libs.coroutines.android)
    // 二维码扫描（ZXing Embed，自带取景 Activity 与相机权限处理）
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") {
        isTransitive = false
    }
    implementation(libs.zxing.core)
    implementation(libs.geckoview)
    // 仅 JVM 单元测试（app/src/test）。仪器测试件（espresso / androidx.test）已移除：
    // 项目没有 androidTest 源码，留着它们属于空转依赖（见 defaultConfig 注释）。
    testImplementation(libs.junit)
}