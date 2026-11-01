package com.xzledger.app.autorecord

import com.xzledger.app.data.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

/**
 * 解析器单元测试（纯 JVM，不需要真机）。
 * 全部文本均为虚构示例，不包含任何真实账户信息。
 */
class TxnParserTest {

    private val now: Long = Calendar.getInstance(Locale.CHINA).apply {
        set(2026, Calendar.NOVEMBER, 1, 12, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun parse(pkg: String, title: String, text: String) =
        TxnParser.parse(NoticeInput(pkg, "示例应用", title, text, now), now)

    // 1. 银行消费：正文里同时有交易金额和账户余额，必须把两者分开。
    @Test
    fun bankExpenseSeparatesAmountFromBalance() {
        val r = parse(
            "com.icbc", "工商银行",
            "您尾号1234的储蓄卡于10月20日14:30消费人民币35.80元，账户余额2580.36元。"
        )
        assertEquals(NoticeKind.EXPENSE, r.kind)
        assertEquals(TxnType.EXPENSE, r.type)
        assertEquals(3580L, r.amountCents)
        assertEquals(258036L, r.balanceCents)
        assertEquals("1234", r.cardTail)
        assertTrue("银行消费应达到自动记账的置信度门槛，实际 " + r.confidence, r.confidence >= 70)
        assertTrue(r.usable)
    }

    // 2. 「收到转账」是收入，不能被当成账户间转账。
    @Test
    fun bankIncomeFromReceivedTransfer() {
        val r = parse("com.chinamworld.main", "建设银行", "您的账户收到转账人民币500.00元。")
        assertEquals(NoticeKind.INCOME, r.kind)
        assertEquals(TxnType.INCOME, r.type)
        assertEquals(50000L, r.amountCents)
    }

    // 3. 支付宝最小化通知。
    @Test
    fun alipayPayment() {
        val r = parse("com.eg.android.AlipayGphone", "支付宝", "支付成功 ¥12.50")
        assertEquals(NoticeKind.EXPENSE, r.kind)
        assertEquals(TxnType.EXPENSE, r.type)
        assertEquals(1250L, r.amountCents)
        assertEquals("CNY", r.currency)
    }

    // 4. 微信支付凭证。
    @Test
    fun wechatPayment() {
        val r = parse("com.tencent.mm", "微信", "微信支付凭证 支出 28.00元")
        assertEquals(NoticeKind.EXPENSE, r.kind)
        assertEquals(TxnType.EXPENSE, r.type)
        assertEquals(2800L, r.amountCents)
    }

    // 5. 退款单独成类，不能当普通支出。
    @Test
    fun refund() {
        val r = parse("com.eg.android.AlipayGphone", "支付宝", "退款成功 ¥20.00，已退回余额")
        assertEquals(NoticeKind.REFUND, r.kind)
        assertEquals(TxnType.REFUND, r.type)
        assertEquals(2000L, r.amountCents)
    }

    // 6. 信用卡还款：既不是新增支出也不是收入，绝不能重复计入。
    @Test
    fun creditCardRepayment() {
        val r = parse("cmb.pb", "招商银行", "信用卡还款成功 1,200.00元，感谢使用")
        assertEquals(NoticeKind.CREDIT_REPAY, r.kind)
        assertEquals(null, r.type)
    }

    // 7. 账户间转账。
    @Test
    fun accountTransfer() {
        val r = parse("com.icbc", "工商银行", "转账 500.00元 到余额宝")
        assertEquals(NoticeKind.TRANSFER, r.kind)
        assertEquals(null, r.type)
        assertEquals(50000L, r.amountCents)
    }

    // 8. 提现。
    @Test
    fun withdraw() {
        val r = parse("com.eg.android.AlipayGphone", "支付宝", "提现到银行卡 100.00元")
        assertEquals(NoticeKind.WITHDRAW, r.kind)
        assertEquals(TxnType.EXPENSE, r.type)
        assertEquals(10000L, r.amountCents)
    }

    // 9. 手续费 / 利息类。
    @Test
    fun fee() {
        val r = parse("cmb.pb", "招商银行", "账户管理费 3.00元")
        assertEquals(NoticeKind.FEE, r.kind)
        assertEquals(TxnType.EXPENSE, r.type)
        assertEquals(300L, r.amountCents)
    }

    // 10. 只提到余额、没有交易金额 → 仅余额变动，不可入账。
    @Test
    fun balanceOnly() {
        val r = parse("com.yitong.mbank.psbc", "邮储银行", "您的账户余额为 1,000.00元")
        assertEquals(NoticeKind.BALANCE_ONLY, r.kind)
        assertEquals(0L, r.amountCents)
        assertFalse(r.usable)
    }

    // 11. 验证码等安全信息：整条丢弃，绝不落盘。
    @Test
    fun otpIgnored() {
        val r = parse("com.icbc", "工商银行", "验证码 123456，5分钟内有效，请勿泄露")
        assertEquals(NoticeKind.IGNORED, r.kind)
        assertFalse(r.usable)
    }

    // 12. 贷款 / 营销通知：即使带金额也不能记成收支。
    @Test
    fun loanAdIgnored() {
        val r = parse("com.icbc", "工商银行", "贷款额度提升至200000元，点击申请，广告")
        assertEquals(NoticeKind.IGNORED, r.kind)
        assertFalse(r.usable)
    }

    // 13. 没有金额的通知。
    @Test
    fun missingAmount() {
        val r = parse("cmb.pb", "招商银行", "您有一笔新的交易，请登录 App 查看")
        assertEquals(NoticeKind.IGNORED, r.kind)
        assertFalse(r.usable)
    }

    // 14. 流水号与完整时间解析。
    @Test
    fun serialAndDateTime() {
        val r = parse(
            "com.icbc", "工商银行",
            "交易流水号 ABC123456789，2025年10月20日 14:30 消费 66.00元"
        )
        assertEquals("ABC123456789", r.serial)
        assertEquals(6600L, r.amountCents)
        assertEquals("2025-10-20", TxnParser.formatDate(r.occurredAt))
        assertNotNull(r.occurredAt)
    }

    // 15. 没有年份的日期：跨年时回退到上一年，不能记成未来。
    @Test
    fun monthDayWithoutYearFallsBack() {
        val r = parse("com.icbc", "工商银行", "您尾号8888的储蓄卡于12月31日消费 10.00元")
        assertEquals("2025-12-31", TxnParser.formatDate(r.occurredAt))
    }
}
