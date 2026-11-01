package com.xzledger.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 记账页的 AI 自学习：用户手动记完一笔后，若已配置 AI 服务并授权「新增账单」，
 * 就在后台询问 AI 这个商户是否值得记住；值得则自动建立一条「商户 → 分类」规则。
 *
 * 分类与性质始终使用用户本次的选择，AI 只能决定「记不记、用哪种匹配方式」，
 * 不能改写账单；未配置服务或未授权时完全不联网，任何失败都静默忽略。
 */
object AiLearn {

    /** 是否具备学习条件（纯本地判断，可在主线程调用）。 */
    fun eligible(merchantRaw: String, categoryId: String?, nature: Nature?): Boolean {
        if (categoryId == null && nature == null) return false
        val norm = MerchantNormalizer.normalize(merchantRaw)
        return MerchantNormalizer.isRuleEligible(norm)
    }

    /** 后台学习一条规则，返回规则名（已建立）或 null（跳过或失败）。 */
    suspend fun learn(
        db: Db,
        ledgerId: String,
        merchantRaw: String,
        categoryId: String?,
        categoryName: String,
        nature: Nature?,
        note: String = "",
        txnId: String? = null
    ): String? = withContext(Dispatchers.IO) {
        try {
            val cfg = db.activeAiConfig() ?: return@withContext null
            if (db.allAiPermissions()[AiPerm.WRITE] != true) return@withContext null
            val norm = MerchantNormalizer.normalize(merchantRaw)
            if (norm.isEmpty()) return@withContext null
            val display = MerchantNormalizer.displayName(merchantRaw)
            // 同一商户已经有同样结论的规则时不再重复建立。
            val dup = db.rules(ledgerId).any { r ->
                r.setCategoryId == categoryId && r.setNature == nature && (
                    (r.matchType == "merchant_exact" && MerchantNormalizer.normalize(r.condMerchant) == norm) ||
                        (r.matchType != "merchant_exact" && r.condKeyword.isNotBlank() && display.contains(r.condKeyword))
                    )
            }
            if (dup) return@withContext null

            val sys = "你是记账应用的后台学习模块。用户在记账页为某个商户选择了分类，请判断这个商户是否稳定、是否值得建立「商户 → 分类」规则。" +
                "只输出一行 JSON，不要解释、不要 Markdown：" +
                "{\"remember\":true|false,\"match_type\":\"merchant_exact|merchant_contains|keyword\",\"pattern\":\"用于匹配的名称或关键词\"}。" +
                "商户名过于通用（例如「超市」「转账」「支付宝」这类无法定位具体商家的词）时，remember 填 false。"
            val u = buildString {
                append("商户：").append(display)
                append("\n分类：").append(categoryName.ifBlank { "未分类" })
                append("\n性质：").append(nature?.label ?: "未填写")
                if (note.isNotBlank()) append("\n备注：").append(note)
            }
            val messages = Json.arr(listOf(
                Json.obj("role" to Json.str("system"), "content" to Json.str(sys)),
                Json.obj("role" to Json.str("user"), "content" to Json.str(u))
            ))
            val raw = AiClient.complete(cfg, messages, null)
            val content = contentOf(raw) ?: return@withContext null
            val obj = Json.parseOrNull(jsonBlock(content)) ?: return@withContext null
            if (Json.bool(Json.get(obj, "remember")) != true) return@withContext null
            val matchType = when (Json.str(Json.get(obj, "match_type"))) {
                "merchant_exact" -> "merchant_exact"
                "merchant_contains" -> "merchant_contains"
                else -> "keyword"
            }
            val pattern = (Json.str(Json.get(obj, "pattern")) ?: "").trim().ifEmpty { display }
            val now = System.currentTimeMillis()
            val rule = Rule(
                id = Ids.newId(), ledgerId = ledgerId,
                name = "AI 学习 · " + display,
                enabled = true, priority = 20,
                matchType = matchType,
                condMerchant = if (matchType == "merchant_exact") display else "",
                condKeyword = if (matchType == "merchant_exact") "" else pattern,
                setCategoryId = categoryId, setNature = nature,
                origin = "learned", hitCount = 0, createdAt = now, updatedAt = now
            )
            db.upsertRule(rule)
            // 学习记录：把「模型为这个商户记住了什么」写进学习列表，用户可在「学习记录」页看到来源为 AI 的条目。
            db.addLearningEvent(
                LearningEvent(
                    id = Ids.newId(), ledgerId = ledgerId, txnId = txnId,
                    merchantRaw = merchantRaw, merchantNorm = norm,
                    oldCategoryId = null, newCategoryId = categoryId,
                    oldNature = null, newNature = nature,
                    origin = "ai", promoted = true
                )
            )
            rule.name
        } catch (t: Throwable) {
            null
        }
    }

    /** 从 OpenAI 兼容响应里取 message.content。 */
    private fun contentOf(body: String): String? {
        val v = Json.parseOrNull(body) ?: return null
        val msg = Json.get(Json.list(Json.get(v, "choices")).firstOrNull(), "message")
        return Json.str(Json.get(msg, "content"))
    }

    /** 模型偶尔会用 ``` 包裹，这里截取第一个花括号块。 */
    private fun jsonBlock(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else text
    }
}