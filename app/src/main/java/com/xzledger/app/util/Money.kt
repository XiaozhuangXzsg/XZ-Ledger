package com.xzledger.app.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat

/**
 * 金额一律以「最小货币单位」(分) 的 Long 保存，避免浮点误差。
 * 展示与输入解析集中在此处，保证全局口径一致。
 */
object Money {
    const val SCALE = 100L

    /** 解析用户输入（元）为分。支持 "12"、"12.3"、"12.34"、"1,234.56"、"12.00"。 */
    fun parseYuanToCents(input: String?): Long? {
        if (input == null) return null
        var s = input.trim()
        if (s.isEmpty()) return null
        s = s.replace(",", "").replace("，", "")
        s = s.replace("¥", "").replace("￥", "").replace("元", "").trim()
        if (s.isEmpty()) return null
        return try {
            BigDecimal(s).setScale(2, RoundingMode.HALF_UP).movePointRight(2).toLong()
        } catch (e: NumberFormatException) {
            null
        }
    }

    /** 分为单位 -> 元的普通字符串，永远保留两位小数 */
    fun centsToPlain(cents: Long): String {
        val neg = cents < 0
        val v = if (neg) -cents else cents
        val yuan = v / 100
        val fen = v % 100
        return (if (neg) "-" else "") + DecimalFormat("#,##0").format(yuan) + "." + (if (fen < 10) "0" + fen else "" + fen)
    }

    /** 分为单位 -> 不带千分位的两位小数字符串（用于导出/编辑框） */
    fun centsToEdit(cents: Long): String {
        val neg = cents < 0
        val v = if (neg) -cents else cents
        val yuan = v / 100
        val fen = v % 100
        return (if (neg) "-" else "") + yuan + "." + (if (fen < 10) "0" + fen else "" + fen)
    }

    /** 带符号显示：支出为 "-"，收入为 "+" */
    fun signed(cents: Long, positive: Boolean): String = (if (positive) "+" else "-") + centsToPlain(cents)

    /** 解析可能带千分位/货币符号/括号负数的导入文本 */
    fun parseLoose(input: String?): Long? {
        if (input == null) return null
        var s = input.trim()
        if (s.isEmpty()) return null
        var neg = false
        if (s.startsWith("(") && s.endsWith(")")) { neg = true; s = s.substring(1, s.length - 1) }
        s = s.replace(",", "").replace("，", "")
            .replace("¥", "").replace("￥", "").replace("元", "").replace(" ", "").trim()
        if (s.startsWith("-")) { neg = true; s = s.substring(1) }
        if (s.startsWith("+")) s = s.substring(1)
        if (s.isEmpty() || s == ".") return null
        val cents = try {
            BigDecimal(s).setScale(2, RoundingMode.HALF_UP).movePointRight(2).toLong()
        } catch (e: NumberFormatException) { return null }
        return if (neg) -cents else cents
    }
}
