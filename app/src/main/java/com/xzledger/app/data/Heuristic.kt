package com.xzledger.app.data

import com.xzledger.app.util.Dates
import com.xzledger.app.util.Money

/**
 * 本地规则识别（离线可用，不依赖 AI，也不产生任何请求费用）。
 * 只依据用户明确说出的信息生成草稿；缺少金额一律不生成账单，交由界面追问。
 */
object Heuristic {

    data class Parse(
        val drafts: List<AiDraft>,
        val notes: List<String>,
        val isQuery: Boolean = false,
        val reply: String = "",
    )

    private val amountRe = Regex("(?:[¥￥]\\s*)?(\\d+(?:\\.\\d{1,2})?)\\s*(?:元|块钱|块|RMB|rmb|人民币)")
    private val bareRe = Regex("(?:花|付|支付|消费|用了|收了|收到|赚了|充|买)[了过]?\\s*(\\d+(?:\\.\\d{1,2})?)")
    private val timeRe = Regex("(\\d{1,2})\\s*[:：]\\s*(\\d{2})")
    private val dateRe = Regex("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]")
    private val splitRe = Regex("[，,；;。！!？?、\\n]+|然后|接着|还有|另外|以及")

    private val accountHints = listOf(
        "支付宝" to "支付宝", "微信" to "微信", "花呗" to "花呗", "现金" to "现金",
        "信用卡" to "信用卡", "储蓄卡" to "储蓄卡", "银行卡" to "银行卡", "云闪付" to "云闪付"
    )

    private val categoryHints: List<Triple<String, String, Nature>> = listOf(
        Triple("餐饮", "餐|饭|早点|早餐|午餐|午饭|晚餐|晚饭|外卖|奶茶|咖啡|喝|零食|水果|超市|食堂|烧烤|火锅|小吃|饮料", Nature.INSTANT),
        Triple("交通", "地铁|公交|打车|滴滴|出租|高铁|火车|机票|车票|加油|停车|共享单车|单车", Nature.INSTANT),
        Triple("电子产品", "电脑|笔记本|手机|耳机|键盘|鼠标|显示器|平板|相机|充电宝|硬盘|路由|显卡|主机|音箱", Nature.PERMANENT),
        Triple("家具", "桌|椅|床|柜|沙发|书架|床垫|置物架|收纳柜", Nature.PERMANENT),
        Triple("生活用品", "纸巾|洗发|沐浴|牙膏|牙刷|洗衣|拖鞋|毛巾|日用|卫生纸|垃圾袋|水杯|雨伞", Nature.INSTANT),
        Triple("娱乐", "电影|游戏|KTV|门票|唱歌|演出|剧本|密室|桌游|网吧|视频会员|音乐会员", Nature.INSTANT),
        Triple("通讯", "话费|流量|套餐|宽带|充话费", Nature.INSTANT),
        Triple("会员", "会员|订阅|充值|VIP|vip|AI", Nature.INSTANT),
        Triple("学习", "书|教材|课程|培训|文具|打印|考试|网课|四六级", Nature.INSTANT),
        Triple("医疗", "药|医院|挂号|看病|体检|牙", Nature.INSTANT),
    )

    private val incomeHints = "生活费|工资|兼职|奖学金|红包|报销|退款|退钱|收到|收入|补贴|利息"

    fun parse(db: Db, ledgerId: String, text: String, autoPost: Boolean = false): Parse {
        val raw = text.trim()
        if (raw.isEmpty()) return Parse(emptyList(), emptyList(), false, "请输入要记录的消费或收入内容。")
        val norm = normalize(raw)
        val notes = ArrayList<String>()
        if (isQuery(norm)) {
            return Parse(emptyList(), notes, true, "")
        }
        val baseDate = resolveDate(norm)
        val baseTime = resolveTime(norm)
        val account = matchAccount(db, ledgerId, norm)
        val segs = norm.split(splitRe).map { it.trim() }.filter { it.isNotEmpty() }
        val drafts = ArrayList<AiDraft>()
        for (seg in segs) {
            val amountCents = resolveAmount(seg)
            if (amountCents == null || amountCents <= 0L) {
                if (seg.length >= 2 && (seg.contains("元") || seg.contains("块") || seg.contains("花") || seg.contains("付"))) {
                    notes.add("有一句没有听清金额：" + seg)
                }
                continue
            }
            val isIncome = Regex(incomeHints).containsMatchIn(seg)
            val isRefund = seg.contains("退款") || seg.contains("退了") || seg.contains("退回")
            val isTransfer = seg.contains("转账") || seg.contains("转给") || seg.contains("还信用卡") || seg.contains("还款")
            val type = when {
                isRefund -> TxnType.REFUND
                isTransfer -> TxnType.TRANSFER
                isIncome -> TxnType.INCOME
                else -> TxnType.EXPENSE
            }
            val merchant = resolveMerchant(seg)
            val categoryHint = resolveCategoryHint(seg)
            val accountName = resolveAccountName(seg, account)
            drafts.add(build(db, ledgerId, seg, amountCents, baseDate, baseTime, type, merchant, categoryHint, account, accountName, autoPost, notes))
        }
        val reply = when {
            drafts.isEmpty() -> "没有识别到金额。请说清楚花了多少钱，例如「今天午餐18元，微信付的」。"
            drafts.size == 1 && drafts[0].state == "saved" -> "已入账：" + AiEngine.draftSummary(drafts[0])
            drafts.size == 1 -> "已生成待确认账单，请核对后点击「确认入账」。"
            else -> "识别到 " + drafts.size + " 笔，请分别核对后确认入账。"
        }
        return Parse(drafts, notes, false, reply)
    }

    private fun build(
        db: Db,
        ledgerId: String,
        seg: String,
        amount: Long,
        date: String,
        time: Pair<Int, Int>,
        type: TxnType,
        merchant: String,
        hint: String?,
        account: Account?,
        accountName: String,
        autoPost: Boolean,
        notes: MutableList<String>,
    ): AiDraft {
        var categoryId: String? = null
        var categoryName = ""
        var nature: Nature? = null
        var basis = ClassifyBasis.DEFAULT
        var reason = "本地规则匹配"
        if (hint != null) {
            val c = AiEngine.matchCategory(db, ledgerId, hint)
            if (c != null) {
                categoryId = c.id
                categoryName = c.name
            }
        }
        if (categoryId == null || (type == TxnType.EXPENSE && nature == null)) {
            val s = Classifier.suggest(db, ledgerId, merchant, seg, account?.id, type, TxnSource.AI_CHAT, amount)
            if (categoryId == null && s.categoryId != null) {
                categoryId = s.categoryId
                categoryName = db.categories(ledgerId).firstOrNull { it.id == s.categoryId }?.name ?: ""
                if (s.basis != ClassifyBasis.DEFAULT) {
                    basis = s.basis
                    reason = s.reason
                }
            }
            if (nature == null) nature = s.nature
        }
        if (nature == null) nature = hintNature(seg)
        val platform = pickPlatform(accountName)
        val d = AiDraft(
            amount = amount,
            date = date,
            time = pad2(time.first) + ":" + pad2(time.second),
            type = type,
            accountId = account?.id,
            accountName = account?.name ?: accountName,
            categoryId = categoryId,
            categoryName = categoryName,
            nature = if (type == TxnType.EXPENSE) nature else null,
            merchant = MerchantNormalizer.displayName(merchant),
            note = "",
            platform = platform,
            basis = if (categoryId != null) basis else ClassifyBasis.DEFAULT,
            basisReason = if (categoryId != null) reason else "未能判断分类，请手动选择",
        )
        if (type == TxnType.EXPENSE && categoryId == null) {
            notes.add("「" + seg + "」未能判断消费分类，已标记为待确认")
        }
        val txn = AiEngine.draftToTxn(db, ledgerId, d)
        if (txn.dedupKey.isNotEmpty() && db.findByDedupKey(ledgerId, txn.dedupKey) != null) {
            return d.copy(state = "duplicate", message = "同一交易单号已存在，未重复创建。")
        }
        val suspected = db.findSuspectedDuplicates(txn)
        if (suspected.isNotEmpty()) {
            return d.copy(state = "confirm", message = "疑似重复：" + suspected.take(2).joinToString("；") {
                it.occurredDate + " " + MerchantNormalizer.displayName(if (it.merchantRaw.isNotEmpty()) it.merchantRaw else it.merchant) + " " + Money.centsToPlain(it.amount) + "元"
            } + "。请核对。")
        }
        if (autoPost && amount > 0L && categoryId != null) {
            val outcome = db.saveTxn(txn)
            return if (outcome.ok) d.copy(state = "saved", savedTxnId = txn.id, message = "已入账")
            else d.copy(state = if (outcome.duplicateOf != null) "duplicate" else "fail", message = outcome.message)
        }
        return d.copy(state = "confirm", message = "待确认")
    }

    fun isQuery(text: String): Boolean {
        val hasAmount = amountRe.containsMatchIn(text)
        val q = text.contains("多少") || text.contains("占比") || text.contains("统计") || text.contains("查询") ||
                text.contains("查一下") || text.contains("看看") || text.contains("分析") || text.contains("怎么样") ||
                text.contains("排行") || text.contains("趋势")
        return !hasAmount && q
    }

    fun normalize(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            val c = when {
                ch in '０'..'９' -> ('0' + (ch - '０'))
                ch == '．' -> '.'
                ch == '，' -> ','
                ch == '。' -> '.'
                ch == '：' -> ':'
                ch == '；' -> ';'
                ch == '（' -> '('
                ch == '）' -> ')'
                ch == '　' -> ' '
                else -> ch
            }
            sb.append(c)
        }
        return sb.toString().trim()
    }

    fun resolveAmount(seg: String): Long? {
        amountRe.find(seg)?.let { m ->
            return Money.parseLoose(m.groupValues[1])
        }
        bareRe.find(seg)?.let { m ->
            return Money.parseLoose(m.groupValues[1])
        }
        return null
    }

    fun resolveDate(text: String): String {
        val today = Dates.today()
        if (text.contains("大前天")) return Dates.shiftDay(today, -3)
        if (text.contains("前天")) return Dates.shiftDay(today, -2)
        if (text.contains("昨天") || text.contains("昨晚") || text.contains("昨夜")) return Dates.shiftDay(today, -1)
        dateRe.find(text)?.let { m ->
            val mo = m.groupValues[1].toIntOrNull()
            val da = m.groupValues[2].toIntOrNull()
            if (mo != null && da != null && mo in 1..12 && da in 1..31) {
                val year = Dates.today().substring(0, 4)
                val candidate = year + "-" + pad2(mo) + "-" + pad2(da)
                if (Dates.parseDay(candidate) != null) return candidate
            }
        }
        return today
    }

    fun resolveTime(text: String): Pair<Int, Int> {
        timeRe.find(text)?.let { m ->
            val h = m.groupValues[1].toIntOrNull() ?: 12
            val mi = m.groupValues[2].toIntOrNull() ?: 0
            if (h in 0..23 && mi in 0..59) return h to mi
        }
        return when {
            text.contains("早上") || text.contains("早晨") || text.contains("早餐") -> 8 to 0
            text.contains("中午") || text.contains("午餐") || text.contains("午饭") -> 12 to 0
            text.contains("下午") -> 15 to 0
            text.contains("晚上") || text.contains("晚餐") || text.contains("晚饭") || text.contains("昨晚") -> 19 to 0
            text.contains("半夜") || text.contains("凌晨") -> 1 to 0
            else -> 12 to 0
        }
    }

    fun matchAccount(db: Db, ledgerId: String, text: String): Account? {
        for ((hint, name) in accountHints) {
            if (text.contains(hint)) {
                val a = AiEngine.matchAccount(db, ledgerId, name)
                if (a != null) return a
            }
        }
        return null
    }

    fun resolveAccountName(seg: String, fallback: Account?): String {
        for ((hint, name) in accountHints) {
            if (seg.contains(hint)) return name
        }
        return fallback?.name ?: ""
    }

    private fun pickPlatform(accountName: String): String = when {
        accountName.contains("支付宝") -> "支付宝"
        accountName.contains("微信") -> "微信"
        else -> accountName
    }

    fun resolveCategoryHint(seg: String): String? {
        for ((cat, pattern, _) in categoryHints) {
            if (Regex(pattern).containsMatchIn(seg)) return cat
        }
        return null
    }

    private fun hintNature(seg: String): Nature? {
        for ((_, pattern, nature) in categoryHints) {
            if (Regex(pattern).containsMatchIn(seg)) return nature
        }
        return null
    }

    /** 尽量取出商户名：去掉金额、时间、账户与常见动词后的剩余部分。 */
    fun resolveMerchant(seg: String): String {
        var s = seg
        s = s.replace(amountRe, " ")
        s = s.replace(bareRe, " ")
        s = s.replace("花了", " ").replace("支付", " ").replace("付款", " ").replace("付了", " ")
            .replace("买了", " ").replace("买", " ").replace("购买", " ").replace("充值", " ")
            .replace("一共", " ").replace("总共", " ").replace("花了", " ")
            .replace("今天", " ").replace("昨天", " ").replace("前天", " ").replace("早上", " ")
            .replace("中午", " ").replace("下午", " ").replace("晚上", " ").replace("昨晚", " ")
            .replace("属于", " ").replace("消费", " ").replace("收入", " ")
        for ((hint, _) in accountHints) s = s.replace(hint, " ")
        s = s.replace("付的", " ").replace("付", " ").replace("的", " ").replace("用", " ")
        s = s.replace("在", " ").replace("给", " ").replace("了", " ")
        val tokens = s.split(Regex("[\\s,.:;()\\[\\]{}<>/\\\\|+*#~!?]+")).filter { it.length >= 2 }
        val best = tokens.maxByOrNull { it.length } ?: ""
        return best.take(24)
    }

    private fun pad2(n: Int): String = if (n < 10) "0" + n else "" + n
}
