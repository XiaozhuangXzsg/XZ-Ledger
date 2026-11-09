package com.xzledger.app.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xzledger.app.AppState
import com.xzledger.app.XzApp
import com.xzledger.app.data.AiImport
import com.xzledger.app.data.activeAiConfig
import com.xzledger.app.data.recentImportBatches
import com.xzledger.app.data.Category
import com.xzledger.app.data.ClassifyBasis
import com.xzledger.app.data.DedupKeys
import com.xzledger.app.data.ImportOutcome
import com.xzledger.app.data.ImportRow
import com.xzledger.app.data.ImportSession
import com.xzledger.app.data.ImportState
import com.xzledger.app.data.ImportFiles
import com.xzledger.app.data.ImportService
import com.xzledger.app.data.Nature
import com.xzledger.app.data.Txn
import com.xzledger.app.data.TxnType
import com.xzledger.app.data.rowKey
import com.xzledger.app.ui.theme.expenseColor
import com.xzledger.app.ui.theme.incomeColor
import com.xzledger.app.ui.theme.refundColor
import com.xzledger.app.util.Dates
import com.xzledger.app.util.Money
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 聊天附件里的文件解析入口（供 ChatPane 调用）。返回 true 表示已受理。 */
fun startChatImport(ctx: Context, state: AppState, nav: NavStack, uri: Uri): Boolean {
    val name = fileDisplayName(ctx, uri)
    chatImportScope.launch {
        val res = withContext(Dispatchers.IO) { ImportFiles.parseFile(XzApp.db, ctx, uri, state.ledgerId, name) }
        ImportSession.start(listOf(res))
        nav.push(Screen.IMPORT_PREVIEW, null)
    }
    Toast.makeText(ctx, "正在解析：" + name, Toast.LENGTH_SHORT).show()
    return true
}

private val chatImportScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/** 文件解析用的独立作用域，避免界面重组或返回时取消正在进行的解析。 */
private val importScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

private fun fileDisplayName(ctx: Context, uri: Uri): String {
    var name = uri.lastPathSegment ?: "文件"
    try {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) {
                val s = c.getString(i)
                if (!s.isNullOrBlank()) name = s
            }
        }
    } catch (e: Exception) {
        // 拿不到显示名时退回 uri 末段
    }
    return name
}

private fun stateLabel(id: String): String = ImportState.of(id).label

private fun stateColor(id: String): Color = when (id) {
    ImportState.DUPLICATE.id -> Color(0xFF8A8F98)
    ImportState.SUSPECTED.id -> Color(0xFFE08A00)
    ImportState.NEED_CONFIRM.id -> Color(0xFF3B6FE0)
    ImportState.FAILED.id -> Color(0xFFD33A2C)
    else -> Color(0xFF1F9D55)
}

@Composable
fun ImportPickerScreen(state: AppState, nav: NavStack) {
    val ctx = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var batchesVersion by remember { mutableStateOf(0) }

    // 注意：不能在 LaunchedEffect 内部清空作为 key 的状态，否则组合会取消正在执行的解析。
    // 这里改为在回调里直接启动协程，解析完成后才进入预览。
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val list = uris ?: emptyList()
        if (list.isNotEmpty() && !busy) {
            busy = true
            importScope.launch {
                try {
                    val out = ArrayList<com.xzledger.app.data.ParseResult>()
                    for ((idx, u) in list.withIndex()) {
                        val name = fileDisplayName(ctx, u)
                        progress = "正在解析 " + (idx + 1) + "/" + list.size + "：" + name
                        val res = withContext(Dispatchers.IO) { ImportFiles.parseFile(XzApp.db, ctx, u, state.ledgerId, name) }
                        out.add(res)
                    }
                    ImportSession.start(out)
                    nav.push(Screen.IMPORT_PREVIEW, null)
                } catch (e: Exception) {
                    Toast.makeText(ctx, "解析失败：" + (e.message ?: "未知错误"), Toast.LENGTH_LONG).show()
                } finally {
                    busy = false
                    progress = ""
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "导入账单", onBack = { nav.pop() })
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Card2 {
                Column(Modifier.padding(4.dp)) {
                    Text("支持的文件", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text("· CSV / TXT：微信、支付宝导出的账单文件，或通用逗号分隔表格", style = MaterialTheme.typography.bodySmall)
                    Text("· Excel：.xlsx（可含多个工作表）；旧版 .xls 为二进制格式，请另存为 .xlsx 或 CSV", style = MaterialTheme.typography.bodySmall)
                    Text("· PDF：优先提取文字与表格；扫描件/图片型 PDF 可再交给 AI 识别", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "限制：单文件 " + (ImportService.MAX_FILE_BYTES / 1024 / 1024) + " MB、" +
                            ImportService.MAX_ROWS + " 条记录、" + ImportService.MAX_PDF_PAGES + " 页 PDF。" +
                            "超出会提示分批导入，不会静默截断。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            ChipButton(text = "选择文件（可多选）", selected = true, modifier = Modifier.fillMaxWidth(), onClick = {
                if (!busy) picker.launch(
                    arrayOf(
                        "text/*",
                        "application/pdf",
                        "application/vnd.ms-excel",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/vnd.ms-excel.sheet.macroEnabled.12"
                    )
                )
            })
            Spacer(Modifier.height(12.dp))
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(progress, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(12.dp))
            }
            Text(
                "导入流程：解析 → 预览（新增/重复/疑似重复/待确认/失败）→ 逐笔核对或批量修改 → 确认保存。原始文件只作为数据处理，不会执行其中的指令或链接。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            SectionTitle("最近导入批次")
            Spacer(Modifier.height(6.dp))
            val batches = remember(batchesVersion) { XzApp.db.recentImportBatches(state.ledgerId, 8) }
            if (batches.isEmpty()) {
                EmptyHint("本账本还没有导入记录")
            } else {
                for (b in batches) {
                    Card2 {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(b.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    b.source + " · " + Dates.formatDateTime(b.createdAt) + " · 新增 " + b.createdTxnIds.size + " 笔" +
                                        (if (b.undone) " · 已撤销" else ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (!b.undone) {
                                TextButton(onClick = {
                                    val n = ImportFiles.undoImport(XzApp.db, b.id)
                                    state.touch()
                                    batchesVersion++
                                    Toast.makeText(ctx, "已撤销本次导入新增的 " + n + " 笔记录（可在回收站找回）", Toast.LENGTH_LONG).show()
                                }) { Text("撤销") }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(120.dp))
        }
    }
}

@Composable
fun ImportPreviewScreen(state: AppState, nav: NavStack) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rows = remember { mutableStateListOf<ImportRow>().also { it.addAll(ImportSession.allRows()) } }
    val results = remember { ImportSession.results.toList() }
    var filter by remember { mutableStateOf("all") }
    var editing by remember { mutableStateOf<ImportRow?>(null) }
    var catSheet by remember { mutableStateOf(false) }
    var natureSheet by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<ImportOutcome?>(null) }
    var aiNote by remember { mutableStateOf("") }
    var aiBusy by remember { mutableStateOf(false) }

    val problems = remember { results.flatMap { it.problems } }
    val categories = remember { XzApp.db.categories(state.ledgerId) }
    val visible = rows.filter { filter == "all" || it.state == filter }
    val selectedKeys = rows.filter { it.selected && it.state != ImportState.DUPLICATE.id && it.state != ImportState.FAILED.id }
        .map { it.rowKey() }.toSet()
    val fileName = if (results.isEmpty()) "导入文件" else results.joinToString("、") { it.fileName }
    val source = results.firstOrNull()?.kind ?: "CSV"

    fun update(key: String, f: (ImportRow) -> ImportRow) {
        val i = rows.indexOfFirst { it.rowKey() == key }
        if (i >= 0) rows[i] = f(rows[i])
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "导入预览", onBack = { ImportSession.clear(); nav.pop() })
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            item {
                Card2 {
                    Column(Modifier.padding(4.dp)) {
                        Text(fileName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "新增 " + rows.count { it.state == ImportState.NEW.id } +
                                " · 重复 " + rows.count { it.state == ImportState.DUPLICATE.id } +
                                " · 疑似重复 " + rows.count { it.state == ImportState.SUSPECTED.id } +
                                " · 待确认 " + rows.count { it.state == ImportState.NEED_CONFIRM.id } +
                                " · 失败 " + rows.count { it.state == ImportState.FAILED.id },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "单号判重：本批 " + rows.count { it.txn.tradeNo.isNotBlank() || it.txn.orderNo.isNotBlank() } +
                                " 笔带交易单号/订单编码，与已有记录或本批先出现的记录同号的会直接标为「重复」并自动跳过（换文件格式、换来源渠道也算同一笔）；没有单号的记录走「疑似重复」人工核对。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (problems.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            for (p in problems.take(4)) {
                                Text("· " + p, style = MaterialTheme.typography.bodySmall, color = Color(0xFFD33A2C))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth()) {
                    ChipButton("全部", filter == "all", Modifier.weight(1f)) { filter = "all" }
                    ChipButton("新增", filter == ImportState.NEW.id, Modifier.weight(1f)) { filter = ImportState.NEW.id }
                    ChipButton("重复", filter == ImportState.DUPLICATE.id, Modifier.weight(1f)) { filter = ImportState.DUPLICATE.id }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth()) {
                    ChipButton("疑似", filter == ImportState.SUSPECTED.id, Modifier.weight(1f)) { filter = ImportState.SUSPECTED.id }
                    ChipButton("待确认", filter == ImportState.NEED_CONFIRM.id, Modifier.weight(1f)) { filter = ImportState.NEED_CONFIRM.id }
                    ChipButton("失败", filter == ImportState.FAILED.id, Modifier.weight(1f)) { filter = ImportState.FAILED.id }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        val visKeys = visible.map { it.rowKey() }.toSet()
                        val allSel = visible.all { it.selected }
                        for (k in visKeys) update(k) { it.copy(selected = !allSel) }
                    }) { Text("全选/取消当前分组") }
                    Spacer(Modifier.weight(1f))
                    Text("已选 " + selectedKeys.size + " 笔", style = MaterialTheme.typography.bodySmall)
                }
            }
            items(visible, key = { it.rowKey() }) { row ->
                ImportRowCard(
                    row = row,
                    categories = categories,
                    onToggle = { update(row.rowKey()) { it.copy(selected = !it.selected) } },
                    onEdit = { editing = row }
                )
                Spacer(Modifier.height(8.dp))
            }
            item {
                if (visible.isEmpty()) EmptyHint("该分组没有记录")
                Spacer(Modifier.height(140.dp))
            }
        }
        GlassPanel(modifier = Modifier.fillMaxWidth(), corner = 20.dp) {
            Column(Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    ChipButton("批量改分类", false, Modifier.weight(1f)) {
                        if (selectedKeys.isEmpty()) Toast.makeText(ctx, "请先勾选要修改的记录", Toast.LENGTH_SHORT).show() else catSheet = true
                    }
                    Spacer(Modifier.width(8.dp))
                    ChipButton("批量改性质", false, Modifier.weight(1f)) {
                        if (selectedKeys.isEmpty()) Toast.makeText(ctx, "请先勾选要修改的记录", Toast.LENGTH_SHORT).show() else natureSheet = true
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    ChipButton("交给 AI 识别", false, Modifier.weight(1f)) {
                        if (aiBusy) return@ChipButton
                        val cfg = XzApp.db.activeAiConfig()
                        if (cfg == null) {
                            Toast.makeText(ctx, "还没有配置 AI 服务，请先到「AI 配置」添加", Toast.LENGTH_LONG).show()
                            aiNote = "未配置 AI 服务，无法使用 AI 识别。可改用 CSV / Excel 导入或手动核对。"
                        } else {
                            aiBusy = true
                            aiNote = "正在请求 AI 识别，请稍候…"
                            val text = results.joinToString("\n\n") { it.rawText }
                            val imgs = results.flatMap { it.pageImages }
                            val ledgerName = state.currentLedger()?.name ?: "默认账本"
                            scope.launch {
                                val pair = AiImport.extract(cfg, ledgerName, text, imgs)
                                if (pair.second.isNotEmpty()) {
                                    aiNote = pair.second
                                } else {
                                    val built = AiImport.rowsFromJson(XzApp.db, state.ledgerId, pair.first, "AI 识别", cfg.name, com.xzledger.app.data.TxnSource.IMPORT_CSV)
                                    if (built.second.isNotEmpty() && built.first.isEmpty()) {
                                        aiNote = built.second
                                    } else {
                                        rows.addAll(built.first)
                                        aiNote = "AI 识别到 " + built.first.size + " 条记录，已追加到预览末尾，请核对后确认导入。" + (if (built.second.isEmpty()) "" else " " + built.second)
                                    }
                                }
                                aiBusy = false
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    ChipButton("确认导入 " + selectedKeys.size + " 笔", true, Modifier.weight(1f)) {
                        if (selectedKeys.isEmpty()) {
                            Toast.makeText(ctx, "没有勾选任何记录", Toast.LENGTH_SHORT).show()
                        } else {
                            val oc = ImportFiles.commit(XzApp.db, state.ledgerId, fileName, source, rows.toList())
                            state.touch()
                            outcome = oc
                        }
                    }
                }
                if (aiNote.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(aiNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (catSheet) {
        ChoiceSheet("选择分类（批量应用到已勾选的 " + selectedKeys.size + " 笔）", categories.map { it.name to it.id }, onPick = { id ->
            catSheet = false
            val list = ImportFiles.applyToRows(rows.toList(), selectedKeys, id, null)
            rows.clear()
            rows.addAll(list)
        }, onDismiss = { catSheet = false })
    }
    if (natureSheet) {
        val opts = Nature.entries.map { it.label + "（" + it.desc + "）" to it }
        ChoiceSheet("选择消费性质（批量应用）", opts, onPick = { n ->
            natureSheet = false
            val list = ImportFiles.applyToRows(rows.toList(), selectedKeys, null, n)
            rows.clear()
            rows.addAll(list)
        }, onDismiss = { natureSheet = false })
    }
    editing?.let { row ->
        RowEditDialog(row = row, categories = categories, onDismiss = { editing = null }) { newRow ->
            update(row.rowKey()) { newRow }
            editing = null
        }
    }
    outcome?.let { oc ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("导入完成") },
            text = {
                Column {
                    Text("成功保存 " + oc.saved + " 笔")
                    if (oc.skippedDuplicate > 0) Text("跳过重复 " + oc.skippedDuplicate + " 笔")
                    if (oc.failed > 0) Text("失败 " + oc.failed + " 笔")
                    if (oc.messages.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        for (m in oc.messages.take(6)) Text("· " + m, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    outcome = null
                    ImportSession.clear()
                    nav.pop()
                }) { Text("完成") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val n = ImportFiles.undoImport(XzApp.db, oc.batchId)
                    state.touch()
                    outcome = null
                    ImportSession.clear()
                    nav.pop()
                    Toast.makeText(ctx, "已撤销本次导入新增的 " + n + " 笔记录（可在回收站找回）", Toast.LENGTH_LONG).show()
                }) { Text("撤销本次导入") }
            }
        )
    }
}

@Composable
private fun ImportRowCard(
    row: ImportRow,
    categories: List<Category>,
    onToggle: () -> Unit,
    onEdit: () -> Unit
) {
    val t = row.txn
    val catName = categories.firstOrNull { it.id == t.categoryId }?.name ?: "未分类"
    Card2(onClick = onEdit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = row.selected, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (t.merchantRaw.isNotBlank()) t.merchantRaw else if (t.title.isNotBlank()) t.title else "（无商户）",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    MoneyText(t.amount, t.type)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    t.occurredDate + " " + Dates.timeOf(t.occurredAt) + " · " +
                        (XzApp.db.account(t.accountId)?.name ?: "未指定账户") + " · " + t.source.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stateLabel(row.state) + " · " + catName + " · " +
                        (if (t.type == TxnType.EXPENSE) (t.nature?.label ?: "未填性质") else t.type.label) +
                        " · " + t.classifyBasis.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = stateColor(row.state)
                )
                if (t.tradeNo.isNotBlank() || t.orderNo.isNotBlank()) {
                    Text(
                        "单号：" + (if (t.tradeNo.isNotBlank()) t.tradeNo else t.orderNo) + (if (t.tradeNo.isNotBlank() && t.orderNo.isNotBlank()) " / 订单：" + t.orderNo else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(row.origin, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (row.message.isNotBlank()) {
                    Text(row.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun RowEditDialog(
    row: ImportRow,
    categories: List<Category>,
    onDismiss: () -> Unit,
    onApply: (ImportRow) -> Unit
) {
    val t = row.txn
    var amountText by remember { mutableStateOf(Money.centsToEdit(t.amount)) }
    var date by remember { mutableStateOf(t.occurredDate) }
    var merchant by remember { mutableStateOf(t.merchantRaw) }
    var note by remember { mutableStateOf(t.note) }
    var tradeNo by remember { mutableStateOf(t.tradeNo) }
    var categoryId by remember { mutableStateOf(t.categoryId) }
    var nature by remember { mutableStateOf(t.nature) }
    var type by remember { mutableStateOf(t.type) }
    var catSheet by remember { mutableStateOf(false) }
    var natureSheet by remember { mutableStateOf(false) }
    var typeSheet by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("核对这一笔") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = amountText, onValueChange = { amountText = it }, label = { Text("金额（元）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = date, onValueChange = { date = it }, label = { Text("日期 yyyy-MM-dd") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = merchant, onValueChange = { merchant = it }, label = { Text("商户/交易对象") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = tradeNo, onValueChange = { tradeNo = it }, label = { Text("交易单号") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("备注") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                SettingRow(title = "类型", subtitle = type.label, onClick = { typeSheet = true })
                SettingRow(
                    title = "分类",
                    subtitle = categories.firstOrNull { it.id == categoryId }?.name ?: "未分类",
                    onClick = { catSheet = true }
                )
                SettingRow(
                    title = "消费性质",
                    subtitle = nature?.label ?: "未填写",
                    onClick = { if (type == TxnType.EXPENSE) natureSheet = true else error = "收入与转账不强制填写消费性质" }
                )
                if (error.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cents = Money.parseYuanToCents(amountText)
                if (cents == null || cents <= 0L) { error = "金额必须是大于 0 的数字"; return@TextButton }
                if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(date)) { error = "日期格式应为 yyyy-MM-dd"; return@TextButton }
                val nt = t.copy(
                    amount = cents,
                    occurredDate = date,
                    occurredAt = Dates.toMillis(date.substring(0, 4).toInt(), date.substring(5, 7).toInt() - 1, date.substring(8, 10).toInt(), Dates.hourOf(t.occurredAt), Dates.minuteOf(t.occurredAt)),
                    merchant = com.xzledger.app.data.MerchantNormalizer.normalize(merchant),
                    merchantRaw = merchant,
                    note = note,
                    tradeNo = DedupKeys.normNo(tradeNo),
                    type = type,
                    categoryId = categoryId,
                    nature = if (type == TxnType.EXPENSE) nature else null,
                    classifyBasis = ClassifyBasis.MANUAL,
                    dedupKey = DedupKeys.build(t.sourcePlatform, DedupKeys.normNo(tradeNo), t.orderNo, type == TxnType.REFUND, date, cents),
                    updatedAt = System.currentTimeMillis()
                )
                onApply(row.copy(txn = nt, message = row.message + (if (row.message.isEmpty()) "" else "；") + "已手动核对"))
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )

    if (catSheet) {
        ChoiceSheet("选择分类", categories.map { it.name to it.id }, onPick = { categoryId = it; catSheet = false }, onDismiss = { catSheet = false })
    }
    if (natureSheet) {
        ChoiceSheet(
            "选择消费性质",
            Nature.entries.map { it.label + "（" + it.desc + "）" to it },
            onPick = { nature = it; natureSheet = false },
            onDismiss = { natureSheet = false }
        )
    }
    if (typeSheet) {
        ChoiceSheet("选择类型", listOf(TxnType.EXPENSE.label to TxnType.EXPENSE, TxnType.INCOME.label to TxnType.INCOME, TxnType.TRANSFER.label to TxnType.TRANSFER, TxnType.REFUND.label to TxnType.REFUND), onPick = { type = it; typeSheet = false }, onDismiss = { typeSheet = false })
    }
}
