package com.xzledger.app.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import com.xzledger.app.util.Dates
import com.xzledger.app.util.Money
import org.json.JSONArray

/** 查询条件：搜索与筛选共用；三类勾选条件之间「同时满足」，同一分类组内「满足任意一项」。 */
data class TxnFilter(
    val ledgerId: String,
    val keyword: String = "",
    val categoryIds: List<String> = emptyList(),
    /** 金额下限（分，按绝对值） */
    val minAmount: Long? = null,
    /** 金额上限（分，按绝对值） */
    val maxAmount: Long? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val types: List<TxnType> = emptyList(),
    val natures: List<Nature> = emptyList(),
    val accountIds: List<String> = emptyList(),
    val sources: List<TxnSource> = emptyList(),
    val includeDeleted: Boolean = false,
    val trashOnly: Boolean = false,
    val limit: Int = 0,
)

/** 关键词若可解析为金额，则按金额精确匹配；这里给出显式判断，避免简单字符串匹配出错。 */
fun keywordAsAmount(keyword: String): Long? {
    val s = keyword.trim()
    if (s.isEmpty()) return null
    if (!s.matches(Regex("^[0-9]+(\\.[0-9]{1,2})?\$"))) return null
    return Money.parseYuanToCents(s)
}

private fun placeholders(n: Int): String = (0 until n).joinToString(",") { "?" }

fun buildFilterSql(f: TxnFilter): Pair<String, Array<String>> {
    val sql = StringBuilder("SELECT * FROM txn WHERE ledger_id=?")
    val args = ArrayList<String>()
    args.add(f.ledgerId)
    if (f.trashOnly) sql.append(" AND deleted=1") else if (!f.includeDeleted) sql.append(" AND deleted=0")
    if (f.keyword.isNotBlank()) {
        val like = "%" + f.keyword.trim() + "%"
        val amt = keywordAsAmount(f.keyword)
        if (amt != null) {
            sql.append(" AND (title LIKE ? OR merchant LIKE ? OR merchant_raw LIKE ? OR note LIKE ? OR order_no LIKE ? OR trade_no LIKE ? OR amount = ?)")
            repeat(6) { args.add(like) }
            args.add(amt.toString())
        } else {
            sql.append(" AND (title LIKE ? OR merchant LIKE ? OR merchant_raw LIKE ? OR note LIKE ? OR order_no LIKE ? OR trade_no LIKE ?)")
            repeat(6) { args.add(like) }
        }
    }
    if (f.categoryIds.isNotEmpty()) {
        sql.append(" AND category_id IN (" + placeholders(f.categoryIds.size) + ")")
        f.categoryIds.forEach { args.add(it) }
    }
    f.minAmount?.let { sql.append(" AND amount >= ?"); args.add(it.toString()) }
    f.maxAmount?.let { sql.append(" AND amount <= ?"); args.add(it.toString()) }
    f.startDate?.let { sql.append(" AND occurred_date >= ?"); args.add(it) }
    f.endDate?.let { sql.append(" AND occurred_date <= ?"); args.add(it) }
    if (f.types.isNotEmpty()) {
        sql.append(" AND type IN (" + placeholders(f.types.size) + ")")
        f.types.forEach { args.add(it.id) }
    }
    if (f.natures.isNotEmpty()) {
        sql.append(" AND nature IN (" + placeholders(f.natures.size) + ")")
        f.natures.forEach { args.add(it.id) }
    }
    if (f.accountIds.isNotEmpty()) {
        sql.append(" AND (account_id IN (" + placeholders(f.accountIds.size) + ") OR to_account_id IN (" + placeholders(f.accountIds.size) + "))")
        f.accountIds.forEach { args.add(it) }
        f.accountIds.forEach { args.add(it) }
    }
    if (f.sources.isNotEmpty()) {
        sql.append(" AND source IN (" + placeholders(f.sources.size) + ")")
        f.sources.forEach { args.add(it.id) }
    }
    sql.append(" ORDER BY occurred_at DESC, created_at DESC")
    if (f.limit > 0) sql.append(" LIMIT " + f.limit)
    return sql.toString() to args.toTypedArray()
}

internal fun Cursor.toTxn() = Txn(
    id = s("id"), ledgerId = s("ledger_id"), type = TxnType.of(s("type")),
    amount = l("amount"), currency = s("currency"), nature = Nature.of(sn("nature")),
    categoryId = sn("category_id"), accountId = sn("account_id"), toAccountId = sn("to_account_id"),
    title = s("title"), merchant = s("merchant"), merchantRaw = s("merchant_raw"), note = s("note"),
    occurredDate = s("occurred_date"), occurredAt = l("occurred_at"),
    createdAt = l("created_at"), updatedAt = l("updated_at"),
    source = TxnSource.of(s("source")), sourcePlatform = s("source_platform"),
    orderNo = s("order_no"), tradeNo = s("trade_no"), txStatus = TxnStatus.of(s("tx_status")),
    refundOf = sn("refund_of"), linkedTxnId = sn("linked_txn_id"), dedupKey = s("dedup_key"),
    classifyBasis = ClassifyBasis.of(s("classify_basis")), classifyRuleId = sn("classify_rule_id"),
    imagePaths = jsonToList(s("image_paths")), tags = jsonToList(s("tags")),
    chatMsgId = sn("chat_msg_id"), importBatchId = sn("import_batch_id"),
    deleted = b("deleted"), deletedAt = ln("deleted_at"), version = i("version")
)

private fun txnValues(t: Txn): ContentValues {
    val cv = ContentValues()
    cv.put("id", t.id); cv.put("ledger_id", t.ledgerId); cv.put("type", t.type.id)
    cv.put("amount", t.amount); cv.put("currency", t.currency); cv.put("nature", t.nature?.id)
    cv.put("category_id", t.categoryId); cv.put("account_id", t.accountId); cv.put("to_account_id", t.toAccountId)
    cv.put("title", t.title); cv.put("merchant", t.merchant); cv.put("merchant_raw", t.merchantRaw)
    cv.put("merchant_norm", MerchantNormalizer.normalize(if (t.merchantRaw.isNotEmpty()) t.merchantRaw else t.merchant))
    cv.put("note", t.note); cv.put("occurred_date", t.occurredDate); cv.put("occurred_at", t.occurredAt)
    cv.put("created_at", t.createdAt); cv.put("updated_at", t.updatedAt)
    cv.put("source", t.source.id); cv.put("source_platform", t.sourcePlatform)
    cv.put("order_no", t.orderNo); cv.put("trade_no", t.tradeNo); cv.put("tx_status", t.txStatus.id)
    cv.put("refund_of", t.refundOf); cv.put("linked_txn_id", t.linkedTxnId); cv.put("dedup_key", t.dedupKey)
    cv.put("classify_basis", t.classifyBasis.id); cv.put("classify_rule_id", t.classifyRuleId)
    cv.put("image_paths", listToJson(t.imagePaths)); cv.put("tags", listToJson(t.tags))
    cv.put("chat_msg_id", t.chatMsgId); cv.put("import_batch_id", t.importBatchId)
    cv.put("deleted", if (t.deleted) 1 else 0); cv.put("deleted_at", t.deletedAt)
    cv.put("version", t.version)
    return cv
}

data class SaveOutcome(val ok: Boolean, val duplicateOf: Txn? = null, val message: String = "")

/** 同一账本内、同一来源平台的同一交易单号/订单编码只允许一条：数据库层唯一约束 + 保存前预检。 */
/** 拆分分片的去重键：主记录仍保留平台键，分片追加 |S<i>，避免重复计入订单总额。 */
fun splitDedupKey(base: String, index: Int): String = base + "|S" + index

fun Db.findByDedupKey(ledgerId: String, key: String): Txn? {
    if (key.isEmpty()) return null
    return readableDatabase.rawQuery("SELECT * FROM txn WHERE ledger_id=? AND dedup_key=?", arrayOf(ledgerId, key)).use { c ->
        if (c.moveToFirst()) c.toTxn() else null
    }
}

/**
 * 跨平台按单号判重：同一笔账单换了来源（先 CSV、后 Excel，或先微信后支付宝导出）也要能识别，
 * 只要交易单号或订单编码有一个相同就算同一笔。平台不同但单号相同的记录，多半是同一笔被导入了两次。
 */
fun Db.findByAnyNo(ledgerId: String, tradeNo: String, orderNo: String, excludeId: String = ""): Txn? {
    val t = DedupKeys.normNo(tradeNo)
    val o = DedupKeys.normNo(orderNo)
    if (t.isEmpty() && o.isEmpty()) return null
    val conds = ArrayList<String>()
    val args = ArrayList<String>()
    args.add(ledgerId); args.add(excludeId)
    if (t.isNotEmpty()) { conds.add("trade_no=?"); args.add(t) }
    if (o.isNotEmpty()) { conds.add("order_no=?"); args.add(o) }
    val sql = "SELECT * FROM txn WHERE ledger_id=? AND deleted=0 AND id<>? AND (" + conds.joinToString(" OR ") + ") ORDER BY occurred_at LIMIT 1"
    return readableDatabase.rawQuery(sql, args.toTypedArray()).use { c -> if (c.moveToFirst()) c.toTxn() else null }
}

fun Db.saveTxn(t: Txn): SaveOutcome {
    if (t.amount <= 0) return SaveOutcome(false, message = "金额必须大于 0")
    if (t.dedupKey.isNotEmpty()) {
        val exist = findByDedupKey(t.ledgerId, t.dedupKey)
        if (exist != null && exist.id != t.id) {
            return SaveOutcome(false, duplicateOf = exist, message = "已存在相同交易单号的记录")
        }
    }
    return try {
        writableDatabase.insertWithOnConflict("txn", null, txnValues(t), SQLiteDatabase.CONFLICT_ABORT)
        enqueue("txn", t.id, "upsert", t.version)
        SaveOutcome(true)
    } catch (e: SQLiteConstraintException) {
        SaveOutcome(false, findByDedupKey(t.ledgerId, t.dedupKey), "数据库唯一约束拦截：重复记录")
    }
}

fun Db.updateTxn(t: Txn): SaveOutcome {
    val cur = txnById(t.id) ?: return saveTxn(t)
    if (cur.version != t.version && t.version != 0) {
        // 并发修改检测：不静默覆盖，交由界面提示冲突。
        return SaveOutcome(false, message = "该账单已在其他位置被修改（版本冲突）")
    }
    val next = t.copy(version = cur.version + 1, updatedAt = System.currentTimeMillis())
    writableDatabase.update("txn", txnValues(next), "id=?", arrayOf(t.id))
    enqueue("txn", t.id, "upsert", next.version)
    return SaveOutcome(true)
}

fun Db.txnById(id: String): Txn? =
    readableDatabase.rawQuery("SELECT * FROM txn WHERE id=?", arrayOf(id)).use { c ->
        if (c.moveToFirst()) c.toTxn() else null
    }

fun Db.txns(f: TxnFilter): List<Txn> {
    val (sql, args) = buildFilterSql(f)
    return readableDatabase.rawQuery(sql, args).use { c ->
        val r = ArrayList<Txn>(); while (c.moveToNext()) r.add(c.toTxn()); r
    }
}

fun Db.txnsByIds(ids: Collection<String>): List<Txn> {
    if (ids.isEmpty()) return emptyList()
    val r = ArrayList<Txn>()
    ids.chunked(400).forEach { chunk ->
        val sql = "SELECT * FROM txn WHERE id IN (" + chunk.joinToString(",") { "?" } + ")"
        readableDatabase.rawQuery(sql, chunk.toTypedArray()).use { c -> while (c.moveToNext()) r.add(c.toTxn()) }
    }
    return r
}

/** 软删除 = 同步墓碑，保留恢复能力（回收站）。 */
fun Db.softDeleteTxn(id: String) {
    val cv = ContentValues()
    cv.put("deleted", 1); cv.put("deleted_at", System.currentTimeMillis()); cv.put("updated_at", System.currentTimeMillis())
    writableDatabase.update("txn", cv, "id=?", arrayOf(id))
    enqueue("txn", id, "delete", 0)
}

fun Db.softDeleteTxns(ids: Collection<String>) {
    ids.forEach { softDeleteTxn(it) }
}

fun Db.restoreTxn(id: String) {
    val cv = ContentValues()
    cv.put("deleted", 0); cv.putNull("deleted_at"); cv.put("updated_at", System.currentTimeMillis())
    writableDatabase.update("txn", cv, "id=?", arrayOf(id))
    enqueue("txn", id, "upsert", 0)
}

/** 回收站：默认当前账本，ledgerId 为 null 时跨账本（同步与清理场景）。 */
fun Db.trash(ledgerId: String? = null): List<Txn> {
    val sql = if (ledgerId == null) "SELECT * FROM txn WHERE deleted=1 ORDER BY deleted_at DESC"
    else "SELECT * FROM txn WHERE deleted=1 AND ledger_id=? ORDER BY deleted_at DESC"
    val args = if (ledgerId == null) null else arrayOf(ledgerId)
    return readableDatabase.rawQuery(sql, args).use { c ->
        val r = ArrayList<Txn>(); while (c.moveToNext()) r.add(c.toTxn()); r
    }
}

fun Db.purgeTxn(id: String) {
    writableDatabase.delete("txn", "id=?", arrayOf(id))
}

fun Db.countTxns(ledgerId: String): Int =
    readableDatabase.rawQuery("SELECT COUNT(*) FROM txn WHERE ledger_id=? AND deleted=0", arrayOf(ledgerId)).use { c ->
        if (c.moveToFirst()) c.getInt(0) else 0
    }

fun Db.countTxnsAll(ledgerId: String): Int =
    readableDatabase.rawQuery("SELECT COUNT(*) FROM txn WHERE ledger_id=?", arrayOf(ledgerId)).use { c ->
        if (c.moveToFirst()) c.getInt(0) else 0
    }

/** 退款累计（用于原消费详情显示累计退款）。 */
fun Db.refundedAmount(txnId: String): Long =
    readableDatabase.rawQuery(
        "SELECT COALESCE(SUM(amount),0) FROM txn WHERE refund_of=? AND deleted=0", arrayOf(txnId)
    ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

fun Db.refundsOf(txnId: String): List<Txn> =
    readableDatabase.rawQuery("SELECT * FROM txn WHERE refund_of=? AND deleted=0 ORDER BY occurred_at", arrayOf(txnId))
        .use { c -> val r = ArrayList<Txn>(); while (c.moveToNext()) r.add(c.toTxn()); r }

/** 疑似重复：不用金额单独判定，必须同时要求方向一致，并附时间/账户/商户等佐证。 */
fun Db.findSuspectedDuplicates(t: Txn, dayWindow: Int = 3): List<Txn> {
    val start = Dates.shiftDay(t.occurredDate, -dayWindow)
    val end = Dates.shiftDay(t.occurredDate, dayWindow)
    val list = readableDatabase.rawQuery(
        "SELECT * FROM txn WHERE ledger_id=? AND deleted=0 AND type=? AND amount=? AND id<>? AND occurred_date BETWEEN ? AND ?",
        arrayOf(t.ledgerId, t.type.id, t.amount.toString(), t.id, start, end)
    ).use { c -> val r = ArrayList<Txn>(); while (c.moveToNext()) r.add(c.toTxn()); r }
    return list.filter { o ->
        val sameAccount = !t.accountId.isNullOrEmpty() && t.accountId == o.accountId
        val sameMerchant = t.merchantNorm().isNotEmpty() && t.merchantNorm() == o.merchantNorm()
        val sameDay = t.occurredDate == o.occurredDate
        val sameTrade = t.tradeNo.isNotEmpty() && t.tradeNo == o.tradeNo
        sameAccount || sameMerchant || sameDay || sameTrade
    }
}

fun Txn.merchantNorm(): String = com.xzledger.app.data.MerchantNormalizer.normalize(
    if (merchantRaw.isNotEmpty()) merchantRaw else merchant
)

// ---------------- 汇总与统计 ----------------

data class Summary(val income: Long, val expense: Long, val refund: Long, val count: Int) {
    /** 月度结余 = 该月收入 - 该月净支出；与账户余额明确区分。 */
    val netExpense: Long get() = expense - refund
    val balance: Long get() = income - netExpense
}

fun Db.summarize(ledgerId: String, startDate: String, endDate: String): Summary {
    var income = 0L; var expense = 0L; var refund = 0L; var count = 0
    readableDatabase.rawQuery(
        "SELECT type, COALESCE(SUM(amount),0), COUNT(*) FROM txn WHERE ledger_id=? AND deleted=0 " +
            "AND occurred_date BETWEEN ? AND ? AND tx_status NOT IN ('closed','pending') GROUP BY type",
        arrayOf(ledgerId, startDate, endDate)
    ).use { c ->
        while (c.moveToNext()) {
            val amt = c.getLong(1); val n = c.getInt(2)
            when (TxnType.of(c.getString(0))) {
                TxnType.INCOME -> income += amt
                TxnType.EXPENSE -> expense += amt
                TxnType.REFUND -> refund += amt
                TxnType.TRANSFER -> {}
            }
            count += n
        }
    }
    return Summary(income, expense, refund, count)
}

fun Db.monthSummary(ledgerId: String, month: String): Summary {
    val days = Dates.daysInMonth(month)
    return summarize(ledgerId, month + "-01", month + "-" + (if (days < 10) "0" + days else "" + days))
}

/**
 * 某月生效的总预算：优先本月单独设置的那条（month = 当月），否则沿用「每月默认」模板（month = 'ALL'）。
 * 返回 null 表示这个月完全没有设置预算。
 */
fun Db.monthBudgetTotal(ledgerId: String, month: String): Long? =
    budgets(ledgerId, month).asSequence()
        .filter { !it.deleted && it.categoryId == null }
        .sortedByDescending { it.month == month }
        .firstOrNull()?.amount

/**
 * 某月结余（含预算）：本月预算 + 收入 − 净支出（支出 − 退款）。
 * 与明细页「结余」同口径；未设置预算时按 0 计。转账不计入。
 * 注意它与 accountBalance 的「账户余额」是两种口径，界面上要区分标注。
 */
fun Db.monthBalance(ledgerId: String, month: String): Long {
    val s = monthSummary(ledgerId, month)
    return (monthBudgetTotal(ledgerId, month) ?: 0L) + s.income - s.netExpense
}

data class DayTotal(val date: String, val income: Long, val expense: Long, val refund: Long)

fun Db.dailyTotals(ledgerId: String, startDate: String, endDate: String): List<DayTotal> {
    val map = LinkedHashMap<String, LongArray>()
    readableDatabase.rawQuery(
        "SELECT occurred_date, type, COALESCE(SUM(amount),0) FROM txn WHERE ledger_id=? AND deleted=0 " +
            "AND occurred_date BETWEEN ? AND ? AND tx_status NOT IN ('closed','pending') GROUP BY occurred_date, type",
        arrayOf(ledgerId, startDate, endDate)
    ).use { c ->
        while (c.moveToNext()) {
            val d = c.getString(0)
            val arr = map.getOrPut(d) { LongArray(3) }
            when (TxnType.of(c.getString(1))) {
                TxnType.INCOME -> arr[0] += c.getLong(2)
                TxnType.EXPENSE -> arr[1] += c.getLong(2)
                TxnType.REFUND -> arr[2] += c.getLong(2)
                TxnType.TRANSFER -> {}
            }
        }
    }
    return map.entries.sortedByDescending { it.key }.map { DayTotal(it.key, it.value[0], it.value[1], it.value[2]) }
}

/** 账户余额：期初 + 收入 + 退款 - 支出 + 转入 - 转出；转账不计收入支出但影响账户余额。 */
fun Db.accountBalance(accountId: String): Long {
    val acct = account(accountId) ?: return 0L
    var bal = acct.openingBalance
    readableDatabase.rawQuery(
        "SELECT type, COALESCE(SUM(amount),0) FROM txn WHERE deleted=0 AND tx_status NOT IN ('closed','pending') " +
            "AND (account_id=? OR to_account_id=?) GROUP BY type, (account_id=?)",
        arrayOf(accountId, accountId, accountId)
    ).use { c ->
        while (c.moveToNext()) {
            val type = TxnType.of(c.getString(0))
            val amt = c.getLong(1)
            val isFrom = c.getInt(2) == 1
            when (type) {
                TxnType.INCOME -> bal += amt
                TxnType.REFUND -> bal += amt
                TxnType.EXPENSE -> bal -= amt
                TxnType.TRANSFER -> bal += if (isFrom) -amt else amt
            }
        }
    }
    return bal
}

/** 账本结余（余额口径）：所有账户余额之和，转账互相抵消。 */
fun Db.ledgerBalance(ledgerId: String): Long {
    val accts = accounts(ledgerId)
    return accts.sumOf { accountBalance(it.id) }
}

// ---------------- 规则 ----------------

private fun Cursor.toRule() = Rule(
    id = s("id"), ledgerId = s("ledger_id"), name = s("name"), enabled = b("enabled"),
    priority = i("priority"), matchType = s("match_type"), condMerchant = s("cond_merchant"),
    condKeyword = s("cond_keyword"), condAccountId = sn("cond_account_id"),
    condDirection = s("cond_direction"), condSource = s("cond_source"),
    condMinAmount = ln("cond_min_amount"), condMaxAmount = ln("cond_max_amount"),
    setCategoryId = sn("set_category_id"), setNature = Nature.of(sn("set_nature")),
    origin = s("origin"), hitCount = i("hit_count"),
    createdAt = l("created_at"), updatedAt = l("updated_at"), deleted = b("deleted")
)

fun Db.rules(ledgerId: String, includeDeleted: Boolean = false): List<Rule> =
    readableDatabase.rawQuery(
        "SELECT * FROM rule WHERE ledger_id=?" + (if (includeDeleted) "" else " AND deleted=0") + " ORDER BY priority DESC, created_at DESC",
        arrayOf(ledgerId)
    ).use { c -> val r = ArrayList<Rule>(); while (c.moveToNext()) r.add(c.toRule()); r }

fun Db.upsertRule(x: Rule) {
    val cv = ContentValues()
    cv.put("id", x.id); cv.put("ledger_id", x.ledgerId); cv.put("name", x.name)
    cv.put("enabled", if (x.enabled) 1 else 0); cv.put("priority", x.priority)
    cv.put("match_type", x.matchType); cv.put("cond_merchant", x.condMerchant)
    cv.put("cond_keyword", x.condKeyword); cv.put("cond_account_id", x.condAccountId)
    cv.put("cond_direction", x.condDirection); cv.put("cond_source", x.condSource)
    cv.put("cond_min_amount", x.condMinAmount); cv.put("cond_max_amount", x.condMaxAmount)
    cv.put("set_category_id", x.setCategoryId); cv.put("set_nature", x.setNature?.id)
    cv.put("origin", x.origin); cv.put("hit_count", x.hitCount)
    cv.put("created_at", x.createdAt); cv.put("updated_at", x.updatedAt)
    cv.put("deleted", if (x.deleted) 1 else 0)
    writableDatabase.insertWithOnConflict("rule", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    enqueue("rule", x.id, if (x.deleted) "delete" else "upsert", 0)
}

fun Db.bumpRuleHit(id: String) {
    writableDatabase.execSQL("UPDATE rule SET hit_count=hit_count+1 WHERE id=?", arrayOf<Any>(id))
}

// ---------------- 学习事件 ----------------

private fun Cursor.toLearningEvent() = LearningEvent(
    id = s("id"), ledgerId = s("ledger_id"), txnId = sn("txn_id"),
    merchantRaw = s("merchant_raw"), merchantNorm = s("merchant_norm"),
    oldCategoryId = sn("old_category_id"), newCategoryId = sn("new_category_id"),
    oldNature = Nature.of(sn("old_nature")), newNature = Nature.of(sn("new_nature")),
    origin = s("origin").ifEmpty { "user" },
    createdAt = l("created_at"), promoted = b("promoted")
)

fun Db.addLearningEvent(e: LearningEvent) {
    val cv = ContentValues()
    cv.put("id", e.id); cv.put("ledger_id", e.ledgerId); cv.put("txn_id", e.txnId)
    cv.put("merchant_raw", e.merchantRaw); cv.put("merchant_norm", e.merchantNorm)
    cv.put("old_category_id", e.oldCategoryId); cv.put("new_category_id", e.newCategoryId)
    cv.put("old_nature", e.oldNature?.id); cv.put("new_nature", e.newNature?.id)
    cv.put("origin", e.origin)
    cv.put("created_at", e.createdAt); cv.put("promoted", if (e.promoted) 1 else 0)
    writableDatabase.insert("learning_event", null, cv)
}

fun Db.learningEvents(ledgerId: String, limit: Int = 500): List<LearningEvent> =
    readableDatabase.rawQuery(
        "SELECT * FROM learning_event WHERE ledger_id=? ORDER BY created_at DESC LIMIT ?",
        arrayOf(ledgerId, limit.toString())
    ).use { c -> val r = ArrayList<LearningEvent>(); while (c.moveToNext()) r.add(c.toLearningEvent()); r }

fun Db.learningEventsFor(ledgerId: String, merchantNorm: String): List<LearningEvent> =
    readableDatabase.rawQuery(
        "SELECT * FROM learning_event WHERE ledger_id=? AND merchant_norm=? ORDER BY created_at DESC LIMIT 50",
        arrayOf(ledgerId, merchantNorm)
    ).use { c -> val r = ArrayList<LearningEvent>(); while (c.moveToNext()) r.add(c.toLearningEvent()); r }

// ---------------- 预算 ----------------

private fun Cursor.toBudget() = Budget(
    id = s("id"), ledgerId = s("ledger_id"), month = s("month"), categoryId = sn("category_id"),
    amount = l("amount"), createdAt = l("created_at"), updatedAt = l("updated_at"), deleted = b("deleted")
)

fun Db.budgets(ledgerId: String, month: String): List<Budget> =
    readableDatabase.rawQuery(
        "SELECT * FROM budget WHERE ledger_id=? AND deleted=0 AND (month=? OR month='ALL') ORDER BY month DESC",
        arrayOf(ledgerId, month)
    ).use { c -> val r = ArrayList<Budget>(); while (c.moveToNext()) r.add(c.toBudget()); r }

fun Db.allBudgets(ledgerId: String): List<Budget> =
    readableDatabase.rawQuery("SELECT * FROM budget WHERE ledger_id=? AND deleted=0 ORDER BY created_at DESC", arrayOf(ledgerId))
        .use { c -> val r = ArrayList<Budget>(); while (c.moveToNext()) r.add(c.toBudget()); r }

fun Db.upsertBudget(b: Budget) {
    val cv = ContentValues()
    cv.put("id", b.id); cv.put("ledger_id", b.ledgerId); cv.put("month", b.month)
    cv.put("category_id", b.categoryId); cv.put("amount", b.amount)
    cv.put("created_at", b.createdAt); cv.put("updated_at", System.currentTimeMillis())
    cv.put("deleted", if (b.deleted) 1 else 0)
    writableDatabase.insertWithOnConflict("budget", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    enqueue("budget", b.id, "upsert", 0)
}

fun Db.deleteBudget(id: String) {
    writableDatabase.delete("budget", "id=?", arrayOf(id))
}

/** 分类支出（净额）：支出 - 该分类退款。 */
fun Db.categorySpending(ledgerId: String, month: String): Map<String, Long> {
    val days = Dates.daysInMonth(month)
    val start = month + "-01"
    val end = month + "-" + (if (days < 10) "0" + days else "" + days)
    val map = HashMap<String, Long>()
    readableDatabase.rawQuery(
        "SELECT category_id, type, COALESCE(SUM(amount),0) FROM txn WHERE ledger_id=? AND deleted=0 " +
            "AND occurred_date BETWEEN ? AND ? AND tx_status NOT IN ('closed','pending') " +
            "AND type IN ('expense','refund') GROUP BY category_id, type",
        arrayOf(ledgerId, start, end)
    ).use { c ->
        while (c.moveToNext()) {
            val key = c.getString(0) ?: ""
            val amt = c.getLong(2)
            val cur = map[key] ?: 0L
            map[key] = if (c.getString(1) == "refund") cur - amt else cur + amt
        }
    }
    return map
}

/** 消费性质占比（长期／即时／周期）。 */
fun Db.natureSpending(ledgerId: String, start: String, end: String): Map<String, Long> {
    val map = HashMap<String, Long>()
    readableDatabase.rawQuery(
        "SELECT COALESCE(nature,'unknown'), type, COALESCE(SUM(amount),0) FROM txn WHERE ledger_id=? AND deleted=0 " +
            "AND occurred_date BETWEEN ? AND ? AND tx_status NOT IN ('closed','pending') AND type IN ('expense','refund') " +
            "GROUP BY nature, type",
        arrayOf(ledgerId, start, end)
    ).use { c ->
        while (c.moveToNext()) {
            val key = c.getString(0)
            val amt = c.getLong(2)
            val cur = map[key] ?: 0L
            map[key] = if (c.getString(1) == "refund") cur - amt else cur + amt
        }
    }
    return map
}

// ---------------- 周期任务 ----------------

private fun Cursor.toRecurring() = RecurringTask(
    id = s("id"), ledgerId = s("ledger_id"), name = s("name"), type = TxnType.of(s("type")),
    amount = l("amount"), categoryId = sn("category_id"), accountId = sn("account_id"),
    nature = Nature.of(sn("nature")), merchant = s("merchant"), note = s("note"),
    period = s("period"), dayOfMonth = i("day_of_month"), nextDueAt = l("next_due_at"),
    mode = s("mode"), advanceDays = i("advance_days"), enabled = b("enabled"),
    lastRunAt = ln("last_run_at"), lastRunKey = s("last_run_key"),
    createdAt = l("created_at"), updatedAt = l("updated_at"), deleted = b("deleted")
)

fun Db.recurringTasks(ledgerId: String): List<RecurringTask> =
    readableDatabase.rawQuery("SELECT * FROM recurring WHERE ledger_id=? AND deleted=0 ORDER BY next_due_at", arrayOf(ledgerId))
        .use { c -> val r = ArrayList<RecurringTask>(); while (c.moveToNext()) r.add(c.toRecurring()); r }

fun Db.allRecurring(): List<RecurringTask> =
    readableDatabase.rawQuery("SELECT * FROM recurring WHERE deleted=0 ORDER BY next_due_at", null)
        .use { c -> val r = ArrayList<RecurringTask>(); while (c.moveToNext()) r.add(c.toRecurring()); r }

fun Db.upsertRecurring(x: RecurringTask) {
    val cv = ContentValues()
    cv.put("id", x.id); cv.put("ledger_id", x.ledgerId); cv.put("name", x.name)
    cv.put("type", x.type.id); cv.put("amount", x.amount); cv.put("category_id", x.categoryId)
    cv.put("account_id", x.accountId); cv.put("nature", x.nature?.id)
    cv.put("merchant", x.merchant); cv.put("note", x.note); cv.put("period", x.period)
    cv.put("day_of_month", x.dayOfMonth); cv.put("next_due_at", x.nextDueAt)
    cv.put("mode", x.mode); cv.put("advance_days", x.advanceDays)
    cv.put("enabled", if (x.enabled) 1 else 0); cv.put("last_run_at", x.lastRunAt)
    cv.put("last_run_key", x.lastRunKey); cv.put("created_at", x.createdAt)
    cv.put("updated_at", System.currentTimeMillis()); cv.put("deleted", if (x.deleted) 1 else 0)
    writableDatabase.insertWithOnConflict("recurring", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    enqueue("recurring", x.id, "upsert", 0)
}

fun Db.deleteRecurring(id: String) {
    val cv = ContentValues(); cv.put("deleted", 1); cv.put("updated_at", System.currentTimeMillis())
    writableDatabase.update("recurring", cv, "id=?", arrayOf(id))
}

/** 幂等：同一任务同一周期只生成一次，重试、重启、时间变化都不会重复入账。 */
fun Db.recordRecurringRun(id: String, runKey: String, at: Long) {
    val cv = ContentValues(); cv.put("last_run_key", runKey); cv.put("last_run_at", at)
    writableDatabase.update("recurring", cv, "id=?", arrayOf(id))
}

// ---------------- AI 配置 ----------------

private fun Cursor.toAiConfig() = AiConfig(
    id = s("id"), name = s("name"), baseUrl = s("base_url"), apiKey = s("api_key"),
    model = s("model"), apiType = s("api_type"), supportsVision = b("supports_vision"),
    supportsFile = b("supports_file"), isActive = b("is_active"),
    createdAt = l("created_at"), updatedAt = l("updated_at")
)

fun Db.aiConfigs(): List<AiConfig> =
    readableDatabase.rawQuery("SELECT * FROM ai_config ORDER BY created_at", null)
        .use { c -> val r = ArrayList<AiConfig>(); while (c.moveToNext()) r.add(c.toAiConfig()); r }

fun Db.activeAiConfig(): AiConfig? =
    readableDatabase.rawQuery("SELECT * FROM ai_config WHERE is_active=1 LIMIT 1", null).use { c ->
        if (c.moveToFirst()) c.toAiConfig() else null
    }

fun Db.upsertAiConfig(x: AiConfig) {
    val cv = ContentValues()
    cv.put("id", x.id); cv.put("name", x.name); cv.put("base_url", x.baseUrl)
    cv.put("api_key", x.apiKey); cv.put("model", x.model); cv.put("api_type", x.apiType)
    cv.put("supports_vision", if (x.supportsVision) 1 else 0)
    cv.put("supports_file", if (x.supportsFile) 1 else 0)
    cv.put("is_active", if (x.isActive) 1 else 0)
    cv.put("created_at", x.createdAt); cv.put("updated_at", System.currentTimeMillis())
    writableDatabase.insertWithOnConflict("ai_config", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
}

fun Db.deleteAiConfig(id: String) {
    writableDatabase.delete("ai_config", "id=?", arrayOf(id))
}

// ---------------- 聊天记录 ----------------

private fun Cursor.toChatMessage() = ChatMessage(
    id = s("id"), ledgerId = s("ledger_id"), role = s("role"), content = s("content"),
    attachments = jsonToList(s("attachments")), status = s("status"), error = sn("error"),
    createdAt = l("created_at"), txnIds = jsonToList(s("txn_ids")), cardsJson = s("cards_json")
)

fun Db.chatMessages(ledgerId: String, limit: Int = 200): List<ChatMessage> =
    readableDatabase.rawQuery(
        "SELECT * FROM chat_message WHERE ledger_id=? ORDER BY created_at DESC LIMIT ?",
        arrayOf(ledgerId, limit.toString())
    ).use { c -> val r = ArrayList<ChatMessage>(); while (c.moveToNext()) r.add(c.toChatMessage()); r.reversed() }

fun Db.addChatMessage(m: ChatMessage) {
    val cv = ContentValues()
    cv.put("id", m.id); cv.put("ledger_id", m.ledgerId); cv.put("role", m.role)
    cv.put("content", m.content); cv.put("attachments", listToJson(m.attachments))
    cv.put("status", m.status); cv.put("error", m.error); cv.put("created_at", m.createdAt)
    cv.put("txn_ids", listToJson(m.txnIds)); cv.put("cards_json", m.cardsJson)
    writableDatabase.insertWithOnConflict("chat_message", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
}

fun Db.updateChatMessage(m: ChatMessage) = addChatMessage(m)

fun Db.clearChat(ledgerId: String) {
    // 删除聊天记录不删除已生成账单。
    writableDatabase.delete("chat_message", "ledger_id=?", arrayOf(ledgerId))
}

fun Db.deleteChatMessage(id: String) {
    writableDatabase.delete("chat_message", "id=?", arrayOf(id))
}

// ---------------- 导入批次 ----------------

fun Db.addImportBatch(b: ImportBatch) {
    val cv = ContentValues()
    cv.put("id", b.id); cv.put("ledger_id", b.ledgerId); cv.put("file_name", b.fileName)
    cv.put("source", b.source); cv.put("created_at", b.createdAt)
    cv.put("created_txn_ids", listToJson(b.createdTxnIds)); cv.put("undone", if (b.undone) 1 else 0)
    writableDatabase.insertWithOnConflict("import_batch", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
}

fun Db.importBatch(id: String): ImportBatch? =
    readableDatabase.rawQuery("SELECT * FROM import_batch WHERE id=?", arrayOf(id)).use { c ->
        if (!c.moveToFirst()) null
        else ImportBatch(
            id = c.s("id"), ledgerId = c.s("ledger_id"), fileName = c.s("file_name"),
            source = c.s("source"), createdAt = c.l("created_at"),
            createdTxnIds = jsonToList(c.s("created_txn_ids")), undone = c.b("undone")
        )
    }

fun Db.markImportUndone(id: String) {
    val cv = ContentValues(); cv.put("undone", 1)
    writableDatabase.update("import_batch", cv, "id=?", arrayOf(id))
}

fun Db.recentImportBatches(ledgerId: String, limit: Int = 20): List<ImportBatch> =
    readableDatabase.rawQuery(
        "SELECT * FROM import_batch WHERE ledger_id=? ORDER BY created_at DESC LIMIT ?",
        arrayOf(ledgerId, limit.toString())
    ).use { c ->
        val r = ArrayList<ImportBatch>()
        while (c.moveToNext()) r.add(
            ImportBatch(
                id = c.s("id"), ledgerId = c.s("ledger_id"), fileName = c.s("file_name"),
                source = c.s("source"), createdAt = c.l("created_at"),
                createdTxnIds = jsonToList(c.s("created_txn_ids")), undone = c.b("undone")
            )
        )
        r
    }

/** 取同一订单的拆分分片：dedup_key 等于 base 的是主记录，base + "|S<i>" 是拆分分片。 */
fun Db.txnsByDedupPrefix(ledgerId: String, base: String): List<Txn> {
    if (base.isEmpty()) return emptyList()
    return readableDatabase.rawQuery(
        "SELECT * FROM txn WHERE ledger_id=? AND deleted=0 AND (dedup_key=? OR dedup_key LIKE ?) ORDER BY occurred_at ASC",
        arrayOf(ledgerId, base, base + "|S%")
    ).use { c ->
        val r = ArrayList<Txn>()
        while (c.moveToNext()) r.add(c.toTxn())
        r
    }
}

/** 按规范化商户名取账单（用于「批量修改已有匹配账单」与可疑重复核对）。 */
fun Db.txnsByMerchantNorm(ledgerId: String, merchantNorm: String, limit: Int = 300): List<Txn> {
    if (merchantNorm.isEmpty()) return emptyList()
    return readableDatabase.rawQuery(
        "SELECT * FROM txn WHERE ledger_id=? AND merchant_norm=? AND deleted=0 ORDER BY occurred_at DESC LIMIT ?",
        arrayOf(ledgerId, merchantNorm, limit.toString())
    ).use { c ->
        val r = ArrayList<Txn>()
        while (c.moveToNext()) r.add(c.toTxn())
        r
    }
}

/** 批量更新分类/性质（用于规则学习中的「同时批量修改已有匹配账单」）。 */
fun Db.batchUpdateClassification(ids: Collection<String>, categoryId: String?, nature: Nature?) {
    if (ids.isEmpty()) return
    ids.chunked(300).forEach { chunk ->
        val sql = "UPDATE txn SET updated_at=" + System.currentTimeMillis() +
            (if (categoryId != null) ", category_id=?" else "") +
            (if (nature != null) ", nature=?" else "") +
            ", version=version+1 WHERE id IN (" + chunk.joinToString(",") { "?" } + ")"
        val args = ArrayList<String>()
        if (categoryId != null) args.add(categoryId)
        if (nature != null) args.add(nature.id)
        args.addAll(chunk)
        writableDatabase.execSQL(sql, args.toTypedArray())
    }
}
/** 记账页「常用商户」提示：名称取最近一次使用，分类与性质取该商户历史上最常用的一项。 */
data class MerchantHint(
    val name: String,
    val norm: String,
    val categoryId: String?,
    val nature: Nature?,
    val count: Int,
)

/**
 * 从最近 500 条有商户的账单里在内存中聚合，避免为提示功能新增复杂 SQL。
 * [direction] 非空时只取该方向的账单：收入的历史商户与支出的历史商户互不串用。
 */
fun Db.merchantHints(ledgerId: String, limit: Int = 12, direction: TxnType? = null): List<MerchantHint> {
    data class Row(val norm: String, val name: String, val cat: String?, val nat: String?)
    val rows = ArrayList<Row>()
    val dirSql = if (direction != null) " AND type=?" else ""
    readableDatabase.rawQuery(
        "SELECT merchant_norm, merchant, merchant_raw, category_id, nature FROM txn " +
            "WHERE ledger_id=? AND deleted=0 AND tx_status NOT IN ('closed','pending') " +
            "AND (merchant_raw<>'' OR merchant<>'')" + dirSql + " ORDER BY occurred_at DESC LIMIT 500",
        if (direction != null) arrayOf(ledgerId, direction.id) else arrayOf(ledgerId)
    ).use { c ->
        while (c.moveToNext()) {
            val norm = if (c.isNull(0)) "" else c.getString(0)
            val raw = if (c.isNull(2)) "" else c.getString(2)
            val name = raw.ifEmpty { if (c.isNull(1)) "" else c.getString(1) }
            if (norm.isEmpty() || name.isEmpty()) continue
            rows.add(Row(norm, name, if (c.isNull(3)) null else c.getString(3), if (c.isNull(4)) null else c.getString(4)))
        }
    }
    if (rows.isEmpty()) return emptyList()
    val grouped = LinkedHashMap<String, MutableList<Row>>()
    for (r in rows) grouped.getOrPut(r.norm) { ArrayList() }.add(r)
    val out = ArrayList<MerchantHint>()
    for ((norm, list) in grouped) {
        val cat = list.mapNotNull { it.cat }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val natId = list.mapNotNull { it.nat }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        out.add(MerchantHint(MerchantNormalizer.displayName(list.first().name), norm, cat, Nature.of(natId), list.size))
        if (out.size >= limit) break
    }
    return out
}
