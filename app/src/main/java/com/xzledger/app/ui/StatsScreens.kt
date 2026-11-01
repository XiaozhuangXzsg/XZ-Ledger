package com.xzledger.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xzledger.app.XzApp
import com.xzledger.app.AppState
import com.xzledger.app.data.Budget
import com.xzledger.app.data.MerchantNormalizer
import com.xzledger.app.data.SettingsKeys
import com.xzledger.app.data.Nature
import com.xzledger.app.data.Txn
import com.xzledger.app.data.TxnFilter
import com.xzledger.app.data.TxnType
import com.xzledger.app.data.accountBalance
import com.xzledger.app.data.budgets
import com.xzledger.app.data.categorySpending
import com.xzledger.app.data.deleteBudget
import com.xzledger.app.data.monthSummary
import com.xzledger.app.data.natureSpending
import com.xzledger.app.data.summarize
import com.xzledger.app.data.txns
import com.xzledger.app.data.upsertBudget
import com.xzledger.app.util.Dates
import com.xzledger.app.util.Money
import com.xzledger.app.ui.theme.expenseColor
import com.xzledger.app.ui.theme.incomeColor
import com.xzledger.app.ui.theme.refundColor

/** 横向比例条：用于分类、性质、月份分布。 */
/** 用于统计展示的商户名（规范化文本，保留原始文本不合并不同商户）。 */
fun displayMerchant(t: Txn): String =
    MerchantNormalizer.normalize(if (t.merchantRaw.isNotEmpty()) t.merchantRaw else t.merchant)

@Composable
fun BarRow(label: String, value: Long, max: Long, color: Color, trailing: String? = null) {
    val frac = if (max <= 0L) 0f else (value.toFloat() / max.toFloat()).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(trailing ?: Money.centsToPlain(value), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (frac > 0f) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(color))
            }
        }
    }
}

@Composable
private fun MonthSwitcher(month: String, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton({ onChange(Dates.shiftMonth(month, -1)) }) { Icon(Icons.Filled.ChevronLeft, contentDescription = "上一月") }
        Text(Dates.monthLabel(month), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        IconButton({ onChange(Dates.shiftMonth(month, 1)) }) { Icon(Icons.Filled.ChevronRight, contentDescription = "下一月") }
    }
}

@Composable
private fun StatLine(label: String, value: String, color: Color? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
            color = color ?: MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun StatsScreen(state: AppState, nav: NavStack) {
    var month by remember { mutableStateOf(state.month) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val swipeThreshold = with(density) { 64.dp.toPx() }
    var dragAccum by remember { mutableStateOf(0f) }
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "月度统计", onBack = { nav.pop() })
        MonthSwitcher(month) { month = it }
        Text("左右滑动可切换月份", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, bottom = 4.dp))
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragAccum = 0f },
                    onHorizontalDrag = { _, delta -> dragAccum += delta },
                    onDragEnd = {
                        if (kotlin.math.abs(dragAccum) > swipeThreshold) {
                            month = Dates.shiftMonth(month, if (dragAccum < 0) 1 else -1)
                        }
                        dragAccum = 0f
                    },
                    onDragCancel = { dragAccum = 0f }
                )
            }
        ) {
            // 需求：月度统计可左右滑动平滑切月。
            AnimatedContent(
                targetState = month,
                transitionSpec = {
                    val forward = targetState > initialState
                    (slideInHorizontally(animationSpec = tween(320)) { if (forward) it / 3 else -it / 3 } +
                        fadeIn(animationSpec = tween(240))) togetherWith
                        (slideOutHorizontally(animationSpec = tween(320)) { if (forward) -it / 3 else it / 3 } +
                            fadeOut(animationSpec = tween(180)))
                },
                label = "monthStats"
            ) { m -> MonthStats(state, m) }
        }
    }
}

@Composable
private fun MonthStats(state: AppState, month: String) {
    val db = XzApp.db
    val cats = remember(state.dataVersion) { db.categories(state.ledgerId) }
    val sum = remember(month, state.dataVersion) { db.monthSummary(state.ledgerId, month) }
    val byCat = remember(month, state.dataVersion) { db.categorySpending(state.ledgerId, month) }
    val byNature = remember(month, state.dataVersion) {
        db.natureSpending(state.ledgerId, month + "-01", Dates.endOfMonth(month))
    }
    val monthTxns = remember(month, state.dataVersion) {
        db.txns(TxnFilter(ledgerId = state.ledgerId, startDate = month + "-01", endDate = Dates.endOfMonth(month), limit = 5000))
    }
    val topMerchants = remember(monthTxns) {
        val m = HashMap<String, Long>()
        monthTxns.forEach { t ->
            if (t.affectsIncomeExpense && !t.isRefund) {
                val k = displayMerchant(t)
                if (k.isNotEmpty()) m[k] = (m[k] ?: 0L) + t.amount
            }
        }
        m.entries.sortedByDescending { it.value }.take(6)
    }
    val catRows = remember(byCat, cats) {
        byCat.entries.filter { it.value > 0L }
            .sortedByDescending { it.value }
            .map { e -> (cats.firstOrNull { it.id == e.key }?.name ?: "未分类") to e.value }
            .take(12)
    }
    val maxCat = catRows.maxOfOrNull { it.second } ?: 0L

        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            item {
                GlassPanel(contentPadding = PaddingValues(16.dp)) {
                    Text("本月汇总", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    StatLine("收入", Money.centsToPlain(sum.income), incomeColor())
                    StatLine("支出（毛额）", Money.centsToPlain(sum.expense), expenseColor())
                    StatLine("退款", Money.centsToPlain(sum.refund), refundColor())
                    StatLine("净支出（支出-退款）", Money.centsToPlain(sum.netExpense), expenseColor())
                    Divider2()
                    StatLine("月度结余（收入-净支出）", Money.centsToPlain(sum.balance),
                        if (sum.balance >= 0) incomeColor() else expenseColor())
                    Text("共 " + sum.count + " 笔；转账、未完成与已关闭交易不计入收支。口径与明细页一致。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                GlassPanel(contentPadding = PaddingValues(16.dp)) {
                    Text("消费性质（长期／即时）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    val maxN = byNature.values.maxOrNull() ?: 0L
                    val permanent = byNature[Nature.PERMANENT.id] ?: 0L
                    val instant = byNature[Nature.INSTANT.id] ?: 0L
                    val unknown = byNature["unknown"] ?: 0L
                    val totalN = permanent + instant + unknown
                    BarRow(Nature.PERMANENT.label + "（永久／半永久）", permanent, maxN, MaterialTheme.colorScheme.primary,
                        if (totalN > 0) Money.centsToPlain(permanent) + " · " + (permanent * 100 / totalN) + "%" else null)
                    BarRow(Nature.INSTANT.label + "（即时／临时）", instant, maxN, expenseColor(), null)
                    if (unknown > 0) BarRow("未标注性质", unknown, maxN, MaterialTheme.colorScheme.outline)
                }
            }
            item {
                GlassPanel(contentPadding = PaddingValues(16.dp)) {
                    Text("分类分布（净额）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (catRows.isEmpty()) EmptyHint("本月没有支出记录")
                    catRows.forEach { (name, v) -> BarRow(name, v, maxCat, MaterialTheme.colorScheme.primary) }
                    Text("统计口径：该分类支出减去该分类退款。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                GlassPanel(contentPadding = PaddingValues(16.dp)) {
                    Text("主要消费商户", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (topMerchants.isEmpty()) EmptyHint("本月没有可统计的商户")
                    val maxM = topMerchants.maxOfOrNull { it.value } ?: 0L
                    topMerchants.forEach { e -> BarRow(e.key, e.value, maxM, MaterialTheme.colorScheme.tertiary) }
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
}

@Composable
fun AnnualScreen(state: AppState, nav: NavStack) {
    val db = XzApp.db
    var year by remember { mutableStateOf(Dates.today().substring(0, 4).toInt()) }
    val y = year.toString().padStart(4, '0')
    val start = y + "-01-01"
    val end = y + "-12-31"
    val sum = remember(year, state.dataVersion) { db.summarize(state.ledgerId, start, end) }
    val yearTxns = remember(year, state.dataVersion) {
        db.txns(TxnFilter(ledgerId = state.ledgerId, startDate = start, endDate = end, limit = 20000))
    }
    val cats = remember(state.dataVersion) { db.categories(state.ledgerId) }
    val months = remember(year, state.dataVersion) {
        (1..12).map { m ->
            val key = y + "-" + (if (m < 10) "0" + m else "" + m)
            key to db.summarize(state.ledgerId, key + "-01", Dates.endOfMonth(key))
        }
    }
    val byCat = remember(yearTxns) {
        val m = HashMap<String, Long>()
        yearTxns.forEach { t ->
            if (t.type == TxnType.EXPENSE || t.type == TxnType.REFUND) {
                val k = t.categoryId ?: ""
                val cur = m[k] ?: 0L
                m[k] = if (t.type == TxnType.REFUND) cur - t.amount else cur + t.amount
            }
        }
        m.entries.filter { it.value > 0L }.sortedByDescending { it.value }
            .map { (cats.firstOrNull { c -> c.id == it.key }?.name ?: "未分类") to it.value }
    }
    val byNature = remember(yearTxns) {
        val m = HashMap<String, Long>()
        yearTxns.forEach { t ->
            if ((t.type == TxnType.EXPENSE || t.type == TxnType.REFUND) && t.affectsIncomeExpense) {
                val k = t.nature?.id ?: "unknown"
                val cur = m[k] ?: 0L
                m[k] = if (t.type == TxnType.REFUND) cur - t.amount else cur + t.amount
            }
        }
        m
    }
    val topMerchants = remember(yearTxns) {
        val m = HashMap<String, Long>()
        yearTxns.forEach { t ->
            if (t.affectsIncomeExpense && !t.isRefund) {
                val k = displayMerchant(t)
                if (k.isNotEmpty()) m[k] = (m[k] ?: 0L) + t.amount
            }
        }
        m.entries.sortedByDescending { it.value }.take(8)
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "年度账单", onBack = { nav.pop() })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ year -= 1 }) { Icon(Icons.Filled.ChevronLeft, contentDescription = "上一年") }
                    Text(y + " 年", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton({ year += 1 }) { Icon(Icons.Filled.ChevronRight, contentDescription = "下一年") }
                }
            }
            item {
                GlassPanel(contentPadding = PaddingValues(16.dp)) {
                    Text("全年汇总", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    StatLine("收入", Money.centsToPlain(sum.income), incomeColor())
                    StatLine("支出（毛额）", Money.centsToPlain(sum.expense), expenseColor())
                    StatLine("退款", Money.centsToPlain(sum.refund), refundColor())
                    StatLine("净支出", Money.centsToPlain(sum.netExpense), expenseColor())
                    Divider2()
                    StatLine("年度结余", Money.centsToPlain(sum.balance), if (sum.balance >= 0) incomeColor() else expenseColor())
                    Text("共 " + sum.count + " 笔。转账、未完成与已关闭交易不计入。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                GlassPanel(contentPadding = PaddingValues(16.dp)) {
                    Text("月度趋势（支出净额）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    val maxM = months.maxOfOrNull { it.second.netExpense } ?: 0L
                    months.forEach { (key, s) ->
                        val label = key.substring(5) + " 月"
                        BarRow(label, s.netExpense, maxM, MaterialTheme.colorScheme.primary,
                            Money.centsToPlain(s.netExpense) + " / 收 " + Money.centsToPlain(s.income))
                    }
                }
            }
            item {
                GlassPanel(contentPadding = PaddingValues(16.dp)) {
                    Text("消费性质占比", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    val permanent = byNature[Nature.PERMANENT.id] ?: 0L
                    val instant = byNature[Nature.INSTANT.id] ?: 0L
                    val unknown = byNature["unknown"] ?: 0L
                    val totalN = permanent + instant + unknown
                    val maxN = maxOf(permanent, instant, unknown)
                    BarRow(Nature.PERMANENT.label, permanent, maxN, MaterialTheme.colorScheme.primary,
                        if (totalN > 0) Money.centsToPlain(permanent) + " · " + (permanent * 100 / totalN) + "%" else null)
                    BarRow(Nature.INSTANT.label, instant, maxN, expenseColor(),
                        if (totalN > 0) Money.centsToPlain(instant) + " · " + (instant * 100 / totalN) + "%" else null)
                    if (unknown > 0) BarRow("未标注", unknown, maxN, MaterialTheme.colorScheme.outline)
                }
            }
            item {
                GlassPanel(contentPadding = PaddingValues(16.dp)) {
                    Text("分类分布（净额）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (byCat.isEmpty()) EmptyHint("本年度没有支出记录")
                    val maxC = byCat.maxOfOrNull { it.second } ?: 0L
                    byCat.take(14).forEach { (name, v) -> BarRow(name, v, maxC, MaterialTheme.colorScheme.secondary) }
                }
            }
            item {
                GlassPanel(contentPadding = PaddingValues(16.dp)) {
                    Text("主要消费商户", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (topMerchants.isEmpty()) EmptyHint("本年度没有可统计的商户")
                    val maxM = topMerchants.maxOfOrNull { it.value } ?: 0L
                    topMerchants.forEach { e -> BarRow(e.key, e.value, maxM, MaterialTheme.colorScheme.tertiary) }
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

@Composable
fun BudgetScreen(state: AppState, nav: NavStack) {
    val db = XzApp.db
    var month by remember { mutableStateOf(state.month) }
    var version by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<Budget?>(null) }
    var creating by remember { mutableStateOf<String?>(null) }
    var amountText by remember { mutableStateOf("") }
    // 总额预算是否同时写为「每月默认」的开关（默认不勾选：改本月不影响其它月份）。
    var asDefault by remember { mutableStateOf(false) }

    // 需求：总额预算与分类预算都要能随时修改。统一处理「已有就改、没有才新建」，
    // 避免再次点「+」时用新 UUID 插出第二条总额预算。
    fun editBudget(exist: Budget?, categoryId: String?, prefill: Long = 0L, setDefault: Boolean = false) {
        editing = exist ?: Budget(
            id = java.util.UUID.randomUUID().toString(), ledgerId = state.ledgerId,
            month = if (categoryId == null) month else "ALL", categoryId = categoryId, amount = 0L
        )
        amountText = when {
            exist != null -> Money.centsToEdit(exist.amount)
            prefill > 0L -> Money.centsToEdit(prefill)
            else -> ""
        }
        asDefault = setDefault
    }

    val cats = remember(state.dataVersion) { db.categories(state.ledgerId, "expense") }
    val list = remember(month, state.dataVersion, version) {
        db.budgets(state.ledgerId, month).filter { !it.deleted }.sortedBy { it.categoryId == null }
    }
    val sum = remember(month, state.dataVersion, version) { db.monthSummary(state.ledgerId, month) }
    val byCat = remember(month, state.dataVersion, version) { db.categorySpending(state.ledgerId, month) }
    // 总预算：优先取本月单独设置的那条；没有就沿用「每月默认」（month = ALL 的模板行）。
    val monthRow = list.firstOrNull { it.categoryId == null && it.month == month }
    val autoRow = list.firstOrNull { it.categoryId == null && it.month == "ALL" }
    val totalBudget = monthRow?.amount ?: autoRow?.amount ?: 0L
    val inherited = monthRow == null && autoRow != null
    // 已用 = 支出（净）− 收入，最低按 0 计：收入先抵消支出，剩下的才动预算。
    val used = (sum.netExpense - sum.income).coerceAtLeast(0L)
    val alertOn = XzApp.store.bool(com.xzledger.app.data.SettingsKeys.BUDGET_ALERT, true)

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "预算", onBack = { nav.pop() }) {
            IconButton({
                creating = if (list.any { it.categoryId == null }) "__cat__" else ""
            }) { Icon(Icons.Filled.Add, contentDescription = "新增预算") }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            item { MonthSwitcher(month) { month = it } }
            item {
                GlassPanel(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                        .clickable { editBudget(monthRow, null, autoRow?.amount ?: 0L) },
                    contentPadding = PaddingValues(16.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("本月总预算", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f))
                        if (inherited && totalBudget > 0L) {
                            Text("沿用每月默认", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (totalBudget <= 0L) {
                        EmptyHint("尚未设置总预算（点按这里设置）")
                    } else {
                        val pct = (used * 100 / totalBudget).toInt()
                        BarRow("已用 " + Money.centsToPlain(used) + " / 预算 " + Money.centsToPlain(totalBudget),
                            used, totalBudget, if (used > totalBudget) expenseColor() else MaterialTheme.colorScheme.primary, pct.toString() + "%")
                        if (alertOn && pct >= 80) {
                            Text(
                                if (used > totalBudget) "已超出本月总预算" else "已接近本月总预算（" + pct + "%）",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (used > totalBudget) expenseColor() else MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    // 需求：总预算可一键设为每月默认，同时每个月仍能单独改。
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("每月自动沿用", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                when {
                                    autoRow != null && inherited -> "本月未单独设置，沿用每月默认 ¥" + Money.centsToPlain(autoRow.amount)
                                    autoRow != null -> "其它月份未单独设置时沿用 ¥" + Money.centsToPlain(autoRow.amount)
                                    else -> "打开后把本月金额写为每月默认，之后的新月份自动套用"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        GlassSwitch(checked = autoRow != null, onCheckedChange = { on ->
                            val templateId = SettingsKeys.BUDGET_AUTO_ID_PREFIX + state.ledgerId
                            if (on) {
                                if (totalBudget > 0L) {
                                    db.upsertBudget(Budget(id = templateId, ledgerId = state.ledgerId, month = "ALL",
                                        categoryId = null, amount = totalBudget, updatedAt = System.currentTimeMillis()))
                                    version++; state.touch()
                                } else {
                                    editBudget(monthRow, null, 0L, true)
                                }
                            } else {
                                autoRow?.let { db.deleteBudget(it.id); version++; state.touch() }
                            }
                        })
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("点按卡片可修改或删除；已用 = 支出（净）− 收入，收入先抵消支出，转账不计入。桌面小组件的「预算剩余」同样按结余算：预算 + 本月结余（收入-净支出）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { SectionTitle("分类预算") }
            if (list.none { it.categoryId != null }) {
                item { EmptyHint("尚未设置分类预算") }
            }
            items(list.filter { it.categoryId != null }) { b ->
                val cat = cats.firstOrNull { it.id == b.categoryId }
                val used = byCat[b.categoryId ?: ""] ?: 0L
                Card2(onClick = {
                    editing = b; amountText = Money.centsToEdit(b.amount)
                }) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        CategoryAvatar(cat?.icon ?: "📦")
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(cat?.name ?: "未知分类", style = MaterialTheme.typography.bodyLarge)
                            Text("已用 " + Money.centsToPlain(used) + " / " + Money.centsToPlain(b.amount),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text((used * 100 / b.amount.coerceAtLeast(1)).toString() + "%",
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                            color = if (used > b.amount) expenseColor() else MaterialTheme.colorScheme.onSurface)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
    if (creating != null) {
        val kind = creating
        val options = ArrayList<Pair<String, String>>()
        options.add("总额预算" to "")
        cats.forEach { options.add(it.name to it.id) }
        ChoiceSheet(title = "选择预算范围", options = options, onPick = { id ->
            creating = null
            val cid = if (id.isEmpty()) null else id
            editBudget(list.firstOrNull { it.categoryId == cid }, cid)
        }, onDismiss = { creating = null })
    }
    val e = editing
    if (e != null) {
        AlertDialog(onDismissRequest = { editing = null },
            title = { Text(if (e.amount <= 0L) "设置预算" else "修改预算") },
            text = {
                Column {
                    Text(if (e.categoryId == null) "本月总预算（" + month + "）" else "分类预算（长期有效）",
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = amountText, onValueChange = { amountText = it },
                        label = { Text("金额（元）") }, singleLine = true)
                    if (e.categoryId == null) {
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("同时设为每月默认", style = MaterialTheme.typography.bodyMedium)
                                Text("打开后其它月份自动套用这个金额；不打开则只改本月",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            GlassSwitch(checked = asDefault, onCheckedChange = { asDefault = it })
                        }
                    }
                    if (e.amount > 0L) {
                        Spacer(Modifier.height(8.dp))
                        TextButton({ db.deleteBudget(e.id); editing = null; version++; state.touch() }) { Text("删除该预算") }
                    }
                }
            },
            confirmButton = {
                TextButton({
                    val cents = Money.parseYuanToCents(amountText)
                    if (cents != null && cents > 0L) {
                        val now = System.currentTimeMillis()
                        db.upsertBudget(e.copy(amount = cents, month = if (e.categoryId == null) month else "ALL", updatedAt = now))
                        if (e.categoryId == null && asDefault) {
                            db.upsertBudget(Budget(id = SettingsKeys.BUDGET_AUTO_ID_PREFIX + state.ledgerId,
                                ledgerId = state.ledgerId, month = "ALL", categoryId = null,
                                amount = cents, updatedAt = now))
                        }
                        editing = null; version++; state.touch()
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton({ editing = null }) { Text("取消") } })
    }
}
