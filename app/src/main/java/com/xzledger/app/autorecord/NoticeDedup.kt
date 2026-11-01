package com.xzledger.app.autorecord

import com.xzledger.app.data.MerchantNormalizer
import com.xzledger.app.data.TxnType
import kotlin.math.abs

/**
 * 通知去重（纯函数，可单元测试）。
 *
 * 分层策略：
 *  1. 有交易流水号：以「来源应用 + 流水号」为强键，最可靠；
 *  2. 无流水号：来源 + 方向 + 金额 + 分钟级时间 + 规范化商户 组成指纹；
 *     同一条通知被系统重复投递、应用更新同一条通知、重启后重放，都会落到同一指纹；
 *  3. 跨应用（银行 + 支付宝／微信）疑似重复：金额相同、方向相同、时间相差 ≤ 10 分钟
 *     且来源不同 → 只提示「疑似重复」，进入待确认，绝不删除、也绝不自动合并。
 *
 * 绝不能只用金额判重：连续两笔同额消费必须都能入账。
 */
object NoticeDedup {

    /** 指纹：用于待确认表唯一约束与「同一条通知多次投递」判重。 */
    fun fingerprint(
        pkg: String,
        type: TxnType?,
        amountCents: Long,
        occurredAt: Long,
        merchant: String,
        serial: String,
    ): String {
        if (serial.isNotEmpty()) return "s|" + pkg + "|" + serial
        val norm = MerchantNormalizer.normalize(merchant)
        val minute = occurredAt / 60_000L
        return listOf("f", pkg, type?.id ?: "?", amountCents.toString(), minute.toString(), norm).joinToString("|")
    }

    /** 写入 txn.dedup_key 的键：与其它入口（CSV、AI、云同步）共用同一个唯一约束。 */
    fun txnDedupKey(fingerprint: String): String = "notify|" + fingerprint

    /**
     * 跨来源疑似重复：同一笔真实交易，银行 App 和支付宝／微信可能同时发通知。
     * 只有「金额一致 + 方向一致 + 时间接近 + 来源不同」才算候选，仍交给用户确认。
     */
    fun looksLikeCrossSourceDuplicate(
        pkgA: String,
        typeA: TxnType?,
        amountA: Long,
        timeA: Long,
        serialA: String,
        pkgB: String,
        typeB: TxnType?,
        amountB: Long,
        timeB: Long,
        serialB: String,
        windowMs: Long = 10 * 60 * 1000L,
    ): Boolean {
        if (pkgA == pkgB) return false
        if (amountA <= 0L || amountA != amountB) return false
        if (typeA != null && typeB != null && typeA != typeB) return false
        if (abs(timeA - timeB) > windowMs) return false
        // 双方都有流水号且不同 → 更可能是两笔独立交易，不提示
        if (serialA.isNotEmpty() && serialB.isNotEmpty() && serialA != serialB) return false
        return true
    }
}
