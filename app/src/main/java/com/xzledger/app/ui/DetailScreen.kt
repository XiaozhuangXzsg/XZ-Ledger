package com.xzledger.app.ui

import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import com.xzledger.app.R
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.xzledger.app.AppState
import com.xzledger.app.XzApp
import com.xzledger.app.data.DayTotal
import com.xzledger.app.data.Nature
import com.xzledger.app.data.SettingsKeys
import com.xzledger.app.data.Txn
import com.xzledger.app.data.TxnFilter
import com.xzledger.app.data.TxnType
import com.xzledger.app.data.dailyTotals
import com.xzledger.app.data.softDeleteTxn
import com.xzledger.app.data.budgets
import com.xzledger.app.data.monthSummary
import com.xzledger.app.data.txns
import com.xzledger.app.ui.theme.expenseColor
import com.xzledger.app.ui.theme.incomeColor
import com.xzledger.app.util.Dates
import com.xzledger.app.util.Money
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DetailScreen(state: AppState, nav: NavStack) {
    val db = XzApp.db
    val version = state.dataVersion
    val ledgerId = state.ledgerId
    val month = state.month

    val summary = remember(version, ledgerId, month) { db.monthSummary(ledgerId, month) }
    // 本月总额预算（null = 未设置）。结余口径：预算 + 收入 - 净支出。
    val budgetTotal = remember(version, ledgerId, month) { db.budgets(ledgerId, month).firstOrNull { it.categoryId == null }?.amount }
    val balance = (budgetTotal ?: 0L) + summary.income - summary.netExpense
    val days: List<DayTotal> = remember(version, ledgerId, month) { db.dailyTotals(ledgerId, Dates.startOfMonth(month), Dates.endOfMonth(month)) }
    val ledgerName = remember(state.ledgerVersion, ledgerId) { state.currentLedger().name }
    val list = remember(version, ledgerId, month) {
        db.txns(
            TxnFilter(
                ledgerId = ledgerId,
                startDate = Dates.startOfMonth(month),
                endDate = Dates.endOfMonth(month)
            )
        )
    }
    val grouped = remember(list) { list.groupBy { it.occurredDate } }
    val dayTotals = remember(days) { days.associateBy { it.date } }

    var showMonthPicker by remember { mutableStateOf(false) }
    var showLedgerSheet by remember { mutableStateOf(false) }
    // 长按某一天的日期行：询问是否在这一天补记一笔（只影响这一次记账，保存后回到今天）。
    var addForDate by remember { mutableStateOf<String?>(null) }
    val anime = animeEnabled()

    Column(Modifier.fillMaxSize()) {
        // ---- 顶部工具栏 ----
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左上角头像：不加淡黄色底，直接显示桑多涅本尊；原图四周留白较多，这里放大后裁掉留白。
            Box(
                Modifier.size(40.dp)
                    .clip(CircleShape)
                    .clickable { nav.push(Screen.ABOUT) },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.mipmap.ic_launcher_foreground),
                    contentDescription = "关于猫猫记账",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().scale(1.5f)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text("猫猫记账", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
            Row(
                Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
                    .clickable { showLedgerSheet = true }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(ledgerName, style = MaterialTheme.typography.labelMedium)
                Icon(Icons.Default.ExpandMore, contentDescription = "切换账本", modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { nav.push(Screen.AI_CHAT) }) { Icon(Icons.Default.AutoAwesome, contentDescription = "AI") }
            IconButton(onClick = { nav.push(Screen.CALENDAR) }) { Icon(Icons.Default.CalendarMonth, contentDescription = "日历") }
            IconButton(onClick = { nav.push(Screen.SEARCH) }) { Icon(Icons.Default.Search, contentDescription = "搜索") }
        }

        // ---- 月度汇总 ----
        GlassPanel(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), corner = 22.dp, strong = true) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth().clickable { showMonthPicker = true },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        Dates.monthLabel(month),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Icon(Icons.Default.ExpandMore, contentDescription = "选择年月", modifier = Modifier.size(20.dp))
                    Spacer(Modifier.weight(1f))
                    Text(
                        "查看统计", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { nav.push(Screen.STATS) }
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth()) {
                    SummaryCell("收入", Money.centsToPlain(summary.income), incomeColor(), Modifier.weight(1f))
                    SummaryCell("支出", Money.centsToPlain(summary.netExpense), expenseColor(), Modifier.weight(1f))
                    SummaryCell("结余", Money.centsToPlain(balance), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "结余 = 本月预算" + (if (budgetTotal == null) "（未设置，按 0 计）" else " ¥" + Money.centsToPlain(budgetTotal)) +
                        " + 收入 - 支出（净）" + (if (summary.refund > 0) "；本月含退款 " + Money.centsToPlain(summary.refund) + "，已从支出中抵减" else "") +
                        "，与账户余额口径不同。长按某一天的日期可在当天补记一笔。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---- 看板娘桑多涅（个性化设置 → 二次元元素 里可关闭）----
        if (anime) {
            // 台词里的预算占用比例 = 净支出 − 收入（最低 0），收入抵消支出后不会再出现负数百分比。
            val lines = remember(month, summary.netExpense, summary.income, budgetTotal, list.size) {
                mascotLines(summary.netExpense, summary.income, budgetTotal, list.size)
            }
            var lineIdx by remember(month) { mutableIntStateOf(0) }
            MascotBubble(
                text = lines[lineIdx % lines.size],
                modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 6.dp),
                onClick = { lineIdx = (lineIdx + 1) % lines.size },
                // 需求：点看板娘头像 → 快捷回到当月。
                onAvatarClick = {
                    val now = Dates.monthKey(System.currentTimeMillis())
                    if (state.month != now) state.month = now
                }
            )
        }

        // ---- 每日账单 ----
        if (list.isEmpty()) {
            EmptyHint("本月还没有账单，去「记账」添加第一笔吧")
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 132.dp)
            ) {
                days.forEach { day ->
                    val dayList = grouped[day.date].orEmpty()
                    if (dayList.isEmpty()) return@forEach
                    // 每一天的账单整体装进一个半透明液态玻璃圆角框。
                    item(key = "d-" + day.date) {
                        GlassPanel(Modifier.fillMaxWidth().padding(vertical = 5.dp), corner = 22.dp) {
                            // 裁剪到卡片圆角内：左滑时行内容与露出的删除键都不会溢出到卡片外面。
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))) {
                                DayHeader(day, onLongPress = { addForDate = day.date })
                                dayList.forEachIndexed { index, txn ->
                                    // 按账单 id 绑定滑动状态：删除一笔后，其它行不会继承它的滑动位置。
                                    key(txn.id) {
                                        if (index > 0) Divider2()
                                        SwipeToDeleteRow(txn, state, nav)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showLedgerSheet) {
        val items = db.ledgers()
        ChoiceSheet(
            title = "选择账本",
            options = items.map { it.name + (if (it.id == ledgerId) "  ✓" else "") to it.id } + ("管理账本…" to "__manage__"),
            onPick = { picked ->
                if (picked == "__manage__") nav.push(Screen.LEDGERS) else state.switchLedger(picked)
            },
            onDismiss = { showLedgerSheet = false }
        )
    }

    if (showMonthPicker) {
        MonthPickerSheet(
            currentMonth = month,
            onDismiss = { showMonthPicker = false },
            onConfirm = { state.month = it; showMonthPicker = false }
        )
    }

    addForDate?.let { d ->
        ConfirmDialog(
            title = "在" + Dates.dayHeader(d) + "补记一笔？",
            text = "记账页的日期会设为 " + d + "，只对这一笔记账生效；保存后日期自动回到今天，之后的新记账不受影响。",
            confirmText = "去记一笔",
            onConfirm = {
                addForDate = null
                state.presetDate = d
                state.pendingTab = 1
            },
            onDismiss = { addForDate = null }
        )
    }
}

@Composable
private fun SummaryCell(label: String, value: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayHeader(day: DayTotal, onLongPress: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = onLongPress)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(Dates.dayHeader(day.date), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.weight(1f))
        if (day.income > 0) Text(
            "收 " + Money.centsToPlain(day.income),
            style = MaterialTheme.typography.labelSmall, color = incomeColor()
        )
        if (day.income > 0 && day.expense > 0) Spacer(Modifier.width(8.dp))
        if (day.expense > 0) Text(
            "支 " + Money.centsToPlain(day.expense - day.refund),
            style = MaterialTheme.typography.labelSmall, color = expenseColor()
        )
    }
}

@Composable
private fun TxnRow(txn: Txn, state: AppState, nav: NavStack, plain: Boolean = false) {
    val category = state.category(txn.categoryId)
    val account = state.account(txn.accountId)
    val body: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            CategoryAvatar(category?.icon ?: (if (txn.type == TxnType.INCOME) "💰" else "📌"))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    txn.title.ifEmpty { category?.name ?: if (txn.type == TxnType.TRANSFER) "转账" else "未分类" },
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (txn.type == TxnType.TRANSFER) {
                        Text("转账", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else NatureBadge(txn.nature)
                    val sub = buildString {
                        if (account != null) append(account.name)
                        if (txn.note.isNotBlank()) { if (isNotEmpty()) append(" · "); append(txn.note) }
                        if (txn.merchant.isNotBlank()) { if (isNotEmpty()) append(" · "); append(txn.merchant) }
                    }
                    if (sub.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            sub, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            MoneyText(txn.amount, txn.type, MaterialTheme.typography.titleMedium)
        }
    }
    if (plain) {
        // 已在外层液态玻璃框内，单条不再自带卡片底色。
        Box(Modifier.fillMaxWidth().clickable { nav.push(Screen.TXN_DETAIL, txn.id) }) { body() }
    } else {
        Card2(Modifier.fillMaxWidth().padding(vertical = 3.dp), onClick = { nav.push(Screen.TXN_DETAIL, txn.id) }) { body() }
    }
}

/**
 * 明细里的单条账单支持横向滑动：
 * 向左轻滑到一半会露出「删除」按钮；一直滑到最左边并保持 1 秒则直接移入回收站。
 * 两种方式都只是软删除（可在回收站恢复），并给出提示，避免误删。
 */
@Composable
private fun SwipeToDeleteRow(txn: Txn, state: AppState, nav: NavStack) {
    val db = XzApp.db
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val reveal = with(density) { 92.dp.toPx() }
    val cover = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f)
    val offset = remember { Animatable(0f) }
    var width by remember { mutableStateOf(0f) }
    var timer by remember { mutableStateOf<Job?>(null) }
    var holding by remember { mutableStateOf(false) }

    fun stopHold() {
        timer?.cancel()
        timer = null
        holding = false
    }

    fun remove() {
        stopHold()
        db.softDeleteTxn(txn.id)
        state.touch()
        Toast.makeText(context, "已移入回收站", Toast.LENGTH_SHORT).show()
    }

    fun startHold() {
        if (holding) return
        holding = true
        timer = scope.launch {
            delay(1000)
            remove()
        }
    }

    Box(Modifier.fillMaxWidth().clipToBounds().onSizeChanged { width = it.width.toFloat() }) {
        // 红色删除区：左边缘保持直角，紧贴被拖开的账单右边缘；宽度跟着滑动距离一起扩展，
        // 「删除」两个字始终居中在这块红色里（一直划到底时红色铺满整行）。
        val revealed = (-offset.value).coerceAtLeast(0f)
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(with(density) { revealed.toDp() })
                    .background(
                        expenseColor().copy(alpha = 0.16f),
                        RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp, topEnd = 16.dp, bottomEnd = 16.dp)
                    )
                    .clickable { remove() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (holding) "删除中" else "删除",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = expenseColor(),
                    maxLines = 1
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .drawBehind {
                    if (offset.value < -1f) drawRect(cover)
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (holding) {
                                scope.launch { offset.animateTo(-width) }
                            } else if (offset.value <= -reveal * 0.5f) {
                                scope.launch { offset.animateTo(-reveal) }
                            } else {
                                scope.launch { offset.animateTo(0f) }
                            }
                        },
                        onDragCancel = {
                            stopHold()
                            scope.launch { offset.animateTo(0f) }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val max = width
                            val next = (offset.value + dragAmount).coerceIn(-max, 0f)
                            scope.launch { offset.snapTo(next) }
                            if (max > 0f && -next >= max - reveal * 0.4f) startHold() else stopHold()
                        }
                    )
                }
        ) {
            TxnRow(txn, state, nav, plain = true)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthPickerSheet(currentMonth: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val currentYear = currentMonth.substring(0, 4).toIntOrNull() ?: 2026
    val currentMon = currentMonth.substring(5, 7).toIntOrNull() ?: 1
    val years = remember { (2000..2100).map { it.toString() + " 年" } }
    val months = remember { (1..12).map { it.toString() + " 月" } }
    var selYear by remember { mutableStateOf(currentYear) }
    var selMonth by remember { mutableStateOf(currentMon) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            Text(
                "选择年月", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                WheelPicker(years, currentYear - 2000, Modifier.weight(1f)) { selYear = 2000 + it }
                WheelPicker(months, currentMon - 1, Modifier.weight(1f)) { selMonth = it + 1 }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(onClick = {
                    val y = selYear.toString().padStart(4, '0')
                    val m = selMonth.toString().padStart(2, '0')
                    onConfirm(y + "-" + m)
                }) { Text("确定") }
            }
        }
    }
}
