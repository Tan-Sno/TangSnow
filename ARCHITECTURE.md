# 架构总览 · 棠雪 / TangSnow

> 面向**接手本项目的人**：讲清结构、数据流与设计取舍。
> 与 `README` 的分工 —— README 讲**用途**（怎么装、怎么用），本文讲**内部结构**。
> 本文只写**已核实**的事实（读源码 / `settings.gradle.kts` / `AndroidManifest.xml` / 门禁脚本）；
> 未核实的一律显式标注，不臆断。
> 英文版：[`ARCHITECTURE.en.md`](ARCHITECTURE.en.md)。
> **改 flavor / 接缝 / 门禁编号时请同步本文。**

---

## 1. 一句话定位

**一款以隐私为第一优先、数据纯本地的 Android 浏览器**。渲染内核用 Mozilla GeckoView（158）；
业务逻辑可选走纯 Kotlin 或 Rust（JNI 桥）两条实现，由构建 flavor 选择。

## 2. 仓库结构

| 路径 | 是什么 |
|---|---|
| `app/` | Android 应用（Kotlin，`minSdk 26` / `targetSdk 37`） |
| `tangsnow-rs/` | Rust 核心源码（**不参与 Android 构建**，产物经 flavor 的 `jniLibs/` 进包） |
| `tools/` | 发版门禁脚本（`verify_release.py`、`check_zip_padding.py`） |
| `.github/workflows/` | CI（两 flavor 单测 + lint + 门禁自检） |
| `gradle/` | 版本目录（`libs.versions.toml`）与 daemon JVM 钉版 |

**凭据不入库**：签名配置在 `keystore.properties`（被 gitignore），任何 release 任务在缺凭据时直接失败
（见 §10 签名三闸门）。

## 3. 运行时分层

```
┌───────────────────────────────────────────────┐
│ UI 层   Activity / Fragment / Controller       │  纯 Android View（无 Compose）
│   ConsentActivity · MainActivity · Settings…   │
├───────────────────────────────────────────────┤
│ 浏览器层  browser/ + GeckoHolder               │  唯一碰 GeckoView 的地方
│   SessionManager · Tab · 各 Delegate 接口       │
├───────────────────────────────────────────────┤
│ 数据层  data/ + data/repo/                     │  本地持久化（Room + SharedPreferences）
│   PreferenceStore · BrowserDb · SessionStore    │
│   BookmarkRepo · HistoryRepo · DownloadRepo     │
├───────────────────────────────────────────────┤
│ 工具层  util/                                   │  无状态纯逻辑（URL/位图/脱敏/法律文本）
├───────────────────────────────────────────────┤
│ 扩展/更新  extension/ · update/                │
├───────────────────────────────────────────────┤
│ 内核层  GeckoView 158（libxul.so，独立进程）    │  Mozilla MPL 2.0
└───────────────────────────────────────────────┘
        ↑ core flavor 决定业务逻辑的**实现语言**（Kotlin / Rust-JNI）
```

**依赖方向单向向下**：UI → 浏览器层 → 数据/工具层；内核层由浏览器层独占持有，其它层不直接引用 GeckoView 类型。

## 4. 包清单与职责（57 文件 / 8 包）

| 包 | 文件 | 职责 | 代表类 |
|---|---|---|---|
| `tangsnow`（根） | 17 | 各 Activity + 顶层控制器 | `MainActivity`(3155 行)、`SettingsFragment`、`SuggestionsController`、`FindBarController`、`TangSnowApplication` |
| `tangsnow.browser` | 1 | **浏览器层唯一文件**：会话/标签/权限/内容委托 | `SessionManager`(2030 行，含 `TabEvents`/`PromptHandler`/`PermissionHandler`/`SelectionHandler` 四个接口) |
| `tangsnow.data` | 12 | 持久化与全局状态 | `PreferenceStore`、`BrowserDb`、`SessionStore`、`Theme`、`SearchEngines`、`LegalDocs`、`AppHttp` |
| `tangsnow.data.repo` | 4 | 三个仓 + 应用作用域 | `BookmarkRepo`、`HistoryRepo`、`DownloadRepo`、`ApplicationScope` |
| `tangsnow.util` | 11 | 无状态工具 | `UrlUtils`、`Bitmaps`、`CrashLogger`、`GeckoResultExt`、`SecureScreen` |
| `tangsnow.ui` | 8 | 可复用 UI 片段 | `Adapters`、`SheetBuilders`、`WebPrompts`、`HomeTiles`、`ScanImageActivity` |
| `tangsnow.extension` | 3 | AMO 扩展目录与安装 | `ExtensionCatalog`、`ExtInstallCoordinator`、`ExtensionPrompts` |
| `tangsnow.update` | 1 | 应用内更新检查（GitHub Releases） | `UpdateChecker` |

> 「根 17 / browser 1」的对比即本项目的设计取向：**浏览器内核交互全部收在一个 `SessionManager` 里**，
> 而不是散进各 Activity —— 这也是它能被单测/元测试钉住的前提。

## 5. 渲染内核集成（GeckoView）

- **唯一持有者**：`GeckoHolder`（运行时/全局设置）+ `SessionManager`（每标签一个 `GeckoSession`）。
- **会话级设置**（`SessionManager.buildSessionSettings`，建会话时一次性生效）：
  `usePrivateMode` / `useTrackingProtection` / `allowJavascript` / `userAgentMode` / `viewportMode` / `suspendMediaWhenInactive`。
- **可热改项**：仅 `allowJavascript` + 内核级 `fontSizeFactor`；UA/视口/跟踪保护档位**刻意不热改**
  （中途改会输入错乱/半生效，切换走"重建会话"）。
- **运行时级设置**只在一处构造（`SessionManager.runtime()`）：内容拦截档位、GPC 信号、指纹保护，
  其余 40 余项走 GeckoView 默认值。
- **内核安全浏览远程查询已关闭**（该能力需连 Mozilla/Google，见 `buildContentBlocking` 内注释）；
  `libxul.so` 以 deflate 压缩存储（`useLegacyPackaging`，与 §10 门禁⑩一致）、按 ABI 分包。

## 6. `core` flavor 与 JNI 接缝（**已接线**：W1 7 桥 + W2 11 桥 = 18 桥）

应用有一维 product flavor：`core`。

| flavor | 状态 | 实体 |
|---|---|---|
| `coreKotlin` | **实际发布通道**（纯 Kotlin 实现） | 逻辑在 `src/main`（如 `UrlUtils`、`BookmarkHtml`） |
| `coreRust` | **预览**（同一批逻辑走 JNI） | `src/coreRust/`：`CoreNative.kt` + `CoreBridge.kt` + `jniLibs/{arm64-v8a,armeabi-v7a,x86_64}/libtangsnow_core.so` |

**已核实的关键事实（与常见误解相反）**：
- `coreKotlin` **没有**专属 source set（逻辑就在 `src/main`）；`coreRust` **只新增** 3 个 Kotlin 文件（`CoreNative`/`CoreBridge`/`CoreImpl`）+ 3 颗 `.so`。
- `src/main` 里 **没有任何一处**引用 `CoreNative`/`CoreBridge` ⇒ **接缝是编译期分派、不是运行时 if**：
  两 flavor 各提供一份同名同签名的 `internal object CoreImpl`，`src/main` 的调用点**一个字不改**
  （继续调 `UrlUtils.resolveInfo` 这类门面），由**编译器**当闸门（少一个 flavor 就是编译失败）。
- `CoreImpl` 的存在位置：`app/src/coreKotlin/java/io/github/tan_sno/tangsnow/core/CoreImpl.kt` 与 `app/src/coreRust/java/io/github/tan_sno/tangsnow/core/CoreImpl.kt`。
- **已接线的桥共 18 个**：W1 七个单值纯字符串函数（`resolveInfo` / `looksLikeUri` / `schemeOf` /
  `isPortableUrl` / `percentEncode` / `searchUrl` / `displayForUrl`）+ W2 十一个带落盘/隐私/往返语义的
  （下载文件名 4、崩溃日志 2、书签 JSON 三件、历史入库过滤 1、法律粗体区间 1）。

**`guard` 契约（`CoreNative`）**：`available` 在类初始化时急切求值（`loadLibrary` 失败=false、不抛）；
`available=false` 时所有 `*Impl` 返回安全默认值、不触碰 native；调用方用 `nullIsResult` 区分"正常空"与"失败"。
`CoreImpl` 这一层**先问 `available`**、不可用一律返回 `null`（= 不接管）⇒ JVM 上 coreRust 与 coreKotlin
**逐字节一致**，这正是共享单测能在两个 flavor 上各跑一遍的前提。

## 7. Rust 核心（`tangsnow-rs/`）

- **定位**：不参与 Android 构建的源码档案；产物只经 `app/src/coreRust/jniLibs/` 进 flavor。
- **13 个模块**：`url_utils` `percent_encode` `search_engines` `download_filename` `bookmark_html` `history`
  `legal_text` `bitmaps` `dt_prompts` `crash_logger` `update_logic` + `jni`（导出层）+ `lib`。
- **导出**：34 个 `Java_io_github_tan_1sno_tangsnow_core_CoreNative_*`（其中 3 个为已知孤儿，白名单外新增即红）。
- **测试**：15 个 `tests/*.rs`（14 个测试二进制 + 共享读取器 `common/mod.rs`），含逐模块 `*_parity.rs`
  （Kotlin↔Rust 行为对齐）+ `jni_boundary_parity` + `properties_fuzz`。
  `cargo test --offline` = **169 例**。其中**十对**已改用**两侧共享的 JSON 夹具**
  （`tests/fixtures/`，Kotlin 侧读同一批文件 ⇒ 改夹具必然两侧一起红）；
  其余是**单侧**用例（源码级哨兵、性质、回归批），刻意只有一侧，见 `tangsnow-rs/README.md` 的说明。
- **两条语言的等价性**由元测试守：`CrossLanguageLiteralParityTest`、`PercentEncodeSetParityTest`、`BridgeSignatureParityTest`。

## 8. 数据与隐私边界

- **全本地**：书签/历史/下载记录/主页定制/站点权限决定都在本机（Room + SharedPreferences）；无账号、无统计、无追踪 SDK。
- **应用自身网络的对外端点共三个**（均单一用途；内核 GeckoView 自带的 Mozilla 远程设置主机
  —— 如扩展系统使用的 `webextensions.settings.services.mozilla.com` —— 随内核分发、不在本枚举内，
  另「检查更新」给出的下载直链指向 `github.com`，由系统浏览器/下载器接手访问）：
  `addons.mozilla.org`（扩展目录/元数据/xpi）、`firefox.settings.services.mozilla.com`（跟踪保护名单本机更新）、
  `api.github.com`（**仅点「检查更新」时**读版本信息，绝不后台自检）。
- **明文禁令**：`res/xml/network_security_config.xml` 的 `cleartextTrafficPermitted="false"`
  ⚠️ 只约束**应用自身 OkHttp**，对 GeckoView 无效（内核自带网络栈，必须能开 http 站点）。
- **DNS 为明文**（未配置 TRR/DoH）：DoH 会为应用自身网络新增对外端点，与"应用自身仅三个端点"的口径冲突，故刻意不用。
- **崩溃日志**只写本机、可查看/删除；`CrashLogger` 落盘前经**三层脱敏**：
  无 `//` 的 scheme（`mailto:` / `data:` 等，此前会把完整邮箱或内嵌内容写盘）、
  不构成 URL 的凭据（`token=…` / `password: …` / `Bearer …`）、
  以及 `scheme://…` URL 收敛为 `scheme://host[:port]/…`（userinfo 剥除）；
  **裸路径刻意保留** —— 它不是 URL，且对排障价值更高（见工作区的 `../notes-tangsnow/RESEARCH-2026-10-19-crashlogger-redaction-audit.md` —— **在仓库外**，本仓只进代码）。

## 9. 安全面

| 面 | 事实 |
|---|---|
| 权限 | 仅 `INTERNET` / `CAMERA`（按需）/ `ACCESS_LOCAL_NETWORK`（API 37+，按需） |
| `exported=true` | 仅 `ConsentActivity`(LAUNCHER) 与 `MainActivity`(http/https + SEND text/plain) |
| GeckoView 剪贴板 provider | 157 带进、158 已移除（导出面 4 → 3）；`AndroidManifest.xml` 的合并规则保留 **DUMP** 收口作防御 |
| FileProvider | `exported=false`；`file_paths.xml` 只放 `Download/` 单一落点（`DownloadRepo.externalizableUri`） |
| WebView 攻击面 | **无 WebView**（用 GeckoView）⇒ `addJavascriptInterface` 等 0 命中 |
| 更新链校验 | `UpdateChecker.isTrustedApkTarget`：拒反斜杠 + 严格 `host==github.com`（防 `github.com.evil` 前缀骗过） |

## 10. 构建与发布链

- **JDK 25**（daemon 由 `gradle/gradle-daemon-jvm.properties` 固定），字节码目标 Java 17；Android SDK API 37。
- **打包**：`packaging{ jniLibs{ useLegacyPackaging=true } }`；ABI 拆 3 包（无 universal）；语言过滤 en/zh/zh-CN/zh-HK/zh-TW。
  > `useLegacyPackaging=true` 是**必需修复**：否则 AGP 会把 16K 对齐 padding 写进 `.so` 的 local header extra，
  > 安装器 `libziparchive` 拒收（res=-113「解析包出现问题」），而门禁 ①–⑨ 全绿看不出来。
- **任务名必带 flavor**（`core` 维度后，裸 `lintDebug`/`assembleRelease` 已 ambiguous），**两 flavor 都要跑**。
- **签名三闸门**（`app/build.gradle.kts`，fail-closed）：文件不存在 / 键缺失或空 / 键仍是占位符 ⇒
  任何 release 任务**直接报错**，绝不静默出 debug 签名包。
- **发版门禁** `tools/verify_release.py` **12 项**（工作区干净 / 版本声明 / 包名版本 ABI / 权限基准 /
  证书指纹+v2 / 新鲜度 / 依赖散列 / `libxul.so` 构建号 / `exported` 白名单 / `lib/*.so` 必须 deflate /
  自家 `.so` `p_align>=16384` / **产物内不得含构建机路径或身份信息**——APK 就是发出去的东西，
  里面留着绝对路径或用户名等于公开编译环境；只扫**自家字节**（GeckoView 的 `.so` 里带的是 Mozilla
  自己的构建路径，不归我们管））；`--selftest` 25 项自检（不替代真包）。
- **CI** `.github/workflows/ci.yml`：两 flavor 单测 + 两 flavor lint + 门禁自检；
  **刻意不跑 `assembleRelease`、不引用 secrets**。

## 11. 测试体系

| 层 | 位置 | 规模 |
|---|---|---|
| 共享单测 | `app/src/test`（45 文件） | **255**（coreKotlin）/ **262**（coreRust） |
| flavor 专有单测 | `app/src/testCoreRust`（2 文件 / 7 例） | 只在 coreRust 编译单元里跑 |
| Rust 单测 | `tangsnow-rs/tangsnow-core/tests`（15 个 `.rs` + 10 份共享夹具） | **169** |
| 门禁自检 | `verify_release.py --selftest` | 25 |
| **仪器测试** | `app/src/androidTestCoreRust`（2 文件 / 12 例） | ⚠️ **只覆盖 coreRust flavor**（coreKotlin 无仪器测试） |

**元测试钉子**（改源码必须同步改它们）：`TestCountCommentTest`（钉 build.gradle 计数注释）、
`ReleaseGateConsistencyTest`（钉 `dist/jniLibs` 逐字节一致 + `build-android.sh` 五处口径）、
`BridgeSignatureParityTest`（钉 Kotlin `external` ↔ Rust 导出，**含产物层 ELF 符号校验**）、
`PolicyConsistencyTest`、`ProguardLogRuleConsistencyTest`、`TouchTargetSentinelTest`、`KdocAdjacencyTest`。

**构建注意**：单测必须带 `--rerun-tasks`（否则 `FROM-CACHE` 是假绿）；lint `checkAllWarnings=true` 为硬门禁。

## 12. 现状与已知限制

1. **仪器测试只覆盖 coreRust**：`app/src/androidTestCoreRust` 有 12 例（W2 九桥 + W1 两桥 + 真机
   往返），但 **coreKotlin（实际发布的那条线）没有仪器测试** ⇒ UI / 真机行为仍无自动化覆盖。
   且这 12 例**在本机尚未跑通**：16KB AVD 起得来，但带 GeckoView 原生库的 APK 装不下
   （`adb install` 超时、随后 shell 无响应）⇒ 需要能装该包的设备才能执行。
2. **`.so` 产物必须随 Rust 源码重编**：随包 `.so` 与 `tangsnow-rs/` 源码是**两份事实**
   （`ReleaseGateConsistencyTest` 只校验「dist 与 flavor 逐字节一致」）。改 Rust 后未重编 ⇒
   发布出去的行为仍是旧的。2026-10-21 已用本机 NDK r30 重编三 ABI（`p_align` 全 `0x4000`）。
3. **两 flavor 是两个独立应用身份**：`coreKotlin` = `io.github.tan_sno.tangsnow`，
   `coreRust` = `io.github.tan_sno.tangsnow.rust`（`applicationIdSuffix = ".rust"`）
   ⇒ 可同机并存、数据隔离、各自升级；**但共用同一套 versionCode 基准**（本版 42 ⇒ ABI 派生
   421/422/423），发布纪律仍是**一次只发一个 flavor**（门禁把另一个 flavor 的包判为
   「预期之外的 APK」）。

## 13. 事实来源

- 代码：`app/src/**`、`tangsnow-rs/**`、`settings.gradle.kts`、`app/build.gradle.kts`、`AndroidManifest.xml`
- 门禁与 CI：`tools/verify_release.py`、`tools/check_zip_padding.py`、`.github/workflows/ci.yml`
- 说明：`README.md`、`CONTRIBUTING.md`

> 本文所有可核对结论均可由上述文件与 git 历史独立复现；改动结构（flavor / 接缝 / 门禁编号）时请一并更新。