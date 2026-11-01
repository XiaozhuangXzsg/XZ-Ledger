package com.xzledger.app.autorecord

import com.xzledger.app.data.TxnType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** 通知解析的原始输入（不含任何持久化，解析完即弃）。 */
data class NoticeInput(
    val pkg: String,
    val appLabel: String,
    val title: String,
    val text: String,
    /** 通知进入系统的时间（毫秒）。交易时间优先从正文解析。 */
    val postedAt: Long,
)

/** 交易语义类型。无法确定的一律 UNKNOWN，绝不猜成收入或支出。 */
enum class NoticeKind(val id: String, val label: String) {
    EXPENSE("expense", "消费支出"),
    INCOME("income", "收入"),
    REFUND("refund", "退款"),
    TRANSFER("transfer", "转账"),
    CREDIT_REPAY("credit_repay", "信用卡还款"),
    WITHDRAW("withdraw", "提现"),
    TOPUP("topup", "充值"),
    FEE("fee", "手续费／利息"),
    BALANCE_ONLY("balance_only", "仅余额变动"),
    IGNORED("ignored", "非交易通知"),
    UNKNOWN("unknown", "无法确定");

    companion object {
        fun of(id: String?): NoticeKind = entries.firstOrNull { it.id == id } ?: UNKNOWN
    }
}

data class ParsedNotice(
    val kind: NoticeKind,
    /** 入账方向；IGNORED / BALANCE_ONLY / UNKNOWN 时为 null。 */
    val type: TxnType?,
    val amountCents: Long = 0L,
    /** 通知里的账户余额：只做辅助识别与展示，绝不参与收支统计。 */
    val balanceCents: Long? = null,
    val cardTail: String = "",
    val merchant: String = "",
    val currency: String = "CNY",
    val serial: String = "",
    val occurredAt: Long,
    /** 0-100，低于阈值只能进待确认。 */
    val confidence: Int = 0,
    val sourceName: String = "",
    val reason: String = "",
) {
    val usable: Boolean get() = kind != NoticeKind.IGNORED && kind != NoticeKind.UNKNOWN &&
        kind != NoticeKind.BALANCE_ONLY && amountCents > 0L
}

/**
 * 通知文本解析器。纯 Kotlin（不依赖 Android），便于单元测试。
 *
 * 规则来源按「来源应用 → 通用句式」两层组织：
 *  1. 先按包名选来源规则（支付宝/微信/银行/未知），只影响来源名称与少量句式权重；
 *  2. 再走通用句式：先分离「交易金额」与「账户余额」，再判定方向、商户、卡尾号、时间、流水号。
 * 新增银行时，只需在 [sources] 里补一条包名 + 关键词，必要时补充商户正则即可，
 * 不需要改动解析主流程。
 */
object TxnParser {

    /** 已知来源规则。包名均为可公开核验的常见应用包名；其它应用由用户在「监听应用」里自行选择。 */
    data class SourceRule(
        val id: String,
        val name: String,
        val packages: List<String>,
        val merchantPatterns: List<Regex> = emptyList(),
    )

    private val alipay = listOf(
        Regex("""(?:向|付款给|转账给)\s*([一-龥A-Za-z0-9·()（）]{2,24}?)(?:付款|转账|成功|$)"""),
        Regex("""(?:收款方|商户|对方)\s*[:：]\s*([^\s，。,;；]{2,24})"""),
    )
    private val wechat = listOf(
        Regex("""(?:付款给|转账给|付款方)\s*([一-龥A-Za-z0-9·()（）]{2,24}?)(?:的收款|收款|$|，)"""),
        Regex("""(?:收款方|商户|对方)\s*[:：]\s*([^\s，。,;；]{2,24})"""),
    )
    private val bank = listOf(
        Regex("""(?:在|商户|收款方|对方)\s*[:：]?\s*([一-龥A-Za-z0-9·()（）]{2,24}?)(?:消费|付款|支付|交易|退款|$)"""),
        Regex("""(?:商户名称|交易商户)\s*[:：]\s*([^\s，。,;；]{2,24})"""),
    )

    val sources: List<SourceRule> = listOf(
        SourceRule("alipay", "支付宝", listOf("com.eg.android.AlipayGphone"), alipay),
        SourceRule("wechat", "微信支付", listOf("com.tencent.mm"), wechat),
        SourceRule("icbc", "工商银行", listOf("com.icbc"), bank),
        SourceRule("ccb", "建设银行", listOf("com.chinamworld.main"), bank),
        SourceRule("abc", "农业银行", listOf("com.android.bankabc"), bank),
        SourceRule("boc", "中国银行", listOf("com.chinamworld.bocmbci"), bank),
        SourceRule("cmb", "招商银行", listOf("cmb.pb"), bank),
        SourceRule("bocom", "交通银行", listOf("com.bankcomm.Bankcomm"), bank),
        SourceRule("psbc", "邮储银行", listOf("com.yitong.mbank.psbc"), bank),
        SourceRule("unionpay", "云闪付", listOf("com.unionpay"), bank),
    )

    fun sourceName(pkg: String, appLabel: String): String =
        sources.firstOrNull { it.packages.contains(pkg) }?.name ?: appLabel.ifEmpty { pkg }

    // ---------------- 过滤：验证码 / 广告 / 登录 / 营销 ----------------
    private val blockKeywords = listOf(
        "验证码", "校验码", "动态码", "动态密码", "密码", "登录", "登陆", "注册", "激活",
        "广告", "优惠券", "活动", "推荐", "营销", "贷款", "借款额度", "额度提升", "提额",
        "理财推荐", "邀请", "红包雨", "抽奖", "积分兑换", "账单已出", "还款提醒",
        "尊敬的用户，您已成功开通", "下载APP", "退订",
    )
    // 含「验证码」「密码」等但其实是交易的通知非常罕见，直接丢弃，不冒误记风险。
    private val sensitiveKeywords = listOf("验证码", "密码", "登录令牌", "安全码", "CVV")

    // ---------------- 方向关键词 ----------------
    private val incomeWords = listOf("收入", "存入", "转入", "到账", "收到", "汇入", "入账", "工资", "奖金", "报销", "分红", "利息收入", "收款")
    private val expenseWords = listOf("消费", "支出", "支付", "付款", "扣款", "扣费", "转出", "取现", "缴费", "购买", "交易金额")
    private val refundWords = listOf("退款", "退货", "退回", "冲正", "撤销交易")
    private val repayWords = listOf("信用卡还款", "还款成功", "自动还款", "还款")
    private val transferWords = listOf("转账", "转存", "内部转账")
    // 转账通知的方向词：只出现在收款侧时按收入处理，避免把「收到转账」误判成账户间转账。
    private val transferInWords = listOf("收到", "到账", "转入", "汇入", "入账", "收款")
    private val transferOutWords = listOf("转出", "转给", "向他人", "转账给")
    private val withdrawWords = listOf("提现")
    private val topupWords = listOf("充值")
    private val feeWords = listOf("手续费", "工本费", "年费", "管理费", "服务费", "短信费", "利息", "结息")
    private val balanceWords = listOf("余额", "可用余额", "账户余额", "结余", "可用额度", "余额为")

    private val currencySymbols = mapOf("¥" to "CNY", "￥" to "CNY", "人民币" to "CNY", "RMB" to "CNY", "CNY" to "CNY", "美元" to "USD", "USD" to "USD", "HK$" to "HKD", "港币" to "HKD")

    private val cardTailRe = Regex("""(?:尾号|尾数为|卡号后四位|末四位|后四位)[:：]?\s*(\d{4})""")
    private val serialRe = Regex("""(?:流水号|交易单号|订单号|凭证号|参考号|交易序号)[:：]?\s*([A-Za-z0-9]{6,32})""")
    private val fullDateTimeRe = Regex("""(\d{4})[-/.年](\d{1,2})[-/.月](\d{1,2})日?(?:\s*(\d{1,2})[:：](\d{2}))?""")
    private val monthDayRe = Regex("""(?<!\d)(\d{1,2})月(\d{1,2})日(?:\s*(\d{1,2})[:：](\d{2}))?""")
    private val timeOnlyRe = Regex("""(?:^|\s)(\d{1,2})[:：](\d{2})(?::(\d{2}))?""")

    // 数字 + 可选货币标记 / 单位
    private val numberRe = Regex("""(?:(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d{1,2}))?)""")

    private fun parseCents(intPart: String, frac: String?): Long {
        val i = intPart.replace(",", "").toLongOrNull() ?: return 0L
        val f = (frac ?: "").padEnd(2, '0').take(2).toLongOrNull() ?: 0L
        return i * 100 + f
    }

    private fun containsAny(s: String, words: List<String>): Boolean = words.any { s.contains(it) }

    private fun currencyOf(text: String): String {
        for ((k, v) in currencySymbols) if (text.contains(k)) return v
        return "CNY"
    }

    /** 解析一条通知。任何异常都返回 IGNORED，绝不抛出到监听服务里。 */
    fun parse(input: NoticeInput, now: Long = System.currentTimeMillis()): ParsedNotice {
        return try {
            parseInternal(input, now)
        } catch (t: Throwable) {
            ParsedNotice(NoticeKind.IGNORED, null, occurredAt = now, reason = "解析异常，已忽略")
        }
    }

    private fun parseInternal(input: NoticeInput, now: Long): ParsedNotice {
        val title = input.title.trim()
        val body = input.text.trim()
        val full = (title + " " + body).trim()
        val srcName = sourceName(input.pkg, input.appLabel)

        // 通知内容为空：锁屏隐藏、系统限制或应用只发了空通知。
        if (full.isBlank()) {
            return ParsedNotice(
                NoticeKind.IGNORED, null, occurredAt = now, sourceName = srcName,
                reason = "通知内容为空（可能被系统或锁屏隐藏）"
            )
        }
        if (sensitiveKeywords.any { full.contains(it) }) {
            return ParsedNotice(NoticeKind.IGNORED, null, occurredAt = now, sourceName = srcName, reason = "疑似验证码／安全信息，已丢弃")
        }
        if (containsAny(full, blockKeywords) && !containsAny(full, expenseWords + incomeWords)) {
            return ParsedNotice(NoticeKind.IGNORED, null, occurredAt = now, sourceName = srcName, reason = "疑似营销／登录／额度类通知，已过滤")
        }
        // 只提到余额、没有任何金额变动语义
        val amounts = extractAmounts(full)
        if (amounts.amountCents <= 0L) {
            val hasBalance = amounts.balanceCents != null
            return ParsedNotice(
                if (hasBalance) NoticeKind.BALANCE_ONLY else NoticeKind.IGNORED,
                null, balanceCents = amounts.balanceCents, occurredAt = now,
                sourceName = srcName,
                reason = if (hasBalance) "只有账户余额，没有交易金额" else "未找到交易金额"
            )
        }

        val kind = detectKind(full, input.pkg)
        val type = when (kind) {
            NoticeKind.EXPENSE, NoticeKind.FEE, NoticeKind.WITHDRAW -> TxnType.EXPENSE
            NoticeKind.INCOME -> TxnType.INCOME
            NoticeKind.REFUND -> TxnType.REFUND
            else -> null
        }

        val cardTail = cardTailRe.find(full)?.groupValues?.get(1).orEmpty()
        val serial = serialRe.find(full)?.groupValues?.get(1).orEmpty()
        val merchant = extractMerchant(full, input.pkg)
        val occurredAt = extractTime(full, now)
        val currency = currencyOf(full)

        var conf = 40
        if (cardTail.isNotEmpty()) conf += 15
        if (serial.isNotEmpty()) conf += 10
        if (merchant.isNotEmpty()) conf += 10
        if (amounts.hadExplicitMarker) conf += 10
        if (occurredAt != now) conf += 5
        if (kind == NoticeKind.UNKNOWN) conf -= 30
        if (kind == NoticeKind.TRANSFER) conf -= 10
        if (kind == NoticeKind.CREDIT_REPAY) conf -= 5
        conf = conf.coerceIn(0, 95)

        return ParsedNotice(
            kind = kind,
            type = type,
            amountCents = amounts.amountCents,
            balanceCents = amounts.balanceCents,
            cardTail = cardTail,
            merchant = merchant,
            currency = currency,
            serial = serial,
            occurredAt = occurredAt,
            confidence = conf,
            sourceName = srcName,
            reason = amounts.reason
        )
    }

    private data class Amounts(
        val amountCents: Long,
        val balanceCents: Long?,
        val hadExplicitMarker: Boolean,
        val reason: String,
    )

    /**
     * 金额提取：先给每个数字打分，区分「交易金额」与「账户余额」。
     * 同一通知里出现多个金额时，用上下文语义决定，绝不简单取第一个。
     */
    private fun extractAmounts(text: String): Amounts {
        var bestScore = Int.MIN_VALUE
        var bestCents = 0L
        var balance: Long? = null
        var bestMarker = false
        data class Cand(val cents: Long, val score: Int, val marker: Boolean)

        val cands = ArrayList<Cand>()
        for (m in numberRe.findAll(text)) {
            val start = m.range.first
            val end = m.range.last + 1
            val before = text.substring(maxOf(0, start - 14), start)
            val after = text.substring(end, minOf(text.length, end + 6))
            // 排除卡号／尾号／流水号／手机号里的数字
            if (Regex("""(尾号|卡号|账号|手机号|流水号|订单号|凭证号|参考号)\s*[:：]?\s*$""").containsMatchIn(before)) continue
            if (after.trimStart().startsWith(":") && before.contains(":")) continue
            val cents = parseCents(m.groupValues[1], m.groupValues[2].ifEmpty { null })
            if (cents <= 0L) continue
            val marker = before.trimEnd().endsWith("¥") || before.trimEnd().endsWith("￥") ||
                before.contains("人民币") || before.contains("RMB", true) || before.contains("CNY", true) ||
                after.trimStart().startsWith("元") || after.trimStart().startsWith("块")
            var score = 0
            if (containsAny(before, balanceWords)) score -= 5
            if (containsAny(after, listOf("余额", "结余"))) score -= 5
            if (containsAny(before, expenseWords + incomeWords + refundWords + repayWords + feeWords)) score += 4
            if (containsAny(before, listOf("金额", "交易金额", "实付", "应付"))) score += 3
            if (marker) score += 2
            if (m.groupValues[2].isNotEmpty()) score += 1 // 有小数，更像金额（余额通常也带小数，只作微调）
            if (before.contains("尾号") || before.contains("卡号")) score -= 4
            cands.add(Cand(cents, score, marker))
            // 余额单独记录（取第一个带余额上下文的数字）
            if (balance == null && containsAny(before, balanceWords)) balance = cents
        }
        if (cands.isEmpty()) return Amounts(0L, balance, false, "未找到金额")
        // 同分时取更靠前、金额较小的那个：银行短信里余额通常更大，且常出现在末尾。
        val pool = cands.filter { it.score >= 0 }.ifEmpty { cands }
        var chosen: Cand? = null
        for (c in pool) {
            if (chosen == null || c.score > chosen.score ||
                (c.score == chosen.score && c.cents < chosen.cents && c.marker)
            ) chosen = c
        }
        bestCents = chosen?.cents ?: 0L
        bestScore = chosen?.score ?: 0
        bestMarker = chosen?.marker == true
        // 只有余额语义、没有交易语义时，把数字归为余额，不当作交易金额。
        if (bestScore <= 0) {
            if (balance != null) return Amounts(0L, balance, false, "只有账户余额，没有交易金额")
        } else if (balance != null && balance == bestCents) {
            balance = null
        }
        return Amounts(
            bestCents, balance, bestMarker,
            when {
                bestScore <= 0 -> "只有金额数字，缺少交易语义，需人工确认"
                else -> ""
            }
        )
    }

    private fun detectKind(text: String, pkg: String): NoticeKind {
        // 信用卡还款：既不是新增支出也不是收入，按「还款」单独处理，避免与已记消费重复统计。
        if (containsAny(text, repayWords) && (text.contains("信用卡") || text.contains("还款"))) {
            return NoticeKind.CREDIT_REPAY
        }
        if (containsAny(text, refundWords)) return NoticeKind.REFUND
        if (containsAny(text, listOf("提现"))) return NoticeKind.WITHDRAW
        if (containsAny(text, listOf("充值"))) return NoticeKind.TOPUP
        if (containsAny(text, feeWords) && !containsAny(text, expenseWords)) return NoticeKind.FEE
        // 转账类通知：先分方向。「收到转账」是收入，不是账户间转账。
        if (containsAny(text, transferWords)) {
            if (transferInWords.any { text.contains(it) } &&
                !transferOutWords.any { text.contains(it) }
            ) return NoticeKind.INCOME
            return NoticeKind.TRANSFER
        }
        if (containsAny(text, refundWords)) return NoticeKind.REFUND
        val income = containsAny(text, incomeWords)
        val expense = containsAny(text, expenseWords)
        return when {
            income && !expense -> NoticeKind.INCOME
            expense && !income -> NoticeKind.EXPENSE
            income && expense -> {
                // 「收入 100 支出 50」这类汇总消息不做猜测
                NoticeKind.UNKNOWN
            }
            // 支付宝/微信的最小化通知（如「支付成功」）通常是支出
            pkg == "com.eg.android.AlipayGphone" || pkg == "com.tencent.mm" -> NoticeKind.EXPENSE
            else -> NoticeKind.UNKNOWN
        }
    }

    private val channelWords = listOf("支付宝", "微信支付", "微信", "云闪付", "银联", "财付通")

    private fun extractMerchant(text: String, pkg: String): String {
        val rule = sources.firstOrNull { it.packages.contains(pkg) }
        val patterns = (rule?.merchantPatterns ?: emptyList()) + bank + alipay + wechat
        for (p in patterns) {
            val g = p.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (g.length in 2..24 && channelWords.none { it == g } && !g.all { it.isDigit() }) return g
        }
        return ""
    }

    private fun extractTime(text: String, now: Long): Long {
        fullDateTimeRe.find(text)?.let { m ->
            val y = m.groupValues[1].toIntOrNull() ?: return@let
            val mo = m.groupValues[2].toIntOrNull() ?: return@let
            val d = m.groupValues[3].toIntOrNull() ?: return@let
            val h = m.groupValues[4].toIntOrNull() ?: 0
            val mi = m.groupValues[5].toIntOrNull() ?: 0
            return buildTime(y, mo, d, h, mi, now, allowYearRollback = false) ?: now
        }
        monthDayRe.find(text)?.let { m ->
            val mo = m.groupValues[1].toIntOrNull() ?: return@let
            val d = m.groupValues[2].toIntOrNull() ?: return@let
            val h = m.groupValues[3].toIntOrNull() ?: 0
            val mi = m.groupValues[4].toIntOrNull() ?: 0
            val cal = Calendar.getInstance().apply { timeInMillis = now }
            return buildTime(cal.get(Calendar.YEAR), mo, d, h, mi, now, allowYearRollback = true) ?: now
        }
        // 只有时间：取今天
        timeOnlyRe.find(text)?.let { m ->
            val h = m.groupValues[1].toIntOrNull() ?: return@let
            val mi = m.groupValues[2].toIntOrNull() ?: return@let
            val cal = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, h.coerceIn(0, 23))
                set(Calendar.MINUTE, mi.coerceIn(0, 59))
                set(Calendar.SECOND, 0)
            }
            val v = cal.timeInMillis
            return if (v > now + 2 * 60 * 60 * 1000L) v - 24 * 60 * 60 * 1000L else v
        }
        return now
    }

    private fun buildTime(
        year: Int, month: Int, day: Int, hour: Int, minute: Int, now: Long, allowYearRollback: Boolean
    ): Long? {
        if (month !in 1..12 || day !in 1..31) return null
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(year, month - 1, day, hour.coerceIn(0, 23), minute.coerceIn(0, 59), 0)
        cal.set(Calendar.MILLISECOND, 0)
        var v = cal.timeInMillis
        // 通知里不写年份时：跨年（例如 12 月的通知在 1 月收到）按上一年算。
        if (allowYearRollback && v > now + 24 * 60 * 60 * 1000L) {
            cal.add(Calendar.YEAR, -1)
            v = cal.timeInMillis
        }
        return v
    }

    fun formatDate(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(java.util.Date(ms))
}
