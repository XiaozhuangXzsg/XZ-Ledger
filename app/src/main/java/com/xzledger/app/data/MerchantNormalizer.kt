package com.xzledger.app.data

/**
 * 商户名规范化。
 *
 * 设计原则（对应需求第九节）：
 * 1. 原始文本始终保存在 Txn.merchantRaw，本对象只产生「用于比较」的规范化字符串；
 * 2. 只裁掉平台前缀、支付渠道后缀、公司后缀、空白与全角符号等确定无意义的成分；
 * 3. 绝不把不同商户合并成一个：不做拼音化、不做首字母缩写、不做模糊包含判断。
 */
object MerchantNormalizer {

    /** 常见平台/渠道噪声：出现在开头或结尾时裁掉。 */
    private val prefixNoise = listOf(
        "支付宝", "微信支付", "微信", "财付通", "银联", "云闪付", "花呗", "余额宝", "美团支付", "抖音支付", "京东支付"
    )
    private val suffixNoise = listOf(
        "（北京）有限公司", "（上海）有限公司", "有限公司", "有限责任公司", "股份有限公司",
        "分公司", "旗舰店", "专营店", "专卖店", "官方旗舰店", "官方店", "商户", "店铺", "收款方"
    )

    /** 全角转半角（仅 ASCII 区），保留中文与其他字符。 */
    private fun toHalfWidth(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            sb.append(
                when {
                    ch.code == 0x3000 -> ' '
                    ch.code in 0xFF01..0xFF5E -> (ch.code - 0xFEE0).toChar()
                    else -> ch
                }
            )
        }
        return sb.toString()
    }

    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var s = toHalfWidth(raw).trim()
        s = s.replace(Regex("[\\s\\u00A0\\u2007\\u202F]+"), "")
        s = s.trim('-', '_', '·', '.', ':', '|', '/', '\\', '*', '#')
        // 支付渠道前缀，例如 "支付宝-星巴克" / "微信支付:瑞幸咖啡"
        for (p in prefixNoise) {
            if (s.startsWith(p)) { s = s.removePrefix(p).trim('-', '_', '·', '.', ':', '|', '/', '*', '#'); break }
        }
        for (p in prefixNoise) {
            if (s.endsWith(p)) { s = s.removeSuffix(p).trim('-', '_', '·', '.', ':', '|', '/', '*', '#'); break }
        }
        for (p in suffixNoise) {
            if (s.length > p.length && s.endsWith(p)) { s = s.removeSuffix(p).trim(); break }
        }
        // 去掉常见无意义尾注
        s = s.replace(Regex("[(（](订单|交易|支付)[^)）]*[)）]"), "")
        s = s.trim()
        return s.lowercase()
    }

    /** 展示用：仅压缩空白，尽量保持原样。 */
    fun displayName(raw: String?): String = toHalfWidth(raw ?: "").trim().replace(Regex("[\\s]+"), " ")

    /** 商户是否可用于建立「记住该商户」规则：过短或纯数字的商户名不建规则，避免错误合并。 */
    fun isRuleEligible(norm: String): Boolean {
        if (norm.length < 2) return false
        if (norm.matches(Regex("^[0-9]+$"))) return false
        return true
    }
}

/**
 * 去重键生成：覆盖手动、AI 聊天、截图、Excel、PDF、CSV、云同步全部入口。
 *
 * - 优先稳定交易单号（tradeNo），订单编码（orderNo）作辅助；
 * - 平台参与拼接，因此不同平台的相同编码不会互相判重；
 * - 单号按字符串保存，保留前导零与长数字；
 * - 退款是独立交易事件，不会因为与原订单同码而被跳过；
 * - 拆分记账时用 splitIndex 区分同一订单的多条拆分记录，金额合计需等于实付金额；
 * - 没有任何可靠标识时返回空串，交给疑似重复核对流程。
 */
object DedupKeys {

    fun normNo(s: String?): String {
        if (s.isNullOrBlank()) return ""
        var t = s.trim().replace(Regex("[\\s\\u00A0]+"), "")
        t = t.removePrefix("'").removePrefix("\u2019")
        // Excel 有时把文本型单号读成 "1234567890123456"（保留）或 "1.2345678901234E15"（无法还原，交由上层标记待确认）
        return t
    }

    fun platformKey(p: String?): String = (p ?: "").trim().lowercase()

    fun build(
        platform: String?,
        tradeNo: String?,
        orderNo: String?,
        isRefund: Boolean = false,
        occurredDate: String = "",
        amount: Long = 0L,
        splitIndex: Int = -1
    ): String {
        val plat = platformKey(platform).ifEmpty { "unknown" }
        val t = normNo(tradeNo)
        val o = normNo(orderNo)
        var base = when {
            t.isNotEmpty() -> plat + "|T|" + t
            o.isNotEmpty() -> plat + "|O|" + o
            else -> return ""
        }
        // 退款是独立事件：即使交易单号与原订单相同（微信退款常见），也追加退款判别串，
        // 既不会与付款记录互相吞并，又能在重复导入同一退款时稳定判重。
        if (isRefund) base += "|R|" + occurredDate + "|" + amount
        if (splitIndex >= 0) base += "|S" + splitIndex
        return base
    }

    /** 人工记账等没有单号的场景不生成去重键，重复在保存前由疑似重复核对拦截。 */
    fun fromTxnForAi(platform: String?, orderNo: String?, tradeNo: String?): String =
        build(platform, tradeNo, orderNo, isRefund = false)
}
