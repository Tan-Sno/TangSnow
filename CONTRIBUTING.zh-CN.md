[English](CONTRIBUTING.md) | **简体中文**

# 贡献指南

感谢你有兴趣为棠雪做出贡献。

参与本项目即表示你同意遵守[社区行为准则](CODE_OF_CONDUCT.zh-CN.md)。

## 语言

仓库的对外文档（本文件、`README`、`SECURITY`、`CODE_OF_CONDUCT`、`THIRD_PARTY_NOTICES`）
均为**中英双语**，且**以英文为默认**；本文件另有
[英文版](CONTRIBUTING.md)。

**代码注释、提交信息与维护者的内部记录以中文为主** —— 这是本项目的工作语言，改造成本极高，
请提前知悉。Issue 与 Pull Request 则**中英文都可以**，用你顺手的那种即可（模板里已注明）。

## 报告问题

请在 [Issues](https://github.com/Tan-Sno/TangSnow/issues) 中提交。仓库已配置好提交模板，
新建 Issue 时按模板填写即可，模板要求的字段正对应下面的清单：

> **安全漏洞请勿使用公开 Issue。** 请改用
> [私密漏洞报告入口](https://github.com/Tan-Sno/TangSnow/security/advisories/new)，
> 详见 [SECURITY.zh-CN.md](SECURITY.zh-CN.md)。

提交时请尽量包含：

- 棠雪版本号（设置 → 关于棠雪）
- 设备型号与 Android 版本
- 复现步骤，以及期望结果与实际结果
- 如涉及网页渲染，请附上可复现的网址
- 相关截图或录屏

## 功能建议

同样通过 Issues 提出。请先说明使用场景与要解决的问题，而不只是功能本身。
涉及新增权限、联网行为或第三方依赖的建议，需要额外说明其对隐私的影响。

## 开发环境

- **JDK 25** —— Gradle 守护进程按 `gradle/gradle-daemon-jvm.properties` 固定使用该版本；
  本机若未安装，Gradle 会自动下载。产物字节码目标为 Java 17。
- Android SDK `platforms;android-37`（API 37，minor level 2）
- Android Studio 2026.1 或更高版本（仅图形界面开发需要，命令行构建不需要）

```bash
./gradlew :app:assembleCoreKotlinDebug        # 构建（单 flavor，见下方说明）
./gradlew :app:lintCoreKotlinDebug :app:lintCoreRustDebug            # 静态检查（两个 flavor 都要）
./gradlew :app:testCoreKotlinDebugUnitTest :app:testCoreRustDebugUnitTest   # 单元测试（同上）
```

`app` 模块有 `core` 这一维 product flavor（`coreKotlin` = 实际发布的纯 Kotlin 实现；
`coreRust` = 同一批逻辑走 JNI 桥，仍是预览状态）。有了 flavor 维度之后，**不带 flavor 的任务名
已经不存在**：`:app:lintDebug`、`:app:testDebugUnitTest` 现在会因任务名 ambiguous 而直接失败。
所以任务名一律带 flavor，且**两个都要跑** —— 只跑一个，另一个 flavor 的编译错误、lint 警告与
单测都能悄悄进主线（CI 就是两个都跑）。

构建 release（`assembleCoreKotlinRelease`，或 `bundleCoreKotlinRelease`）需要仓库根目录的
`keystore.properties`（不随仓库分发）；凭据不可用时 release 构建**直接报错** —— 三道闸门分别管
"文件不存在""键缺失或为空""键还是 `<<占位符>>`"这三种形态，防止静默产出"看起来可分发"的
debug 签名包。Android Studio 的「生成签名式 App Bundle / APK」向导自带凭据，闸门认得、会放行。
仅本地验证打包链路时，可显式加 `-Ptangsnow.allowDebugSignedRelease` 跳过该闸门 —— 产物仍用
debug 证书、仍不可分发或上架。正式包**一次只发一个 flavor**：两个 flavor 的 `versionCode`
派生相同（基准 42 ⇒ 421/422/423），混发会在用户没选过的两份实现之间来回升降。
不过自 A1 起两者已是**两个独立应用身份**（`coreKotlin` = `io.github.tan_sno.tangsnow`，
`coreRust` = `io.github.tan_sno.tangsnow.rust`），可同机并存、数据隔离；Rust 线用独立的
`v<ver>-rust-preview` tag 发布，且该 release **必须标 prerelease**：应用内更新检查读的是
`/releases/latest`，而资产挑选只按 ABI 匹配、不看 flavor —— Rust 包若作为正式 release 发出，
会被正式版用户当成「有新版本」，下到的却是包名带 `.rust` 的另一个应用。

提交前请确保上面几条命令均通过，且 lint 保持**零问题**。
注意本项目已开启 `checkAllWarnings`（`app/build.gradle.kts`），**warning 级问题同样会被报出** ——
请勿为了消掉一条警告而关掉整类检查，有必要的话按现成写法逐条写明豁免理由。

`coreRust` 那条线另有 Rust 侧命令（在 `tangsnow-rs/` 下执行）：

```bash
cargo test --offline                                     # 纯逻辑（host 上跑）
cargo check --offline --features jni -p tangsnow-core    # 改过 src/jni.rs 之后必须再跑这条
```

第二条不是冗余：`lib.rs` 把 `pub mod jni` 关在 `#[cfg(feature = "jni")]` 后面，所以
**`cargo test` 全绿证明不了那 34 个 JNI 导出编得过**（形参类型、`JClass` 的形状都在那一层）。
它也只保证类型层 —— JNI 的**名称与描述符**是运行期匹配的，那一半只有重建 `.so` + 真机能证明
（`BridgeSignatureParityTest` 比的是符号名与形参**个数**，比不了类型）。

## 代码约定

- 语言与界面框架：Kotlin + Android View System（不使用 Compose）
- **颜色**一律使用语义命名（`@color/…` 或 `?attr/colorXxx`），不要硬编码色值
- **尺寸**（dp/sp）只在「同一语义在多处重复、或已出现取值漂移」时才抽入 `values/dimens.xml`；
  单次使用的值留在布局原地。同一个数字在不同位置常代表不同含义（40dp 触控区 vs 40dp 外边距），
  强行合并只会制造假耦合。抽取标准与已有取例见 `dimens.xml` 顶部注释
- 用户可见文案必须同时维护中文（`values/`）与英文（`values-en/`）两份，且键集合保持一致
- 新增网络请求须说明用途；本项目遵循「数据不出设备」原则，不接受遥测、统计或广告类依赖
- `docs/PRIVACY.md` 与 `docs/TERMS.md` 由 `tools/export_legal_docs.py` 从 `strings.xml` 导出，
  **请勿直接编辑这两份文件**；改动法律文本后需重新导出并在必要时提升 `POLICY_VERSION`
- **格式由工具定，不由人定**：`.editorconfig`（LF、文件末尾换行、去行尾空白（`.md` 例外——那里行尾
  双空格是换行语义）、`.gitattributes`（`* text=auto eol=lf`，`.bat`/`.cmd` 用 CRLF，二进制列全）
  与已入库的 `.idea/codeStyles` 是唯一的格式化真相，缩进族是 4 空格（`kt`/`kts`/`py`/`yml`/`toml`/`rs`），
  `js`/`ts`/`json` 是 2。**别在自己的改动之外顺手重排文件** —— 那会把真正的 diff 淹掉
- **Rust 侧不做 rustfmt 合规**：请不要跑 `cargo fmt`。本仓的 `.rs` 从来不按 rustfmt 排版
  （2026-10-18 实测 `cargo fmt --check` 报 114 处 / 19 个文件，多数与近期改动无关；这个数字会变，
  别把它当基准）。真要重排请单独立一笔，别混进功能修复里

## 提交信息

采用 [Conventional Commits](https://www.conventionalcommits.org/) 格式：

```
<type>: <简短描述>

[可选正文：说明为什么这样改]
```

常用 `type`：`feat`、`fix`、`perf`、`refactor`、`docs`、`build`、`test`、`chore`。

描述行使用祈使语气、保持简短；正文只写「为什么」，不逐条罗列改动（改动本身看 diff 即可）。

## 提交 Pull Request

1. 一个 PR 只解决一个问题，便于审查与回滚
2. 说明改了什么、为什么这样改，以及如何验证
3. 若涉及界面或交互改动，请附截图
4. 若修复了某个 Issue，请在描述中关联该 Issue 编号

## 许可证

向本项目提交贡献即表示你同意以 [Apache License 2.0](LICENSE) 授权你的贡献。
