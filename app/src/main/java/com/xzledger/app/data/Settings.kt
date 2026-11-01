package com.xzledger.app.data

import com.xzledger.app.ui.theme.Appearance

/**
 * 设置存储：全部落在本地 SQLite 的 setting 表，键值型。
 * 与业务数据一起离线可用；不含任何 API Key（API Key 单独存放于 ai_config，不进入普通导出）。
 */
object SettingsKeys {
    const val APPEARANCE_MODE = "appearance.mode"
    const val APPEARANCE_SEED = "appearance.seed"
    const val FONT_SCALE = "appearance.font_scale"
    const val ANIMATIONS = "appearance.animations"
    const val SMOOTH_ANIMATIONS = "appearance.smooth"
    const val GLASS = "appearance.glass"
    const val LANGUAGE = "appearance.language"
    const val ANIME = "appearance.anime"
    const val LIQUID_APPLE = "appearance.liquid_apple"
    const val LEDGER_ID = "app.ledger_id"
    const val NICKNAME = "user.nickname"
    const val AVATAR = "user.avatar"
    const val APP_LOCK = "security.app_lock"
    const val APP_LOCK_SECRET = "security.app_lock_secret"
    const val CALENDAR_PROMPTED = "perm.calendar_prompted"
    const val CALENDAR_READ = "perm.calendar_read"
    const val GITHUB_URL = "about.github"
    const val DOWNLOAD_URL = "about.download"
    const val FEEDBACK = "about.feedback"
    const val CLOUD_ENABLED = "sync.enabled"
    const val CLOUD_ENDPOINT = "sync.endpoint"
    const val CLOUD_TOKEN = "sync.token"
    const val BUDGET_ALERT = "budget.alert_enabled"
    /** 每月自动沿用的总预算模板 id 前缀（模板行 month = ALL，category_id = NULL）。 */
    const val BUDGET_AUTO_ID_PREFIX = "budget-auto-"
    const val REMINDER_TIME = "reminder.time"
    const val REMINDER_ENABLED = "reminder.enabled"
    const val EXPORT_INCLUDES_KEYS = "export.include_keys"
    /** 桌面小组件背景不透明度（0-100 的整数，100 = 完全不透明）。 */
    const val WIDGET_BG_ALPHA = "widget.bg_alpha"

    /** 桑多涅的月度评价：文本与生成日期（yyyy-MM-dd），一天最多生成一次。 */
    const val MINE_ADVICE_TEXT = "mine.advice_text"
    const val MINE_ADVICE_DATE = "mine.advice_date"
}

class Store(private val db: Db) {
    // 空字符串视为「未设置」，回落到默认值：避免历史迁移或异常写入留下的空值把默认值顶掉（曾导致 记账提醒 页 substring 崩溃）。
    fun str(key: String, def: String = ""): String = db.getSetting(key)?.ifEmpty { def } ?: def
    fun setStr(key: String, value: String) = db.setSetting(key, value)
    // 注意：getSetting 对缺失键返回 ""（不是 null），原来的 `?:` 分支永远不会走到，
    // 导致 bool(key, def = true) 在未设置时被当成 false（二次元元素/动画/玻璃的默认值曾被顶掉）。
    fun bool(key: String, def: Boolean = false): Boolean {
        val v = db.getSetting(key)
        return if (v.isEmpty()) def else v == "1"
    }
    fun setBool(key: String, value: Boolean) = db.setSetting(key, if (value) "1" else "0")
    fun float(key: String, def: Float): Float = db.getSetting(key)?.toFloatOrNull() ?: def
    fun setFloat(key: String, value: Float) = db.setSetting(key, value.toString())
    fun int(key: String, def: Int): Int = db.getSetting(key)?.trim()?.toIntOrNull() ?: def
    fun setInt(key: String, value: Int) = db.setSetting(key, value.toString())

    fun appearance(): Appearance = Appearance(
        mode = str(SettingsKeys.APPEARANCE_MODE, "system"),
        seed = str(SettingsKeys.APPEARANCE_SEED, "sandrone").ifBlank { "sandrone" },
        fontScale = float(SettingsKeys.FONT_SCALE, 1f).coerceIn(0.85f, 1.4f),
        animations = bool(SettingsKeys.ANIMATIONS, true),
        smoothAnimations = bool(SettingsKeys.SMOOTH_ANIMATIONS, true),
        glass = bool(SettingsKeys.GLASS, true),
        language = str(SettingsKeys.LANGUAGE, "zh"),
        anime = bool(SettingsKeys.ANIME, true),
        liquidApple = bool(SettingsKeys.LIQUID_APPLE, false)
    )

    fun saveAppearance(a: Appearance) {
        setStr(SettingsKeys.APPEARANCE_MODE, a.mode)
        setStr(SettingsKeys.APPEARANCE_SEED, a.seed)
        setFloat(SettingsKeys.FONT_SCALE, a.fontScale)
        setBool(SettingsKeys.ANIMATIONS, a.animations)
        setBool(SettingsKeys.SMOOTH_ANIMATIONS, a.smoothAnimations)
        setBool(SettingsKeys.GLASS, a.glass)
        setStr(SettingsKeys.LANGUAGE, a.language)
        setBool(SettingsKeys.ANIME, a.anime)
        setBool(SettingsKeys.LIQUID_APPLE, a.liquidApple)
    }
}
