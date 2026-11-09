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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xzledger.app.AppState
import com.xzledger.app.XzApp
import com.xzledger.app.data.Account
import com.xzledger.app.data.AccountType
import com.xzledger.app.data.Category
import com.xzledger.app.data.Ids
import com.xzledger.app.data.Ledger
import com.xzledger.app.data.Nature
import com.xzledger.app.data.Txn
import com.xzledger.app.data.TxnFilter
import com.xzledger.app.data.TxnType
import com.xzledger.app.data.accountBalance
import com.xzledger.app.data.countTxns
import com.xzledger.app.data.ledgerBalance
import com.xzledger.app.data.purgeTxn
import com.xzledger.app.data.restoreTxn
import com.xzledger.app.data.saveTxn
import com.xzledger.app.data.softDeleteTxn
import com.xzledger.app.data.splitDedupKey
import com.xzledger.app.data.txnById
import com.xzledger.app.data.txns
import com.xzledger.app.data.txnsByDedupPrefix
import com.xzledger.app.data.trash
import com.xzledger.app.data.updateTxn
import com.xzledger.app.util.Dates
import com.xzledger.app.util.Money

/**
 * 拆分类记账：把一次付款按商品拆成多条，分别为不同分类/性质。
 * 约束：分片金额合计必须等于实付金额，主记录保留原平台去重键，分片使用 base|S<i>，
 * 因此重复导入同一订单不会再次入账，也不会重复计入订单总额。
 */
@Composable
fun SplitScreen(state: AppState, nav: NavStack, id: String) {
    val db = XzApp.db
    var version by remember { mutableStateOf(0) }
    val txn = remember { db.txnById(id) }
    if (txn == null) {
        Column(Modifier.fillMaxSize()) {
            TopBar("拆分账单", onBack = { nav.pop() })
            EmptyHint("账单不存在或已被删除")
        }
        return
    }
    val baseKey = if (txn.dedupKey.isNotEmpty()) txn.dedupKey else "txn|" + txn.id
    val parts = remember { db.txnsByDedupPrefix(state.ledgerId, baseKey).filter { it.id != txn.id } }
    val total = txn.amount + parts.sumOf { it.amount }
    val rows = remember {
        mutableStateListOf<SplitRow>().apply {
            add(SplitRow(txn.id, Money.centsToEdit(txn.amount), txn.categoryId, txn.nature, true))
            parts.forEach { p -> add(SplitRow(p.id, Money.centsToEdit(p.amount), p.categoryId, p.nature, false)) }
        }
    }
    var editingIndex by remember { mutableStateOf(-1) }
    var message by remember { mutableStateOf<String?>(null) }
    val cats = state.categories().filter { it.kind == if (txn.type == TxnType.INCOME) "income" else "expense" }

    Column(Modifier.fillMaxSize()) {
        TopBar("拆分账单", onBack = { nav.pop() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            GlassPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("实付金额（拆分合计必须等于它）", style = MaterialTheme.typography.labelMedium)
                    Text(Money.centsToPlain(total) + " 元", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "商户：" + (txn.merchant.ifEmpty { "未填写" }) + "；订单编码：" + (txn.orderNo.ifEmpty { "无" }) + "；交易单号：" + (txn.tradeNo.ifEmpty { "无" }),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            rows.forEachIndexed { index, row ->
                Card2(Modifier.fillMaxWidth().padding(vertical = 4.dp), onClick = { editingIndex = index }) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                (if (index == 0) "主记录" else "拆分 " + index) + "：" +
                                    (state.category(row.categoryId)?.name ?: "未选择分类"),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                "性质 " + (row.nature?.label ?: "未填写") + "；金额 " + row.amountText + " 元",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (index > 0) {
                            IconButton({ editingIndex = index }) { Icon(Icons.Filled.Delete, contentDescription = "移除分片") }
                        } else {
                            Text("主", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row {
                ChipButton("新增分片", false, Modifier.weight(1f), onClick = {
                    rows.add(SplitRow(null, "", null, null, false))
                })
                Spacer(Modifier.width(8.dp))
                ChipButton("保存拆分", true, Modifier.weight(1f), onClick = {
                    val amounts = ArrayList<Long>()
                    var bad = false
                    rows.forEach { r ->
                        val c = Money.parseYuanToCents(r.amountText)
                        if (c == null || c <= 0L) bad = true else amounts.add(c)
                    }
                    val sum = amounts.sum()
                    if (bad) {
                        message = "每一条分片的金额都必须大于 0"
                    } else if (sum != total) {
                        message = "拆分合计 " + Money.centsToPlain(sum) + " 元与实付金额 " + Money.centsToPlain(total) + " 元不一致，请调整。"
                    } else if (rows.any { it.categoryId == null }) {
                        message = "请为每一条分片选择分类"
                    } else {
                        var i = 0
                        rows.forEachIndexed { index, r ->
                            val cents = amounts[index]
                            if (index == 0) {
                                var parent = txn.copy(amount = cents, categoryId = r.categoryId, nature = r.nature)
                                if (txn.dedupKey.isEmpty()) parent = parent.copy(dedupKey = baseKey)
                                if (parent.dedupKey != txn.dedupKey || parent.amount != txn.amount ||
                                    parent.categoryId != txn.categoryId || parent.nature != txn.nature
                                ) db.updateTxn(parent)
                            } else if (r.id == null) {
                                i++
                                db.saveTxn(
                                    txn.copy(
                                        id = Ids.newId(), amount = cents, categoryId = r.categoryId, nature = r.nature,
                                        dedupKey = splitDedupKey(baseKey, i), linkedTxnId = txn.id,
                                        note = if (txn.note.isEmpty()) "订单拆分" else txn.note + "（订单拆分）",
                                        createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()
                                    )
                                )
                            } else {
                                val old = parts.firstOrNull { it.id == r.id }
                                if (old != null && (old.amount != cents || old.categoryId != r.categoryId || old.nature != r.nature)) {
                                    db.updateTxn(old.copy(amount = cents, categoryId = r.categoryId, nature = r.nature))
                                }
                            }
                        }
                        val keptIds = rows.mapNotNull { it.id }.toSet()
                        parts.filter { it.id !in keptIds }.forEach { db.softDeleteTxn(it.id) }
                        state.touch()
                        message = "拆分已保存，明细与统计已按拆分后的分类更新。"
                    }
                })
            }
            if (message != null) {
                Spacer(Modifier.height(8.dp))
                Text(message ?: "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(150.dp))
        }
    }

    if (editingIndex in rows.indices) {
        val row = rows[editingIndex]
        var amount by remember(editingIndex) { mutableStateOf(row.amountText) }
        var catId by remember(editingIndex) { mutableStateOf(row.categoryId) }
        var nature by remember(editingIndex) { mutableStateOf(row.nature) }
        AlertDialog(
            onDismissRequest = { editingIndex = -1 },
            title = { Text(if (editingIndex == 0) "主记录" else "拆分分片") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("金额（元）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("分类", style = MaterialTheme.typography.labelMedium)
                    cats.forEach { c ->
                        Row(Modifier.fillMaxWidth().clickable { catId = c.id }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            CategoryAvatar(c.icon, 32.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(c.name, style = MaterialTheme.typography.bodyMedium)
                            if (catId == c.id) {
                                Spacer(Modifier.width(6.dp))
                                Text("已选择", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("消费性质", style = MaterialTheme.typography.labelMedium)
                    NatureChoiceRow(nature = nature, noneLabel = "未填") { nature = it }
                    Spacer(Modifier.height(8.dp))
                    if (editingIndex > 0) {
                        TextButton({
                            rows.removeAt(editingIndex)
                            editingIndex = -1
                        }) { Text("删除该分片（保存时生效）") }
                    }
                }
            },
            confirmButton = {
                TextButton({
                    row.amountText = amount
                    row.categoryId = catId
                    row.nature = nature
                    version++
                    editingIndex = -1
                }) { Text("确定") }
            },
            dismissButton = { TextButton({ editingIndex = -1 }) { Text("取消") } }
        )
    }
}

/** 拆分编辑用的可变行。字段用 Compose 状态承载，修改后界面立即刷新。 */
class SplitRow(
    val id: String?,
    amountText: String,
    categoryId: String?,
    nature: Nature?,
    val isMain: Boolean
) {
    var amountText by mutableStateOf(amountText)
    var categoryId by mutableStateOf(categoryId)
    var nature by mutableStateOf(nature)
}

@Composable
fun CategoriesScreen(state: AppState, nav: NavStack) {
    val db = XzApp.db
    var version by remember { mutableStateOf(0) }
    var kind by remember { mutableStateOf("expense") }
    val list = remember(version, state.ledgerVersion, kind) {
        db.categories(state.ledgerId, kind).sortedWith(compareBy({ it.sortOrder }, { it.createdAt }))
    }
    var editing by remember { mutableStateOf<Category?>(null) }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopBar("分类管理", onBack = { nav.pop() }, actions = {
            TextButton({ creating = true }) { Text("新增") }
        })
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            ChipButton("支出分类", kind == "expense", Modifier.weight(1f), onClick = { kind = "expense" })
            Spacer(Modifier.width(8.dp))
            ChipButton("收入分类", kind == "income", Modifier.weight(1f), onClick = { kind = "income" })
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(list, key = { it.id }) { c ->
                Card2(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CategoryAvatar(c.icon, 38.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f).clickable { editing = c }) {
                            Text(c.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "默认性质：" + (c.natureDefault?.label ?: "未设置") + "；已有账单 " + db.countTxnByCategory(c.id) + " 笔",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton({
                            val prev = list.getOrNull(list.indexOf(c) - 1) ?: return@IconButton
                            db.upsertCategory(prev.copy(sortOrder = c.sortOrder))
                            db.upsertCategory(c.copy(sortOrder = prev.sortOrder))
                            version++; state.touch()
                        }) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "上移") }
                        IconButton({
                            val next = list.getOrNull(list.indexOf(c) + 1) ?: return@IconButton
                            db.upsertCategory(next.copy(sortOrder = c.sortOrder))
                            db.upsertCategory(c.copy(sortOrder = next.sortOrder))
                            version++; state.touch()
                        }) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "下移") }
                        IconButton({
                            val n = db.countTxnByCategory(c.id)
                            if (n > 0) error = "该分类下还有 " + n + " 笔账单，删除后这些账单会变成未分类，请先改分类。"
                            else if (c.isSystem) error = "系统预置分类建议保留，可以改名但不要删除。"
                            else { db.upsertCategory(c.copy(deleted = true)); version++; state.touch() }
                        }) { Icon(Icons.Filled.Delete, contentDescription = "删除") }
                    }
                }
            }
            item { Spacer(Modifier.height(120.dp)) }
        }
    }

    if (creating || editing != null) {
        CategoryEditDialog(
            ledgerId = state.ledgerId, kind = kind, initial = editing,
            onDismiss = { creating = false; editing = null },
            onSave = { c -> db.upsertCategory(c); creating = false; editing = null; version++; state.touch() }
        )
    }
    if (error != null) {
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("无法删除") },
            text = { Text(error ?: "") },
            confirmButton = { TextButton({ error = null }) { Text("知道了") } }
        )
    }
}

@Composable
private fun CategoryEditDialog(ledgerId: String, kind: String, initial: Category?, onDismiss: () -> Unit, onSave: (Category) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var icon by remember { mutableStateOf(initial?.icon ?: "🏷") }
    var nature by remember { mutableStateOf(initial?.natureDefault) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增分类" else "编辑分类") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("分类名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = icon, onValueChange = { icon = it }, label = { Text("图标（emoji 或字符）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("默认消费性质（选填；收入分类通常不填）", style = MaterialTheme.typography.labelMedium)
                NatureChoiceRow(nature = nature, noneLabel = "不设置") { nature = it }
            }
        },
        confirmButton = {
            TextButton({
                val base = initial ?: Category(id = Ids.newId(), ledgerId = ledgerId, name = "", kind = kind, sortOrder = 500)
                onSave(base.copy(name = name.ifEmpty { "未命名分类" }, icon = icon, natureDefault = nature, updatedAt = System.currentTimeMillis()))
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消") } }
    )
}

@Composable
fun AccountsScreen(state: AppState, nav: NavStack) {
    val db = XzApp.db
    var version by remember { mutableStateOf(0) }
    val list = remember(version, state.ledgerVersion) { db.accounts(state.ledgerId) }
    var editing by remember { mutableStateOf<Account?>(null) }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopBar("账户管理", onBack = { nav.pop() }, actions = {
            TextButton({ creating = true }) { Text("新增") }
        })
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                Text(
                    "账户余额 = 期初余额 + 该账户收入 − 该账户支出。转账是账户之间的资金移动，不计入收入或支出。",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            items(list, key = { it.id }) { a ->
                Card2(Modifier.fillMaxWidth().padding(vertical = 4.dp), onClick = { editing = a }) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        CategoryAvatar(a.icon.ifEmpty { "💳" }, 38.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.name + if (a.archived) "（已归档）" else "", style = MaterialTheme.typography.bodyLarge)
                            Text(a.type.label + "；余额 " + Money.centsToPlain(db.accountBalance(a.id)) + " 元", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton({
                            val used = db.txns(TxnFilter(ledgerId = state.ledgerId, accountIds = listOf(a.id), limit = 1)).isNotEmpty()
                            if (used) error = "该账户已被账单使用，建议改为归档而不是删除。"
                            else { db.upsertAccount(a.copy(deleted = true)); version++; state.touch() }
                        }) { Icon(Icons.Filled.Delete, contentDescription = "删除") }
                    }
                }
            }
            item { Spacer(Modifier.height(120.dp)) }
        }
    }

    if (creating || editing != null) {
        AccountEditDialog(
            ledgerId = state.ledgerId, initial = editing,
            onDismiss = { creating = false; editing = null },
            onSave = { a -> db.upsertAccount(a); creating = false; editing = null; version++; state.touch() }
        )
    }
    if (error != null) {
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("提示") },
            text = { Text(error ?: "") },
            confirmButton = { TextButton({ error = null }) { Text("知道了") } }
        )
    }
}

@Composable
private fun AccountEditDialog(ledgerId: String, initial: Account?, onDismiss: () -> Unit, onSave: (Account) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: AccountType.OTHER) }
    var icon by remember { mutableStateOf(initial?.icon ?: "💳") }
    var opening by remember { mutableStateOf(if (initial == null) "0" else Money.centsToEdit(initial.openingBalance)) }
    var archived by remember { mutableStateOf(initial?.archived ?: false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增账户" else "编辑账户") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("账户名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = opening, onValueChange = { opening = it }, label = { Text("期初余额（元）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = icon, onValueChange = { icon = it }, label = { Text("图标") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("账户类型", style = MaterialTheme.typography.labelMedium)
                AccountType.entries.forEach { t ->
                    Row(Modifier.fillMaxWidth().clickable { type = t }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(t.label, style = MaterialTheme.typography.bodyMedium)
                        if (type == t) {
                            Spacer(Modifier.width(6.dp))
                            Text("已选择", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("归档（不再用于新账单）", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    GlassSwitch(checked = archived, onCheckedChange = { archived = it })
                }
            }
        },
        confirmButton = {
            TextButton({
                val base = initial ?: Account(id = Ids.newId(), ledgerId = ledgerId, name = "")
                onSave(
                    base.copy(
                        name = name.ifEmpty { "未命名账户" }, type = type, icon = icon,
                        openingBalance = Money.parseYuanToCents(opening) ?: 0L,
                        archived = archived, updatedAt = System.currentTimeMillis()
                    )
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消") } }
    )
}

@Composable
fun LedgersScreen(state: AppState, nav: NavStack) {
    val db = XzApp.db
    var version by remember { mutableStateOf(0) }
    val list = remember(version, state.ledgerVersion) { db.ledgers() }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Ledger?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopBar("账本管理", onBack = { nav.pop() }, actions = {
            TextButton({ creating = true }) { Text("新增") }
        })
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                Text(
                    "所有明细、统计、搜索、日历和 AI 操作都以当前账本为范围。切换账本不会删除任何数据。",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            items(list, key = { it.id }) { l ->
                Card2(Modifier.fillMaxWidth().padding(vertical = 4.dp), onClick = { state.switchLedger(l.id); state.reloadLedgers(); nav.pop() }) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(l.name + if (l.id == state.ledgerId) "（当前）" else "", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                db.countTxns(l.id).toString() + " 笔账单；结余 " + Money.centsToPlain(db.ledgerBalance(l.id)) + " 元",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton({ renaming = l }) { Text("重命名") }
                        IconButton({
                            if (db.ledgers().size <= 1) error = "至少保留一个账本。"
                            else if (db.countTxns(l.id) > 0) error = "该账本还有 " + db.countTxns(l.id) + " 笔账单，请先清空或移动到其它账本。"
                            else if (l.id == state.ledgerId) error = "不能删除当前正在使用的账本，请先切换到其它账本。"
                            else { db.markLedgerDeleted(l.id); state.reloadLedgers(); version++; state.touch() }
                        }) { Icon(Icons.Filled.Delete, contentDescription = "删除") }
                    }
                }
            }
            item { Spacer(Modifier.height(120.dp)) }
        }
    }

    if (creating) {
        NameDialog("新增账本", "", { creating = false }) { name ->
            db.upsertLedger(Ledger(id = Ids.newId(), name = name.ifEmpty { "新账本" }))
            state.reloadLedgers(); creating = false; version++; state.touch()
        }
    }
    if (renaming != null) {
        val l = renaming!!
        NameDialog("重命名账本", l.name, { renaming = null }) { name ->
            db.upsertLedger(l.copy(name = name.ifEmpty { l.name }, updatedAt = System.currentTimeMillis()))
            state.reloadLedgers(); renaming = null; version++; state.touch()
        }
    }
    if (error != null) {
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("无法完成") },
            text = { Text(error ?: "") },
            confirmButton = { TextButton({ error = null }) { Text("知道了") } }
        )
    }
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("名称") }, singleLine = true) },
        confirmButton = { TextButton({ onConfirm(value) }) { Text("确定") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } }
    )
}

@Composable
fun TrashScreen(state: AppState, nav: NavStack) {
    val db = XzApp.db
    var version by remember { mutableStateOf(0) }
    val list = remember(version, state.dataVersion) { db.trash(state.ledgerId) }

    Column(Modifier.fillMaxSize()) {
        TopBar("回收站", onBack = { nav.pop() }, actions = {
            if (list.isNotEmpty()) TextButton({
                list.forEach { db.purgeTxn(it.id) }
                state.touch(); version++
            }) { Text("彻底清空") }
        })
        if (list.isEmpty()) EmptyHint("回收站是空的。删除的账单会先放到这里，可以恢复。")
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                Text(
                    "彻底清空会永久删除这些记录，无法恢复。账单被删除后不参与任何统计。",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            items(list, key = { it.id }) { t ->
                Card2(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.title.ifEmpty { t.merchant.ifEmpty { "未填写商户" } }, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                t.occurredDate + "；" + Money.centsToPlain(t.amount) + " 元；删除于 " +
                                    (t.deletedAt?.let { Dates.formatDateTime(it) } ?: "未知"),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton({ db.restoreTxn(t.id); state.touch(); version++ }) { Text("恢复") }
                        TextButton({ db.purgeTxn(t.id); state.touch(); version++ }) { Text("彻底删除") }
                    }
                }
            }
            item { Spacer(Modifier.height(120.dp)) }
        }
    }
}
