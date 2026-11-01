package com.xzledger.app.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xzledger.app.XzApp
import com.xzledger.app.AppState
import com.xzledger.app.data.AppLinks
import com.xzledger.app.data.SettingsKeys
import com.xzledger.app.data.Sync
import com.xzledger.app.data.activeAiConfig
import com.xzledger.app.notify.Reminders
import com.xzledger.app.ui.theme.Appearance
import com.xzledger.app.ui.theme.seedLabel
import com.xzledger.app.ui.theme.seedKeys
import com.xzledger.app.util.Dates
import com.xzledger.app.widget.SquareWidgetProvider
import com.xzledger.app.widget.WideWidgetProvider
import com.xzledger.app.widget.WidgetRefresh
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(state: AppState, nav: NavStack) {
    val store = XzApp.store
    val ctx = LocalContext.current
    var showSeed by remember { mutableStateOf(false) }
    var showReminder by remember { mutableStateOf(false) }
    var showPin by remember { mutableStateOf(false) }
    var pinText by remember { mutableStateOf("") }
    var a by remember { mutableStateOf(state.appearance) }
    var showWidgetAlpha by remember { mutableStateOf(false) }
    var wAlphaPct by remember { mutableStateOf(store.int(SettingsKeys.WIDGET_BG_ALPHA, 95).coerceIn(0, 100)) }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "个性化设置", onBack = { nav.pop() })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
            item { SectionTitle("外观") }
            item {
                MenuRow("主题模式", when (a.mode) {
                    "light" -> "浅色"; "dark" -> "深色"; else -> "跟随系统"
                }) {
                    val next = when (a.mode) { "system" -> "light"; "light" -> "dark"; else -> "system" }
                    a = a.copy(mode = next); state.updateAppearance(a)
                }
            }
            item { MenuRow("主题颜色", seedLabel(a.seed)) { showSeed = true } }
            item {
                MenuRow("字体大小", when {
                    a.fontScale < 0.95f -> "小"; a.fontScale > 1.2f -> "大"; a.fontScale > 1.0f -> "较大"; else -> "标准"
                }) {
                    val next = when {
                        a.fontScale < 0.95f -> 1.0f
                        a.fontScale < 1.05f -> 1.15f
                        a.fontScale < 1.25f -> 1.3f
                        else -> 0.9f
                    }
                    a = a.copy(fontScale = next); state.updateAppearance(a)
                }
            }
            item {
                MenuRow("语言", if (a.language == "en") "English" else "简体中文") {
                    a = a.copy(language = if (a.language == "en") "zh" else "en"); state.updateAppearance(a)
                }
            }
            item { SwitchRow("动画效果", "关闭后减少过渡动画", a.animations) { a = a.copy(animations = it); state.updateAppearance(a) } }
            item { SwitchRow("平滑出现动画", "关闭后仅使用淡入", a.smoothAnimations) { a = a.copy(smoothAnimations = it); state.updateAppearance(a) } }
            item { SwitchRow("液态玻璃效果（ColorOS 17）", "开关、底部胶囊、页面卡片与桌面小组件一起切换；关闭后回到纯色界面", a.glass) { a = a.copy(glass = it); state.updateAppearance(a) } }
            item { SwitchRow("Apple 液态玻璃（WWDC 2025）", "更薄更透的玻璃、更强的边缘高光、更柔的大阴影与更大圆角；关闭则保持 ColorOS 风格", a.liquidApple) { a = a.copy(liquidApple = it); state.updateAppearance(a) } }
            item { SwitchRow("二次元元素（桑多涅）", "首页看板娘台词、星芒点缀与默认桑多涅头像；关闭后回到纯记账界面", a.anime) { a = a.copy(anime = it); state.updateAppearance(a) } }

            item { SectionTitle("桌面小组件") }
            item { MenuRow("添加 今日概览（4x2）", "一键添加；若桌面无反应，请长按桌面空白处手动添加") { pinWidget(ctx, WideWidgetProvider::class.java) } }
            item { MenuRow("添加 预算（2x2）", "一键添加；若桌面无反应，请长按桌面空白处手动添加") { pinWidget(ctx, SquareWidgetProvider::class.java) } }
            item { MenuRow("背景透明度", wAlphaPct.toString() + "% 不透明") { showWidgetAlpha = true } }

            item { SectionTitle("安全") }
            item { SwitchRow("应用锁", "使用应用密码锁定打开应用", store.bool(SettingsKeys.APP_LOCK, false)) { v ->
                if (v && store.str(SettingsKeys.APP_LOCK_SECRET, "").length < 4) {
                    showPin = true
                } else {
                    store.setBool(SettingsKeys.APP_LOCK, v)
                }
            } }
            item { MenuRow("修改应用密码", "至少 4 位数字") { pinText = ""; showPin = true } }

            item { SectionTitle("提醒") }
            item { SwitchRow("记账提醒", "每天固定时间提醒记账", store.bool(SettingsKeys.REMINDER_ENABLED, false)) { v ->
                store.setBool(SettingsKeys.REMINDER_ENABLED, v)
                Reminders.reschedule(ctx)
            } }
            item { MenuRow("提醒时间", store.str(SettingsKeys.REMINDER_TIME, "21:00")) { showReminder = true } }
            item { SwitchRow("预算提醒", "接近或超出预算时在应用内提示", store.bool(SettingsKeys.BUDGET_ALERT, true)) { v ->
                store.setBool(SettingsKeys.BUDGET_ALERT, v)
            } }

            item { SectionTitle("数据与云") }
            item { MenuRow("云同步", "配置后端地址与令牌，查看待同步队列") { nav.push(Screen.SYNC) } }
            item { MenuRow("备份与恢复", "本地导出与恢复") { nav.push(Screen.BACKUP) } }
            item { MenuRow("ColorOS 权限与提醒设置帮助") { nav.push(Screen.HELP) } }
            item { SectionTitle("其他") }
            item { MenuRow("关于") { nav.push(Screen.ABOUT) } }
        }
    }

    if (showSeed) {
        ChoiceSheet(title = "主题颜色", options = seedKeys.map { seedLabel(it) to it },
            onPick = { a = a.copy(seed = it); state.updateAppearance(a); showSeed = false },
            onDismiss = { showSeed = false })
    }
    if (showReminder) {
        val hours = (0..23).map { h -> (if (h < 10) "0" + h else "" + h) + ":00" }
        ChoiceSheet(title = "提醒时间", options = hours.map { it to it },
            onPick = { v ->
                store.setStr(SettingsKeys.REMINDER_TIME, v)
                Reminders.reschedule(ctx)
                showReminder = false
            }, onDismiss = { showReminder = false })
    }
    if (showPin) {
        AlertDialog(onDismissRequest = { showPin = false },
            title = { Text("设置应用密码") },
            text = {
                Column {
                    Text("至少 4 位数字。密码以明文保存在本机应用私有数据库中，不会上传或写入日志。",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = pinText, onValueChange = { pinText = it.filter { c -> c.isDigit() }.take(12) },
                        label = { Text("应用密码") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton({
                    if (pinText.length >= 4) {
                        store.setStr(SettingsKeys.APP_LOCK_SECRET, pinText)
                        store.setBool(SettingsKeys.APP_LOCK, true)
                        showPin = false
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton({ showPin = false }) { Text("取消") } })
    }
    if (showWidgetAlpha) {
        // 草稿值：确认后才落库并刷新小组件，取消不影响已保存的透明度。
        var draft by remember { mutableStateOf(wAlphaPct.toFloat()) }
        AlertDialog(onDismissRequest = { showWidgetAlpha = false },
            title = { Text("桌面小组件背景透明度") },
            text = {
                Column {
                    Text("当前不透明度 " + draft.roundToInt() + "%。数值越小背景越透明，0% 时只保留金色描边。保存后立即刷新桌面小组件。",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    Slider(value = draft, onValueChange = { draft = it }, valueRange = 0f..100f, steps = 19)
                }
            },
            confirmButton = {
                TextButton({
                    val v = draft.roundToInt().coerceIn(0, 100)
                    wAlphaPct = v
                    store.setInt(SettingsKeys.WIDGET_BG_ALPHA, v)
                    WidgetRefresh.updateAll(ctx)
                    showWidgetAlpha = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton({ showWidgetAlpha = false }) { Text("取消") } })
    }
}

/** 一键把小组件钉到桌面；系统不支持时给出长按桌面手动添加的提示。 */
private fun pinWidget(ctx: Context, provider: Class<*>) {
    try {
        val manager = AppWidgetManager.getInstance(ctx)
        if (manager == null || !manager.isRequestPinAppWidgetSupported) {
            Toast.makeText(ctx, "当前桌面不支持一键添加，请在桌面长按空白处 → 添加小组件 → 猫猫记账", Toast.LENGTH_LONG).show()
            return
        }
        manager.requestPinAppWidget(ComponentName(ctx, provider), null, null)
    } catch (t: Throwable) {
        Toast.makeText(ctx, "添加失败：" + (t.message ?: "未知原因"), Toast.LENGTH_LONG).show()
    }
}

@Composable
fun HelpScreen(state: AppState, nav: NavStack) {
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "ColorOS 权限与提醒帮助", onBack = { nav.pop() })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            item {
                GlassPanel(contentPadding = PaddingValues(16.dp)) {
                    Text("为什么需要这些权限", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "· 通知：周期任务与记账提醒需要通知权限（Android 13 及以上在首次设置提醒时申请）。\n" +
                            "· 日历读取（可选）：仅用于可选的系统日程关联；拒绝后内部收支日历仍然完整可用。\n" +
                            "· 相机／麦克风（可选）：拍照或语音记账时使用，图片与语音仅在你确认后才用于识别。\n" +
                            "· 精确闹钟：用于在指定时间提醒；系统不允许时自动降级为不精确提醒。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            item {
                GlassPanel(contentPadding = PaddingValues(16.dp)) {
                    Text("ColorOS 设置建议", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "1. 设置 → 应用 → 应用管理 → 猫猫记账 → 耗电管理：允许后台运行（无需关闭全部省电限制）。\n" +
                            "2. 设置 → 通知与状态栏 → 应用通知 → 猫猫记账：允许通知，建议允许横幅。\n" +
                            "3. 设置 → 应用 → 自启动：允许猫猫记账自启动，以便重启后恢复周期提醒。\n" +
                            "4. 若开启应用锁，可在 设置 → 隐私 → 应用锁 中把本应用加入系统应用锁。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            item {
                GlassPanel(contentPadding = PaddingValues(16.dp)) {
                    Text("能力边界（如实说明）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "系统省电策略、后台限制或极端内存压力下，提醒可能延迟或不触发；本应用使用 WorkManager 与 AlarmManager 双通道并可在开机后重建，但不能保证所有系统状态下都准时。\n" +
                            "AI 功能需要网络和你自行配置的接口；接口不可用时应用会明确提示失败，不会假装保存成功。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

@Composable
fun AboutScreen(state: AppState, nav: NavStack) {
    val store = XzApp.store
    val ctx = LocalContext.current
    val github = store.str(SettingsKeys.GITHUB_URL, AppLinks.GITHUB)
    val download = store.str(SettingsKeys.DOWNLOAD_URL, AppLinks.RELEASE)
    var editGithub by remember { mutableStateOf(false) }
    var editDownload by remember { mutableStateOf(false) }
    var buffer by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "关于", onBack = { nav.pop() })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            item {
                GlassPanel(contentPadding = PaddingValues(16.dp)) {
                    Text("猫猫记账", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text("版本 " + APP_VERSION + "（versionCode 6）", style = MaterialTheme.typography.bodySmall)
                    Text("面向大学生日常收支的本地优先记账应用，优先适配 ColorOS。", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    Text("开发者", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("一只小庄呀（B站）· 兰大数学院 zza", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text("联系方式", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("邮箱：zhuangzhongao@gmail.com", style = MaterialTheme.typography.bodySmall)
                    Text("微信：Zza17751159236（仅兰大校内可加）", style = MaterialTheme.typography.bodySmall)
                    Text("原神亚服 UID：857353806", style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                MenuRow("给项目 Star", github) { openUrl(ctx, github) }
            }
            item { MenuRow("配置 GitHub 地址", "留空则回到默认仓库 " + AppLinks.GITHUB) { buffer = github; editGithub = true } }
            item { MenuRow("分享应用", download) { shareApp(ctx, download) } }
            item { MenuRow("配置下载地址", "默认指向 GitHub 最新 Release；留空即用默认") { buffer = download; editDownload = true } }
            item { MenuRow("反馈", store.str(SettingsKeys.FEEDBACK, "尚未配置反馈方式")) { } }
            item { MenuRow("隐私说明", "所有账单数据默认仅保存在本机；AI 与云同步均为可选且需你主动配置") { } }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
    if (editGithub) {
        AlertDialog(onDismissRequest = { editGithub = false }, title = { Text("GitHub 项目地址") },
            text = { OutlinedTextField(value = buffer, onValueChange = { buffer = it }, label = { Text("https://...") }, singleLine = true) },
            confirmButton = { TextButton({ store.setStr(SettingsKeys.GITHUB_URL, buffer.trim()); editGithub = false }) { Text("保存") } },
            dismissButton = { TextButton({ editGithub = false }) { Text("取消") } })
    }
    if (editDownload) {
        AlertDialog(onDismissRequest = { editDownload = false }, title = { Text("下载地址与介绍") },
            text = { OutlinedTextField(value = buffer, onValueChange = { buffer = it }, label = { Text("https://...") }, singleLine = true) },
            confirmButton = { TextButton({ store.setStr(SettingsKeys.DOWNLOAD_URL, buffer.trim()); editDownload = false }) { Text("保存") } },
            dismissButton = { TextButton({ editDownload = false }) { Text("取消") } })
    }
}

fun openUrl(ctx: android.content.Context, url: String) {
    try {
        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        android.widget.Toast.makeText(ctx, "无法打开链接：" + e.message, android.widget.Toast.LENGTH_SHORT).show()
    }
}

fun shareApp(ctx: android.content.Context, download: String) {
    val text = if (download.isEmpty()) "猫猫记账 —— 面向大学生的本地记账应用（尚未配置下载地址）"
    else "猫猫记账 —— 下载地址：" + download
    try {
        val i = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_TEXT, text)
        ctx.startActivity(android.content.Intent.createChooser(i, "分享应用"))
    } catch (e: Exception) {
        android.widget.Toast.makeText(ctx, "无法分享：" + e.message, android.widget.Toast.LENGTH_SHORT).show()
    }
}

const val APP_VERSION = "1.0.5 rc1"

private fun entityLabel(entity: String): String = when (entity) {
    "txn" -> "账单"; "category" -> "分类"; "account" -> "账户"; "ledger" -> "账本"
    "budget" -> "预算"; "recurring" -> "周期任务"; "rule" -> "分类规则"; "import_batch" -> "导入批次"
    else -> entity
}

private fun opLabel(op: String): String = when (op) {
    "upsert" -> "新增或修改"; "delete" -> "删除"; "create" -> "新建"; else -> op
}

@Composable
fun SyncScreen(state: AppState, nav: NavStack) {
    val store = XzApp.store
    val db = XzApp.db
    var version by remember { mutableStateOf(0) }
    var endpoint by remember { mutableStateOf(store.str(SettingsKeys.CLOUD_ENDPOINT, "")) }
    var token by remember { mutableStateOf(store.str(SettingsKeys.CLOUD_TOKEN, "")) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val enabled = remember(version) { store.bool(SettingsKeys.CLOUD_ENABLED, false) }
    val pending = remember(version, state.dataVersion) { db.outboxCount() }
    val items = remember(version, state.dataVersion) { db.outboxItems(50) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "云同步", onBack = { nav.pop() })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            item {
                GlassPanel(contentPadding = PaddingValues(16.dp)) {
                    Text(if (enabled) "云同步：已开启" else "云同步：未开启（本地记账、统计与导出不受影响）",
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "本应用不绑定任何付费云服务，也不内置服务器。你需要自行部署兼容接口后填入地址与令牌。" +
                            "服务端需校验账号与账本权限并做唯一约束（防重复）。免费额度、部署条件与后续成本取决于你选择的服务商，" +
                            "本应用不做承诺。云同步不能替代本地备份。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    SwitchRow("启用云同步", null, enabled) { v ->
                        store.setBool(SettingsKeys.CLOUD_ENABLED, v); version++
                    }
                    OutlinedTextField(value = endpoint, onValueChange = { endpoint = it },
                        label = { Text("接口地址，例如 https://host/api/xz/sync") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(value = token, onValueChange = { token = it },
                        label = { Text("访问令牌（仅本机保存，不随账单同步）") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChipButton("保存配置", false) {
                            store.setStr(SettingsKeys.CLOUD_ENDPOINT, endpoint.trim())
                            store.setStr(SettingsKeys.CLOUD_TOKEN, token.trim())
                            result = "配置已保存"
                        }
                        ChipButton(if (busy) "同步中…" else "立即同步", false) {
                            if (endpoint.isBlank()) { result = "请先填写接口地址" } else {
                                store.setStr(SettingsKeys.CLOUD_ENDPOINT, endpoint.trim())
                                store.setStr(SettingsKeys.CLOUD_TOKEN, token.trim())
                                busy = true; result = null
                                scope.launch {
                                    result = Sync.push(db, store)
                                    busy = false; version++
                                }
                            }
                        }
                    }
                    result?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item { SectionTitle("待同步队列（" + pending + " 项）") }
            if (!enabled) item {
                Text("云同步未开启，这些记录只保留在本机；开启并配置接口后会按顺序提交。每笔记录只保留最后一次修改。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
            }
            if (items.isEmpty()) item { EmptyHint("没有待同步的写入操作") }
            items(items.size) { idx ->
                val o = items[idx]
                Card2 {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(entityLabel(o.entity) + " · " + opLabel(o.op), style = MaterialTheme.typography.bodyMedium)
                        Text("编号 " + o.entityId.take(8) + " · " + Dates.formatDateTime(o.createdAt),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}
