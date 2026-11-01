package com.xzledger.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xzledger.app.AppState
import com.xzledger.app.XzApp
import com.xzledger.app.data.TxnFilter
import com.xzledger.app.data.TxnType
import com.xzledger.app.data.txns
import com.xzledger.app.util.Dates
import com.xzledger.app.util.Money

data class SearchFilter(
    val catOn: Boolean = false,
    val categoryIds: List<String> = emptyList(),
    val amtOn: Boolean = false,
    val minYuan: String = "",
    val maxYuan: String = "",
    val timeOn: Boolean = false,
    val startDate: String = "",
    val endDate: String = "",
    val incomeOn: Boolean = false,
    val expenseOn: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(state: AppState, nav: NavStack) {
    val db = XzApp.db
    val version = state.dataVersion
    var keyword by remember { mutableStateOf("") }
    var applied by remember { mutableStateOf(SearchFilter()) }
    var showFilter by remember { mutableStateOf(false) }
    val cats = remember(version, state.ledgerId) { db.categories(state.ledgerId) }

    val min = Money.parseYuanToCents(applied.minYuan)
    val max = Money.parseYuanToCents(applied.maxYuan)
    val types = buildList {
        if (applied.incomeOn) add(TxnType.INCOME)
        if (applied.expenseOn) add(TxnType.EXPENSE)
    }
    val results = remember(version, keyword, applied, state.ledgerId) {
        db.txns(
            TxnFilter(
                ledgerId = state.ledgerId,
                keyword = keyword,
                categoryIds = if (applied.catOn) applied.categoryIds else emptyList(),
                minAmount = if (applied.amtOn) min else null,
                maxAmount = if (applied.amtOn) max else null,
                startDate = if (applied.timeOn && applied.startDate.isNotEmpty()) applied.startDate else null,
                endDate = if (applied.timeOn && applied.endDate.isNotEmpty()) applied.endDate else null,
                types = types
            )
        )
    }
    val income = results.filter { it.type == TxnType.INCOME }.sumOf { it.amount }
    val expense = results.filter { it.type == TxnType.EXPENSE }.sumOf { it.amount } -
        results.filter { it.type == TxnType.REFUND }.sumOf { it.amount }

    Column(Modifier.fillMaxSize()) {
        TopBar("搜索", onBack = { nav.pop() }) {
            IconButton(onClick = { showFilter = true }) {
                Icon(Icons.Default.FilterList, contentDescription = "筛选")
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            SimpleSearchField(keyword, { keyword = it }, "关键词 / 商户 / 分类 / 备注 / 金额")
            if (keyword.isNotEmpty()) {
                IconButton(onClick = { keyword = "" }) { Icon(Icons.Default.Close, contentDescription = "清空") }
            }
        }
        if (hasFilter(applied)) {
            Text(
                filterSummary(applied, cats.associate { it.id to it.name }),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        Text(
            "共 " + results.size + " 笔　收入 " + Money.centsToPlain(income) + "　支出 " + Money.centsToPlain(expense) + "　结余 " + Money.centsToPlain(income - expense),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
        Text(
            "搜索范围：当前账本「" + state.currentLedger().name + "」",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        if (results.isEmpty()) EmptyHint("没有符合条件的账单")
        else LazyColumn(Modifier.fillMaxSize()) {
            items(results.size) { i ->
                val t = results[i]
                Row(
                    Modifier.fillMaxWidth().clickable { nav.push(Screen.TXN_DETAIL, t.id) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CategoryAvatar(state.category(t.categoryId)?.icon ?: (if (t.type == TxnType.TRANSFER) "🔁" else "·"))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title.ifEmpty { state.category(t.categoryId)?.name ?: "未分类" }, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            t.occurredDate + "　" + (state.account(t.accountId)?.name ?: "") + (if (t.note.isNotEmpty()) "　" + t.note else ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    MoneyText(t.amount, t.type)
                }
                Divider2()
            }
            item { Spacer(Modifier.height(140.dp)) }
        }
    }

    if (showFilter) {
        FilterSheet(
            initial = applied,
            categories = cats.map { it.id to it.name },
            onDismiss = { showFilter = false },
            onApply = { applied = it; showFilter = false }
        )
    }
}

private fun hasFilter(f: SearchFilter): Boolean =
    (f.catOn && f.categoryIds.isNotEmpty()) || (f.amtOn && (f.minYuan.isNotEmpty() || f.maxYuan.isNotEmpty())) ||
        (f.timeOn && (f.startDate.isNotEmpty() || f.endDate.isNotEmpty())) || f.incomeOn || f.expenseOn

private fun filterSummary(f: SearchFilter, catNames: Map<String, String>): String {
    val parts = ArrayList<String>()
    if (f.catOn && f.categoryIds.isNotEmpty()) parts.add("分类：" + f.categoryIds.mapNotNull { catNames[it] }.joinToString("、"))
    if (f.amtOn) parts.add("金额：" + (f.minYuan.ifEmpty { "不限" }) + " ~ " + (f.maxYuan.ifEmpty { "不限" }))
    if (f.timeOn) parts.add("时间：" + (f.startDate.ifEmpty { "不限" }) + " ~ " + (f.endDate.ifEmpty { "不限" }))
    if (f.incomeOn) parts.add("只看收入")
    if (f.expenseOn) parts.add("只看支出")
    return "已应用筛选　" + parts.joinToString("；")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(
    initial: SearchFilter,
    categories: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onApply: (SearchFilter) -> Unit
) {
    var draft by remember { mutableStateOf(initial) }
    var pickStart by remember { mutableStateOf(false) }
    var pickEnd by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val minV = Money.parseYuanToCents(draft.minYuan)
    val maxV = Money.parseYuanToCents(draft.maxYuan)
    val amtBad = draft.amtOn && draft.minYuan.isNotEmpty() && draft.maxYuan.isNotEmpty() && minV != null && maxV != null && minV > maxV
    val timeBad = draft.timeOn && draft.startDate.isNotEmpty() && draft.endDate.isNotEmpty() && draft.startDate > draft.endDate
    val valid = !amtBad && !timeBad

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("筛选", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            // 收支方向
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("只看收入", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                GlassSwitch(checked = draft.incomeOn, onCheckedChange = { draft = draft.copy(incomeOn = it) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("只看支出", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                GlassSwitch(checked = draft.expenseOn, onCheckedChange = { draft = draft.copy(expenseOn = it) })
            }
            Spacer(Modifier.height(6.dp))
            // 分类
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("按分类筛选", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                GlassSwitch(checked = draft.catOn, onCheckedChange = { draft = draft.copy(catOn = it) })
            }
            if (draft.catOn) {
                Column {
                    categories.chunked(2).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { (id, label) ->
                                val on = draft.categoryIds.contains(id)
                                Box(
                                    Modifier.weight(1f).padding(vertical = 3.dp)
                                        .background(
                                            if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
                                            RoundedCornerShape(10.dp)
                                        )
                                        .clickable {
                                            draft = draft.copy(
                                                categoryIds = if (on) draft.categoryIds - id else draft.categoryIds + id
                                            )
                                        }
                                        .padding(horizontal = 10.dp, vertical = 8.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        val look = CatIcons.look(null, label)
                                        Icon(
                                            look.icon, contentDescription = null,
                                            tint = if (on) MaterialTheme.colorScheme.primary else look.color,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                    }
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    Text("同一组内多个分类为「满足任意一项」", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(6.dp))
            // 金额
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("按金额筛选（绝对值）", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                GlassSwitch(checked = draft.amtOn, onCheckedChange = { draft = draft.copy(amtOn = it) })
            }
            if (draft.amtOn) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draft.minYuan, onValueChange = { draft = draft.copy(minYuan = it) },
                        label = { Text("下限") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    OutlinedTextField(
                        value = draft.maxYuan, onValueChange = { draft = draft.copy(maxYuan = it) },
                        label = { Text("上限") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                }
                if (amtBad) Text("金额下限不能高于上限", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(6.dp))
            // 时间
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("按时间筛选", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                GlassSwitch(checked = draft.timeOn, onCheckedChange = { draft = draft.copy(timeOn = it) })
            }
            if (draft.timeOn) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draft.startDate, onValueChange = {}, label = { Text("开始日期") },
                        readOnly = true, singleLine = true, modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = draft.endDate, onValueChange = {}, label = { Text("结束日期") },
                        readOnly = true, singleLine = true, modifier = Modifier.weight(1f)
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ pickStart = true }, modifier = Modifier.weight(1f)) { Text(if (draft.startDate.isEmpty()) "选择开始日期" else "改开始日期") }
                    TextButton({ pickEnd = true }, modifier = Modifier.weight(1f)) { Text(if (draft.endDate.isEmpty()) "选择结束日期" else "改结束日期") }
                }
                if (timeBad) Text("开始日期不能晚于结束日期", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(12.dp))
            Text("三类条件之间为「同时满足」", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                TextButton(
                    onClick = { draft = SearchFilter() },
                    modifier = Modifier.weight(1f)
                ) { Text("清空筛选") }
                Button(
                    onClick = { if (valid) onApply(draft) },
                    enabled = valid,
                    modifier = Modifier.weight(1f)
                ) { Text("确定") }
            }
            Spacer(Modifier.height(28.dp))
        }
    }

    if (pickStart) {
        val st = rememberDatePickerState(initialSelectedDateMillis = Dates.utcDayMillis(if (draft.startDate.isEmpty()) Dates.today() else draft.startDate))
        DatePickerDialog(
            onDismissRequest = { pickStart = false },
            confirmButton = {
                TextButton({
                    st.selectedDateMillis?.let { draft = draft.copy(startDate = Dates.fromUtcDayMillis(it)) }
                    pickStart = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton({ pickStart = false }) { Text("取消") } }
        ) { DatePicker(state = st) }
    }
    if (pickEnd) {
        val st = rememberDatePickerState(initialSelectedDateMillis = Dates.utcDayMillis(if (draft.endDate.isEmpty()) Dates.today() else draft.endDate))
        DatePickerDialog(
            onDismissRequest = { pickEnd = false },
            confirmButton = {
                TextButton({
                    st.selectedDateMillis?.let { draft = draft.copy(endDate = Dates.fromUtcDayMillis(it)) }
                    pickEnd = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton({ pickEnd = false }) { Text("取消") } }
        ) { DatePicker(state = st) }
    }
}
