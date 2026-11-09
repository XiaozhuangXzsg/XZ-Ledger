package com.xzledger.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xzledger.app.XzApp
import com.xzledger.app.AppState
import com.xzledger.app.data.Ids
import com.xzledger.app.data.Nature
import com.xzledger.app.data.RecurringTask
import com.xzledger.app.data.TxnType
import com.xzledger.app.data.deleteRecurring
import com.xzledger.app.data.recurringTasks
import com.xzledger.app.data.upsertRecurring
import com.xzledger.app.notify.RecurringEngine
import com.xzledger.app.util.Dates
import com.xzledger.app.util.Money

private val PERIODS = listOf(
    "monthly" to "每月",
    "weekly" to "每周",
    "daily" to "每天",
    "yearly" to "每年"
)

fun periodLabel(p: String): String = PERIODS.firstOrNull { it.first == p }?.second ?: p

@Composable
fun RecurringScreen(state: AppState, nav: NavStack) {
    val db = XzApp.db
    val ctx = LocalContext.current
    var version by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<RecurringTask?>(null) }
    var pickCategory by remember { mutableStateOf(false) }
    var pickPeriod by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    val list = remember(state.dataVersion, version) { db.recurringTasks(state.ledgerId) }
    val cats = remember(state.dataVersion) { db.categories(state.ledgerId) }
    val accs = remember(state.dataVersion) { db.accounts(state.ledgerId) }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "周期记账与提醒", onBack = { nav.pop() }) {
            IconButton({
                editing = RecurringTask(id = Ids.newId(), ledgerId = state.ledgerId, name = "", type = TxnType.EXPENSE,
                    amount = 0L, categoryId = cats.firstOrNull()?.id, accountId = accs.firstOrNull()?.id,
                    period = "monthly", dayOfMonth = Dates.today().substring(8).toIntOrNull() ?: 1,
                    nextDueAt = System.currentTimeMillis(), mode = "remind", enabled = true)
            }) { Icon(Icons.Filled.Add, contentDescription = "新增周期任务") }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            item {
                Card2 {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text("说明", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "「仅提醒」只发送通知，不会生成账单；「到期自动生成账单」才会写入真实支出。" +
                                "同一到期日重复执行不会重复入账（任务键 + 数据库唯一约束双重兜底）。" +
                                "设备重启、时间变化后会重新安排提醒。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ChipButton("立即执行到期任务", false) {
                                val n = RecurringEngine.runDue(ctx)
                                info = "已处理 " + n + " 个到期任务"
                                version++; state.touch()
                            }
                            ChipButton("刷新", false) { version++ }
                        }
                        info?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            if (list.isEmpty()) item { EmptyHint("还没有周期任务。可添加房租、话费、会员续费等。") }
            items(list) { t ->
                val cat = cats.firstOrNull { it.id == t.categoryId }
                Card2(onClick = { editing = t.copy() }) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CategoryAvatar(cat?.icon ?: "🔁", 36.dp, name = cat?.name)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t.name.ifEmpty { "未命名任务" }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text(periodLabel(t.period) + " · 第 " + t.dayOfMonth + if (t.period == "monthly") " 天" else "" +
                                    " · " + (if (t.mode == "auto") "到期自动生成账单" else "仅提醒"),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("下次：" + Dates.formatDateTime(t.nextDueAt) +
                                    (if (t.lastRunKey.isNotEmpty()) " · 上次执行：" + Dates.formatDateTime(t.lastRunAt ?: 0L) else ""),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            MoneyText(t.amount, t.type)
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("启用", style = MaterialTheme.typography.bodySmall)
                            GlassSwitch(checked = t.enabled, onCheckedChange = { v ->
                                db.upsertRecurring(t.copy(enabled = v, updatedAt = System.currentTimeMillis())); version++; state.touch()
                            })
                            Spacer(Modifier.width(8.dp))
                            Text("到期自动入账", style = MaterialTheme.typography.bodySmall)
                            GlassSwitch(checked = t.mode == "auto", onCheckedChange = { v ->
                                db.upsertRecurring(t.copy(mode = if (v) "auto" else "remind", updatedAt = System.currentTimeMillis())); version++; state.touch()
                            })
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }

    if (pickCategory) {
        val options = cats.filter { it.kind == "expense" }.map { it.name to it.id }
        ChoiceSheet(title = "选择分类", options = options, onPick = { id ->
            editing = editing?.copy(categoryId = id); pickCategory = false
        }, onDismiss = { pickCategory = false })
    }
    if (pickPeriod) {
        ChoiceSheet(title = "选择周期", options = PERIODS, onPick = { p ->
            editing = editing?.copy(period = p); pickPeriod = false
        }, onDismiss = { pickPeriod = false })
    }
    val e = editing
    if (e != null) {
        var name by remember(e.id) { mutableStateOf(e.name) }
        var amountText by remember(e.id) { mutableStateOf(if (e.amount > 0L) Money.centsToEdit(e.amount) else "") }
        var dayText by remember(e.id) { mutableStateOf(e.dayOfMonth.toString()) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (e.amount > 0L) "编辑周期任务" else "新增周期任务") },
            text = {
                LazyColumn {
                    item { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称，例如 房租") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                    item { Spacer(Modifier.height(8.dp)) }
                    item {
                        OutlinedTextField(value = amountText, onValueChange = { amountText = it },
                            label = { Text("金额（元）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                    item {
                        MenuRow("类型", if (e.type == TxnType.EXPENSE) "支出" else "收入") {
                            editing = e.copy(type = if (e.type == TxnType.EXPENSE) TxnType.INCOME else TxnType.EXPENSE)
                        }
                        MenuRow("分类", cats.firstOrNull { it.id == e.categoryId }?.name ?: "未选择") { pickCategory = true }
                        MenuRow("周期", periodLabel(e.period)) { pickPeriod = true }
                    }
                    item {
                        OutlinedTextField(value = dayText, onValueChange = { dayText = it.filter { c -> c.isDigit() } },
                            label = { Text("每月/每周第几天（1-28）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                    item {
                        Text("到期处理方式", style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ChipButton("仅提醒", e.mode == "remind") { editing = e.copy(mode = "remind") }
                            Spacer(Modifier.width(8.dp))
                            ChipButton("自动生成账单", e.mode == "auto") { editing = e.copy(mode = "auto") }
                        }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                    item {
                        Text("性质（可选）", style = MaterialTheme.typography.bodySmall)
                        NatureChoiceRow(nature = e.nature, noneLabel = "不设置") { editing = e.copy(nature = it) }
                    }
                    if (e.amount > 0L) {
                        item { Spacer(Modifier.height(8.dp)) }
                        item {
                            TextButton({
                                db.deleteRecurring(e.id); editing = null; version++; state.touch()
                            }) { Text("删除该任务") }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton({
                    val cents = Money.parseYuanToCents(amountText)
                    val day = (dayText.toIntOrNull() ?: 1).coerceIn(1, 28)
                    if (cents != null && cents > 0L && name.isNotBlank()) {
                        val base = e.copy(name = name.trim(), amount = cents, dayOfMonth = day, updatedAt = System.currentTimeMillis())
                        val due = RecurringEngine.nextDue(base.period, day, 9, 0)
                        db.upsertRecurring(base.copy(nextDueAt = if (e.amount > 0L) base.nextDueAt else due))
                        editing = null; version++; state.touch()
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton({ editing = null }) { Text("取消") } }
        )
    }
}
