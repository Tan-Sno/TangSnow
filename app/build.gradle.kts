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
    // 凭据可用性（配置期求值一次，供下方两道闸门共用）。
    // 键缺失（getProperty 返回 null）与占位符「<<…>>」同样视为不可用：此前 storeFile
    // 缺键会让 file() 在配置期抛出与「占位符未替换」毫无关系的空检查错误，路径写错
    // 则落到打包深处、与「密码错/别名错」混成三义性 —— 正是这些闸门要消灭的东西。
    val credKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    fun invalidCredentials(): List<String> {
        val missing = credKeys.filter { keystoreProps.getProperty(it)?.contains("<<") ?: true }
        if ("storeFile" in missing) return missing
        // storeFile 本身可用时才查文件存在性；相对路径按 app/ 模块目录解析（与下方 file() 一致）
        return missing + listOfNotNull(
            "storeFile".takeIf { !file(keystoreProps.getProperty("storeFile")).isFile }
        )
    }
    val invalidCredentialsAtConfig = invalidCredentials()

    // 但下列两种情况下**绝不能失败**，否则会把本来能成功的构建挡死：
    //
    // ① 本次请求的不是 release 打包任务 —— debug 构建不需要任何签名凭据，
    //    若一并挡住，贡献者 clone 下来连 assembleDebug 都跑不了。
    //
    // ② Android Studio 的「Generate Signed Bundle / APK」签名向导在驱动本次构建 ——
    //    向导**不读本文件**，而是为这一次构建注入 android.injected.signing.*，
    //    并由 AGP 用它覆盖项目里声明的 signingConfig（这正是向导的意义：
    //    即使项目自带一份签名配置也能被覆盖）。此时凭据由向导提供，
    //    keystore.properties 里是否还留着占位符与本次构建**无关**，必须放行。
    //
    // ⚠️ 属性名是**点号**分隔（`store.password` 而非 `storePassword`）——
    // 取自 AGP 内部常量，已在 gradle 缓存里反查确认：
    //   android.injected.signing.store.file / store.password /
    //   key.alias / key.password（另有 store.type、v1、v2）
    val injectedSigning = gradle.startParameter.projectProperties
    val wizardDriven = listOf(
        "android.injected.signing.store.password",
        "android.injected.signing.key.alias",
        "android.injected.signing.key.password",
    ).all { !injectedSigning[it].isNullOrBlank() }

    // 闸门一（配置期，任务名启发式）：对显式点名 release 打包的任务最快失败。
    // ⚠️ 已知盲区：`./gradlew build` / `./gradlew assemble` 这类汇总任务名不含
    // "Release" 却会带上 release 打包 —— 由下方闸门二在执行期兜底。
    val packagingVerbs = listOf("package", "assemble", "bundle", "install")
    val wantsRelease = gradle.startParameter.taskNames.any { raw ->
        val name = raw.substringAfterLast(':')
        name.contains("Release", ignoreCase = true) &&
            !name.contains("Debug", ignoreCase = true) &&
            packagingVerbs.any { name.startsWith(it, ignoreCase = true) }
    }
    if (invalidCredentialsAtConfig.isNotEmpty() && wantsRelease && !wizardDriven) {
        throw GradleException(
            "release 签名凭据不可用(${invalidCredentialsAtConfig.joinToString(" / ")})。\n" +
                "二选一即可：\n" +
                "  1) 填入真实值。别名可用 " +
                "keytool -list -v -keystore <storeFile 路径> -storepass <store 密码> 查询；\n" +
                "  2) 改用 Android Studio 的「Build → Generate Signed Bundle / APK」向导，" +
                "它自带凭据输入、不读本文件，因此无需修改这里。"
        )
    }

    // 闸门二（执行期安全网）：覆盖闸门一的盲区 —— `build`/`assemble` 汇总任务带出的
    // release 打包。凡 package/bundle/install *Release（不含 uninstall）都要求凭据可用；
    // 取值已在配置期完成，doFirst 只读捕获值 ⇒ 配置缓存友好；向导驱动时整体跳过。
    // 错误不在这里的配置期抛、而挪到 doFirst：那会把「只想跑 build 里的 debug 部分」
    // 的调用一并挡死，只拦真正要签名的那一步才对。
    tasks.configureEach {
        if (wizardDriven) return@configureEach
        if (!name.matches(Regex("^(package|bundle|install).*Release$"))) return@configureEach
        val invalid = invalidCredentialsAtConfig
        if (invalid.isNotEmpty()) {
            doFirst {
                throw GradleException(
                    "release 签名凭据不可用(${invalid.joinToString(" / ")})——本次打包签名必定失败。\n" +
                        "二选一：1) 在 keystore.properties 填入真实值；2) 用 Android Studio 的\n" +
                        "「Build → Generate Signed Bundle / APK」向导（它不读本文件，自带凭据）。"
                )
            }
        }
    }
}

android {
    namespace = "io.github.tan_sno.tangsnow"
    lint {
        // 打开全部警告级检查。
        //
        // 为什么必须显式写：AGP 默认只报 error/fatal 与**部分** warning，于是
        // `lintDebug` 输出 "No issues found" 只意味着「默认口径下没问题」，
        // 很容易被读成「零问题」—— 那正是「一个被关掉的检查比没有检查更危险」的变体：
        // 它制造「已经检查过了」的错觉。开启后所有 warning 级检查都会进报告。
        // （实测效应：开启后一次就多出 225 条 warning —— 此前它们全是不可见的。）
        checkAllWarnings = true

        // 关闭的检查**逐条写明理由**；正确性类检查一律保留。
        //
        // 分两组：① 对本项目「整类不适用」的；② 「适用条件在本项目不成立」的。
        // 每组都写清「为什么关掉它不会漏掉真问题」——否则下一个人只能选择盲信或重查。
        disable += setOf(
            // —— ① 整类不适用 ——
            // 面向 Java：Java 内部类访问外部类私有成员会生成一个合成访问器方法（多一次调用）。
            // Kotlin **不生成**这类访问器（编译期直接在字节码层处理），故本检查对纯 Kotlin 工程无对象。
            "SyntheticAccessor",
            // 建议把直引号换成弯引号（英文排版习惯）。本项目界面为中文，中文引号是「」；
            // 直引号只出现在英文文案与技术标识里，换成弯引号反而错。
            "TypographyQuotes",
            // 编码风格建议：用 KTX 扩展替代等价调用。
            "UseKtx",
            // 过度绘制提示：布局层级已按需优化，此项对成品无可执行动作。
            "Overdraw",
            // 对几十项的小列表属可接受用法（全量重绑的开销远小于列表规模）。
            "NotifyDataSetChanged",

            // —— ② 适用条件在本项目不成立 ——
            // 同名文案语义独立：app_name / home_wordmark / style_tangsnow 都是「棠雪」，
            // 但三者会各自演进（改应用名不该顺带改风格名）。提取成一条会强行耦合；
            // 且同名值不增加包体（资源表按 key 存，不按值去重）。
            "DuplicateStrings",
            // 包级 `Context.toast` 扩展与 Activity 成员 `toast` 同名（ExtensionsActivity / MainActivity）。
            // 两套实现语义**完全相同**（都是 Toast.makeText + LENGTH_SHORT），
            // 而 Kotlin「成员优先于扩展」是确定行为 —— 不存在因遮蔽而调错实现的风险。
            // 保留成员版是有意的：Activity 内部调用少一个 receiver。
            "MemberExtensionConflict",
            // 列表项与可点击行内的文本若设 textIsSelectable，会**劫持父容器的点击手势**
            // —— 点标签 / 点扩展 / 点文档行会变成「选中文本」，功能直接失效。
            // 纯展示页里确有复制价值的只有「关于」页的版本号，已在 activity_about.xml 显式启用。
            "SelectableText",
            // 全项目仅 1 张 96×96、6.4KB 的图标（ic_ext_darkreader.png）。
            // 转 WebP 的收益可忽略，却要为构建/维护引入一条图片处理链路；不划算。
            "ConvertToWebp",
        )
    }
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        // 应用标识：反向域名形式，基于 GitHub 提供的 <用户名>.github.io（= io.github.tan_sno）。
        // 为什么不是 com.tangsnow.tangsnow：① 组织段与应用段同名，冗余；
        // ② tangsnow.com 已被他人注册，用一个自己不控制的域名做前缀，就失去了
        // 「反向域名保证全局唯一」这一规范的全部意义。
        // io.github.<用户名> 是 Maven Central(Sonatype) 官方文档明确支持的、
        // 面向「没有自有域名的小项目」的命名空间，且归属可由 GitHub 账号验证。
        // ⚠️ applicationId 一经发布不可更改（改了会被视为另一个应用、旧版无法升级），
        // 故此处与 namespace 保持一致并显式声明，避免将来改 namespace 时连带改掉它。
        applicationId = "io.github.tan_sno.tangsnow"
        minSdk = 26
        targetSdk = 37
        // 2.1.0：更换 applicationId 等于更换应用身份，与 2.0.2 的安装身份不兼容
        // （旧版无法覆盖升级，需卸载重装）。若仍沿用 2.0.2，会出现「两个不同的应用
        // 都自称 2.0.2」，故递增次版本号以示区分。
        // 2.1.1：「检查更新」改为向 GitHub Releases 读取并自动比对版本、按设备架构
        // 给出对应下载；由此新增对外端点 api.github.com（隐私政策 §4 与同意页摘要
        // 已同步披露，POLICY_VERSION 升至 18）。另含若干死代码与不合约定写法的清理。
        // 2.1.2：隐私与合规修正（均未改变对外端点，故政策版本只因文本修正而从 18 升至 19）——
        //  ① 特权 scheme（file: / moz-extension:）的放行判据改用内核文档化的
        //     `isDirectNavigation`，堵掉「data: 文档里跳 file:」被判成非网页发起而放行的漏判；
        //  ② release 不再把站点域名写进 Logcat：隐私相关日志门控在 BuildConfig.DEBUG，
        //     并在 proguard-rules.pro 剥离 Log.v/d/i（AGP 默认规则并不剥离）；
        //  ③ 清除浏览数据如实披露「勾选 Cookie 与站点数据会连带清缓存」（内核位掩码使然，
        //     由 ClearFlagsGuardTest 断言守着）；
        //  ④ 扩展安装补回「下载中 n%」进度（此前类注释承诺了、实现没跟上）；
        //  ⑤ 政策正文的加粗标记不再被原样显示成星号，政策「更新日期」同步。
        // 2.1.3：把 09-22 那批修复**正式收进一个递增的版本号**。
        //   起因：2.1.2 曾以**同版本号重新打包**发布（说明见该版发布页），versionCode 未变 ⇒
        //   已装 2.1.2 的用户点「检查更新」不会收到提示。本版把 versionCode 35 → 36，
        //   让下面这些修复能正常触达已安装用户。无新增对外端点，POLICY_VERSION 不变（19）。
        //  ① 扩展调用 `tabs.create()` 此前**必然失败**：在 IO 线程建会话，而
        //     `GeckoSession.open` 的首条指令就是断言主线程（javap 实测）—— 表现为
        //     扩展里点「在新标签打开」没有任何反应；
        //  ② 自定义主页图片永久失效时不再静默保留坏配置，改为提示并回退极简；
        //  ③ 扩展委托持有方在 runtime 已关闭时仍会被摘除（原先整段跳过 ⇒ 进程级集合
        //     强引用已销毁的 Activity 且再也回不到空集，委托从此无法解绑）；
        //  ④ `onVisited` / `getVisited` 的取消语义收敛，不再用 runCatching 吞掉取消异常；
        //  ⑤ 主线程 I/O 收敛：`HistoryRepo.areVisited` 改 suspend + Dispatchers.IO；
        //     冷启动读会话快照若预读未完成，改为有界等待，不再在主线程重复读盘 + 解析；
        //  ⑥ lint 开启 `checkAllWarnings`（此前默认口径下不可见的 225 条 warning 已逐条处置，
        //     全警告口径下仍为 `No issues found`）。
        // 2.1.4：**披露准确性 + 发布链路加固**。政策升至 21 —— §4 的名单主机此前写的
        //   是一个本应用已禁用、并不产生流量的地址（真实主机是 Mozilla Remote Settings）；
        //   「请勿跟踪（DNT）」在 GeckoView 155 无 API 可用、属无法兑现的承诺，已移除；
        //   AMO 的「图标」用途不成立（图标全部内嵌 APK）。全量用户下次启动会重新同意，
        //   这是预期的。无新增对外端点与权限。
        //  ① 发布链路：`gradlew` 补可执行位（非 Windows 环境 `./gradlew` 会 Permission denied）；
        //     签名守卫补**执行期安全网**，堵住 `build` / `assemble` 绕过配置期检查仍去打包
        //     签名的盲区；未登记 ABI 改配置期硬失败；`verify_release.py` 补 minSdk/targetSdk
        //     断言、versionCode 解析行锚定、v2 签名判定改用 schemes 解析、冒烟修正 aapt2
        //     字段名大小写（`sdkVersion` → `minSdkVersion`，此前 minSdk 恒为 None 全误报）；
        //  ② 如实反馈：大文件路由后系统下载器拒绝 URL 时不再静默（此前「开始下载」成了
        //     空头支票）；「退出时自动清除」的设置摘要改为枚举实际触发面；
        //  ③ 性能与正确性：书签导入由逐条事务收敛为**整批一个写事务**；地址栏联想补**真防抖**
        //     （此前注释写着「防抖」、实现里只有序号比对，每键两次 DB 查询）；
        //  ④ 可访问性：新增文字专用色 `accent_text`（原 `accent` 在多种浅背景上低于 4.5:1），
        //     20 处文字切换、图标仍用 `accent`；方向性图标补 `autoMirrored`；
        //  ⑤ 仓库卫生：`.gitignore` 兜底 apk/aab/hprof，新增 `.editorconfig`，备份规则补
        //     设备保护存储四域，`file_paths.xml` 按实际交付落点最小化收紧。
        versionCode = 37
        versionName = "2.1.4"
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
                // 键缺失时显式报错而非空检查 NPE（此前注释声称 requireNotNull 却未落实）。
                // 占位符场景仍放行创建 —— 由上方两道闸门在 release 打包时拦截，
                // 保证贡献者只跑 assembleDebug 时完全不受影响。
                fun cred(key: String): String =
                    keystoreProps.getProperty(key)
                        ?: throw GradleException("keystore.properties 缺少 $key。")
                storeFile = file(cred("storeFile"))
                storePassword = cred("storePassword")
                keyAlias = cred("keyAlias")
                keyPassword = cred("keyPassword")
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
            // 未登记的 ABI（含 universal 未拆分）必须配置期硬失败：静默跳过会让该包
            // 拿到未加偏移的基准 versionCode，比同批带偏移的包更低 —— 同机换 ABI
            // 安装会被系统判为「降级」而拒绝，且无任何提示。
            val offset = abiCodes[abi]
                ?: error(
                    "ABI「${abi ?: "universal（未拆分）"}」未登记 versionCode 偏移。"
                        + "请同步本表与 tools/verify_release.py 的 ABI_SPLITS，"
                        + "或恢复 isUniversalApk = false。"
                )
            output.versionCode.set(output.versionCode.get() * 10 + offset)
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
    // isTransitive = false：它自带的那份 zxing-core 与下面显式声明的版本会冲突，故只取本体
    implementation(libs.zxing.embed) {
        isTransitive = false
    }
    implementation(libs.zxing.core)
    implementation(libs.geckoview)
    // 仅 JVM 单元测试（app/src/test）。仪器测试件（espresso / androidx.test）已移除：
    // 项目没有 androidTest 源码，留着它们属于空转依赖（见 defaultConfig 注释）。
    testImplementation(libs.junit)
    // android.jar 的 org.json 在 JVM 测试里是抛 Stub! 的桩；用 Maven 真实实现覆盖
    // test classpath（仅测试可见，不进产物），SessionStore 的 JSON 往返才能被单测。
    testImplementation(libs.json)
}