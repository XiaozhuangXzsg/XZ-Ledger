package com.xzledger.app.data

/**
 * 自动分类：个人规则 > 历史修改习惯 > 默认。
 * 只有在无法判断时才留空，交给界面提示用户确认，绝不猜测。
 */
object Classifier {
    data class Suggestion(
        val categoryId: String?,
        val nature: Nature?,
        val basis: ClassifyBasis,
        val ruleId: String? = null,
        val reason: String = ""
    )

    fun suggest(
        db: Db,
        ledgerId: String,
        merchantRaw: String,
        note: String,
        accountId: String?,
        type: TxnType,
        source: TxnSource = TxnSource.MANUAL,
        amount: Long = 0L
    ): Suggestion {
        val norm = MerchantNormalizer.normalize(merchantRaw)
        val rules = db.rules(ledgerId)
            .filter { it.enabled && !it.deleted }
            .sortedWith(compareBy({ it.priority }, { -it.createdAt }))
        for (r in rules) {
            if (matches(r, norm, merchantRaw, note, accountId, type, source, amount)) {
                return Suggestion(
                    r.setCategoryId, r.setNature, ClassifyBasis.RULE, r.id,
                    "依据你的规则：" + r.name
                )
            }
        }
        // 历史修改习惯：同一规范化商户至少两次一致修改才提示；并列最多视为多用途，不猜。
        if (MerchantNormalizer.isRuleEligible(norm)) {
            val events = db.learningEventsFor(ledgerId, norm)
                .filter { it.newCategoryId != null || it.newNature != null }
            if (events.size >= 2) {
                val catCounts = events.mapNotNull { it.newCategoryId }.groupingBy { it }.eachCount()
                val natCounts = events.mapNotNull { it.newNature }.groupingBy { it }.eachCount()
                val catTop = catCounts.values.maxOrNull() ?: 0
                val tie = catCounts.values.count { it == catTop } > 1
                if (!tie) {
                    val cat = catCounts.entries.firstOrNull { it.value == catTop }?.key
                    val nat = natCounts.entries.maxByOrNull { it.value }?.key
                    return Suggestion(
                        cat, nat, ClassifyBasis.HISTORY, null,
                        "依据你的历史习惯（同一商户 " + events.size + " 次修改）"
                    )
                }
            }
        }
        return Suggestion(null, null, ClassifyBasis.DEFAULT, null, "")
    }

    fun matches(
        r: Rule, norm: String, raw: String, note: String, accountId: String?,
        type: TxnType, source: TxnSource, amount: Long
    ): Boolean {
        if (r.condMinAmount != null && amount > 0 && amount < r.condMinAmount) return false
        if (r.condMaxAmount != null && amount > 0 && amount > r.condMaxAmount) return false
        if (!r.condAccountId.isNullOrEmpty() && r.condAccountId != accountId) return false
        if (r.condDirection.isNotEmpty() && r.condDirection != type.id) return false
        if (r.condSource.isNotEmpty() && r.condSource != source.id) return false
        val hay = (raw + " " + note + " " + norm).lowercase()
        val rm = MerchantNormalizer.normalize(r.condMerchant)
        val rk = r.condKeyword.trim().lowercase()
        return when (r.matchType) {
            "merchant_exact" -> norm.isNotEmpty() && rm.isNotEmpty() && norm == rm
            "merchant_contains" -> rm.isNotEmpty() && hay.contains(rm)
            "keyword" -> rk.isNotEmpty() && hay.contains(rk)
            "any" -> {
                if (rm.isEmpty() && rk.isEmpty()) false
                else (rm.isEmpty() || hay.contains(rm)) && (rk.isEmpty() || hay.contains(rk))
            }
            else -> false
        }
    }
}
