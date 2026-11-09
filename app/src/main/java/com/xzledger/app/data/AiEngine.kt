package com.xzledger.app.data

import com.xzledger.app.util.Dates
import com.xzledger.app.util.Money

/** 把解析出的 JSON 结构重新序列化，用于把 assistant 消息原样回传给接口。 */
internal fun jsonOf(v: Any?): String = when (v) {
    null -> "null"
    is String -> Json.str(v)
    is Boolean -> if (v) "true" else "false"
    is Long -> v.toString()
    is Int -> v.toString()
    is Double -> if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 9.0E15) v.toLong().toString() else v.toString()
    is Number -> v.toString()
    is Map<*, *> -> v.entries.joinToString(",", "{", "}") { jsonOf(it.key as? String ?: it.key.toString()) + ":" + jsonOf(it.value) }
    is List<*> -> v.joinToString(",", "[", "]") { jsonOf(it) }
    else -> Json.str(v.toString())
}

/**
 * AI 工具循环。所有金额、统计、去重判定都在程序内用真实数据库数据完成，
 * AI 只负责理解自然语言与解释结果，不能编造数字，也不能绕过权限。
 */
object AiEngine {

    private const val MAX_ROUNDS = 4
    private const val MAX_ROWS = 200

    suspend fun send(
        db: Db,
        ledger: Ledger,
        cfg: AiConfig,
        history: List<ChatMessage>,
        userText: String,
        attachments: List<String> = emptyList(),
        images: List<String> = emptyList(),
        allowRead: Boolean = false,
        allowWrite: Boolean = false,
        allowDelete: Boolean = false,
        autoPost: Boolean = false,
    ): AiResult {
        val notes = ArrayList<String>()
        val drafts = ArrayList<AiDraft>()
        val msgs = ArrayList<String>()
        msgs.add(msgOf("system", systemPrompt(db, ledger, allowRead, allowWrite, autoPost)))
        if (!allowRead && !allowWrite) {
            notes.add("未授权读取账本与新增账单：AI 只能做普通对话，不能查询数据或生成账单")
        }
        for (h in history.takeLast(20)) {
            if (h.role == "user" || h.role == "assistant") {
                if (h.content.isNotBlank()) msgs.add(msgOf(h.role, h.content))
            }
        }
        val text = if (attachments.isEmpty()) userText else userText + "\n[附件：" + attachments.joinToString("、") + "]"
        if (images.isNotEmpty() && !cfg.supportsVision) {
            return AiResult(
                text = "",
                drafts = emptyList(),
                error = "当前 AI 配置未开启「支持图片输入」，无法识别图片内容。请在「AI 配置」中确认模型能力并开启，或改用文字记账、文件导入。",
                toolNotes = notes
            )
        }
        val userContent = if (images.isEmpty()) Json.str(text) else {
            val parts = ArrayList<String>()
            parts.add(Json.obj("type" to Json.str("text"), "text" to Json.str(text)))
            for (img in images) {
                val url = imageDataUrl(img)
                if (url != null) parts.add(Json.obj("type" to Json.str("image_url"), "image_url" to Json.obj("url" to Json.str(url))))
            }
            Json.arr(parts)
        }
        msgs.add(Json.obj("role" to Json.str("user"), "content" to userContent))
        val tools = toolsJson(allowRead, allowWrite, allowDelete)
        var finalText = ""
        try {
            var round = 0
            while (round < MAX_ROUNDS) {
                round++
                val raw = AiClient.complete(cfg, msgs.joinToString(",", "[", "]"), tools)
                val v = Json.parseOrNull(raw)
                    ?: return result(drafts, notes, "AI 返回的内容不是合法 JSON，无法解析。")
                val apiError = Json.str(Json.get(v, "error"))
                if (apiError != null) return result(drafts, notes, "AI 接口报错：" + apiError)
                val msg = Json.get(Json.list(Json.get(v, "choices")).firstOrNull(), "message")
                    ?: return result(drafts, notes, "AI 未返回有效回复（choices 为空）。")
                val calls = Json.list(Json.get(msg, "tool_calls"))
                if (calls.isEmpty()) {
                    finalText = Json.str(Json.get(msg, "content")) ?: ""
                    break
                }
                msgs.add(jsonOf(msg))
                for (call in calls) {
                    val fn = Json.get(call, "function")
                    val name = Json.str(Json.get(fn, "name")) ?: ""
                    val argsText = Json.str(Json.get(fn, "arguments")) ?: "{}"
                    val callId = Json.str(Json.get(call, "id")) ?: ("call_" + System.currentTimeMillis())
                    val args = Json.parseOrNull(argsText)
                    notes.add(name)
                    val out = when (name) {
                        "query_ledger" -> if (!allowRead)
                            fake("用户没有授权读取账本，请告诉用户在「我的 - AI 权限管理」中开启后再试。")
                        else queryLedger(db, ledger.id, args)
                        "create_txn" -> if (!allowWrite)
                            fake("用户没有授权新增账单，请告诉用户在「我的 - AI 权限管理」中开启后再试。")
                        else createTxn(db, ledger.id, args, autoPost, drafts)
                        "delete_txn" -> if (!allowDelete)
                            fake("用户没有授权删除账单，无法删除。")
                        else deleteTxn(db, ledger.id, args)
                        else -> fake("不支持的工具：" + name)
                    }
                    msgs.add(Json.obj(
                        "role" to Json.str("tool"),
                        "tool_call_id" to Json.str(callId),
                        "content" to Json.str(out)
                    ))
                }
            }
            if (finalText.isBlank()) {
                val tail = AiClient.complete(cfg, msgs.joinToString(",", "[", "]"), null)
                finalText = contentOf(tail) ?: ""
            }
        } catch (e: Exception) {
            return result(drafts, notes, "AI 调用失败：" + (e.message ?: e.javaClass.simpleName))
        }
        if (finalText.isBlank()) finalText = defaultText(drafts)
        return AiResult(text = finalText, drafts = drafts.toList(), error = null, toolNotes = notes)
    }

    private fun result(drafts: List<AiDraft>, notes: List<String>, error: String) =
        AiResult(text = "", drafts = drafts.toList(), error = error, toolNotes = notes)

    private fun defaultText(drafts: List<AiDraft>): String {
        if (drafts.isEmpty()) return "我没有从这句话里识别出账单，可以补充金额、时间或商户再说一次。"
        val saved = drafts.count { it.state == "saved" }
        val confirm = drafts.count { it.state == "confirm" }
        if (saved > 0) return "已入账 " + saved + " 笔，可在明细中查看或撤销。"
        if (confirm > 0) return "已生成 " + confirm + " 笔待确认账单，请核对后点击「确认入账」。"
        return "处理完成。"
    }

    private fun fake(error: String) = Json.obj("ok" to "false", "error" to Json.str(error))

    private fun msgOf(role: String, content: String) =
        Json.obj("role" to Json.str(role), "content" to Json.str(content))

    private fun contentOf(raw: String): String? {
        val v = Json.parseOrNull(raw) ?: return null
        return Json.str(Json.get(Json.list(Json.get(v, "choices")).firstOrNull()?.let { Json.get(it, "message") }, "content"))
    }

    private fun systemPrompt(db: Db, ledger: Ledger, allowRead: Boolean, allowWrite: Boolean, autoPost: Boolean): String {
        val sb = StringBuilder()
        sb.append("你是「猫猫记账」内置的记账助手，服务大学生日常收支。\n")
        sb.append("今天日期：").append(Dates.today()).append("；当前账本：").append(ledger.name).append("。\n")
        sb.append("硬性规则：\n")
        sb.append("1. 一切金额与统计只能来自工具返回的真实结果，不得编造任何数字；工具失败要如实说明失败。\n")
        sb.append("2. 用户描述消费时调用 create_txn；缺少金额等必要信息必须先追问，绝不凭空补全。\n")
        sb.append("3. 一句话包含多笔消费时，分别多次调用 create_txn。\n")
        sb.append("4. 消费性质与消费分类是两个独立字段，分长期消费（永久／半永久，如电子产品、家具）、即时消费（即时／临时，如餐饮、交通）、周期消费（按月／按周期固定发生，如房租、话费、会员订阅、AI 会员充值）三类。\n")
        sb.append("5. 不确定、疑似重复或规则冲突时，不要尝试绕过，交给用户确认。\n")
        sb.append("5.1 如果用户发来小票、支付截图或账单图片，请提取金额、日期时间、商户、支付账户、订单编码与交易单号（按字符串原样保留前导零），再调用 create_txn。\n")
        if (allowWrite && autoPost) {
            sb.append("6. 用户已开启快捷自动入账：信息完整的记录工具会直接保存，请在回复中说明结果并提示可撤销。\n")
        } else if (allowWrite) {
            sb.append("6. 用户未开启自动入账：create_txn 只生成待确认记录，请提示用户在界面上核对确认。\n")
        } else {
            sb.append("6. 用户未授权新增账单：不要承诺已经记账，可提示去「AI 权限管理」开启。\n")
        }
        if (!allowRead) sb.append("7. 用户未授权读取账本：不能调用查询工具，涉及金额统计的问题要说明需要先授权。\n")
        val cats = db.categories(ledger.id)
        if (cats.isNotEmpty()) {
            sb.append("可用分类：").append(cats.joinToString("、") { it.name }).append("。\n")
        }
        val accs = db.accounts(ledger.id)
        if (accs.isNotEmpty()) {
            sb.append("可用账户：").append(accs.joinToString("、") { it.name }).append("。\n")
        }
        sb.append("请用简体中文回答，语气简洁友好，不要输出 Markdown 表格。")
        return sb.toString()
    }

    private fun toolsJson(allowRead: Boolean, allowWrite: Boolean, allowDelete: Boolean): String {
        val list = ArrayList<String>()
        if (allowRead) {
            list.add(fn(
                "query_ledger",
                "查询当前账本的真实账单与统计。金额合计由程序计算，返回的数字可直接引用。",
                Json.obj(
                    "type" to Json.str("object"),
                    "properties" to Json.obj(
                        "start_date" to param("string", "开始日期，格式 yyyy-MM-dd"),
                        "end_date" to param("string", "结束日期，格式 yyyy-MM-dd"),
                        "keyword" to param("string", "可选：商户或备注关键词"),
                        "group_by" to enumParam(listOf("summary", "category", "nature", "merchant", "list"), "统计方式")
                    )
                )
            ))
        }
        if (allowWrite) {
            list.add(fn(
                "create_txn",
                "把一笔消费或收入生成账单。金额单位是元，必须由用户明确说出，不能猜测。",
                Json.obj(
                    "type" to Json.str("object"),
                    "properties" to Json.obj(
                        "amount_yuan" to param("number", "金额，单位元，例如 18.5"),
                        "date" to param("string", "消费日期 yyyy-MM-dd，默认今天"),
                        "time" to param("string", "时间 HH:mm，默认 12:00"),
                        "type" to enumParam(listOf("expense", "income", "transfer", "refund"), "收支方向，默认支出"),
                        "category" to param("string", "消费分类名称，必须来自可用分类列表"),
                        "nature" to enumParam(listOf("permanent", "instant", "periodic"), "消费性质：长期、即时或周期"),
                        "account" to param("string", "支付账户名称，如 微信、支付宝、现金"),
                        "merchant" to param("string", "商户或交易对象"),
                        "note" to param("string", "备注"),
                        "trade_no" to param("string", "交易单号，有则填写，用于去重"),
                        "order_no" to param("string", "订单编码，按字符串原样填写，保留前导零"),
                        "platform" to param("string", "来源平台，如 微信、支付宝")
                    ),
                    "required" to Json.arr(listOf(Json.str("amount_yuan")))
                )
            ))
        }
        if (allowDelete) {
            list.add(fn(
                "delete_txn",
                "按账单 id 删除一笔账单（软删除，可恢复）。仅在用户明确要求删除时调用。",
                Json.obj(
                    "type" to Json.str("object"),
                    "properties" to Json.obj("id" to param("string", "账单 id")),
                    "required" to Json.arr(listOf(Json.str("id")))
                )
            ))
        }
        if (list.isEmpty()) return ""
        return Json.arr(list)
    }

    private fun fn(name: String, desc: String, params: String): String = Json.obj(
        "type" to Json.str("function"),
        "function" to Json.obj(
            "name" to Json.str(name),
            "description" to Json.str(desc),
            "parameters" to params
        )
    )

    private fun param(type: String, desc: String): String =
        Json.obj("type" to Json.str(type), "description" to Json.str(desc))

    private fun enumParam(values: List<String>, desc: String): String = Json.obj(
        "type" to Json.str("string"),
        "description" to Json.str(desc),
        "enum" to Json.arr(values.map { Json.str(it) })
    )

    // ---------------------------------------------------------------- 工具实现

    private fun queryLedger(db: Db, ledgerId: String, args: Any?): String {
        val start = validDay(Json.str(Json.get(args, "start_date"))) ?: Dates.today()
        val end = validDay(Json.str(Json.get(args, "end_date"))) ?: start
        val lo = if (start <= end) start else end
        val hi = if (start <= end) end else start
        val keyword = (Json.str(Json.get(args, "keyword")) ?: "").trim()
        val groupBy = Json.str(Json.get(args, "group_by")) ?: "summary"
        val s = db.summarize(ledgerId, lo, hi)
        val cats = db.categories(ledgerId).associateBy { it.id }
        val accs = db.accounts(ledgerId).associateBy { it.id }
        val txns = db.txns(TxnFilter(
            ledgerId = ledgerId,
            keyword = keyword,
            startDate = lo,
            endDate = hi,
            limit = MAX_ROWS
        )).filter { it.affectsIncomeExpense }

        val summary = Json.obj(
            "ok" to "true",
            "range" to Json.str(lo + " 至 " + hi),
            "income_cents" to s.income.toString(),
            "expense_cents" to s.expense.toString(),
            "refund_cents" to s.refund.toString(),
            "net_expense_cents" to (s.expense - s.refund).toString(),
            "balance_cents" to (s.income - s.expense + s.refund).toString(),
            "count" to s.count.toString(),
            "note" to Json.str("金额单位是分，1 元 = 100 分；退款按退款发生月抵减支出，转账不计入收支。")
        )

        val parts = ArrayList<String>()
        parts.add(Json.obj("summary" to summary))
        if (groupBy == "category") parts.add("{\"by_category\":{" + categoryRows(txns, cats) + "}}")
        if (groupBy == "nature") parts.add("{\"by_nature\":{" + natureRows(txns) + "}}")
        if (groupBy == "merchant") parts.add("{\"by_merchant\":{" + merchantRows(txns) + "}}")
        if (groupBy == "list" || txns.size <= 20) parts.add(Json.obj("rows" to Json.arr(txns.take(60).map { row(it, cats, accs) })))
        return "{" + parts.joinToString(",") + "}"
    }

    private fun categoryRows(txns: List<Txn>, cats: Map<String, Category>): String {
        val m = HashMap<String, Long>()
        for (t in txns) {
            val k = t.categoryId ?: "uncategorized"
            val d = when (t.type) {
                TxnType.EXPENSE -> t.amount
                TxnType.REFUND -> -t.amount
                TxnType.INCOME -> -t.amount
                else -> 0L
            }
            m[k] = (m[k] ?: 0L) + d
        }
        return m.entries.sortedByDescending { it.value }.take(20).joinToString(",") { e ->
            Json.str(cats[e.key]?.name ?: "未分类") + ":" + e.value
        }
    }

    private fun natureRows(txns: List<Txn>): String {
        val m = HashMap<String, Long>()
        for (t in txns) {
            if (t.type != TxnType.EXPENSE && t.type != TxnType.REFUND) continue
            val k = t.nature?.label ?: "未填写性质"
            val d = if (t.type == TxnType.REFUND) -t.amount else t.amount
            m[k] = (m[k] ?: 0L) + d
        }
        return m.entries.sortedByDescending { it.value }.joinToString(",") { Json.str(it.key) + ":" + it.value }
    }

    private fun merchantRows(txns: List<Txn>): String {
        val m = HashMap<String, Long>()
        for (t in txns) {
            if (t.type != TxnType.EXPENSE && t.type != TxnType.REFUND) continue
            val raw = if (t.merchantRaw.isNotEmpty()) t.merchantRaw else t.merchant
            val k = MerchantNormalizer.displayName(raw)
            if (k.isEmpty()) continue
            val d = if (t.type == TxnType.REFUND) -t.amount else t.amount
            m[k] = (m[k] ?: 0L) + d
        }
        return m.entries.sortedByDescending { it.value }.take(15).joinToString(",") { Json.str(it.key) + ":" + it.value }
    }

    private fun row(t: Txn, cats: Map<String, Category>, accs: Map<String, Account>): String = Json.obj(
        "id" to Json.str(t.id),
        "date" to Json.str(t.occurredDate),
        "time" to Json.str(Dates.timeOf(t.occurredAt)),
        "type" to Json.str(t.type.label),
        "amount_cents" to t.amount.toString(),
        "category" to Json.str(t.categoryId?.let { cats[it]?.name } ?: "未分类"),
        "nature" to Json.str(t.nature?.label ?: ""),
        "account" to Json.str(t.accountId?.let { accs[it]?.name } ?: ""),
        "merchant" to Json.str(if (t.merchantRaw.isNotEmpty()) t.merchantRaw else t.merchant),
        "note" to Json.str(t.note)
    )

    private fun createTxn(db: Db, ledgerId: String, args: Any?, autoPost: Boolean, drafts: MutableList<AiDraft>): String {
        val yuan = numOf(Json.get(args, "amount_yuan"))
        val centsDirect = Json.long(Json.get(args, "amount_cents"))
        val cents = when {
            centsDirect != null && centsDirect > 0 -> centsDirect
            yuan != null && yuan > 0.0 -> Math.round(yuan * 100.0)
            else -> 0L
        }
        if (cents <= 0L) {
            return fake("缺少有效金额，请在回复中向用户询问金额，不要猜测，也不要在缺少金额时创建账单。")
        }
        val day = validDay(Json.str(Json.get(args, "date"))) ?: Dates.today()
        val time = parseTime(Json.str(Json.get(args, "time")))
        val type = TxnType.of(Json.str(Json.get(args, "type")))
        val merchantRaw = (Json.str(Json.get(args, "merchant")) ?: "").trim()
        val note = (Json.str(Json.get(args, "note")) ?: "").trim()
        val tradeNo = (Json.str(Json.get(args, "trade_no")) ?: "").trim()
        val orderNo = (Json.str(Json.get(args, "order_no")) ?: "").trim()
        val accountName = (Json.str(Json.get(args, "account")) ?: "").trim()
        val account = matchAccount(db, ledgerId, accountName)
        val platform = pickPlatform(Json.str(Json.get(args, "platform")), account?.name ?: accountName)

        var categoryId: String? = null
        var categoryName = ""
        var nature = Nature.of(Json.str(Json.get(args, "nature")))
        var basis = ClassifyBasis.AI
        var reason = "AI 依据用户描述判断"
        val wanted = (Json.str(Json.get(args, "category")) ?: "").trim()
        if (wanted.isNotEmpty()) {
            val c = matchCategory(db, ledgerId, wanted)
            if (c != null) {
                categoryId = c.id
                categoryName = c.name
            }
        }
        if (categoryId == null || nature == null) {
            val s = Classifier.suggest(db, ledgerId, merchantRaw, note, account?.id, type, TxnSource.AI_CHAT, cents)
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
        val draft = AiDraft(
            amount = cents,
            date = day,
            time = pad2(time.first) + ":" + pad2(time.second),
            type = type,
            accountId = account?.id,
            accountName = account?.name ?: accountName,
            categoryId = categoryId,
            categoryName = categoryName,
            nature = nature,
            merchant = MerchantNormalizer.displayName(merchantRaw),
            note = note,
            tradeNo = tradeNo,
            platform = platform,
            basis = basis,
            basisReason = reason,
        )
        val filled = draft.copy(
            amount = cents,
            categoryId = categoryId,
            nature = if (type == TxnType.EXPENSE) nature else null
        ).let { it.copy() }
        val txn = draftToTxn(db, ledgerId, filled)

        val byKey = if (txn.dedupKey.isNotEmpty()) db.findByDedupKey(ledgerId, txn.dedupKey) else null
        if (byKey != null) {
            val dup = filled.copy(state = "duplicate", message = "同一交易单号的记录已存在，已跳过，未重复创建。")
            drafts.add(dup)
            saveDraftRecord(db, ledgerId, dup)
            return Json.obj(
                "ok" to "false",
                "duplicate" to "true",
                "error" to Json.str("该交易单号在本账本已存在（" + byKey.occurredDate + " " + MerchantNormalizer.displayName(byKey.merchantRaw.ifEmpty { byKey.merchant }) + "），未重复创建。请告知用户已跳过。")
            )
        }
        val suspected = db.findSuspectedDuplicates(txn)
        if (suspected.isNotEmpty()) {
            val msg = "疑似重复：" + suspected.take(3).joinToString("；") { it.occurredDate + " " + MerchantNormalizer.displayName(if (it.merchantRaw.isNotEmpty()) it.merchantRaw else it.merchant) + " " + Money.centsToPlain(it.amount) + "元" }
            val d = filled.copy(state = "confirm", message = msg + "。已生成待确认记录，请用户核对。")
            drafts.add(d)
            db.addChatMessage(chatCard(db, ledgerId, d))
            return Json.obj("ok" to "false", "needs_confirm" to "true", "reason" to Json.str(msg), "error" to Json.str(msg))
        }
        if (autoPost && cents > 0) {
            val outcome = db.saveTxn(txn)
            val d = if (outcome.ok) {
                filled.copy(state = "saved", savedTxnId = txn.id, message = "已入账")
            } else {
                filled.copy(
                    state = if (outcome.duplicateOf != null) "duplicate" else "fail",
                    message = outcome.message
                )
            }
            drafts.add(d)
            db.addChatMessage(chatCard(db, ledgerId, d))
            val tx = if (outcome.ok) db.findByDedupKey(ledgerId, txn.dedupKey) else null
            return if (outcome.ok) {
                Json.obj(
                    "ok" to "true",
                    "saved" to "true",
                    "txn_id" to Json.str(tx?.id ?: txn.id),
                    "message" to Json.str("已保存到数据库：" + day + " " + filled.categoryName + " " + Money.centsToPlain(cents) + "元")
                )
            } else {
                Json.obj("ok" to "false", "error" to Json.str("保存失败：" + outcome.message))
            }
        }
        drafts.add(filled.copy(state = "confirm", message = "待确认"))
        db.addChatMessage(chatCard(db, ledgerId, filled))
        return Json.obj(
            "ok" to "true",
            "pending" to "true",
            "message" to Json.str("已生成待确认账单（未写入数据库，需要用户在界面上点确认入账）。")
        )
    }

    /** 把待确认草稿真正写入数据库（用户点击「确认入账」时调用）。 */
    fun saveDraft(db: Db, ledgerId: String, d: AiDraft): SaveOutcome {
        val t = draftToTxn(db, ledgerId, d)
        val outcome = db.saveTxn(t)
        saveDraftRecord(db, ledgerId, d.copy(
            state = if (outcome.ok) "saved" else if (outcome.duplicateOf != null) "duplicate" else "fail",
            message = if (outcome.ok) "已入账" else outcome.message,
            savedTxnId = if (outcome.ok) t.id else null
        ))
        return outcome
    }

    /** 图片转 data URL，供支持视觉输入的模型使用。单张超过 4MB 直接拒绝，避免流量与费用失控。 */
    private fun imageDataUrl(path: String): String? {
        return try {
            val f = java.io.File(path)
            if (!f.exists() || f.length() <= 0L || f.length() > 4L * 1024L * 1024L) return null
            val ext = f.name.substringAfterLast('.', "jpg").lowercase()
            val mime = when (ext) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                else -> "image/jpeg"
            }
            val b64 = android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
            "data:" + mime + ";base64," + b64
        } catch (e: Exception) {
            null
        }
    }

    fun saveDraftRecord(db: Db, ledgerId: String, d: AiDraft) {
        val id = "aidraft:" + d.id
        val old = db.chatMessages(ledgerId, 200).firstOrNull { it.id == id }
        val msg = ChatMessage(
            id = id,
            ledgerId = ledgerId,
            role = "tool",
            content = draftSummary(d),
            status = when (d.state) {
                "saved" -> "ok"
                "fail", "duplicate" -> "error"
                else -> "pending"
            },
            error = if (d.state == "fail" || d.state == "duplicate") d.message else null,
            txnIds = listOfNotNull(d.savedTxnId),
            cardsJson = draftJson(d)
        )
        if (old == null) db.addChatMessage(msg) else db.updateChatMessage(msg)
    }

    private fun chatCard(db: Db, ledgerId: String, d: AiDraft): ChatMessage = ChatMessage(
        id = "aidraft:" + d.id,
        ledgerId = ledgerId,
        role = "tool",
        content = draftSummary(d),
        status = when (d.state) {
            "saved" -> "ok"
            "fail", "duplicate" -> "error"
            else -> "pending"
        },
        error = if (d.state == "fail" || d.state == "duplicate") d.message else null,
        txnIds = listOfNotNull(d.savedTxnId),
        cardsJson = draftJson(d)
    )

    fun draftSummary(d: AiDraft): String = d.date + " " + d.time + " " + d.type.label + " " +
        Money.centsToPlain(d.amount) + " 元" +
        (if (d.categoryName.isNotEmpty()) " · " + d.categoryName else "") +
        (if (d.nature != null) " · " + d.nature.label else "") +
        (if (d.accountName.isNotEmpty()) " · " + d.accountName else "") +
        (if (d.merchant.isNotEmpty()) " · " + d.merchant else "")

    fun draftJson(d: AiDraft): String = Json.obj(
        "id" to Json.str(d.id),
        "amount" to d.amount.toString(),
        "date" to Json.str(d.date),
        "time" to Json.str(d.time),
        "type" to Json.str(d.type.id),
        "accountId" to Json.str(d.accountId),
        "accountName" to Json.str(d.accountName),
        "categoryId" to Json.str(d.categoryId),
        "categoryName" to Json.str(d.categoryName),
        "nature" to Json.str(d.nature?.id),
        "merchant" to Json.str(d.merchant),
        "note" to Json.str(d.note),
        "tradeNo" to Json.str(d.tradeNo),
        "platform" to Json.str(d.platform),
        "basis" to Json.str(d.basis.id),
        "basisReason" to Json.str(d.basisReason),
        "state" to Json.str(d.state),
        "message" to Json.str(d.message),
        "savedTxnId" to Json.str(d.savedTxnId)
    )

    fun draftFromJson(text: String): AiDraft? {
        val v = Json.parseOrNull(text) ?: return null
        if (Json.get(v, "amount") == null) return null
        return AiDraft(
            id = Json.str(Json.get(v, "id")) ?: Ids.newId(),
            amount = Json.long(Json.get(v, "amount")) ?: 0L,
            date = Json.str(Json.get(v, "date")) ?: Dates.today(),
            time = Json.str(Json.get(v, "time")) ?: "12:00",
            type = TxnType.of(Json.str(Json.get(v, "type"))),
            accountId = Json.str(Json.get(v, "accountId")),
            accountName = Json.str(Json.get(v, "accountName")) ?: "",
            categoryId = Json.str(Json.get(v, "categoryId")),
            categoryName = Json.str(Json.get(v, "categoryName")) ?: "",
            nature = Nature.of(Json.str(Json.get(v, "nature"))),
            merchant = Json.str(Json.get(v, "merchant")) ?: "",
            note = Json.str(Json.get(v, "note")) ?: "",
            tradeNo = Json.str(Json.get(v, "tradeNo")) ?: "",
            platform = Json.str(Json.get(v, "platform")) ?: "",
            basis = ClassifyBasis.of(Json.str(Json.get(v, "basis"))),
            basisReason = Json.str(Json.get(v, "basisReason")) ?: "",
            state = Json.str(Json.get(v, "state")) ?: "confirm",
            message = Json.str(Json.get(v, "message")) ?: "",
            savedTxnId = Json.str(Json.get(v, "savedTxnId"))
        )
    }

    fun draftToTxn(db: Db, ledgerId: String, d: AiDraft): Txn {
        val t = parseTime(d.time)
        val at = Dates.toMillis(
            d.date.substring(0, 4).toIntOrNull() ?: 2024,
            (d.date.substring(5, 7).toIntOrNull() ?: 1) - 1,
            d.date.substring(8, 10).toIntOrNull() ?: 1,
            t.first, t.second
        )
        val raw = d.merchant
        return Txn(
            id = if (d.savedTxnId != null) d.savedTxnId!! else Ids.newId(),
            ledgerId = ledgerId,
            type = d.type,
            amount = d.amount,
            nature = if (d.type == TxnType.EXPENSE) d.nature else null,
            categoryId = d.categoryId,
            accountId = d.accountId,
            title = if (raw.isNotEmpty()) raw else d.note,
            merchant = MerchantNormalizer.normalize(raw),
            merchantRaw = raw,
            note = d.note,
            occurredDate = d.date,
            occurredAt = at,
            source = d.source,
            sourcePlatform = d.platform,
            orderNo = "",
            tradeNo = d.tradeNo,
            txStatus = TxnStatus.SUCCESS,
            dedupKey = DedupKeys.build(d.platform, d.tradeNo, "", d.type == TxnType.REFUND, d.date, d.amount),
            classifyBasis = if (d.categoryId != null) d.basis else ClassifyBasis.DEFAULT,
            classifyRuleId = null
        )
    }

    private fun deleteTxn(db: Db, ledgerId: String, args: Any?): String {
        val id = (Json.str(Json.get(args, "id")) ?: "").trim()
        if (id.isEmpty()) return fake("缺少账单 id。")
        val t = db.txnById(id)
        if (t == null || t.ledgerId != ledgerId) return fake("未找到该账单（可能不属于当前账本）。")
        db.softDeleteTxn(id)
        return Json.obj(
            "ok" to "true",
            "message" to Json.str("已删除并移入回收站：" + t.occurredDate + " " + Money.centsToPlain(t.amount) + "元，可在回收站恢复。")
        )
    }

    // ---------------------------------------------------------------- 小工具

    private fun numOf(v: Any?): Double? = when (v) {
        is Double -> v
        is Long -> v.toDouble()
        is Int -> v.toDouble()
        is Number -> v.toDouble()
        is String -> v.trim().replace(",", "").toDoubleOrNull()
        else -> null
    }

    private fun pad2(n: Int): String = if (n < 10) "0" + n else "" + n

    private fun validDay(s: String?): String? {
        val v = (s ?: "").trim()
        if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(v)) return null
        return Dates.parseDay(v)?.let { v }
    }

    private fun parseTime(s: String?): Pair<Int, Int> {
        val v = (s ?: "").trim()
        if (v.isEmpty()) return 12 to 0
        val m = Regex("(\\d{1,2})\\s*[:：]\\s*(\\d{1,2})").find(v) ?: return 12 to 0
        val h = m.groupValues[1].toIntOrNull() ?: 12
        val mi = m.groupValues[2].toIntOrNull() ?: 0
        return h.coerceIn(0, 23) to mi.coerceIn(0, 59)
    }

    private fun pickPlatform(explicit: String?, accountName: String): String {
        val e = (explicit ?: "").trim()
        if (e.isNotEmpty()) return e
        val a = accountName
        return when {
            a.contains("支付宝") -> "支付宝"
            a.contains("微信") -> "微信"
            a.contains("花呗") -> "花呗"
            else -> a
        }
    }

    fun matchAccount(db: Db, ledgerId: String, name: String): Account? {
        if (name.isBlank()) return null
        val accs = db.accounts(ledgerId)
        val n = name.trim()
        return accs.firstOrNull { it.name == n }
            ?: accs.firstOrNull { it.name.contains(n) || n.contains(it.name) }
            ?: accs.firstOrNull { it.name.contains(n.replace("支付", "")) && n.replace("支付", "").isNotEmpty() }
    }

    fun matchCategory(db: Db, ledgerId: String, name: String): Category? {
        if (name.isBlank()) return null
        val cats = db.categories(ledgerId)
        val n = name.trim()
        return cats.firstOrNull { it.name == n }
            ?: cats.firstOrNull { it.name.contains(n) }
            ?: cats.firstOrNull { n.contains(it.name) }
    }

    /** 在预览界面按用户指定分类/性质批量修正草稿用。 */
    fun applyToDrafts(drafts: List<AiDraft>, categoryId: String?, categoryName: String?, nature: Nature?): List<AiDraft> =
        drafts.map { d ->
            d.copy(
                categoryId = categoryId ?: d.categoryId,
                categoryName = categoryName ?: d.categoryName,
                nature = if (d.type == TxnType.EXPENSE) (nature ?: d.nature) else null,
                basis = if (categoryId != null) ClassifyBasis.MANUAL else d.basis,
                basisReason = if (categoryId != null) "用户手动指定" else d.basisReason
            )
        }
}
