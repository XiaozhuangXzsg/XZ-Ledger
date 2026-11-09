package com.xzledger.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xzledger.app.data.Nature
import com.xzledger.app.data.TxnType
import com.xzledger.app.ui.theme.LocalAppearance
import com.xzledger.app.ui.theme.glassDark
import com.xzledger.app.ui.theme.liquidGlass
import com.xzledger.app.util.Money
import kotlinx.coroutines.launch

/**
 * 液态玻璃面板（ColorOS 17 风格）：半透明基底 + 斜向高光 + 主题色环境反光 + 渐变厚边 + 柔和投影。
 * 关闭「液态玻璃效果」后退回不透明纯色卡片，布局不变。
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    corner: Dp = 28.dp,
    strong: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .liquidGlass(corner = corner, strong = strong)
            .padding(contentPadding)
    ) { content() }
}

@Composable
fun Color.luminanceIsDark(): Boolean = luminance() < 0.5f

@Composable
fun Card2(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Box(
        modifier
            .liquidGlass(corner = 20.dp, elevation = 8.dp)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) { content() }
}

/** 顶栏：返回键是一枚悬浮玻璃圆钮，标题加大加粗，整体向 ColorOS 的「大标题 + 浮起操作」靠拢。 */
@Composable
fun TopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            Box(
                Modifier
                    .size(40.dp)
                    .liquidGlass(corner = 20.dp, elevation = 6.dp)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
        } else Spacer(Modifier.width(4.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        actions()
    }
}

/** 设置行：按下时整行轻微内收（ColorOS 的「按下去」手感），不再出现方形水波纹。 */
@Composable
fun SettingRow(title: String, subtitle: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && onClick != null) 0.985f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 900f),
        label = "rowPress"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .then(
                if (onClick != null) Modifier.clickable(interactionSource = interaction, indication = null) { onClick() }
                else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(
                subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        trailing()
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(title, subtitle, trailing = { GlassSwitch(checked = checked, onCheckedChange = onChange) })
}

/**
 * 液态玻璃开关（ColorOS 17 风格）：胶囊轨道 + 玻璃高光 + 可拖动的白色玻璃滑块。
 * 点按与左右拖动都能切换；滑块用低阻尼弹簧回弹，关闭玻璃时退化为纯色轨道。
 */
@Composable
fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val dark = glassDark()
    val glass = LocalAppearance.current.glass
    val primary = MaterialTheme.colorScheme.primary
    val scope = rememberCoroutineScope()
    val pos = remember { Animatable(if (checked) 1f else 0f) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(pos.value) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val trackW = 50.dp
    val trackH = 30.dp
    val thumbSize = 24.dp
    val trackShape = RoundedCornerShape(percent = 50)
    val density = LocalDensity.current
    val trackWidthPx = with(density) { trackW.toPx() }
    val travelPx = with(density) { (trackW - thumbSize - 6.dp).toPx() }

    LaunchedEffect(checked) {
        pos.animateTo(if (checked) 1f else 0f, spring(dampingRatio = 0.55f, stiffness = 700f))
    }
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 1.10f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 900f),
        label = "switchPress"
    )
    val offTrack = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.22f else 0.14f)
    val trackBrush = if (checked) {
        Brush.verticalGradient(listOf(primary.copy(alpha = 0.98f), primary.copy(alpha = 0.74f)))
    } else {
        Brush.verticalGradient(listOf(offTrack, offTrack))
    }
    val borderColor = if (checked) Color.White.copy(alpha = 0.30f) else Color.White.copy(alpha = if (dark) 0.16f else 0.55f)
    val dragState = rememberDraggableState { delta ->
        if (!dragging) {
            dragging = true
            dragValue = pos.value
        }
        dragValue = (dragValue + delta / trackWidthPx).coerceIn(0f, 1f)
    }

    Box(
        modifier
            .alpha(if (enabled) 1f else 0.45f)
            .size(trackW, trackH)
            .shadow(if (glass) 6.dp else 0.dp, trackShape, clip = false)
            .clip(trackShape)
            .background(trackBrush)
            .border(1.dp, borderColor, trackShape)
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                enabled = enabled,
                onDragStopped = {
                    val target = dragValue >= 0.5f
                    scope.launch {
                        pos.snapTo(dragValue)
                        dragging = false
                        if (target != checked) onCheckedChange?.invoke(target)
                        else pos.animateTo(if (checked) 1f else 0f, spring(dampingRatio = 0.55f, stiffness = 700f))
                    }
                }
            )
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) {
                onCheckedChange?.invoke(!checked)
            }
    ) {
        // 玻璃轨道的内高光 / 下内阴影
        if (glass) {
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = if (dark) 0.16f else 0.55f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.10f)
                        )
                    )
                )
            )
        }
        Box(
            Modifier
                .padding(3.dp)
                .size(thumbSize)
                .graphicsLayer {
                    translationX = (if (dragging) dragValue else pos.value) * travelPx
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .shadow(2.dp, CircleShape, clip = false)
                .background(Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFEDEDF2))), CircleShape)
                .drawBehind {
                    // 滑块上的一点镜面反光，避免变成死白的圆片
                    val c = Offset(size.width * 0.34f, size.height * 0.26f)
                    val r = size.minDimension * 0.68f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color.White.copy(alpha = 0.85f), Color.Transparent),
                            center = c,
                            radius = r
                        ),
                        radius = r,
                        center = c
                    )
                }
        )
    }
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
        Text(
            text, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
        )
    }
}

@Composable
fun ConfirmDialog(
    title: String, text: String, confirmText: String = "确定",
    onConfirm: () -> Unit, onDismiss: () -> Unit
) {
    val glass = LocalAppearance.current.glass
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(30.dp),
        containerColor = if (glass) MaterialTheme.colorScheme.surface.copy(alpha = 0.94f) else MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm() }) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 底部选择面板：透明容器 + 悬浮玻璃面板 + 顶部拖拽提示条（ColorOS 的浮层卡片）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> ChoiceSheet(
    title: String,
    options: List<Pair<String, T>>,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.Transparent,
        scrimColor = Color.Black.copy(alpha = 0.38f),
        dragHandle = null
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 14.dp)
                .liquidGlass(corner = 30.dp, strong = true, elevation = 24.dp)
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(2.dp))
                )
            }
            Text(
                title, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
            )
            Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                options.forEach { (label, value) ->
                    SettingRow(label, onClick = { onPick(value); onDismiss() })
                }
            }
        }
    }
}

/** 分别滚动的年／月滚轮：中间项高亮、其余渐隐（iOS 风格）；滚动停止后回调当前项，确认时才真正生效。 */
@Composable
fun WheelPicker(
    items: List<String>,
    initialIndex: Int,
    modifier: Modifier = Modifier,
    visibleCount: Int = 5,
    onIndexChange: (Int) -> Unit
) {
    val itemHeight = 42.dp
    val state = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
    val fling = rememberSnapFlingBehavior(lazyListState = state)
    val pad = itemHeight * ((visibleCount - 1) / 2)
    val itemPx = with(LocalDensity.current) { itemHeight.toPx() }
    val centered by remember(state, itemPx) {
        derivedStateOf {
            val idx = state.firstVisibleItemIndex
            if (state.firstVisibleItemScrollOffset > itemPx / 2f) idx + 1 else idx
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex }.collect { onIndexChange(it.coerceIn(0, items.size - 1)) }
    }
    Box(modifier.height(itemHeight * visibleCount), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxWidth().height(itemHeight).background(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                RoundedCornerShape(14.dp)
            )
        )
        LazyColumn(
            state = state, flingBehavior = fling,
            contentPadding = PaddingValues(vertical = pad),
            modifier = Modifier.fillMaxSize()
        ) {
            items(items.size) { i ->
                val isCenter = i == centered
                Box(Modifier.fillMaxWidth().height(itemHeight), contentAlignment = Alignment.Center) {
                    Box(Modifier.graphicsLayer {
                        scaleX = if (isCenter) 1.06f else 0.92f
                        scaleY = scaleX
                    }) {
                        Text(
                            items[i],
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isCenter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.40f),
                            fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

/** 可点击的设置行：等价于 SettingRow 并把点击作为主行为，避免把 onClick 误写成 trailing。 */
@Composable
fun MenuRow(title: String, subtitle: String? = null, onClick: () -> Unit) {
    SettingRow(title, subtitle, onClick = onClick)
}

/** 胶囊筛选按钮：未选中时是一枚玻璃胶囊，选中时换成主题色渐变。 */
@Composable
fun ChipButton(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val glass = LocalAppearance.current.glass
    val shape = RoundedCornerShape(percent = 50)
    val interaction = remember { MutableInteractionSource() }
    val base = if (selected) {
        Modifier
            .shadow(if (glass) 8.dp else 0.dp, shape, clip = false)
            .background(
                Brush.verticalGradient(
                    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha = 0.82f))
                ),
                shape
            )
    } else {
        Modifier.liquidGlass(corner = 18.dp, elevation = 6.dp)
    }
    Box(
        modifier
            .then(base)
            .clip(shape)
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 分类／账户头像：柔和色底 + 简洁线条图标（emoji 仅作为匹配键，不再直接显示）。 */
@Composable
fun CategoryAvatar(icon: String, size: Dp = 40.dp, tint: Color? = null, name: String? = null) {
    val look = remember(icon, name) { CatIcons.look(icon, name) }
    val color = tint ?: look.color
    Box(
        Modifier
            .size(size)
            .background(color.copy(alpha = 0.16f), CircleShape)
            .border(BorderStroke(1.dp, color.copy(alpha = 0.32f)), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            look.icon,
            contentDescription = name ?: icon,
            tint = color,
            modifier = Modifier.size(size * 0.54f)
        )
    }
}

@Composable
fun MoneyText(cents: Long, type: TxnType, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge) {
    val sign = when (type) {
        TxnType.EXPENSE -> "-"
        TxnType.INCOME -> "+"
        TxnType.REFUND -> "+"
        TxnType.TRANSFER -> ""
    }
    val color = when (type) {
        TxnType.EXPENSE -> com.xzledger.app.ui.theme.expenseColor()
        TxnType.INCOME -> com.xzledger.app.ui.theme.incomeColor()
        TxnType.REFUND -> com.xzledger.app.ui.theme.refundColor()
        TxnType.TRANSFER -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(sign + Money.centsToPlain(cents), style = style, color = color, fontWeight = FontWeight.SemiBold)
}

@Composable
fun NatureBadge(nature: Nature?) {
    if (nature == null) return
    val color = when (nature) {
        Nature.PERMANENT -> MaterialTheme.colorScheme.tertiary
        Nature.PERIODIC -> MaterialTheme.colorScheme.primary
        Nature.INSTANT -> MaterialTheme.colorScheme.secondary
    }
    Box(
        Modifier.background(color.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) { Text(nature.label, style = MaterialTheme.typography.labelSmall, color = color) }
}

/**
 * 消费性质单选：长期／即时／周期三选一（可选带「不设置」）。
 * 三项中文标签较长，用 FlowRow 自动换行，窄屏也不会被挤成两行字。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NatureChoiceRow(
    nature: Nature?,
    modifier: Modifier = Modifier,
    noneLabel: String? = "不设置",
    onChange: (Nature?) -> Unit
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (noneLabel != null) ChipButton(noneLabel, nature == null) { onChange(null) }
        Nature.entries.forEach { n -> ChipButton(n.label, nature == n) { onChange(n) } }
    }
}

@Composable
fun Divider2() {
    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 20.dp, end = 16.dp, top = 18.dp, bottom = 6.dp)
    )
}

@Composable
fun VerticalScrollColumn(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { content() }
}

@Composable
fun SimpleSearchField(value: String, onChange: (String) -> Unit, placeholder: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (value.isEmpty()) Text(
                        placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    inner()
                }
            )
        }
    }
}
