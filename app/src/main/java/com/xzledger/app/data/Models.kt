package com.xzledger.app.data

/** 收支方向。退款单独立记账，用于抵减支出，避免与收入混淆。 */
enum class TxnType(val id: String, val label: String) {
    EXPENSE("expense", "支出"),
    INCOME("income", "收入"),
    TRANSFER("transfer", "转账"),
    REFUND("refund", "退款");

    companion object {
        fun of(id: String?): TxnType = entries.firstOrNull { it.id == id } ?: EXPENSE
    }
}

/** 消费性质：与消费分类是两个独立字段。 */
enum class Nature(val id: String, val label: String, val desc: String) {
    PERMANENT("permanent", "长期消费", "永久／半永久消费"),
    INSTANT("instant", "即时消费", "即时／临时消费");

    companion object {
        fun of(id: String?): Nature? = entries.firstOrNull { it.id == id }
    }
}

enum class AccountType(val id: String, val label: String) {
    CASH("cash", "现金"),
    DEBIT("debit", "储蓄卡"),
    CREDIT("credit", "信用卡"),
    ALIPAY("alipay", "支付宝"),
    WECHAT("wechat", "微信"),
    OTHER("other", "其他");

    companion object {
        fun of(id: String?): AccountType = entries.firstOrNull { it.id == id } ?: OTHER
    }
}

enum class TxnStatus(val id: String, val label: String) {
    SUCCESS("success", "交易成功"),
    PENDING("pending", "进行中"),
    CLOSED("closed", "已关闭"),
    REFUNDED("refunded", "已全额退款"),
    PARTIAL_REFUND("partial_refund", "部分退款");

    companion object {
        fun of(id: String?): TxnStatus = entries.firstOrNull { it.id == id } ?: SUCCESS
    }
}

/** 账单来源，用于去重与个人规则条件。 */
enum class TxnSource(val id: String, val label: String) {
    MANUAL("manual", "手动记账"),
    AI_CHAT("ai_chat", "AI 聊天"),
    IMPORT_CSV("import_csv", "CSV 导入"),
    IMPORT_XLSX("import_xlsx", "Excel 导入"),
    IMPORT_PDF("import_pdf", "PDF 导入"),
    OCR("ocr", "图片识别"),
    RECURRING("recurring", "周期记账"),
    SYNC("sync", "云同步");

    companion object {
        fun of(id: String?): TxnSource = entries.firstOrNull { it.id == id } ?: MANUAL
    }
}

/** 分类依据，界面需要展示给用户看。 */
enum class ClassifyBasis(val id: String, val label: String) {
    MANUAL("manual", "手动选择"),
    RULE("rule", "依据你的商户规则"),
    HISTORY("history", "依据你的历史习惯"),
    AI("ai", "AI 建议"),
    DEFAULT("default", "默认分类");

    companion object {
        fun of(id: String?): ClassifyBasis = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

data class Ledger(
    val id: String,
    val name: String,
    val ownerId: String = "local",
    val sortOrder: Int = 0,
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
    val version: Int = 1,
)

data class Account(
    val id: String,
    val ledgerId: String,
    val name: String,
    val type: AccountType = AccountType.OTHER,
    val icon: String = "",
    val openingBalance: Long = 0L,
    val currency: String = "CNY",
    val sortOrder: Int = 0,
    val archived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
    val version: Int = 1,
)

data class Category(
    val id: String,
    val ledgerId: String,
    val name: String,
    val kind: String,
    val icon: String = "",
    val natureDefault: Nature? = null,
    val parentId: String? = null,
    val sortOrder: Int = 0,
    val isSystem: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
    val version: Int = 1,
)

data class Txn(
    val id: String,
    val ledgerId: String,
    val type: TxnType,
    /** 金额，最小货币单位（分），恒为正数；方向由 type 决定。 */
    val amount: Long,
    val currency: String = "CNY",
    val nature: Nature? = null,
    val categoryId: String? = null,
    val accountId: String? = null,
    val toAccountId: String? = null,
    val title: String = "",
    val merchant: String = "",
    val merchantRaw: String = "",
    val note: String = "",
    /** 消费发生日期 yyyy-MM-dd，唯一统计口径。 */
    val occurredDate: String,
    val occurredAt: Long,
    /** 技术同步时间，与消费日期分开保存。 */
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val source: TxnSource = TxnSource.MANUAL,
    val sourcePlatform: String = "",
    /** 订单编码，字符串保存，保留前导零。 */
    val orderNo: String = "",
    /** 稳定交易单号，优先用于判重。 */
    val tradeNo: String = "",
    val txStatus: TxnStatus = TxnStatus.SUCCESS,
    val refundOf: String? = null,
    val linkedTxnId: String? = null,
    val dedupKey: String = "",
    val classifyBasis: ClassifyBasis = ClassifyBasis.MANUAL,
    val classifyRuleId: String? = null,
    val imagePaths: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val chatMsgId: String? = null,
    val importBatchId: String? = null,
    val deleted: Boolean = false,
    val deletedAt: Long? = null,
    val version: Int = 1,
) {
    val isRefund: Boolean get() = type == TxnType.REFUND

    /** 是否计入真实收支：转账、已关闭、进行中等无实际资金变化的交易不计入。 */
    val affectsIncomeExpense: Boolean
        get() = !deleted && txStatus != TxnStatus.CLOSED && txStatus != TxnStatus.PENDING &&
                (type == TxnType.EXPENSE || type == TxnType.INCOME || type == TxnType.REFUND)
}

data class Rule(
    val id: String,
    val ledgerId: String,
    val name: String,
    val enabled: Boolean = true,
    val priority: Int = 100,
    /** merchant_exact | merchant_contains | keyword */
    val matchType: String = "merchant_exact",
    val condMerchant: String = "",
    val condKeyword: String = "",
    val condAccountId: String? = null,
    /** "" 表示不限 */
    val condDirection: String = "",
    val condSource: String = "",
    val condMinAmount: Long? = null,
    val condMaxAmount: Long? = null,
    val setCategoryId: String? = null,
    val setNature: Nature? = null,
    /** user = 用户明确确认；learned = 由重复修改自动建议 */
    val origin: String = "user",
    val hitCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
)

data class LearningEvent(
    val id: String,
    val ledgerId: String,
    val txnId: String?,
    val merchantRaw: String,
    val merchantNorm: String,
    val oldCategoryId: String?,
    val newCategoryId: String?,
    val oldNature: Nature?,
    val newNature: Nature?,
    /** 记录来源：user = 用户修改账单后记录；ai = 配置了 AI 后由模型学习产生。 */
    val origin: String = "user",
    val createdAt: Long = System.currentTimeMillis(),
    val promoted: Boolean = false,
)

data class Budget(
    val id: String,
    val ledgerId: String,
    /** yyyy-MM 或 ALL（长期有效） */
    val month: String = "ALL",
    /** null 表示总额预算 */
    val categoryId: String? = null,
    val amount: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
)

data class RecurringTask(
    val id: String,
    val ledgerId: String,
    val name: String,
    val type: TxnType = TxnType.EXPENSE,
    val amount: Long,
    val categoryId: String? = null,
    val accountId: String? = null,
    val nature: Nature? = null,
    val merchant: String = "",
    val note: String = "",
    /** monthly | weekly | daily | yearly */
    val period: String = "monthly",
    val dayOfMonth: Int = 1,
    val nextDueAt: Long,
    /** auto = 到期自动生成账单；remind = 仅提醒（避免把未实际支付的费用误记为真实支出） */
    val mode: String = "remind",
    val advanceDays: Int = 1,
    val enabled: Boolean = true,
    val lastRunAt: Long? = null,
    /** 幂等键：同一周期只生成一次，防止重试/重启造成重复入账 */
    val lastRunKey: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
)

data class AiConfig(
    val id: String,
    val name: String,
    /** 接口地址，填写到 /v1 这一级，程序内部再拼 /chat/completions，避免重复拼接。 */
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val apiType: String = "openai",
    val supportsVision: Boolean = false,
    val supportsFile: Boolean = false,
    val isActive: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    /** 规范化请求地址：允许用户填到域名、/v1 或完整端点。 */
    fun chatEndpoint(): String {
        val u = baseUrl.trim().trimEnd('/')
        if (u.endsWith("/chat/completions")) return u
        if (u.endsWith("/v1")) return u + "/chat/completions"
        return u + "/v1/chat/completions"
    }
}

data class ChatMessage(
    val id: String,
    val ledgerId: String,
    /** user | assistant | system | tool */
    val role: String,
    val content: String,
    val attachments: List<String> = emptyList(),
    /** ok | pending | error | processing */
    val status: String = "ok",
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** 该条消息关联的待确认/已入账账单 id */
    val txnIds: List<String> = emptyList(),
    /** 结构化账单卡片 JSON */
    val cardsJson: String = "",
)

data class ImportBatch(
    val id: String,
    val ledgerId: String,
    val fileName: String,
    val source: String,
    val createdAt: Long = System.currentTimeMillis(),
    val createdTxnIds: List<String> = emptyList(),
    val undone: Boolean = false,
)

/** 导入预览中的一行 */
data class ImportRow(
    val rowIndex: Int,
    /** 来源信息：文件、工作表/页码、行号 */
    val origin: String,
    val txn: Txn,
    /** new | duplicate | suspected | need_confirm | failed */
    val state: String = "new",
    val message: String = "",
    val selected: Boolean = true,
)

enum class ImportState(val id: String, val label: String) {
    NEW("new", "新增"),
    DUPLICATE("duplicate", "重复"),
    SUSPECTED("suspected", "疑似重复"),
    NEED_CONFIRM("need_confirm", "待确认"),
    FAILED("failed", "解析失败");

    companion object {
        fun of(id: String?): ImportState = entries.firstOrNull { it.id == id } ?: NEW
    }
}
