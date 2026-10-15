# tangsnow-rs — 棠雪 TangSnow 的 Rust 重构

> **本目录在主仓里的定位（2026-10-15 收编）**：这是 Rust 内核变体的源码，**目前不参与默认构建** ——
> `settings.gradle.kts` 没有引入它，`app/` 也没有依赖它，所以主线（`coreKotlin`）的构建、测试、
> 发布完全不受影响。收编它只有一个理由：此前这份源码**只存在于 `<OLD_RUST_WORKSPACE>` 这一块盘上，
> 被该仓的 `.git/info/exclude` 排除、不在任何 remote**，坏一次就永久失去重建三颗 `.so` 的能力。
>
> 落地路径记在 `notes-tangsnow/STATUS.md` 的 2026-10-15 一节：P1 加 `core` flavor 维度
> （`.so` + `CoreNative.kt`/`CoreBridge.kt` 进 `app/src/coreRust/`）→ P2 逐函数双实现缝 →
> P3 跨 flavor 共享 parity 用例。在那之前，这里的 `cargo test` 只能在本机 host 目标上跑
> （`android` 三个 target 未安装，且 `tools/build-android.sh:22` 写死 `prebuilt/linux-x86_64`
> ⇒ `.so` 的交叉编译从来不是这台 Windows 做的）。

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

**验证结果**：`cargo test` **70/70 通过**，其中
- 30 + 15 个差分用例与 JVM 单测同源（同输入 ⇒ 同输出，含防钓鱼 `@` 检查、
  IPv4-mapped IPv6 三种写法、scheme 三分法、引擎表逐条核对）；
- 22 个书签用例含 **500 例固定种子随机语料差分**（线性实现 vs 参考正则实现）；
- **复杂度哨兵**：2MB 对抗输入（`<a >` 重复 / 无 `>` 收尾）毫秒级完成，
  钉住 Kotlin 侧「单趟线性扫描防 O(n²)」的设计不变量。

**交叉编译**：NDK r27c 直连 clang，三 ABI release 产物在 `bridge/jniLibs/`
（arm64-v8a 2.2M / armeabi-v7a 1.4M / x86_64 2.4M，opt-level=z + LTO + strip +
panic=abort），18 个 JNI 符号以 `tan_1sno` 正确转义导出。

## 结构

```
tangsnow-rs/
├── Cargo.toml                # workspace（release: opt-level=z + LTO + panic=abort）
├── tangsnow-core/            # 核心库（host 测试 70/70 绿）
│   ├── src/                  # percent_encode / url_utils / search_engines /
│   │                         # bookmark_html / history / jni(feature="jni")
│   └── tests/                # 与 JVM 单测同源的差分测试
├── bridge/
│   ├── CoreNative.kt         # Kotlin 侧 JNI 声明（含 available 降级门）
│   ├── gradle-integration.gradle.kts   # Gradle 接入片段
│   └── jniLibs/<abi>/libtangsnow_core.so   # 预构建产物
└── tools/build-android.sh    # 直连 NDK 重编脚本（不依赖 cargo-ndk）
```

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
bash tools/build-android.sh         # 产出 bridge/jniLibs/<abi>/libtangsnow_core.so
```

踩坑记录：cargo target `armv7-linux-androideabi` 对应的 NDK 包装器名是
`armv7a-linux-androideabi26-clang`（多个 a）。

## 已知口径差异（有意为之 / 无法完全抹平）

| 点 | Kotlin | Rust | 影响 |
|---|---|---|---|
| IDN | `IDN.toASCII(ALLOW_UNASSIGNED)`（IDNA2003） | `idna` crate（UTS46/IDNA2008） | 未分配码位的异常输入判定不同；常规中文域名一致 |
| 字母判定 | `isLetterOrDigit`（L*+Nl+Nd） | `is_alphabetic/is_numeric`（含 No） | 仅影响 scheme 合法性判定中的罕见 Unicode 数字 |
| 标题截断 | `take(200)` 按 UTF-16 单元 | 按 char | 仅含星形平面的超长标题截断点差 1-2 字符 |
| nbsp 处理 | `Character.isWhitespace(nbsp)=false` | 已用 `java_is_whitespace` 对齐 | 已抹平；正则 `\S` 类已按 Java ASCII 口径改写 |

`display_for_url` 用 WHATWG `url` crate 替代 `android.net.Uri`：对 http/https
结果页的 host 与查询参数解码语义一致（`+`→空格、取首个同名参数）。

## 未移植（留在 Kotlin，理由）

- `SearchEngines.all/current`（依赖 `PreferenceStore` 偏好层）
- `HistoryRepo` 的 DB/协程侧（Android I/O）
- `display_for_url` 的调用方（UI 层）；`labelRes` 本地化名（Android 资源）

## 许可

与上游一致：Apache-2.0。
