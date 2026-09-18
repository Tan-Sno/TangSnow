# 贡献指南

感谢你有兴趣为棠雪做出贡献。

## 报告问题

请在 [Issues](https://github.com/Tan-Sno/TangSnow/issues) 中提交，并尽量包含：

- 棠雪版本号（设置 → 关于棠雪）
- 设备型号与 Android 版本
- 复现步骤，以及期望结果与实际结果
- 如涉及网页渲染，请附上可复现的网址
- 相关截图或录屏

## 功能建议

同样通过 Issues 提出。请先说明使用场景与要解决的问题，而不只是功能本身。
涉及新增权限、联网行为或第三方依赖的建议，需要额外说明其对隐私的影响。

## 开发环境

- JDK 17+
- Android SDK `platforms;android-37`（含 minor API level 2）
- Android Studio 2026.1 或更高版本

```bash
./gradlew :app:assembleDebug      # 构建
./gradlew :app:lintDebug          # 静态检查
./gradlew :app:testDebugUnitTest  # 单元测试
```

提交前请确保上述三条命令均通过，且 lint 无新增问题。

## 代码约定

- 语言与界面框架：Kotlin + Android View System（不使用 Compose）
- 颜色与尺寸一律使用语义命名（如 `colorSurface`），不要硬编码色值
- 用户可见文案必须同时维护中文（`values/`）与英文（`values-en/`）两份，且键集合保持一致
- 新增网络请求须说明用途；本项目遵循「数据不出设备」原则，不接受遥测、统计或广告类依赖
- `docs/PRIVACY.md` 与 `docs/TERMS.md` 由 `tools/export_legal_docs.py` 从 `strings.xml` 导出，
  **请勿直接编辑这两份文件**；改动法律文本后需重新导出并在必要时提升 `POLICY_VERSION`

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
