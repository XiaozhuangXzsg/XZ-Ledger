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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xzledger.app.AppState
import com.xzledger.app.XzApp
import com.xzledger.app.data.Category
import com.xzledger.app.data.ClassifyBasis
import com.xzledger.app.data.DedupKeys
import com.xzledger.app.data.Ids
import com.xzledger.app.data.Nature
import com.xzledger.app.data.Txn
import com.xzledger.app.data.TxnStatus
import com.xzledger.app.data.TxnType
import com.xzledger.app.data.refundedAmount
import com.xzledger.app.data.refundsOf
import com.xzledger.app.data.restoreTxn
import com.xzledger.app.data.saveTxn
import com.xzledger.app.data.softDeleteTxn
import com.xzledger.app.data.txnById
import com.xzledger.app.data.txns
import com.xzledger.app.data.updateTxn
import com.xzledger.app.util.Dates
import com.xzledger.app.util.Money

/** 账单详情：查看、编辑、删除（可撤销）、记录退款、拆分。 */
@Composable
fun TxnDetailScreen(state: AppState, nav: NavStack, id: String) {
    val db = XzApp.db
    var version by remember { mutableStateOf(0) }
    val txn = remember(id, version, state.dataVersion) { db.txnById(id) }
    var confirmDel by remember { mutableStateOf(false) }
    var deletedNow by remember { mutableStateOf(false) }
    var refundOpen by remember { mutableStateOf(false) }

    if (txn == null) {
        Column(Modifier.fillMaxSize()) {
            TopBar("账单详情", onBack = { nav.pop() })
            EmptyHint("账单不存在或已被彻底删除")
        }
        return
    }

    val cat = remember(txn.categoryId) { txn.categoryId?.let { db.category(it) } }
    val nature = txn.nature

    Column(Modifier.fillMaxSize()) {
        TopBar(
            "账单详情",
            onBack = { nav.pop() },
            actions = {
                IconButton({ nav.push(Screen.TXN_EDIT, txn.id) }) { Icon(Icons.Default.Edit, contentDescription = "编辑") }
                IconButton({ confirmDel = true }) { Icon(Icons.Default.Delete, contentDescription = "删除") }
            }
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            GlassPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(typeLabel(txn.type), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    MoneyText(txn.amount, txn.type, MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        if (cat != null) {
                            CategoryAvatar(cat.icon, 22.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(cat.name, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.width(10.dp))
                        }
                        if (nature != null) NatureBadge(nature)
                    }
                    if (txn.isRefund) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "这是退款记录，按退款发生月抵减支出；原消费详情显示累计退款。",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Card2(Modifier.fillMaxWidth()) {
                Column {
                    DetailRow("日期", Dates.formatDayHuman(txn.occurredDate) + " " + Dates.timeOf(txn.occurredAt))
                    Divider2()
                    DetailRow("账户", state.account(txn.accountId)?.name ?: "未指定")
                    if (txn.type == TxnType.TRANSFER) {
                        Divider2()
                        DetailRow("转入账户", state.account(txn.toAccountId)?.name ?: "未指定")
                    }
                    Divider2()
                    DetailRow("商户", txn.merchant.ifEmpty { "—" })
                    if (txn.merchantRaw.isNotEmpty() && txn.merchantRaw != txn.merchant) {
                        Divider2()
                        DetailRow("原始商户文本", txn.merchantRaw)
                    }
                    Divider2()
                    DetailRow("备注", txn.note.ifEmpty { "—" })
                    Divider2()
                    DetailRow("账单来源", sourceLabel(txn.source.id))
                    if (txn.sourcePlatform.isNotEmpty()) {
                        Divider2()
                        DetailRow("来源平台", txn.sourcePlatform)
                    }
                    if (txn.orderNo.isNotEmpty()) {
                        Divider2()
                        DetailRow("订单编码", txn.orderNo)
                    }
                    if (txn.tradeNo.isNotEmpty()) {
                        Divider2()
                        DetailRow("交易单号", txn.tradeNo)
                    }
                    Divider2()
                    DetailRow("交易状态", statusLabel(txn.txStatus))
                    Divider2()
                    DetailRow("分类依据", basisLabel(txn.classifyBasis))
                    if (txn.dedupKey.isNotEmpty()) {
                        Divider2()
                        DetailRow("去重标识", txn.dedupKey, small = true)
                    }
                    Divider2()
                    DetailRow("记录编号", txn.id, small = true)
                }
            }

            if (!txn.isRefund && txn.type == TxnType.EXPENSE) {
                Spacer(Modifier.height(12.dp))
                val refunded = remember(txn.id, version, state.dataVersion) { db.refundedAmount(txn.id) }
                val refunds = remember(txn.id, version, state.dataVersion) { db.refundsOf(txn.id) }
                Card2(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("退款", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "累计已退款 ¥" + Money.centsToPlain(refunded) + "（共 " + refunds.size + " 笔）",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Row {
                            ChipButton("记录退款", false, onClick = { refundOpen = true })
                            Spacer(Modifier.width(8.dp))
                            ChipButton("拆分分类", false, onClick = { nav.push(Screen.SPLIT, txn.id) })
                        }
                    }
                }
            }

            if (txn.imagePaths.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Card2(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("附件图片", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Row {
                            txn.imagePaths.forEach { p ->
                                Box(Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)).padding(end = 6.dp)) {
                                    LocalImage(p, Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth()) {
                ChipButton("编辑", false, Modifier.weight(1f), onClick = { nav.push(Screen.TXN_EDIT, txn.id) })
                Spacer(Modifier.width(10.dp))
                ChipButton("删除", false, Modifier.weight(1f), onClick = { confirmDel = true })
            }
            Spacer(Modifier.height(150.dp))
        }
    }

    if (confirmDel) {
        ConfirmDialog(
            title = "删除这笔账单？",
            text = "删除后会计入回收站，可以恢复；统计与日历会立即更新。",
            confirmText = "删除",
            onConfirm = {
                db.softDeleteTxn(txn.id)
                confirmDel = false
                deletedNow = true
                state.touch()
            },
            onDismiss = { confirmDel = false }
        )
    }

    if (deletedNow) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("已移入回收站") },
            text = { Text("这笔账单已删除，列表与统计已经更新。你也可以立即撤销。") },
            confirmButton = {
                TextButton({
                    deletedNow = false
                    nav.pop()
                }) { Text("完成") }
            },
            dismissButton = {
                TextButton({
                    db.restoreTxn(txn.id)
                    deletedNow = false
                    version++
                    state.touch()
                }) { Text("撤销删除") }
            }
        )
    }

    if (refundOpen) {
        var amt by remember { mutableStateOf(Money.centsToEdit(txn.amount)) }
        var note by remember { mutableStateOf("退款") }
        val day = txn.occurredDate
        AlertDialog(
            onDismissRequest = { refundOpen = false },
            title = { Text("记录退款") },
            text = {
                Column {
                    Text("原消费 ¥" + Money.centsToPlain(txn.amount) + "，退款会关联到这笔消费，并按退款发生月抵减支出。", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = amt, onValueChange = { amt = it }, singleLine = true, label = { Text("退款金额") })
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = note, onValueChange = { note = it }, singleLine = true, label = { Text("备注") })
                }
            },
            confirmButton = {
                TextButton({
                    val cents = Money.parseYuanToCents(amt)
                    if (cents == null || cents <= 0L) {
                        // 金额非法时不写入，避免脏数据
                    } else {
                        val now = System.currentTimeMillis()
                        val r = Txn(
                            id = Ids.newId(),
                            ledgerId = txn.ledgerId,
                            type = TxnType.REFUND,
                            amount = cents,
                            nature = txn.nature,
                            categoryId = txn.categoryId,
                            accountId = txn.accountId,
                            title = "退款：" + txn.title.ifEmpty { txn.merchant },
                            merchant = txn.merchant,
                            merchantRaw = txn.merchantRaw,
                            note = note,
                            occurredDate = Dates.dayKey(now, java.util.TimeZone.getDefault()),
                            occurredAt = now,
                            source = txn.source,
                            sourcePlatform = txn.sourcePlatform,
                            orderNo = txn.orderNo,
                            tradeNo = txn.tradeNo,
                            txStatus = TxnStatus.SUCCESS,
                            refundOf = txn.id,
                            dedupKey = "refund|" + txn.id + "|" + now,
                            classifyBasis = ClassifyBasis.MANUAL
                        )
                        db.saveTxn(r)
                        refundOpen = false
                        version++
                        state.touch()
                    }
                }) { Text("保存退款") }
            },
            dismissButton = { TextButton({ refundOpen = false }) { Text("取消") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TxnEditScreen(state: AppState, nav: NavStack, id: String) {
    val db = XzApp.db
    val txn = remember(id) { db.txnById(id) }
    if (txn == null) {
        Column(Modifier.fillMaxSize()) {
            TopBar("编辑账单", onBack = { nav.pop() })
            EmptyHint("账单不存在")
        }
        return
    }
    var amount by remember { mutableStateOf(Money.centsToEdit(txn.amount)) }
    var type by remember { mutableStateOf(txn.type) }
    var categoryId by remember { mutableStateOf(txn.categoryId) }
    var nature by remember { mutableStateOf(txn.nature) }
    var accountId by remember { mutableStateOf(txn.accountId) }
    var dayKey by remember { mutableStateOf(txn.occurredDate) }
    var hour by remember { mutableStateOf(Dates.hourOf(txn.occurredAt)) }
    var minute by remember { mutableStateOf(Dates.minuteOf(txn.occurredAt)) }
    var merchant by remember { mutableStateOf(txn.merchant) }
    var note by remember { mutableStateOf(txn.note) }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var showAccount by remember { mutableStateOf(false) }
    var showNatureChoice by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showLearn by remember { mutableStateOf(false) }

    val cats = remember(state.ledgerVersion, type) {
        state.categories().filter { it.kind == if (type == TxnType.INCOME) "income" else "expense" }
    }
    val accs = state.accounts()

    Column(Modifier.fillMaxSize()) {
        TopBar("编辑账单", onBack = { nav.pop() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            GlassPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = amount, onValueChange = { amount = it }, singleLine = true,
                        label = { Text("金额（元）") }, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Row {
                        listOf(TxnType.EXPENSE, TxnType.INCOME, TxnType.TRANSFER).forEach { t ->
                            ChipButton(typeLabel(t), type == t, Modifier.weight(1f), onClick = { type = t })
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                    if (type != TxnType.TRANSFER) {
                        Spacer(Modifier.height(10.dp))
                        Text("消费性质（收入可不填）", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(6.dp))
                        Row {
                            ChipButton("未填写", nature == null, Modifier.weight(1f), onClick = { nature = null })
                            Spacer(Modifier.width(6.dp))
                            ChipButton(Nature.PERMANENT.label, nature == Nature.PERMANENT, Modifier.weight(1f), onClick = { nature = Nature.PERMANENT })
                            Spacer(Modifier.width(6.dp))
                            ChipButton(Nature.INSTANT.label, nature == Nature.INSTANT, Modifier.weight(1f), onClick = { nature = Nature.INSTANT })
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "长期消费＝永久／半永久消费；即时消费＝即时／临时消费。两者与分类是独立字段。",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            if (type != TxnType.TRANSFER) {
                SectionTitle("分类")
                LazyRow {
                    items(cats) { c ->
                        Column(
                            Modifier.padding(end = 10.dp).width(66.dp).clickable { categoryId = c.id },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CategoryAvatar(c.icon, 46.dp)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                c.name, style = MaterialTheme.typography.labelSmall,
                                color = if (categoryId == c.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            Card2(Modifier.fillMaxWidth()) {
                Column {
                    MenuRow("日期", Dates.formatDayHuman(dayKey)) { showDate = true }
                    Divider2()
                    MenuRow("时间", pad(hour) + ":" + pad(minute)) { showTime = true }
                    Divider2()
                    MenuRow("账户", state.account(accountId)?.name ?: "未指定") { showAccount = true }
                    Divider2()
                    OutlinedTextField(
                        value = merchant, onValueChange = { merchant = it }, singleLine = true,
                        label = { Text("商户") }, modifier = Modifier.fillMaxWidth().padding(8.dp)
                    )
                    OutlinedTextField(
                        value = note, onValueChange = { note = it },
                        label = { Text("备注") }, modifier = Modifier.fillMaxWidth().padding(8.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                ChipButton("保存修改", true, Modifier.weight(1f), onClick = {
                    val cents = Money.parseYuanToCents(amount)
                    if (cents == null || cents <= 0L) {
                        message = "金额必须大于 0"
                    } else if (type != TxnType.TRANSFER && categoryId == null) {
                        message = "请选择分类"
                    } else if (type == TxnType.TRANSFER && (accountId == null || accountId == txn.toAccountId)) {
                        message = "转账需要两个不同的账户"
                    } else {
                        val newAt = Dates.toMillis(
                            dayKey.substring(0, 4).toInt(), dayKey.substring(5, 7).toInt() - 1, dayKey.substring(8, 10).toInt(), hour, minute
                        )
                        val changed = categoryId != txn.categoryId || nature != txn.nature
                        val out = db.updateTxn(
                            txn.copy(
                                type = type, amount = cents, categoryId = categoryId, nature = nature,
                                accountId = accountId, occurredDate = dayKey, occurredAt = newAt,
                                merchant = merchant, merchantRaw = txn.merchantRaw.ifEmpty { merchant },
                                note = note,
                                classifyBasis = if (changed) ClassifyBasis.MANUAL else txn.classifyBasis,
                                classifyRuleId = if (changed) null else txn.classifyRuleId
                            )
                        )
                        if (!out.ok) {
                            message = out.message.ifEmpty { "保存失败" }
                        } else {
                            state.touch()
                            if (changed) showLearn = true else nav.pop()
                        }
                    }
                })
            }
            if (message != null) {
                Spacer(Modifier.height(8.dp))
                Text(message ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(150.dp))
        }
    }

    if (showAccount) {
        ChoiceSheet(
            title = "选择账户",
            options = accs.map { it.name to it.id },
            onPick = { accountId = it },
            onDismiss = { showAccount = false }
        )
    }

    if (showDate) {
        val dp = rememberDatePickerState(initialSelectedDateMillis = Dates.utcDayMillis(dayKey))
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton({
                    dp.selectedDateMillis?.let { dayKey = Dates.fromUtcDayMillis(it) }
                    showDate = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton({ showDate = false }) { Text("取消") } }
        ) { DatePicker(state = dp) }
    }

    if (showTime) {
        val tp = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTime = false },
            title = { Text("选择时间") },
            text = { TimePicker(state = tp) },
            confirmButton = {
                TextButton({
                    hour = tp.hour; minute = tp.minute; showTime = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton({ showTime = false }) { Text("取消") } }
        )
    }

    if (showLearn) {
        LearningChoiceDialog(
            state = state,
            ledgerId = txn.ledgerId,
            txnId = txn.id,
            merchantRaw = merchant,
            note = note,
            accountId = accountId,
            type = type,
            newCategoryId = categoryId,
            newNature = nature,
            oldCategoryId = txn.categoryId,
            oldNature = txn.nature,
            onDone = { nav.pop(); state.touch() },
            onSkip = { nav.pop() }
        )
    }
}

@Composable
fun DetailRow(label: String, value: String, small: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = if (small) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End
        )
    }
}

fun typeLabel(t: TxnType): String = when (t) {
    TxnType.EXPENSE -> "支出"
    TxnType.INCOME -> "收入"
    TxnType.TRANSFER -> "转账"
    TxnType.REFUND -> "退款"
}

fun statusLabel(s: TxnStatus): String = when (s) {
    TxnStatus.SUCCESS -> "交易成功"
    TxnStatus.PENDING -> "进行中（不计入真实收支）"
    TxnStatus.CLOSED -> "已关闭（不计入真实收支）"
    TxnStatus.REFUNDED -> "已全额退款"
    TxnStatus.PARTIAL_REFUND -> "部分退款"
}

fun basisLabel(b: ClassifyBasis): String = when (b) {
    ClassifyBasis.MANUAL -> "用户手动选择"
    ClassifyBasis.RULE -> "依据你的个人规则"
    ClassifyBasis.HISTORY -> "依据历史修改习惯"
    ClassifyBasis.AI -> "AI 建议"
    ClassifyBasis.DEFAULT -> "默认分类"
}

fun sourceLabel(s: String): String = when (s) {
    "manual" -> "手动记账"
    "ai_chat" -> "AI 聊天记账"
    "import_csv" -> "CSV 导入"
    "import_xlsx" -> "Excel 导入"
    "import_pdf" -> "PDF 导入"
    "ocr" -> "图片识别"
    "recurring" -> "周期记账"
    "sync" -> "云同步"
    else -> s
}

fun pad(v: Int): String = if (v < 10) "0" + v else v.toString()
