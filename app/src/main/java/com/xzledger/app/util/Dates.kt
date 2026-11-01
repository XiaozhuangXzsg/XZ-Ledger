package com.xzledger.app.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 账目日期规则：
 *  - occurred_date 用本地日历日 'yyyy-MM-dd' 字符串保存，按天/按月统计只用它，绝不重新换算时区，
 *    避免设备时区变化导致账目日期错误。
 *  - occurred_at 保存毫秒时间戳用于排序与展示具体时刻。
 *  - created_at / updated_at 为技术同步时间，与消费日期完全分开保存。
 */
object Dates {
    const val DAY = "yyyy-MM-dd"
    const val MONTH = "yyyy-MM"
    const val MINUTE = "yyyy-MM-dd HH:mm"

    fun dayKey(millis: Long, zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat(DAY, Locale.CHINA).apply { timeZone = zone }.format(Date(millis))

    fun monthKey(millis: Long): String = dayKey(millis).substring(0, 7)

    fun monthOf(dayKey: String): String = if (dayKey.length >= 7) dayKey.substring(0, 7) else dayKey

    fun parseDay(dayKey: String): Long? = try {
        SimpleDateFormat(DAY, Locale.CHINA).parse(dayKey)?.time
    } catch (e: Exception) { null }

    /** 构造本地时间：年/月(0基)/日/时/分 -> millis */
    fun toMillis(year: Int, month0: Int, day: Int, hour: Int, minute: Int): Long {
        val c = Calendar.getInstance()
        c.set(year, month0, day, hour, minute, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun startOfDay(dayKey: String): Long {
        val p = parseDay(dayKey) ?: return System.currentTimeMillis()
        val c = Calendar.getInstance().apply { timeInMillis = p }
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun endOfDay(dayKey: String): Long = startOfDay(dayKey) + 86400000L - 1

    /** 某月第一天 00:00 millis */
    fun startOfMonthMillis(monthKey: String): Long {
        val y = monthKey.substring(0, 4).toInt()
        val m = monthKey.substring(5, 7).toInt() - 1
        val c = Calendar.getInstance()
        c.set(y, m, 1, 0, 0, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** 某月最后一天 23:59:59.999 millis */
    fun endOfMonthMillis(monthKey: String): Long {
        val c = Calendar.getInstance().apply { timeInMillis = startOfMonthMillis(monthKey) }
        c.add(Calendar.MONTH, 1); c.add(Calendar.MILLISECOND, -1)
        return c.timeInMillis
    }

    fun daysInMonth(monthKey: String): Int {
        val c = Calendar.getInstance().apply { timeInMillis = startOfMonthMillis(monthKey) }
        return c.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    /** 星期几，1=周一 ... 7=周日 */
    fun isoDayOfWeek(dayKey: String): Int {
        val c = Calendar.getInstance().apply { timeInMillis = startOfDay(dayKey) }
        val d = c.get(Calendar.DAY_OF_WEEK)
        return if (d == Calendar.SUNDAY) 7 else d - 1
    }

    fun shiftMonth(monthKey: String, delta: Int): String {
        val c = Calendar.getInstance().apply { timeInMillis = startOfMonthMillis(monthKey) }
        c.add(Calendar.MONTH, delta)
        return SimpleDateFormat(MONTH, Locale.CHINA).format(c.time)
    }

    fun shiftDay(dayKey: String, delta: Int): String {
        val c = Calendar.getInstance().apply { timeInMillis = startOfDay(dayKey) }
        c.add(Calendar.DAY_OF_MONTH, delta)
        return SimpleDateFormat(DAY, Locale.CHINA).format(c.time)
    }

    fun formatMinute(millis: Long): String =
        SimpleDateFormat(MINUTE, Locale.CHINA).format(Date(millis))

    fun formatDayHuman(dayKey: String): String {
        val today = today()
        if (dayKey == today) return "今天"
        if (dayKey == shiftDay(today, -1)) return "昨天"
        val p = parseDay(dayKey) ?: return dayKey
        val cal = Calendar.getInstance().apply { timeInMillis = p }
        val w = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        val idx = cal.get(Calendar.DAY_OF_WEEK) - 1
        return dayKey + " " + w[idx]
    }

    /** 系统日期选择控件用的是 UTC 零点，这里做与本地日期字符串之间的无损换算。 */
    fun utcDayMillis(dayKey: String): Long = try {
        SimpleDateFormat(DAY, Locale.CHINA).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(dayKey)?.time ?: System.currentTimeMillis()
    } catch (e: Exception) { System.currentTimeMillis() }

    fun fromUtcDayMillis(millis: Long): String =
        SimpleDateFormat(DAY, Locale.CHINA).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))

    fun today(): String = dayKey(System.currentTimeMillis())
    fun thisMonth(): String = monthKey(System.currentTimeMillis())

    /** 某月第一天/最后一天的日期字符串（'yyyy-MM-dd'），用于按月筛选与统计。 */
    fun startOfMonth(monthKey: String): String = monthKey + "-01"
    fun endOfMonth(monthKey: String): String {
        val d = daysInMonth(monthKey)
        return monthKey + "-" + (if (d < 10) "0" + d else d.toString())
    }

    /** 'yyyy-MM' -> '2026年2月' */
    fun monthLabel(monthKey: String): String {
        if (monthKey.length < 7) return monthKey
        val y = monthKey.substring(0, 4)
        val m = monthKey.substring(5, 7).toIntOrNull() ?: 1
        return y + "年" + m + "月"
    }

    /** 'yyyy-MM-dd' -> '2月14日 周五'，今天/昨天优先 */
    fun dayHeader(dayKey: String): String {
        if (dayKey == today()) return "今天"
        if (dayKey == shiftDay(today(), -1)) return "昨天"
        if (dayKey.length < 10) return dayKey
        val m = dayKey.substring(5, 7).toIntOrNull() ?: 1
        val d = dayKey.substring(8, 10).toIntOrNull() ?: 1
        val w = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        val idx = (isoDayOfWeek(dayKey) - 1).coerceIn(0, 6)
        return m.toString() + "月" + d + "日 " + w[idx]
    }

    /** 'yyyy-MM-dd HH:mm' 的时分部分 */
    fun timeOf(occurredAt: Long): String = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(occurredAt))

    fun formatDateTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(millis))

    fun hourOf(millis: Long): Int = Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.HOUR_OF_DAY)

    fun minuteOf(millis: Long): Int = Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.MINUTE)
}
