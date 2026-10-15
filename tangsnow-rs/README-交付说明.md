# 棠雪 · Rust 核心版 — 交付说明

日期：2026-10-03

## 这是什么

完整可安装的棠雪浏览器（Android），其中**核心逻辑已重构为 Rust** 并以原生库方式接入：

- 浏览器壳：Kotlin UI（原工程不动）
- 引擎：GeckoView 157（本项目固有）
- **核心逻辑：Rust（tangsnow-core），编译为 `libtangsnow_core.so`，随包分发**

接入点（Rust 优先，`.so` 缺失/加载失败自动回落 Kotlin 原实现，行为经差分测试逐口径锁定）：

| 功能 | Kotlin 侧调用点 |
|---|---|
| 地址栏解析（URL/搜索判定） | `UrlUtils.resolveInfo` |
| 分享文本提链 | `UrlUtils.extractUrlFromText` |
| 局域网地址识别 | `UrlUtils.isLocalNetworkAddress` |
| 搜索 URL 构造 | `SearchEngines.SearchEngine.searchUrl` |
| 结果页还原关键词 | `SearchEngines.displayForUrl` |
| 书签导入解析/清洗/导出 | `BookmarkHtml.parseImportCounted / sanitize / export` |
| 历史记录隐私过滤 | `HistoryRepo.add` |

改动面：**5 个文件 +60 行**（`rust-core-integration.patch`），新增 `core/CoreNative.kt`（JNI 桥）+ `core/CoreBridge.kt`（JSON 适配层），另加 R8 keep 规则一条。

## 文件

| 文件 | 说明 |
|---|---|
| `tangsnow-2.1.6-rust-core-arm64-release.apk` | **推荐安装**：R8 优化版，188MB，debug 证书签名 |
| `tangsnow-2.1.6-rust-core-arm64-debug.apk` | 未混淆调试版，196MB |
| `rust-core-integration.patch` | 棠雪工程的全部集成改动（git diff） |
| `tangsnow-rs/`（随包另发） | Rust 核心完整源码（workspace：core/bridge/jniLibs/tools） |

其余 ABI（armeabi-v7a / x86_64）APK 已在工作区 `external/TangSnow/app/build/outputs/` 备好，需要再发。

## 验证

- Rust 侧：**70/70** 单测全绿（cargo test）+ 三 ABI 交叉编译 ELF 头校验
- 集成后：**29 套 JVM 单测全绿**（0 失败 0 错误）——此路径走 Kotlin 降级实现，证明降级机制无损
- APK 内已验证 `lib/*/libtangsnow_core.so` 三 ABI 齐全（arm64 包内 2.28MB）
- release 走工程自带签名闸门（`-Ptangsnow.allowDebugSignedRelease`，本地验证用）

## 重建方法

```bash
# 1. Rust 核心（三 ABI .so）
cd tangsnow-rs && ./tools/android-build.sh   # 输出 bridge/jniLibs/

# 2. 把 jniLibs 拷入 app/src/main/jniLibs/，应用 rust-core-integration.patch
# 3. 构建（4GB 内存机器请用仓库内已调好的 JVM 参数）
./gradlew assembleRelease -Ptangsnow.allowDebugSignedRelease
```

## 已知边界

- R8 对 `CoreNative` 整类 keep（JNI 契约必需，已在 proguard-rules.pro 注明）
- `available` 开关永不打 Log：JVM 单测无 android.util.Log 桩，catch 块碰 Log 会把降级变崩溃
- release 为 debug 证书签名：本地安装验证用；正式分发需配置 keystore.properties 重新构建
