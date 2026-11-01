package com.xzledger.app.ui

import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xzledger.app.AppState
import com.xzledger.app.XzApp
import com.xzledger.app.data.AiClient
import com.xzledger.app.data.AiConfig
import com.xzledger.app.data.AiDraft
import com.xzledger.app.data.AiEngine
import com.xzledger.app.data.AiPerm
import com.xzledger.app.data.chatMessages
import com.xzledger.app.data.deleteChatMessage
import com.xzledger.app.data.softDeleteTxn
import com.xzledger.app.data.AiResult
import com.xzledger.app.data.ChatMessage
import com.xzledger.app.data.ClassifyBasis
import com.xzledger.app.data.Heuristic
import com.xzledger.app.data.Ids
import com.xzledger.app.data.Nature
import com.xzledger.app.data.TxnType
import com.xzledger.app.data.activeAiConfig
import com.xzledger.app.data.addChatMessage
import com.xzledger.app.data.aiConfigs
import com.xzledger.app.data.clearChat
import com.xzledger.app.data.deleteAiConfig
import com.xzledger.app.data.deleteChatMessage
import com.xzledger.app.data.upsertAiConfig
import com.xzledger.app.util.Attachments
import com.xzledger.app.util.Dates
import com.xzledger.app.util.Money
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- AI 配置

@Composable
fun AiConfigScreen(state: AppState, nav: NavStack) {
    val db = XzApp.db
    val scope = rememberCoroutineScope()
    var version by remember { mutableStateOf(0) }
    val configs = remember(version, state.dataVersion) { db.aiConfigs() }
    var editing by remember { mutableStateOf<AiConfig?>(null) }
    var creating by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopBar("AI 服务配置", onBack = { nav.pop() })
        VerticalScrollColumn {
            Card2(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    SectionTitle("接口地址填写说明")
                    Text(
                        "填写到 /v1 这一级即可，例如 https://api.deepseek.com/v1 ，程序会自动补上 /chat/completions。若服务商给出的地址已经包含 /chat/completions，就直接填写完整地址。程序不会重复拼接路径。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "API Key 只保存在本机数据库，不写入日志，不出现在普通导出与云同步负载中。密钥输入框不会回显，请自行妥善保管。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            SectionTitle("已配置的服务")
            if (configs.isEmpty()) EmptyHint("还没有配置 AI 服务。配置后即可使用 AI 聊天记账；未配置时也可使用本地规则识别。")
            configs.forEach { cfg ->
                Card2(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(cfg.name, style = MaterialTheme.typography.titleSmall)
                            if (cfg.isActive) {
                                Spacer(Modifier.width(8.dp))
                                Text("当前使用", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(Modifier.weight(1f))
                            Text(cfg.model, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(cfg.baseUrl, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "能力声明：" + (if (cfg.supportsVision) "支持图片输入" else "不支持图片输入") + "；" + (if (cfg.supportsFile) "支持文件输入" else "不支持文件输入"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row {
                            TextButton(onClick = {
                                db.upsertAiConfig(cfg.copy(isActive = true))
                                state.touch(); version++
                            }) { Text("设为当前") }
                            TextButton(onClick = {
                                testing = true; testResult = null
                                scope.launch {
                                    val r = AiClient.test(cfg)
                                    testResult = cfg.name + "：" + r
                                    testing = false
                                }
                            }) { Text("连接测试") }
                            TextButton(onClick = { editing = cfg }) { Text("编辑") }
                            TextButton(onClick = {
                                db.deleteAiConfig(cfg.id)
                                version++; state.touch()
                            }) { Text("删除") }
                        }
                    }
                }
            }
            if (testing) Text("正在测试连接…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            testResult?.let {
                Card2(Modifier.fillMaxWidth()) {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) { Text("新增 AI 服务") }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (creating || editing != null) {
        AiConfigDialog(
            initial = editing,
            onDismiss = { creating = false; editing = null },
            onSave = { cfg ->
                db.upsertAiConfig(cfg)
                if (cfg.isActive) configs.forEach { if (it.id != cfg.id) db.upsertAiConfig(it.copy(isActive = false)) }
                creating = false; editing = null; version++; state.touch()
            }
        )
    }
}

private data class AiPreset(val name: String, val baseUrl: String, val model: String)

private val AI_PRESETS = listOf(
    AiPreset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
    AiPreset("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
    AiPreset("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
    AiPreset("Kimi", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
    AiPreset("硅基流动", "https://api.siliconflow.cn/v1", "Qwen/Qwen2.5-7B-Instruct"),
    AiPreset("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
    AiPreset("OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-4o-mini"),
    AiPreset("本地 Ollama", "http://127.0.0.1:11434/v1", "qwen2.5")
)

@Composable
private fun AiConfigDialog(initial: AiConfig?, onDismiss: () -> Unit, onSave: (AiConfig) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl ?: "") }
    var apiKey by remember { mutableStateOf(initial?.apiKey ?: "") }
    var model by remember { mutableStateOf(initial?.model ?: "") }
    var vision by remember { mutableStateOf(initial?.supportsVision ?: false) }
    var file by remember { mutableStateOf(initial?.supportsFile ?: false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增 AI 服务" else "编辑 AI 服务") },
        text = {
            Column {
                if (initial == null) {
                    Text("快速预设：点选服务商后只需填 API Key", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AI_PRESETS.forEach { p ->
                            ChipButton(p.name, name == p.name) {
                                name = p.name; baseUrl = p.baseUrl; model = p.model
                            }
                        }
                    }
                } else {
                    Text("接口地址与模型可留空后自行填写。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("服务名称") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("接口地址（填到 /v1 这一级）") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("API Key") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("模型名称") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                SwitchRow("模型支持图片输入", "关闭时不会把图片发送给该接口", vision) { vision = it }
                SwitchRow("模型支持文件输入", "关闭时会改用本地解析流程", file) { file = it }
                Spacer(Modifier.height(4.dp))
                Text(
                    "接口类型：OpenAI 兼容（本版保留适配层，其他协议后续可扩展）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank() || baseUrl.isBlank() || model.isBlank()) {
                    error = "服务名称、接口地址与模型名称都不能为空。"
                } else {
                    val cfg = (initial ?: AiConfig(id = Ids.newId(), name = name, baseUrl = baseUrl, apiKey = apiKey, model = model)).copy(
                        name = name.trim(),
                        baseUrl = baseUrl.trim(),
                        apiKey = apiKey.trim(),
                        model = model.trim(),
                        supportsVision = vision,
                        supportsFile = file,
                        isActive = initial?.isActive ?: true
                    )
                    onSave(cfg)
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ---------------------------------------------------------------- AI 权限

@Composable
fun AiPermScreen(state: AppState, nav: NavStack) {
    val db = XzApp.db
    var version by remember { mutableStateOf(0) }
    val perms = remember(version) { db.allAiPermissions() }
    Column(Modifier.fillMaxSize()) {
        TopBar("AI 权限管理", onBack = { nav.pop() })
        VerticalScrollColumn {
            Card2(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    SectionTitle("读取与写入彼此独立")
                    Text(
                        "读取账本与新增账单是两个独立开关，默认全部关闭。关闭后 AI 无法查询数据、也无法生成账单；工具执行失败时界面会明确显示失败，不会显示成功。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "数据范围：默认只针对当前账本「" + state.currentLedger().name + "」。提问时只发送最近 20 条聊天内容与本条输入；需要金额统计时，由本机程序计算后再把结果交给 AI 解释，不会整本账发送。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            for ((key, label, desc) in AiPerm.items) {
                SwitchRow(label, desc, perms[key] == true) { v ->
                    db.setAiPermission(key, v)
                    version++; state.touch()
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------- AI 聊天

@Composable
fun AiChatScreen(state: AppState, nav: NavStack) {
    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar(
            "AI 聊天记账",
            onBack = { nav.pop() },
            actions = {
                IconButton(onClick = { nav.push(Screen.AI_CONFIG) }) { Icon(Icons.Default.AutoAwesome, contentDescription = "AI 配置") }
            }
        )
        ChatPane(state, nav, expanded = true, modifier = Modifier.weight(1f))
    }
}

/** 记账页与全屏聊天共用的聊天面板。 */
@Composable
fun ChatPane(state: AppState, nav: NavStack, expanded: Boolean, slim: Boolean = false, modifier: Modifier = Modifier) {
    val db = XzApp.db
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ledgerId = state.ledgerId
    var version by remember { mutableStateOf(0) }
    var sending by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var image by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
    var showClear by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    val messages = remember(version, state.dataVersion, ledgerId) { db.chatMessages(ledgerId) }
    val shown = if (expanded) messages else messages.takeLast(4)
    val listState = rememberLazyListState()
    val cfg = remember(version, state.dataVersion) { db.activeAiConfig() }

    LaunchedEffect(shown.size) {
        if (shown.isNotEmpty()) listState.animateScrollToItem(shown.size - 1)
    }

    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val list = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        val t = list?.firstOrNull()
        if (!t.isNullOrBlank()) input = if (input.isBlank()) t else input + " " + t
        else hint = "没有识别到语音内容（可能未安装系统语音识别服务），可以改用文字输入。"
    }
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val path = Attachments.importFromUri(ctx, uri)
            if (path == null) hint = "图片导入失败，请重试或换一张图片。"
            else {
                image = path
                if (cfg?.supportsVision != true) hint = "当前 AI 配置未开启「支持图片输入」，图片不会被发送。可在 AI 配置中开启，或直接手动记账。"
            }
        }
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val ok = startChatImport(ctx, state, nav, uri)
            if (!ok) hint = "无法读取该文件。请确认文件未加密、格式受支持（Excel .xlsx/.xls、PDF、CSV），或换一个文件重试。"
        }
    }

    val send: () -> Unit = {
        val text = input.trim()
        val img = image
        if (!sending && (text.isNotEmpty() || img != null)) {
            sending = true
            input = ""
            image = null
            hint = null
            val userMsg = ChatMessage(
                id = Ids.newId(), ledgerId = ledgerId, role = "user",
                content = if (text.isEmpty()) "[图片]" else text,
                attachments = listOfNotNull(img), status = "ok"
            )
            db.addChatMessage(userMsg)
            version++
            scope.launch {
                val active = db.activeAiConfig()
                val ledger = state.currentLedger()
                val perms = db.allAiPermissions()
                val autoPost = perms[AiPerm.AUTO_POST] == true
                val result: AiResult = try {
                    if (active != null) {
                        AiEngine.send(
                            db = db,
                            ledger = ledger,
                            cfg = active,
                            history = db.chatMessages(ledgerId).filter { it.id != userMsg.id },
                            userText = text,
                            attachments = listOfNotNull(img),
                            images = listOfNotNull(img),
                            allowRead = perms[AiPerm.READ] == true,
                            allowWrite = perms[AiPerm.WRITE] == true,
                            allowDelete = perms[AiPerm.DELETE] == true,
                            autoPost = autoPost
                        )
                    } else {
                        val p = Heuristic.parse(db, ledgerId, text, autoPost)
                        AiResult(text = p.reply, drafts = p.drafts, error = null, toolNotes = p.notes + "本地规则识别（未配置 AI 服务）")
                    }
                } catch (e: Exception) {
                    AiResult(text = "", error = "处理失败：" + (e.message ?: e.javaClass.simpleName))
                }
                for (d in result.drafts) AiEngine.saveDraftRecord(db, ledgerId, d)
                val reply = ChatMessage(
                    id = Ids.newId(), ledgerId = ledgerId, role = "assistant",
                    content = result.text.ifBlank { if (result.error != null) "处理失败，请重试。" else "处理完成。" },
                    status = if (result.error != null) "error" else "ok",
                    error = result.error
                )
                db.addChatMessage(reply)
                if (result.error != null) hint = result.error
                state.touch()
                version++
                sending = false
            }
        }
    }

    Column(modifier.fillMaxWidth()) {
        if (!slim) Card2(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("AI 聊天记账", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (cfg == null) "未连接 AI" else "模型：" + cfg.model,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    // 顶部只留一个「更多」入口：清空聊天、AI 权限收进菜单，界面更干净。
                    if (expanded) {
                        Box {
                            IconButton(onClick = { showMore = true }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Default.MoreVert, contentDescription = "更多", modifier = Modifier.size(18.dp))
                            }
                            DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                                DropdownMenuItem(
                                    text = { Text("AI 权限管理") },
                                    onClick = { showMore = false; nav.push(Screen.AI_PERM) }
                                )
                                DropdownMenuItem(
                                    text = { Text("清空聊天记录") },
                                    onClick = { showMore = false; showClear = true }
                                )
                            }
                        }
                    }
                }
                Text(
                    "账本：" + state.currentLedger().name + "　状态：" + (if (cfg == null) "未配置（使用本地规则识别）" else "已配置"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (cfg == null) {
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = { nav.push(Screen.AI_CONFIG) }, modifier = Modifier.fillMaxWidth()) { Text("连接 AI") }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (!slim) {
            if (expanded) {
                // 输入行始终贴着底部：消息区占满剩余空间，没有消息时也不会把输入框顶到中间。
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    ChatBody(shown, listState, state, nav, expanded = true)
                }
            } else {
                ChatBody(shown, listState, state, nav, expanded = false)
            }
        }
        if (sending) {
            Text("AI 正在识别与处理…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
        }
        hint?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 4.dp))
        }
        image?.let { path ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("已选图片：" + path.substringAfterLast('/'), style = MaterialTheme.typography.labelSmall)
                IconButton(onClick = { image = null }) { Icon(Icons.Default.Close, contentDescription = "移除图片", modifier = Modifier.size(16.dp)) }
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            if (slim) IconButton(onClick = { nav.push(Screen.AI_CHAT) }) { Icon(Icons.Default.AutoAwesome, contentDescription = "展开完整聊天") }
            IconButton(onClick = {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "说出要记的账")
                }
                try {
                    voiceLauncher.launch(intent)
                } catch (e: Exception) {
                    hint = "当前设备没有可用的语音识别服务，可以改用文字输入。"
                }
            }) { Icon(Icons.Default.Mic, contentDescription = "语音") }
            if (!slim) IconButton(onClick = { imageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Icon(Icons.Default.Image, contentDescription = "图片")
            }
            if (!slim) IconButton(onClick = {
                fileLauncher.launch(arrayOf(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.ms-excel",
                    "application/pdf",
                    "text/csv",
                    "text/comma-separated-values",
                    "text/plain",
                    "*/*"
                ))
            }) { Icon(Icons.Default.AttachFile, contentDescription = "文件") }
            ChatInputField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                maxLines = 3
            )
            IconButton(onClick = send, enabled = !sending) { Icon(Icons.Default.Send, contentDescription = "发送") }
        }
        if (!expanded && !slim) {
            TextButton(onClick = { nav.push(Screen.AI_CHAT) }) { Text("展开为大聊天视图") }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (showClear) {
        ConfirmDialog(
            title = "清空聊天记录",
            text = "只会删除本账本的聊天记录，已入账的账单不会被删除。",
            confirmText = "清空",
            onConfirm = {
                db.clearChat(ledgerId)
                showClear = false
                version++
            },
            onDismiss = { showClear = false }
        )
    }
}

@Composable
private fun ChatBody(shown: List<ChatMessage>, listState: LazyListState, state: AppState, nav: NavStack, expanded: Boolean) {
    if (shown.isEmpty()) {
        EmptyHint(
            if (expanded) "直接说话就能记账，例如「今天午餐18元，微信付的。」「早餐8元，公交2元，给 AI 会员充值了30元。」"
            else "在下面输入一句话即可记账，也可以点右上角展开完整聊天。"
        )
    } else {
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            items(shown, key = { it.id }) { m ->
                MessageRow(m, state, nav, expanded)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/**
 * 聊天输入框：初始只有一行高（胶囊形），文字换行后随行数长高，并平滑过渡为圆角矩形。
 * 记账页与 AI 聊天页一致：超过两行后继续长高，最高 3 行，再多在框内滚动（记账页整体可滚动，不会被顶出屏幕）。
 */
@Composable
private fun ChatInputField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    maxLines: Int = 4,
    placeholder: String = "例如：午餐18元，微信付的"
) {
    val textStyle = MaterialTheme.typography.bodyMedium
    val density = LocalDensity.current
    val linePx = with(density) { textStyle.lineHeight.toPx() }
    var textH by remember { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    val multi = textH > linePx * 1.5f
    val cornerPct by animateFloatAsState(if (multi) 30f else 50f, label = "inputCorner")
    val shape = RoundedCornerShape(percent = cornerPct.toInt())
    val bg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (focused) 0.85f else 0.5f)
    val line = MaterialTheme.colorScheme.primary.copy(alpha = if (focused) 0.85f else 0.3f)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .background(bg, shape)
            .border(1.dp, line, shape)
            .onFocusChanged { focused = it.isFocused }
            .padding(horizontal = 14.dp, vertical = 9.dp)
            .onSizeChanged { textH = it.height },
        textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        maxLines = maxLines,
        decorationBox = { inner ->
            if (value.isEmpty()) {
                Text(
                    placeholder,
                    style = textStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            inner()
        }
    )
}

@Composable
private fun MessageRow(m: ChatMessage, state: AppState, nav: NavStack, expanded: Boolean) {
    if (m.role == "tool") {
        DraftCard(m, state, nav)
        return
    }
    val isUser = m.role == "user"
    // 气泡不再限制行数：AI 的回复一次显示完整内容（和自己的输入框一样随文字自然长高，不再出现内部滚动）。
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.widthIn(max = if (expanded) 300.dp else 260.dp)
        ) {
            Column(
                Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(m.content, style = MaterialTheme.typography.bodyMedium)
                if (m.status == "error") {
                    Text(m.error ?: "处理失败", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun DraftCard(m: ChatMessage, state: AppState, nav: NavStack) {
    val db = XzApp.db
    val scope = rememberCoroutineScope()
    var version by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    val draft = remember(m.cardsJson, version, state.dataVersion) { AiEngine.draftFromJson(m.cardsJson) }
    if (draft == null) {
        Text(m.content, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val isSaved = draft.state == "saved"
    val isBlocked = draft.state == "fail" || draft.state == "duplicate"
    Card2(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (isSaved) "已入账" else if (draft.state == "duplicate") "重复，已跳过" else if (draft.state == "fail") "保存失败" else "待确认账单",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isSaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                Text(AiEngine.draftSummary(draft), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "分类依据：" + classifyBasisLabel(draft.basis) + (if (draft.basisReason.isNotEmpty()) "（" + draft.basisReason + "）" else ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (draft.message.isNotEmpty()) {
                Text(draft.message, style = MaterialTheme.typography.labelSmall, color = if (isBlocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!isSaved && !isBlocked) {
                Row {
                    TextButton(onClick = {
                        val outcome = AiEngine.saveDraft(db, state.ledgerId, draft)
                        if (!outcome.ok) AiEngine.saveDraftRecord(db, state.ledgerId, draft.copy(state = if (outcome.duplicateOf != null) "duplicate" else "fail", message = outcome.message))
                        state.touch(); version++
                    }) { Text("确认入账") }
                    TextButton(onClick = { editing = true }) { Text("修改") }
                    TextButton(onClick = {
                        db.deleteChatMessage(m.id)
                        state.touch(); version++
                    }) { Text("忽略") }
                }
            } else if (isSaved) {
                Row {
                    TextButton(onClick = { draft.savedTxnId?.let { nav.push(Screen.TXN_DETAIL, it) } }) { Text("查看账单") }
                    TextButton(onClick = {
                        draft.savedTxnId?.let { db.softDeleteTxn(it) }
                        AiEngine.saveDraftRecord(db, state.ledgerId, draft.copy(state = "fail", message = "已撤销入账，账单已移入回收站"))
                        state.touch(); version++
                    }) { Text("撤销") }
                }
            }
        }
    }
    if (editing) {
        DraftEditDialog(draft, state, onDismiss = { editing = false }, onApply = { fixed ->
            AiEngine.saveDraftRecord(db, state.ledgerId, fixed.copy(state = "confirm", message = "已按你的修改更新，待确认"))
            editing = false
            state.touch(); version++
        })
    }
}

private fun classifyBasisLabel(b: ClassifyBasis): String = when (b) {
    ClassifyBasis.MANUAL -> "手动选择"
    ClassifyBasis.RULE -> "依据你的商户规则"
    ClassifyBasis.HISTORY -> "依据你的历史习惯"
    ClassifyBasis.AI -> "AI 建议"
    ClassifyBasis.DEFAULT -> "默认分类"
}

@Composable
private fun DraftEditDialog(d: AiDraft, state: AppState, onDismiss: () -> Unit, onApply: (AiDraft) -> Unit) {
    val db = XzApp.db
    val cats = remember(state.dataVersion) { db.categories(state.ledgerId) }
    val accounts = remember(state.dataVersion) { db.accounts(state.ledgerId) }
    var amount by remember { mutableStateOf(Money.centsToEdit(d.amount)) }
    var date by remember { mutableStateOf(d.date) }
    var time by remember { mutableStateOf(d.time) }
    var merchant by remember { mutableStateOf(d.merchant) }
    var note by remember { mutableStateOf(d.note) }
    var categoryId by remember { mutableStateOf(d.categoryId) }
    var accountId by remember { mutableStateOf(d.accountId) }
    var nature by remember { mutableStateOf(d.nature) }
    var showCat by remember { mutableStateOf(false) }
    var showNature by remember { mutableStateOf(false) }
    var showAccount by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改识别结果") },
        text = {
            Column {
                OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("金额（元）") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Row {
                    OutlinedTextField(value = date, onValueChange = { date = it }, label = { Text("日期") }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    OutlinedTextField(value = time, onValueChange = { time = it }, label = { Text("时间") }, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = merchant, onValueChange = { merchant = it }, label = { Text("商户") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("备注") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                SettingRow("分类", cats.firstOrNull { it.id == categoryId }?.name ?: "未选择", onClick = { showCat = true })
                SettingRow("账户", accounts.firstOrNull { it.id == accountId }?.name ?: "未选择", onClick = { showAccount = true })
                SettingRow("消费性质", nature?.label ?: "未选择", onClick = { showNature = true })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cents = Money.parseYuanToCents(amount)
                if (cents == null || cents <= 0L) {
                    error = "金额必须大于 0，且格式正确。"
                } else if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(date)) {
                    error = "日期格式应为 yyyy-MM-dd。"
                } else if (!Regex("\\d{1,2}:\\d{2}").matches(time)) {
                    error = "时间格式应为 HH:mm。"
                } else {
                    onApply(d.copy(
                        amount = cents, date = date, time = time, merchant = merchant, note = note,
                        categoryId = categoryId, accountId = accountId,
                        nature = if (d.type == TxnType.EXPENSE) nature else null,
                        basis = ClassifyBasis.MANUAL, basisReason = "用户手动修改"
                    ))
                }
            }) { Text("保存修改") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
    if (showCat) {
        ChoiceSheet("选择分类", cats.map { it.name to it.id }, onPick = { categoryId = it }, onDismiss = { showCat = false })
    }
    if (showAccount) {
        ChoiceSheet("选择账户", accounts.map { it.name to it.id }, onPick = { accountId = it }, onDismiss = { showAccount = false })
    }
    if (showNature) {
        ChoiceSheet("选择消费性质", Nature.entries.map { it.label + "（" + it.desc + "）" to it }, onPick = { nature = it }, onDismiss = { showNature = false })
    }
}
