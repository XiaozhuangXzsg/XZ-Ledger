package com.xzledger.app.widget

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.xzledger.app.data.SettingsKeys
import java.io.File
import java.util.Calendar
import java.util.Locale

/**
 * 桌面小组件的数据层。
 *
 * 直接以「只读」方式打开 Room 的 xz_ledger.db（不引入任何新依赖、不经过 Room 实例），
 * 口径与 data/TxnDao.kt 保持一致：
 *  - 排除 deleted=1；
 *  - 排除 tx_status IN ('closed','pending')；
 *  - 时间按 occurred_date（本地日历日 'yyyy-MM-dd'）比较；
 *  - 金额单位为「分」(Long)；
 *  - 净支出 = expense - refund。
 *
 * 任何查询异常（库不存在 / 表结构不匹配 / 打不开）都吞掉并返回空快照，绝不抛异常。
 */
object WidgetData {

    const val DB_NAME = "xz_ledger.db"
    const val DEFAULT_LEDGER_ID = "ledger-default"

    /** 今日：仅 expense / income，按当天 occurred_date。 */
    private const val SQL_TODAY =
        "SELECT type, COALESCE(SUM(amount),0) FROM txn " +
            "WHERE ledger_id=? AND deleted=0 AND occurred_date=? " +
            "AND tx_status NOT IN ('closed','pending') AND type IN ('expense','income') GROUP BY type"

    /** 本月：expense / refund（净支出）与 income（结余、预算剩余都要用）。 */
    private const val SQL_MONTH =
        "SELECT type, COALESCE(SUM(amount),0) FROM txn " +
            "WHERE ledger_id=? AND deleted=0 AND occurred_date BETWEEN ? AND ? " +
            "AND tx_status NOT IN ('closed','pending') AND type IN ('expense','refund','income') GROUP BY type"

    /** 预算：与 TxnDao.budgets() 同序（具体月在前，ALL 在后），取第一条 category_id IS NULL 的总额预算。 */
    private const val SQL_BUDGET =
        "SELECT amount, category_id, month FROM budget " +
            "WHERE ledger_id=? AND deleted=0 AND (month=? OR month='ALL') ORDER BY month DESC"

    private const val SQL_LEDGER =
        "SELECT value FROM setting WHERE key=?"

    /** 小组件一次刷新所需的全部数据。金额单位均为「分」。 */
    data class Snapshot(
        val todayExpense: Long = 0L,
        val todayIncome: Long = 0L,
        val monthIncome: Long = 0L,
        /** 本月净支出 = 支出 - 退款。 */
        val monthNetExpense: Long = 0L,
        /**
         * 本月结余：月预算（未设置按 0）+ 本月收入 - 本月净支出。
         * 与明细页「结余」同口径；预算剩余也直接等于它。
         */
        val balance: Long = 0L,
        val budgetTotal: Long? = null,
        val budgetUsed: Long = 0L,
        val budgetRemainPct: Int? = null,
        val budgetUsedBarPct: Int = 0,
        val overBudget: Boolean = false,
        val overBudgetPct: Int = 0,
        /** 桌面小组件背景不透明度 0-100（100 = 不透明）。 */
        val widgetBgAlphaPct: Int = 95,
        /** 是否启用液态玻璃高光层（跟随「个性化设置 → 液态玻璃效果」）。 */
        val glassEnabled: Boolean = true
    ) {
        val hasBudget: Boolean get() = budgetRemainPct != null

        /** 预算剩余按结余算：结余本身已经含月预算，所以剩余就是结余。 */
        val budgetRemaining: Long get() = balance
    }

    /** 读一次数据库；任何异常都返回空快照。 */
    fun load(context: Context): Snapshot {
        val app = context.applicationContext
        return try {
            val file = app.getDatabasePath(DB_NAME)
            if (!file.exists()) {
                Snapshot()
            } else {
                val db = openReadOnly(file)
                try {
                    query(db)
                } finally {
                    try {
                        db.close()
                    } catch (ignore: Throwable) {
                    }
                }
            }
        } catch (t: Throwable) {
            Snapshot()
        }
    }

    /**
     * 按规定用 OPEN_READ_ONLY 打开；只有在只读打开失败时（例如 WAL 需要恢复）才退回读写模式，
     * 且此时文件已确认存在，不会新建库。两种方式失败都会被 load() 捕获。
     */
    private fun openReadOnly(file: File): SQLiteDatabase {
        return try {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: SQLiteException) {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        }
    }

    private fun query(db: SQLiteDatabase): Snapshot {
        val ledgerId = readLedgerId(db)
        val alphaPct = readWidgetAlphaPct(db)
        val glass = readGlass(db)

        val cal = Calendar.getInstance()
        val today = dayKey(cal)
        val month = monthKey(cal)
        val start = month + "-01"
        val end = month + "-" + pad2(cal.getActualMaximum(Calendar.DAY_OF_MONTH))

        var todayExpense = 0L
        var todayIncome = 0L
        db.rawQuery(SQL_TODAY, arrayOf(ledgerId, today)).use { c ->
            while (c.moveToNext()) {
                when (c.getString(0)) {
                    "expense" -> todayExpense += c.getLong(1)
                    "income" -> todayIncome += c.getLong(1)
                }
            }
        }

        var monthExpense = 0L
        var monthRefund = 0L
        var monthIncome = 0L
        db.rawQuery(SQL_MONTH, arrayOf(ledgerId, start, end)).use { c ->
            while (c.moveToNext()) {
                when (c.getString(0)) {
                    "expense" -> monthExpense += c.getLong(1)
                    "refund" -> monthRefund += c.getLong(1)
                    "income" -> monthIncome += c.getLong(1)
                }
            }
        }
        val netExpense = monthExpense - monthRefund
        val total = readTotalBudget(db, ledgerId, month)
        // 结余 = 月预算 + 本月收入 - 本月净支出（与明细页同口径）；未设置预算时预算按 0 计。
        val balance = (total ?: 0L) + monthIncome - netExpense

        if (total == null || total <= 0L) {
            return Snapshot(
                todayExpense = todayExpense,
                todayIncome = todayIncome,
                monthIncome = monthIncome,
                monthNetExpense = netExpense,
                balance = balance,
                widgetBgAlphaPct = alphaPct,
                glassEnabled = glass
            )
        }

        // 预算剩余直接取结余：结余 = 预算 + 收入 - 净支出。
        // 对应地「已用」= 净支出 - 收入（收入多于支出时为负，按 0 处理，此时剩余会大于预算）。
        val netUsed = netExpense - monthIncome
        val usedForPct = if (netUsed > 0L) netUsed else 0L
        val usedPct = ((usedForPct * 100L) / total).toInt()
        val remainPct = (((total - usedForPct).coerceAtLeast(0L) * 100L) / total).toInt().coerceIn(0, 100)
        return Snapshot(
            todayExpense = todayExpense,
            todayIncome = todayIncome,
            monthIncome = monthIncome,
            monthNetExpense = netExpense,
            balance = balance,
            budgetTotal = total,
            budgetUsed = netExpense,
            budgetRemainPct = remainPct,
            budgetUsedBarPct = usedPct.coerceIn(0, 100),
            overBudget = usedForPct > total,
            overBudgetPct = usedPct,
            widgetBgAlphaPct = alphaPct,
            glassEnabled = glass
        )
    }

    /** 小组件背景不透明度：读设置表，缺省 95（= 原来的 #F2 透明度）。 */
    private fun readWidgetAlphaPct(db: SQLiteDatabase): Int {
        return try {
            (readSetting(db, SettingsKeys.WIDGET_BG_ALPHA)?.trim()?.toIntOrNull() ?: 95).coerceIn(0, 100)
        } catch (t: Throwable) {
            95
        }
    }

    /** 液态玻璃开关：与 Store.bool 同口径（空值 = 默认开启，'1' = 开启）。 */
    private fun readGlass(db: SQLiteDatabase): Boolean {
        val v = readSetting(db, SettingsKeys.GLASS)
        return if (v.isNullOrEmpty()) true else v == "1"
    }

    private fun readSetting(db: SQLiteDatabase, key: String): String? {
        return try {
            db.rawQuery(SQL_LEDGER, arrayOf(key)).use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun readLedgerId(db: SQLiteDatabase): String {
        return try {
            db.rawQuery(SQL_LEDGER, arrayOf(SettingsKeys.LEDGER_ID)).use { c ->
                if (c.moveToFirst()) {
                    val v = c.getString(0)
                    if (v != null && v.isNotBlank()) v else DEFAULT_LEDGER_ID
                } else {
                    DEFAULT_LEDGER_ID
                }
            }
        } catch (t: Throwable) {
            DEFAULT_LEDGER_ID
        }
    }

    /** 取总额预算（category_id IS NULL）。没有记录或金额<=0 时返回 null = 未设置预算。 */
    private fun readTotalBudget(db: SQLiteDatabase, ledgerId: String, month: String): Long? {
        return try {
            db.rawQuery(SQL_BUDGET, arrayOf(ledgerId, month)).use { c ->
                while (c.moveToNext()) {
                    if (c.isNull(1)) {
                        val amount = c.getLong(0)
                        return if (amount > 0L) amount else null
                    }
                }
                null
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun pad2(v: Int): String = if (v < 10) "0" + v else "" + v

    private fun dayKey(cal: Calendar): String =
        String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))

    private fun monthKey(cal: Calendar): String =
        String.format(Locale.US, "%04d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
}
