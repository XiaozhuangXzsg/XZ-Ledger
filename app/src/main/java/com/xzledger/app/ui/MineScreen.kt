package com.xzledger.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xzledger.app.AppState
import com.xzledger.app.XzApp
import com.xzledger.app.data.AppLinks
import com.xzledger.app.data.SettingsKeys
import com.xzledger.app.data.pendingCount
import com.xzledger.app.autorecord.NotifyPrefs
import com.xzledger.app.data.monthBalance
import com.xzledger.app.data.monthBudgetTotal
import com.xzledger.app.data.monthSummary
import com.xzledger.app.util.Attachments
import com.xzledger.app.util.Dates
import com.xzledger.app.update.Updater
import com.xzledger.app.util.Money

private val M_BLUE = Color(0xFF74B9FF)
private val M_AMBER = Color(0xFFFFC46B)
private val M_GREEN = Color(0xFF7ED49B)
private val M_PURPLE = Color(0xFFB79BFF)
private val M_PINK = Color(0xFFFF9BC2)
private val M_TEAL = Color(0xFF6FD0C4)
private val M_GOLD = Color(0xFFE8C46B)
private val M_GRAY = Color(0xFFA8B0BC)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MineScreen(state: AppState, nav: NavStack) {
    val db = XzApp.db
    val ctx = LocalContext.current
    val store = state.store
    val version = state.dataVersion
    var nickname by remember { mutableStateOf(store.str(SettingsKeys.NICKNAME, "").ifBlank { "猫猫" }) }
    var avatar by remember { mutableStateOf(store.str(SettingsKeys.AVATAR, "")) }
    var editName by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf("") }
    var showReminder by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    val anime = animeEnabled()

    val ledger = state.currentLedger()
    // 需求：「账户余额」改为随所选月份更新，按月预算重新开始统计（口径与明细页「结余」一致）。
    val month = state.month
    val sum = remember(version, state.ledgerId, month) { db.monthSummary(state.ledgerId, month) }
    val monthBalance = remember(version, state.ledgerId, month) { db.monthBalance(state.ledgerId, month) }
    val hasBudget = remember(version, state.ledgerId, month) { db.monthBudgetTotal(state.ledgerId, month) != null }

    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            Attachments.importFromUri(ctx, uri)?.let {
                avatar = it
                store.setStr(SettingsKeys.AVATAR, it)
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(18.dp))
        // 顶部：头像 + 昵称（对齐主流记账应用的「我的」页）
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(84.dp)) {
                Box(
                    Modifier.fillMaxSize().clip(CircleShape)
                        // 设了头像就不要再垫淡黄色底，避免桑多涅外面套一圈黄；只有默认首字母才需要底色。
                        .background(if (avatar.isEmpty() && !anime) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                        .clickable { pickAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    contentAlignment = Alignment.Center
                ) {
                    if (avatar.isNotEmpty()) {
                        LocalImage(avatar, Modifier.fillMaxSize())
                    } else if (anime) {
                        // 未设头像时用桑多涅看板娘（个性化设置 → 二次元元素 可关）
                        SandroneFace()
                    } else {
                        Text(
                            nickname.take(1),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Box(
                    Modifier.align(Alignment.BottomEnd).size(24.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .clickable { pickAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Edit, contentDescription = "更换头像", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(13.dp)) }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(nickname, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Icon(
                    Icons.Default.Edit, contentDescription = "修改昵称",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp).size(15.dp).clickable {
                        nameDraft = nickname; editName = true
                    }
                )
            }
            Text("头像仅保存在本机", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(16.dp))
        // 三个统计（无卡片，轻量呈现）
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            MineStat(Dates.monthLabel(month) + " 支出", "¥" + Money.centsToPlain(sum.netExpense), Modifier.weight(1f), M_PINK)
            MineStat("本月笔数", sum.count.toString(), Modifier.weight(1f), M_BLUE)
            MineStat("本月结余", "¥" + Money.centsToPlain(monthBalance), Modifier.weight(1f),
                if (monthBalance >= 0) M_GREEN else M_PINK)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "当前账本「" + ledger.name + "」；已删除账单不参与统计，转账在账户之间互抵。" +
                (if (hasBudget) "本月结余 = 本月预算 + 收入 − 支出（净）。"
                else "本月未设置预算，结余 = 收入 − 支出（净）。") +
                "在明细页切换月份后，这里也随之更新。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
        )

        // 看板娘桑多涅（个性化设置 → 二次元元素 可关）；有可用 AI 时长按 3 秒可让其评价本月账单。
        if (anime) {
            Spacer(Modifier.height(12.dp))
            SandroneAdvicePanel(state)
        }

        if (hint != null) {
            Text(
                hint ?: "", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )
        }

        Spacer(Modifier.height(10.dp))
        MineGroup("数据统计") {
            // 需求：月度统计放在最上方。
            IconMenuRow(Icons.Outlined.BarChart, M_BLUE, "月度统计", "左右滑动切月；分类占比、商户排行、趋势") { nav.push(Screen.STATS) }
            IconMenuRow(Icons.Outlined.Savings, M_GREEN, "预算设置", "月度总预算（可设每月默认）与分类预算") { nav.push(Screen.BUDGET) }
            IconMenuRow(Icons.Outlined.EventRepeat, M_TEAL, "周期记账与订阅提醒", "房租、话费、会员；可选自动生成账单或仅提醒") { nav.push(Screen.RECURRING) }
        }
        MineGroup("智能记账") {
            IconMenuRow(Icons.Outlined.SmartToy, M_TEAL, "AI 聊天记账", "用一句话记账、查询与统计") { nav.push(Screen.AI_CHAT) }
            IconMenuRow(Icons.Outlined.AutoAwesome, M_PURPLE, "AI API 配置", "服务名称、接口地址、API Key、模型") { nav.push(Screen.AI_CONFIG) }
            IconMenuRow(Icons.Outlined.Lock, M_BLUE, "AI 权限管理", "读账本与新增账单分别授权") { nav.push(Screen.AI_PERM) }
            val notifyOn = store.bool(SettingsKeys.NOTIFY_ENABLED, false)
            val notifyPending = remember(state.dataVersion) { db.pendingCount(state.ledgerId) }
            IconMenuRow(
                Icons.Outlined.NotificationsActive, M_AMBER, "通知自动记账",
                if (!notifyOn) "未开启；开启后自动识别银行与支付通知"
                else (if (NotifyPrefs.mode(store) == NotifyPrefs.MODE_AUTO) "自动记账" else "待确认") +
                    (if (notifyPending > 0) "　有 " + notifyPending + " 笔待确认" else "　暂无待确认")
            ) { nav.push(Screen.NOTIFY_AUTO) }
            IconMenuRow(Icons.Outlined.Rule, M_AMBER, "个人分类规则", "商户 / 关键词 / 账户 / 方向 / 来源") { nav.push(Screen.RULES) }
            IconMenuRow(Icons.Outlined.MenuBook, M_GREEN, "学习记录", "历史修改与建议规则") { nav.push(Screen.LEARNING) }
        }
        MineGroup("数据管理") {
            IconMenuRow(Icons.Outlined.FileUpload, M_BLUE, "导入账单", "微信 / 支付宝 / 通用 CSV / Excel / PDF") { nav.push(Screen.IMPORT_PICKER) }
            IconMenuRow(Icons.Outlined.Backup, M_GREEN, "导出与备份恢复", "CSV 导出、完整备份与恢复") { nav.push(Screen.BACKUP) }
            IconMenuRow(Icons.Outlined.Category, M_AMBER, "分类管理", "分类图标、性质默认值与排序") { nav.push(Screen.CATEGORIES) }
            IconMenuRow(Icons.Outlined.AccountBalanceWallet, M_GOLD, "账户管理", "现金 / 储蓄卡 / 信用卡 / 支付宝 / 微信") { nav.push(Screen.ACCOUNTS) }
            IconMenuRow(Icons.Outlined.LibraryBooks, M_PURPLE, "账本管理", "新增、重命名与切换") { nav.push(Screen.LEDGERS) }
            IconMenuRow(Icons.Outlined.RestoreFromTrash, M_GRAY, "回收站", "恢复误删账单") { nav.push(Screen.TRASH) }
            IconMenuRow(Icons.Outlined.Cloud, M_TEAL, "云同步", "适配层接入，未配置时本地照常可用") { nav.push(Screen.SYNC) }
            IconMenuRow(
                Icons.Outlined.NotificationsNone, M_AMBER, "记账提醒",
                if (store.bool(SettingsKeys.REMINDER_ENABLED)) "已开启　每天 " + store.str(SettingsKeys.REMINDER_TIME, "21:00") else "未开启"
            ) { showReminder = true }
        }
        MineGroup("个性化") {
            IconMenuRow(Icons.Outlined.Palette, M_PURPLE, "主题 / 字体 / 语言", "浅色、深色、跟随系统与配色") { nav.push(Screen.SETTINGS) }
            IconMenuRow(Icons.Outlined.Animation, M_PINK, "动画与液态玻璃", "淡化或平滑出现；不支持模糊时降级为半透明") { nav.push(Screen.SETTINGS) }
            IconMenuRow(Icons.Outlined.HelpOutline, M_BLUE, "应用锁与 ColorOS 设置帮助", "生物识别或应用密码；权限与后台指引") { nav.push(Screen.HELP) }
        }
        MineGroup("更多") {
            IconMenuRow(Icons.Outlined.BarChart, M_BLUE, "年度账单", "年收支、分类分布、长期与即时占比") { nav.push(Screen.ANNUAL) }
            IconMenuRow(Icons.Outlined.Info, M_GRAY, "关于", "版本 " + APP_VERSION + "、开发者与反馈") { nav.push(Screen.ABOUT) }
            IconMenuRow(
                Icons.Outlined.Refresh, M_GREEN, "检查更新",
                "当前 " + APP_VERSION + "；从 GitHub 正式 Release 检查新版本"
            ) { Updater.check(ctx, store, manual = true) }
            IconMenuRow(Icons.Outlined.StarBorder, M_GOLD, "给项目 Star", "打开 XZ-Ledger 仓库，不会替你点 Star") {
                val url = store.str(SettingsKeys.GITHUB_URL, AppLinks.GITHUB)
                if (url.isEmpty()) hint = "尚未配置 GitHub 地址，请到「关于」中填写后再打开"
                else try {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) { hint = "无法打开链接：" + url }
            }
            IconMenuRow(Icons.Outlined.Share, M_TEAL, "分享应用", "分享到 GitHub 最新 Release 的链接") {
                val url = store.str(SettingsKeys.DOWNLOAD_URL, AppLinks.RELEASE)
                val text = "猫猫记账：面向大学生的本地优先记账应用。" + (if (url.isEmpty()) "（下载地址尚未配置）" else " 最新版下载：" + url)
                val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
                try { ctx.startActivity(Intent.createChooser(i, "分享应用")) } catch (e: Exception) { hint = "无法调用系统分享" }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "猫猫记账 " + APP_VERSION + "　本地优先，离线可用",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(150.dp))
    }

    if (editName) {
        AlertDialog(
            onDismissRequest = { editName = false },
            title = { Text("修改昵称") },
            text = {
                OutlinedTextField(value = nameDraft, onValueChange = { nameDraft = it }, singleLine = true, label = { Text("昵称") })
            },
            confirmButton = {
                TextButton({
                    val n = nameDraft.trim().ifEmpty { "猫猫" }
                    nickname = n
                    store.setStr(SettingsKeys.NICKNAME, n)
                    editName = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton({ editName = false }) { Text("取消") } }
        )
    }

    if (showReminder) {
        var enabled by remember { mutableStateOf(store.bool(SettingsKeys.REMINDER_ENABLED)) }
        val t = store.str(SettingsKeys.REMINDER_TIME, "21:00").takeIf { it.length >= 5 } ?: "21:00"
        // 时间改用上下滚动的滚轮选择（与年月选择一致）。
        val hours = remember { (0..23).map { (if (it < 10) "0" else "") + it + " 时" } }
        val minutes = remember { (0..59).map { (if (it < 10) "0" else "") + it + " 分" } }
        var selHour by remember { mutableStateOf(t.substring(0, 2).toIntOrNull()?.coerceIn(0, 23) ?: 21) }
        var selMinute by remember { mutableStateOf(t.substring(3, 5).toIntOrNull()?.coerceIn(0, 59) ?: 0) }
        AlertDialog(
            onDismissRequest = { showReminder = false },
            title = { Text("记账提醒") },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("每天提醒", Modifier.weight(1f))
                        GlassSwitch(checked = enabled, onCheckedChange = { enabled = it })
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "提醒时间",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        WheelPicker(hours, selHour, Modifier.weight(1f)) { selHour = it }
                        WheelPicker(minutes, selMinute, Modifier.weight(1f)) { selMinute = it }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "提醒时间受系统后台与省电策略影响，不保证所有系统状态下都绝对准时。ColorOS 下建议在「设置 → 电池 → 应用耗电管理」中允许本应用后台运行。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton({
                    val hh = (if (selHour < 10) "0" else "") + selHour + ":" + (if (selMinute < 10) "0" else "") + selMinute
                    store.setBool(SettingsKeys.REMINDER_ENABLED, enabled)
                    store.setStr(SettingsKeys.REMINDER_TIME, hh)
                    com.xzledger.app.notify.Reminders.reschedule(ctx)
                    showReminder = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton({ showReminder = false }) { Text("取消") } }
        )
    }
}

/** 分组卡片：组内每行左侧一个极简线条图标 + 标题 + 副标题 + 右箭头。 */
@Composable
private fun MineGroup(title: String, content: @Composable () -> Unit) {
    SectionTitle(title)
    Card2(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(vertical = 4.dp)) { content() }
    }
}

@Composable
private fun IconMenuRow(
    icon: ImageVector,
    color: Color,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(34.dp).background(color.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center
        ) { Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Default.ChevronRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun MineStat(label: String, value: String, modifier: Modifier = Modifier, accent: Color) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = accent)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

