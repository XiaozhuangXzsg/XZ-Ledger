package com.xzledger.app.notify

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.xzledger.app.MainActivity
import com.xzledger.app.R
import com.xzledger.app.XzApp
import com.xzledger.app.data.SettingsKeys
import java.util.Calendar

object Reminders {
    const val CHANNEL_ID = "xz_reminder"
    const val REQ_DAILY = 1001

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, "记账提醒", NotificationManager.IMPORTANCE_DEFAULT)
        ch.description = "每日记账提醒与周期记账／订阅续费提醒"
        nm.createNotificationChannel(ch)
    }

    private fun pending(ctx: Context): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction("com.xzledger.app.DAILY_REMINDER")
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(ctx, REQ_DAILY, i, flags)
    }

    fun reschedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(ctx)
        am.cancel(pi)
        val store = XzApp.store
        if (!store.bool(SettingsKeys.REMINDER_ENABLED)) return
        val t = store.str(SettingsKeys.REMINDER_TIME, "21:00").takeIf { it.length >= 5 } ?: "21:00"
        val hh = t.substring(0, 2).toIntOrNull() ?: 21
        val mm = if (t.length >= 5) t.substring(3, 5).toIntOrNull() ?: 0 else 0
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hh); set(Calendar.MINUTE, mm)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_MONTH, 1)
        }
        val at = cal.timeInMillis
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                // 未获精确闹钟权限时退回非精确闹钟，绝不静默失败。
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, SystemClock.elapsedRealtime() + 60_000L, pi)
        }
    }

    fun notify(ctx: Context, id: Int, title: String, text: String) {
        ensureChannel(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val content = PendingIntent.getActivity(
            ctx, id, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(content)
            .build()
        try { nm.notify(id, n) } catch (e: SecurityException) { }
    }
}
