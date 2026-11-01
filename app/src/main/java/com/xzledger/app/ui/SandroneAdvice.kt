package com.xzledger.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QuestionMark
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xzledger.app.AppState
import com.xzledger.app.XzApp
import com.xzledger.app.data.AiClient
import com.xzledger.app.data.AiPerm
import com.xzledger.app.data.Json
import com.xzledger.app.data.SettingsKeys
import com.xzledger.app.data.activeAiConfig
import com.xzledger.app.data.budgets
import com.xzledger.app.data.categorySpending
import com.xzledger.app.data.monthSummary
import com.xzledger.app.util.Dates
import com.xzledger.app.util.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「我的」页的桑多涅面板：配置了有效 AI 并授权读取账本时，可以长按 3 秒让桑多涅看一次本月账单，
 * 给出评价与建议；一天最多一次（次数与文本都存在本地 setting 表里）。
 *
 * 长按期间绕整个模块画一圈进度环；没有可用 AI 时保留固定台词并说明原因，不做任何联网。
 */
@Composable
fun SandroneAdvicePanel(state: AppState, modifier: Modifier = Modifier) {
    val store = state.store
    val version = state.dataVersion
    val today = Dates.today()
    val adviceMonth = Dates.monthKey(System.currentTimeMillis())
    val cfg = remember(version) { XzApp.db.activeAiConfig() }
    val canRead = remember(version) { XzApp.db.allAiPermissions()[AiPerm.READ] == true }

    var adviceText by remember(version) {
        mutableStateOf(if (store.str(SettingsKeys.MINE_ADVICE_DATE, "") == today) store.str(SettingsKeys.MINE_ADVICE_TEXT, "") else "")
    }
    var adviceDate by remember(version) { mutableStateOf(store.str(SettingsKeys.MINE_ADVICE_DATE, "")) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val usedToday = adviceDate == today
    val canAsk = cfg != null && canRead && !usedToday

    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val primary = MaterialTheme.colorScheme.primary

    fun fire() {
        if (busy) return
        busy = true
        note = null
        scope.launch {
            val text = requestAdvice(adviceMonth, state.ledgerId)
            if (text != null) {
                adviceText = text
                adviceDate = today
                store.setStr(SettingsKeys.MINE_ADVICE_TEXT, text)
                store.setStr(SettingsKeys.MINE_ADVICE_DATE, today)
                note = null
            } else {
                note = "这次没能拿到评价：请确认 AI 接口可用，明天再试一次。"
            }
            busy = false
        }
    }

    val caption = when {
        busy -> "桑多涅正在翻这个月的账本…"
        adviceText.isNotEmpty() -> adviceText
        else -> "「账本之事，交给在下便好。」至冬的执行官陪你把每一枚摩拉都记清楚。"
    }
    val hintLine = when {
        busy -> "请稍候，在下很快看完。"
        !canRead -> "配置 AI 接口并授权「读取账本」后，长按可以让在下评价本月账单。"
        cfg == null -> "还没有可用的 AI 接口；在「AI API 配置」填好后可用。"
        usedToday -> "今天已经评价过了，明天再来。"
        else -> "长按 3 秒，让在下看一次本月账单。"
    }

    Box(
        modifier.fillMaxWidth().padding(horizontal = 16.dp)
            // 长按 3 秒：进度环走满才真正请求，松手提前取消；没有可用 AI 时完全不响应。
            .pointerInput(canAsk, busy) {
                detectTapGestures(
                    onPress = {
                        if (canAsk && !busy) {
                            var fired = false
                            val job = scope.launch {
                                progress.animateTo(1f, tween(3000, easing = LinearEasing))
                                fired = true
                                fire()
                            }
                            tryAwaitRelease()
                            if (!fired) job.cancel() else progress.snapTo(0f)
                        }
                    }
                )
            }
    ) {
        GlassPanel(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                SandroneBadge(52.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("桑多涅", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        caption,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        note ?: hintLine,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (note != null) MaterialTheme.colorScheme.error else primary.copy(alpha = 0.85f)
                    )
                }
                Spacer(Modifier.width(8.dp))
                // 需求：问号要正好落在圆圈中心。全角「？」的字形在圆里会偏，改用居中的矢量图标。
                Box(
                    Modifier.size(26.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.QuestionMark,
                        contentDescription = "长按 3 秒让桑多涅评价本月账单",
                        tint = primary,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }

        // 进度环画在面板之上：绕整个模块一圈，随长按时长填充。
        Box(
            Modifier.matchParentSize().drawBehind {
                val p = progress.value
                if (p <= 0.001f) return@drawBehind
                val strokePx = 3.dp.toPx()
                val inset = strokePx / 2f + 1.dp.toPx()
                val w = size.width - inset * 2f
                val h = size.height - inset * 2f
                if (w <= 0f || h <= 0f) return@drawBehind
                val r = (28.dp.toPx() - inset).coerceIn(0f, minOf(w, h) / 2f)
                val path = Path().apply {
                    addRoundRect(RoundRect(inset, inset, inset + w, inset + h, CornerRadius(r, r)))
                }
                val measure = PathMeasure()
                measure.setPath(path, false)
                val len = measure.length
                if (len <= 0f) return@drawBehind
                val seg = Path()
                measure.getSegment(0f, len * p.coerceIn(0f, 1f), seg, true)
                drawPath(seg, color = primary, style = Stroke(width = strokePx, cap = StrokeCap.Round))
            }
        )
    }
}

/**
 * 请求一次本月评价。只把必要的汇总结论发给模型（收入、净支出、预算、结余、前几类），
 * 不发送商户明细、备注与任何凭据；失败返回 null，由界面如实提示。
 */
private suspend fun requestAdvice(month: String, ledgerId: String): String? = withContext(Dispatchers.IO) {
    try {
        val db = XzApp.db
        val cfg = db.activeAiConfig() ?: return@withContext null
        if (db.allAiPermissions()[AiPerm.READ] != true) return@withContext null
        val s = db.monthSummary(ledgerId, month)
        val net = s.expense - s.refund
        val budget = db.budgets(ledgerId, month).firstOrNull { it.categoryId == null }?.amount
        val balance = (budget ?: 0L) + s.income - net
        val top = db.categorySpending(ledgerId, month).entries
            .sortedByDescending { it.value }.take(4)
            .map { (db.category(it.key)?.name ?: "未分类") + " ¥" + Money.centsToPlain(it.value) }
        val sys = "你是记账应用里的角色「桑多涅」：优雅、克制、略带傲娇，自称「在下」。根据用户的本月账单，" +
            "用不超过两句中文给出评价与一条可执行建议；不要 Markdown、不要列表、不要罗列全部数字，" +
            "不要提及自己是 AI，直接输出正文（80 字以内）。"
        val user = buildString {
            append("月份：").append(month)
            append("\n收入：¥").append(Money.centsToPlain(s.income))
            append("\n支出（净）：¥").append(Money.centsToPlain(net))
            append("\n退款：¥").append(Money.centsToPlain(s.refund))
            append("\n月预算：").append(budget?.let { "¥" + Money.centsToPlain(it) } ?: "未设置")
            append("\n结余（预算+收入-支出）：¥").append(Money.centsToPlain(balance))
            append("\n账单笔数：").append(s.count)
            if (top.isNotEmpty()) append("\n支出前几类：").append(top.joinToString("、"))
        }
        val messages = Json.arr(
            listOf(
                Json.obj("role" to Json.str("system"), "content" to Json.str(sys)),
                Json.obj("role" to Json.str("user"), "content" to Json.str(user))
            )
        )
        val raw = AiClient.complete(cfg, messages, null)
        val v = Json.parseOrNull(raw) ?: return@withContext null
        val msg = Json.get(Json.list(Json.get(v, "choices")).firstOrNull(), "message")
        val text = Json.str(Json.get(msg, "content"))?.trim() ?: return@withContext null
        text.ifEmpty { null }?.take(240)
    } catch (t: Throwable) {
        null
    }
}
