package com.xzledger.app.data

import com.xzledger.app.util.Dates
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RestoreResult(
    val ledgers: Int = 0,
    val accounts: Int = 0,
    val categories: Int = 0,
    val rules: Int = 0,
    val budgets: Int = 0,
    val recurring: Int = 0,
    val txns: Int = 0,
    val skipped: Int = 0,
    val errors: List<String> = emptyList()
) {
    val ok: Boolean get() = errors.isEmpty() || txns + ledgers + accounts + categories > 0
    fun summary(): String = "账本 " + ledgers + " · 账户 " + accounts + " · 分类 " + categories +
        " · 规则 " + rules + " · 预算 " + budgets + " · 周期任务 " + recurring +
        " · 账单 " + txns + (if (skipped > 0) " · 跳过已存在 " + skipped else "")
}

/**
 * 备份与恢复（需求十二/十三）。注意：
 * - 云同步不能替代独立备份，所以这里始终生成本地文件；
 * - API Key 默认不进入普通导出（需要用户在界面上明确勾选才包含）；
 * - 恢复按 id 合并：已存在的账单不会被覆盖，避免抹掉人工修改过的分类与备注。
 */
object Backup {

    const val FORMAT = "xzledger-backup"
    const val FORMAT_VERSION = 1

    private val EXPORTED_SETTINGS = listOf(
        SettingsKeys.APPEARANCE_MODE,
        SettingsKeys.APPEARANCE_SEED,
        SettingsKeys.FONT_SCALE,
        SettingsKeys.ANIMATIONS,
        SettingsKeys.SMOOTH_ANIMATIONS,
        SettingsKeys.GLASS,
        SettingsKeys.LANGUAGE,
        SettingsKeys.NICKNAME,
        SettingsKeys.BUDGET_ALERT,
        SettingsKeys.REMINDER_ENABLED,
        SettingsKeys.REMINDER_TIME
        // 刻意不导出 APP_LOCK_SECRET（应用密码）与 CLOUD_TOKEN（云同步令牌）
    )

    fun fileName(prefix: String, ext: String): String =
        prefix + "-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + "." + ext

    fun defaultJsonName(): String = fileName("catledger-backup", "json")
    fun defaultCsvName(): String = fileName("catledger-txns", "csv")

    // ---------------- 导出 ----------------

    fun exportJson(db: Db, ledgerId: String?, includeApiKeys: Boolean = false): String {
        val ledgers = db.ledgers().filter { ledgerId == null || it.id == ledgerId }
        val accounts = ArrayList<String>()
        val categories = ArrayList<String>()
        val rules = ArrayList<String>()
        val budgets = ArrayList<String>()
        val recurring = ArrayList<String>()
        val txns = ArrayList<String>()
        for (lg in ledgers) {
            for (a in db.accounts(lg.id)) accounts.add(accountJson(a))
            for (c in db.categories(lg.id)) categories.add(categoryJson(c))
            for (r in db.rules(lg.id, true)) rules.add(ruleJson(r))
            for (b in db.allBudgets(lg.id)) budgets.add(budgetJson(b))
            for (r in db.recurringTasks(lg.id)) recurring.add(recurringJson(r))
            for (t in db.txns(TxnFilter(ledgerId = lg.id, includeDeleted = true))) txns.add(txnJson(t))
        }
        val settings = ArrayList<String>()
        for (k in EXPORTED_SETTINGS) {
            val v = db.getSetting(k) ?: continue
            settings.add(Json.obj("key" to Json.str(k), "value" to Json.str(v)))
        }
        val parts = ArrayList<String>()
        parts.add("\"format\": " + Json.str(FORMAT))
        parts.add("\"format_version\": " + Json.num(FORMAT_VERSION.toLong()))
        parts.add("\"exported_at\": " + Json.num(System.currentTimeMillis()))
        parts.add("\"includes_api_keys\": " + Json.boolLit(includeApiKeys))
        parts.add("\"scope_ledger\": " + Json.str(ledgerId))
        parts.add("\"settings\": " + Json.arr(settings))
        parts.add("\"ledgers\": " + Json.arr(ledgers.map { ledgerJson(it) }))
        parts.add("\"accounts\": " + Json.arr(accounts))
        parts.add("\"categories\": " + Json.arr(categories))
        parts.add("\"rules\": " + Json.arr(rules))
        parts.add("\"budgets\": " + Json.arr(budgets))
        parts.add("\"recurring\": " + Json.arr(recurring))
        parts.add("\"txns\": " + Json.arr(txns))
        if (includeApiKeys) {
            parts.add("\"ai_configs\": " + Json.arr(db.aiConfigs().map { aiConfigJson(it) }))
        }
        return "{\n  " + parts.joinToString(",\n  ") + "\n}\n"
    }

    /** CSV 导出：只导出账单明细，分类/性质/账户用名称，方便用户自己核对。 */
    fun exportCsv(db: Db, ledgerId: String): String {
        val catNames = db.categories(ledgerId).associate { it.id to it.name }
        val accNames = db.accounts(ledgerId).associate { it.id to it.name }
        val sb = StringBuilder()
        sb.append("\uFEFF")
        sb.append("日期,时间,类型,金额(元),消费性质,分类,账户,商户,备注,交易单号,订单编码,平台,状态,来源,已删除\r\n")
        for (t in db.txns(TxnFilter(ledgerId = ledgerId, includeDeleted = true))) {
            val cells = listOf(
                t.occurredDate,
                Dates.timeOf(t.occurredAt),
                t.type.label,
                com.xzledger.app.util.Money.centsToPlain(t.amount),
                t.nature?.label ?: "",
                t.categoryId?.let { catNames[it] } ?: "",
                t.accountId?.let { accNames[it] } ?: "",
                if (t.merchantRaw.isNotBlank()) t.merchantRaw else t.merchant,
                t.note,
                t.tradeNo,
                t.orderNo,
                t.sourcePlatform,
                t.txStatus.label,
                t.source.label,
                if (t.deleted) "是" else "否"
            )
            sb.append(cells.joinToString(",") { csvCell(it) }).append("\r\n")
        }
        return sb.toString()
    }

    private fun csvCell(v: String): String {
        val needsQuote = v.contains(',') || v.contains('"') || v.contains('\n') || v.contains('\r')
        if (!needsQuote) return v
        return "\"" + v.replace("\"", "\"\"") + "\""
    }

    // ---------------- 单条序列化 ----------------

    private fun ledgerJson(x: Ledger): String = Json.obj(
        "id" to Json.str(x.id), "name" to Json.str(x.name), "owner_id" to Json.str(x.ownerId),
        "sort_order" to Json.num(x.sortOrder.toLong()), "is_default" to Json.boolLit(x.isDefault),
        "created_at" to Json.num(x.createdAt), "updated_at" to Json.num(x.updatedAt),
        "deleted" to Json.boolLit(x.deleted), "version" to Json.num(x.version.toLong())
    )

    private fun accountJson(x: Account): String = Json.obj(
        "id" to Json.str(x.id), "ledger_id" to Json.str(x.ledgerId), "name" to Json.str(x.name),
        "type" to Json.str(x.type.id), "icon" to Json.str(x.icon),
        "opening_balance" to Json.num(x.openingBalance), "currency" to Json.str(x.currency),
        "sort_order" to Json.num(x.sortOrder.toLong()), "archived" to Json.boolLit(x.archived),
        "created_at" to Json.num(x.createdAt), "updated_at" to Json.num(x.updatedAt),
        "deleted" to Json.boolLit(x.deleted), "version" to Json.num(x.version.toLong())
    )

    private fun categoryJson(x: Category): String = Json.obj(
        "id" to Json.str(x.id), "ledger_id" to Json.str(x.ledgerId), "name" to Json.str(x.name),
        "kind" to Json.str(x.kind), "icon" to Json.str(x.icon),
        "nature_default" to Json.str(x.natureDefault?.id), "parent_id" to Json.str(x.parentId),
        "sort_order" to Json.num(x.sortOrder.toLong()), "is_system" to Json.boolLit(x.isSystem),
        "created_at" to Json.num(x.createdAt), "updated_at" to Json.num(x.updatedAt),
        "deleted" to Json.boolLit(x.deleted), "version" to Json.num(x.version.toLong())
    )

    private fun ruleJson(x: Rule): String = Json.obj(
        "id" to Json.str(x.id), "ledger_id" to Json.str(x.ledgerId), "name" to Json.str(x.name),
        "enabled" to Json.boolLit(x.enabled), "priority" to Json.num(x.priority.toLong()),
        "match_type" to Json.str(x.matchType), "cond_merchant" to Json.str(x.condMerchant),
        "cond_keyword" to Json.str(x.condKeyword), "cond_account_id" to Json.str(x.condAccountId),
        "cond_direction" to Json.str(x.condDirection), "cond_source" to Json.str(x.condSource),
        "cond_min_amount" to (x.condMinAmount?.let { Json.num(it) } ?: "null"),
        "cond_max_amount" to (x.condMaxAmount?.let { Json.num(it) } ?: "null"),
        "set_category_id" to Json.str(x.setCategoryId), "set_nature" to Json.str(x.setNature?.id),
        "origin" to Json.str(x.origin), "hit_count" to Json.num(x.hitCount.toLong()),
        "created_at" to Json.num(x.createdAt), "updated_at" to Json.num(x.updatedAt),
        "deleted" to Json.boolLit(x.deleted)
    )

    private fun budgetJson(x: Budget): String = Json.obj(
        "id" to Json.str(x.id), "ledger_id" to Json.str(x.ledgerId), "month" to Json.str(x.month),
        "category_id" to Json.str(x.categoryId), "amount" to Json.num(x.amount),
        "created_at" to Json.num(x.createdAt), "updated_at" to Json.num(x.updatedAt),
        "deleted" to Json.boolLit(x.deleted)
    )

    private fun recurringJson(x: RecurringTask): String = Json.obj(
        "id" to Json.str(x.id), "ledger_id" to Json.str(x.ledgerId), "name" to Json.str(x.name),
        "type" to Json.str(x.type.id), "amount" to Json.num(x.amount),
        "category_id" to Json.str(x.categoryId), "account_id" to Json.str(x.accountId),
        "nature" to Json.str(x.nature?.id), "merchant" to Json.str(x.merchant), "note" to Json.str(x.note),
        "period" to Json.str(x.period), "day_of_month" to Json.num(x.dayOfMonth.toLong()),
        "next_due_at" to Json.num(x.nextDueAt), "mode" to Json.str(x.mode),
        "advance_days" to Json.num(x.advanceDays.toLong()), "enabled" to Json.boolLit(x.enabled),
        "last_run_at" to (x.lastRunAt?.let { Json.num(it) } ?: "null"),
        "last_run_key" to Json.str(x.lastRunKey),
        "created_at" to Json.num(x.createdAt), "updated_at" to Json.num(x.updatedAt),
        "deleted" to Json.boolLit(x.deleted)
    )

    private fun txnJson(x: Txn): String = Json.obj(
        "id" to Json.str(x.id), "ledger_id" to Json.str(x.ledgerId), "type" to Json.str(x.type.id),
        "amount" to Json.num(x.amount), "currency" to Json.str(x.currency),
        "nature" to Json.str(x.nature?.id), "category_id" to Json.str(x.categoryId),
        "account_id" to Json.str(x.accountId), "to_account_id" to Json.str(x.toAccountId),
        "title" to Json.str(x.title), "merchant" to Json.str(x.merchant), "merchant_raw" to Json.str(x.merchantRaw),
        "note" to Json.str(x.note), "occurred_date" to Json.str(x.occurredDate),
        "occurred_at" to Json.num(x.occurredAt), "created_at" to Json.num(x.createdAt),
        "updated_at" to Json.num(x.updatedAt), "source" to Json.str(x.source.id),
        "source_platform" to Json.str(x.sourcePlatform), "order_no" to Json.str(x.orderNo),
        "trade_no" to Json.str(x.tradeNo), "tx_status" to Json.str(x.txStatus.id),
        "refund_of" to Json.str(x.refundOf), "linked_txn_id" to Json.str(x.linkedTxnId),
        "dedup_key" to Json.str(x.dedupKey), "classify_basis" to Json.str(x.classifyBasis.id),
        "classify_rule_id" to Json.str(x.classifyRuleId),
        "image_paths" to Json.arr(x.imagePaths.map { Json.str(it) }),
        "tags" to Json.arr(x.tags.map { Json.str(it) }),
        "chat_msg_id" to Json.str(x.chatMsgId), "import_batch_id" to Json.str(x.importBatchId),
        "deleted" to Json.boolLit(x.deleted),
        "deleted_at" to (x.deletedAt?.let { Json.num(it) } ?: "null"),
        "version" to Json.num(x.version.toLong())
    )

    private fun aiConfigJson(x: AiConfig): String = Json.obj(
        "id" to Json.str(x.id), "name" to Json.str(x.name), "base_url" to Json.str(x.baseUrl),
        "api_key" to Json.str(x.apiKey), "model" to Json.str(x.model), "api_type" to Json.str(x.apiType),
        "supports_vision" to Json.boolLit(x.supportsVision), "supports_file" to Json.boolLit(x.supportsFile),
        "is_active" to Json.boolLit(x.isActive), "created_at" to Json.num(x.createdAt),
        "updated_at" to Json.num(x.updatedAt)
    )

    // ---------------- 恢复 ----------------

    fun restore(db: Db, text: String): RestoreResult {
        val root = try {
            Json.parse(text)
        } catch (e: Exception) {
            return RestoreResult(errors = listOf("备份文件不是合法 JSON：" + (e.message ?: "解析失败")))
        }
        val format = s(root, "format")
        if (format != FORMAT) {
            return RestoreResult(errors = listOf("这不是「猫猫记账」导出的备份文件（format=" + (if (format.isEmpty()) "缺失" else format) + "）"))
        }
        val errors = ArrayList<String>()
        var ledgers = 0
        var accounts = 0
        var categories = 0
        var rules = 0
        var budgets = 0
        var recurring = 0
        var txns = 0
        var skipped = 0

        for (item in Json.list(Json.get(root, "ledgers"))) {
            val id = s(item, "id")
            if (id.isEmpty()) continue
            db.upsertLedger(
                Ledger(
                    id = id, name = s(item, "name").ifEmpty { "恢复的账本" },
                    ownerId = s(item, "owner_id").ifEmpty { "local" },
                    sortOrder = n(item, "sort_order").toInt(),
                    isDefault = b(item, "is_default"),
                    createdAt = n(item, "created_at"), updatedAt = n(item, "updated_at"),
                    deleted = b(item, "deleted"), version = n(item, "version", 1L).toInt()
                )
            )
            ledgers++
        }
        for (item in Json.list(Json.get(root, "accounts"))) {
            val id = s(item, "id"); val lid = s(item, "ledger_id")
            if (id.isEmpty() || lid.isEmpty()) continue
            db.upsertAccount(
                Account(
                    id = id, ledgerId = lid, name = s(item, "name").ifEmpty { "账户" },
                    type = AccountType.of(s(item, "type")), icon = s(item, "icon"),
                    openingBalance = n(item, "opening_balance"), currency = s(item, "currency").ifEmpty { "CNY" },
                    sortOrder = n(item, "sort_order").toInt(), archived = b(item, "archived"),
                    createdAt = n(item, "created_at"), updatedAt = n(item, "updated_at"),
                    deleted = b(item, "deleted"), version = n(item, "version", 1L).toInt()
                )
            )
            accounts++
        }
        for (item in Json.list(Json.get(root, "categories"))) {
            val id = s(item, "id"); val lid = s(item, "ledger_id")
            if (id.isEmpty() || lid.isEmpty()) continue
            db.upsertCategory(
                Category(
                    id = id, ledgerId = lid, name = s(item, "name").ifEmpty { "分类" },
                    kind = s(item, "kind").ifEmpty { "expense" }, icon = s(item, "icon"),
                    natureDefault = Nature.of(s(item, "nature_default")), parentId = sn(item, "parent_id"),
                    sortOrder = n(item, "sort_order").toInt(), isSystem = b(item, "is_system"),
                    createdAt = n(item, "created_at"), updatedAt = n(item, "updated_at"),
                    deleted = b(item, "deleted"), version = n(item, "version", 1L).toInt()
                )
            )
            categories++
        }
        for (item in Json.list(Json.get(root, "rules"))) {
            val id = s(item, "id"); val lid = s(item, "ledger_id")
            if (id.isEmpty() || lid.isEmpty()) continue
            db.upsertRule(
                Rule(
                    id = id, ledgerId = lid, name = s(item, "name").ifEmpty { "个人规则" },
                    enabled = b(item, "enabled", true), priority = n(item, "priority", 100L).toInt(),
                    matchType = s(item, "match_type").ifEmpty { "merchant_exact" },
                    condMerchant = s(item, "cond_merchant"), condKeyword = s(item, "cond_keyword"),
                    condAccountId = sn(item, "cond_account_id"), condDirection = s(item, "cond_direction"),
                    condSource = s(item, "cond_source"),
                    condMinAmount = Json.long(Json.get(item, "cond_min_amount")),
                    condMaxAmount = Json.long(Json.get(item, "cond_max_amount")),
                    setCategoryId = sn(item, "set_category_id"), setNature = Nature.of(s(item, "set_nature")),
                    origin = s(item, "origin").ifEmpty { "user" }, hitCount = n(item, "hit_count").toInt(),
                    createdAt = n(item, "created_at"), updatedAt = n(item, "updated_at"),
                    deleted = b(item, "deleted")
                )
            )
            rules++
        }
        for (item in Json.list(Json.get(root, "budgets"))) {
            val id = s(item, "id"); val lid = s(item, "ledger_id")
            if (id.isEmpty() || lid.isEmpty()) continue
            db.upsertBudget(
                Budget(
                    id = id, ledgerId = lid, month = s(item, "month").ifEmpty { "ALL" },
                    categoryId = sn(item, "category_id"), amount = n(item, "amount"),
                    createdAt = n(item, "created_at"), updatedAt = n(item, "updated_at"),
                    deleted = b(item, "deleted")
                )
            )
            budgets++
        }
        for (item in Json.list(Json.get(root, "recurring"))) {
            val id = s(item, "id"); val lid = s(item, "ledger_id")
            if (id.isEmpty() || lid.isEmpty()) continue
            db.upsertRecurring(
                RecurringTask(
                    id = id, ledgerId = lid, name = s(item, "name").ifEmpty { "周期任务" },
                    type = TxnType.of(s(item, "type")), amount = n(item, "amount"),
                    categoryId = sn(item, "category_id"), accountId = sn(item, "account_id"),
                    nature = Nature.of(s(item, "nature")), merchant = s(item, "merchant"), note = s(item, "note"),
                    period = s(item, "period").ifEmpty { "monthly" },
                    dayOfMonth = n(item, "day_of_month", 1L).toInt(),
                    nextDueAt = n(item, "next_due_at"), mode = s(item, "mode").ifEmpty { "remind" },
                    advanceDays = n(item, "advance_days", 1L).toInt(), enabled = b(item, "enabled", true),
                    lastRunAt = Json.long(Json.get(item, "last_run_at")), lastRunKey = s(item, "last_run_key"),
                    createdAt = n(item, "created_at"), updatedAt = n(item, "updated_at"),
                    deleted = b(item, "deleted")
                )
            )
            recurring++
        }
        for (item in Json.list(Json.get(root, "txns"))) {
            val id = s(item, "id"); val lid = s(item, "ledger_id")
            if (id.isEmpty() || lid.isEmpty()) { skipped++; continue }
            if (db.txnById(id) != null) { skipped++; continue }
            val t = Txn(
                id = id, ledgerId = lid, type = TxnType.of(s(item, "type")), amount = n(item, "amount"),
                currency = s(item, "currency").ifEmpty { "CNY" }, nature = Nature.of(s(item, "nature")),
                categoryId = sn(item, "category_id"), accountId = sn(item, "account_id"),
                toAccountId = sn(item, "to_account_id"), title = s(item, "title"),
                merchant = s(item, "merchant"), merchantRaw = s(item, "merchant_raw"), note = s(item, "note"),
                occurredDate = s(item, "occurred_date"), occurredAt = n(item, "occurred_at"),
                createdAt = n(item, "created_at"), updatedAt = n(item, "updated_at"),
                source = TxnSource.of(s(item, "source")), sourcePlatform = s(item, "source_platform"),
                orderNo = s(item, "order_no"), tradeNo = s(item, "trade_no"),
                txStatus = TxnStatus.of(s(item, "tx_status")), refundOf = sn(item, "refund_of"),
                linkedTxnId = sn(item, "linked_txn_id"), dedupKey = s(item, "dedup_key"),
                classifyBasis = ClassifyBasis.of(s(item, "classify_basis")),
                classifyRuleId = sn(item, "classify_rule_id"),
                imagePaths = strList(item, "image_paths"), tags = strList(item, "tags"),
                chatMsgId = sn(item, "chat_msg_id"), importBatchId = sn(item, "import_batch_id"),
                deleted = b(item, "deleted"), deletedAt = Json.long(Json.get(item, "deleted_at")),
                version = n(item, "version", 1L).toInt()
            )
            val outcome = db.saveTxn(t)
            if (outcome.ok) txns++
            else {
                skipped++
                if (errors.size < 10) errors.add("账单 " + id + "：" + outcome.message)
            }
        }
        for (item in Json.list(Json.get(root, "settings"))) {
            val k = s(item, "key")
            if (k.isEmpty() || k == SettingsKeys.APP_LOCK_SECRET || k == SettingsKeys.CLOUD_TOKEN) continue
            db.setSetting(k, s(item, "value"))
        }
        for (item in Json.list(Json.get(root, "ai_configs"))) {
            val id = s(item, "id")
            if (id.isEmpty()) continue
            db.upsertAiConfig(
                AiConfig(
                    id = id, name = s(item, "name"), baseUrl = s(item, "base_url"), apiKey = s(item, "api_key"),
                    model = s(item, "model"), apiType = s(item, "api_type").ifEmpty { "openai" },
                    supportsVision = b(item, "supports_vision"), supportsFile = b(item, "supports_file"),
                    isActive = b(item, "is_active"), createdAt = n(item, "created_at"), updatedAt = n(item, "updated_at")
                )
            )
        }
        return RestoreResult(ledgers, accounts, categories, rules, budgets, recurring, txns, skipped, errors)
    }

    // ---------------- 解析小工具 ----------------

    private fun s(v: Any?, key: String): String = Json.str(Json.get(v, key)) ?: ""

    private fun sn(v: Any?, key: String): String? {
        val x = Json.str(Json.get(v, key))
        return if (x.isNullOrEmpty()) null else x
    }

    private fun n(v: Any?, key: String, def: Long = 0L): Long = Json.long(Json.get(v, key)) ?: def

    private fun b(v: Any?, key: String, def: Boolean = false): Boolean = Json.bool(Json.get(v, key)) ?: def

    private fun strList(v: Any?, key: String): List<String> =
        Json.list(Json.get(v, key)).mapNotNull { Json.str(it) }
}
