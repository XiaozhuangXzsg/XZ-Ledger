package com.xzledger.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xzledger.app.XzApp

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        try {
            // 先处理周期任务：到期自动生成账单或仅提醒；去重键保证任何重试都不会重复入账。
            RecurringEngine.runDue(ctx)
        } catch (e: Exception) {
        }
        try {
            if (XzApp.store.bool(com.xzledger.app.data.SettingsKeys.REMINDER_ENABLED)) {
                Reminders.notify(ctx, Reminders.REQ_DAILY, "记账提醒", "别忘了记录今天的收支，打开猫猫记账记一笔吧。")
            }
        } catch (e: Exception) {
        }
        try { Reminders.reschedule(ctx) } catch (e: Exception) { }
    }
}
