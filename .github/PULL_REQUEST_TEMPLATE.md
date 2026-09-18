## 这个 PR 做了什么

<!-- 一句话说清解决的问题。关联的 Issue 请写「Closes #123」。 -->

## 为什么这样改

<!-- 说明取舍与理由。若试过其他方案，简述为什么没采用。 -->

## 如何验证

<!-- 复现/验证步骤，或说明为什么难以手工验证。涉及界面改动请附截图。 -->

## 自查清单

- [ ] 一个 PR 只解决一个问题
- [ ] `./gradlew :app:assembleDebug` 通过
- [ ] `./gradlew :app:lintDebug` 通过，且无新增问题
- [ ] `./gradlew :app:testDebugUnitTest` 通过
- [ ] 用户可见文案已同时维护中文（`values/`）与英文（`values-en/`），键集合一致
- [ ] 未引入遥测、统计或广告类依赖
- [ ] 若涉及新增联网行为或权限，已在描述中说明用途
- [ ] 未直接编辑 `docs/PRIVACY.md` 与 `docs/TERMS.md`（这两份由脚本从 `strings.xml` 导出）
