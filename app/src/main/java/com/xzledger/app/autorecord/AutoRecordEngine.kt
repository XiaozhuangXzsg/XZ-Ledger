package com.xzledger.app.autorecord

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.xzledger.app.XzApp
import com.xzledger.app.data.Account
import com.xzledger.app.data.AccountType
import com.xzledger.app.data.ClassifyBasis
import com.xzledger.app.data.Classifier
import com.xzledger.app.data.Db
import com.xzledger.app.data.Ids
import com.xzledger.app.data.Nature
import com.xzledger.app.data.NoticeLog
import com.xzledger.app.data.PendingTxn
import com.xzledger.app.data.SettingsKeys
import com.xzledger.app.data.Store
import com.xzledger.app.data.Txn
import com.xzledger.app.data.TxnSource
import com.xzledger.app.data.TxnStatus
import com.xzledger.app.data.TxnType
import com.xzledger.app.data.accountLink
import com.xzledger.app.data.addNoticeLog
import com.xzledger.app.data.findByAnyNo
import com.xzledger.app.data.findByDedupKey
import com.xzledger.app.data.insertPending
import com.xzledger.app.data.pendingByFingerprint
import com.xzledger.app.data.saveTxn
import com.xzledger.app.data.similarTxns
import com.xzledger.app.data.trimNoticeLog
import com.xzledger.app.util.Money
import java.util.concurrent.Executors

/**
 * 通知自动记账引擎。
 *
 * 回调只做三件事：判断是否启用、把解析放到后台线程、结束。所有数据库写入都在单线程
 * 执行器里完成，避免在 NotificationListenerService 的主线程回调上做重活。
 *
 * 安全边界：
 *  - 通知原文不落库、不进日志，日志只记录结构化摘要（金额/类别/动作）；
 *  - 解析在本地完成，默认不上传任何文本；
 *  - 低置信度、未匹配账户、疑似重复、退款/转账/还款一律只进待确认，绝不自动入账。
 */
object AutoRecordEngine {

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "notify-bookkeeping").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val main = Handler(Looper.getMainLooper())

    /** 监听服务把通知投递进来；立即返回，不阻塞系统回调。 */
    fun submit(context: Context, input: NoticeInput) {
        val app = context.applicationContext
        worker.execute {
            try {
                handle(app, input)
            } catch (t: Throwable) {
                // 兜底：任何异常都不能让通知监听崩溃或被系统认为无响应。
                logFailure(app, input, t)
            }
        }
    }

    private fun handle(context: Context, input: NoticeInput) {
        val db = appDb() ?: return
        val store = appStore() ?: return
        if (!NotifyPrefs.enabled(store)) return
        val mode = NotifyPrefs.mode(store)
        if (mode == NotifyPrefs.MODE_OFF) return
        if (!NotifyPrefs.packages(store).contains(input.pkg)) return
        val ledgerId = currentLedgerId(db, store)
        if (ledgerId.isEmpty()) return

        val parsed = TxnParser.parse(input)
        if (!parsed.usable) {
            addLog(db, ledgerId, input, parsed, "filtered", parsed.reason.ifEmpty { "不是可入账的交易通知" }, 0L)
            return
        }
        val type = parsed.type
        if (type == null) {
            addLog(db, ledgerId, input, parsed, "filtered", "无法判断收入还是支出", parsed.amountCents)
            return
        }

        val fingerprint = NoticeDedup.fingerprint(
            input.pkg, type, parsed.amountCents, parsed.occurredAt, parsed.merchant, parsed.serial
        )
        val dedupKey = NoticeDedup.txnDedupKey(fingerprint)

        // 1) 已经作为账单存在（重启重放、同一条通知再次投递）
        val existing = db.findByDedupKey(ledgerId, dedupKey)
        if (existing != null) {
            addLog(db, ledgerId, input, parsed, "duplicate", "已入账，跳过", parsed.amountCents)
            return
        }
        // 2) 流水号已存在（同一笔交易换了文案）
        if (parsed.serial.isNotEmpty()) {
            val byNo = db.findByAnyNo(ledgerId, parsed.serial, parsed.serial)
            if (byNo != null) {
                addLog(db, ledgerId, input, parsed, "duplicate", "流水号已存在，跳过", parsed.amountCents)
                return
            }
        }
        // 3) 待确认里已有同一指纹
        val pendingDup = db.pendingByFingerprint(ledgerId, fingerprint)
        if (pendingDup != null) {
            addLog(db, ledgerId, input, parsed, "duplicate", "已在待确认列表，跳过", parsed.amountCents)
            return
        }

        val accounts = db.accounts(ledgerId).filter { !it.archived }
        val account = matchAccount(db, ledgerId, accounts, input.pkg, parsed)

        // 4) 跨来源疑似重复（银行 App + 支付宝/微信同时通知同一笔）
        val suspect = db.similarTxns(ledgerId, type, parsed.amountCents, parsed.occurredAt)
            .firstOrNull {
                NoticeDedup.looksLikeCrossSourceDuplicate(
                    input.pkg, type, parsed.amountCents, parsed.occurredAt, parsed.serial,
                    it.sourcePlatform, it.type, it.amount, it.occurredAt, ""
                )
            }

        val suggestion = Classifier.suggest(
            db = db, ledgerId = ledgerId, merchantRaw = parsed.merchant, note = "",
            accountId = account?.id, type = type, source = TxnSource.NOTIFY, amount = parsed.amountCents
        )

        val blockReason = blockReason(mode, store, input.pkg, parsed, type, account, suspect)
        if (blockReason == null) {
            val txn = buildTxn(db, ledgerId, input, parsed, type, account!!, suggestion.categoryId, suggestion.nature, suggestion.basis, suggestion.ruleId, dedupKey)
            val outcome = db.saveTxn(txn)
            if (outcome.ok) {
                addLog(db, ledgerId, input, parsed, "auto", "已自动记账", parsed.amountCents)
                db.trimNoticeLog(ledgerId)
                notifyUser("已自动记账：" + Money.centsToPlain(parsed.amountCents) + " " + txn.title)
                refreshUi()
                return
            }
            // 写库失败（例如被唯一约束拦下）→ 落到待确认，绝不丢数据
            insertPending(db, ledgerId, input, parsed, type, account, suggestion, fingerprint, dedupKey, suspect?.id, outcome.message)
            addLog(db, ledgerId, input, parsed, "pending", "写入失败，已转待确认", parsed.amountCents)
            return
        }

        insertPending(db, ledgerId, input, parsed, type, account, suggestion, fingerprint, dedupKey, suspect?.id, blockReason)
        addLog(db, ledgerId, input, parsed, "pending", blockReason, parsed.amountCents)
        if (suspect != null) notifyUser("有一笔疑似重复的账单待确认，请到「通知自动记账」查看")
    }

    /** 返回 null 表示可以自动入账；否则返回给用户看的原因。 */
    private fun blockReason(
        mode: String,
        store: Store,
        pkg: String,
        parsed: ParsedNotice,
        type: TxnType,
        account: Account?,
        suspect: com.xzledger.app.data.SimilarTxn?,
    ): String? {
        if (mode != NotifyPrefs.MODE_AUTO) return "待确认：当前是「待确认」模式"
        if (!NotifyPrefs.autoPackages(store).contains(pkg)) return "待确认：该应用未允许自动入账"
        if (parsed.kind == NoticeKind.REFUND) return "待确认：退款需要关联原账单"
        if (parsed.kind == NoticeKind.TRANSFER || parsed.kind == NoticeKind.CREDIT_REPAY ||
            parsed.kind == NoticeKind.WITHDRAW || parsed.kind == NoticeKind.TOPUP
        ) return "待确认：可能是账户间转账／还款，避免重复计收支"
        if (type == TxnType.EXPENSE && !NotifyPrefs.autoExpense(store)) return "待确认：未允许自动记录支出"
        if (type == TxnType.INCOME && !NotifyPrefs.autoIncome(store)) return "待确认：未允许自动记录收入"
        if (parsed.confidence < NotifyPrefs.AUTO_MIN_CONFIDENCE) {
            return "待确认：识别置信度 " + parsed.confidence + "%，低于 " + NotifyPrefs.AUTO_MIN_CONFIDENCE + "%"
        }
        if (account == null) return "待确认：没有匹配到账户，请手动选择"
        if (suspect != null) return "待确认：与已有账单疑似重复"
        return null
    }

    private fun buildTxn(
        db: Db,
        ledgerId: String,
        input: NoticeInput,
        parsed: ParsedNotice,
        type: TxnType,
        account: Account,
        categoryId: String?,
        nature: Nature?,
        basis: ClassifyBasis,
        ruleId: String?,
        dedupKey: String,
    ): Txn {
        val title = parsed.merchant.ifEmpty { parsed.sourceName + kindWord(parsed.kind) }
        return Txn(
            id = Ids.newId(),
            ledgerId = ledgerId,
            type = type,
            amount = parsed.amountCents,
            currency = parsed.currency,
            nature = nature,
            categoryId = categoryId,
            accountId = account.id,
            title = title,
            merchant = parsed.merchant,
            merchantRaw = parsed.merchant,
            note = "",
            occurredDate = TxnParser.formatDate(parsed.occurredAt),
            occurredAt = parsed.occurredAt,
            source = TxnSource.NOTIFY,
            sourcePlatform = input.pkg,
            tradeNo = parsed.serial,
            txStatus = TxnStatus.SUCCESS,
            dedupKey = dedupKey,
            classifyBasis = basis,
            classifyRuleId = ruleId,
        )
    }

    private fun insertPending(
        db: Db,
        ledgerId: String,
        input: NoticeInput,
        parsed: ParsedNotice,
        type: TxnType,
        account: Account?,
        suggestion: Classifier.Suggestion,
        fingerprint: String,
        dedupKey: String,
        duplicateOf: String?,
        reason: String,
    ) {
        val now = System.currentTimeMillis()
        val title = parsed.merchant.ifEmpty { parsed.sourceName + kindWord(parsed.kind) }
        db.insertPending(
            PendingTxn(
                id = Ids.newId(),
                ledgerId = ledgerId,
                pkg = input.pkg,
                appLabel = input.appLabel.ifEmpty { NotifyPrefs.labelOf(XzApp.instance, input.pkg) },
                type = type,
                amount = parsed.amountCents,
                balance = parsed.balanceCents,
                cardTail = parsed.cardTail,
                merchant = parsed.merchant,
                title = title,
                note = "",
                currency = parsed.currency,
                serial = parsed.serial,
                occurredAt = parsed.occurredAt,
                occurredDate = TxnParser.formatDate(parsed.occurredAt),
                kind = parsed.kind.id,
                confidence = parsed.confidence,
                status = "pending",
                reason = reason,
                fingerprint = fingerprint,
                duplicateOf = duplicateOf ?: "",
                accountId = account?.id,
                categoryId = suggestion.categoryId,
                nature = suggestion.nature,
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    /**
     * 账户自动关联（保守）：
     *  1. 用户手工绑定过的规则优先（按「应用+尾号」或「应用」）；
     *  2. 支付宝/微信只在其唯一对应账户时才采用；
     *  3. 银行尾号能在账户名里唯一命中时才采用；
     *  4. 多个候选一律不自动选，交给用户。
     * 尾号从不当作完整卡号使用，也绝不请求任何银行凭据。
     */
    private fun matchAccount(db: Db, ledgerId: String, accounts: List<Account>, pkg: String, parsed: ParsedNotice): Account? {
        if (accounts.isEmpty()) return null
        val byId = accounts.associateBy { it.id }
        val tailKey = if (parsed.cardTail.isNotEmpty()) "tail:" + parsed.cardTail else ""
        val keys = buildList {
            if (tailKey.isNotEmpty()) add("pkg:" + pkg + "|" + tailKey)
            add("pkg:" + pkg)
            if (tailKey.isNotEmpty()) add(tailKey)
        }
        for (k in keys) {
            val id = db.accountLink(ledgerId, k)
            val a = id?.let { byId[it] }
            if (a != null) return a
        }

        val platformType = when (pkg) {
            "com.eg.android.AlipayGphone" -> AccountType.ALIPAY
            "com.tencent.mm" -> AccountType.WECHAT
            else -> null
        }
        if (platformType != null) {
            val sameType = accounts.filter { it.type == platformType }
            if (sameType.size == 1) return sameType[0]
        }

        if (parsed.cardTail.isNotEmpty()) {
            val byTail = accounts.filter { it.name.contains(parsed.cardTail) }
            if (byTail.size == 1) return byTail[0]
        }
        return null
    }

    private fun currentLedgerId(db: Db, store: Store): String {
        val fromState = try {
            XzApp.state.ledgerId
        } catch (t: Throwable) {
            ""
        }
        if (fromState.isNotEmpty()) return fromState
        val saved = store.str(SettingsKeys.LEDGER_ID, "")
        if (saved.isNotEmpty()) return saved
        val ledgers = try {
            db.ledgers()
        } catch (t: Throwable) {
            emptyList()
        }
        return ledgers.firstOrNull { it.isDefault }?.id ?: ledgers.firstOrNull()?.id ?: ""
    }

    private fun addLog(
        db: Db,
        ledgerId: String,
        input: NoticeInput,
        parsed: ParsedNotice,
        action: String,
        detail: String,
        amount: Long,
    ) {
        val now = System.currentTimeMillis()
        db.addNoticeLog(
            NoticeLog(
                id = Ids.newId(),
                ledgerId = ledgerId,
                pkg = input.pkg,
                appLabel = input.appLabel.ifEmpty { NotifyPrefs.labelOf(XzApp.instance, input.pkg) },
                kind = parsed.kind.id,
                action = action,
                detail = detail,
                amount = if (amount > 0L) amount else null,
                createdAt = now,
            )
        )
    }

    private fun logFailure(context: Context, input: NoticeInput, t: Throwable) {
        try {
            val db = appDb() ?: return
            val store = appStore() ?: return
            val ledgerId = currentLedgerId(db, store)
            if (ledgerId.isEmpty()) return
            db.addNoticeLog(
                NoticeLog(
                    id = Ids.newId(), ledgerId = ledgerId, pkg = input.pkg,
                    appLabel = NotifyPrefs.labelOf(context, input.pkg), kind = "error",
                    action = "error", detail = "解析失败：" + (t.javaClass.simpleName),
                    amount = null, createdAt = System.currentTimeMillis()
                )
            )
        } catch (ignored: Throwable) {
            // 日志本身失败也不能抛出去
        }
    }

    private fun kindWord(kind: NoticeKind): String = when (kind) {
        NoticeKind.EXPENSE -> "支出"
        NoticeKind.INCOME -> "收入"
        NoticeKind.REFUND -> "退款"
        NoticeKind.TRANSFER -> "转账"
        NoticeKind.CREDIT_REPAY -> "还款"
        NoticeKind.WITHDRAW -> "提现"
        NoticeKind.TOPUP -> "充值"
        NoticeKind.FEE -> "手续费"
        else -> "交易"
    }

    private fun notifyUser(text: String) {
        val app = try {
            XzApp.instance
        } catch (t: Throwable) {
            return
        }
        val store = try {
            XzApp.store
        } catch (t: Throwable) {
            return
        }
        if (!NotifyPrefs.showToast(store)) return
        main.post {
            try {
                Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
            } catch (ignored: Throwable) {
            }
        }
    }

    /** 自动入账后立刻刷新界面与桌面小组件；失败不影响入账结果。 */
    private fun refreshUi() {
        main.post {
            try {
                XzApp.state.touch()
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun appDb(): Db? = try {
        XzApp.db
    } catch (t: Throwable) {
        null
    }

    private fun appStore(): Store? = try {
        XzApp.store
    } catch (t: Throwable) {
        null
    }
}
