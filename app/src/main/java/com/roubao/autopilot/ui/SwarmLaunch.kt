package com.roubao.autopilot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roubao.autopilot.ui.theme.BaoziTheme
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * 启动动画要揭开的真实元素位置（窗口坐标），由 ChatScreen 在布局时上报。
 * 动画不画假界面：真实主页垫在下面，遮罩按这些位置逐个挖洞揭开，
 * 所以动画结束时的画面就是真实主页本身，天然无缝。
 */
object LaunchTargets {
    var topBar by mutableStateOf<Rect?>(null)
    var avatar by mutableStateOf<Rect?>(null)
    var greeting by mutableStateOf<Rect?>(null)
    var sub1 by mutableStateOf<Rect?>(null)
    var sub2 by mutableStateOf<Rect?>(null)
    var input by mutableStateOf<Rect?>(null)
}

fun LayoutCoordinates.launchRect(): Rect =
    Rect(positionInWindow(), Size(size.width.toFloat(), size.height.toFloat()))

/**
 * 冷启动 swarm 光标动画·无缝版（5.5 秒）：
 * 真实主页垫在下层，上面盖一层背景色遮罩；20 个带名字标签的彩色光标飞进来，
 * 沿着每个真实元素的边缘把它「画」出来——遮罩在对应位置挖洞，露出底下真实的元素。
 * 顺序：头像 → 问候语 → 两行副标题 → 输入框 → 顶栏，最后点一下发送键、散开，遮罩淡尽。
 * 只在 App 冷启动时播放一次，点击任意处跳过。若主页元素不在（比如直接打开旧会话），
 * 遮罩会提前淡出，不拖时间。绘制异常会被吞掉并直接结束动画，绝不拖垮 App。
 */
private const val SWARM_TOTAL_MS = 5500L

private class SwarmCursor(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    val color: Color,
    val name: String,
    val ringAngle: Float,
    val seed: Float,
    val orbitR: Float
)

private class SwarmRuntime {
    val cursors = mutableListOf<SwarmCursor>()
    var prevFrameMs: Long = -1L
    var failed: Boolean = false
}

private val SWARM_COLORS = listOf(
    Color(0xFF4D6BFE), Color(0xFFF65A8C), Color(0xFFFF8A3D), Color(0xFF2FBF71),
    Color(0xFF8B5CF6), Color(0xFF14B8A6), Color(0xFFEF4444), Color(0xFFF5B83D),
    Color(0xFF38BDF8), Color(0xFFFB7185), Color(0xFFA3E635), Color(0xFFF472B6),
    Color(0xFF60A5FA), Color(0xFFFACC15)
)

private val SWARM_NAMES = listOf(
    "Saak", "Daap", "Nova", "Echo", "Pix", "Neo",
    "Luna", "Ivy", "Rex", "Milo", "Zoe", "Ada",
    "Leo", "Muse", "Kiki", "Omar", "Pipi", "Yuki", "Finn", "Gus"
)

private fun easeInOut(x: Float): Float {
    val c = x.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}

private fun easeOut(x: Float): Float {
    val c = x.coerceIn(0f, 1f)
    return 1f - (1f - c) * (1f - c)
}

@OptIn(ExperimentalTextApi::class)
@Composable
fun SwarmLaunchAnimation(
    darkTheme: Boolean,
    onFinished: () -> Unit
) {
    val colors = BaoziTheme.colors
    val localDensity = LocalDensity.current
    val dpPx = with(localDensity) { 1.dp.toPx() }
    val textMeasurer = rememberTextMeasurer()
    val runtime = remember { SwarmRuntime() }

    var startMs by remember { mutableStateOf(-1L) }
    var frameMs by remember { mutableStateOf(0L) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    val tagStyle = remember {
        TextStyle(color = Color.White, fontSize = 9.5.sp, fontWeight = FontWeight.Medium)
    }
    val tagLayouts: List<TextLayoutResult> = remember {
        SWARM_NAMES.map { textMeasurer.measure(it, tagStyle) }
    }

    LaunchedEffect(canvasSize) {
        val w = canvasSize.width
        val h = canvasSize.height
        if (w > 0f && h > 0f && runtime.cursors.isEmpty()) {
            val rnd = Random(42)
            for (i in SWARM_NAMES.indices) {
                val (sx, sy) = when (i % 4) {
                    0 -> Pair(rnd.nextFloat() * w, -40f)
                    1 -> Pair(w + 40f, rnd.nextFloat() * h)
                    2 -> Pair(rnd.nextFloat() * w, h + 40f)
                    else -> Pair(-40f, rnd.nextFloat() * h)
                }
                runtime.cursors.add(
                    SwarmCursor(
                        x = sx, y = sy, vx = 0f, vy = 0f,
                        color = SWARM_COLORS[i % SWARM_COLORS.size],
                        name = SWARM_NAMES[i],
                        ringAngle = (i.toFloat() / SWARM_NAMES.size) * 360f,
                        seed = rnd.nextFloat() * 100f,
                        orbitR = (54f + rnd.nextFloat() * 84f)
                    )
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { now ->
                if (startMs < 0L) {
                    startMs = now
                }
                frameMs = now
            }
            if (runtime.failed) {
                break
            }
            val el = if (startMs > 0L) frameMs - startMs else 0L
            if (el > SWARM_TOTAL_MS) {
                break
            }
            // 主页元素迟迟没出现（不在空状态主页）：2.6 秒就收尾，不让用户干等
            if (el > 2600L && LaunchTargets.avatar == null) {
                break
            }
        }
        onFinished()
    }

    val elapsed = if (startMs > 0L) frameMs - startMs else 0L
    val t = (elapsed.toFloat() / SWARM_TOTAL_MS).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { onFinished() }
            }
    ) {
        // ---------- 遮罩层：背景色盖住真实主页，按真实元素位置逐个挖洞 ----------
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                try {
                    val avatar = LaunchTargets.avatar
                    val maskAlpha: Float
                    if (avatar == null) {
                        // 元素还没布局好先全盖；等太久就整体淡出
                        maskAlpha = if (elapsed < 1650L) {
                            1f
                        } else {
                            (1f - easeInOut((elapsed - 1650L) / 950f)).coerceIn(0f, 1f)
                        }
                        drawRect(color = colors.background, alpha = maskAlpha)
                    } else {
                        maskAlpha = when {
                            t < 0.90f -> 1f
                            else -> (1f - (t - 0.90f) / 0.10f).coerceIn(0f, 1f)
                        }
                        drawRect(color = colors.background, alpha = maskAlpha)

                        val pad = 5f * dpPx
                        // 头像：圆洞由小长大
                        val pA = easeOut((t - 0.08f) / 0.12f)
                        if (pA > 0f) {
                            val r = (avatar.width / 2f + 9f * dpPx) * pA
                            drawCircle(
                                color = Color.Black,
                                radius = r,
                                center = avatar.center,
                                blendMode = BlendMode.Clear
                            )
                        }
                        // 问候语：洞从左往右展开，像被写出来
                        val greet = LaunchTargets.greeting
                        val pG = ((t - 0.20f) / 0.16f).coerceIn(0f, 1f)
                        if (greet != null && pG > 0f) {
                            drawRect(
                                color = Color.Black,
                                topLeft = Offset(greet.left - pad, greet.top - pad),
                                size = Size((greet.width + pad * 2f) * pG, greet.height + pad * 2f),
                                blendMode = BlendMode.Clear
                            )
                        }
                        // 副标题两行：依次展开
                        val s1 = LaunchTargets.sub1
                        val pS1 = ((t - 0.36f) / 0.08f).coerceIn(0f, 1f)
                        if (s1 != null && pS1 > 0f) {
                            drawRect(
                                color = Color.Black,
                                topLeft = Offset(s1.left - pad, s1.top - pad),
                                size = Size((s1.width + pad * 2f) * pS1, s1.height + pad * 2f),
                                blendMode = BlendMode.Clear
                            )
                        }
                        val s2 = LaunchTargets.sub2
                        val pS2 = ((t - 0.44f) / 0.08f).coerceIn(0f, 1f)
                        if (s2 != null && pS2 > 0f) {
                            drawRect(
                                color = Color.Black,
                                topLeft = Offset(s2.left - pad, s2.top - pad),
                                size = Size((s2.width + pad * 2f) * pS2, s2.height + pad * 2f),
                                blendMode = BlendMode.Clear
                            )
                        }
                        // 输入框：圆角洞从中心长开
                        val input = LaunchTargets.input
                        val pI = easeOut((t - 0.52f) / 0.16f)
                        if (input != null && pI > 0f) {
                            val iw = input.width * pI
                            val ih = input.height * pI
                            drawRoundRect(
                                color = Color.Black,
                                topLeft = Offset(input.center.x - iw / 2f, input.center.y - ih / 2f),
                                size = Size(iw, ih),
                                cornerRadius = CornerRadius(24f * dpPx * pI, 24f * dpPx * pI),
                                blendMode = BlendMode.Clear
                            )
                        }
                        // 顶栏：洞从中间向两边展开
                        val bar = LaunchTargets.topBar
                        val pT = ((t - 0.68f) / 0.10f).coerceIn(0f, 1f)
                        if (bar != null && pT > 0f) {
                            val bw = bar.width * pT
                            drawRect(
                                color = Color.Black,
                                topLeft = Offset(bar.center.x - bw / 2f, bar.top - pad),
                                size = Size(bw, bar.height + pad * 2f),
                                blendMode = BlendMode.Clear
                            )
                        }
                    }
                } catch (e: Exception) {
                    runtime.failed = true
                }
            }
        }

        // ---------- 光标层 ----------
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) }
        ) {
            try {
                val w = size.width
                val h = size.height
                if (w <= 0f || h <= 0f || runtime.cursors.isEmpty()) {
                    return@Canvas
                }
                val cx = w / 2f
                val cursors = runtime.cursors

                val dtMs = if (runtime.prevFrameMs < 0L) {
                    16f
                } else {
                    (frameMs - runtime.prevFrameMs).toFloat().coerceIn(1f, 50f)
                }
                runtime.prevFrameMs = frameMs
                val dtScale = dtMs / 16.7f
                val timeSec = elapsed / 1000f

                val avatar = LaunchTargets.avatar
                val greet = LaunchTargets.greeting
                val s1 = LaunchTargets.sub1
                val s2 = LaunchTargets.sub2
                val input = LaunchTargets.input

                // 各工位光标的实时位置（都贴着真实元素的揭开前沿）
                val pA = easeOut((t - 0.08f) / 0.12f)
                val rimAngle = Math.toRadians((-90f + 360f * pA).toDouble())
                val avatarRim = avatar?.let {
                    Offset(
                        it.center.x + cos(rimAngle).toFloat() * (it.width / 2f + 9f * dpPx),
                        it.center.y + sin(rimAngle).toFloat() * (it.width / 2f + 9f * dpPx)
                    )
                }
                val pG = ((t - 0.20f) / 0.16f).coerceIn(0f, 1f)
                val penTip = greet?.let {
                    Offset(it.left + it.width * pG, it.center.y + it.height * 0.3f)
                }
                val pS1 = ((t - 0.36f) / 0.08f).coerceIn(0f, 1f)
                val pS2 = ((t - 0.44f) / 0.08f).coerceIn(0f, 1f)
                val subSweep = when {
                    t < 0.44f -> s1?.let { Offset(it.left + it.width * pS1, it.bottom + 4f * dpPx) }
                    else -> s2?.let { Offset(it.left + it.width * pS2, it.bottom + 4f * dpPx) }
                }
                val inputTrace = input?.let {
                    when {
                        t < 0.60f -> Offset(
                            it.left + it.width * ((t - 0.52f) / 0.08f).coerceIn(0f, 1f),
                            it.top
                        )
                        t < 0.66f -> Offset(
                            it.right,
                            it.top + it.height * ((t - 0.60f) / 0.06f).coerceIn(0f, 1f)
                        )
                        else -> Offset(it.right - 40f * dpPx, it.bottom - 27f * dpPx)
                    }
                }
                val sendC = input?.let { Offset(it.right - 40f * dpPx, it.bottom - 27f * dpPx) }

                // 群体焦点：跟着当前正在揭开的元素走
                val focus: Offset = when {
                    t < 0.36f -> avatar?.center ?: Offset(cx, h * 0.41f)
                    t < 0.52f -> greet?.center ?: Offset(cx, h * 0.52f)
                    t < 0.78f -> input?.center ?: Offset(cx, h * 0.88f)
                    else -> avatar?.center ?: Offset(cx, h * 0.41f)
                }

                // 发送键涟漪
                val rp = (t - 0.74f) / 0.05f
                if (sendC != null && rp in 0f..1f) {
                    drawCircle(
                        color = colors.primary.copy(alpha = (1f - rp) * 0.75f),
                        radius = (6f + rp * 26f) * dpPx,
                        center = sendC,
                        style = Stroke(width = 2f * dpPx)
                    )
                }

                for ((idx, c) in cursors.withIndex()) {
                    var steer = 0.055f
                    val target: Offset = when {
                        t >= 0.86f -> {
                            val dx = c.x - cx
                            val dy = c.y - focus.y
                            val len = hypot(dx, dy).coerceAtLeast(1f)
                            Offset(c.x + dx / len * w * 0.8f, c.y + dy / len * h * 0.8f)
                        }
                        idx == 0 && t in 0.05f..0.22f && avatarRim != null -> {
                            steer = 0.20f
                            avatarRim
                        }
                        idx == 1 && t in 0.19f..0.37f && penTip != null -> {
                            steer = 0.20f
                            penTip
                        }
                        idx == 2 && t in 0.35f..0.53f && subSweep != null -> {
                            steer = 0.18f
                            subSweep
                        }
                        idx == 3 && t in 0.51f..0.80f && inputTrace != null -> {
                            steer = 0.18f
                            inputTrace
                        }
                        else -> {
                            val a = Math.toRadians((c.ringAngle + timeSec * 55f + c.seed).toDouble())
                            Offset(
                                focus.x + cos(a).toFloat() * c.orbitR * dpPx,
                                focus.y + sin(a).toFloat() * c.orbitR * dpPx * 0.72f
                            )
                        }
                    }
                    c.vx = (c.vx + (target.x - c.x) * steer * dtScale) * 0.86f
                    c.vy = (c.vy + (target.y - c.y) * steer * dtScale) * 0.86f
                    for (o in cursors) {
                        if (o === c) continue
                        val dx = c.x - o.x
                        val dy = c.y - o.y
                        val d2 = dx * dx + dy * dy
                        val minD = 30f * dpPx
                        if (d2 > 0.01f && d2 < minD * minD) {
                            val d = hypot(dx, dy)
                            val push = (minD - d) * 0.06f * dtScale
                            c.vx += dx / d * push
                            c.vy += dy / d * push
                        }
                    }
                    c.x += c.vx * dtScale
                    c.y += c.vy * dtScale
                }

                // 光标本体（箭头 + 名字标签）；整体在最后阶段随遮罩一起隐去
                val cursorAlpha = when {
                    t < 0.90f -> 1f
                    else -> (1f - (t - 0.90f) / 0.10f).coerceIn(0f, 1f)
                }
                if (avatar == null && elapsed > 1650L) {
                    // 兜底淡出时光标也跟着淡
                }
                val arrowH = 21f * dpPx
                for ((idx, c) in cursors.withIndex()) {
                    val tilt = (c.vx * 0.05f).coerceIn(-13f, 13f)
                    rotate(degrees = tilt, pivot = Offset(c.x, c.y)) {
                        val path = Path().apply {
                            moveTo(c.x, c.y)
                            lineTo(c.x, c.y + arrowH * 0.72f)
                            lineTo(c.x + arrowH * 0.18f, c.y + arrowH * 0.56f)
                            lineTo(c.x + arrowH * 0.30f, c.y + arrowH * 0.88f)
                            lineTo(c.x + arrowH * 0.45f, c.y + arrowH * 0.79f)
                            lineTo(c.x + arrowH * 0.31f, c.y + arrowH * 0.50f)
                            lineTo(c.x + arrowH * 0.53f, c.y + arrowH * 0.50f)
                            close()
                        }
                        drawPath(path = path, color = Color.White.copy(alpha = cursorAlpha))
                        val inset = 1.6f * dpPx
                        val inner = Path().apply {
                            moveTo(c.x + inset * 0.4f, c.y + inset)
                            lineTo(c.x + inset * 0.4f, c.y + arrowH * 0.72f - inset * 0.4f)
                            lineTo(c.x + arrowH * 0.18f, c.y + arrowH * 0.56f)
                            lineTo(c.x + arrowH * 0.30f, c.y + arrowH * 0.88f - inset * 0.2f)
                            lineTo(c.x + arrowH * 0.45f - inset * 0.3f, c.y + arrowH * 0.79f - inset * 0.3f)
                            lineTo(c.x + arrowH * 0.31f, c.y + arrowH * 0.50f + inset * 0.4f)
                            lineTo(c.x + arrowH * 0.53f - inset * 0.5f, c.y + arrowH * 0.50f + inset * 0.4f)
                            close()
                        }
                        drawPath(path = inner, color = c.color.copy(alpha = cursorAlpha))
                    }
                    val layout = tagLayouts[idx % tagLayouts.size]
                    val tagW = layout.size.width + 13f * dpPx
                    val tagH = 16.5f * dpPx
                    val tagX = c.x + 11f * dpPx
                    val tagY = c.y + arrowH * 0.62f
                    drawRoundRect(
                        color = c.color.copy(alpha = cursorAlpha),
                        topLeft = Offset(tagX, tagY),
                        size = Size(tagW, tagH),
                        cornerRadius = CornerRadius(tagH / 2f, tagH / 2f)
                    )
                    drawText(
                        textLayoutResult = layout,
                        topLeft = Offset(
                            tagX + (tagW - layout.size.width) / 2f,
                            tagY + (tagH - layout.size.height) / 2f
                        ),
                        alpha = cursorAlpha
                    )
                }
            } catch (e: Exception) {
                runtime.failed = true
            }
        }
    }
}
