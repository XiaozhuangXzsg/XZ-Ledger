package com.xzledger.app.autorecord

import android.content.Context
import android.content.pm.PackageManager
import com.xzledger.app.data.SettingsKeys
import com.xzledger.app.data.Store
import org.json.JSONArray

/**
 * 自动记账的模式与授权设置（全部落在本地 setting 表）。
 * 默认 = 待确认；自动记账必须显式开启，并且只对用户勾选过的应用生效。
 */
object NotifyPrefs {
    const val MODE_OFF = "off"
    const val MODE_CONFIRM = "confirm"
    const val MODE_AUTO = "auto"

    /** 自动入账的最低置信度；低于它的只能进待确认。 */
    const val AUTO_MIN_CONFIDENCE = 70

    fun enabled(store: Store): Boolean = store.bool(SettingsKeys.NOTIFY_ENABLED, false)

    fun mode(store: Store): String {
        val m = store.str(SettingsKeys.NOTIFY_MODE, MODE_CONFIRM)
        return if (m == MODE_OFF || m == MODE_AUTO) m else MODE_CONFIRM
    }

    fun setMode(store: Store, mode: String) = store.setStr(SettingsKeys.NOTIFY_MODE, mode)

    /** 正在监听哪些应用（包名白名单）。 */
    fun packages(store: Store): List<String> {
        val raw = store.str(SettingsKeys.NOTIFY_PACKAGES, "")
        if (raw.isBlank()) return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun setPackages(store: Store, list: List<String>) {
        val a = JSONArray()
        list.distinct().forEach { a.put(it) }
        store.setStr(SettingsKeys.NOTIFY_PACKAGES, a.toString())
    }

    /** 允许「自动入账」（而非待确认）的应用；空集合表示一个都不自动。 */
    fun autoPackages(store: Store): Set<String> {
        val raw = store.str(SettingsKeys.NOTIFY_AUTO_PACKAGES, "")
        if (raw.isBlank()) return emptySet()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }.toSet()
        } catch (t: Throwable) {
            emptySet()
        }
    }

    fun setAutoPackages(store: Store, list: Set<String>) {
        val a = JSONArray()
        list.distinct().forEach { a.put(it) }
        store.setStr(SettingsKeys.NOTIFY_AUTO_PACKAGES, a.toString())
    }

    fun autoIncome(store: Store): Boolean = store.bool(SettingsKeys.NOTIFY_AUTO_INCOME, true)
    fun autoExpense(store: Store): Boolean = store.bool(SettingsKeys.NOTIFY_AUTO_EXPENSE, true)
    fun showToast(store: Store): Boolean = store.bool(SettingsKeys.NOTIFY_TOAST, true)

    /** 系统「通知使用权」是否已授予本应用。 */
    fun hasAccess(context: Context): Boolean {
        val flat = android.provider.Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ) ?: return false
        val pkg = context.packageName
        return flat.split(":").any {
            it.startsWith(pkg + "/") || it == pkg
        }
    }

    /** 读取已安装、可启动的应用（用于「监听应用管理」列表，不臆造包名）。 */
    data class AppInfo(val pkg: String, val label: String)

    fun installedApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val list = try {
            pm.queryIntentActivities(intent, 0)
        } catch (t: Throwable) {
            emptyList()
        }
        return list.mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == context.packageName || pkg == context.packageName + ".debug") return@mapNotNull null
            val label = try {
                ri.loadLabel(pm).toString()
            } catch (t: Throwable) {
                pkg
            }
            AppInfo(pkg, label)
        }.distinctBy { it.pkg }.sortedBy { it.label }
    }

    fun labelOf(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (t: Throwable) {
        pkg
    }
}
