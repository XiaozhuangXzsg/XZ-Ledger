package com.xzledger.app.ui

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xzledger.app.AppState
import com.xzledger.app.XzApp
import com.xzledger.app.data.Backup
import com.xzledger.app.data.RestoreResult
import com.xzledger.app.data.SettingsKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_RESTORE_BYTES = 32L * 1024 * 1024

@Composable
fun BackupScreen(state: AppState, nav: NavStack) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var includeKeys by remember { mutableStateOf(state.store.bool(SettingsKeys.EXPORT_INCLUDES_KEYS, false)) }
    var busy by remember { mutableStateOf(false) }
    var restoreResult by remember { mutableStateOf<RestoreResult?>(null) }
    var restoreError by remember { mutableStateOf("") }
    var pendingRestoreText by remember { mutableStateOf("") }

    fun writeText(uri: Uri, text: String, label: String) {
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                        out.flush()
                    } ?: return@withContext "无法写入所选位置"
                    ""
                } catch (e: Exception) {
                    e.message ?: "写入失败"
                }
            }
            Toast.makeText(
                ctx,
                if (err.isEmpty()) label + "已保存" else "导出失败：" + err,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    val createJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = Backup.exportJson(XzApp.db, state.ledgerId, includeKeys)
        writeText(uri, text, "JSON 备份（" + text.length + " 字符）")
    }
    val createCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = Backup.exportCsv(XzApp.db, state.ledgerId)
        writeText(uri, text, "CSV 明细")
    }
    val openRestore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val pair = withContext(Dispatchers.IO) {
                try {
                    val size = querySize(ctx, uri)
                    if (size > MAX_RESTORE_BYTES) {
                        "" to "备份文件过大（" + (size / 1024 / 1024) + " MB），超过 32 MB 上限，请拆分后分批恢复。"
                    } else {
                        val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                        if (text.isBlank()) "" to "读取到的文件内容为空"
                        else text to ""
                    }
                } catch (e: Exception) {
                    "" to ("读取备份失败：" + (e.message ?: "未知错误"))
                }
            }
            busy = false
            if (pair.second.isNotEmpty()) {
                restoreError = pair.second
            } else {
                pendingRestoreText = pair.first
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "备份与恢复", onBack = { nav.pop() })
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Card2 {
                Column(Modifier.padding(4.dp)) {
                    Text("备份内容", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Text("账本、账户、分类、消费性质规则、个人分类规则、预算、周期任务、全部账单（含回收站）与个性化设置。", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "API Key 默认不写入导出文件；应用锁密码与云同步令牌始终不导出。" +
                            "图片附件本体不会打包进 JSON 备份，仅保留本机路径。" +
                            "云同步不能替代独立备份，建议定期导出到电脑或网盘。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            SwitchRow(
                title = "导出时包含 AI 配置与 API Key",
                subtitle = if (includeKeys) "已开启：导出文件包含密钥，请妥善保管，不要分享给他人" else "默认关闭（推荐）",
                checked = includeKeys,
                onChange = {
                    includeKeys = it
                    state.store.setBool(SettingsKeys.EXPORT_INCLUDES_KEYS, it)
                }
            )
            Spacer(Modifier.height(12.dp))
            ChipButton("导出 JSON 备份", true, Modifier.fillMaxWidth()) {
                createJson.launch(Backup.defaultJsonName())
            }
            Spacer(Modifier.height(8.dp))
            ChipButton("导出当前账本 CSV 明细", false, Modifier.fillMaxWidth()) {
                createCsv.launch(Backup.defaultCsvName())
            }
            Spacer(Modifier.height(8.dp))
            ChipButton("从备份文件恢复", false, Modifier.fillMaxWidth()) {
                openRestore.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "恢复采用按编号合并的方式：备份里已有的账单如果本机也存在，会跳过而不是覆盖，" +
                    "因此不会抹掉你手动改过的分类、消费性质与备注。" +
                    "当前账本：" + state.currentLedger().name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (busy) {
                Spacer(Modifier.height(10.dp))
                Text("正在读取备份文件…", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(120.dp))
        }
    }

    if (pendingRestoreText.isNotEmpty()) {
        val text = pendingRestoreText
        AlertDialog(
            onDismissRequest = { },
            title = { Text("确认恢复") },
            text = {
                Column {
                    Text("将把该备份中的账本、分类、规则与账单合并到本机。")
                    Text("已存在的账单会跳过，不会覆盖本机数据。", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestoreText = ""
                    busy = true
                    scope.launch {
                        val res = withContext(Dispatchers.IO) { Backup.restore(XzApp.db, text) }
                        busy = false
                        state.touch()
                        state.reloadLedgers()
                        if (res.errors.isNotEmpty() && res.txns + res.ledgers == 0) restoreError = res.errors.first()
                        else restoreResult = res
                    }
                }) { Text("开始恢复") }
            },
            dismissButton = { TextButton(onClick = { pendingRestoreText = "" }) { Text("取消") } }
        )
    }

    restoreResult?.let { r ->
        AlertDialog(
            onDismissRequest = { restoreResult = null },
            title = { Text("恢复完成") },
            text = {
                Column {
                    Text(r.summary())
                    if (r.errors.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        for (e in r.errors.take(6)) Text("· " + e, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { restoreResult = null }) { Text("好") } }
        )
    }

    if (restoreError.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { restoreError = "" },
            title = { Text("无法恢复") },
            text = { Text(restoreError) },
            confirmButton = { TextButton(onClick = { restoreError = "" }) { Text("好") } }
        )
    }
}

private fun querySize(ctx: android.content.Context, uri: Uri): Long {
    var size = -1L
    try {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.SIZE)
            if (i >= 0 && c.moveToFirst()) size = c.getLong(i)
        }
    } catch (e: Exception) {
        size = -1L
    }
    return size
}
