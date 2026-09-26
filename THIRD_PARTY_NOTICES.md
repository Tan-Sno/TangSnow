# Third-Party Notices

本文件列出「棠雪 / TangSnow」源码与安装包中再分发的第三方开源组件、图标素材及相关声明。
应用自身代码采用 Apache License 2.0（见根目录 LICENSE）；本文件仅针对第三方内容，不修改 LICENSE。

## 开源组件（运行时依赖）

| 组件 | 版本 | 许可证 | 用途 |
|---|---|---|---|
| Mozilla GeckoView | 155.0.20260903215306 | Mozilla Public License 2.0 | 网页渲染内核（基于 Gecko 引擎） |
| OkHttp | 5.5.0 | Apache License 2.0 | 扩展目录 / 元数据 / 应用内更新检查等 HTTP 请求 |
| ZXing core | 3.5.4 | Apache License 2.0 | 二维码解码 |
| ZXing Android Embedded | 4.3.0 | Apache License 2.0 | 扫码取景与相机权限处理 |
| AndroidX（activity/appcompat/core/recyclerview/preference 等） | 见版本目录 | Apache License 2.0 | 界面与系统组件 |
| Google Material Components（Material 3） | 1.14.0 | Apache License 2.0 | 界面组件与主题 |
| Kotlin / kotlinx-coroutines | — | Apache License 2.0 | 语言与协程 |

- GeckoView 为 Mozilla 开源项目，MPL 2.0 全文：https://www.mozilla.org/en-US/MPL/2.0/

### MPL 2.0 §3.2 —— 以可执行形式分发时的源码获取途径

本应用以可执行形式（APK）分发并包含 MPL 2.0 覆盖的 **GeckoView**。按 MPL 2.0 第 3.2 条，分发可执行形式时必须同时告知接收者**如何取得对应的 Source Code Form**（费用不超过分发成本）：

- **GeckoView 源码**：<https://github.com/mozilla/gecko-dev>（亦可从 Mozilla 发布的
  [maven 仓库](https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview/) 取到与本应用所用版本
  `155.0.20260903215306` 完全对应的构件）。
- 本应用**未修改** GeckoView 的源码（仅以依赖形式调用其公开 API），因此不产生 MPL 第 3.1 条意义上
  的 Modifications；应用自身代码作为 Larger Work 采用 Apache License 2.0，不受 MPL 2.0 传染性约束。
- GeckoView 源码中的许可证与版权声明（MPL 2.0 §3.4）均未被移除或改动。
- Apache License 2.0 全文：https://www.apache.org/licenses/LICENSE-2.0

## 图标与图片素材

- 部分界面矢量图标基于 Google **Material Icons**（Apache License 2.0，
  https://fonts.google.com/icons / https://github.com/google/material-design-icons），
  其版权归 Google 所有。
- 应用图标与主页图形为「棠雪」原创设计。
- `res/drawable-nodpi/ic_engine_*.png`：各搜索引擎（百度、必应、搜狗、360、神马等）的官方图标，
  仅用于界面标识所指服务，版权归各自所有者；不构成任何背书或隶属关系。
- `res/drawable-nodpi/ic_ext_*.png`：各浏览器扩展（Dark Reader、Tampermonkey、Bitwarden、
  Stylus、Simple Translate 等（Privacy Badger 与 ClearURLs 未内嵌官方图标，以字母头像兜底））的官方图标，仅用于扩展目录界面标识，
  版权归各自作者/项目；不构成任何背书或隶属关系。

## 商标声明

- 「棠雪」「TangSnow」是本项目的原创品牌，与任何第三方无关。
- 本项目基于 Mozilla GeckoView 内核构建，但**并非 Mozilla 产品，也与 Firefox 无隶属关系**；
  「Firefox」「Mozilla」及其图标均为 Mozilla Foundation 的商标，此处仅用于客观描述内核来源。
- 各搜索引擎与扩展名称、图标均为其各自所有者的商标，在本项目中仅作标识性引用。
