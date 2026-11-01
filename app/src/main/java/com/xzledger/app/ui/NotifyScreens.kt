package com.xzledger.app.ui

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.xzledger.app.BuildConfig
import com.xzledger.app.XzApp
import com.xzledger.app.autorecord.AutoRecordEngine
import com.xzledger.app.autorecord.NoticeDedup
import com.xzledger.app.autorecord.NoticeInput
import com.xzledger.app.autorecord.NoticeKind
import com.xzledger.app.autorecord.NotifyListenerService
import com.xzledger.app.autorecord.NotifyPrefs
import com.xzledger.app.autorecord.TxnParser
import com.xzledger.app.data.ClassifyBasis
import com.xzledger.app.data.Ids
import com.xzledger.app.data.NoticeLog
import com.xzledger.app.data.PendingTxn
import com.xzledger.app.data.SettingsKeys
import com.xzledger.app.data.Txn
import com.xzledger.app.data.TxnSource
import com.xzledger.app.data.TxnType
import com.xzledger.app.data.clearNoticeLog
import com.xzledger.app.data.clearPending
import com.xzledger.app.data.deletePending
import com.xzledger.app.data.noticeLogs
import com.xzledger.app.data.pendingCount
import com.xzledger.app.data.pendingTxns
import com.xzledger.app.data.saveTxn
import com.xzledger.app.util.Money
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
private val clockFmt = SimpleDateFormat("HH:mm", Locale.CHINA)

private fun openNotifyAccessSettings(ctx: android.content.Context) {
    val intents = listOf(
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                android.content.ComponentName(ctx, NotifyListenerService::class.java).flattenToString()),
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (i in intents) {
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (ignored: Throwable) {
        }
    }
}

private fun actionLabel(action: String): String = when (action) {
    "auto" -> "已自动记账"
    "pending" -> "待确认"
    "duplicate" -> "重复已跳过"
    "filtered" -> "已过滤"
    "error" -> "解析失败"
    else -> action
}

private fun actionColor(action: String): Color = when (action) {
    "auto" -> Color(0xFF4C9A6A)
    "duplicate" -> Color(0xFFC9A227)
    "filtered" -> Color(0xFF8A8A8A)
    "error" -> Color(0xFFE2604A)
    else -> Color(0xFF5B8DEF)
}

@Composable
fun NotifyAutoScreen(state: AppState, nav: NavStack) {
    val ctx = LocalContext.current
    val store = XzApp.store
    val db = XzApp.db
    var tick by remember { mutableIntStateOf(0) }
    var access by remember { mutableStateOf(NotifyPrefs.hasAccess(ctx)) }
    var explained by remember { mutableStateOf(store.bool(SettingsKeys.NOTIFY_EXPLAINED, false)) }
    var showAll by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var confirmAuto by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // 从系统设置返回后授权状态会变；轮询比依赖生命周期回调更稳（页面始终只有一份）。
    LaunchedEffect(Unit) {
        while (true) {
            access = NotifyPrefs.hasAccess(ctx)
            delay(1500)
        }
    }

    val enabled = store.bool(SettingsKeys.NOTIFY_ENABLED, false)
    val mode = remember(tick) { NotifyPrefs.mode(store) }
    val selected = remember(tick) { NotifyPrefs.packages(store) }
    val autoSel = remember(tick) { NotifyPrefs.autoPackages(store) }
    val pending = remember(state.dataVersion, tick) { db.pendingCount(state.ledgerId) }
    val installed = remember { NotifyPrefs.installedApps(ctx) }
    val common = remember { TxnParser.sources.flatMap { it.packages }.toSet() }

    fun toggleListen(pkg: String, on: Boolean) {
        val cur = NotifyPrefs.packages(store).toMutableList()
        if (on) {
            if (pkg !in cur) cur.add(pkg)
            if (!access) openNotifyAccessSettings(ctx)
        } else {
            cur.remove(pkg)
            val auto = NotifyPrefs.autoPackages(store).toMutableSet()
            auto.remove(pkg)
            NotifyPrefs.setAutoPackages(store, auto)
        }
        NotifyPrefs.setPackages(store, cur)
        tick++
    }

    fun toggleAuto(pkg: String, on: Boolean) {
        val cur = NotifyPrefs.autoPackages(store).toMutableSet()
        if (on) cur.add(pkg) else cur.remove(pkg)
        NotifyPrefs.setAutoPackages(store, cur)
        tick++
    }

    val apps = remember(installed, selected, showAll, query) {
        val base = if (showAll || query.isNotBlank()) installed
        else installed.filter { it.pkg in common || it.pkg in selected }
        val q = query.trim()
        if (q.isEmpty()) base else base.filter { it.label.contains(q, true) || it.pkg.contains(q, true) }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "通知自动记账", onBack = { nav.pop() })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 140.dp)) {
            if (!explained) {
                item {
                    GlassPanel(Modifier.fillMaxWidth().padding(bottom = 12.dp), contentPadding = PaddingValues(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Warning, null, tint = MaterialTheme.colorScheme.tertiary)
                            Spacer(Modifier.width(8.dp))
                            Text("先读这段，再决定是否开启", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "· 只读取你在这里勾选的应用发来的通知文本，解析全部在本机完成，默认不上传任何通知内容。\n" +
                                "· 不申请无障碍服务、不读取短信数据库、不获取银行账号与密码，也不会替你操作任何银行 App。\n" +
                                "· 通知里可能含金额、商户与卡尾号；应用只保留结构化摘要，验证码/密码/登录令牌类通知会被直接丢弃、不落库。\n" +
                                "· 可随时在系统「通知使用权」里撤销授权，或关闭开关并清除本地解析记录。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        TextButton({
                            store.setBool(SettingsKeys.NOTIFY_EXPLAINED, true)
                            explained = true
                            openNotifyAccessSettings(ctx)
                        }) { Text("我已了解，去开启通知使用权") }
                    }
                }
            }

            // 授权状态
            item {
                GlassPanel(Modifier.fillMaxWidth().padding(bottom = 12.dp), contentPadding = PaddingValues(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(9.dp).background(
                                if (access) Color(0xFF4C9A6A) else Color(0xFFE2604A), CircleShape
                            )
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (access) "通知使用权：已授予" else "通知使用权：未授予", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (access) {
                            "系统监听服务：" + (if (NotifyListenerService.connected) "已连接" else "未连接（下次通知或重启后自动恢复）") +
                                "。只有下方勾选的应用的通知会被读取。"
                        } else {
                            "需要你在系统设置里允许「猫猫记账」读取通知，否则收不到任何交易通知。本应用不会用无障碍或其它方式绕过。"
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Row {
                        TextButton({ openNotifyAccessSettings(ctx) }) {
                            Text(if (access) "打开系统设置" else "去授予权限")
                        }
                        TextButton({
                            access = NotifyPrefs.hasAccess(ctx)
                            tick++
                        }) { Text("刷新状态") }
                    }
                }
            }

            // 总开关与模式
            item {
                GlassPanel(Modifier.fillMaxWidth().padding(bottom = 12.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    SwitchRow(
                        "启用通知自动记账",
                        if (!access) "尚未授权通知使用权，开启也不会生效" else if (enabled) "已开启" else "已关闭",
                        checked = enabled,
                    ) { on ->
                        store.setBool(SettingsKeys.NOTIFY_ENABLED, on)
                        if (on && !access) openNotifyAccessSettings(ctx)
                        tick++
                    }
                }
            }

            item {
                GlassPanel(Modifier.fillMaxWidth().padding(bottom = 12.dp), contentPadding = PaddingValues(16.dp)) {
                    Text("模式", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChipButton("关闭", mode == NotifyPrefs.MODE_OFF, Modifier.weight(1f)) {
                            NotifyPrefs.setMode(store, NotifyPrefs.MODE_OFF); tick++
                        }
                        ChipButton("待确认", mode == NotifyPrefs.MODE_CONFIRM, Modifier.weight(1f)) {
                            NotifyPrefs.setMode(store, NotifyPrefs.MODE_CONFIRM); tick++
                        }
                        ChipButton("自动记账", mode == NotifyPrefs.MODE_AUTO, Modifier.weight(1f)) {
                            confirmAuto = true
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when (mode) {
                            NotifyPrefs.MODE_OFF -> "关闭：即使授权也不解析任何通知，已有记录保留。"
                            NotifyPrefs.MODE_CONFIRM -> "待确认（默认）：所有识别结果先进入待确认，由你逐笔确认后才写入真实账本。"
                            else -> "自动记账：只有高置信度、账户已匹配、非重复的收支才会直接入账；退款、转账、还款与低置信度仍进待确认。"
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                GlassPanel(Modifier.fillMaxWidth().padding(bottom = 12.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    SettingRow(
                        "待确认账单",
                        if (pending > 0) "有 " + pending + " 笔等待你确认；确认前不会影响统计" else "暂无待确认",
                        onClick = { nav.push(Screen.NOTIFY_PENDING) }
                    ) { Text(if (pending > 0) pending.toString() else "", color = MaterialTheme.colorScheme.primary) }
                    SettingRow("自动记账记录", "最近 100 条解析结果；不保存通知原文", onClick = { nav.push(Screen.NOTIFY_LOG) })
                    SettingRow("清除本地解析记录", "清空待确认与记录；已入账的账单不受影响", onClick = { confirmClear = true })
                }
            }

            // 调试版专用的端到端自检入口：走真实解析与待确认流程，不写真实账单。
            if (BuildConfig.DEBUG) {
                item {
                    GlassPanel(Modifier.fillMaxWidth().padding(bottom = 12.dp), contentPadding = PaddingValues(16.dp)) {
                        Text("调试工具（仅调试版）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "注入一条虚构的银行通知，走完整「解析 → 判断 → 待确认」流程，用来验证链路；不会改动已记录的账单。请先在上方勾选任意一个监听应用。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton({
                            val pkgs = NotifyPrefs.packages(store)
                            val pkg = pkgs.firstOrNull()
                            if (pkg == null) {
                                hint = "请先在上方勾选一个监听应用"
                            } else {
                                AutoRecordEngine.submit(
                                    ctx.applicationContext,
                                    NoticeInput(
                                        pkg = pkg,
                                        appLabel = NotifyPrefs.labelOf(ctx, pkg),
                                        title = NotifyPrefs.labelOf(ctx, pkg),
                                        text = "您尾号1234的储蓄卡于11月1日12:30消费人民币35.80元，商户：星巴克，账户余额2580.36元。",
                                        postedAt = System.currentTimeMillis()
                                    )
                                )
                                hint = "已注入一条虚构通知，稍后回本页查看待确认"
                                scope.launch { delay(1500); tick++ }
                            }
                        }) { Text("注入测试通知") }
                    }
                }
            }

            // 监听应用
            item {
                SectionTitle("监听应用")
                Text(
                    "只勾选你信任的银行与支付应用。默认只列出已安装的常用来源；其他应用可搜索后添加。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )
                Spacer(Modifier.height(6.dp))
                SimpleSearchField(query, { query = it }, "搜索应用名称或包名")
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("已选 " + selected.size + " 个", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    TextButton({ showAll = !showAll }) { Text(if (showAll) "只看常用" else "显示全部已安装应用") }
                }
            }

            if (apps.isEmpty()) {
                item { EmptyHint("没有匹配的应用；可点「显示全部已安装应用」再搜索。") }
            }

            items(apps, key = { it.pkg }) { app ->
                val listening = app.pkg in selected
                val auto = app.pkg in autoSel
                GlassPanel(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.bodyLarge)
                            Text(app.pkg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        GlassSwitch(checked = listening, onCheckedChange = { toggleListen(app.pkg, it) })
                    }
                    if (listening) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("允许自动入账", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    if (mode == NotifyPrefs.MODE_AUTO) "仅对高置信度、账户已匹配、非重复的收支生效"
                                    else "当前是待确认模式，打开也会先进待确认",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            GlassSwitch(checked = auto, onCheckedChange = { toggleAuto(app.pkg, it) })
                        }
                    }
                }
            }

            // 解析规则（只读展示，说明支持范围，不臆造未安装的银行包名）
            item {
                Spacer(Modifier.height(8.dp))
                SectionTitle("解析规则")
                GlassPanel(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentPadding = PaddingValues(16.dp)) {
                    Text(
                        "内置 " + TxnParser.sources.size + " 组来源规则：支付宝、微信支付、云闪付，以及工商、建设、农业、中国、招商、交通、邮储等银行的常见通知格式；" +
                            "未内置的应用也能用通用规则解析（金额、方向、商户、余额），只是置信度更低、通常只会进待确认。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    val installedPkgs = installed.map { it.pkg }.toSet()
                    TxnParser.sources.forEach { s ->
                        val anyInstalled = s.packages.any { it in installedPkgs }
                        Text(
                            "· " + s.name + if (anyInstalled) "（已安装）" else "（未安装）",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (anyInstalled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "同一笔交易可能同时来自银行 App 与支付宝/微信：应用会把金额相同、方向相同、10 分钟内、来源不同的记录标成「疑似重复」，只提示、不删除、不自动合并。",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                SectionTitle("隐私说明")
                GlassPanel(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
                    Text(
                        "· 所有解析与存储都在本机；默认不联网络、不上传通知文本，也不接入任何第三方分析。\n" +
                            "· 日志只记录「时间 / 应用 / 金额 / 动作 / 原因」，不保存通知原文、不保存完整卡号，尾号只用于匹配账户。\n" +
                            "· 验证码、密码、登录令牌、安全码等敏感通知会被直接丢弃。\n" +
                            "· 关闭开关后不再处理新通知；「清除本地解析记录」只清待确认与日志，已入账账单需在明细里手动删除。\n" +
                            "· 自动记账不涉及任何银行操作，也不要求提供银行账号密码。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            hint?.let { h ->
                item {
                    Text(h, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(16.dp))
                }
            }
        }
    }

    if (confirmAuto) {
        ConfirmDialog(
            "开启自动记账？",
            "只有「置信度高、能匹配到账户、不是重复、也不是退款/转账/还款」的收支才会直接写入账本；" +
                "其余一律进入待确认，不会自动入账，也不会删除任何已有账单。",
            confirmText = "开启",
            onConfirm = {
                NotifyPrefs.setMode(store, NotifyPrefs.MODE_AUTO)
                if (!store.bool(SettingsKeys.NOTIFY_ENABLED, false)) store.setBool(SettingsKeys.NOTIFY_ENABLED, true)
                confirmAuto = false
                tick++
            },
            onDismiss = { confirmAuto = false }
        )
    }

    if (confirmClear) {
        ConfirmDialog(
            "清除本地解析记录？",
            "会删除全部待确认记录与解析日志；已经写入账本的账单不会被删除。",
            confirmText = "清除",
            onConfirm = {
                db.clearPending(state.ledgerId)
                db.clearNoticeLog(state.ledgerId)
                confirmClear = false
                tick++
                hint = "已清除本地解析记录"
                Toast.makeText(ctx, "已清除本地解析记录", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { confirmClear = false }
        )
    }
}

@Composable
fun NotifyPendingScreen(state: AppState, nav: NavStack) {
    val ctx = LocalContext.current
    val db = XzApp.db
    var tick by remember { mutableIntStateOf(0) }
    var pickFor by remember { mutableStateOf<PendingTxn?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    val list = remember(state.dataVersion, tick) { db.pendingTxns(state.ledgerId) }
    val accounts = remember(state.dataVersion) { db.accounts(state.ledgerId).filter { !it.archived } }

    fun post(p: PendingTxn, accountId: String) {
        val now = System.currentTimeMillis()
        val txn = Txn(
            id = Ids.newId(),
            ledgerId = state.ledgerId,
            type = p.type,
            amount = p.amount,
            currency = p.currency,
            nature = p.nature,
            categoryId = p.categoryId,
            accountId = accountId,
            title = p.title.ifEmpty { p.merchant },
            merchant = p.merchant,
            merchantRaw = p.merchant,
            note = p.note,
            occurredDate = p.occurredDate,
            occurredAt = p.occurredAt,
            createdAt = now,
            updatedAt = now,
            source = TxnSource.NOTIFY,
            sourcePlatform = p.pkg,
            tradeNo = p.serial,
            dedupKey = NoticeDedup.txnDedupKey(p.fingerprint),
            classifyBasis = ClassifyBasis.MANUAL,
        )
        val out = db.saveTxn(txn)
        if (out.ok) {
            db.deletePending(p.id)
            state.touch()
            tick++
            hint = "已入账：" + Money.centsToPlain(p.amount) + " " + txn.title
            Toast.makeText(ctx, "已入账", Toast.LENGTH_SHORT).show()
        } else {
            hint = out.message
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "待确认账单", onBack = { nav.pop() }) {
            if (list.isNotEmpty()) {
                TextButton({ confirmClear = true }) { Text("全部忽略") }
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            item {
                GlassPanel(Modifier.fillMaxWidth().padding(bottom = 12.dp), contentPadding = PaddingValues(16.dp)) {
                    Text(
                        "这些结果置信度不足、没有匹配到账户、疑似重复或属于退款/转账，需要你确认后才会写入真实账本。忽略只会删除这条待确认记录，不会删除任何已入账账单。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (list.isEmpty()) {
                item { EmptyHint("没有待确认账单。") }
            }
            items(list, key = { it.id }) { p ->
                val postable = p.type != TxnType.TRANSFER && p.type != TxnType.REFUND &&
                    p.kind != NoticeKind.CREDIT_REPAY.id && p.kind != NoticeKind.WITHDRAW.id && p.kind != NoticeKind.TOPUP.id
                val account = accounts.firstOrNull { it.id == p.accountId }
                GlassPanel(Modifier.fillMaxWidth().padding(bottom = 10.dp), contentPadding = PaddingValues(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            NoticeKind.entries.firstOrNull { it.id == p.kind }?.label ?: "交易",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "置信度 " + p.confidence + "%", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                        Text(p.appLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.title.ifEmpty { p.merchant.ifEmpty { "未识别商户" } },
                            style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            (if (p.type == TxnType.INCOME) "+" else "-") + Money.centsToPlain(p.amount),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                            color = if (p.type == TxnType.INCOME) Color(0xFF4C9A6A) else Color(0xFFE2604A)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        (if (p.occurredDate.isNotEmpty()) p.occurredDate + " " else "") + clockFmt.format(Date(p.occurredAt)) +
                            (if (p.cardTail.isNotEmpty()) " · 尾号 " + p.cardTail else "") +
                            (if (account != null) " · 账户 " + account.name else " · 未匹配账户"),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (p.reason.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(p.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        TextButton({
                            db.deletePending(p.id); tick++
                            hint = "已忽略这条待确认记录"
                        }) { Text("忽略") }
                        if (postable) {
                            TextButton({
                                if (p.accountId.isNullOrEmpty()) pickFor = p
                                else post(p, p.accountId)
                            }) { Text("确认入账") }
                        } else {
                            Text("需在记账页手动处理")
                        }
                    }
                }
            }
            hint?.let { h ->
                item { Text(h, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(8.dp)) }
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            "全部忽略？", "会删除所有待确认记录；已入账账单不受影响。", confirmText = "全部忽略",
            onConfirm = {
                db.clearPending(state.ledgerId)
                confirmClear = false
                tick++
            },
            onDismiss = { confirmClear = false }
        )
    }

    pickFor?.let { p ->
        if (accounts.isEmpty()) {
            Toast.makeText(ctx, "还没有账户，请先在「账户管理」里添加", Toast.LENGTH_SHORT).show()
            pickFor = null
        } else {
            ChoiceSheet("选择这笔账单的账户", accounts.map { it.name to it.id }, onPick = { id ->
                post(p, id)
                pickFor = null
            }, onDismiss = { pickFor = null })
        }
    }
}

@Composable
fun NotifyLogScreen(state: AppState, nav: NavStack) {
    val db = XzApp.db
    var tick by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    val logs = remember(state.dataVersion, tick) { db.noticeLogs(state.ledgerId, 100) }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "自动记账记录", onBack = { nav.pop() }) {
            if (logs.isNotEmpty()) TextButton({ confirmClear = true }) { Text("清空") }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            item {
                Text(
                    "只记录结构化摘要，不保存通知原文或完整卡号。最多保留最近 100 条。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
            }
            if (logs.isEmpty()) item { EmptyHint("还没有解析记录。") }
            items(logs, key = { it.id }) { log: NoticeLog ->
                GlassPanel(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentPadding = PaddingValues(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(actionLabel(log.action), style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold, color = actionColor(log.action))
                        Spacer(Modifier.weight(1f))
                        Text(timeFmt.format(Date(log.createdAt)), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(log.appLabel, style = MaterialTheme.typography.bodyMedium)
                    if (log.detail.isNotEmpty()) {
                        Text(log.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    log.amount?.let {
                        Text(Money.centsToPlain(it), style = MaterialTheme.typography.bodyMedium,
                            color = if (log.action == "auto") Color(0xFF4C9A6A) else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            "清空记录？", "只清空解析日志，不影响任何已入账账单。", confirmText = "清空",
            onConfirm = {
                db.clearNoticeLog(state.ledgerId)
                confirmClear = false
                tick++
            },
            onDismiss = { confirmClear = false }
        )
    }
}
