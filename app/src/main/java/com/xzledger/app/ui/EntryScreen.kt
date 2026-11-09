package com.xzledger.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xzledger.app.AppState
import com.xzledger.app.XzApp
import com.xzledger.app.data.AiLearn
import com.xzledger.app.data.Category
import com.xzledger.app.data.ClassifyBasis
import com.xzledger.app.data.Classifier
import com.xzledger.app.data.Ids
import com.xzledger.app.data.MerchantHint
import com.xzledger.app.data.MerchantNormalizer
import com.xzledger.app.data.merchantHints
import com.xzledger.app.data.Nature
import com.xzledger.app.data.Txn
import com.xzledger.app.data.TxnSource
import com.xzledger.app.data.TxnType
import com.xzledger.app.data.activeAiConfig
import com.xzledger.app.data.bumpRuleHit
import com.xzledger.app.data.saveTxn
import com.xzledger.app.util.Attachments
import com.xzledger.app.util.Dates
import com.xzledger.app.util.Money
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.IconButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryScreen(state: AppState, nav: NavStack) {
    val db = XzApp.db
    val ctx = LocalContext.current
    val version = state.dataVersion
    val ledgerId = state.ledgerId

    val expenseCats = remember(version, ledgerId) { db.categories(ledgerId, "expense") }
    val incomeCats = remember(version, ledgerId) { db.categories(ledgerId, "income") }
    val accounts = remember(version, ledgerId) { db.accounts(ledgerId) }
    // 历史商户按收/支方向分开：收入的历史商户不会串到支出里，反之亦然。
    val expenseTips = remember(version, ledgerId) { db.merchantHints(ledgerId, 12, TxnType.EXPENSE) }
    val incomeTips = remember(version, ledgerId) { db.merchantHints(ledgerId, 12, TxnType.INCOME) }
    val scope = rememberCoroutineScope()

    var type by remember { mutableStateOf(TxnType.EXPENSE) }
    var amountText by remember { mutableStateOf("") }
    var categoryId by remember { mutableStateOf<String?>(null) }
    var nature by remember { mutableStateOf<Nature?>(null) }
    var natureTouched by remember { mutableStateOf(false) }
    var accountId by remember { mutableStateOf<String?>(null) }
    var toAccountId by remember { mutableStateOf<String?>(null) }
    var merchant by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(Dates.today()) }
    // 补记模式：由「明细」长按某一天进入，只对这一笔记账生效，保存后自动回到今天。
    var presetApplied by remember { mutableStateOf(false) }
    var hhmm by remember { mutableStateOf(Dates.formatMinute(System.currentTimeMillis()).substring(11, 16)) }
    var images by remember { mutableStateOf(listOf<String>()) }
    var basis by remember { mutableStateOf(ClassifyBasis.MANUAL) }
    var historyNote by remember { mutableStateOf("") }
    var ruleId by remember { mutableStateOf<String?>(null) }
    var reason by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var showAccount by remember { mutableStateOf(false) }
    var showToAccount by remember { mutableStateOf(false) }
    var showCameraPurpose by remember { mutableStateOf(false) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var pendingPath by remember { mutableStateOf<String?>(null) }

    val cats = if (type == TxnType.INCOME) incomeCats else expenseCats

    LaunchedEffect(state.presetDate) {
        val d = state.presetDate
        if (!d.isNullOrEmpty()) { date = d; presetApplied = true; state.presetDate = null }
    }
    LaunchedEffect(ledgerId, accounts.size) {
        if (accountId == null || accounts.none { it.id == accountId }) accountId = accounts.firstOrNull()?.id
        if (toAccountId == null || accounts.none { it.id == toAccountId }) toAccountId = accounts.getOrNull(1)?.id ?: accounts.firstOrNull()?.id
    }
    // 自动分类：综合交易描述、商户、个人规则与历史修改习惯；手动选择优先。
    LaunchedEffect(merchant, note, type, accountId, amountText) {
        // 转账没有分类；退款沿用原账单的分类与性质，不再让分类器改写。
        if (type == TxnType.TRANSFER || type == TxnType.REFUND) return@LaunchedEffect
        delay(350)
        val amt = Money.parseYuanToCents(amountText) ?: 0L
        val s = db.let { Classifier.suggest(it, ledgerId, merchant, note, accountId, type, TxnSource.MANUAL, amt) }
        basis = s.basis
        ruleId = s.ruleId
        reason = s.reason
        if (s.categoryId != null && cats.none { it.id == categoryId }) categoryId = s.categoryId
        if (!natureTouched) {
            nature = s.nature ?: cats.firstOrNull { it.id == categoryId }?.natureDefault
        }
    }
    LaunchedEffect(categoryId) {
        if (!natureTouched) nature = cats.firstOrNull { it.id == categoryId }?.natureDefault
    }
    LaunchedEffect(toast) { if (toast != null) { delay(2200); toast = null } }

    // 从「选择退款账单」页选中原支出后：切到退款模式，并带出物品、商户、分类、账户与金额。
    LaunchedEffect(state.refundTarget) {
        val src = state.refundTarget ?: return@LaunchedEffect
        type = TxnType.REFUND
        amountText = Money.centsToEdit(src.amount)
        merchant = src.merchant.ifEmpty { src.title }
        categoryId = src.categoryId
        nature = src.nature
        natureTouched = true
        if (!src.accountId.isNullOrEmpty()) accountId = src.accountId
        historyNote = "退款对象：" + src.title.ifEmpty { src.merchant } + "（" + src.occurredDate + "）"
        note = ""
        basis = ClassifyBasis.MANUAL
        ruleId = null
        reason = ""
    }

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { uris ->
        uris.forEach { u -> Attachments.importFromUri(ctx, u)?.let { p -> images = images + p } }
    }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val p = pendingPath
        if (ok && p != null) images = images + p
        pendingUri = null; pendingPath = null
    }

    fun pushKey(k: String) {
        if (k == ".") {
            if (!amountText.contains(".")) amountText = if (amountText.isEmpty()) "0." else amountText + "."
        } else {
            val dot = amountText.indexOf('.')
            if (dot >= 0 && amountText.length - dot > 2) return
            if (amountText.length >= 12) return
            amountText = if (amountText == "0") k else amountText + k
        }
    }

    fun doSave() {
        if (saving) return
        val cents = Money.parseYuanToCents(amountText)
        if (cents == null || cents <= 0L) { toast = "请先输入大于 0 的金额"; return }
        // 退款：必须先有原支出账单，退款金额不超过原账单金额（可部分退）。
        val refundSrc = if (type == TxnType.REFUND) state.refundTarget else null
        if (type == TxnType.REFUND && refundSrc == null) {
            toast = "请先在分类里点「退款」，再选一条原支出账单"; return
        }
        if (refundSrc != null && cents > refundSrc.amount) { toast = "退款金额不能超过原账单金额"; return }
        if (type != TxnType.TRANSFER && type != TxnType.REFUND && categoryId == null) { toast = "请选择消费分类"; return }
        if (type == TxnType.TRANSFER) {
            if (accountId == null || toAccountId == null) { toast = "请选择转出与转入账户"; return }
            if (accountId == toAccountId) { toast = "转出与转入账户不能相同"; return }
        }
        saving = true
        try {
            val y = date.substring(0, 4).toInt()
            val mo = date.substring(5, 7).toInt() - 1
            val d = date.substring(8, 10).toInt()
            val at = Dates.toMillis(y, mo, d, hhmm.substring(0, 2).toIntOrNull() ?: 0, hhmm.substring(3, 5).toIntOrNull() ?: 0)
            val cat = cats.firstOrNull { it.id == categoryId }
            val title = when {
                type == TxnType.TRANSFER -> "转账"
                merchant.isNotBlank() -> merchant.trim()
                cat != null -> cat.name
                else -> "未分类"
            }
            // 退款单挂在原支出上：来源、账户、分类沿用原账单，dedup 同样以原账单 + 时间保证幂等。
            val refundTitle = if (refundSrc != null) "退款：" + refundSrc.title.ifEmpty { refundSrc.merchant.ifEmpty { "原账单" } } else title
            val t = Txn(
                id = Ids.newId(), ledgerId = ledgerId, type = type, amount = cents,
                nature = if (type == TxnType.TRANSFER || type == TxnType.INCOME) null else nature,
                categoryId = if (type == TxnType.TRANSFER) null else categoryId,
                accountId = if (refundSrc != null) refundSrc.accountId else accountId,
                toAccountId = if (type == TxnType.TRANSFER) toAccountId else null,
                title = refundTitle,
                merchant = merchant.trim(),
                merchantRaw = merchant.trim(),
                note = note.trim(),
                occurredDate = date, occurredAt = at,
                source = refundSrc?.source ?: TxnSource.MANUAL,
                dedupKey = if (refundSrc != null) "refund|" + refundSrc.id + "|" + at else "",
                classifyBasis = if (basis == ClassifyBasis.MANUAL) ClassifyBasis.MANUAL else basis,
                classifyRuleId = ruleId,
                imagePaths = images,
                refundOf = refundSrc?.id
            )
            val out = db.saveTxn(t)
            if (!out.ok) {
                toast = out.message.ifEmpty { "保存失败，未写入数据库" }
            } else {
                if (ruleId != null) db.bumpRuleHit(ruleId!!)
                // 保存成功后：若配置了 AI 并授权「新增账单」，在后台学习这个商户的分类。
                val learnMerchant = merchant.trim()
                val learnCategory = categoryId
                val learnNature = if (type == TxnType.TRANSFER) null else nature
                val learnCatName = cat?.name ?: ""
                val learnNote = note.trim()
                amountText = ""; merchant = ""; note = ""; images = emptyList()
                natureTouched = false; basis = ClassifyBasis.MANUAL; ruleId = null; reason = ""
                val wasRefund = refundSrc != null
                if (wasRefund) {
                    // 退款存完回到普通支出模式，避免连着把下一笔也记成退款。
                    state.refundTarget = null; type = TxnType.EXPENSE
                    categoryId = null; nature = null; historyNote = ""
                }
                // 补记只作用于这一笔：存完立刻回到今天，后续记账恢复正常日期。
                if (presetApplied) { date = Dates.today(); presetApplied = false }
                state.touch()
                toast = if (wasRefund) "已记录退款，本月支出已相应抵减" else "已保存到「" + state.currentLedger().name + "」"
                if (!wasRefund && type != TxnType.TRANSFER && AiLearn.eligible(learnMerchant, learnCategory, learnNature)) {
                    scope.launch {
                        val learned = AiLearn.learn(db, ledgerId, learnMerchant, learnCategory, learnCatName, learnNature, learnNote, t.id)
                        if (learned != null) toast = "已保存，并为该商户记住分类（" + learned + "）"
                    }
                }
            }
        } catch (e: Exception) {
            toast = "保存失败：" + (e.message ?: "未知错误")
        } finally {
            saving = false
        }
    }

    // 底部 AI 输入行：键盘高度与底部胶囊条高度取较大值作为底部留白，
    // 键盘弹出时输入行随键盘同步上移，收起时停在胶囊条上方，与「AI 聊天记账」页一致。
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val barBottom = if (imeBottom > navBottom) imeBottom else navBottom + 78.dp

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = barBottom + 62.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // 类型切换压缩为一行
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(TxnType.EXPENSE, TxnType.INCOME, TxnType.TRANSFER).forEach { t ->
                    ChipButton(t.label, type == t) {
                        if (type != t) {
                            if (type == TxnType.REFUND) state.refundTarget = null
                            type = t; categoryId = null; nature = null; natureTouched = false
                            basis = ClassifyBasis.MANUAL; ruleId = null; reason = ""; historyNote = ""
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))

            // 金额与消费性质
            GlassPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("¥", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            amountText.ifEmpty { "0.00" },
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (amountText.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (type == TxnType.TRANSFER) "转账金额" else "金额",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // 收入没有长期/即时消费之分，只对支出（含退款）显示性质。
                    if (type != TxnType.TRANSFER && type != TxnType.INCOME) {
                        Spacer(Modifier.height(6.dp))
                        NatureChoiceRow(nature = nature, noneLabel = null) { nature = it; natureTouched = true }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))

            // 日期 / 时间 / 账户 / 图片：同一行胶囊，替代原先的整块表单
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MiniChip(if (presetApplied) Dates.dayHeader(date) + " · 补记" else Dates.dayHeader(date)) { showDate = true }
                MiniChip(hhmm) { showTime = true }
                if (type == TxnType.TRANSFER) {
                    MiniChip("转出 " + (state.account(accountId)?.name ?: "选择")) { showAccount = true }
                    MiniChip("转入 " + (state.account(toAccountId)?.name ?: "选择")) { showToAccount = true }
                } else {
                    MiniChip(state.account(accountId)?.name ?: "选择账户") { showAccount = true }
                }
                Spacer(Modifier.weight(1f))
                MiniIcon(Icons.Default.PhotoLibrary) {
                    pickImages.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                MiniIcon(Icons.Default.PhotoCamera) { showCameraPurpose = true }
            }
            if (presetApplied) {
                Text(
                    "补记：这一次记到 " + date + "，保存后日期自动回到今天",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 2.dp, top = 6.dp)
                )
            }
            if (images.isNotEmpty()) {
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    images.forEach { p ->
                        Box(Modifier.size(48.dp)) {
                            LocalImage(p, Modifier.fillMaxSize())
                            Box(
                                Modifier.align(Alignment.TopEnd).size(20.dp)
                                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
                                    .clickable { images = images - p },
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Default.Close, contentDescription = "移除", modifier = Modifier.size(14.dp)) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))

            // 商户与备注：保留输入能力，行高压缩
            Card2(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 2.dp)) {
                    EditorRow("商户", merchant, "例如：星巴克") { merchant = it; historyNote = "" }
                    Divider2()
                    EditorRow("备注", note, "补充说明（可选）") { note = it }
                }
            }

            // 历史商户快捷选择：与已输入内容模糊匹配；选中后自动带上该商户的历史分类与性质。
            if (type != TxnType.TRANSFER) {
                // 只取同方向（收 / 支）的历史商户，收入不会看到支出的历史记录。
                val tips = if (type == TxnType.INCOME) incomeTips else expenseTips
                val quick: List<MerchantHint> = remember(tips, merchant) {
                    val q = MerchantNormalizer.normalize(merchant)
                    val pool = if (q.isEmpty()) tips else tips.filter { it.norm.contains(q) || q.contains(it.norm) }
                    pool.take(8)
                }
                if (quick.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(quick.size) { i ->
                            val h = quick[i]
                            val hc = cats.firstOrNull { it.id == h.categoryId }
                            MerchantChip(
                                name = h.name,
                                category = hc?.name,
                                selected = merchant.equals(h.name, ignoreCase = true),
                                onClick = {
                                    // 再点一次已选中的历史记录即取消选择。
                                    if (merchant.equals(h.name, ignoreCase = true)) {
                                        merchant = ""; historyNote = ""
                                    } else {
                                        merchant = h.name
                                        if (hc != null) {
                                            categoryId = hc.id
                                            basis = ClassifyBasis.MANUAL
                                            ruleId = null
                                            historyNote = "按历史记录自动选择（" + hc.name + "）"
                                            if (!natureTouched) nature = h.nature ?: hc.natureDefault
                                        } else if (!natureTouched) {
                                            nature = h.nature
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            if (type != TxnType.TRANSFER) {
                Row(Modifier.height(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("分类", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    val hint = historyNote.ifEmpty { if (basis != ClassifyBasis.DEFAULT) reason.ifEmpty { basis.label } else "" }
                    if (hint.isNotEmpty()) {
                        Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 分类行第一项是「退款」：点它去总账单里挑一条支出作为退款对象，
                    // 选好后再点一次可取消，回到普通记账。退款只针对支出，收入下不显示。
                    if (type == TxnType.EXPENSE || type == TxnType.REFUND) item {
                        val refundOn = type == TxnType.REFUND
                        Box(
                            Modifier.height(48.dp).width(64.dp)
                                .background(
                                    if (refundOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(14.dp)
                                )
                                .clickable {
                                    if (refundOn) {
                                        state.refundTarget = null; type = TxnType.EXPENSE
                                        categoryId = null; nature = null; natureTouched = false
                                        amountText = ""; merchant = ""; note = ""; historyNote = ""
                                    } else {
                                        nav.push(Screen.REFUND_PICK)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (refundOn) "退款 ✓" else "退款",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (refundOn) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    items(cats.size) { i ->
                        val c = cats[i]
                        CategoryChip(c, c.id == categoryId) {
                            categoryId = c.id
                            basis = ClassifyBasis.MANUAL
                            ruleId = null
                            reason = ""
                            if (!natureTouched) nature = c.natureDefault
                        }
                    }
                    item {
                        Box(
                            Modifier.height(48.dp).width(56.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
                                .clickable { nav.push(Screen.CATEGORIES) },
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Default.Add, contentDescription = "管理分类") }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            Keypad(
                onKey = { pushKey(it) },
                onDel = { if (amountText.isNotEmpty()) amountText = amountText.substring(0, amountText.length - 1) }
            )
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = { doSave() },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().height(46.dp)
            ) { Text(if (saving) "保存中…" else "保存") }
            Spacer(Modifier.height(4.dp))
        }

        // 底部 AI 输入栏：与旁边的按键一起贴底，浮在保存键等所有内容之上，
        // 但与手机键盘同步升降，不会被键盘覆盖。
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = barBottom)
        ) { ChatCardCompact(state, nav) }

        if (toast != null) {
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = barBottom + 72.dp, start = 24.dp, end = 24.dp)
                    .background(MaterialTheme.colorScheme.inverseSurface, RoundedCornerShape(14.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) { Text(toast ?: "", color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.bodyMedium) }
        }
    }

    if (showDate) {
        val dp = rememberDatePickerState(initialSelectedDateMillis = Dates.utcDayMillis(date))
        LaunchedEffect(Unit) { dp.selectedDateMillis = Dates.utcDayMillis(date) }
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton({
                    dp.selectedDateMillis?.let { date = Dates.fromUtcDayMillis(it) }
                    showDate = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton({ showDate = false }) { Text("取消") } }
        ) { DatePicker(state = dp) }
    }

    if (showTime) {
        val tp = rememberTimePickerState(
            initialHour = hhmm.substring(0, 2).toIntOrNull() ?: 0,
            initialMinute = hhmm.substring(3, 5).toIntOrNull() ?: 0,
            is24Hour = true
        )
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showTime = false },
            title = { Text("选择时间") },
            text = { TimePicker(state = tp) },
            confirmButton = {
                TextButton({
                    hhmm = (if (tp.hour < 10) "0" else "") + tp.hour + ":" + (if (tp.minute < 10) "0" else "") + tp.minute
                    showTime = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton({ showTime = false }) { Text("取消") } }
        )
    }

    if (showAccount) {
        ChoiceSheet("选择账户", accounts.map { it.name to it.id }, onPick = { accountId = it }, onDismiss = { showAccount = false })
    }
    if (showToAccount) {
        ChoiceSheet("选择转入账户", accounts.map { it.name to it.id }, onPick = { toAccountId = it }, onDismiss = { showToAccount = false })
    }

    if (showCameraPurpose) {
        ConfirmDialog(
            title = "使用相机",
            text = "用于拍摄小票或支付截图，照片只保存在本机应用私有目录，用于生成这笔账单的附图与识别，不会自动上传。",
            confirmText = "继续",
            onConfirm = {
                showCameraPurpose = false
                val (uri, path) = Attachments.newCaptureTarget(ctx)
                pendingUri = uri; pendingPath = path
                takePicture.launch(uri)
            },
            onDismiss = { showCameraPurpose = false }
        )
    }
}

@Composable
private fun CategoryChip(c: Category, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.width(56.dp).height(48.dp).background(bg, RoundedCornerShape(14.dp)).clickable { onClick() }.padding(5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val look = CatIcons.look(c.icon, c.name)
        Icon(look.icon, contentDescription = c.name, tint = if (selected) fg else look.color, modifier = Modifier.size(18.dp))
        Text(c.name, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1)
    }
}

@Composable
private fun FieldRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun EditorRow(label: String, value: String, hint: String, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun Keypad(onKey: (String) -> Unit, onDel: () -> Unit) {
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(".", "0", "⌫"))
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp)).padding(vertical = 4.dp)) {
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth()) {
                r.forEach { k ->
                    Box(
                        Modifier.weight(1f).height(36.dp).clickable { if (k == "⌫") onDel() else onKey(k) },
                        contentAlignment = Alignment.Center
                    ) { Text(k, style = MaterialTheme.typography.titleLarge) }
                }
            }
        }
    }
}

@Composable
private fun ChatCardCompact(state: AppState, nav: NavStack) {
    // 需求第十九节：记账页保留内嵌 AI 聊天输入行；输入框初始一行胶囊，超过两行后长高、最高 3 行（整页可滚动，不会把键盘顶出屏幕）。
    ChatPane(state = state, nav = nav, expanded = false, slim = true, modifier = Modifier.fillMaxWidth())
}

/** 极简胶囊：日期 / 时间 / 账户等轻量选择，点击后弹原有对话框。 */
@Composable
private fun MiniChip(text: String, onClick: () -> Unit) {
    Box(
        Modifier.height(34.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(17.dp))
            .clickable { onClick() }.padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
}

/** 极简图标按钮：相册 / 拍照。 */
@Composable
private fun MiniIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(17.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(17.dp)) }
}

/** 历史商户胶囊：名称右侧用浅色小字标出该商户的历史分类，点选即带回填。 */
@Composable
private fun MerchantChip(name: String, category: String?, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.height(34.dp).background(bg, RoundedCornerShape(17.dp)).clickable { onClick() }.padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.History, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(name, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
        if (!category.isNullOrEmpty()) {
            Spacer(Modifier.width(5.dp))
            Text(category, style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.75f), maxLines = 1)
        }
    }
}

