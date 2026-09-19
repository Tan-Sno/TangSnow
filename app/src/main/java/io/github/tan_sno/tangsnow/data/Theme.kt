package io.github.tan_sno.tangsnow.data

import androidx.appcompat.app.AppCompatDelegate

/** 主题枚举：值与 [AppCompatDelegate] 的 night mode 模式一一对应。 */
enum class Theme(val key: String, val nightMode: Int) {
    SYSTEM("system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT("light", AppCompatDelegate.MODE_NIGHT_NO),
    DARK("dark", AppCompatDelegate.MODE_NIGHT_YES);

    companion object {
        val DEFAULT = SYSTEM

        fun fromKey(key: String?): Theme =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** 主题应用入口，便于统一变更（如增加日志、埋点、动画过渡等）。 */
object ThemeController {
    fun apply(theme: Theme) {
        AppCompatDelegate.setDefaultNightMode(theme.nightMode)
    }
}