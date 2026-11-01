package com.xzledger.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.Xml
import com.xzledger.app.util.Attachments
import com.xzledger.app.util.Dates
import com.xzledger.app.util.Money
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

/** 预览行的稳定标识：来源 + 行号。 */
fun ImportRow.rowKey(): String = origin + "#" + rowIndex

/**
 * 一个文件的解析结果。
 * 原始文件内容只作为待处理数据，绝不执行其中的指令、链接或宏（需求第十七节）。
 */
data class ParseResult(
    val kind: String,
    val fileName: String,
    val rows: List<ImportRow>,
    val problems: List<String>,
    /** 扫描型 / 图片型 PDF 渲染出的页面图片，可交给支持图片输入的 AI 识别。 */
    val pageImages: List<String> = emptyList(),
    /** 供 AI 辅助解析的原文（已截断，仅内存使用，不落盘）。 */
    val rawText: String = "",
)

/** 一次导入会话：跨页面传递解析结果，不持久化，避免原始账单留在磁盘。 */
object ImportSession {
    var results: MutableList<ParseResult> = mutableListOf()
    fun start(list: List<ParseResult>) { results = dedupBatch(list).toMutableList() }

    /**
     * 批内判重：一次可以选多个文件（也可能把同一份账单导两遍），这些文件在确认前都还没写库，
     * 数据库唯一索引拦不住。这里按「交易单号 / 订单编码」在本次预览内先比一遍，
     * 同一笔只保留第一条，后面的直接标成「重复」并取消勾选，避免重复记账。
     */
    fun dedupBatch(list: List<ParseResult>): List<ParseResult> {
        val seen = HashMap<String, ImportRow>()
        return list.map { pr ->
            pr.copy(rows = pr.rows.map { r ->
                val t = DedupKeys.normNo(r.txn.tradeNo)
                val o = DedupKeys.normNo(r.txn.orderNo)
                val key = when {
                    t.isNotEmpty() -> "T|" + t
                    o.isNotEmpty() -> "O|" + o
                    else -> ""
                }
                if (key.isEmpty() || r.state == ImportState.FAILED.id) {
                    if (key.isNotEmpty()) seen[key] = r
                    r
                } else {
                    val prev = seen[key]
                    if (prev == null) {
                        seen[key] = r
                        r
                    } else {
                        r.copy(
                            state = ImportState.DUPLICATE.id,
                            selected = false,
                            message = "本次导入中重复：与「" +
                                (if (prev.txn.merchantRaw.isNotBlank()) prev.txn.merchantRaw else prev.origin) +
                                "」使用同一订单编号（" + (if (t.isNotEmpty()) t else o) + "），已自动跳过"
                        )
                    }
                }
            })
        }
    }
    fun clear() { results = mutableListOf() }
    fun allRows(): List<ImportRow> = results.flatMap { it.rows }
}

data class ImportOutcome(
    val batchId: String,
    val saved: Int,
    val skippedDuplicate: Int,
    val failed: Int,
    val messages: List<String> = emptyList(),
)

object ImportService {

    const val MAX_FILE_BYTES = 8 * 1024 * 1024
    const val MAX_ROWS = 5000
    const val MAX_PDF_PAGES = 20
    const val MAX_TEXT_FOR_AI = 12000

    // ---------------- 字段别名 ----------------

    private val aliases: List<Pair<String, List<String>>> = listOf(
        "tradeNo" to listOf("交易单号", "交易号", "交易流水号", "流水号", "支付宝交易号", "微信支付单号", "交易订单号"),
        "orderNo" to listOf("商户单号", "商家订单号", "商户订单号", "订单号", "订单编码", "商家单号", "外部订单号"),
        "time" to listOf("交易时间", "交易创建时间", "付款时间", "创建时间", "交易日期", "入账时间", "记账日期", "时间", "日期"),
        "type" to listOf("交易类型", "类型", "交易分类", "收支类型"),
        "direction" to listOf("收/支", "收/付款", "收支", "收/付款类型", "资金方向", "借贷标志", "借贷", "出入账"),
        "status" to listOf("当前状态", "交易状态", "资金状态", "状态"),
        "account" to listOf("支付方式", "付款方式", "收/付款方式", "付款账户", "支付账户", "资金渠道", "账户"),
        "merchant" to listOf("交易对方", "商户名称", "交易对象", "商户", "收款方", "对方"),
        "product" to listOf("商品说明", "商品名称", "交易内容", "商品", "交易说明", "摘要", "商品描述"),
        "note" to listOf("备注", "说明"),
        "refund" to listOf("成功退款(元)", "成功退款（元）", "退款金额", "已退款金额"),
        "incomeAmount" to listOf("收入", "收入金额"),
        "expenseAmount" to listOf("支出", "支出金额"),
        "amount" to listOf("金额(元)", "交易金额", "发生金额", "付款金额", "金额(人民币)", "金额")
    )

    private fun normHeader(s: String): String = s.trim()
        .replace(" ", "").replace("\u00A0", "")
        .replace("（", "(").replace("）", ")")
        .replace("／", "/").replace("，", ",")

    /** 识别表头：字段 -> 列号；识别不到足够字段时返回 null。 */
    fun mapHeader(cells: List<String>): Map<String, Int>? {
        val out = HashMap<String, Int>()
        val used = HashSet<Int>()
        for ((field, names) in aliases) {
            for (idx in cells.indices) {
                if (used.contains(idx)) continue
                val h = normHeader(cells[idx])
                if (h.isEmpty()) continue
                if (names.any { normHeader(it) == h }) { out[field] = idx; used.add(idx); break }
            }
        }
        for ((field, names) in aliases) {
            if (out.containsKey(field)) continue
            var best = -1
            var bestLen = 0
            for (idx in cells.indices) {
                if (used.contains(idx)) continue
                val h = normHeader(cells[idx])
                if (h.isEmpty()) continue
                for (n in names) {
                    val nn = normHeader(n)
                    if (h.contains(nn) && nn.length > bestLen) { best = idx; bestLen = nn.length }
                }
            }
            if (best >= 0) { out[field] = best; used.add(best) }
        }
        val hasAmount = out.containsKey("amount") || out.containsKey("incomeAmount") || out.containsKey("expenseAmount")
        val hasTime = out.containsKey("time")
        val hasDir = out.containsKey("direction") || out.containsKey("type")
        if (!hasAmount || !(hasTime || hasDir)) return null
        if (out.size < 3) return null
        return out
    }

    private fun cell(cells: List<String>, map: Map<String, Int>, field: String): String {
        val i = map[field] ?: return ""
        return if (i in cells.indices) cells[i].trim() else ""
    }

    // ---------------- 日期时间 ----------------

    private val datePatterns = listOf(
        Regex("^(\\d{4})[-/.年](\\d{1,2})[-/.月](\\d{1,2})"),
        Regex("^(\\d{4})(\\d{2})(\\d{2})")
    )

    /** 解析为 yyyy-MM-dd；无法识别返回 null。 */
    fun parseDate(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val s0 = raw.trim().removePrefix("'").replace("\u00A0", " ")
        val serial = Regex("^(\\d{5})(\\.\\d+)?$").find(s0)
        if (serial != null) {
            val days = serial.groupValues[1].toIntOrNull()
            if (days != null && days in 20000..60000) return excelSerialToDate(days)
        }
        for (p in datePatterns) {
            val m = p.find(s0) ?: continue
            val y = m.groupValues[1].toIntOrNull() ?: continue
            val mo = m.groupValues[2].toIntOrNull() ?: continue
            val d = m.groupValues[3].toIntOrNull() ?: continue
            if (mo in 1..12 && d in 1..31) {
                return y.toString() + "-" + (if (mo < 10) "0" + mo else "" + mo) + "-" + (if (d < 10) "0" + d else "" + d)
            }
        }
        return null
    }

    private fun excelSerialToDate(days: Int): String {
        val millis = (days - 25569).toLong() * 86400000L
        return Dates.dayKey(millis)
    }

    /** 从同一段文本里取 HH:mm；取不到返回 null。 */
    fun parseTime(raw: String?): Pair<Int, Int>? {
        if (raw.isNullOrBlank()) return null
        var s = raw.replace("：", ":")
        val pm = s.contains("下午") || s.contains("晚上") || s.contains("傍晚")
        val am = s.contains("上午") || s.contains("早上") || s.contains("凌晨")
        s = s.replace("下午", "").replace("晚上", "").replace("傍晚", "")
            .replace("上午", "").replace("早上", "").replace("凌晨", "")
        val m = Regex("(\\d{1,2}):(\\d{2})").find(s) ?: return null
        var h = m.groupValues[1].toIntOrNull() ?: return null
        val mi = m.groupValues[2].toIntOrNull() ?: return null
        if (pm && h < 12) h += 12
        if (am && h == 12) h = 0
        if (h !in 0..23 || mi !in 0..59) return null
        return h to mi
    }

    // ---------------- 方向 / 状态 ----------------

    private fun directionOf(dirText: String, typeText: String): TxnType? {
        val t = (dirText + " " + typeText).trim()
        if (t.isEmpty() || t == "/" || t == "-") return null
        return when {
            t.contains("退款") || t.contains("退货") -> TxnType.REFUND
            t.contains("支出") || t.contains("付款") || t.contains("转出") || t.contains("借") || t.contains("消费") -> TxnType.EXPENSE
            t.contains("收入") || t.contains("收款") || t.contains("转入") || t.contains("贷") -> TxnType.INCOME
            else -> null
        }
    }

    fun statusOf(text: String): TxnStatus {
        val t = text.trim()
        if (t.isEmpty()) return TxnStatus.SUCCESS
        return when {
            t.contains("已全额退款") || t.contains("退款成功") || t.contains("已退款") || t.contains("全额退款") -> TxnStatus.REFUNDED
            t.contains("部分退款") -> TxnStatus.PARTIAL_REFUND
            t.contains("关闭") || t.contains("取消") || t.contains("失败") || t.contains("未支付") || t.contains("已撤销") -> TxnStatus.CLOSED
            t.contains("待付款") || t.contains("进行中") || t.contains("处理中") || t.contains("等待确认") ||
                t.contains("已受理") || t.contains("待入账") || t.contains("未完成") -> TxnStatus.PENDING
            else -> TxnStatus.SUCCESS
        }
    }

    // ---------------- 统一构造预览行 ----------------

    /**
     * 构造一条预览行：完成 去重判定 / 退款关联 / 分类建议 / 账户匹配。
     * state: new / duplicate / suspected / need_confirm / failed
     */
    fun buildRow(
        db: Db,
        ledgerId: String,
        rowIndex: Int,
        origin: String,
        amount: Long?,
        date: String,
        timePair: Pair<Int, Int>?,
        type: TxnType,
        status: TxnStatus,
        merchantRaw: String,
        note: String,
        tradeNo: String,
        orderNo: String,
        accountName: String,
        platform: String,
        source: TxnSource,
        natureOverride: Nature? = null,
        categoryOverrideId: String? = null,
        extra: String = ""
    ): ImportRow {
        val msgs = ArrayList<String>()
        if (extra.isNotEmpty()) msgs.add(extra)
        val safeDate = date.ifEmpty { Dates.today() }
        if (amount == null || amount <= 0L) {
            val t = Txn(
                id = Ids.newId(), ledgerId = ledgerId, type = type, amount = 0L,
                occurredDate = safeDate, occurredAt = System.currentTimeMillis(),
                merchant = MerchantNormalizer.normalize(merchantRaw), merchantRaw = merchantRaw, note = note,
                tradeNo = tradeNo, orderNo = orderNo, source = source, sourcePlatform = platform, txStatus = status
            )
            return ImportRow(rowIndex, origin, t, ImportState.FAILED.id, msgs.joinToString("；") + "；金额无法识别，未导入", false)
        }
        if (date.isEmpty()) {
            val t = Txn(
                id = Ids.newId(), ledgerId = ledgerId, type = type, amount = amount,
                occurredDate = safeDate, occurredAt = System.currentTimeMillis(),
                merchant = MerchantNormalizer.normalize(merchantRaw), merchantRaw = merchantRaw, note = note,
                tradeNo = tradeNo, orderNo = orderNo, source = source, sourcePlatform = platform, txStatus = status
            )
            return ImportRow(rowIndex, origin, t, ImportState.FAILED.id, msgs.joinToString("；") + "；日期无法识别，未导入", false)
        }
        val hp = timePair ?: (12 to 0)
        if (timePair == null) msgs.add("未提供时间，暂按 12:00 记录")
        if (status == TxnStatus.CLOSED || status == TxnStatus.PENDING) {
            msgs.add("该笔状态为「" + status.label + "」，不计入真实收支统计")
        }
        val amountAbs = if (amount < 0) -amount else amount
        val parts = date.split("-")
        val y = parts.getOrNull(0)?.toIntOrNull() ?: 2000
        val mo = (parts.getOrNull(1)?.toIntOrNull() ?: 1) - 1
        val d = parts.getOrNull(2)?.toIntOrNull() ?: 1
        val occurredAt = Dates.toMillis(y, mo, d, hp.first, hp.second)
        val account = matchAccount(db, ledgerId, accountName)
        val suggested = if (categoryOverrideId != null) {
            Classifier.Suggestion(categoryOverrideId, natureOverride, ClassifyBasis.MANUAL, null, "用户指定")
        } else {
            Classifier.suggest(db, ledgerId, merchantRaw, note, account?.id, type, source, amountAbs)
        }
        val nature = if (type == TxnType.EXPENSE) (natureOverride ?: suggested.nature) else null
        val dedup = DedupKeys.build(platform, tradeNo, orderNo, type == TxnType.REFUND, date, amountAbs)
        if (orderNo.isNotEmpty() && Regex("^[0-9]+(\\.[0-9]+)?[eE]\\+?[0-9]+$").matches(orderNo.trim())) {
            msgs.add("订单编码疑似被 Excel 以科学计数法保存（" + orderNo + "），已按原文保留，请核对")
        }
        var linked: String? = null
        if (type == TxnType.REFUND) {
            val payKey = DedupKeys.build(platform, tradeNo, orderNo, false)
            val original = if (payKey.isNotEmpty()) db.findByDedupKey(ledgerId, payKey) else null
            if (original != null) {
                linked = original.id
                msgs.add("已关联原消费：" + original.occurredDate + " " + Money.centsToPlain(original.amount) + " 元")
            } else {
                msgs.add("未找到对应原消费，退款作为独立记录保存")
            }
        }
        val txn = Txn(
            id = Ids.newId(), ledgerId = ledgerId, type = type, amount = amountAbs,
            nature = nature, categoryId = suggested.categoryId, accountId = account?.id,
            title = if (type == TxnType.REFUND) "退款" else "",
            merchant = MerchantNormalizer.normalize(merchantRaw), merchantRaw = merchantRaw,
            note = note, occurredDate = date, occurredAt = occurredAt,
            source = source, sourcePlatform = platform, orderNo = orderNo, tradeNo = tradeNo,
            txStatus = status, refundOf = linked, dedupKey = dedup,
            classifyBasis = suggested.basis, classifyRuleId = suggested.ruleId
        )
        if (dedup.isNotEmpty()) {
            val dup = db.findByDedupKey(ledgerId, dedup)
            if (dup != null) {
                return ImportRow(
                    rowIndex, origin, txn, ImportState.DUPLICATE.id,
                    "已存在相同交易单号/订单编码的记录（" + dup.occurredDate + " " + Money.centsToPlain(dup.amount) + " 元）", false
                )
            }
        }
        // 跨平台判重：同一笔账单换了文件格式（CSV -> Excel）或换了导出渠道时，去重键里的平台不同，
        // 主键查不到；这里再按「交易单号 / 订单编码」原文比对一次，避免同一笔被重复导入。
        if (type != TxnType.REFUND) {
            val same = db.findByAnyNo(ledgerId, tradeNo, orderNo)
            if (same != null && same.type != TxnType.REFUND) {
                return ImportRow(
                    rowIndex, origin, txn, ImportState.DUPLICATE.id,
                    "已存在同一订单编号的记录（" + same.occurredDate + " " + Money.centsToPlain(same.amount) + " 元 · " +
                        same.sourcePlatform.ifEmpty { same.source.label } + "），已自动跳过", false
                )
            }
        }
        if (type != TxnType.REFUND) {
            val suspects = db.findSuspectedDuplicates(txn)
            if (suspects.isNotEmpty()) {
                val s = suspects.first()
                msgs.add("与已有记录相似：" + s.occurredDate + " " + Money.centsToPlain(s.amount) + " 元 " + (if (s.merchantRaw.isEmpty()) s.merchant else s.merchantRaw))
                return ImportRow(rowIndex, origin, txn, ImportState.SUSPECTED.id, msgs.joinToString("；"), true)
            }
        }
        if (suggested.categoryId == null) {
            msgs.add("分类待确认：没有个人规则或历史依据")
            return ImportRow(rowIndex, origin, txn, ImportState.NEED_CONFIRM.id, msgs.joinToString("；"), true)
        }
        return ImportRow(rowIndex, origin, txn, ImportState.NEW.id, msgs.joinToString("；"), true)
    }

    fun matchAccount(db: Db, ledgerId: String, name: String): Account? {
        if (name.isBlank()) return null
        val list = db.accounts(ledgerId)
        val n = name.replace(" ", "")
        list.firstOrNull { it.name.replace(" ", "") == n }?.let { return it }
        list.firstOrNull { it.name.replace(" ", "").isNotEmpty() && n.contains(it.name.replace(" ", "")) }?.let { return it }
        list.firstOrNull { it.name.replace(" ", "").contains(n) && n.length >= 2 }?.let { return it }
        val kw = when {
            n.contains("支付宝") || n.contains("花呗") || n.contains("余额宝") -> AccountType.ALIPAY
            n.contains("微信") || n.contains("零钱") -> AccountType.WECHAT
            n.contains("信用卡") -> AccountType.CREDIT
            n.contains("储蓄卡") || n.contains("银行卡") || n.contains("银行") -> AccountType.DEBIT
            n.contains("现金") -> AccountType.CASH
            else -> null
        }
        return kw?.let { k -> list.firstOrNull { it.type == k } }
    }
}
