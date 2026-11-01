package com.xzledger.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import com.xzledger.app.MainActivity
import com.xzledger.app.R
import com.xzledger.app.util.Money
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 桌面小组件基类（RemoteViews，不使用 Glance / Compose）。
 *
 * 两个尺寸各注册一个独立 Provider（见本文件末尾 WideWidgetProvider / SquareWidgetProvider），
 * 二者布局不同但共用同一套 id 与渲染逻辑。
 */
abstract class CatWidgetProvider : AppWidgetProvider() {

    private companion object {
        const val TAG = "XZWidget"
    }

    /** 4x2 用 R.layout.widget_wide，2x2 用 R.layout.widget_square。 */
    protected abstract val layoutRes: Int

    /** 预算环形条位图边长（dp），与布局里 img_budget_ring 的尺寸一致，避免缩放导致中心百分比发虚。 */
    protected abstract val ringSizeDp: Int

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id -> updateOne(context, appWidgetManager, id) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        updateOne(context, appWidgetManager, appWidgetId)
    }

    override fun onEnabled(context: Context) {
        WidgetRefresh.updateAll(context)
    }

    override fun onDisabled(context: Context) {
        WidgetRefresh.updateAll(context)
    }

    private fun updateOne(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
        try {
            val views = RemoteViews(context.packageName, layoutRes)
            val data = WidgetData.load(context)
            render(context, views, data)
            applyBackground(context, views, data.widgetBgAlphaPct, data.glassEnabled)
            views.setTextViewText(R.id.txt_date, dateText())

            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

            selfCheck(context, views, appWidgetId)
            manager.updateAppWidget(appWidgetId, views)
        } catch (t: Throwable) {
            // 渲染失败就维持上一帧，绝不把异常抛回系统（Launcher 会直接显示崩溃）
            Log.e(TAG, "updateOne id=" + appWidgetId + " failed", t)
        }
    }

    /** 自查：在我们自己的进程里把这份 RemoteViews 真正 inflate + apply 一遍。
     *  应用侧抛出的任何资源/布局错误都会在这里被记录下来，不用等桌面报「载入窗口小部件时出现问题」。 */
    private fun selfCheck(context: Context, views: RemoteViews, appWidgetId: Int) {
        if (!com.xzledger.app.BuildConfig.DEBUG) return
        try {
            val host = FrameLayout(context)
            views.apply(context, host)
            Log.i(TAG, "selfCheck ok id=" + appWidgetId + " children=" + host.childCount)
        } catch (t: Throwable) {
            Log.e(TAG, "selfCheck failed id=" + appWidgetId, t)
        }
    }

    /** 桌面小组件背景透明度：给圆角底色上一层带 alpha 的 tint（金色描边在 foreground，不受影响）。
     *  液态玻璃高光层跟随全局开关显隐；关闭时只剩纯色圆角卡片。 */
    private fun applyBackground(context: Context, views: RemoteViews, alphaPct: Int, glass: Boolean) {
        val alpha = (255 * alphaPct.coerceIn(0, 100)) / 100
        val base = try {
            context.getColor(R.color.xz_dark)
        } catch (t: Throwable) {
            0xFF1A1714.toInt()
        }
        val color = Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base))
        views.setColorStateList(R.id.widget_root, "setBackgroundTintList", ColorStateList.valueOf(color))
        views.setViewVisibility(R.id.widget_gloss, if (glass) View.VISIBLE else View.GONE)
    }

    private fun render(context: Context, views: RemoteViews, data: WidgetData.Snapshot) {
        views.setTextViewText(R.id.txt_today_expense, money(data.todayExpense))
        views.setTextViewText(R.id.txt_today_income, money(data.todayIncome))
        // 所有桌面小组件都显示本月结余（收入 - 净支出）
        views.setTextViewText(
            R.id.txt_balance,
            context.getString(R.string.widget_balance_prefix) + money(data.balance)
        )

        // 今日支出 / 收入：以二者较大值作为 100% 的参照，非零值至少画 1%，保证肉眼可见。
        val maxToday = maxOf(data.todayExpense, data.todayIncome, 1L)
        val expensePct = if (data.todayExpense > 0L) ((data.todayExpense * 100L) / maxToday).toInt() else 0
        val incomePct = if (data.todayIncome > 0L) ((data.todayIncome * 100L) / maxToday).toInt() else 0
        views.setProgressBar(R.id.bar_expense, 100, expensePct.coerceIn(0, 100), false)
        views.setProgressBar(R.id.bar_income, 100, incomePct.coerceIn(0, 100), false)

        // 预算改为环形比例条：RemoteViews 画不了弧，改为画一张圆环位图塞进 ImageView。
        val ringPx = (ringSizeDp * context.resources.displayMetrics.density).toInt().coerceAtMost(320)
        if (!data.hasBudget) {
            views.setTextViewText(R.id.txt_budget_value, context.getString(R.string.widget_budget_unset))
            views.setViewVisibility(R.id.img_budget_ring, View.INVISIBLE)
            return
        }

        // 预算剩余按结余算：预算 + 结余（= 预算 + 收入 - 净支出）
        val remaining = data.budgetRemaining
        views.setViewVisibility(R.id.img_budget_ring, View.VISIBLE)
        if (data.overBudget) {
            views.setTextViewText(
                R.id.txt_budget_value,
                context.getString(R.string.widget_budget_over_prefix) + "¥" + Money.centsToPlain(-remaining)
            )
            views.setImageViewBitmap(R.id.img_budget_ring, BudgetRing.render(ringPx, data.overBudgetPct, true))
        } else {
            views.setTextViewText(
                R.id.txt_budget_value,
                context.getString(R.string.widget_budget_remaining_prefix) + "¥" + Money.centsToPlain(remaining)
            )
            views.setImageViewBitmap(R.id.img_budget_ring, BudgetRing.render(ringPx, data.budgetUsedBarPct, false))
        }
    }

    /** 负数显示为 -¥12.34，而不是 ¥-12.34。 */
    private fun money(cents: Long): String =
        if (cents < 0L) "-¥" + Money.centsToPlain(-cents) else "¥" + Money.centsToPlain(cents)

    private fun dateText(): String =
        SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(Date())
}

/** 4x2 宽条小组件。 */
class WideWidgetProvider : CatWidgetProvider() {
    override val layoutRes: Int get() = R.layout.widget_wide
    override val ringSizeDp: Int get() = 60
}

/** 2x2 方形小组件：预算环形条是主体。 */
class SquareWidgetProvider : CatWidgetProvider() {
    override val layoutRes: Int get() = R.layout.widget_square
    override val ringSizeDp: Int get() = 52
}
