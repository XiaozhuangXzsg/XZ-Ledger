package com.xzledger.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 云同步适配层（可选功能，默认关闭）。
 *
 * 说明与取舍：
 * - 本应用不绑定任何云服务商，也不内置服务器。用户在「我的 → 云同步」中自行填写接口地址与访问令牌。
 * - 协议约定（兼容自建后端）：
 *   POST {endpoint}
 *   headers: Content-Type: application/json; Authorization: Bearer {token}
 *   body: {"deviceId":"...","items":[{"id":"唯一编号","entity":"txn","entityId":"...","op":"upsert","baseVersion":3}]}
 *   响应: 2xx + {"accepted":["id",...]} 表示服务端已落库（可省略，缺省表示全部接受）；
 *          409 表示版本冲突（应用会保留本地数据并提示，不静默覆盖）。
 * - 客户端只用唯一编号做重试去重；真正的并发检查与服务端权限隔离由后端负责，本应用不做假设。
 * - API Key 等密钥不进入同步负载；同步失败会如实返回失败原因。
 */
object Sync {

    private const val MAX_BATCH = 200

    suspend fun push(db: Db, store: Store): String = withContext(Dispatchers.IO) {
        val endpoint = store.str(SettingsKeys.CLOUD_ENDPOINT, "").trim()
        if (endpoint.isEmpty()) return@withContext "未配置接口地址，未执行同步"
        if (!store.bool(SettingsKeys.CLOUD_ENABLED, false)) return@withContext "云同步未开启，未执行同步"
        val token = store.str(SettingsKeys.CLOUD_TOKEN, "").trim()
        val items = db.outboxItems(MAX_BATCH)
        if (items.isEmpty()) return@withContext "没有待同步的操作"
        val body = buildBody(items)
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 20000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer " + token)
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = readAll(if (code in 200..299) conn.inputStream else conn.errorStream)
            when {
                code == 409 -> "同步冲突：服务端存在更新的版本，已保留本地数据，未覆盖。请先在另一台设备处理后再同步。"
                code in 200..299 -> {
                    val accepted = parseAccepted(text)
                    val ids = if (accepted.isEmpty()) items.map { it.id } else items.filter { accepted.contains(it.id) }.map { it.id }
                    ids.forEach { db.markOutboxSynced(it) }
                    "同步完成：已提交 " + ids.size + " 项" + (if (ids.size < items.size) "，其余 " + (items.size - ids.size) + " 项服务端未确认，保留待同步" else "")
                }
                else -> "同步失败：HTTP " + code + " " + text.take(200)
            }
        } catch (e: Exception) {
            "同步失败：" + (e.message ?: e.javaClass.simpleName) + "（本地数据未受影响，可稍后重试）"
        } finally {
            conn?.disconnect()
        }
    }

    private fun readAll(stream: java.io.InputStream?): String {
        if (stream == null) return ""
        return BufferedReader(stream.reader(Charsets.UTF_8)).use { it.readText() }
    }

    private fun jsonEscape(s: String): String {
        val sb = StringBuilder()
        s.forEach { c ->
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("?") else sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun buildBody(items: List<Db.OutboxItem>): String {
        val sb = StringBuilder()
        sb.append("{\"deviceId\":\"").append(jsonEscape(deviceId())).append("\",\"items\":[")
        items.forEachIndexed { i, it ->
            if (i > 0) sb.append(',')
            sb.append("{\"id\":\"").append(jsonEscape(it.id))
                .append("\",\"entity\":\"").append(jsonEscape(it.entity))
                .append("\",\"entityId\":\"").append(jsonEscape(it.entityId))
                .append("\",\"op\":\"").append(jsonEscape(it.op))
                .append("\",\"baseVersion\":").append(it.baseVersion)
                .append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun deviceId(): String = try {
        java.util.UUID.randomUUID().toString().take(8)
    } catch (e: Exception) {
        "unknown"
    }

    /** 极简响应解析：只识别 "accepted":["a","b"] 形式，缺失时表示服务端全部接受。 */
    private fun parseAccepted(text: String): List<String> {
        val idx = text.indexOf("\"accepted\"")
        if (idx < 0) return emptyList()
        val start = text.indexOf('[', idx)
        val end = text.indexOf(']', start + 1)
        if (start < 0 || end < 0 || end <= start + 1) return emptyList()
        return text.substring(start + 1, end).split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }
    }
}
