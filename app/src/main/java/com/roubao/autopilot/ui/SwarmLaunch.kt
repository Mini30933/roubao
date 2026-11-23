package com.roubao.autopilot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
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
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * 冷启动 swarm 光标动画（6 秒）：20 个带名字标签的彩色光标飞进来，
 * 一起把「新对话」主页原样画出来——描出头像圆圈、逐字写出「嗨！今天想聊些什么？」、
 * 扫出副标题、勾出底部输入框，最后点一下发送键、散开淡出，无缝落到真实主页上。
 * 只在 App 冷启动时播放一次，点击任意处跳过。
 * 纯装饰动画：内部状态都是普通变量（不在绘制阶段写快照状态），绘制异常会被吞掉并直接结束动画。
 */
private const val SWARM_TOTAL_MS = 6000L
private const val SWARM_GREETING = "嗨！今天想聊些什么？"
private const val SWARM_SUB1 = "在「设置」里填好角色设定，我就是你要的那个角色"
private const val SWARM_SUB2 = "聊天中让我操作手机，我也会照做"

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

/** 动画运行时状态（普通变量，不参与 Compose 快照） */
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

    // 所有要画的文字先量好，避免每帧重复测量
    val tagStyle = remember {
        TextStyle(color = Color.White, fontSize = 9.5.sp, fontWeight = FontWeight.Medium)
    }
    val tagLayouts: List<TextLayoutResult> = remember {
        SWARM_NAMES.map { textMeasurer.measure(it, tagStyle) }
    }
    val greetingStyle = remember {
        TextStyle(color = Color(0xFF111111), fontSize = 25.sp, fontWeight = FontWeight.Bold)
    }
    val greetingLayouts: List<TextLayoutResult> = remember {
        (0..SWARM_GREETING.length).map { textMeasurer.measure(SWARM_GREETING.substring(0, it), greetingStyle) }
    }
    val subStyle = remember {
        TextStyle(color = Color(0xFF9A9A9A), fontSize = 13.sp)
    }
    val sub1Layout = remember { textMeasurer.measure(SWARM_SUB1, subStyle) }
    val sub2Layout = remember { textMeasurer.measure(SWARM_SUB2, subStyle) }
    val titleLayout = remember {
        textMeasurer.measure("新对话", TextStyle(color = Color(0xFF111111), fontSize = 19.sp, fontWeight = FontWeight.Bold))
    }
    val placeholderLayout = remember {
        textMeasurer.measure("发消息或按住说话", TextStyle(color = Color(0xFF9A9A9A), fontSize = 15.sp))
    }
    val chipLayout = remember {
        textMeasurer.measure("角色设定", TextStyle(color = Color(0xFF666666), fontSize = 12.5.sp))
    }
    val sLayout = remember {
        textMeasurer.measure("S", TextStyle(color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold))
    }

    // 尺寸确定后生成光标（从屏幕四边外飞入）
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
            if (startMs > 0L && frameMs - startMs > SWARM_TOTAL_MS) {
                break
            }
        }
        onFinished()
    }

    val elapsed = if (startMs > 0L) frameMs - startMs else 0L
    val t = (elapsed.toFloat() / SWARM_TOTAL_MS).coerceIn(0f, 1f)
    // 最后 10% 整体淡出，落到真实主页上
    val overlayAlpha = when {
        t < 0.90f -> 1f
        else -> (1f - (t - 0.90f) / 0.10f).coerceIn(0f, 1f)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(alpha = overlayAlpha)
            .background(colors.background)
            .pointerInput(Unit) {
                detectTapGestures { onFinished() }
            }
    ) {
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
                val primary = colors.primary
                val ink = colors.textPrimary
                val subInk = colors.textHint

                val dtMs = if (runtime.prevFrameMs < 0L) {
                    16f
                } else {
                    (frameMs - runtime.prevFrameMs).toFloat().coerceIn(1f, 50f)
                }
                runtime.prevFrameMs = frameMs
                val dtScale = dtMs / 16.7f
                val timeSec = elapsed / 1000f

                // ---------- 主页几何（与真实主页同位置） ----------
                val topBarY = h * 0.078f
                val avatarC = Offset(cx, h * 0.408f)
                val avatarR = 36f * dpPx
                val greetFull = greetingLayouts[SWARM_GREETING.length]
                val greetX = cx - greetFull.size.width / 2f
                val greetY = h * 0.487f
                val sub1X = cx - sub1Layout.size.width / 2f
                val sub1Y = h * 0.548f
                val sub2X = cx - sub2Layout.size.width / 2f
                val sub2Y = sub1Y + 21f * dpPx
                val cardX0 = w * 0.045f
                val cardX1 = w * 0.955f
                val cardH = 104f * dpPx
                val cardBot = h - 14f * dpPx
                val cardTop = cardBot - cardH
                val cardRadius = 26f * dpPx
                val sendC = Offset(cardX1 - 40f * dpPx, cardBot - 27f * dpPx)

                // ---------- 各元素的绘制进度 ----------
                val topAlpha = easeInOut((t - 0.02f) / 0.10f)
                val traceP = ((t - 0.05f) / 0.17f).coerceIn(0f, 1f)
                val avatarFill = easeInOut((t - 0.20f) / 0.07f)
                val typeP = ((t - 0.25f) / 0.20f).coerceIn(0f, 1f)
                val typedCount = floor(typeP * SWARM_GREETING.length).toInt().coerceIn(0, SWARM_GREETING.length)
                val sub1P = easeInOut((t - 0.45f) / 0.07f)
                val sub2P = easeInOut((t - 0.51f) / 0.07f)
                val cardStrokeP = ((t - 0.57f) / 0.09f).coerceIn(0f, 1f)
                val cardFill = easeInOut((t - 0.63f) / 0.07f)
                val cardInner = easeInOut((t - 0.68f) / 0.08f)

                // ---------- 顶栏 ----------
                if (topAlpha > 0f) {
                    val menuX = w * 0.075f
                    for (i in -1..1) {
                        drawLine(
                            color = ink.copy(alpha = topAlpha),
                            start = Offset(menuX - 10f * dpPx, topBarY + i * 5.5f * dpPx),
                            end = Offset(menuX + 10f * dpPx, topBarY + i * 5.5f * dpPx),
                            strokeWidth = 2.2f * dpPx
                        )
                    }
                    drawText(
                        textLayoutResult = titleLayout,
                        color = ink,
                        topLeft = Offset(cx - titleLayout.size.width / 2f, topBarY - titleLayout.size.height / 2f),
                        alpha = topAlpha
                    )
                    // 右上编辑笔（简化成斜线 + 笔尖）
                    val ex = w * 0.925f
                    drawLine(
                        color = ink.copy(alpha = topAlpha),
                        start = Offset(ex - 7f * dpPx, topBarY + 7f * dpPx),
                        end = Offset(ex + 6f * dpPx, topBarY - 6f * dpPx),
                        strokeWidth = 2.2f * dpPx
                    )
                    drawLine(
                        color = ink.copy(alpha = topAlpha),
                        start = Offset(ex - 7f * dpPx, topBarY + 7f * dpPx),
                        end = Offset(ex - 8.5f * dpPx, topBarY + 1.5f * dpPx),
                        strokeWidth = 2.2f * dpPx
                    )
                }

                // ---------- 头像：先描圈，再填充，最后浮现 S ----------
                if (traceP > 0f) {
                    drawArc(
                        color = primary.copy(alpha = 0.95f),
                        startAngle = -90f,
                        sweepAngle = 360f * traceP,
                        useCenter = false,
                        topLeft = Offset(avatarC.x - avatarR, avatarC.y - avatarR),
                        size = Size(avatarR * 2f, avatarR * 2f),
                        style = Stroke(width = 3f * dpPx)
                    )
                }
                if (avatarFill > 0f) {
                    drawCircle(
                        color = primary.copy(alpha = avatarFill),
                        radius = avatarR,
                        center = avatarC
                    )
                    drawText(
                        textLayoutResult = sLayout,
                        topLeft = Offset(
                            avatarC.x - sLayout.size.width / 2f,
                            avatarC.y - sLayout.size.height / 2f
                        ),
                        alpha = avatarFill
                    )
                }

                // ---------- 问候语：逐字写出 ----------
                if (typedCount > 0) {
                    val layout = greetingLayouts[typedCount]
                    drawText(
                        textLayoutResult = layout,
                        color = ink,
                        topLeft = Offset(greetX, greetY)
                    )
                }
                val penTip = Offset(
                    greetX + greetingLayouts[typedCount].size.width,
                    greetY + greetFull.size.height * 0.72f
                )

                // ---------- 副标题：逐行扫出 ----------
                if (sub1P > 0f) {
                    drawText(
                        textLayoutResult = sub1Layout,
                        color = subInk,
                        topLeft = Offset(sub1X, sub1Y),
                        alpha = sub1P
                    )
                }
                if (sub2P > 0f) {
                    drawText(
                        textLayoutResult = sub2Layout,
                        color = subInk,
                        topLeft = Offset(sub2X, sub2Y),
                        alpha = sub2P
                    )
                }

                // ---------- 输入框：先勾边，再填充，再长出里面的东西 ----------
                if (cardStrokeP > 0f) {
                    drawRoundRect(
                        color = subInk.copy(alpha = 0.55f * cardStrokeP),
                        topLeft = Offset(cardX0, cardTop),
                        size = Size(cardX1 - cardX0, cardH),
                        cornerRadius = CornerRadius(cardRadius, cardRadius),
                        style = Stroke(width = 1.4f * dpPx)
                    )
                }
                if (cardFill > 0f) {
                    drawRoundRect(
                        color = colors.backgroundInput.copy(alpha = cardFill),
                        topLeft = Offset(cardX0, cardTop),
                        size = Size(cardX1 - cardX0, cardH),
                        cornerRadius = CornerRadius(cardRadius, cardRadius)
                    )
                }
                if (cardInner > 0f) {
                    drawText(
                        textLayoutResult = placeholderLayout,
                        color = subInk,
                        topLeft = Offset(cardX0 + 18f * dpPx, cardTop + 14f * dpPx),
                        alpha = cardInner
                    )
                    // 角色设定胶囊
                    val chipX = cardX0 + 14f * dpPx
                    val chipY = cardBot - 42f * dpPx
                    val chipW = chipLayout.size.width + 36f * dpPx
                    val chipH = 29f * dpPx
                    drawRoundRect(
                        color = subInk.copy(alpha = 0.6f * cardInner),
                        topLeft = Offset(chipX, chipY),
                        size = Size(chipW, chipH),
                        cornerRadius = CornerRadius(chipH / 2f, chipH / 2f),
                        style = Stroke(width = 1.1f * dpPx)
                    )
                    drawCircle(
                        color = colors.textSecondary.copy(alpha = cardInner),
                        radius = 3.6f * dpPx,
                        center = Offset(chipX + 13f * dpPx, chipY + chipH / 2f - 3.4f * dpPx)
                    )
                    drawArc(
                        color = colors.textSecondary.copy(alpha = cardInner),
                        startAngle = 180f,
                        sweepAngle = 180f,
                        useCenter = false,
                        topLeft = Offset(chipX + 7.4f * dpPx, chipY + chipH / 2f - 1.5f * dpPx),
                        size = Size(11.2f * dpPx, 9f * dpPx),
                        style = Stroke(width = 1.6f * dpPx)
                    )
                    drawText(
                        textLayoutResult = chipLayout,
                        color = colors.textSecondary,
                        topLeft = Offset(
                            chipX + 24f * dpPx,
                            chipY + (chipH - chipLayout.size.height) / 2f
                        ),
                        alpha = cardInner
                    )
                    // 加号
                    val plusC = Offset(cardX1 - 92f * dpPx, cardBot - 27f * dpPx)
                    drawLine(
                        color = colors.textSecondary.copy(alpha = cardInner),
                        start = Offset(plusC.x - 8f * dpPx, plusC.y),
                        end = Offset(plusC.x + 8f * dpPx, plusC.y),
                        strokeWidth = 2f * dpPx
                    )
                    drawLine(
                        color = colors.textSecondary.copy(alpha = cardInner),
                        start = Offset(plusC.x, plusC.y - 8f * dpPx),
                        end = Offset(plusC.x, plusC.y + 8f * dpPx),
                        strokeWidth = 2f * dpPx
                    )
                    // 发送圆钮 + 上箭头
                    drawCircle(
                        color = (if (darkTheme) Color(0xFF2E2E2E) else Color(0xFFE3E3E3)).copy(alpha = cardInner),
                        radius = 25f * dpPx,
                        center = sendC
                    )
                    val arrowInk = if (darkTheme) Color.White else Color(0xFF666666)
                    drawLine(
                        color = arrowInk.copy(alpha = cardInner),
                        start = Offset(sendC.x, sendC.y + 8f * dpPx),
                        end = Offset(sendC.x, sendC.y - 8f * dpPx),
                        strokeWidth = 2.4f * dpPx
                    )
                    drawLine(
                        color = arrowInk.copy(alpha = cardInner),
                        start = Offset(sendC.x - 5.5f * dpPx, sendC.y - 2.5f * dpPx),
                        end = Offset(sendC.x, sendC.y - 8.5f * dpPx),
                        strokeWidth = 2.4f * dpPx
                    )
                    drawLine(
                        color = arrowInk.copy(alpha = cardInner),
                        start = Offset(sendC.x + 5.5f * dpPx, sendC.y - 2.5f * dpPx),
                        end = Offset(sendC.x, sendC.y - 8.5f * dpPx),
                        strokeWidth = 2.4f * dpPx
                    )
                }

                // ---------- 发送键点击涟漪 ----------
                val rp = (t - 0.75f) / 0.05f
                if (rp in 0f..1f) {
                    drawCircle(
                        color = primary.copy(alpha = (1f - rp) * 0.75f),
                        radius = (6f + rp * 26f) * dpPx,
                        center = sendC,
                        style = Stroke(width = 2f * dpPx)
                    )
                }

                // ---------- 光标分工 ----------
                val focus: Offset = when {
                    t < 0.25f -> avatarC
                    t < 0.46f -> Offset(cx, greetY + greetFull.size.height / 2f)
                    t < 0.58f -> Offset(cx, sub1Y + 10f * dpPx)
                    t < 0.78f -> Offset(cx, cardTop + cardH / 2f)
                    else -> Offset(cx, h * 0.45f)
                }
                val arcTip = Offset(
                    avatarC.x + cos(Math.toRadians((-90f + 360f * traceP).toDouble())).toFloat() * avatarR,
                    avatarC.y + sin(Math.toRadians((-90f + 360f * traceP).toDouble())).toFloat() * avatarR
                )
                val subSweep: Offset = if (t < 0.51f) {
                    Offset(sub1X + sub1Layout.size.width * sub1P, sub1Y + sub1Layout.size.height + 4f * dpPx)
                } else {
                    Offset(sub2X + sub2Layout.size.width * sub2P, sub2Y + sub2Layout.size.height + 4f * dpPx)
                }
                val cardTrace: Offset = when {
                    t < 0.65f -> Offset(
                        cardX0 + (cardX1 - cardX0) * ((t - 0.57f) / 0.08f).coerceIn(0f, 1f),
                        cardTop
                    )
                    t < 0.70f -> Offset(
                        cardX1,
                        cardTop + (sendC.y - cardTop) * ((t - 0.65f) / 0.05f).coerceIn(0f, 1f)
                    )
                    else -> sendC
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
                        idx == 0 && t in 0.04f..0.24f -> {
                            steer = 0.20f
                            arcTip
                        }
                        idx == 1 && t in 0.24f..0.46f -> {
                            steer = 0.20f
                            penTip
                        }
                        idx == 2 && t in 0.44f..0.58f -> {
                            steer = 0.18f
                            subSweep
                        }
                        idx == 3 && t in 0.56f..0.79f -> {
                            steer = 0.18f
                            cardTrace
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

                // ---------- 绘制光标（箭头 + 名字标签） ----------
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
                        drawPath(path = path, color = Color.White)
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
                        drawPath(path = inner, color = c.color)
                    }
                    val layout = tagLayouts[idx % tagLayouts.size]
                    val tagW = layout.size.width + 13f * dpPx
                    val tagH = 16.5f * dpPx
                    val tagX = c.x + 11f * dpPx
                    val tagY = c.y + arrowH * 0.62f
                    drawRoundRect(
                        color = c.color,
                        topLeft = Offset(tagX, tagY),
                        size = Size(tagW, tagH),
                        cornerRadius = CornerRadius(tagH / 2f, tagH / 2f)
                    )
                    drawText(
                        textLayoutResult = layout,
                        topLeft = Offset(
                            tagX + (tagW - layout.size.width) / 2f,
                            tagY + (tagH - layout.size.height) / 2f
                        )
                    )
                }
            } catch (e: Exception) {
                // 装饰动画绝不能拖垮 App：任何绘制异常都直接结束动画
                runtime.failed = true
            }
        }
    }
}
