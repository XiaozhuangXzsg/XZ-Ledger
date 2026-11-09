package com.xzledger.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import java.util.UUID

object Ids {
    const val DEFAULT_LEDGER = "ledger-default"
    const val OWNER_LOCAL = "local"
    fun newId(): String = UUID.randomUUID().toString()
}

internal fun Cursor.s(name: String): String {
    val i = getColumnIndex(name); return if (i < 0 || isNull(i)) "" else getString(i)
}
internal fun Cursor.sn(name: String): String? {
    val i = getColumnIndex(name); return if (i < 0 || isNull(i)) null else getString(i)
}
internal fun Cursor.l(name: String): Long {
    val i = getColumnIndex(name); return if (i < 0 || isNull(i)) 0L else getLong(i)
}
internal fun Cursor.ln(name: String): Long? {
    val i = getColumnIndex(name); return if (i < 0 || isNull(i)) null else getLong(i)
}
internal fun Cursor.i(name: String): Int {
    val i = getColumnIndex(name); return if (i < 0 || isNull(i)) 0 else getInt(i)
}
internal fun Cursor.b(name: String): Boolean = i(name) != 0

internal fun jsonToList(json: String): List<String> = try {
    if (json.isBlank()) emptyList()
    else {
        val a = JSONArray(json)
        (0 until a.length()).map { a.optString(it) }
    }
} catch (e: Exception) { emptyList() }

internal fun listToJson(list: List<String>): String {
    val a = JSONArray()
    list.forEach { a.put(it) }
    return a.toString()
}

/**
 * 本地持久化数据库（SQLite，离线可用）。
 * 设计要点：
 *  - 金额全部 INTEGER（分）。
 *  - occurred_date 为本地日历日字符串，统计口径唯一。
 *  - source_platform + trade_no / order_no 生成 dedup_key，并建立数据库层唯一约束，
 *    不依赖界面检查，覆盖手动、AI、截图、Excel、PDF、CSV、云同步全部入口。
 *  - deleted + deleted_at 作为同步墓碑，避免离线设备把已删除账单重新上传。
 *  - version 用于并发修改检测。
 */
class Db(context: Context) : SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    init { setWriteAheadLoggingEnabled(true) }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE ledger(
            id TEXT PRIMARY KEY, name TEXT NOT NULL, owner_id TEXT NOT NULL DEFAULT 'local',
            sort_order INTEGER NOT NULL DEFAULT 0, is_default INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
            deleted INTEGER NOT NULL DEFAULT 0, version INTEGER NOT NULL DEFAULT 1)""")

        db.execSQL("""CREATE TABLE account(
            id TEXT PRIMARY KEY, ledger_id TEXT NOT NULL, name TEXT NOT NULL,
            type TEXT NOT NULL DEFAULT 'other', icon TEXT NOT NULL DEFAULT '',
            opening_balance INTEGER NOT NULL DEFAULT 0, currency TEXT NOT NULL DEFAULT 'CNY',
            sort_order INTEGER NOT NULL DEFAULT 0, archived INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
            deleted INTEGER NOT NULL DEFAULT 0, version INTEGER NOT NULL DEFAULT 1)""")

        db.execSQL("""CREATE TABLE category(
            id TEXT PRIMARY KEY, ledger_id TEXT NOT NULL, name TEXT NOT NULL,
            kind TEXT NOT NULL, icon TEXT NOT NULL DEFAULT '', nature_default TEXT,
            parent_id TEXT, sort_order INTEGER NOT NULL DEFAULT 0,
            is_system INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
            deleted INTEGER NOT NULL DEFAULT 0, version INTEGER NOT NULL DEFAULT 1)""")

        db.execSQL("""CREATE TABLE txn(
            id TEXT PRIMARY KEY, ledger_id TEXT NOT NULL, type TEXT NOT NULL,
            amount INTEGER NOT NULL, currency TEXT NOT NULL DEFAULT 'CNY',
            nature TEXT, category_id TEXT, account_id TEXT, to_account_id TEXT,
            title TEXT NOT NULL DEFAULT '', merchant TEXT NOT NULL DEFAULT '',
            merchant_raw TEXT NOT NULL DEFAULT '', merchant_norm TEXT NOT NULL DEFAULT '',
            note TEXT NOT NULL DEFAULT '',
            occurred_date TEXT NOT NULL, occurred_at INTEGER NOT NULL,
            created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
            source TEXT NOT NULL DEFAULT 'manual', source_platform TEXT NOT NULL DEFAULT '',
            order_no TEXT NOT NULL DEFAULT '', trade_no TEXT NOT NULL DEFAULT '',
            tx_status TEXT NOT NULL DEFAULT 'success', refund_of TEXT, linked_txn_id TEXT,
            dedup_key TEXT NOT NULL DEFAULT '', classify_basis TEXT NOT NULL DEFAULT 'manual',
            classify_rule_id TEXT, image_paths TEXT NOT NULL DEFAULT '[]',
            tags TEXT NOT NULL DEFAULT '[]', chat_msg_id TEXT, import_batch_id TEXT,
            deleted INTEGER NOT NULL DEFAULT 0, deleted_at INTEGER,
            version INTEGER NOT NULL DEFAULT 1)""")
        db.execSQL("CREATE INDEX idx_txn_ledger_date ON txn(ledger_id, occurred_date)")
        db.execSQL("CREATE INDEX idx_txn_category ON txn(category_id)")
        db.execSQL("CREATE INDEX idx_txn_account ON txn(account_id)")
        db.execSQL("CREATE INDEX idx_txn_fuzzy ON txn(ledger_id, amount, occurred_date, type)")
        db.execSQL("CREATE UNIQUE INDEX idx_txn_dedup ON txn(ledger_id, dedup_key) WHERE dedup_key <> ''")

        db.execSQL("""CREATE TABLE rule(
            id TEXT PRIMARY KEY, ledger_id TEXT NOT NULL, name TEXT NOT NULL,
            enabled INTEGER NOT NULL DEFAULT 1, priority INTEGER NOT NULL DEFAULT 100,
            match_type TEXT NOT NULL DEFAULT 'merchant_exact',
            cond_merchant TEXT NOT NULL DEFAULT '', cond_keyword TEXT NOT NULL DEFAULT '',
            cond_account_id TEXT, cond_direction TEXT NOT NULL DEFAULT '',
            cond_source TEXT NOT NULL DEFAULT '', cond_min_amount INTEGER, cond_max_amount INTEGER,
            set_category_id TEXT, set_nature TEXT, origin TEXT NOT NULL DEFAULT 'user',
            hit_count INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0)""")

        db.execSQL("""CREATE TABLE learning_event(
            id TEXT PRIMARY KEY, ledger_id TEXT NOT NULL, txn_id TEXT,
            merchant_raw TEXT NOT NULL DEFAULT '', merchant_norm TEXT NOT NULL DEFAULT '',
            old_category_id TEXT, new_category_id TEXT, old_nature TEXT, new_nature TEXT,
            origin TEXT NOT NULL DEFAULT 'user',
            created_at INTEGER NOT NULL, promoted INTEGER NOT NULL DEFAULT 0)""")

        db.execSQL("""CREATE TABLE budget(
            id TEXT PRIMARY KEY, ledger_id TEXT NOT NULL, month TEXT NOT NULL DEFAULT 'ALL',
            category_id TEXT, amount INTEGER NOT NULL, created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0)""")

        db.execSQL("""CREATE TABLE recurring(
            id TEXT PRIMARY KEY, ledger_id TEXT NOT NULL, name TEXT NOT NULL,
            type TEXT NOT NULL DEFAULT 'expense', amount INTEGER NOT NULL,
            category_id TEXT, account_id TEXT, nature TEXT,
            merchant TEXT NOT NULL DEFAULT '', note TEXT NOT NULL DEFAULT '',
            period TEXT NOT NULL DEFAULT 'monthly', day_of_month INTEGER NOT NULL DEFAULT 1,
            next_due_at INTEGER NOT NULL, mode TEXT NOT NULL DEFAULT 'remind',
            advance_days INTEGER NOT NULL DEFAULT 1, enabled INTEGER NOT NULL DEFAULT 1,
            last_run_at INTEGER, last_run_key TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
            deleted INTEGER NOT NULL DEFAULT 0)""")

        db.execSQL("""CREATE TABLE ai_config(
            id TEXT PRIMARY KEY, name TEXT NOT NULL, base_url TEXT NOT NULL,
            api_key TEXT NOT NULL DEFAULT '', model TEXT NOT NULL,
            api_type TEXT NOT NULL DEFAULT 'openai',
            supports_vision INTEGER NOT NULL DEFAULT 0, supports_file INTEGER NOT NULL DEFAULT 0,
            is_active INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL)""")

        db.execSQL("""CREATE TABLE ai_permission(
            key TEXT PRIMARY KEY, granted INTEGER NOT NULL DEFAULT 0)""")

        db.execSQL("""CREATE TABLE chat_message(
            id TEXT PRIMARY KEY, ledger_id TEXT NOT NULL, role TEXT NOT NULL,
            content TEXT NOT NULL DEFAULT '', attachments TEXT NOT NULL DEFAULT '[]',
            status TEXT NOT NULL DEFAULT 'ok', error TEXT, created_at INTEGER NOT NULL,
            txn_ids TEXT NOT NULL DEFAULT '[]', cards_json TEXT NOT NULL DEFAULT '')""")

        db.execSQL("""CREATE TABLE import_batch(
            id TEXT PRIMARY KEY, ledger_id TEXT NOT NULL, file_name TEXT NOT NULL DEFAULT '',
            source TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL,
            created_txn_ids TEXT NOT NULL DEFAULT '[]', undone INTEGER NOT NULL DEFAULT 0)""")

        db.execSQL("""CREATE TABLE setting(key TEXT PRIMARY KEY, value TEXT NOT NULL DEFAULT '')""")

        // v4：通知自动记账。待确认交易与通知日志与账目表分离，
        // 保证未确认的候选不会污染账本、统计与导出。
        db.execSQL(AutoRecordSchema.CREATE_PENDING)
        db.execSQL(AutoRecordSchema.CREATE_PENDING_INDEX)
        db.execSQL(AutoRecordSchema.CREATE_NOTICE_LOG)
        db.execSQL(AutoRecordSchema.CREATE_NOTICE_LOG_INDEX)
        db.execSQL(AutoRecordSchema.CREATE_ACCOUNT_LINK)

        db.execSQL("""CREATE TABLE outbox(
            id TEXT PRIMARY KEY, entity TEXT NOT NULL, entity_id TEXT NOT NULL,
            op TEXT NOT NULL, payload TEXT NOT NULL DEFAULT '', base_version INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'pending')""")

        seed(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 增量迁移：只加列、不重建表，避免丢失用户账目。
        if (oldVersion < 2) {
            // v1 建表时漏了 txn.merchant_norm（规范化商户名，用于个人规则与历史习惯匹配）。
            try {
                db.execSQL("ALTER TABLE txn ADD COLUMN merchant_norm TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE txn SET merchant_norm = merchant WHERE merchant_norm = ''")
            } catch (e: Exception) {
                // 列已存在时忽略
            }
        }
        if (oldVersion < 4) {
            // v4：通知自动记账新增待确认表、通知日志表、账户关联规则表。只增表，不动既有账目。
            db.execSQL(AutoRecordSchema.CREATE_PENDING)
            db.execSQL(AutoRecordSchema.CREATE_PENDING_INDEX)
            db.execSQL(AutoRecordSchema.CREATE_NOTICE_LOG)
            db.execSQL(AutoRecordSchema.CREATE_NOTICE_LOG_INDEX)
            db.execSQL(AutoRecordSchema.CREATE_ACCOUNT_LINK)
        }
        if (oldVersion < 3) {
            // v3：学习记录增加来源列（ai = 由配置的模型学习产生，user = 用户修改后记录）。
            try {
                db.execSQL("ALTER TABLE learning_event ADD COLUMN origin TEXT NOT NULL DEFAULT 'user'")
            } catch (e: Exception) {
                // 列已存在时忽略
            }
        }
    }

    private fun now(): Long = System.currentTimeMillis()

    private fun seed(db: SQLiteDatabase) {
        val t = now()
        val cv = ContentValues()
        cv.put("id", Ids.DEFAULT_LEDGER)
        cv.put("name", "默认账本")
        cv.put("owner_id", Ids.OWNER_LOCAL)
        cv.put("sort_order", 0)
        cv.put("is_default", 1)
        cv.put("created_at", t); cv.put("updated_at", t)
        cv.put("deleted", 0); cv.put("version", 1)
        db.insert("ledger", null, cv)

        val accounts = listOf(
            Triple("现金", "cash", "💵"),
            Triple("支付宝", "alipay", "📱"),
            Triple("微信", "wechat", "💬"),
            Triple("储蓄卡", "debit", "🏦"),
            Triple("信用卡", "credit", "💳")
        )
        accounts.forEachIndexed { idx, a ->
            val c = ContentValues()
            c.put("id", "acct-" + a.second)
            c.put("ledger_id", Ids.DEFAULT_LEDGER)
            c.put("name", a.first); c.put("type", a.second); c.put("icon", a.third)
            c.put("opening_balance", 0L); c.put("currency", "CNY")
            c.put("sort_order", idx); c.put("archived", 0)
            c.put("created_at", t); c.put("updated_at", t)
            c.put("deleted", 0); c.put("version", 1)
            db.insert("account", null, c)
        }

        // 消费分类与收入分类：nature_default 为 null 表示不预设消费性质，需结合具体商品判断。
        val expense = listOf(
            Pair("餐饮", "🍚") to "instant",
            Pair("交通", "🚌") to "instant",
            Pair("网购", "📦") to null,
            Pair("超市零食", "🛒") to "instant",
            Pair("娱乐", "🎮") to "instant",
            Pair("电子产品", "💻") to "permanent",
            Pair("生活用品", "🧻") to null,
            Pair("家具", "🛋") to "permanent",
            Pair("服饰", "👕") to "permanent",
            Pair("学习", "📚") to "permanent",
            Pair("医疗", "💊") to "instant",
            Pair("话费", "📱") to "periodic",
            Pair("会员订阅", "🎫") to "periodic",
            Pair("游戏充值", "🕹") to "instant",
            Pair("AI会员充值", "🤖") to "periodic",
            Pair("房租", "🏠") to "periodic",
            Pair("其他", "📌") to null
        )
        var order = 0
        expense.forEach { (pair, nature) ->
            val c = ContentValues()
            c.put("id", "cat-e-" + order)
            c.put("ledger_id", Ids.DEFAULT_LEDGER)
            c.put("name", pair.first); c.put("kind", "expense"); c.put("icon", pair.second)
            c.put("nature_default", nature); c.put("sort_order", order)
            c.put("is_system", 1); c.put("created_at", t); c.put("updated_at", t)
            c.put("deleted", 0); c.put("version", 1)
            db.insert("category", null, c)
            order++
        }

        val income = listOf(
            Pair("生活费", "💰"),
            Pair("奖学金", "🏆"),
            Pair("兼职", "💼"),
            Pair("红包", "🧧"),
            Pair("其他收入", "➕")
        )
        order = 0
        income.forEach { pair ->
            val c = ContentValues()
            c.put("id", "cat-i-" + order)
            c.put("ledger_id", Ids.DEFAULT_LEDGER)
            c.put("name", pair.first); c.put("kind", "income"); c.put("icon", pair.second)
            c.put("sort_order", order); c.put("is_system", 1)
            c.put("created_at", t); c.put("updated_at", t)
            c.put("deleted", 0); c.put("version", 1)
            db.insert("category", null, c)
            order++
        }

        // AI 权限默认全部关闭，用户显式授权后才可用。
        listOf("read_ledger", "create_txn", "auto_post", "delete_txn", "cross_ledger").forEach { k ->
            val c = ContentValues(); c.put("key", k); c.put("granted", 0)
            db.insert("ai_permission", null, c)
        }
    }

    // ---------------- 设置 KV ----------------

    fun getSetting(key: String, def: String = ""): String =
        readableDatabase.rawQuery("SELECT value FROM setting WHERE key=?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) ?: def else def
        }

    fun setSetting(key: String, value: String) {
        val cv = ContentValues(); cv.put("key", key); cv.put("value", value)
        writableDatabase.insertWithOnConflict("setting", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ---------------- 账本 ----------------

    private fun Cursor.toLedger() = Ledger(
        id = s("id"), name = s("name"), ownerId = s("owner_id"),
        sortOrder = i("sort_order"), isDefault = b("is_default"),
        createdAt = l("created_at"), updatedAt = l("updated_at"),
        deleted = b("deleted"), version = i("version")
    )

    fun ledgers(): List<Ledger> =
        readableDatabase.rawQuery("SELECT * FROM ledger WHERE deleted=0 ORDER BY is_default DESC, sort_order, created_at", null)
            .use { c -> val r = ArrayList<Ledger>(); while (c.moveToNext()) r.add(c.toLedger()); r }

    fun ledger(id: String): Ledger? =
        readableDatabase.rawQuery("SELECT * FROM ledger WHERE id=?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) c.toLedger() else null
        }

    fun upsertLedger(x: Ledger) {
        val cv = ContentValues()
        cv.put("id", x.id); cv.put("name", x.name); cv.put("owner_id", x.ownerId)
        cv.put("sort_order", x.sortOrder); cv.put("is_default", if (x.isDefault) 1 else 0)
        cv.put("created_at", x.createdAt); cv.put("updated_at", x.updatedAt)
        cv.put("deleted", if (x.deleted) 1 else 0); cv.put("version", x.version)
        writableDatabase.insertWithOnConflict("ledger", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        enqueue("ledger", x.id, if (x.deleted) "delete" else "upsert", x.version)
    }

    fun markLedgerDeleted(id: String) {
        val cv = ContentValues(); cv.put("deleted", 1); cv.put("updated_at", now())
        writableDatabase.update("ledger", cv, "id=?", arrayOf(id))
        enqueue("ledger", id, "delete", 0)
    }

    // ---------------- 账户 ----------------

    private fun Cursor.toAccount() = Account(
        id = s("id"), ledgerId = s("ledger_id"), name = s("name"),
        type = AccountType.of(s("type")), icon = s("icon"),
        openingBalance = l("opening_balance"), currency = s("currency"),
        sortOrder = i("sort_order"), archived = b("archived"),
        createdAt = l("created_at"), updatedAt = l("updated_at"),
        deleted = b("deleted"), version = i("version")
    )

    fun accounts(ledgerId: String): List<Account> =
        readableDatabase.rawQuery("SELECT * FROM account WHERE ledger_id=? AND deleted=0 ORDER BY sort_order, created_at", arrayOf(ledgerId))
            .use { c -> val r = ArrayList<Account>(); while (c.moveToNext()) r.add(c.toAccount()); r }

    fun account(id: String?): Account? {
        if (id.isNullOrEmpty()) return null
        return readableDatabase.rawQuery("SELECT * FROM account WHERE id=?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) c.toAccount() else null
        }
    }

    fun upsertAccount(x: Account) {
        val cv = ContentValues()
        cv.put("id", x.id); cv.put("ledger_id", x.ledgerId); cv.put("name", x.name)
        cv.put("type", x.type.id); cv.put("icon", x.icon)
        cv.put("opening_balance", x.openingBalance); cv.put("currency", x.currency)
        cv.put("sort_order", x.sortOrder); cv.put("archived", if (x.archived) 1 else 0)
        cv.put("created_at", x.createdAt); cv.put("updated_at", x.updatedAt)
        cv.put("deleted", if (x.deleted) 1 else 0); cv.put("version", x.version + 1)
        writableDatabase.insertWithOnConflict("account", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        enqueue("account", x.id, if (x.deleted) "delete" else "upsert", x.version + 1)
    }

    // ---------------- 分类 ----------------

    private fun Cursor.toCategory() = Category(
        id = s("id"), ledgerId = s("ledger_id"), name = s("name"), kind = s("kind"),
        icon = s("icon"), natureDefault = Nature.of(sn("nature_default")), parentId = sn("parent_id"),
        sortOrder = i("sort_order"), isSystem = b("is_system"),
        createdAt = l("created_at"), updatedAt = l("updated_at"),
        deleted = b("deleted"), version = i("version")
    )

    fun categories(ledgerId: String, kind: String? = null): List<Category> {
        val sql = StringBuilder("SELECT * FROM category WHERE ledger_id=? AND deleted=0")
        val args = ArrayList<String>(); args.add(ledgerId)
        if (kind != null) { sql.append(" AND kind=?"); args.add(kind) }
        sql.append(" ORDER BY sort_order, created_at")
        return readableDatabase.rawQuery(sql.toString(), args.toTypedArray()).use { c ->
            val r = ArrayList<Category>(); while (c.moveToNext()) r.add(c.toCategory()); r
        }
    }

    fun category(id: String?): Category? {
        if (id.isNullOrEmpty()) return null
        return readableDatabase.rawQuery("SELECT * FROM category WHERE id=?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) c.toCategory() else null
        }
    }

    fun upsertCategory(x: Category) {
        val cv = ContentValues()
        cv.put("id", x.id); cv.put("ledger_id", x.ledgerId); cv.put("name", x.name)
        cv.put("kind", x.kind); cv.put("icon", x.icon)
        cv.put("nature_default", x.natureDefault?.id); cv.put("parent_id", x.parentId)
        cv.put("sort_order", x.sortOrder); cv.put("is_system", if (x.isSystem) 1 else 0)
        cv.put("created_at", x.createdAt); cv.put("updated_at", x.updatedAt)
        cv.put("deleted", if (x.deleted) 1 else 0); cv.put("version", x.version + 1)
        writableDatabase.insertWithOnConflict("category", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        enqueue("category", x.id, if (x.deleted) "delete" else "upsert", x.version + 1)
    }

    /** 分类被使用次数，用于删除前提示。 */
    fun countTxnByCategory(categoryId: String): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM txn WHERE category_id=? AND deleted=0", arrayOf(categoryId)).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    // ---------------- 同步 outbox ----------------

    /** 待同步的写入操作。每笔写入都有唯一编号，用于服务端重试去重。 */
    data class OutboxItem(
        val id: String,
        val entity: String,
        val entityId: String,
        val op: String,
        val baseVersion: Int,
        val createdAt: Long,
    )

    fun enqueue(entity: String, entityId: String, op: String, baseVersion: Int) {
        val cv = ContentValues()
        cv.put("id", Ids.newId()); cv.put("entity", entity); cv.put("entity_id", entityId)
        cv.put("op", op); cv.put("payload", ""); cv.put("base_version", baseVersion)
        cv.put("created_at", now()); cv.put("status", "pending")
        // 同一条记录只保留最后一次待同步操作：服务端按 (entity, entity_id) 做唯一约束，
        // 重复排队既没有意义，也会让「待同步队列」随着每次编辑无限膨胀。
        writableDatabase.delete("outbox", "entity=? AND entity_id=? AND status='pending'", arrayOf(entity, entityId))
        writableDatabase.insert("outbox", null, cv)
    }

    fun outboxCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM outbox WHERE status='pending'", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    fun outboxItems(limit: Int = 200): List<OutboxItem> =
        readableDatabase.rawQuery(
            "SELECT id, entity, entity_id, op, base_version, created_at FROM outbox WHERE status='pending' ORDER BY created_at LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            val r = ArrayList<OutboxItem>()
            while (c.moveToNext()) {
                r.add(OutboxItem(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4), c.getLong(5)))
            }
            r
        }

    fun markOutboxSynced(id: String) {
        val cv = ContentValues(); cv.put("status", "done")
        writableDatabase.update("outbox", cv, "id=?", arrayOf(id))
    }

    // ---------------- AI 权限 ----------------

    fun aiPermission(key: String): Boolean =
        readableDatabase.rawQuery("SELECT granted FROM ai_permission WHERE key=?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getInt(0) != 0 else false
        }

    fun setAiPermission(key: String, granted: Boolean) {
        val cv = ContentValues(); cv.put("key", key); cv.put("granted", if (granted) 1 else 0)
        writableDatabase.insertWithOnConflict("ai_permission", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun allAiPermissions(): Map<String, Boolean> =
        readableDatabase.rawQuery("SELECT key, granted FROM ai_permission", null).use { c ->
            val m = LinkedHashMap<String, Boolean>()
            while (c.moveToNext()) m[c.getString(0)] = c.getInt(1) != 0
            m
        }

    companion object {
        const val NAME = "xz_ledger.db"
        /** 数据库结构版本。升版本必须同时补齐 onUpgrade 迁移，禁止破坏性重建。 */
        const val VERSION = 4
    }
}
