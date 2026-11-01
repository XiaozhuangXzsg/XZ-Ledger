package com.xzledger.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * 供应用内数据变化后主动刷新小组件用（应用内调用点由业务方自行添加）。
 *
 * 用法：WidgetRefresh.updateAll(context)
 * 注意：建议在后台线程调用；内部全部 try/catch，不会影响主流程。
 */
object WidgetRefresh {

    @JvmStatic
    fun updateAll(context: Context) {
        val app = context.applicationContext
        try {
            val manager = AppWidgetManager.getInstance(app) ?: return
            notifyProvider(app, manager, WideWidgetProvider::class.java)
            notifyProvider(app, manager, SquareWidgetProvider::class.java)
        } catch (t: Throwable) {
            // 没有放置小组件 / 服务不可用时静默返回
        }
    }

    private fun notifyProvider(app: Context, manager: AppWidgetManager, provider: Class<*>) {
        try {
            val ids = manager.getAppWidgetIds(ComponentName(app, provider))
            if (ids == null || ids.isEmpty()) return
            val intent = Intent(app, provider).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            app.sendBroadcast(intent)
        } catch (t: Throwable) {
        }
    }
}
