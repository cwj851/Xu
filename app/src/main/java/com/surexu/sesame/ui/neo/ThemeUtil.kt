package com.surexu.sesame.ui.neo

import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import com.surexu.sesame.data.AppConfig

/**
 * 主题（夜间模式）统一入口：跟随系统 / 浅色 / 深色 三态。
 * 需在每个 Neo Activity 的 attachBaseContext 最先调用，
 * 确保页面按 AppConfig.followSystem + darkMode 以对应 DayNight 模式创建。
 */
object ThemeUtil {

    /** 当前主题对应的 AppCompat 夜间模式。 */
    fun currentNightMode(): Int = when {
        AppConfig.INSTANCE.followSystem ?: true -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        AppConfig.INSTANCE.darkMode ?: false -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_NO
    }

    /** 进程内仅触发一次 AppConfig 加载（Lombok boolean getter 名不可靠，故不用 isInit）。 */
    private val configLoaded = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 应用全局夜间模式（应在 Activity.attachBaseContext 中优先调用）。 */
    fun applyNightMode() {
        // 冷启动时 AppConfig 尚未 load，INSTANCE 还是默认值（followSystem=true），
        // 会错误地套用浅色；这里先确保配置已从磁盘加载再决定主题。
        if (configLoaded.compareAndSet(false, true)) {
            AppConfig.load()
        }
        AppCompatDelegate.setDefaultNightMode(currentNightMode())
    }

    /** 当前资源配置是否处于夜间模式（用于状态栏/导航栏图标颜色等自绘分支）。 */
    fun isNightActive(context: android.content.Context): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
}
