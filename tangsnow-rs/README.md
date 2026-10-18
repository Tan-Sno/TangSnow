# tangsnow-rs — 棠雪 TangSnow 的 Rust 重构

> **本目录在主仓里的定位（2026-10-15 收编）**：这是 Rust 内核变体的源码，**目前不参与默认构建** ——
> `settings.gradle.kts` 没有引入它，`app/` 也没有依赖它，所以主线（`coreKotlin`）的构建、测试、
> 发布完全不受影响。收编它只有一个理由：此前这份源码**只存在于 `<OLD_RUST_WORKSPACE>` 这一块盘上，
> 被该仓的 `.git/info/exclude` 排除、不在任何 remote**，坏一次就永久失去重建三颗 `.so` 的能力。
>
> 落地路径见 git 历史（2026-10-15 起）：P1 加 `core` flavor 维度
> （`.so` + `CoreNative.kt`/`CoreBridge.kt` 进 `app/src/coreRust/`）→ P2 逐函数双实现缝 →
> P3 跨 flavor 共享 parity 用例。在那之前，这里的 `cargo test` 只能在本机 host 目标上跑
> （`android` 三个 target 未安装 ⇒ `.so` 的交叉编译从来不是这台 Windows 做的；
> `tools/build-android.sh` 的 NDK 宿主目录按 `uname` 自动探测，可用 `NDK_HOST_BIN` 覆盖）。

把 [TangSnow](https://github.com/Tan-Sno/TangSnow)（Kotlin/GeckoView Android 浏览器）中
**零 Android 依赖的纯逻辑模块**移植为 Rust 核心库 `tangsnow-core`，经 JNI 供 App 调用。

## 为什么是"核心逻辑 Rust 化"而不是"整个 App 重写"

GeckoView 的 API 面是 JVM（Java/Kotlin）的，浏览器引擎（Gecko）本身就是 C++/Rust
混合体。Android 上"纯 Rust App"意味着放弃 GeckoView——那不再是棠雪。技术正确的
重构姿势：**UI/引擎胶水留 Kotlin，纯逻辑下沉 Rust**，这正是主流 Android 团队的
混合架构。

## 移植范围（已完成，测试全绿）

| Rust 模块 | Kotlin 源 | 行数 | 对应 JVM 测试 |
|---|---|---|---|
| `percent_encode` | `util/UrlUtils.kt` 顶层 | 30 | `PercentEncodeTest` |
| `url_utils` | `util/UrlUtils.kt` | 399 | `UrlUtilsTest`(27)+`SchemeGateTest`(6) |
| `search_engines` | `data/SearchEngines.kt` | 217 | `SearchEnginesTest`(9) |
| `bookmark_html` | `data/BookmarkHtml.kt` | 295 | `BookmarkHtmlTest`(23) |
| `history` | `data/repo/HistoryRepo.kt` 纯函数 | 96 | — |

**验证结果**：`cargo test` **232/232 通过**（2026-10-17 实测，15 个测试二进制合计；旧口径写的 70/70、210/210、213/213、223/223、224/224、231/231 均已过期。**这个数在本工作区只在这一处维护** —— 结构图与 `自建验证指南.md` 都只指回这里，不再各抄一份：Rust 侧计数**没有**像 Kotlin 那样被 `TestCountCommentTest` 钉住，多写一处就多一处会过期），其中
- 30 + 15 个差分用例与 JVM 单测同源（同输入 ⇒ 同输出，含防钓鱼 `@` 检查、
  IPv4-mapped IPv6 三种写法、scheme 三分法、引擎表逐条核对）；
- **IDNA 差分 3 例**（`tests/url_idna_parity.rs`）：把「裸主机是否判为网址」的口径钉在主线
  `IDN.toASCII` 的**实测行为**上，含本仓唯一一处**有意分歧**（退化输入 `.`）与 8 条已记录的口径差异，
  详见本文件「已知口径差异」表；
- 22 个书签用例含 **500 例固定种子随机语料差分**（线性实现 vs 参考正则实现）；
- **复杂度哨兵**：2MB 对抗输入（`<a >` 重复 / 无 `>` 收尾）毫秒级完成，
  钉住 Kotlin 侧「单趟线性扫描防 O(n²)」的设计不变量；
- **JNI 边界 8 例**（2026-10-15 加，外部审查 H3/M5/L8 的落点）：
  `tests/modified_utf8.rs` 5 例把 modified UTF-8（CESU-8）的解码钉成纯函数用例（星平面字符
  往返、`U+0000` 写作 `0xC0 0x80`、坏输入只换替换字符不 panic），
  `tests/jni_boundary_parity.rs` 3 例是**源码级哨兵**——因为 `jni.rs` 关在
  `#[cfg(feature = "jni")]` 后面、host 一行也跑不到那层，只能像 `TestCountCommentTest`
  那样把"必须走 CESU-8 解码""`displayForUrl` 的 id 由 Kotlin 传入""数组长度用 `try_from` 收窄"
  钉成红灯；`tests/search_engines_parity.rs` 另加 2 例（别名表按真 id 命中、`template_only` 等价）。
- **2026-10-17 回归批 7 例**（`tests/regression_2026_10_17.rs`）：把当轮修掉的 6 处跨语言口径缺陷
  逐条钉住——`java_is_whitespace` 的 4 个码点（含 `java_trim` 连带）、`display_for_url` 只取
  **首个**同名参数、`is_newer` 段解析按 Kotlin `toIntOrNull`（i32）、`pick_asset` 的 ABI 大小写
  不敏感、`LOCALHOST_RE`/`URL_IN_TEXT_RE` 只折叠 ASCII 且取反类须 `(?u:…)` 包住；每条注释都写明
  「旧实现会给出什么」，便于日后有人质疑旧行为时一眼看到当时的复现。
- **2026-10-17 `dt_prompts` 口径批**：用真 JBR（JDK 25）把 Kotlin 侧 `WebPrompts.kt` 实际调用的
  `DateTimeFormatter` 行为逐条量出来，订正 Rust 侧三处**与实测相反**的口径 —— 连同模块注释里那三条
  错误断言一起改（错注释比错代码更贵）：字段宽度 `HH/mm/ss/MM/dd` **恰好两位**（旧实现按「SMART
  接受 1–2 位」把 `9:5` 也收下）、`24:00[:00]` 是**午夜特例**（纯时间归 `00:00`；带日期**进位次日**，
  旧实现一律拒收）、超界**日钳到月末**（`2026-02-30` → 02-28、闰年 `2024-02-30` → 02-29，旧实现直接
  拒收）；另有 `yyyy` = `YEAR_OF_ERA` 不含 0 年这条随之补上。整张实测矩阵钉在
  `smart_matrix_matches_real_jvm_2026_10_17`，避免口径再凭印象漂移。

- **两张手抄表的跨语言比对（2026-10-16）**：`app/src/test/.../SearchEngineTableParityTest`（Kotlin 侧，3 例）
  逐条比对 `SearchEngines.kt` 与 `search_engines.rs` 的内置引擎表（id / 模板 / queryParam / **顺序**）与
  `HOST_ALIASES`（含「别名键必须是内置 id」）。它**第一次跑就抓到一条真漂移**：Rust 少了
  `"bing" → ["bing.com"]` 这一整行（⇒ 国际版引擎在 `www.bing.com` 结果页静默不回显关键词），
  已补，另加一条行为用例 `国际版引擎的结果页落在无区域前缀主机上也回显`。
  ⚠️ 同 H3/M5：补的是源码，随包 `.so` 不重建就不带它。

**交叉编译**：NDK r27c 直连 clang，重编落点是 `dist/jniLibs/`（**该目录已不被 git 跟踪**，
2026-10-15 整理：此前它与 `app/src/coreRust/jniLibs/` 各存一份逐字节相同的 `.so`，
两份跟踪副本就是"改一边忘一边"的口径），**唯一进仓、进构建的副本在
`app/src/coreRust/jniLibs/<abi>/libtangsnow_core.so`**。产物形状：arm64-v8a 2.2M /
armeabi-v7a 1.4M / x86_64 2.4M，opt-level=z + LTO + strip + panic=abort，**34** 个 JNI 符号
以 `tan_1sno` 正确转义导出（2026-10-15 从产物里实测；原先这里写 18 —— 与 `STATUS.md`
记的「.so 34 导出 vs Kotlin 31 `external`」也对不上）。

⚠️ **随包 `.so` 是 2026-10-10 那一批**（`app/src/coreRust/jniLibs/` 那三颗，实测），**不含**
2026-10-15 的 H3（CESU-8）与 M5（`displayForUrlImpl` 多一个形参）。
本机没有 android target、`.so` 在那台 Linux 环境交叉编译 ⇒ 源码与产物之间的偏差已知且在案：
重建之前，`displayForUrlImpl` 在真机上会以 `UnsatisfiedLinkError` 形式被 `guard` 兜成降级
（关键词回显失效），CESU-8 那条仍是旧行为。接线（PLAN 的 W1/W2/W3）之前必须先重建 `.so`。

## 结构

```
tangsnow-rs/
├── Cargo.toml                # workspace（release: opt-level=z + LTO + panic=abort）
├── tangsnow-core/            # 核心库（host 测试全绿，**计数只在上方「验证结果」维护一处**；默认不编译 jni.rs）
│   ├── src/                  # percent_encode / url_utils / search_engines /
│   │                         # bookmark_html / history / jni(feature="jni")
│   └── tests/                # 与 JVM 单测同源的差分测试
├── dist/                   # 只剩重编落点（`jniLibs/`，已 gitignore）；2026-10-15 清空
│   └── (已归档) CoreNative.kt 设计稿 / gradle-integration.gradle.kts / rust-core-integration.patch
└── tools/build-android.sh    # 直连 NDK 重编脚本（不依赖 cargo-ndk）
```

**dist/ 里那三样东西的下落**（2026-10-15 整理，别再在仓里找）：
- `CoreNative.kt`（101 行设计稿）—— **删除**。它与在用的防护网桥不是一份东西，
  留着就会被 `自建验证指南.md` 那样的步骤引用去、把人引到「拷过去覆盖正式实现」。
- `gradle-integration.gradle.kts`（pre-flavor 的 `sourceSets["main"].jniLibs` 片段）——
  移到仓外归档（**不进仓库**；需查历史请走 git 历史）。
  主线靠 flavor 的约定目录自动接管，这段一旦被 apply 就是**第二份 jniLibs 来源**。
- `rust-core-integration.patch`（改 `src/main` 调用点的旧接入）—— 移到
  仓外归档（**不进仓库**；需查历史请走 git 历史）。
  移走的原因不是"它失效了"：实测它**仍能 `git apply` 干净应用**，而打上去会让 coreKotlin
  编译不过（`src/main` 不能引用 flavor 专有符号）⇒ 留在公开仓里就是一个能点的陷阱，
  要查历史请走 git 历史。
  `ReleaseGateConsistencyTest` 钉着这两个路径**不得回到仓库里**。

Kotlin 侧的 JNI 声明在 `app/src/coreRust/java/io/github/tan_sno/tangsnow/core/`
（`CoreNative.kt` 防护网桥 + `CoreBridge.kt` JSON 适配）。
两侧符号与形参个数的一致性由 `app/src/test/.../BridgeSignatureParityTest` 钉。

## Kotlin 侧接入（渐进式，风险从低到高）

1. **旁路验证（推荐先做）**：同一输入分别喂 Kotlin 实现与 `CoreNative`，比对输出。
2. **委托切换**：`UrlUtils` / `BookmarkHtml` / `SearchEngines` 调用点改走
   `CoreNative`（`available == false` 时回落 Kotlin 实现）。
3. **整体替换**：删除 Kotlin 纯逻辑实现，库缺失时 fail-fast。

JNI 边界约定（见 `src/jni.rs` 头注）：
- 字符串 UTF-8 传值，可空返回用 null；
- 书签结构化数据在边界走 JSON，核心 API 保持类型化；
- 边界零 panic（release `panic = "abort"`，FFI 崩溃即进程死亡）。

## 重编 .so

```bash
export ANDROID_NDK_HOME=$HOME/ndk/android-ndk-r27c   # r25+ 均可
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
bash tools/build-android.sh         # 产出 dist/jniLibs/<abi>/libtangsnow_core.so
```

踩坑记录：cargo target `armv7-linux-androideabi` 对应的 NDK 包装器名是
`armv7a-linux-androideabi26-clang`（多个 a）。

## 已知口径差异（有意为之 / 无法完全抹平）

| 点 | Kotlin | Rust | 影响 |
|---|---|---|---|
| IDN | `IDN.toASCII(ALLOW_UNASSIGNED)`（IDNA2003） | `idna` crate（UTS46/IDNA2008） | **8 类实测分歧 + 1 类已修**，逐条见下表 |
| 字母判定 | `isLetterOrDigit`（L*+Nl+Nd） | `is_alphabetic/is_numeric`（含 No） | 仅影响 scheme 合法性判定中的罕见 Unicode 数字 |
| 标题截断 | `take(200)` 按 UTF-16 单元 | 按 char（码点） | BMP 文本一致；含星形面字符时**最多差一倍**（每个星形面字符在 Kotlin 侧占 2 个 UTF-16 单元，200 单元只能装 100 个），且 Kotlin 可能在代理对中间切断留下孤立代理 |
| nbsp 处理 | `Character.isWhitespace(nbsp)=false` | 已用 `java_is_whitespace` 对齐 | 已抹平；正则 `\S` 类已按 Java ASCII 口径改写 |

### IDN 分歧明细（2026-10-15 用 JBR 25.0.3 把主线 `UrlUtils.kt:74-92` 当 oracle 逐条量出；40 条语料共 9 处分歧 —— 下表 8 条为**保留的差异**，第 9 条（空标签）已修，见表格后「已修」）

| # | 输入 | 主线 (Java) | Rust | 性质 |
|---|---|---|---|---|
| 1 | `ß.de` | `ss.de` | `xn--zca.de` | Java 用**过渡映射**（ß→ss），正是 IDNA2008 废弃它的原因（同形字/抢注）；Rust 更新更严 |
| 2 | `FAß.de` | `fass.de` | `xn--fa-hia.de` | 同上 |
| 3 | `ς.com` | `xn--4xa.com` | `xn--3xa.com` | UTS46 把行尾 sigma 折叠为 σ ⇒ **同一主机转出不同 ASCII**；两侧都把它当键用时（历史是否访问过、可信目标、参数剥离、会话恢复）必须知道这点 |
| 4 | `ＡＢ.CD` | `ab.CD` | `ab.cd` | 同上（大小写折叠差异，键相等性受影响） |
| 5 | `a\u{200d}b.com` | `ab.com` | `<None>` | Java 抹掉零宽继续转，Rust 拒 |
| 6 | `xn--.com`、`xn--xn--a.com` | 原样放行 | `<None>` | Rust 做 punycode 回环校验，Java 完全不验 |
| 7 | `.`（单点） | `.` | `<None>` | 退化输入，Rust 拒 |
| 8 | `\u{0378}.com`（未分配码位） | `xn--zva.com`（`ALLOW_UNASSIGNED` 放行） | `<None>` | 原表那句"未分配码位判定不同"指的就是这类，罕见但确实不同 |

**已修**：空标签（`a..b.com`、`.example.com`、`a...b`、`..`）此前 Rust **放过**，比浏览器和主线都宽松 —— 结果是同一条输入主线当搜索词、Rust flavor 当网址导航。现两侧一致拒，而**尾点**（`example.com.`）两边都保留。

`display_for_url` 用 WHATWG `url` crate 替代 `android.net.Uri`：对 http/https
结果页的 host 与查询参数解码语义一致（`+`→空格、取首个同名参数）。

## 未移植（留在 Kotlin，理由）

- `SearchEngines.all/current`（依赖 `PreferenceStore` 偏好层）
- `HistoryRepo` 的 DB/协程侧（Android I/O）
- `display_for_url` 的调用方（UI 层）；`labelRes` 本地化名（Android 资源）

## 许可

与上游一致：Apache-2.0。
