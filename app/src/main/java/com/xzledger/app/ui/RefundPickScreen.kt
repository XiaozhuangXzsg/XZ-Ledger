package com.xzledger.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xzledger.app.AppState
import com.xzledger.app.XzApp
import com.xzledger.app.data.Txn
import com.xzledger.app.data.TxnFilter
import com.xzledger.app.data.TxnType
import com.xzledger.app.data.txns
import com.xzledger.app.ui.theme.expenseColor
import com.xzledger.app.util.Money

/**
 * 退款记账：先选一条支出账单作为退款对象。
 * 选中后回到记账页，自动带出物品名称、商户、原分类与账户，金额默认填原金额（可改，支持部分退款）。
 * 这里只读取账单，不会修改任何已记录的账单。
 */
@Composable
fun RefundPickScreen(state: AppState, nav: NavStack) {
    val db = XzApp.db
    val version = state.dataVersion
    val ledgerId = state.ledgerId
    var keyword by remember { mutableStateOf("") }

    val list = remember(version, ledgerId, keyword) {
        db.txns(
            TxnFilter(
                ledgerId = ledgerId,
                keyword = keyword.trim(),
                types = listOf(TxnType.EXPENSE),
                limit = 300
            )
        )
    }
    // 已被退过的金额：同一笔支出可以多次部分退款，这里给出剩余可退额，避免重复退。
    val refunded = remember(version, ledgerId) {
        db.txns(TxnFilter(ledgerId = ledgerId, types = listOf(TxnType.REFUND), limit = 800))
            .groupBy { it.refundOf.orEmpty() }
            .mapValues { e -> e.value.sumOf { it.amount } }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("选择退款账单", onBack = { nav.pop() })
        OutlinedTextField(
            value = keyword,
            onValueChange = { keyword = it },
            singleLine = true,
            label = { Text("搜索商户、备注或金额") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
        )
        Text(
            "选择一条支出账单作为退款对象；已记录的账单不会被改动，退款会单独记一笔。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        if (list.isEmpty()) {
            EmptyHint("没有找到可退款的支出账单")
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                items(list, key = { it.id }) { t ->
                    RefundPickRow(t, refunded[t.id] ?: 0L, state) {
                        state.refundTarget = t
                        nav.pop()
                    }
                }
                item { Spacer(Modifier.height(120.dp)) }
            }
        }
    }
}

@Composable
private fun RefundPickRow(t: Txn, refundedAmount: Long, state: AppState, onClick: () -> Unit) {
    Card2(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onClick() }) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    t.title.ifEmpty { t.merchant.ifEmpty { "未命名账单" } },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    t.occurredDate + " · " + (state.category(t.categoryId)?.name ?: "未分类") +
                        (if (t.merchant.isNotEmpty()) " · " + t.merchant else ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (refundedAmount > 0L) {
                    Text(
                        "已退 ¥" + Money.centsToPlain(refundedAmount) + "，剩余可退 ¥" + Money.centsToPlain((t.amount - refundedAmount).coerceAtLeast(0L)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                "¥" + Money.centsToPlain(t.amount),
                style = MaterialTheme.typography.titleMedium,
                color = expenseColor()
            )
        }
    }
}
