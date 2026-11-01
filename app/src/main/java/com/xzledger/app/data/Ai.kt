package com.xzledger.app.data

import com.xzledger.app.util.Dates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI 权限键。读账本与新增账单相互独立；默认全部关闭，用户必须逐项授权。
 * 删除、批量修改、跨账本属于高风险权限，默认关闭且不会自动开启。
 */
object AiPerm {
    const val READ = "read_ledger"
    const val WRITE = "write_txn"
    const val AUTO_POST = "auto_post"
    const val DELETE = "delete_txn"
    const val BATCH = "batch_modify"
    const val CROSS_LEDGER = "cross_ledger"

    val items: List<Triple<String, String, String>> = listOf(
        Triple(READ, "读取账本", "允许 AI 通过查询工具读取当前账本的统计与明细"),
        Triple(WRITE, "新增账单", "允许 AI 生成账单记录（未开启自动入账时只生成待确认记录）"),
        Triple(AUTO_POST, "快捷自动入账", "信息完整、无重复无疑义时直接写入数据库，并显示撤销入口"),
        Triple(DELETE, "删除账单", "高风险：允许 AI 删除账单，默认关闭"),
        Triple(BATCH, "批量修改", "高风险：允许 AI 批量修改分类或性质，默认关闭"),
        Triple(CROSS_LEDGER, "跨账本读取", "高风险：允许读取其他账本，默认关闭"),
    )

    fun label(key: String): String = items.firstOrNull { it.first == key }?.second ?: key
    fun desc(key: String): String = items.firstOrNull { it.first == key }?.third ?: ""
}

/** AI 识别出的账单草稿：数据库保存成功前绝不显示「已入账」。 */
data class AiDraft(
    val id: String = Ids.newId(),
    val amount: Long = 0L,
    val date: String = Dates.today(),
    val time: String = "12:00",
    val type: TxnType = TxnType.EXPENSE,
    val accountId: String? = null,
    val accountName: String = "",
    val categoryId: String? = null,
    val categoryName: String = "",
    val nature: Nature? = null,
    val merchant: String = "",
    val note: String = "",
    val tradeNo: String = "",
    val platform: String = "",
    val basis: ClassifyBasis = ClassifyBasis.DEFAULT,
    val basisReason: String = "",
    /** new（信息完整可入账）| confirm（待用户确认）| saved（已入账）| fail（保存失败）| duplicate */
    val state: String = "new",
    val message: String = "",
    val savedTxnId: String? = null,
    val source: TxnSource = TxnSource.AI_CHAT,
) {
    val missingAmount: Boolean get() = amount <= 0L
}

data class AiResult(
    val text: String = "",
    val drafts: List<AiDraft> = emptyList(),
    val error: String? = null,
    val toolNotes: List<String> = emptyList(),
)

/** 与用户自填的 OpenAI 兼容接口通信。 */
object AiClient {

    suspend fun test(cfg: AiConfig): String = withContext(Dispatchers.IO) {
        try {
            val msgs = Json.arr(listOf(Json.obj(
                "role" to Json.str("user"),
                "content" to Json.str("请只回复 OK")
            )))
            val body = Json.obj(
                "model" to Json.str(cfg.model),
                "messages" to msgs,
                "max_tokens" to "16",
                "temperature" to "0"
            )
            val text = post(cfg, body)
            val v = Json.parseOrNull(text)
            val content = Json.str(Json.get(Json.list(Json.get(v, "choices")).firstOrNull()?.let { Json.get(it, "message") }, "content"))
            if (content != null) "连接成功（模型：" + cfg.model + "）。模型回复：" + content.take(40)
            else "连接成功，但返回内容为空，请检查模型名称是否可用。"
        } catch (e: Exception) {
            "连接失败：" + (e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun complete(cfg: AiConfig, messagesJson: String, toolsJson: String?): String = withContext(Dispatchers.IO) {
        val body = if (toolsJson == null) {
            Json.obj(
                "model" to Json.str(cfg.model),
                "messages" to messagesJson,
                "temperature" to "0.2"
            )
        } else {
            Json.obj(
                "model" to Json.str(cfg.model),
                "messages" to messagesJson,
                "tools" to toolsJson,
                "tool_choice" to Json.str("auto"),
                "temperature" to "0.2"
            )
        }
        post(cfg, body)
    }

    private fun post(cfg: AiConfig, body: String): String {
        if (cfg.baseUrl.isBlank()) throw IOException("尚未填写接口地址")
        if (cfg.model.isBlank()) throw IOException("尚未填写模型名称")
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(cfg.chatEndpoint()).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 60000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (cfg.apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer " + cfg.apiKey)
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = readAll(if (code in 200..299) conn.inputStream else conn.errorStream)
            if (code !in 200..299) throw IOException("HTTP " + code + "：" + text.take(300))
            return text
        } finally {
            conn?.disconnect()
        }
    }

    private fun readAll(stream: java.io.InputStream?): String {
        if (stream == null) return ""
        return BufferedReader(stream.reader(Charsets.UTF_8)).use { it.readText() }
    }
}
