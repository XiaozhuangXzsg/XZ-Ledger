package com.xzledger.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 设备重启、应用更新、时间或时区变化后恢复提醒与周期任务。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        try {
            Reminders.reschedule(ctx)
            RecurringEngine.runDue(ctx)
        } catch (e: Exception) {
        }
    }
}
