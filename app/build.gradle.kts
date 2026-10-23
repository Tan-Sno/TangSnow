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
    // 会把含中文的路径（例如把 keystore 放在名为「密钥库」的目录下）变成乱码并报「keystore 不存在」。
    if (keystorePropsFile.exists()) keystorePropsFile.reader(Charsets.UTF_8).use { load(it) }
}
// 「Android Studio 签名向导驱动本次构建」的判据 —— **三处闸门单一来源**。
// 2026-10-01 提升到顶层：第三道闸门在这个 if/else 之外，原先取不到块内的局部值（编译不过）。
// 向导不读 keystore.properties，而是为这一次构建注入 android.injected.signing.*，由 AGP 覆盖项目里
// 声明的 signingConfig ⇒ 此时凭据由向导提供，必须放行。
// ⚠️ 属性名是**点号**分隔（`store.password` 而非 `storePassword`）—— 取自 AGP 内部常量，已在 gradle 缓存里反查确认。
val injectedSigning = gradle.startParameter.projectProperties
val wizardDriven = listOf(
    "android.injected.signing.store.password",
    "android.injected.signing.key.alias",
    "android.injected.signing.key.password",
).all { !injectedSigning[it].isNullOrBlank() }

if (!keystorePropsFile.exists()) {
    logger.warn(
        "未找到 keystore.properties：release 将回退用 debug 证书签名，" +
            "该产物不可分发、不可上架。"
    )
} else {
    // 凭据键不可用判定（文件级函数，供下方配置期闸门与执行期安全网共用）。
    // 三种形态都视为不可用：键缺失（null）、**空值**（此前漏网 —— `keyPassword=`
    // 会被当成有效凭据放行，深处炸出三义性错误）、占位符「<<…>>」。
    val credKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    fun invalidCredentialKeys(props: java.util.Properties): List<String> =
        credKeys.filter { val v = props.getProperty(it); v.isNullOrBlank() || v.contains("<<") }

    // 凭据可用性（配置期求值一次，供闸门一快速反馈）。
    // storeFile 缺键/占位符会让 file() 在配置期抛出与「占位符未替换」毫无关系的
    // 空检查错误，路径写错则落到打包深处、与「密码错/别名错」混成三义性 ——
    // 正是这些闸门要消灭的东西。
    fun invalidCredentials(): List<String> {
        val missing = invalidCredentialKeys(keystoreProps)
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
    // 向导驱动判据 = 文件顶层的 `wizardDriven`（单一来源，三处闸门共用）。
    // 另有 `android.injected.signing.store.file` / `store.type` / `v1` / `v2` 等键可做更细的判断。

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
    // release 打包。凡 package/bundle/install *Release（不含 uninstall）一律挂检查。
    // ⚠️ 必须**执行期重读** keystore.properties：配置缓存不追踪构建逻辑直接读的
    // 文件 —— 配置期快照被缓存后，「填好真实凭据再原样重跑」会被陈旧 doFirst 误拦
    // （提示让用户填，而用户已填），反向「凭据改坏」时又全哑。以执行期磁盘内容为准
    // 双向正确；读取都在 doFirst 内、不触碰 Project API ⇒ 配置缓存友好。
    // 向导驱动时整体跳过（android.injected.* 是 -P 属性，属缓存输入，键变化触发重配置）。
    tasks.configureEach {
        if (wizardDriven) return@configureEach
        if (!name.matches(Regex("^(package|bundle|install).*Release$"))) return@configureEach
        doFirst {
            // 注意：这里用导入的简名 Properties，不能写全限定 java.util.Properties ——
            // Kotlin DSL 里 `java` 会先解析到插件访问器，全限定形式反而编译不过。
            // 文件若在配置与执行之间被删除，按「全部凭据不可用」给友好提示而非裸 IO 堆栈。
            val current = runCatching {
                Properties().apply {
                    keystorePropsFile.reader(Charsets.UTF_8).use { load(it) }
                }
            }.getOrNull()
            val invalid = if (current == null) credKeys else invalidCredentialKeys(current)
            if (invalid.isNotEmpty()) {
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
        // 2.1.4：**披露纠错 + 发布链路加固 + 局域网权限**。政策升至 **22** ——
        //   ① 名单主机纠错：§4 原写的 shavar.services.mozilla.com 只服务已禁用的
        //      safebrowsing gethash（omni.ja 实测），真实主机是 Mozilla Remote Settings；
        //   ② DNT 承诺移除（GeckoView 155 无 API 可用，无法兑现）；
        //   ③ AMO「图标」用途移除（图标全部内嵌 APK）；
        //   ④ 新增 ACCESS_LOCAL_NETWORK（Android 17 起访问局域网必需，按需申请）。
        //   全量用户下次启动会重新同意，这是预期的。无新增对外端点。
        //  ① 发布链路：`gradlew` 补可执行位（非 Windows 环境 `./gradlew` 会 Permission denied）；
        //     签名守卫补**执行期安全网**，堵住 `build` / `assemble` 绕过配置期检查仍去打包
        //     签名的盲区；未登记 ABI 改配置期硬失败；`verify_release.py` 补 minSdk/targetSdk
        //     断言、versionCode 解析行锚定、v2 签名判定改用 schemes 解析、冒烟修正 aapt2
        //     字段名大小写（`sdkVersion` → `minSdkVersion`，此前 minSdk 恒为 None 全误报）；
        //  ② 如实反馈：下载落盘失败不再被当成成功、系统下载器拒绝 URL 时也不再静默
        //     （此前「开始下载」常成空头支票）。⚠️ 原先这句写的是「**大文件路由后**…」，
        //     已失准：按 Content-Length 把大文件交系统下载器的路由**在本版本内已撤销**
        //     （登录态大附件二次 GET 不带 Cookie，会把登录页 HTML 存成目标文件名还报成功），
        //     故本版不存在该路由。「退出时自动清除」的设置摘要改为枚举实际触发面；
        //  ③ 性能与正确性：书签导入由逐条事务收敛为**整批一个写事务**；地址栏联想补**真防抖**
        //     （此前注释写着「防抖」、实现里只有序号比对，每键两次 DB 查询）；
        //  ④ 可访问性：新增文字专用色 `accent_text`（原 `accent` 在多种浅背景上低于 4.5:1），
        //     20 处文字切换、图标仍用 `accent`；方向性图标补 `autoMirrored`；
        //  ⑤ 仓库卫生：`.gitignore` 兜底 apk/aab/hprof，新增 `.editorconfig`，备份规则补
        //     设备保护存储四域，`file_paths.xml` 按实际交付落点最小化收紧。
        //  ⑥ **语言初选页**（用户可见的新页面）：设备语言既非中文也非英文、且用户从未选过
        //     语言时，在同意流程前插一页问一次（默认 English，可改）；中文/英文设备看不到。
        //     配套修好应用内语言的持久化闭环（此前初选页只写了 applied_tag，第二次冷启动会
        //     发现 appLocale 与它不一致而把语言抹回系统设置）。
        //  ⑦ 两轮外部审查后的修复：下载落盘判据收口（判空丢失 / IS_PENDING 幽灵行 / API 26-28
        //     孤儿占位；三处同构的 MediaStore 代码收敛为 `DownloadRepo.writeToDownloads`）；
        //     写盘失败不再回退「不带 Cookie 的二次 GET」；书签解析改单趟实体解码
        //     （`&amp;lt;` 往返不再被二次解码改写）；局域网待跳转链接不再被冷启动覆盖；
        //     弹窗守卫改到**执行瞬间**复查归属；书签解析上限的截断如实计入「跳过」；
        //     同意页在 singleTask 复用下不再吞掉随门禁转发的深链 / 分享。
        // 2.1.5：**六轮外部审查 + 自查的修复收口**（29 文件 / +1100 −237）。无新功能、无新对外
        //   端点，政策文本未变 ⇒ `POLICY_VERSION` 保持 **22**。逐条都能对应到一次具体的误判或漏判：
        //  ① 权限与生命周期：`SessionManager` 三个会弹 UI 的权限回调补齐 prompt 侧同款的入口判定
        //     与执行瞬间复查（后台标签不再抢焦点、应答不再打进已关闭的会话）；`ExtensionPrompts.Once`
        //     补「内核已先行结算」兜底，堵住二次 `complete` 抛 ISE 逃逸主线程；
        //  ② 资源释放：PDF 管道流在启动协程前先判销毁并关流（`lifecycleScope` 在 Activity 已销毁时
        //     块体不执行 ⇒ fd 泄漏）；打印适配器的整份 PDF 复制移出主线程并让取消贯穿途中；
        //  ③ 网络健壮性：默认网络客户端补 `callTimeout`，堵住慢滴 / 黑洞下的无限挂起；
        //  ④ 下载正确性：删除要**确认真删掉**才摘记录（否则孤儿文件再也删不掉）；「查不到」与
        //     「查询失败」分开（原先把查询失败当不存在 ⇒ 永久摘除记录）；
        //     `Content-Disposition` 的参数名与 charset 改为大小写不敏感；
        //  ⑤ 弹窗收口：新增 `DialogTracker` 并铺满 8 个宿主（会话不解绑 / WindowLeaked 的根因）；
        //  ⑥ 一致性：扩展安装去重键带上来源（两个不同链接 / 文件不再互相吞掉）、超时按阶段归因、
        //     「地区受限」标记只在 451 置位、书签 toggle 收进写事务、会话快照失败不再静默、
        //     清除数据的取消传播与「内核不可用」如实上报、查找条与返回键的覆盖层与高亮归属、
        //     资料库页签与搜索词的重建恢复、语言初选页重建后保留已选语言、取色对话框回填与
        //     无障碍描述、硬编码失败原因改资源串、崩溃日志过滤口径、对话框内边距 px→dp。
        //   ABI 分包 versionCode 随之派生为 381 / 382 / 383。
        // 2.1.7：**第三方审查报告 6 的收口**。无新功能、无新对外端点、政策文本未变 ⇒
        //   `POLICY_VERSION` **保持 23**（不动）。修的都是"编译通过、测试全绿也发现不了"那一类：
        //   ① 【P1】`exiting` 在「**不清除**」退出分支不复位 ⇒ `finishAffinity()` 不杀进程，同进程内
        //      「从桌面重开 → 再退出」会被早退挡成空操作：开着「退出即清除」也不清、零提示（承诺为假 +
        //      无反馈），同时跳过 `saveState()`、且 onDestroy 的关停判据恒假 ⇒ 内核再也不关停；
        //   ② 扩展安装的总超时 `onFailure` 在「超时与宿主销毁竞态」下会丢投递（**普适超时路径本身
        //      不丢**——`withTimeout` 只取消自己的子 TimeoutCoroutine，外层协程仍活跃，
        //      `withContext(Dispatchers.Main)` 照常执行；丢投递只发生在 TCE 触发后、主线程投递
        //      执行前，宿主 destroy 的 `cancelAll()` 恰好取消作业的那一刻）⇒ 投递套
        //      `NonCancellable` 并补 `runCatching` 堵住该竞态；同时把"带一次性投递闸"那句
        //      **失实的 KDoc 改成真话**（该措辞曾声称"总超时已取消本协程、投递曾永不发生"，前提错误，
        //      2026-10-02 复核更正——见 ExtInstallCoordinator 内同位置的注释）；
        //   ③ 删除自定义搜索引擎按**显示位置**算下标 ⇒ `all()` 在 id 分配后才过滤坏条目，位置会前移
        //      ⇒ 有坏条目时会**删错引擎**；改为从 `engine.id` 反解存储下标；
        //   ④ 崩溃日志「分享」失败复用了"当前没有可分享的页面"⇒ 归因错，新增 `about_crash_share_failed`；
        //   ⑤ 手动进入 PiP 不再传空参数（此前把 `updatePipParams` 刚设好的 autoEnter / 源矩形丢掉）；
        //      下载确认队列的销毁判据补 `hasOtherHost()`（不再被另一个实例的 onDestroy 整队放弃）。
        //   ABI 分包 versionCode 随之派生为 401 / 402 / 403。
        //   versionCode = 41（本文件的版本历史按这个风格记，`tools/verify_release.py` 也会去核对声明与制品一致）。
        // 2.1.6：**政策 §4 措辞与实发对齐 + 第三方全盘报告的核实与修复收口**（8 文件 + 3 个新测试类 /
        //   +387 −47）。无新功能、无新对外端点；但 §4 原写「不携带任何设备标识、账号或浏览记录」，
        //   而实发还带应用版本号（User-Agent）与设备语言偏好（Accept-Language）—— 二者都不是设备
        //   标识符，原措辞不算不实，但可能被读得比实际更宽 ⇒ 如实列出并明确「除此之外不发送其它
        //   信息」⇒ **`POLICY_VERSION` 22 → 23**（`4b27038`，2026-09-30；docs 已重导出）。
        //   ⚠️ 此前这里写的是「政策文本未变 ⇒ 保持 22」，与 `:308` 引用的 23 自相矛盾，且让版本史
        //   漏记了这次 bump（2026-10-02 第三方全量审查 P2 指出、我方回 git 取证后校正：`4b27038`
        //   在 v2.1.5 之前、在 v2.1.6 之内）。六条都是「回代码取证」后的结论：
        //  ① 【P1】双实例 MainActivity 互相关停：`singleTop` 下从外部深链 / 分享进来会压出第二个实例，
        //     而任一实例销毁都会无条件置空四个处理器并 `shutdown()` 进程级会话管理器 ⇒ 先创建的那个
        //     实例还活着却已失去内核与全部回调（地址栏 / 进度 / 权限回调失联、prompt 被自动拒绝）。
        //     改为「**按引用比对**解绑 + **宿主计数归零**才关停（计数与 isFinishing 解耦）+
        //     `onResume` 幂等重挂（含把会话画面重新挂回本实例）」。
        //  ② 【P1】无痕痕迹进崩溃日志：`noteVisit` 缺无痕守卫（同一函数里写历史那处却有），且该值住在
        //     内存里、**没有任何清除出口** ⇒ 补与写历史同口径的守卫、新增 `clearHost()`（退出无痕 /
        //     清除浏览数据各挂一处）、写盘前对堆栈做 URL 脱敏（只留 `scheme://host`，userinfo 一并剥掉）。
        //  ③ 【P2】`window.open` 可绕过 `file:` 闸门：该链最终走应用自己的 `loadUri`
        //     （isDirectNavigation=true）⇒ 内核侧的特权 scheme 闸门必然放行。抽出
        //     `UrlUtils.schemeOf / isWebNavigationScheme` 供 `onLoadRequest` 与 `onOpenInCurrentTab`
        //     **共用同一份白名单**（含 data/blob —— 只放 http/https/about 会让 `window.open('data:…')` 回归）。
        //  ④ 【P2】下载文件名按**字符**限长（150），而文件系统单段上限是 255 **字节**：
        //     150 个汉字 = 450 字节，会原样穿过限长并在 `createNewFile` 抛 `File name too long`，
        //     整单下载失败。改按 UTF-8 字节（上限 200）且**按码点整块收**（不劈开汉字 / emoji）。
        //  ⑤ 【P2】历史标题取自导航提交时刻的 `tab.title`（标题事件未必已到）⇒ 标题错一页且无处回写。
        //     改为在 `onPageStop` 补写（此时 URL 与标题都已定型、天然配对）；**不用** `onTitleChanged`
        //     —— 标题若早于导航提交到达，那时 `tab.url` 还是**上一页**，照它回写会张冠李戴。
        //  ⑥ 【自查新增】`Bitmaps.cover` 只受「填满」比例支配、不受像素闸门约束 ⇒ 极端长宽比下会先放大出
        //     数千万像素的中间图（8000×500 配 1080×2400 ≈ 9200 万像素 / 92MB）。改为**先裁后缩**。
        //  ⑦ 【依赖升级】GeckoView **155 → 157**：Mozilla 官方 product-details 显示当前 Release 通道 =
        //     157.0（FIREFOX_NIGHTLY=159.0a1、DEVEL/Beta=158.0b1），155 已落后两个 release；按「只跟官方
        //     Release 通道」的口径取 157 的最新构建。core-ktx 1.19.0 → 1.19.1；其余依赖逐一查过已是各自
        //     最新**稳定**版（AGP 9.4.1、activity-ktx 1.13.0、appcompat 1.8.0、material 1.14.0、
        //     okhttp 5.5.0、zxing 3.5.4 / 4.3.0、coroutines 1.11.0、constraintlayout 2.2.2、
        //     preference-ktx 1.2.1、compileSdk 37.2 为 stable 最高档、JDK 25）。
        //     **升级后逐条复核了原先在 155 上的取证结论**（javap：`mSession` 仍是逐视图字段且类里无静态
        //     视图注册表、`LoadRequest.isDirectNavigation` 仍在、`SessionState` 仍实现 `HistoryList`、
        //     `GeckoResult.complete` 仍抛 "result is already complete"、`open` 首指令仍是
        //     `assertOnUiThread`；omni.ja：安全浏览四条 pref 与名单服务主机
        //     `firefox.settings.services.mozilla.com` 与 155 **完全一致**）⇒ 政策 §4 的措辞与
        //     `POLICY_VERSION = 23` 都**无需再动**（主机没变，不构成新的披露变化）。
        //     ⚠️ Gradle wrapper 9.6.0 → 9.8.0 有更新但**刻意未升**：与本次内核升级无关，且会动到
        //     `gradle-daemon-jvm.properties` 那套已验证的组合，留到专门做构建链升级那一轮再一起做。
        //   测试 181 → **248**（+5 类：`CrashLoggerRedactTest` / `SchemeGateTest` / `BitmapsCoverRectTest` /
        //   `内核版本与依赖目录一致` / `TestCountCommentTest`；再 +2 类：
        //   `GeckoVersionTextConsistencyTest` / `GeckoEgressOverrideConsistencyTest`；
        //   2026-10-01 为后者补了一条「必须是 YAML 形状」的断言 ⇒ +1；
        //   2026-10-02 为扩展源判据（`isAmoTarget`，含反斜杠换主）与下载 MIME 归一（`bareMimeType`）
        //   各补 1 例 ⇒ +2；2026-10-02 外部审查报告 3 为文件名双向控制符补 2 例、再为
        //   `BrowserDbLimitGuardTest`（新类，3 例）⇒ +5；
        //   2026-10-03 新增 `TouchTargetSentinelTest`（新类，3 例：未豁免违规 / 豁免须有理由 /
        //   豁免表不得留已修好的条目）⇒ +3），
        //   2026-10-03 另为 `data:` / `blob:` 维持进搜索、以及裸 `about:` 边界各补 1 例
        //   （这两条取舍此前**只有注释、没有断言**，等于没有门禁）⇒ +2），
        //   2026-10-15 为隐藏扩展名的格式控制字符补 2 例（Cf/Cc 按 Unicode 类别删除、
        //   普通空格**不在**删除之列）⇒ +2；同日主线加 `useLegacyPackaging` 与门禁第 ⑩ 项，
        //   同日再加 `ReleaseGateConsistencyTest`（4 例：两张 ABI 表必须一致、flavor 名与产物文件名口径、
        //   ⑩ 依赖的 padding 脚本在位且阈值未动、`useLegacyPackaging` 与其说明在位）⇒ +4），
        //   同日再加 `BridgeSignatureParityTest`（3 例：每个 Kotlin `external fun` 都有同名同形参数
        //   个数的 Rust 导出、白名单之外的新孤儿、重名与 `tan_1sno` 转义及 `#[no_mangle]` 配对）⇒ +3。
        //   ⚠️ 本行只数**共享源集** `app/src/test`：coreRust 专有的 `app/src/testCoreRust`
        //   （`CoreBridgeDecodeTest`，4 例）不在其内 —— 那些用例引用只存在于一个 flavor 的符号，
        //   在 coreKotlin 里根本编译不出来，把它们计入会让这个哨兵自己变成假话。
        //   同日整理轮再加 3 例：`ReleaseGateConsistencyTest` 2 例（随包 `.so` 只有一份真相 —— flavor
        //   三颗在位 + `tangsnow-rs/.gitignore` 的 `/dist/jniLibs/` 规则在位 + 落点若有产物必须与
        //   flavor 逐字节相同；pre-flavor 残件不得回到仓库）与 `ProguardLogRuleConsistencyTest` 1 例
        //   （coreRust flavor 源集的 release 日志与 proguard 注释里的「另计 w ×0、e ×3」一致）⇒ +3，
        //   2026-10-16 再加 `SearchEngineTableParityTest`（3 例：内置引擎表的 id/模板/queryParam/顺序、
        //   主机别名表、`DEFAULT_ID` 与 `CUSTOM_ID_PREFIX` 两个常量且默认 id 在表内 —— 这两张表在
        //   Kotlin 与 Rust 里**各抄一份**、此前无人比对，初跑即抓到 Rust 少登记一行 `"bing" → bing.com`）
        //   ⇒ +3；同日扫描轮再加 `PercentEncodeSetParityTest`（2 例：百分号编码的「未保留标点集」在
        //   UrlUtils / Rust percent_encode / CoreNative 兜底实现**三处拷贝**必须同集合，且两侧的
        //   十六进制表都是大写 `0123456789ABCDEF`）⇒ +2；再加 `CrossLanguageLiteralParityTest`
        //   （10 例：scheme 三张表、可执行扩展名表、历史跳过前缀、兜底文件名，再加书签实体正则、
        //   崩溃日志的两个正则、CJK 截断字符类、链接尾随标点表 —— **并排读两侧源码**比对，
        //   因为原先 `tests/*_parity.rs` 那批是**镜像**用例，改一侧清单它照绿，属假覆盖）⇒ +10；
        //   同日再加 `文档与CI里的 gradlew 命令都带 flavor`（1 例：扫所有可照抄的 `./gradlew` 行，
        //   出现不带 flavor 的 lintDebug/testDebugUnitTest/assembleRelease 就红 —— 实测这两个任务名
        //   在 flavor 维度之后已解析不了，而文档与 PR 模板里 5 处正是那么写的，已一并改对）⇒ +1，合计 **273**。
        //   同日纪律轮再加 1 例：`ReleaseGateConsistencyTest.重编 so 的脚本仍带着宿主探测 16K 对齐与 profile
        //   拷贝这三处修复` —— `tangsnow-rs/tools/build-android.sh` 是三 ABI `.so` 唯一的产出入口，而它跑在
        //   **另一台 Linux**（本机没有 Rust 的 android target），所以它坏了本地一声不响：现在钉住
        //   「uname 探宿主 + NDK_HOST_BIN 可覆盖 + 零/多候选都 exit 1」（L1）、`max-page-size=16384`
        //   （少了它在 16KB 页设备上被 PackageManager 拒收）与「三行 cp 跟 `$PROFILEDIR`」⇒ +1，合计 **274**；
        //   2026-10-18 门禁轮再加 1 例：`ReleaseGateConsistencyTest.第 ⑪ 项的 ELF 页对齐门禁在位且范围
        //   没被悄悄改掉` —— 钉住 ⑪ 的四个**实测出来的**设计决定（参评 ABI 只有 arm64-v8a/x86_64、
        //   只拦自家 `libtangsnow_core.so`、阈值 16384、判定函数被 main() 真调用且不足就 raise Fail），
        //   再加「夹具不少于 5 档」⇒ +1，合计 **275**；
        //   同日再加 1 例：`ReleaseGateConsistencyTest.tangsnow-rs 的测试数与 README 声明一致，且 README
        //   点名的差分文件都在` —— `tangsnow-rs/README.md` 自己写着「Rust 侧计数没有被 TestCountCommentTest
        //   钉住」，那句「N/N」一直是**手抄**：本例数仓库里的 `#[test]`（排除 `target/`）与它比对，
        //   并顺带拦住"新增 `tests/*.rs` 忘了 `git add`"（`regression_2026_10_17.rs` 落地时正是未跟踪）⇒ +1，
        //   2026-10-19 为 `BridgeSignatureParityTest` 再加 1 例：**随包 `.so` 的导出符号**必须与 Kotlin
        //   的 `external fun` 声明一一对应（直接解析 `jniLibs/` 三颗 ELF 的动态符号表）—— 上面几条比的
        //   是**两份源码**，改了 `external fun` 却忘重编 `.so` 时源码层照绿，真机则是静默降级
        //   （`guard` 兜底、无异常无日志）；`CoreNative.kt` 自己也写着这类漂移「不能指望真机给信号」，
        //   故补产物层红灯。边界：只查**符号名**，查不了形参个数/类型（JNI 对非重载方法按短名查找，
        //   3 参变 4 参仍能绑定）⇒ +1，
        //   合计 **277**；2026-10-19 主题↔内核颜色方案轮再加 1 例：`ColorSchemeWiringTest`（1 例：`Theme`
        //   三档与 `COLOR_SCHEME_*` 逐一对应、`runtime()` 的 Builder 链与 `applyLiveSettings()` 两处重套
        //   调用在位、热改 key 含主题 —— 修的是「App 主题只驱动视图层、内核不知道」的割裂）⇒ +1，
        //   2026-10-19 崩溃提示策略轮再加 1 例：`GeckoRuntimePolicyWiringTest`（1 例：内核
        //   `crashPullNeverShowAgain(true)` 策略在 `runtime()` 的 Builder 链里在位；并在类文档里留痕
        //   「翻译入口 G2 为何**不做**」的 AAR 取证，防日后反复）⇒ +1，
        //   2026-10-19 崩溃日志脱敏补齐轮再加 5 例（`CrashLoggerRedactTest`：`mailto:` / `data:` 这类
        //   **无 `//`** 的 scheme、`token=…` 键值对凭据、`Bearer …` 认证方案后的凭据、以及整链幂等与
        //   裸路径不误伤 —— 堵的是「同一份异常 message，debug 被 `BuildConfig.DEBUG` 拦住、release 却
        //   把凭据原样落盘」这两条出口的强度不一致）⇒ +5，
        //   2026-10-19 同轮再收紧一层 `CREDENTIAL_PAIRS`（固定词表 → 「含敏感词的标识符」，左侧边界放宽到
        //   非字母数字、并容许 JSON 引号键）⇒ 复合/粘连凭据名（`client_secret` / `id_token` / `secret_key` /
        //   `clientsecret` 等 13 种形态）不再漏网，`CrashLoggerRedactTest` 再 +4 例 ⇒ 合计 **288**；
        //   2026-10-19 同轮再堵下载「干净断流报成功」：为长度收尾校验（`expectedBodyLength` /
        //   `bodyLengthMismatch`，外部审查 P4-4 / 待办 #31）加 `DownloadBodyLengthTest` 14 例 ⇒ 合计 **302**。
        //   2026-10-19 A1 轮（Rust 独立应用身份 `.rust`）：为更新链通道判定加 `UpdateCheckerTest.channelOf`
        //   3 例（正式线 / 预览线 / `rusty` 同前缀仍判正式线）⇒ 合计 **305**。
        //   2026-10-20 审查修复轮：为书签标题跨语言截断（B-2）加 `BookmarkHtmlTest`（新类，1 例：
        //   JVM 侧按**码点**截断，与 Rust `chars().take(200)` 同口径）⇒ +1；
        //   同轮为书签非 ASCII 搜索（A-3）加 `BookmarkSearchTest`（新类，10 例：ASCII/非 ASCII 分流、
        //   CJK 字面量精确匹配、有大小写变体的字符用通配兜底、`i`/`k` 的 exotic 折叠兜底、
        //   LIKE 元字符转义、组合上点放弃预筛、模式宽度恒等于关键词长度、应用侧精确判定与旧内存过滤一致、
        //   **预筛是精确匹配集合的超集**（不变式，配一份最小 LIKE 实现当判据）、以及反向抽查预筛不过度放行）⇒ +10，
        //   同轮为对外法律文档同步（A-4）加 `LegalDocsSyncTest`（新类，3 例：导出文档含应用内中英文原文、
        //   文档标注的政策版本与应用内一致、文档标注的更新日期与文案首行一致 —— 对外那两份是
        //   `tools/export_legal_docs.py` 的导出物，忘了重导出就只剩对外那份静默过期）⇒ +3，
        //   同轮为法律粗体跨语言索引口径（B-1）加 1 例：emoji 前置时区间仍按 **UTF-16 码元**计
        //   （Rust 侧已由「按码点计」改成 `encode_utf16().count()`，两侧同口径；设备侧另补
        //   `W2SeamDeviceTest` 一条，让 `LegalText.parse` 的 native 合并分支真正被执行到）⇒ +1，
        //   同轮为触摸代理命中区（C-8）加 1 例：`TouchTargetSentinelTest` 钉住「TouchDelegate 的命中矩形
        //   必须在**布局后**算」（原先用 `post {}`，在 attach 阶段执行时坐标还是 0 ⇒ 命中区退化成
        //   卡片左上角的隐形破坏性热区）⇒ +1，
        //   同轮为地址栏伪装面（C-3）加 1 例：`SearchEnginesTest` 钉住「子域不再被通配命中」
        //   （Rust 侧同步改成只认模板主机 + 显式登记的别名主机，并补「官方结果页主机仍全部命中」）⇒ +1，
        //   同轮为零断言用例（C-12）加 1 例：`ReleaseGateConsistencyTest` 钉住「每个 `#[test]` 都必须有断言」
        //   （`bench_smoke.rs` 曾经只打印不判定、却照样计入 README 声明的测试数）⇒ +1，
        //   同轮把 5 对跨语言差分用例改为**两侧共享 JSON 夹具**（C-14：原先两侧各写一份期望值、
        //   改实现 + 同步改镜像期望值 ⇒ 两侧皆绿而行为已漂）：`legal_text` / `crash_logger` /
        //   `bitmaps` 采样 / `search_engines` / `download_filename` 五对改完后，
        //   `LegalTextTest`（7→2）、`CrashLoggerRedactTest`（9→9，改为按 id 查夹具）、
        //   `BitmapsSampleSizeTest`（7→2）、`SearchEnginesTest`（10→2）、
        //   `DownloadFileNameTest`（26→17）⇒ 合计 −31，
        //   合计 **287**。
        //   2026-10-22 收口（C-14 剩余 5 对 + 最后 1 处镜像）：`url_utils` / `bookmark_html` /
        //   `bitmaps` 裁剪 / `history` / `url_idna` 五对也改用共享夹具（新增
        //   `HistoryRepoSkipUrlTest`(新类,1 例) 与 `UrlIdnaParityTest`(新类,15 例)），
        //   `UrlUtilsTest`（30→3）、`SchemeGateTest`（6→2）、`PercentEncodeTest`（10→2，
        //   搜索模板那 5 条镜像并入 `search_engines` 夹具）、`BookmarkHtmlTest`（24→11）、
        //   `BitmapsCoverRectTest`（6→3）⇒ 合计 −39，
        //   合计 **248**。
        //   （上一版这里被我写成 274 —— `TestCountCommentTest` 当场把这句注释本身钉红了，钉子有效。）
        //   ⚠️ 本行数字由 `TestCountCommentTest` 钉住：改测试数量必须同步这里，否则两个 flavor 的单测任务
        //   （`:app:testCoreKotlinDebugUnitTest` / `:app:testCoreRustDebugUnitTest`）都直接红。
        //   10-15 那两条新哨兵当时已主动验证会红（临时改坏 ⇒ 6 条 FAILED）；此后每加一条读源文件的
        //   哨兵都单独跑过变异，台账逐条记了「哪个变异红在哪条用例」。
        //   ⚠️ lint 口径更新（2026-10-02）：`checkAllWarnings = true` 下现有一条**常驻**的
        //   `AndroidGradlePluginVersion` Warning（wrapper 9.6.0 → 9.8.0 的升级提示）——wrapper
        //   刻意不升（见上方版本史），该提示已在 lint.xml 按检查项豁免并写明理由；除此之外仍
        //   `No issues found`。门禁口径据此为「零错误 + 零未豁免警告」。
        // 2.2.0：**修「点同意即闪退」** —— 2.1.6/2.1.7 的致命回归（用户报告：两版都在同意页点
        //   「同意」即闪退，2.1.5 正常）。根因已定位到具体机制：为关闭内核未披露出网而新加的
        //   `GeckoRuntimeSettings.Builder.configFilePath(...)` 在 Android 上**必然失败** —— GeckoView 157 的
        //   `DebugConfig.fromFile` 走 snakeyaml 2.2 的 JavaBean 路径（`Constructor(DebugConfig.class, …)`），
        //   而 Android **没有** `java.beans.Introspector`（`android.jar` 里只有 6 个 `java.beans.*`）⇒
        //   抛 `NoClassDefFoundError`；`GeckoRuntime` 只 catch `ConfigException`/`FileNotFoundException`
        //   ⇒ 异常逃出运行时创建，又被 `warmUp` 的 `runCatching` 吞掉 ⇒ **运行时没建成** ⇒ 主界面建
        //   `GeckoSession` 时崩。修法：① 移除该注入（Android 上不可用，且即使解析成功也会被
        //   catch-and-ignore、覆盖从不生效）；② `warmUp` 改为如实返回是否成功，失败时在同意页给出
        //   提示而不是带着空运行时进主界面。**versionCode 41（ABI 派生 411/412/413）**。
        // 2026-10-22 内核升级：GeckoView **157 → 158**（`158.0.20261021001945`）。
        //   口径与上次一致：Mozilla 官方 product-details 当前 Release = 158.0.1（Beta=159.0b5、
        //   Nightly=160.0a1）⇒ 取 158 的最新构建。**官方性三层核对**：maven.mozilla.org 的
        //   maven-metadata 列明该版本；AAR 的 sha1（`96b4899c…`）/md5（`593bc303…`）与公布值逐字相同；
        //   包内 `libxul.so` 自报构建号 20261021001945 且含 `rv:158`。
        //   **升级面复核**：`javap -public` 对 23 个实际用到的类只有一处**新增**
        //   （`GeckoResult<Bitmap> captureFullPage()`），无删除/改签名；157 那五条取证结论逐条仍成立
        //   （mSession 字段数 2、already-complete 文案、`open` 字节码前缀、assertOnUiThread 42 处，
        //   两版逐字相同）；omni.ja 的安全浏览 8 条 pref、翻译开关、崩溃上报地址与设置服务主机
        //   （webextensions.settings.services.mozilla.com）与 157 **完全一致** ⇒ 不构成新的披露
        //   变化，`POLICY_VERSION` 维持 23。对外声明同步：THIRD_PARTY_NOTICES（中英）、
        //   `open_source_text`（中英）、README、ARCHITECTURE（中英）。
        // 2.3.0（2026-10-23）：**自 2.2.0 以来的累积版本**，无新增功能面，两处实质变化：
        //   ① 内核 157 → 158（见上一条，官方性四重核对）；② 文本与口径修正 —— 对外文档的
        //   ABI 派生号（411/412/413 → 421/422/423）、下载表 APK 文件名补 flavor 前缀
        //   （`app-coreKotlin-<abi>-release.apk`，2.2.0 发布时还没有 flavor 维度，旧名对不上
        //   本版产物）、ARCHITECTURE 测试规模对齐实测（248/255 · 169 · 25）。
        //   2.2.0 之后合入的 69 笔里还含：审查报告 002 的 11 条修复、门禁第 ⑫ 项（产物不得含
        //   构建机路径）、C-14 镜像差分夹具化、SessionManager 弹窗委托拆解、Rust 侧夹具
        //   mtime 陈旧导致的假红取证。**versionCode 42（ABI 派生 421/422/423）**。
        //   `POLICY_VERSION` 维持 23：内核升级未改变出网对象与披露面（omni.ja 安全浏览 pref
        //   与主机两版逐字一致，已实测），文本修正不涉及政策条款。
        versionCode = 42
        versionName = "2.3.0"
        // 仪器测试（2026-10-19 加回）：`app/src/androidTestCoreRust` 下有一道 JNI 边界断言
        // （`libtangsnow_core.so` 是否真的加载 + 7 个 W1 函数是否绑定到**当前**签名）。
        // 它只在**设备**上跑，是「Rust 接管」在真机上唯一的硬证据 —— JVM 单测里
        // `CoreNative.available` 恒为 false，所有桥函数在进 native 之前就返回安全默认值。
        // 依赖只加 runner、不引 espresso：断言是普通 JUnit4，没有 UI 驱动需求。
        // 原先「不声明 + 不加依赖」的两条理由（没有 androidTest 源码、空转依赖）随本次加源码一并失效。
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    // 资源语言过滤：只保留中文与英文的字符串变体。第三方库（AndroidX/Material/zxing）
    // 默认携带 ~80 种语言的翻译；shrinkResources 只按「资源名是否被引用」裁剪，
    // 被引用字符串的各语言变体全保留 —— 不过滤的话，在应用强制的中英语言模型下，
    // 库字符串（zxing 取景页、Material 对话框按钮等）会按系统语言冒出来，与应用内
    // 语言选择不一致。⚠️ 该列表按**精确资源配置**匹配：库只带 values-zh-rCN/rHK/rTW
    // （无裸 zh），只写 "zh" 会把库的中文全部剥掉（中文设备上库字符串退英文）——
    // 必须逐个列出；裸 zh 一并保留是防御（个别库可能带 values-zh）。
    // AGP 9 的新 DSL（取代 defaultConfig.resourceConfigurations）。
    androidResources {
        localeFilters.addAll(listOf("en", "zh", "zh-rCN", "zh-rHK", "zh-rTW"))
    }

    // ABI 拆分：GeckoView 原生库（.so）约占包体 86%，按 ABI 独立出包可大幅瘦身；
    // 不生成包含全部 ABI 的 universal APK（体积巨大且无必要）。
    // ⚠️ 存储形态见下方 packaging —— 是 deflate，不是未压缩存储（2026-10-15 改，原因见那段）。
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    // 内核实现维度（2026-10-15，Rust 线转正的第一步）：
    //  · coreKotlin —— 纯 Kotlin 实现，**主线默认**，产物与今日完全等价；
    //  · coreRust   —— 同一批纯逻辑改由 `libtangsnow_core.so`（Rust）承担，源码在仓库根的
    //                  `tangsnow-rs/`，`.so` 与本 flavor 的桥接代码放 `app/src/coreRust/`
    //                  （flavor 的 java/ 与 jniLibs/ 按约定目录自动接管，无需 sourceSets 声明）。
    // ⚠️ 两 flavor 现为**两个独立应用身份**（2026-10-19 A1）：
    //    · `coreKotlin` 沿用 defaultConfig 的 `io.github.tan_sno.tangsnow`（已发布身份，不可动）；
    //    · `coreRust` 带 `.rust` 后缀 ⇒ 同一台设备上**可同时安装、数据互不干扰、各自升级**。
    //    namespace 两 flavor **相同**（类名与组件全名不变），只有 applicationId 分叉；因此
    //    `tools/verify_release.py` 的 EXPECTED_PACKAGE 与「动态接收器权限」这两处必须**按 flavor
    //    分设**（已同步）。发布纪律：两身份各自独立发布，Rust 用 `v<ver>-rust-preview` tag。
    //    现在 coreRust 仍只是「能编译、能打包、`.so` 进得了包」的骨架，逐函数接线见 git 提交历史。
    flavorDimensions += "core"
    productFlavors {
        create("coreKotlin") {
            dimension = "core"
        }
        create("coreRust") {
            dimension = "core"
            // A1：Rust 独立应用身份 —— 与 Kotlin 包同机共存、数据隔离（namespace 不变）
            applicationIdSuffix = ".rust"
        }
    }

    // ⚠️ useLegacyPackaging = true 是从 Rust 线 2026-10-07 安装攻坚搬来的**修复项**，删前必读：
    //
    // 症状：release 包在真机报「解析包出现问题」；API26 模拟器复现为
    //   INSTALL_FAILED_NO_MATCHING_ABIS / "Failed to extract native libraries, res=-113"。
    // 根因：AGP 为 16KB 页设备做 zip 层对齐时，把对齐 padding（~14KB）写进 `.so` 条目的
    //   **local header extra field**。Java 的 ZipFile / Python zipfile 都读得动，但安装器的
    //   C++ libziparchive 在提取阶段校验 local extra 失败 ⇒ -113。对照实验（同一产物仅改
    //   padding）：16K padding 版必败，`zipalign -P 4` 重写版（padding 1734B，与官方 1878B
    //   同量级）必成 —— 证据闭环，不是猜测。
    // 修复：`useLegacyPackaging = true` ⇒ .so 以 deflate 压缩存储、安装时解压提取，完全绕开
    //   local-header padding。代价：安装期多一步解压、/data 双份占用；APK 体积反而略降。
    //   运行时 16KB 页兼容不受影响 —— 内核 .so 本身是 4K 对齐，提取后照常映射。
    // 历史对照：2.1.6（4K 时代，padding 小）可装；**2.2.0 全系列（16K padding）全败**。
    //   本地 app/release/ 那三颗已发布产物实测 13 个 .so 全部 stored、8 个 padding
    //   8574–15506B ⇒ 正是这一形态。把关：tools/verify_release.py 第 ⑩ 项 + tools/check_zip_padding.py。
    packaging {
        jniLibs {
            useLegacyPackaging = true
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
                        + "请同步本表与 tools/verify_release.py 的 ABI_ORDER / abi_splits(--flavor)，"
                        + "或恢复 isUniversalApk = false。"
                )
            output.versionCode.set(output.versionCode.get() * 10 + offset)
        }
    }
}

// 跨语言**共享夹具**（`tangsnow-rs/tangsnow-core/tests/fixtures/*.json`）是 JVM 单测的**运行时输入**：
// Kotlin 侧由 `SharedFixtures` 在用例执行时读文件，Gradle **看不见**这条依赖 ⇒ 只改夹具时
// `:app:testCoreKotlinDebugUnitTest` 判为 UP-TO-DATE、**根本不重跑**，于是「改了夹具却没跑测试」
// 看起来一切正常（2026-10-22 实测撞到过：夹具被改成另一个值，Gradle 直接跳过，绿得毫无意义）。
// 声明成 test input 之后，改夹具必然重跑。
// Rust 侧不受这条影响：`include_str!` 已登记依赖，cargo 按 mtime 判新鲜度。
tasks.withType<Test>().configureEach {
    inputs.dir(file("../tangsnow-rs/tangsnow-core/tests/fixtures"))
        .withPropertyName("tangsnowSharedFixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// 第三道签名闸门：keystore.properties **整个缺失**时 release 静默回退 debug 证书
// （文件存在但凭据无效/占位符的情形已由配置期与执行期闸门硬失败）。回退本是为
// 贡献者 clone 后能构建，但「assembleRelease 的名字暗示可分发」与 debug 签名产物
// 的两面待遇不一致 —— 现在缺失时也默认硬失败，明确传
// -Ptangsnow.allowDebugSignedRelease 才放行（贡献者本地自查用；产物仍不可分发）。
// doFirst 而非配置期：贡献者跑 assembleDebug / 测试时完全不受影响。
// ⚠️ 与其他两道闸门同口径：**向导驱动时跳过**（`-Pandroid.injected.signing.*` 就是真凭据，
// 而向导不产生 keystore.properties）—— 缺这一条会把"全新 clone + 向导"这条合法路径拦下
// （2026-10-01 修正）。本机不受影响（该文件存在），此改动只影响"文件缺失 + 向导"这一种组合。
val allowDebugSignedRelease =
    providers.gradleProperty("tangsnow.allowDebugSignedRelease").isPresent
// 匹配口径：凡「以打包动词开头、且属于 release 变体」的任务都纳入（CR-008）。
// 原先只认 assembleRelease / bundleRelease 两个名字。而本项目开了 ABI 分包，
// 真正**产出** APK/AAB 的是 `packageReleaseUniversalApk` / `packageReleaseBundle`
// （`assembleRelease` 只是挂在它们上面的汇总任务）⇒ 熔断在真正产包的那一步形同虚设。
// ⚠️ 不用 `endsWith("Release")`：那会命中 compileReleaseKotlin / lintVitalRelease /
// testReleaseUnitTest 等根本不产包的编译、检查任务，把熔断变成噪音（见 design 风险表）。
// 这里锚定「打包动词 + 提到 Release」，实测（`:app:tasks --all`）覆盖
//   assembleRelease / bundleRelease / packageRelease / packageReleaseUniversalApk /
//   packageReleaseBundle / installRelease
// 而 compileRelease* / lint*Release / test* 一律不命中；debug 任务名不含 Release，同样不命中。
val releasePackagingTask = Regex("^(package|assemble|bundle|install).*Release")
tasks.matching { releasePackagingTask.matches(it.name) }.configureEach {
    if (!keystorePropsFile.exists() && !allowDebugSignedRelease && !wizardDriven) {
        doFirst {
            throw GradleException(
                "未找到 keystore.properties：release 产物将回退 debug 证书签名，不可分发。"
                    + "请配置真实凭据后构建；或（仅本地验证时）显式加 "
                    + "-Ptangsnow.allowDebugSignedRelease 跳过本闸门。"
            )
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
    // JVM 单元测试（app/src/test）
    testImplementation(libs.junit)
    // 仪器测试（app/src/androidTestCoreRust，2026-10-19 加回，理由见 defaultConfig 注释）
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
    // android.jar 的 org.json 在 JVM 测试里是抛 Stub! 的桩；用 Maven 真实实现覆盖
    // test classpath（仅测试可见，不进产物），SessionStore 的 JSON 往返才能被单测。
    testImplementation(libs.json)
}
