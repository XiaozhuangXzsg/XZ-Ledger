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

    // ---- 通知自动记账（全部本地，不上传）----
    /** 通知自动记账总开关。未开启时服务即使被系统绑定也不做任何解析。 */
    const val NOTIFY_ENABLED = "notify.enabled"
    /** 模式：off / confirm（待确认，默认）/ auto（自动记账）。 */
    const val NOTIFY_MODE = "notify.mode"
    /** 允许监听的应用包名（JSON 数组）。为空表示一个都不监听。 */
    const val NOTIFY_PACKAGES = "notify.packages"
    /** 允许「自动入账」的应用包名（JSON 数组）；空表示全部只进待确认。 */
    const val NOTIFY_AUTO_PACKAGES = "notify.auto_packages"
    const val NOTIFY_AUTO_INCOME = "notify.auto_income"
    const val NOTIFY_AUTO_EXPENSE = "notify.auto_expense"
    const val NOTIFY_TOAST = "notify.toast"
    /** 首次进入通知自动记账页的隐私说明是否已确认。 */
    const val NOTIFY_EXPLAINED = "notify.explained"
    /** 每日本地解析记录条数上限（超出后裁剪最旧的）。 */
    const val NOTIFY_LOG_KEEP = "notify.log_keep"

    // ---- 应用内更新（GitHub Releases）----
    /** 最近一次检查更新的时间戳（毫秒），用于「每天最多检查一次」。 */
    const val UPDATE_LAST_CHECK = "update.last_check"
    /** 用户点过「稍后再说」的时间戳，避免每次启动都打扰。 */
    const val UPDATE_SNOOZE_UNTIL = "update.snooze_until"
    /** 已忽略的版本号（versionCode），用户选择「跳过此版本」时写入。 */
    const val UPDATE_SKIPPED_CODE = "update.skipped_code"
}

/**
 * 内置默认链接：GitHub 仓库与它的最新 Release。
 * 「关于」里填了自定义地址就用自定义的；清空后回落到这里，保证「给项目 Star」「分享应用」始终可用。
 */
object AppLinks {
    const val GITHUB = "https://github.com/XiaozhuangXzsg/XZ-Ledger"
    const val RELEASE = "https://github.com/XiaozhuangXzsg/XZ-Ledger/releases/latest"
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
