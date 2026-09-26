package io.github.tan_sno.tangsnow.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LocaleManager.shouldOffer] —— 「首次启动要不要弹语言初选页」的判定。
 *
 * 这个判定的**误判代价不对称**：
 *  - 误弹（本该跳过却弹了）⇒ 中文 / 英文用户平白多看一眼；
 *  - 漏弹（该弹却没弹）⇒ 非中英语言环境的用户永远困在中文界面 —— 那正是本次改动的目的。
 * 所以边界值（`zh` / `en` 的各种写法、大小写、空值）必须钉住。
 *
 * 只测纯逻辑那一层（不构造 Context）—— `LocaleManager` 的 object 初始化不触碰任何
 * Android API，因此可以在普通 JVM 单测里安全调用。
 */
class LocaleManagerTest {

    @Test
    fun `设备语言是中文或英文时不弹`() {
        for (lang in listOf("zh", "en", "ZH", "EN", "Zh", "En")) {
            assertFalse(
                "设备语言「$lang」时界面本就正确，不该弹语言初选",
                LocaleManager.shouldOffer(offered = false, appLocale = "", deviceLanguage = lang),
            )
        }
    }

    @Test
    fun `设备语言既非中文也非英文时弹`() {
        for (lang in listOf("ja", "fr", "de", "ko", "ru", "es", "ar", "th", "vi")) {
            assertTrue(
                "设备语言「$lang」会落到默认资源（中文界面），应当弹一次让用户自己选",
                LocaleManager.shouldOffer(offered = false, appLocale = "", deviceLanguage = lang),
            )
        }
    }

    @Test
    fun `已经问过就不再弹`() {
        assertFalse(LocaleManager.shouldOffer(offered = true, appLocale = "", deviceLanguage = "ja"))
        assertFalse(LocaleManager.shouldOffer(offered = true, appLocale = "zh", deviceLanguage = "ja"))
    }

    @Test
    fun `用户已显式选过语言就不再弹`() {
        assertFalse(LocaleManager.shouldOffer(offered = false, appLocale = "zh", deviceLanguage = "ja"))
        assertFalse(LocaleManager.shouldOffer(offered = false, appLocale = "en", deviceLanguage = "ja"))
    }

    @Test
    fun `appLocale 为空是「跟随系统」的正常取值，不能据此认定没问过`() {
        // 关键回归点：用户在本页选了「简体中文」后 appLocale 非空；但若将来有人把
        // languageChoiceOffered 标记去掉、只靠 appLocale 是否为空来判断，那么**选了
        // 「跟随系统」**的用户（appLocale 仍为空）每次冷启动都会被再弹一次。
        // 这里断言：同样空 appLocale，未问过才弹、问过就不弹。
        assertTrue(LocaleManager.shouldOffer(offered = false, appLocale = "", deviceLanguage = "ja"))
        assertFalse(LocaleManager.shouldOffer(offered = true, appLocale = "", deviceLanguage = "ja"))
    }

    @Test
    fun `取不到设备语言时不弹`() {
        // 极端情况（LocaleList 为空、或语言码异常）：拿不到语言就无从判断用户是否看得懂中文，
        // 此时宁可不打扰 —— 用户仍可在「设置 → 语言」里自己改。
        for (unknown in listOf("", " ", "  ")) {
            assertFalse(
                "设备语言为「$unknown」时不该弹",
                LocaleManager.shouldOffer(offered = false, appLocale = "", deviceLanguage = unknown),
            )
        }
    }
}
