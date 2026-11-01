package com.xzledger.app.data

/**
 * 通知自动记账的数据结构（数据库 v4 新增）。
 * 设计原则：
 *  - 待确认候选与账目表 txn 完全分离，未确认的东西不计入收支、统计与导出。
 *  - 通知日志只保留「来源应用 + 判定结果 + 金额」，不保存通知原文，避免敏感信息长期留存。
 *  - 账户关联规则独立成表，用户确认过一次尾号后不再重复询问。
 */
object AutoRecordSchema {
    const val CREATE_PENDING = """CREATE TABLE IF NOT EXISTS pending_txn(
        id TEXT PRIMARY KEY, ledger_id TEXT NOT NULL,
        pkg TEXT NOT NULL DEFAULT '', app_label TEXT NOT NULL DEFAULT '',
        type TEXT NOT NULL DEFAULT 'expense', amount INTEGER NOT NULL,
        balance INTEGER, card_tail TEXT NOT NULL DEFAULT '',
        merchant TEXT NOT NULL DEFAULT '', title TEXT NOT NULL DEFAULT '',
        note TEXT NOT NULL DEFAULT '', currency TEXT NOT NULL DEFAULT 'CNY',
        serial TEXT NOT NULL DEFAULT '', occurred_at INTEGER NOT NULL,
        occurred_date TEXT NOT NULL, kind TEXT NOT NULL DEFAULT 'unknown',
        confidence INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL DEFAULT 'pending',
        reason TEXT NOT NULL DEFAULT '', fingerprint TEXT NOT NULL DEFAULT '',
        duplicate_of TEXT NOT NULL DEFAULT '', account_id TEXT, category_id TEXT,
        nature TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)"""

    const val CREATE_PENDING_INDEX =
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_pending_fp ON pending_txn(ledger_id, fingerprint)"

    const val CREATE_NOTICE_LOG = """CREATE TABLE IF NOT EXISTS notice_log(
        id TEXT PRIMARY KEY, ledger_id TEXT NOT NULL,
        pkg TEXT NOT NULL DEFAULT '', app_label TEXT NOT NULL DEFAULT '',
        kind TEXT NOT NULL DEFAULT '', action TEXT NOT NULL DEFAULT '',
        detail TEXT NOT NULL DEFAULT '', amount INTEGER,
        created_at INTEGER NOT NULL)"""

    const val CREATE_NOTICE_LOG_INDEX =
        "CREATE INDEX IF NOT EXISTS idx_notice_created ON notice_log(created_at)"

    const val CREATE_ACCOUNT_LINK = """CREATE TABLE IF NOT EXISTS account_link(
        id TEXT PRIMARY KEY, ledger_id TEXT NOT NULL, match_key TEXT NOT NULL,
        account_id TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)"""

    const val CREATE_ACCOUNT_LINK_INDEX =
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_account_link ON account_link(ledger_id, match_key)"
}

/** 待确认交易：解析结果落库时只保存记账所需字段。 */
data class PendingTxn(
    val id: String,
    val ledgerId: String,
    val pkg: String,
    val appLabel: String = "",
    val type: TxnType = TxnType.EXPENSE,
    val amount: Long,
    /** 通知里的账户余额，仅作辅助信息展示，绝不参与收支统计。 */
    val balance: Long? = null,
    val cardTail: String = "",
    val merchant: String = "",
    val title: String = "",
    val note: String = "",
    val currency: String = "CNY",
    val serial: String = "",
    val occurredAt: Long,
    val occurredDate: String,
    /** expense / income / refund / transfer / credit_repay / unknown */
    val kind: String = "unknown",
    val confidence: Int = 0,
    /** pending = 等待确认；imported = 已入账；ignored = 用户忽略 */
    val status: String = "pending",
    val reason: String = "",
    val fingerprint: String = "",
    /** 疑似与已有账单重复时指向它，交给用户确认，不自动删除任何一条。 */
    val duplicateOf: String = "",
    val accountId: String? = null,
    val categoryId: String? = null,
    val nature: Nature? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

data class NoticeLog(
    val id: String,
    val ledgerId: String,
    val pkg: String,
    val appLabel: String,
    val kind: String,
    val action: String,
    val detail: String = "",
    val amount: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)
