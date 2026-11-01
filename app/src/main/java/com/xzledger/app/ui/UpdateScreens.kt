package com.xzledger.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.xzledger.app.BuildConfig
import com.xzledger.app.XzApp
import com.xzledger.app.update.UpdateChecker
import com.xzledger.app.update.Updater

/**
 * 更新对话框：与应用其它页面同一套玻璃面板样式。
 * 只负责展示与按钮，检查 / 下载 / 校验 / 安装的状态都在 Updater 里。
 */
@Composable
fun UpdateHost() {
    if (!Updater.visible) return
    val ctx = LocalContext.current
    val store = XzApp.store
    val phase = Updater.phase
    val info = Updater.info
    val progress = Updater.progress
    val message = Updater.message
    val busy = phase == Updater.Phase.CHECKING || phase == Updater.Phase.DOWNLOADING || phase == Updater.Phase.VERIFYING

    Dialog(onDismissRequest = {
        if (phase != Updater.Phase.DOWNLOADING && phase != Updater.Phase.VERIFYING) Updater.snooze(store)
    }) {
        GlassPanel(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp),
            contentPadding = PaddingValues(20.dp)
        ) {
            Text(
                titleOf(phase),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "本地数据不会被清除；更新只是覆盖安装同一个应用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))

            when (phase) {
                Updater.Phase.CHECKING -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    Text("正在读取 GitHub 上的最新正式版本…", style = MaterialTheme.typography.bodyMedium)
                }

                Updater.Phase.UP_TO_DATE -> {
                    VersionLine("当前版本", BuildConfig.VERSION_NAME + "（" + BuildConfig.VERSION_CODE + "）")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        message ?: "当前已是最新版本",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Updater.Phase.AVAILABLE -> {
                    if (info != null) {
                        VersionLine("当前版本", BuildConfig.VERSION_NAME + "（" + BuildConfig.VERSION_CODE + "）")
                        VersionLine("最新版本", info.versionName + "（" + info.versionCode + "）")
                        if (info.apkSize > 0L) VersionLine("安装包", sizeText(info.apkSize))
                        Spacer(Modifier.height(10.dp))
                        Text("更新内容", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            notesText(info),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.heightIn(max = 190.dp).verticalScroll(rememberScrollState())
                        )
                    }
                }

                Updater.Phase.DOWNLOADING -> {
                    if (info != null) VersionLine("最新版本", info.versionName + "（" + info.versionCode + "）")
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(
                        (progress * 100).toInt().toString() + "%" + (message?.let { "　" + it } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Updater.Phase.VERIFYING -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    Text(message ?: "正在校验 SHA-256、包名、版本号与签名…", style = MaterialTheme.typography.bodyMedium)
                }

                Updater.Phase.READY -> {
                    if (info != null) {
                        VersionLine("待安装版本", info.versionName + "（" + info.versionCode + "）")
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "SHA-256、包名、版本号与签名均已校验通过。接下来由系统安装器接管，" +
                                "不需要卸载旧版本，账本、设置与备份都会保留。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    message?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }

                Updater.Phase.ERROR -> {
                    Text(
                        message ?: "更新未完成",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "旧版本仍可正常使用。可以点「重试」重新检查，或到 GitHub 手动下载。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> Text(" ")
            }

            Spacer(Modifier.height(16.dp))
            when (phase) {
                Updater.Phase.CHECKING -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton({ Updater.close() }) { Text("关闭") }
                    }
                }

                Updater.Phase.UP_TO_DATE -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (info != null) TextButton({ openBrowser(ctx, info.htmlUrl) }) { Text("在 GitHub 查看") }
                        TextButton({ Updater.close() }) { Text("知道了") }
                    }
                }

                Updater.Phase.AVAILABLE -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton({ Updater.skipCurrent(store) }) { Text("跳过此版本") }
                        Row {
                            TextButton({ Updater.snooze(store) }) { Text("稍后再说") }
                            TextButton({ Updater.download(ctx) }) { Text("立即更新") }
                        }
                    }
                }

                Updater.Phase.DOWNLOADING -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton({ Updater.close() }) { Text("取消下载") }
                    }
                }

                Updater.Phase.VERIFYING -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text("请稍候…", style = MaterialTheme.typography.bodySmall)
                    }
                }

                Updater.Phase.READY -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton({ Updater.close() }) { Text("稍后安装") }
                        TextButton({ Updater.install(ctx) }) { Text("立即安装") }
                    }
                }

                Updater.Phase.ERROR -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton({ openBrowser(ctx, info?.htmlUrl ?: "https://github.com/XiaozhuangXzsg/XZ-Ledger/releases/latest") }) {
                            Text("手动下载")
                        }
                        Row {
                            TextButton({ Updater.close() }) { Text("关闭") }
                            TextButton({ Updater.check(ctx, store, manual = true) }) { Text("重试") }
                        }
                    }
                }

                else -> Unit
            }
        }
    }
}

@Composable
private fun VersionLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

private fun titleOf(phase: Updater.Phase): String = when (phase) {
    Updater.Phase.CHECKING -> "检查更新"
    Updater.Phase.UP_TO_DATE -> "已是最新版本"
    Updater.Phase.AVAILABLE -> "发现新版本"
    Updater.Phase.DOWNLOADING -> "正在下载安装包"
    Updater.Phase.VERIFYING -> "正在校验安装包"
    Updater.Phase.READY -> "可以安装"
    Updater.Phase.ERROR -> "更新未完成"
    else -> "更新"
}

private fun notesText(info: UpdateChecker.UpdateInfo): String {
    val raw = info.notes.trim()
    if (raw.isEmpty()) return "该版本没有填写更新说明，可在 GitHub Release 页面查看。"
    return if (raw.length > 1200) raw.take(1200) + "…（完整说明见 GitHub Release）" else raw
}

private fun sizeText(bytes: Long): String {
    if (bytes <= 0L) return "未知"
    val mb = bytes / 1024.0 / 1024.0
    return String.format(java.util.Locale.CHINA, "%.1f MB", mb)
}

private fun openBrowser(ctx: android.content.Context, url: String) {
    try {
        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    } catch (_: Exception) {
    }
}
