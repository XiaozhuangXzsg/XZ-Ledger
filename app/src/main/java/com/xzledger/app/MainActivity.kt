package com.xzledger.app

import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.xzledger.app.data.SettingsKeys
import com.xzledger.app.ui.ChipButton
import com.xzledger.app.ui.DetailScreen
import com.xzledger.app.ui.EntryScreen
import com.xzledger.app.ui.MineScreen
import com.xzledger.app.ui.NavStack
import com.xzledger.app.ui.Route
import com.xzledger.app.ui.SubScreens
import com.xzledger.app.ui.theme.GlassBackdrop
import com.xzledger.app.ui.theme.XzTheme
import com.xzledger.app.ui.theme.liquidGlass
import com.xzledger.app.widget.WidgetRefresh

class MainActivity : ComponentActivity() {
    /** 需求八「应用锁」：冷启动与从后台回到前台时都要求验证。 */
    private val locked = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        locked.value = XzApp.store.bool(SettingsKeys.APP_LOCK, false)
        setContent {
            val state = XzApp.state
            XzTheme(state.appearance) {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground
                ) {
                    Box(Modifier.fillMaxSize()) {
                        // ColorOS 17 风格彩色氛围底：液态玻璃需要下面的光晕才有通透感。
                        GlassBackdrop()
                        if (locked.value) {
                            LockScreen(onUnlock = { locked.value = false })
                        } else {
                            AppRoot(state)
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (XzApp.store.bool(SettingsKeys.APP_LOCK, false)) locked.value = true
        // 回到桌面时刷新小组件：一次覆盖所有数据变更路径，且避免在主线程读数据库。
        val ctx = applicationContext
        Thread {
            try {
                WidgetRefresh.updateAll(ctx)
            } catch (t: Throwable) {
            }
        }.start()
    }
}

/** 解锁页：优先系统锁屏验证（指纹/人脸/系统密码），也可用应用内密码。 */
@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    val ctx = LocalContext.current
    val store = XzApp.store
    val secret = remember { store.str(SettingsKeys.APP_LOCK_SECRET, "") }
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val credential = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK) onUnlock() else error = "系统验证未通过，可改用应用内密码"
    }

    @Suppress("DEPRECATION")
    fun systemVerify() {
        val km = ctx.getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        val intent = km.createConfirmDeviceCredentialIntent("解锁猫猫记账", "验证指纹、人脸或系统锁屏密码")
        if (intent == null) error = "本机未设置锁屏密码，无法使用系统验证，请使用应用内密码"
        else credential.launch(intent)
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(56.dp)
                .liquidGlass(corner = 18.dp, strong = true, elevation = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        Text("猫猫记账已加锁", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "账目只属于你自己。验证通过后才能查看账本内容。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (secret.isEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text(
                "尚未设置应用内密码。可在「我的 → 个性化 → 应用锁」中修改或关闭应用锁。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(14.dp))
            ChipButton("直接进入", true, Modifier.fillMaxWidth()) { onUnlock() }
            return@Column
        }
        Spacer(Modifier.height(18.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it; error = "" },
            label = { Text("应用密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        if (error.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(14.dp))
        ChipButton("解锁", true, Modifier.fillMaxWidth()) {
            if (input == secret) onUnlock() else error = "密码不正确"
        }
        Spacer(Modifier.height(8.dp))
        ChipButton("使用指纹 / 人脸 / 系统密码验证", false, Modifier.fillMaxWidth()) { systemVerify() }
    }
}

@Composable
fun AppRoot(state: AppState) {
    val nav = remember { NavStack() }
    var tab by remember { mutableIntStateOf(0) }
    val appearance = state.appearance
    val anim = appearance.animations
    // 子页面从按下的位置「长」出来：记录最近一次按下点在屏幕中的比例位置。
    var tapX by remember { mutableFloatStateOf(0.5f) }
    var tapY by remember { mutableFloatStateOf(0.62f) }
    // 真正开始打开子页面时把原点锁住，避免页面内的后续点击改掉退场原点。
    var openX by remember { mutableFloatStateOf(0.5f) }
    var openY by remember { mutableFloatStateOf(0.62f) }
    var rootW by remember { mutableIntStateOf(1) }
    var rootH by remember { mutableIntStateOf(1) }
    val swallow = remember { MutableInteractionSource() }

    BackHandler(enabled = nav.stack.isNotEmpty()) { nav.pop() }

    // 首次启动确保存在默认账本，并校正指向已删除账本的设置。
    LaunchedEffect(Unit) { state.ensureDefaultLedger() }

    LaunchedEffect(state.pendingTab) {
        val t = state.pendingTab
        if (t != null) {
            nav.clear()
            tab = t
            state.pendingTab = null
        }
    }

    // 子页面入场 / 退场进度：0 = 完全收起（未打开），1 = 完全展开。
    val route = nav.current
    var shown by remember { mutableStateOf<Route?>(null) }
    val progress = remember { Animatable(0f) }
    val smooth = anim && appearance.smoothAnimations
    val pageBg = MaterialTheme.colorScheme.background

    LaunchedEffect(route, smooth) {
        if (route != null) {
            val fresh = shown == null || progress.value > 0.98f
            if (fresh) {
                openX = tapX
                openY = tapY
            }
            shown = route
            when {
                !smooth -> progress.animateTo(1f, tween(140))
                fresh -> {
                    progress.snapTo(0f)
                    progress.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 360f))
                }
                else -> {
                    // 子页面之间继续前进：不再从零放大，只做一次轻微推进，避免整屏闪一下。
                    progress.snapTo(0.94f)
                    progress.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 380f))
                }
            }
        } else if (shown != null) {
            if (smooth) progress.animateTo(0f, spring(dampingRatio = 0.88f, stiffness = 520f))
            else progress.animateTo(0f, tween(120))
            shown = null
        }
    }
    val origin = TransformOrigin(openX, openY)

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { rootW = it.width.coerceAtLeast(1); rootH = it.height.coerceAtLeast(1) }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press) {
                            val p = event.changes.firstOrNull()?.position
                            if (p != null) {
                                tapX = (p.x / rootW.toFloat()).coerceIn(0f, 1f)
                                tapY = (p.y / rootH.toFloat()).coerceIn(0f, 1f)
                            }
                        }
                    }
                }
            }
    ) {
        // 底层：三主页 + 胶囊导航。打开子页面时整体后退、圆角收拢，露出下面的玻璃层次。
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = if (smooth) progress.value.coerceIn(0f, 1f) else 1f
                    val s = 1f - 0.065f * p
                    scaleX = s
                    scaleY = s
                    clip = p > 0.001f
                    if (p > 0.001f) shape = RoundedCornerShape((28f * p).dp)
                }
        ) {
            Box(Modifier.fillMaxSize().statusBarsPadding()) {
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        val dir = if (targetState > initialState) 1 else -1
                        (slideInHorizontally(tween(260)) { dir * it / 5 } + fadeIn(tween(190))) togetherWith
                            (slideOutHorizontally(tween(260)) { -dir * it / 5 } + fadeOut(tween(170)))
                    },
                    label = "tabPage"
                ) { t ->
                    Box(Modifier.fillMaxSize()) {
                        when (t) {
                            0 -> DetailScreen(state, nav)
                            1 -> EntryScreen(state, nav)
                            else -> MineScreen(state, nav)
                        }
                    }
                }

                GlassNav(
                    tab = tab,
                    onSelect = { tab = it },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }

        // 压暗层：子页面打开时把底层整体压暗，玻璃卡片才有「浮起来」的层次。
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (smooth) 0.34f * progress.value.coerceIn(0f, 1f) else 0f }
                .background(Color.Black)
        )

        // 子页面：以按下的位置为原点缩放，同时把圆角从 34dp 收到 0、内容略微滞后，
        // 看起来是从那个按键里「长」出一整页，而不是把整页平面放大。
        val page = shown
        if (page != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val p = if (smooth) progress.value else 1f
                        val pc = p.coerceIn(0f, 1.05f)
                        val s = if (smooth) 0.32f + 0.68f * pc else 1f
                        scaleX = s
                        scaleY = s
                        transformOrigin = origin
                        alpha = if (smooth) ((p - 0.02f) / 0.72f).coerceIn(0f, 1f) else 1f
                        shape = RoundedCornerShape((34f * (1f - pc).coerceIn(0f, 1f)).dp)
                        clip = true
                    }
            ) {
                Surface(
                    Modifier.fillMaxSize(),
                    color = if (smooth) pageBg.copy(alpha = 0.985f) else pageBg
                ) {
                    Box(Modifier.fillMaxSize()) {
                        // 吞掉空白处的点击，避免隔着页面点到下面被压住的胶囊导航。
                        Box(
                            Modifier
                                .fillMaxSize()
                                .clickable(interactionSource = swallow, indication = null) {}
                        )
                        Box(Modifier.fillMaxSize().statusBarsPadding()) {
                            SubScreens(route = page, state = state, nav = nav)
                        }
                    }
                }
            }
        }
    }
}

/** 底部胶囊导航：玻璃胶囊 + 会「弹」的滑动高亮块（低阻尼弹簧，停下时轻微回弹）。 */
@Composable
private fun GlassNav(tab: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val itemWidth = 78.dp
    val gap = 2.dp
    val glass = com.xzledger.app.ui.theme.LocalAppearance.current.glass
    // 选中高亮块随页面切换滑动（需求：底部条滑动效果）。
    val highlight by animateDpAsState(
        targetValue = (itemWidth + gap) * tab,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 260f),
        label = "navSlide"
    )
    val pill = remember { Animatable(1f) }
    LaunchedEffect(tab) {
        pill.snapTo(0.82f)
        pill.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 380f))
    }
    val shape = RoundedCornerShape(23.dp)
    Box(
        modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.liquidGlass(corner = 34.dp, strong = true, elevation = 20.dp)) {
            Box(Modifier.padding(6.dp)) {
                Box(
                    Modifier
                        .offset(x = highlight)
                        .size(width = itemWidth, height = 46.dp)
                        .graphicsLayer { scaleX = pill.value; scaleY = pill.value }
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = if (glass) 0.30f else 0.24f),
                                    MaterialTheme.colorScheme.primary.copy(alpha = if (glass) 0.12f else 0.18f)
                                )
                            ),
                            shape
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.18f), shape)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NavItem("明细", Icons.Outlined.ReceiptLong, tab == 0, Modifier.width(itemWidth)) { onSelect(0) }
                    Spacer(Modifier.width(gap))
                    NavItem("记账", Icons.Outlined.AddCircleOutline, tab == 1, Modifier.width(itemWidth)) { onSelect(1) }
                    Spacer(Modifier.width(gap))
                    NavItem("我的", Icons.Outlined.Person, tab == 2, Modifier.width(itemWidth)) { onSelect(2) }
                }
            }
        }
    }
}

@Composable
private fun NavItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val color by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "navColor"
    )
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.12f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 520f),
        label = "navIcon"
    )
    // 只保留滑动高亮胶囊，不再出现长方形的水波纹点击背景。
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .height(46.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = color,
            modifier = Modifier.size(22.dp).graphicsLayer { scaleX = iconScale; scaleY = iconScale }
        )
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
