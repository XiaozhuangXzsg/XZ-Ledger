package com.xzledger.app.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat

/**
 * 系统日历读取：仅在用户明确授权后用于可选的系统日程关联展示。
 * 内部收支日历完全由账单日期生成，不依赖这里。
 */
object SystemCalendar {
    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun eventsOn(ctx: Context, dayKey: String): List<String> {
        if (!hasPermission(ctx)) return emptyList()
        val start = Dates.startOfDay(dayKey)
        val end = Dates.endOfDay(dayKey)
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(start.toString()).appendPath(end.toString()).build()
        val out = ArrayList<String>()
        try {
            ctx.contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val t = c.getString(0)
                    if (!t.isNullOrBlank()) out.add(t)
                }
            }
        } catch (e: Exception) { }
        return out
    }
}
