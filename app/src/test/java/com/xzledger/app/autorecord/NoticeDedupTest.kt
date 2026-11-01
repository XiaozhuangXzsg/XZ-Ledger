package com.xzledger.app.autorecord

import com.xzledger.app.data.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 去重与跨来源疑似重复的单元测试。 */
class NoticeDedupTest {

    private val t0 = 1_760_000_000_000L // 固定时间，避免测试随时钟漂移

    @Test
    fun serialIsStrongestKey() {
        val a = NoticeDedup.fingerprint("com.icbc", TxnType.EXPENSE, 3580L, t0, "某商户", "SN123")
        val b = NoticeDedup.fingerprint("com.icbc", TxnType.EXPENSE, 9999L, t0 + 5 * 60_000L, "别的商户", "SN123")
        assertEquals(a, b)
        assertEquals("s|com.icbc|SN123", a)
    }

    @Test
    fun sameNoticeWithinMinuteSharesFingerprint() {
        val a = NoticeDedup.fingerprint("com.tencent.mm", TxnType.EXPENSE, 2800L, t0, "某商户", "")
        val b = NoticeDedup.fingerprint("com.tencent.mm", TxnType.EXPENSE, 2800L, t0 + 30_000L, "某商户", "")
        assertEquals(a, b)
    }

    @Test
    fun differentAmountIsNeverDeduped() {
        val a = NoticeDedup.fingerprint("com.tencent.mm", TxnType.EXPENSE, 2800L, t0, "某商户", "")
        val b = NoticeDedup.fingerprint("com.tencent.mm", TxnType.EXPENSE, 2900L, t0, "某商户", "")
        assertNotEquals(a, b)
    }

    @Test
    fun differentMinuteIsDifferentFingerprint() {
        val a = NoticeDedup.fingerprint("com.tencent.mm", TxnType.EXPENSE, 2800L, t0, "某商户", "")
        val b = NoticeDedup.fingerprint("com.tencent.mm", TxnType.EXPENSE, 2800L, t0 + 61_000L, "某商户", "")
        assertNotEquals(a, b)
    }

    @Test
    fun crossSourceWithSameAmountNearbyIsSuspect() {
        assertTrue(
            NoticeDedup.looksLikeCrossSourceDuplicate(
                "com.icbc", TxnType.EXPENSE, 3580L, t0, "",
                "com.eg.android.AlipayGphone", TxnType.EXPENSE, 3580L, t0 + 2 * 60_000L, ""
            )
        )
    }

    @Test
    fun crossSourceWithDifferentAmountIsNotSuspect() {
        assertFalse(
            NoticeDedup.looksLikeCrossSourceDuplicate(
                "com.icbc", TxnType.EXPENSE, 3580L, t0, "",
                "com.eg.android.AlipayGphone", TxnType.EXPENSE, 3600L, t0, ""
            )
        )
    }

    @Test
    fun crossSourceWithDifferentSerialsIsNotSuspect() {
        assertFalse(
            NoticeDedup.looksLikeCrossSourceDuplicate(
                "com.icbc", TxnType.EXPENSE, 3580L, t0, "SN1",
                "com.eg.android.AlipayGphone", TxnType.EXPENSE, 3580L, t0, "SN2"
            )
        )
    }

    @Test
    fun samePackageIsNeverCrossSource() {
        assertFalse(
            NoticeDedup.looksLikeCrossSourceDuplicate(
                "com.icbc", TxnType.EXPENSE, 3580L, t0, "",
                "com.icbc", TxnType.EXPENSE, 3580L, t0, ""
            )
        )
    }

    @Test
    fun txnDedupKeyIsNamespaced() {
        val fp = NoticeDedup.fingerprint("com.icbc", TxnType.EXPENSE, 100L, t0, "X", "")
        assertEquals("notify|" + fp, NoticeDedup.txnDedupKey(fp))
    }
}
