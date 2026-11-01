package com.xzledger.app.notify

import android.content.Context
import com.xzledger.app.XzApp
import com.xzledger.app.data.ClassifyBasis
import com.xzledger.app.data.Ids
import com.xzledger.app.data.Txn
import com.xzledger.app.data.TxnSource
import com.xzledger.app.data.TxnType
import com.xzledger.app.data.allRecurring
import com.xzledger.app.data.recordRecurringRun
import com.xzledger.app.data.saveTxn
import com.xzledger.app.data.upsertRecurring
import com.xzledger.app.util.Dates
import com.xzledger.app.util.Money
import java.util.Calendar

/**
 * 周期记账与订阅提醒引擎。
 * 幂等保证：每个任务每个到期日只有一个去重键，先检查 last_run_key，再写入数据库唯一约束的 dedup_key，
 * 因此重试、进程被杀重启、系统时间变化都不会重复入账。
 */
object RecurringEngine {

    fun runDue(ctx: Context): Int {
        val db = XzApp.db
        val now = System.currentTimeMillis()
        var created = 0
        val tasks = try { db.allRecurring() } catch (e: Exception) { return 0 }
        for (t in tasks) {
            if (!t.enabled || t.deleted) continue
            val due = t.nextDueAt
            if (due <= 0L) continue
            // 提前 advanceDays 天进入提醒窗口
            if (now < due - t.advanceDays * 86_400_000L) continue
            val dueDay = Dates.dayKey(due, java.util.TimeZone.getDefault())
            val runKey = "recurring|" + t.id + "|" + dueDay
            if (runKey != t.lastRunKey) {
                if (t.mode == "auto") {
                    val txn = Txn(
                        id = Ids.newId(),
                        ledgerId = t.ledgerId,
                        type = t.type,
                        amount = t.amount,
                        nature = t.nature,
                        categoryId = t.categoryId,
                        accountId = t.accountId,
                        title = t.name,
                        merchant = t.merchant,
                        note = if (t.note.isEmpty()) "周期记账自动生成" else t.note,
                        occurredDate = dueDay,
                        occurredAt = due,
                        source = TxnSource.RECURRING,
                        sourcePlatform = "recurring",
                        dedupKey = runKey,
                        classifyBasis = ClassifyBasis.RULE
                    )
                    val out = try { db.saveTxn(txn) } catch (e: Exception) {
                        com.xzledger.app.data.SaveOutcome(false, null, e.message ?: "写入失败")
                    }
                    if (out.ok) created++
                } else {
                    Reminders.notify(
                        ctx, 2000 + Math.abs(t.id.hashCode() % 1000),
                        "周期记账提醒：" + t.name,
                        "¥" + Money.centsToPlain(t.amount) + " 于 " + dueDay + " 到期（仅提醒，不会自动记账）。确认已支付后可在记账页补记。"
                    )
                }
                try { db.recordRecurringRun(t.id, runKey, now) } catch (e: Exception) { }
            }
            val next = advance(t.nextDueAt, t.period, now)
            try { db.upsertRecurring(t.copy(nextDueAt = next, lastRunAt = t.lastRunAt ?: now, lastRunKey = runKey)) } catch (e: Exception) { }
        }
        return created
    }

    private fun advance(from: Long, period: String, now: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = from }
        var guard = 0
        do {
            when (period) {
                "daily" -> cal.add(Calendar.DAY_OF_MONTH, 1)
                "weekly" -> cal.add(Calendar.WEEK_OF_YEAR, 1)
                "yearly" -> cal.add(Calendar.YEAR, 1)
                else -> cal.add(Calendar.MONTH, 1)
            }
            guard++
        } while (cal.timeInMillis <= now && guard < 600)
        return cal.timeInMillis
    }

    /** 按天的下一个到期时间（新建任务时使用）。 */
    fun nextDue(period: String, dayOfMonth: Int, hour: Int = 9, minute: Int = 0): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        when (period) {
            "daily" -> if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_MONTH, 1)
            "weekly" -> if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_MONTH, 7)
            "yearly" -> {
                cal.set(Calendar.DAY_OF_MONTH, Math.min(dayOfMonth.coerceAtLeast(1), 28))
                if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.YEAR, 1)
            }
            else -> {
                cal.set(Calendar.DAY_OF_MONTH, Math.min(dayOfMonth.coerceAtLeast(1), 28))
                if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.MONTH, 1)
            }
        }
        return cal.timeInMillis
    }
}
