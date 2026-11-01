package com.xzledger.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

private fun android.database.Cursor.toPending() = PendingTxn(
    id = s("id"), ledgerId = s("ledger_id"), pkg = s("pkg"), appLabel = s("app_label"),
    type = TxnType.of(s("type")), amount = l("amount"),
    balance = ln("balance"), cardTail = s("card_tail"),
    merchant = s("merchant"), title = s("title"), note = s("note"),
    currency = s("currency").ifEmpty { "CNY" }, serial = s("serial"),
    occurredAt = l("occurred_at"), occurredDate = s("occurred_date"),
    kind = s("kind").ifEmpty { "unknown" }, confidence = i("confidence"),
    status = s("status").ifEmpty { "pending" }, reason = s("reason"),
    fingerprint = s("fingerprint"), duplicateOf = s("duplicate_of"),
    accountId = sn("account_id"), categoryId = sn("category_id"),
    nature = Nature.of(sn("nature")),
    createdAt = l("created_at"), updatedAt = l("updated_at"),
)

private fun pendingValues(p: PendingTxn): ContentValues {
    val v = ContentValues()
    v.put("id", p.id); v.put("ledger_id", p.ledgerId)
    v.put("pkg", p.pkg); v.put("app_label", p.appLabel)
    v.put("type", p.type.id); v.put("amount", p.amount); v.put("balance", p.balance)
    v.put("card_tail", p.cardTail); v.put("merchant", p.merchant); v.put("title", p.title)
    v.put("note", p.note); v.put("currency", p.currency); v.put("serial", p.serial)
    v.put("occurred_at", p.occurredAt); v.put("occurred_date", p.occurredDate)
    v.put("kind", p.kind); v.put("confidence", p.confidence); v.put("status", p.status)
    v.put("reason", p.reason); v.put("fingerprint", p.fingerprint)
    v.put("duplicate_of", p.duplicateOf)
    v.put("account_id", p.accountId); v.put("category_id", p.categoryId)
    v.put("nature", p.nature?.id)
    v.put("created_at", p.createdAt); v.put("updated_at", p.updatedAt)
    return v
}

/** 写入待确认候选。同一指纹（同来源同金额同时间同商户）只保留一条，返回 true 表示新插入。 */
fun Db.insertPending(p: PendingTxn): Boolean {
    val row = writableDatabase.insertWithOnConflict(
        "pending_txn", null, pendingValues(p), SQLiteDatabase.CONFLICT_IGNORE
    )
    return row != -1L
}

fun Db.pendingTxns(ledgerId: String, status: String = "pending"): List<PendingTxn> {
    val sql = if (status.isEmpty()) "SELECT * FROM pending_txn WHERE ledger_id=? ORDER BY occurred_at DESC"
    else "SELECT * FROM pending_txn WHERE ledger_id=? AND status=? ORDER BY occurred_at DESC"
    val args = if (status.isEmpty()) arrayOf(ledgerId) else arrayOf(ledgerId, status)
    return readableDatabase.rawQuery(sql, args).use { c ->
        val r = ArrayList<PendingTxn>(); while (c.moveToNext()) r.add(c.toPending()); r
    }
}

fun Db.pendingCount(ledgerId: String): Int =
    readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM pending_txn WHERE ledger_id=? AND status='pending'", arrayOf(ledgerId)
    ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

fun Db.pendingById(id: String): PendingTxn? =
    readableDatabase.rawQuery("SELECT * FROM pending_txn WHERE id=?", arrayOf(id)).use { c ->
        if (c.moveToFirst()) c.toPending() else null
    }

fun Db.pendingByFingerprint(ledgerId: String, fp: String): PendingTxn? {
    if (fp.isEmpty()) return null
    return readableDatabase.rawQuery(
        "SELECT * FROM pending_txn WHERE ledger_id=? AND fingerprint=?", arrayOf(ledgerId, fp)
    ).use { c -> if (c.moveToFirst()) c.toPending() else null }
}

internal fun Db.updatePendingRow(p: PendingTxn) {
    writableDatabase.update("pending_txn", pendingValues(p), "id=?", arrayOf(p.id))
}

fun Db.updatePending(p: PendingTxn) = updatePendingRow(p.copy(updatedAt = System.currentTimeMillis()))

fun Db.deletePending(id: String) {
    writableDatabase.delete("pending_txn", "id=?", arrayOf(id))
}

fun Db.clearPending(ledgerId: String) {
    writableDatabase.delete("pending_txn", "ledger_id=?", arrayOf(ledgerId))
}

/** 通知处理日志：只记录来源、判定与结果，不含通知原文。 */
fun Db.addNoticeLog(x: NoticeLog) {
    val v = ContentValues()
    v.put("id", x.id); v.put("ledger_id", x.ledgerId)
    v.put("pkg", x.pkg); v.put("app_label", x.appLabel)
    v.put("kind", x.kind); v.put("action", x.action)
    v.put("detail", x.detail); v.put("amount", x.amount)
    v.put("created_at", x.createdAt)
    writableDatabase.insertWithOnConflict("notice_log", null, v, SQLiteDatabase.CONFLICT_REPLACE)
}

fun Db.noticeLogs(ledgerId: String, limit: Int = 100): List<NoticeLog> =
    readableDatabase.rawQuery(
        "SELECT * FROM notice_log WHERE ledger_id=? ORDER BY created_at DESC LIMIT ?",
        arrayOf(ledgerId, limit.toString())
    ).use { c ->
        val r = ArrayList<NoticeLog>()
        while (c.moveToNext()) r.add(
            NoticeLog(
                id = c.s("id"), ledgerId = c.s("ledger_id"), pkg = c.s("pkg"),
                appLabel = c.s("app_label"), kind = c.s("kind"), action = c.s("action"),
                detail = c.s("detail"), amount = c.ln("amount"), createdAt = c.l("created_at")
            )
        )
        r
    }

fun Db.clearNoticeLog(ledgerId: String) {
    writableDatabase.delete("notice_log", "ledger_id=?", arrayOf(ledgerId))
}

/** 日志只保留最近 N 条，减少敏感信息在本地停留的时间。 */
fun Db.trimNoticeLog(ledgerId: String, keep: Int = 200) {
    writableDatabase.execSQL(
        "DELETE FROM notice_log WHERE ledger_id=? AND id NOT IN " +
            "(SELECT id FROM notice_log WHERE ledger_id=? ORDER BY created_at DESC LIMIT ?)",
        arrayOf(ledgerId, ledgerId, keep)
    )
}

/** 账户关联规则：match_key 形如 "cmb:1234"（银行+尾号）或 "com.eg.android.AlipayGphone"。 */
fun Db.accountLink(ledgerId: String, matchKey: String): String? {
    if (matchKey.isEmpty()) return null
    return readableDatabase.rawQuery(
        "SELECT account_id FROM account_link WHERE ledger_id=? AND match_key=?",
        arrayOf(ledgerId, matchKey)
    ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
}

fun Db.saveAccountLink(ledgerId: String, matchKey: String, accountId: String) {
    if (matchKey.isEmpty() || accountId.isEmpty()) return
    val now = System.currentTimeMillis()
    val old = accountLink(ledgerId, matchKey)
    val v = ContentValues()
    v.put("id", ledgerId + "|" + matchKey)
    v.put("ledger_id", ledgerId); v.put("match_key", matchKey)
    v.put("account_id", accountId); v.put("created_at", now); v.put("updated_at", now)
    writableDatabase.insertWithOnConflict("account_link", null, v, SQLiteDatabase.CONFLICT_REPLACE)
}

fun Db.accountLinks(ledgerId: String): Map<String, String> =
    readableDatabase.rawQuery(
        "SELECT match_key, account_id FROM account_link WHERE ledger_id=?", arrayOf(ledgerId)
    ).use { c ->
        val m = LinkedHashMap<String, String>()
        while (c.moveToNext()) m[c.getString(0)] = c.getString(1)
        m
    }

/** 疑似重复检查用的轻量行（只取判断必要的列，不把整行账单读进内存）。 */
data class SimilarTxn(
    val id: String,
    val source: String,
    val sourcePlatform: String,
    val occurredAt: Long,
    val amount: Long,
    val type: TxnType,
    val merchant: String,
)

/** 同金额、同方向、时间窗内的已有账单；用于「跨应用同一笔交易」的疑似重复提示。 */
fun Db.similarTxns(
    ledgerId: String,
    type: TxnType,
    amount: Long,
    at: Long,
    windowMs: Long = 10 * 60 * 1000L,
): List<SimilarTxn> {
    if (amount <= 0L) return emptyList()
    return readableDatabase.rawQuery(
        "SELECT id, source, source_platform, occurred_at, amount, type, merchant FROM txn " +
            "WHERE ledger_id=? AND deleted=0 AND type=? AND amount=? AND occurred_at BETWEEN ? AND ? LIMIT 5",
        arrayOf(ledgerId, type.id, amount.toString(), (at - windowMs).toString(), (at + windowMs).toString())
    ).use { c ->
        val r = ArrayList<SimilarTxn>()
        while (c.moveToNext()) {
            r.add(
                SimilarTxn(
                    id = c.getString(0), source = c.getString(1), sourcePlatform = c.getString(2),
                    occurredAt = c.getLong(3), amount = c.getLong(4),
                    type = TxnType.of(c.getString(5)), merchant = c.getString(6)
                )
            )
        }
        r
    }
}
