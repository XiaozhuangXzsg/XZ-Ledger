package com.xzledger.app.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xzledger.app.AppState
import com.xzledger.app.XzApp
import com.xzledger.app.data.ClassifyBasis
import com.xzledger.app.data.Ids
import com.xzledger.app.data.LearningEvent
import com.xzledger.app.data.MerchantNormalizer
import com.xzledger.app.data.Nature
import com.xzledger.app.data.Rule
import com.xzledger.app.data.Txn
import com.xzledger.app.data.TxnType
import com.xzledger.app.data.addLearningEvent
import com.xzledger.app.data.batchUpdateClassification
import com.xzledger.app.data.bumpRuleHit
import com.xzledger.app.data.learningEvents
import com.xzledger.app.data.rules
import com.xzledger.app.data.txnsByMerchantNorm
import com.xzledger.app.data.upsertRule

/**
 * 用户修改分类/性质后的三选项（需求九）。
 *  - 仅改本笔：改动已生效，只在学习记录里留一条参考，不推广到其它账单。
 *  - 记住该商户/匹配条件：建立 origin=user 的个人规则，明确确认的规则优先级最高。
 *  - 同时批量修改已有匹配账单：规则 + 历史账单批量更新（需要用户确认）。
 * 单次修改只作参考；多次一致由 Classifier 提示为「历史习惯」。
 */
@Composable
fun LearningChoiceDialog(
    state: AppState,
    ledgerId: String,
    txnId: String?,
    merchantRaw: String,
    note: String,
    accountId: String?,
    type: TxnType,
    newCategoryId: String?,
    newNature: Nature?,
    oldCategoryId: String?,
    oldNature: Nature?,
    onDone: () -> Unit,
    onSkip: () -> Unit
) {
    val db = XzApp.db
    val norm = MerchantNormalizer.normalize(merchantRaw)
    val eligible = MerchantNormalizer.isRuleEligible(norm)
    var mode by remember { mutableStateOf("once") }
    var recorded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!recorded && (newCategoryId != oldCategoryId || newNature != oldNature)) {
            db.addLearningEvent(
                LearningEvent(
                    id = Ids.newId(), ledgerId = ledgerId, txnId = txnId,
                    merchantRaw = merchantRaw, merchantNorm = norm,
                    oldCategoryId = oldCategoryId, newCategoryId = newCategoryId,
                    oldNature = oldNature, newNature = newNature
                )
            )
            recorded = true
        }
    }

    AlertDialog(
        onDismissRequest = { onSkip() },
        title = { Text("让软件记住这次修改？") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "你已把分类或消费性质改成其它值。单次修改只作为参考，不会自动推广到所有相似账单。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                if (norm.isEmpty()) {
                    Text("本笔没有可用的商户名称，只能选择「仅改本笔」。", style = MaterialTheme.typography.labelSmall)
                }
                LearnOption("仅改本笔", "只影响这一笔，不改动其它账单。", mode == "once") { mode = "once" }
                LearnOption(
                    "记住该商户，以后自动应用",
                    if (eligible) "为「" + MerchantNormalizer.displayName(merchantRaw) + "」建立个人规则，优先级最高。"
                    else "商户名称过于通用，建议改用关键词规则。",
                    mode == "remember"
                ) { mode = "remember" }
                if (eligible) {
                    LearnOption("同时批量修改已有匹配账单", "把该商户已有账单一起改成当前分类与性质。", mode == "batch") { mode = "batch" }
                }
            }
        },
        confirmButton = {
            TextButton({
                applyLearningChoice(db, ledgerId, mode, norm, merchantRaw, note, accountId, type, newCategoryId, newNature, txnId)
                onDone()
            }) { Text("确定") }
        },
        dismissButton = { TextButton({ onSkip() }) { Text("跳过") } }
    )
}

@Composable
private fun LearnOption(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 应用学习选择；建立规则时写入 origin=user 的个人规则。 */
fun applyLearningChoice(
    db: com.xzledger.app.data.Db, ledgerId: String, mode: String, norm: String, merchantRaw: String,
    note: String, accountId: String?, type: TxnType, newCategoryId: String?, newNature: Nature?, txnId: String?
) {
    if (mode == "once" || norm.isEmpty()) return
    val now = System.currentTimeMillis()
    val useExact = MerchantNormalizer.isRuleEligible(norm)
    val rule = Rule(
        id = Ids.newId(), ledgerId = ledgerId,
        name = (if (useExact) "商户 " else "关键词 ") + MerchantNormalizer.displayName(merchantRaw),
        enabled = true, priority = 10,
        matchType = if (useExact) "merchant_exact" else "keyword",
        condMerchant = if (useExact) merchantRaw else "",
        condKeyword = if (useExact) "" else merchantRaw.trim(),
        condAccountId = null, condDirection = "", condSource = "",
        setCategoryId = newCategoryId, setNature = newNature,
        origin = "user", hitCount = 0, createdAt = now, updatedAt = now
    )
    db.upsertRule(rule)
    if (mode == "batch") {
        val ids = db.txnsByMerchantNorm(ledgerId, norm).filter { it.id != txnId }.map { it.id }
        db.batchUpdateClassification(ids, newCategoryId, newNature)
    }
}

/** 个人分类规则页：可编辑、停用、删除，并显示匹配条件与命中次数。 */
@Composable
fun RulesScreen(state: AppState, nav: NavStack) {
    val db = XzApp.db
    var version by remember { mutableStateOf(0) }
    val list = remember(version, state.ledgerVersion) { db.rules(state.ledgerId) }
    var editing by remember { mutableStateOf<Rule?>(null) }
    var creating by remember { mutableStateOf(false) }
    var detailOf by remember { mutableStateOf<Rule?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopBar("个人分类规则", onBack = { nav.pop() }, actions = {
            TextButton({ creating = true }) { Text("新建") }
        })
        if (list.isEmpty()) {
            EmptyHint("还没有规则。在明细里改一次分类或性质，选择「记住该商户」就会生成规则。")
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(list, key = { it.id }) { r ->
                Card2(Modifier.fillMaxWidth().padding(vertical = 5.dp), onClick = { detailOf = r }) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(ruleConditionText(state, r), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "设置：分类 " + (state.category(r.setCategoryId)?.name ?: "不变") +
                                    "；性质 " + (r.setNature?.label ?: "不变") +
                                    "；命中 " + r.hitCount + " 次；" + (if (r.origin == "user") "用户确认" else "学习建议"),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        GlassSwitch(checked = r.enabled, onCheckedChange = { on -> db.upsertRule(r.copy(enabled = on, updatedAt = System.currentTimeMillis())); version++; state.touch() })
                    }
                }
            }
            item { Spacer(Modifier.height(120.dp)) }
        }
    }

    if (detailOf != null) {
        val r = detailOf!!
        AlertDialog(
            onDismissRequest = { detailOf = null },
            title = { Text("规则详情") },
            text = {
                Column {
                    Text("名称：" + r.name)
                    Text("匹配方式：" + matchTypeLabel(r.matchType))
                    Text("匹配条件：" + ruleConditionText(state, r))
                    Text("匹配原因：命中时明细会显示「依据你的规则：" + r.name + "」")
                    Text("优先级：" + r.priority + "（数字小的先匹配）")
                    Text("创建时间：" + com.xzledger.app.util.Dates.formatDateTime(r.createdAt))
                }
            },
            confirmButton = {
                TextButton({
                    db.upsertRule(r.copy(deleted = true, updatedAt = System.currentTimeMillis()))
                    detailOf = null; version++; state.touch()
                }) { Text("删除") }
            },
            dismissButton = {
                Row {
                    TextButton({ editing = r; detailOf = null }) { Text("编辑") }
                    TextButton({ detailOf = null }) { Text("关闭") }
                }
            }
        )
    }

    if (creating || editing != null) {
        RuleEditDialog(
            state = state,
            initial = editing,
            onDismiss = { creating = false; editing = null },
            onSave = { rule -> db.upsertRule(rule); creating = false; editing = null; version++; state.touch() }
        )
    }
}

@Composable
private fun RuleEditDialog(state: AppState, initial: Rule?, onDismiss: () -> Unit, onSave: (Rule) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var matchType by remember { mutableStateOf(initial?.matchType ?: "merchant_exact") }
    var merchant by remember { mutableStateOf(initial?.condMerchant ?: "") }
    var keyword by remember { mutableStateOf(initial?.condKeyword ?: "") }
    var categoryId by remember { mutableStateOf(initial?.setCategoryId) }
    var nature by remember { mutableStateOf(initial?.setNature) }
    var priority by remember { mutableStateOf((initial?.priority ?: 100).toString()) }
    val cats = state.categories()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建规则" else "编辑规则") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("规则名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("匹配方式", style = MaterialTheme.typography.labelMedium)
                Row {
                    ChipButton("商户相同", matchType == "merchant_exact", Modifier.weight(1f), onClick = { matchType = "merchant_exact" })
                    Spacer(Modifier.width(6.dp))
                    ChipButton("商户包含", matchType == "merchant_contains", Modifier.weight(1f), onClick = { matchType = "merchant_contains" })
                    Spacer(Modifier.width(6.dp))
                    ChipButton("关键词", matchType == "keyword", Modifier.weight(1f), onClick = { matchType = "keyword" })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = merchant, onValueChange = { merchant = it }, label = { Text("商户条件") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = keyword, onValueChange = { keyword = it }, label = { Text("关键词条件（商户、备注、交易描述中任意出现）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("设为分类（可不设）", style = MaterialTheme.typography.labelMedium)
                LazyRow {
                    item {
                        ChipButton("不设置", categoryId == null, onClick = { categoryId = null })
                        Spacer(Modifier.width(6.dp))
                    }
                    items(cats) { c ->
                        Row {
                            ChipButton(c.name, categoryId == c.id, onClick = { categoryId = c.id })
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("设为消费性质（可不设）", style = MaterialTheme.typography.labelMedium)
                Row {
                    ChipButton("不设置", nature == null, Modifier.weight(1f), onClick = { nature = null })
                    Spacer(Modifier.width(6.dp))
                    ChipButton(Nature.PERMANENT.label, nature == Nature.PERMANENT, Modifier.weight(1f), onClick = { nature = Nature.PERMANENT })
                    Spacer(Modifier.width(6.dp))
                    ChipButton(Nature.INSTANT.label, nature == Nature.INSTANT, Modifier.weight(1f), onClick = { nature = Nature.INSTANT })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = priority, onValueChange = { priority = it.filter { ch -> ch.isDigit() } }, label = { Text("优先级（数字小的先匹配）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (categoryId == null && nature == null) {
                    Spacer(Modifier.height(6.dp))
                    Text("至少要设置分类或性质中的一项，否则这条规则没有作用。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            val now = System.currentTimeMillis()
            TextButton({
                val base = initial ?: Rule(id = Ids.newId(), ledgerId = state.ledgerId, name = "")
                onSave(
                    base.copy(
                        name = name.ifEmpty { if (merchant.isNotEmpty()) merchant else keyword },
                        matchType = matchType, condMerchant = merchant, condKeyword = keyword,
                        setCategoryId = categoryId, setNature = nature,
                        priority = priority.toIntOrNull() ?: 100,
                        origin = "user", updatedAt = now
                    )
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消") } }
    )
}

fun matchTypeLabel(m: String): String = when (m) {
    "merchant_exact" -> "商户名称相同"
    "merchant_contains" -> "商户名称包含"
    "keyword" -> "关键词包含"
    "any" -> "商户与关键词同时满足"
    else -> m
}

fun ruleConditionText(state: AppState, r: Rule): String {
    val sb = StringBuilder()
    sb.append(matchTypeLabel(r.matchType)).append("：")
    when (r.matchType) {
        "merchant_exact", "merchant_contains" -> sb.append(r.condMerchant)
        "keyword" -> sb.append(r.condKeyword)
        else -> {
            if (r.condMerchant.isNotEmpty()) sb.append(r.condMerchant)
            if (r.condKeyword.isNotEmpty()) sb.append(" + ").append(r.condKeyword)
        }
    }
    if (!r.condAccountId.isNullOrEmpty()) sb.append("；账户 ").append(state.account(r.condAccountId)?.name ?: r.condAccountId)
    if (r.condDirection.isNotEmpty()) sb.append("；方向 ").append(if (r.condDirection == "expense") "支出" else r.condDirection)
    if (r.condSource.isNotEmpty()) sb.append("；来源 ").append(r.condSource)
    if (r.condMinAmount != null) sb.append("；金额≥").append(com.xzledger.app.util.Money.centsToEdit(r.condMinAmount))
    if (r.condMaxAmount != null) sb.append("；金额≤").append(com.xzledger.app.util.Money.centsToEdit(r.condMaxAmount))
    return sb.toString()
}

/** 学习记录页：展示每次修改、按商户聚合，可一键升级为个人规则。 */
@Composable
fun LearningScreen(state: AppState, nav: NavStack) {
    val db = XzApp.db
    var version by remember { mutableStateOf(0) }
    val events = remember(version, state.ledgerVersion) { db.learningEvents(state.ledgerId) }
    val groups = remember(version, state.ledgerVersion) { events.groupBy { it.merchantNorm } }
    var message by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopBar("分类学习记录", onBack = { nav.pop() }, actions = {
            TextButton({ nav.push(Screen.RULES) }) { Text("规则") }
        })
        if (events.isEmpty()) {
            EmptyHint("还没有学习记录。修改账单的分类或消费性质后这里会记录你的习惯；配置并授权 AI 后，AI 学到的商户分类也会记在这里。")
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                Text(
                    "单次修改只作参考；同一商户出现两次以上一致修改时，自动分类会提示「依据你的历史习惯」。明确确认的规则优先级最高。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            groups.forEach { (norm, list) ->
                item(key = norm) {
                    val latest = list.first()
                    val catCounts = list.mapNotNull { it.newCategoryId }.groupingBy { it }.eachCount()
                    val topCat = catCounts.entries.maxByOrNull { it.value }
                    Card2(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                MerchantNormalizer.displayName(latest.merchantRaw) + "   (" + list.size + " 次记录" +
                                    (if (list.any { it.origin == "ai" }) " · AI " + list.count { it.origin == "ai" } else "") + ")",
                                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium
                            )
                            if (latest.origin == "ai") {
                                Text(
                                    "AI 学习：该商户记住为「" + (state.category(latest.newCategoryId)?.name ?: "未分类") + "」" +
                                        (latest.newNature?.let { "；性质 " + it.label } ?: "") + "（已建立规则，之后自动应用）",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Text(
                                    "最近一次：" + (state.category(latest.oldCategoryId)?.name ?: "未分类") + " → " +
                                        (state.category(latest.newCategoryId)?.name ?: "未分类") + "；" +
                                        (latest.oldNature?.label ?: "无性质") + " → " + (latest.newNature?.label ?: "无性质"),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (topCat != null) {
                                val tie = catCounts.values.count { it == topCat.value } > 1
                                Text(
                                    if (tie) "该商户有 " + catCounts.size + " 种用途，无法判断，需人工确认。"
                                    else "最常改为：" + (state.category(topCat.key)?.name ?: "") + "（" + topCat.value + " 次）",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Row {
                                ChipButton("建立规则", false, Modifier.weight(1f), onClick = {
                                    val now = System.currentTimeMillis()
                                    db.upsertRule(
                                        Rule(
                                            id = Ids.newId(), ledgerId = state.ledgerId,
                                            name = "商户 " + MerchantNormalizer.displayName(latest.merchantRaw),
                                            matchType = "merchant_exact", condMerchant = latest.merchantRaw,
                                            setCategoryId = topCat?.key, setNature = latest.newNature,
                                            origin = "user", priority = 10, createdAt = now, updatedAt = now
                                        )
                                    )
                                    message = "已建立规则，之后的账单会自动应用。规则调整默认只影响后续账单。"
                                    version++; state.touch()
                                })
                                Spacer(Modifier.width(8.dp))
                                ChipButton("查看规则", false, Modifier.weight(1f), onClick = { nav.push(Screen.RULES) })
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(120.dp)) }
        }
    }

    if (message != null) {
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("已完成") },
            text = { Text(message ?: "") },
            confirmButton = { TextButton({ message = null }) { Text("知道了") } }
        )
    }
}
