package com.xzledger.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.xzledger.app.AppState
import com.xzledger.app.XzApp
import com.xzledger.app.data.SettingsKeys
import com.xzledger.app.data.TxnFilter
import com.xzledger.app.data.TxnType
import com.xzledger.app.data.dailyTotals
import com.xzledger.app.data.txns
import com.xzledger.app.util.Dates
import com.xzledger.app.util.Money
import com.xzledger.app.util.SystemCalendar

/**
 * 内部收支日历：完全由账单日期生成。系统日历读权限仅用于可选的系统日程关联。
 */
@Composable
fun CalendarScreen(state: AppState, nav: NavStack) {
    val db = XzApp.db
    val ctx = LocalContext.current
    val store = state.store
    val version = state.dataVersion
    var month by remember { mutableStateOf(state.month) }
    var selected by remember { mutableStateOf(Dates.today()) }
    var dragAcc by remember { mutableStateOf(0f) }
    var showRationale by remember { mutableStateOf(false) }
    var sysEvents by remember { mutableStateOf(listOf<String>()) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        store.setBool(SettingsKeys.CALENDAR_READ, granted)
        if (!granted) sysEvents = emptyList()
    }

    LaunchedEffect(Unit) {
        if (!store.bool(SettingsKeys.CALENDAR_PROMPTED)) showRationale = true
    }

    val totals = remember(version, month, state.ledgerId) {
        db.dailyTotals(state.ledgerId, Dates.startOfMonth(month), Dates.endOfMonth(month))
    }
    val expenseByDay: Map<String, Long> = totals.associate { it.date to (it.expense - it.refund) }
    val incomeByDay: Map<String, Long> = totals.associate { it.date to it.income }
    val monthExpense = totals.sumOf { it.expense - it.refund }
    val monthIncome = totals.sumOf { it.income }

    val dayTxns = remember(version, selected, state.ledgerId) {
        db.txns(TxnFilter(ledgerId = state.ledgerId, startDate = selected, endDate = selected))
    }

    LaunchedEffect(selected) {
        sysEvents = if (SystemCalendar.hasPermission(ctx)) SystemCalendar.eventsOn(ctx, selected) else emptyList()
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("日历", onBack = { nav.pop() }) {
            val granted = SystemCalendar.hasPermission(ctx)
            TextButton(onClick = {
                if (granted) {
                    sysEvents = SystemCalendar.eventsOn(ctx, selected)
                } else showRationale = true
            }) {
                Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (granted) "系统日程" else "关联日程", style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
        Column(Modifier.fillMaxWidth().weight(1f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { month = Dates.shiftMonth(month, -1) }) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = "上个月")
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(Dates.monthLabel(month), style = MaterialTheme.typography.titleMedium)
                    Text(
                        "收入 " + Money.centsToPlain(monthIncome) + "  支出 " + Money.centsToPlain(monthExpense) + "  结余 " + Money.centsToPlain(monthIncome - monthExpense),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { month = Dates.shiftMonth(month, 1) }) {
                    Icon(Icons.Default.ChevronRight, contentDescription = "下个月")
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach { w ->
                    Text(
                        w, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            // 固定高度的月视图：上下拖动切换月份，避免切换时数字错位。
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(GRID_HEIGHT)
                    // 裁剪成一屏：上下切换月份时两个月份的网格只能各自出现一半，不会同时在格子里叠数字。
                    .clipToBounds()
                    .pointerInput(month) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (dragAcc > 90f) month = Dates.shiftMonth(month, -1)
                                else if (dragAcc < -90f) month = Dates.shiftMonth(month, 1)
                                dragAcc = 0f
                            },
                            onVerticalDrag = { _, d -> dragAcc += d }
                        )
                    }
            ) {
                AnimatedContent(
                    targetState = month,
                    transitionSpec = {
                        // 整屏高度滑动 + 外层裁剪：新旧月份互不重叠，视觉上像翻页。
                        val spec = tween<IntOffset>(260)
                        if (Dates.shiftMonth(targetState, 1) == initialState) {
                            slideInVertically(spec) { -it } togetherWith slideOutVertically(spec) { it }
                        } else {
                            slideInVertically(spec) { it } togetherWith slideOutVertically(spec) { -it }
                        }
                    },
                    label = "calendar"
                ) { m ->
                    MonthGrid(
                        month = m,
                        expenseByDay = expenseByDay,
                        selected = selected,
                        onSelect = { selected = it }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "上下滑动切换月份；日期下方为该日支出（已按退款口径抵减）。内部日历不依赖系统日历。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(6.dp))
            DaySummary(
                state = state,
                dayKey = selected,
                txns = dayTxns,
                income = incomeByDay[selected] ?: 0L,
                expense = expenseByDay[selected] ?: 0L,
                sysEvents = sysEvents,
                onEntry = {
                    state.presetDate = selected
                    state.pendingTab = 1
                },
                onOpenTxn = { id -> nav.push(Screen.TXN_DETAIL, id) }
            )
        }
    }

    if (showRationale) {
        LaunchedEffect(Unit) { state.store.setBool(SettingsKeys.CALENDAR_PROMPTED, true) }
        ConfirmDialog(
            title = "关联系统日程（可选）",
            text = "接下来可以向系统申请「读取日历」权限，仅用于在日历页显示你已有的系统日程，方便对照。拒绝也不影响记账与内部收支日历的使用，我们不会反复弹出申请。",
            confirmText = "同意申请",
            onConfirm = {
                showRationale = false
                permLauncher.launch(Manifest.permission.READ_CALENDAR)
            },
            onDismiss = { showRationale = false }
        )
    }
}

private val CELL_HEIGHT = 46.dp
private val GRID_HEIGHT = CELL_HEIGHT * 6

/** 单元格内的金额要够短，否则会在小格子里被裁成半个数字。 */
private fun shortAmount(cents: Long): String {
    val yuan = cents / 100
    return when {
        yuan >= 10000 -> (yuan / 1000).toString() + "k"
        yuan >= 100 -> yuan.toString()
        cents % 100 == 0L -> yuan.toString()
        else -> yuan.toString() + "." + ((cents % 100) / 10)
    }
}

@Composable
private fun MonthGrid(
    month: String,
    expenseByDay: Map<String, Long>,
    selected: String,
    onSelect: (String) -> Unit
) {
    val first = month + "-01"
    val offset = Dates.isoDayOfWeek(first) - 1
    val days = Dates.daysInMonth(month)
    val today = Dates.today()
    val cells = ArrayList<String?>()
    repeat(offset) { cells.add(null) }
    for (d in 1..days) cells.add(month + "-" + (if (d < 10) "0" + d else d.toString()))
    while (cells.size < 42) cells.add(null)
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    Box(Modifier.weight(1f).height(CELL_HEIGHT).padding(2.dp)) {
                        if (day != null) {
                            val isToday = day == today
                            val isSelected = day == selected
                            val exp = expenseByDay[day] ?: 0L
                            Column(
                                Modifier.fillMaxSize()
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent,
                                        RoundedCornerShape(10.dp)
                                    )
                                    .then(
                                        if (isToday) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                                        else Modifier
                                    )
                                    .clickable { onSelect(day) },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    day.substring(8, 10).trimStart('0'),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    softWrap = false,
                                    fontWeight = if (isToday || exp != 0L) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    if (exp == 0L) "" else shortAmount(exp),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DaySummary(
    state: AppState,
    dayKey: String,
    txns: List<com.xzledger.app.data.Txn>,
    income: Long,
    expense: Long,
    sysEvents: List<String>,
    onEntry: () -> Unit,
    onOpenTxn: (String) -> Unit
) {
    Card2(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    Dates.dayHeader(dayKey) + "（" + dayKey + "）",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall
                )
                Button(onClick = onEntry) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("在该日记账", maxLines = 1)
                }
            }
            // 汇总单独占一行，避免与右侧按钮挤在一起换行。
            Text(
                "收入 " + Money.centsToPlain(income) + "　支出 " + Money.centsToPlain(expense) + "　结余 " + Money.centsToPlain(income - expense),
                Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (sysEvents.isNotEmpty()) {
                Text(
                    "系统日程：" + sysEvents.joinToString("、"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            if (txns.isEmpty()) {
                EmptyHint("这一天还没有账单")
            } else {
                LazyColumn(Modifier.height((txns.size.coerceAtMost(4) * 56).dp)) {
                    items(txns.size) { i ->
                        val t = txns[i]
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenTxn(t.id) }.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CategoryAvatar(state.category(t.categoryId)?.icon ?: (if (t.type == TxnType.TRANSFER) "🔁" else "·"))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t.title.ifEmpty { state.category(t.categoryId)?.name ?: "未分类" }, style = MaterialTheme.typography.bodyMedium)
                                if (t.note.isNotEmpty()) Text(t.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            MoneyText(t.amount, t.type)
                        }
                        Divider2()
                    }
                }
            }
            Spacer(Modifier.height(150.dp))
        }
    }
}
