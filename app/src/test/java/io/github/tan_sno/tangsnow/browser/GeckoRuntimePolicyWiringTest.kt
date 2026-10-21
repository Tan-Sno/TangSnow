package io.github.tan_sno.tangsnow.browser

import io.github.tan_sno.tangsnow.TestPaths
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **内核崩溃面的提示策略必须留在位，且翻译入口「不做」的结论要有据可查（防反复）。**
 *
 * ## G13：`crashPullNeverShowAgain(true)`
 * 内核的「远程设置崩溃拉取」在触发时会询问用户是否拉取崩溃信息。项目承诺「崩溃日志只写本机、
 * 可查看/删除、不上传」⇒ 显式设 true（不再打扰），与 `crashHandler` 未设（内核崩溃不进应用
 * `CrashLogger`）属同一族口径。删掉这一行工程照常编译、单测照绿，故用读源码的方式钉住。
 *
 * ## 为什么**不**加 `translationsOfferPopup(false)`（G2 结论，留痕防反复）
 * 取证（157 AAR，2026-10-19，`unzip assets/omni.ja`）：
 *  - `greprefs.js:1315` `browser.translations.enable = false`，但 `geckoview-prefs.js:51`
 *    **把它覆盖成 `true`** ⇒ 内核层翻译能力默认**启用**；
 *  - `browser.translations.automaticallyPopup = true`（`greprefs.js:1322`）；
 *  - 翻译要真正跑起来，嵌入方必须显式接 `GeckoSession.setTranslationsSessionDelegate(...)`
 *    并驱动 `TranslationsController$SessionTranslation`（含 `ModelManagementOptions` 模型管理）；
 *    本应用在 `app/src/main` grep「translat」只有**扩展目录条目**，零接入
 *    ⇒ `getTranslationsSessionDelegate()` 恒为 null ⇒ **当前不可能触发翻译，也不会下载模型**。
 *  ⇒ 关 popup 无可见效果；且应用层**没有任何 pref 覆盖出口**（`configFilePath` 已于 2026-10-03
 *    因 Android 上 `DebugConfig.fromFile` 走 JavaBean（无 `java.beans.Introspector`）必抛而移除，
 *    见 `SessionManager.runtime()` 上方注释），连能力本身也关不掉。
 *  ⇒ 结论：**不落代码**；改为在架构 §8「对外端点」写明「翻译模型分发」为潜在端点、当前因未接入
 *    而不发生。若将来接入翻译 UI，须同时评估该出网面。
 */
class GeckoRuntimePolicyWiringTest {

    @Test
    fun `内核崩溃拉取提示的关闭策略在 runtime 的 Builder 链里`() {
        val src = File(TestPaths.repoRoot(), SRC_PATH).readText()
        assertTrue(
            "runtime() 的 Builder 链里找不到 crashPullNeverShowAgain(true)（G13 策略被删）",
            Regex("""\.crashPullNeverShowAgain\s*\(\s*true\s*\)""").containsMatchIn(src),
        )
    }

    private companion object {
        const val SRC_PATH =
            "app/src/main/java/io/github/tan_sno/tangsnow/browser/SessionManager.kt"
    }
}