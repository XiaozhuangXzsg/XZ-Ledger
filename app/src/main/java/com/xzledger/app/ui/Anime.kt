package com.xzledger.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xzledger.app.R
import com.xzledger.app.ui.theme.LocalAppearance
import com.xzledger.app.util.Money

/**
 * 桑多涅（原神 · 至冬执行官）二次元元素。
 *
 * 全部元素统一由「个性化设置 → 外观 → 二次元元素（桑多涅）」开关控制：
 * 关闭后界面回到纯记账风格，不再出现看板娘、台词气泡与星芒点缀。
 */

/** 是否开启二次元元素。 */
@Composable
fun animeEnabled(): Boolean = LocalAppearance.current.anime

/** 四角星芒：原神 UI 常见的装饰点。 */
@Composable
fun Sparkle(size: Dp, color: Color = MaterialTheme.colorScheme.primary, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val c = this.center
        val r = this.size.minDimension / 2f
        val waist = r * 0.18f
        val path = Path().apply {
            moveTo(c.x, c.y - r)
            quadraticBezierTo(c.x + waist, c.y - waist, c.x + r, c.y)
            quadraticBezierTo(c.x + waist, c.y + waist, c.x, c.y + r)
            quadraticBezierTo(c.x - waist, c.y + waist, c.x - r, c.y)
            quadraticBezierTo(c.x - waist, c.y - waist, c.x, c.y - r)
            close()
        }
        drawPath(path, color)
    }
}

/** 桑多涅头像：裁圆显示；带一层金色细环，不加任何底色。调用方负责给出尺寸。 */
@Composable
fun SandroneFace(modifier: Modifier = Modifier, ring: Boolean = true) {
    Box(modifier.fillMaxSize().clip(CircleShape)) {
        Image(
            painter = painterResource(R.mipmap.ic_launcher_foreground),
            contentDescription = "桑多涅",
            contentScale = ContentScale.Crop,
            // 原图四周留白多，放大后由外层圆形裁掉。
            modifier = Modifier.fillMaxSize().scale(1.4f)
        )
        if (ring) {
            Box(
                Modifier.fillMaxSize().clip(CircleShape)
                    .border(1.2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.80f), CircleShape)
            )
        }
    }
}

/** 固定尺寸的桑多涅徽章：外发光 + 头像 + 右上角星芒。 */
@Composable
fun SandroneBadge(size: Dp, modifier: Modifier = Modifier, glow: Boolean = true) {
    val gold = MaterialTheme.colorScheme.primary
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (glow) {
            Box(
                Modifier.matchParentSize().scale(1.25f).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(gold.copy(alpha = 0.28f), Color.Transparent)))
            )
        }
        Box(Modifier.size(size)) { SandroneFace() }
        Sparkle(size * 0.26f, gold, Modifier.align(Alignment.TopEnd).padding(top = 1.dp))
    }
}

/** 看板娘台词气泡：点台词换下一句；点头像可回到当月（微信式「点一下头像回到今天」）。 */
@Composable
fun MascotBubble(text: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null,
                 onAvatarClick: (() -> Unit)? = null) {
    val gold = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(16.dp)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(38.dp)
                .then(if (onAvatarClick != null) Modifier.clickable { onAvatarClick() } else Modifier)
        ) { SandroneFace() }
        Spacer(Modifier.width(9.dp))
        Box(
            Modifier
                .weight(1f)
                .clip(shape)
                .background(gold.copy(alpha = 0.10f))
                .border(1.dp, gold.copy(alpha = 0.35f), shape)
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
                .padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Sparkle(9.dp, gold.copy(alpha = 0.9f))
                Spacer(Modifier.width(6.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * 看板娘台词：按本月记账情况生成几句，点击气泡轮换。
 * @param netExpense 本月净支出（分，已扣退款）
 * @param income 本月收入（分）
 * @param budgetTotal 本月总额预算（分），null = 未设置
 * @param count 本月账单笔数
 *
 * 「花掉了百分之多少的预算」按 净支出 − 收入 计算（收入抵消支出），最低按 0 计，
 * 不会出现负数百分比。
 */
fun mascotLines(netExpense: Long, income: Long, budgetTotal: Long?, count: Int): List<String> {
    val lines = ArrayList<String>(4)
    // 真正动用预算的钱：支出（净）减去收入，最低 0。
    val used = (netExpense - income).coerceAtLeast(0L)
    if (count <= 0 || netExpense <= 0L) {
        lines += "这个月还没有账单，陪桑多涅记下第一笔吧～"
        lines += "先记一笔试试？记完就能看到这个月的小金库啦。"
    } else {
        val pct = if (budgetTotal != null && budgetTotal > 0L) Math.round(used * 100.0 / budgetTotal).toInt() else -1
        lines += if (pct >= 0) "本月已经花掉 " + pct + "% 的预算，桑多涅帮你盯着。"
        else "本月已经花掉 ¥" + Money.centsToPlain(used) + "，还没设置预算，桑多涅先记着。"
        lines += "已经记了 " + count + " 笔，坚持记账的人运气都不会太差哦。"
    }
    lines += "「账本之事，交给在下便好。」—— 桑多涅"
    lines += "至冬的执行官也会认真记录每一枚摩拉的。"
    return lines
}
