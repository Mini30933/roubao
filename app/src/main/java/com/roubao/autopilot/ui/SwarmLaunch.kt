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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.roubao.autopilot.ui.theme.BaoziTheme
import kotlin.math.cos
import kotlin.math.sin

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

    /** 遮罩层自身在窗口中的原点；元素位置减去它才是遮罩画布的本地坐标 */
    var rootOffset by mutableStateOf(Offset.Zero)
}

fun LayoutCoordinates.launchRect(): Rect =
    Rect(positionInWindow(), Size(size.width.toFloat(), size.height.toFloat()))

/**
 * 冷启动动画·分工协作版（5.5 秒）：
 * 真实主页垫底，遮罩按真实元素位置逐个揭开（头像圆洞带柔边、文字柔边扫过、
 * 输入框与顶栏整体淡入），结束即真实主页。
 * 只用 4 个光标，每个都有明确工位、全程配合，没有闲置打转的：
 * A/B 两人各描半个头像圈，再各扫一行副标题，干完离场；
 * C 写问候语、勾输入框边框；D 最后进场点发送。
 * 只在冷启动播放一次，点击跳过；元素不在（直接打开旧会话）时提前淡出；
 * 绘制异常会被吞掉并直接结束动画，绝不拖垮 App。
 */
private const val SWARM_TOTAL_MS = 5500L

private var animFailed = false

private fun seg(t: Float, s: Float, e: Float): Float =
    ((t - s) / (e - s)).coerceIn(0f, 1f)

private fun easeInOut(x: Float): Float {
    val c = x.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}

private fun easeOut(x: Float): Float {
    val c = x.coerceIn(0f, 1f)
    return 1f - (1f - c) * (1f - c)
}

private fun lerp(a: Float, b: Float, f: Float): Float = a + (b - a) * f

private fun lerpO(a: Offset, b: Offset, f: Float): Offset =
    Offset(lerp(a.x, b.x, f), lerp(a.y, b.y, f))

private fun travel(t: Float, s: Float, e: Float, from: Offset, to: Offset): Offset =
    lerpO(from, to, easeInOut(seg(t, s, e)))

private fun actorAlpha(t: Float, inS: Float, inE: Float, outS: Float, outE: Float): Float {
    val fin = seg(t, inS, inE)
    val fout = 1f - seg(t, outS, outE)
    return minOf(fin, fout).coerceIn(0f, 1f)
}

/** 圆角矩形周长上的点：从上边左端出发顺时针走，u 为 0..1 */
private fun roundedRectPoint(rect: Rect, cr: Float, u: Float): Offset {
    val sw = rect.width - 2f * cr
    val sh = rect.height - 2f * cr
    val arc = (Math.PI.toFloat() / 2f) * cr
    val total = 2f * sw + 2f * sh + 4f * arc
    var d = u.coerceIn(0f, 1f) * total
    if (d <= sw) return Offset(rect.left + cr + d, rect.top)
    d -= sw
    if (d <= arc) {
        val a = -Math.PI.toFloat() / 2f + (d / arc) * (Math.PI.toFloat() / 2f)
        return Offset(rect.right - cr + cos(a) * cr, rect.top + cr + sin(a) * cr)
    }
    d -= arc
    if (d <= sh) return Offset(rect.right, rect.top + cr + d)
    d -= sh
    if (d <= arc) {
        val a = (d / arc) * (Math.PI.toFloat() / 2f)
        return Offset(rect.right - cr + cos(a) * cr, rect.bottom - cr + sin(a) * cr)
    }
    d -= arc
    if (d <= sw) return Offset(rect.right - cr - d, rect.bottom)
    d -= sw
    if (d <= arc) {
        val a = Math.PI.toFloat() / 2f + (d / arc) * (Math.PI.toFloat() / 2f)
        return Offset(rect.left + cr + cos(a) * cr, rect.bottom - cr + sin(a) * cr)
    }
    d -= arc
    if (d <= sh) return Offset(rect.left, rect.bottom - cr - d)
    d -= sh
    val a = Math.PI.toFloat() + (d / arc.coerceAtLeast(1f)) * (Math.PI.toFloat() / 2f)
    return Offset(rect.left + cr + cos(a) * cr, rect.top + cr + sin(a) * cr)
}

private fun DrawScope.drawCursorArrow(pos: Offset, color: Color, alpha: Float, dpPx: Float) {
    if (alpha <= 0.01f) return
    val h = 21f * dpPx
    val path = Path().apply {
        moveTo(pos.x, pos.y)
        lineTo(pos.x, pos.y + h * 0.72f)
        lineTo(pos.x + h * 0.18f, pos.y + h * 0.56f)
        lineTo(pos.x + h * 0.30f, pos.y + h * 0.88f)
        lineTo(pos.x + h * 0.45f, pos.y + h * 0.79f)
        lineTo(pos.x + h * 0.31f, pos.y + h * 0.50f)
        lineTo(pos.x + h * 0.53f, pos.y + h * 0.50f)
        close()
    }
    drawPath(path = path, color = Color.White.copy(alpha = alpha))
    val inset = 1.6f * dpPx
    val inner = Path().apply {
        moveTo(pos.x + inset * 0.4f, pos.y + inset)
        lineTo(pos.x + inset * 0.4f, pos.y + h * 0.72f - inset * 0.4f)
        lineTo(pos.x + h * 0.18f, pos.y + h * 0.56f)
        lineTo(pos.x + h * 0.30f, pos.y + h * 0.88f - inset * 0.2f)
        lineTo(pos.x + h * 0.45f - inset * 0.3f, pos.y + h * 0.79f - inset * 0.3f)
        lineTo(pos.x + h * 0.31f, pos.y + h * 0.50f + inset * 0.4f)
        lineTo(pos.x + h * 0.53f - inset * 0.5f, pos.y + h * 0.50f + inset * 0.4f)
        close()
    }
    drawPath(path = inner, color = color.copy(alpha = alpha))
}

/** 文字洞：从左往右扫，前沿带渐变柔边，不会把字切成一半；留白给足，不缺角 */
private fun DrawScope.wipeHole(rect: Rect, p: Float, padX: Float, padY: Float, feather: Float) {
    if (p <= 0f) return
    val left = rect.left - padX
    val right = rect.right + padX
    val top = rect.top - padY
    val height = rect.height + padY * 2f
    if (p >= 1f) {
        drawRect(
            color = Color.Black,
            topLeft = Offset(left, top),
            size = Size(right - left, height),
            blendMode = BlendMode.Clear
        )
        return
    }
    val fx = left + (right - left) * p
    val endX = fx + feather
    val span = (endX - left).coerceAtLeast(1f)
    val stop = ((fx - left) / span).coerceIn(0f, 1f)
    val brush = Brush.horizontalGradient(
        colorStops = arrayOf(0f to Color.Black, stop to Color.Black, 1f to Color.Transparent),
        startX = left,
        endX = endX
    )
    drawRect(
        brush = brush,
        topLeft = Offset(left, top),
        size = Size(span, height),
        blendMode = BlendMode.Clear
    )
}

@Composable
fun SwarmLaunchAnimation(
    darkTheme: Boolean,
    onFinished: () -> Unit
) {
    val colors = BaoziTheme.colors
    val localDensity = LocalDensity.current
    val dpPx = with(localDensity) { 1.dp.toPx() }

    var startMs by remember { mutableStateOf(-1L) }
    var frameMs by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        animFailed = false
        while (true) {
            withFrameMillis { now ->
                if (startMs < 0L) {
                    startMs = now
                }
                frameMs = now
            }
            if (animFailed) {
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
            .onGloballyPositioned { LaunchTargets.rootOffset = it.positionInWindow() }
            .pointerInput(Unit) {
                detectTapGestures { onFinished() }
            }
    ) {
        // ---------- 遮罩层：背景色盖住真实主页，按真实元素位置逐个揭开 ----------
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                try {
                    val origin = LaunchTargets.rootOffset
                    fun Rect?.loc(): Rect? = this?.translate(-origin.x, -origin.y)
                    val avatar = LaunchTargets.avatar.loc()
                    if (avatar == null) {
                        // 元素还没布局好先全盖；等太久就整体淡出
                        val maskAlpha = if (elapsed < 1650L) {
                            1f
                        } else {
                            (1f - easeInOut((elapsed - 1650L) / 950f)).coerceIn(0f, 1f)
                        }
                        drawRect(color = colors.background, alpha = maskAlpha)
                    } else {
                        val maskAlpha = when {
                            t < 0.90f -> 1f
                            else -> (1f - (t - 0.90f) / 0.10f).coerceIn(0f, 1f)
                        }
                        drawRect(color = colors.background, alpha = maskAlpha)

                        // 头像：圆洞由小长大，边缘带柔边
                        val pA = easeOut(seg(t, 0.06f, 0.22f))
                        if (pA > 0f) {
                            val rFull = avatar.width / 2f + 9f * dpPx
                            val r = rFull * pA
                            val f = 5f * dpPx
                            val brush = Brush.radialGradient(
                                colorStops = arrayOf(
                                    0f to Color.Black,
                                    (r / (r + f)).coerceIn(0f, 1f) to Color.Black,
                                    1f to Color.Transparent
                                ),
                                center = avatar.center,
                                radius = r + f
                            )
                            drawCircle(
                                brush = brush,
                                radius = r + f,
                                center = avatar.center,
                                blendMode = BlendMode.Clear
                            )
                        }
                        // 问候语 + 两行副标题：柔边扫过
                        val greet = LaunchTargets.greeting.loc()
                        if (greet != null) {
                            wipeHole(greet, easeInOut(seg(t, 0.20f, 0.38f)), 8f * dpPx, 9f * dpPx, 34f * dpPx)
                        }
                        val s1 = LaunchTargets.sub1.loc()
                        if (s1 != null) {
                            wipeHole(s1, easeInOut(seg(t, 0.36f, 0.48f)), 7f * dpPx, 7f * dpPx, 26f * dpPx)
                        }
                        val s2 = LaunchTargets.sub2.loc()
                        if (s2 != null) {
                            wipeHole(s2, easeInOut(seg(t, 0.46f, 0.58f)), 7f * dpPx, 7f * dpPx, 26f * dpPx)
                        }
                        // 输入框：整框淡入（不切里面的占位文字）
                        val input = LaunchTargets.input.loc()
                        val pI = easeOut(seg(t, 0.56f, 0.76f))
                        if (input != null && pI > 0f) {
                            drawRoundRect(
                                color = Color.Black.copy(alpha = pI),
                                topLeft = Offset(input.left - 2f * dpPx, input.top - 2f * dpPx),
                                size = Size(input.width + 4f * dpPx, input.height + 4f * dpPx),
                                cornerRadius = CornerRadius(24f * dpPx, 24f * dpPx),
                                blendMode = BlendMode.Clear
                            )
                        }
                        // 顶栏：整体淡入
                        val bar = LaunchTargets.topBar.loc()
                        val pT = easeOut(seg(t, 0.66f, 0.82f))
                        if (bar != null && pT > 0f) {
                            drawRect(
                                color = Color.Black.copy(alpha = pT),
                                topLeft = Offset(bar.left - 4f * dpPx, bar.top - 4f * dpPx),
                                size = Size(bar.width + 8f * dpPx, bar.height + 8f * dpPx),
                                blendMode = BlendMode.Clear
                            )
                        }
                    }
                } catch (e: Exception) {
                    animFailed = true
                }
            }
        }

        // ---------- 光标层：4 个光标，分工协作，没有闲置的 ----------
        Canvas(modifier = Modifier.fillMaxSize()) {
            try {
                val origin = LaunchTargets.rootOffset
                fun Rect?.loc(): Rect? = this?.translate(-origin.x, -origin.y)
                val avatar = LaunchTargets.avatar.loc() ?: return@Canvas
                val greet = LaunchTargets.greeting.loc()
                val s1 = LaunchTargets.sub1.loc()
                val s2 = LaunchTargets.sub2.loc()
                val input = LaunchTargets.input.loc()

                val globalFade = when {
                    t < 0.90f -> 1f
                    else -> (1f - (t - 0.90f) / 0.10f).coerceIn(0f, 1f)
                }

                val aCircleR = avatar.width / 2f + 9f * dpPx
                val circleTop = Offset(avatar.center.x, avatar.center.y - aCircleR)
                val pTrace = easeInOut(seg(t, 0.06f, 0.22f))

                // 发送键位置与涟漪（D 在 0.80 点下）
                val sendC = input?.let { Offset(it.right - 40f * dpPx, it.bottom - 27f * dpPx) }
                val rp = seg(t, 0.80f, 0.92f)
                if (sendC != null && rp in 0.01f..0.99f) {
                    drawCircle(
                        color = colors.primary.copy(alpha = (1f - rp) * 0.75f * globalFade),
                        radius = (6f + rp * 26f) * dpPx,
                        center = sendC,
                        style = Stroke(width = 2f * dpPx)
                    )
                }

                // —— 光标 A（金）：描左半圈 → 扫副标题一 → 离场 ——
                run {
                    val color = Color(0xFFF5B83D)
                    var pos: Offset? = null
                    var alpha = 0f
                    val s1Start = s1?.let { Offset(it.left - 6f * dpPx, it.center.y + 11f * dpPx) }
                    val s1End = s1?.let { Offset(it.right + 6f * dpPx, it.center.y + 11f * dpPx) }
                    when {
                        t < 0.06f -> {
                            val from = Offset(avatar.center.x - 150f * dpPx, avatar.center.y - 130f * dpPx)
                            pos = travel(t, 0f, 0.06f, from, circleTop)
                            alpha = actorAlpha(t, 0f, 0.05f, 2f, 2.1f)
                        }
                        t < 0.22f -> {
                            val ang = Math.toRadians((-90f - 180f * pTrace).toDouble())
                            pos = Offset(
                                avatar.center.x + cos(ang).toFloat() * aCircleR,
                                avatar.center.y + sin(ang).toFloat() * aCircleR
                            )
                            alpha = 1f
                        }
                        t < 0.36f -> {
                            if (s1Start != null) {
                                val bottom = Offset(avatar.center.x, avatar.center.y + aCircleR)
                                pos = travel(t, 0.22f, 0.36f, bottom, s1Start)
                                alpha = 1f
                            }
                        }
                        t < 0.48f -> {
                            if (s1Start != null && s1End != null) {
                                pos = lerpO(s1Start, s1End, easeInOut(seg(t, 0.36f, 0.48f)))
                                alpha = 1f
                            }
                        }
                        t < 0.60f -> {
                            if (s1End != null && s1 != null) {
                                val exitTo = Offset(s1.center.x - 70f * dpPx, s1.top - 120f * dpPx)
                                pos = travel(t, 0.48f, 0.60f, s1End, exitTo)
                                alpha = actorAlpha(t, 0f, 0.01f, 0.48f, 0.60f)
                            }
                        }
                    }
                    val pNow = pos
                    if (pNow != null) drawCursorArrow(pNow, color, alpha * globalFade, dpPx)
                }

                // —— 光标 B（蓝）：描右半圈 → 扫副标题二 → 离场 ——
                run {
                    val color = Color(0xFF4D6BFE)
                    var pos: Offset? = null
                    var alpha = 0f
                    val s2Start = s2?.let { Offset(it.left - 6f * dpPx, it.center.y + 11f * dpPx) }
                    val s2End = s2?.let { Offset(it.right + 6f * dpPx, it.center.y + 11f * dpPx) }
                    when {
                        t < 0.06f -> {
                            val from = Offset(avatar.center.x + 150f * dpPx, avatar.center.y - 130f * dpPx)
                            val to = Offset(circleTop.x + 5f * dpPx, circleTop.y)
                            pos = travel(t, 0f, 0.06f, from, to)
                            alpha = actorAlpha(t, 0f, 0.05f, 2f, 2.1f)
                        }
                        t < 0.22f -> {
                            val ang = Math.toRadians((-90f + 180f * pTrace).toDouble())
                            pos = Offset(
                                avatar.center.x + cos(ang).toFloat() * aCircleR,
                                avatar.center.y + sin(ang).toFloat() * aCircleR
                            )
                            alpha = 1f
                        }
                        t < 0.46f -> {
                            if (s2Start != null) {
                                val bottom = Offset(avatar.center.x, avatar.center.y + aCircleR)
                                pos = travel(t, 0.22f, 0.46f, bottom, s2Start)
                                alpha = 1f
                            }
                        }
                        t < 0.58f -> {
                            if (s2Start != null && s2End != null) {
                                pos = lerpO(s2Start, s2End, easeInOut(seg(t, 0.46f, 0.58f)))
                                alpha = 1f
                            }
                        }
                        t < 0.70f -> {
                            if (s2End != null && s2 != null) {
                                val exitTo = Offset(s2.center.x + 70f * dpPx, s2.top - 120f * dpPx)
                                pos = travel(t, 0.58f, 0.70f, s2End, exitTo)
                                alpha = actorAlpha(t, 0f, 0.01f, 0.58f, 0.70f)
                            }
                        }
                    }
                    val pNow = pos
                    if (pNow != null) drawCursorArrow(pNow, color, alpha * globalFade, dpPx)
                }

                // —— 光标 C（粉）：写问候语 → 勾输入框边框 → 离场 ——
                run {
                    val color = Color(0xFFF65A8C)
                    var pos: Offset? = null
                    var alpha = 0f
                    val gStart = greet?.let { Offset(it.left - 4f * dpPx, it.center.y + 11f * dpPx) }
                    val gEnd = greet?.let { Offset(it.right + 4f * dpPx, it.center.y + 11f * dpPx) }
                    val outlineRect = input?.let {
                        Rect(it.left - 2f * dpPx, it.top - 2f * dpPx, it.right + 2f * dpPx, it.bottom + 2f * dpPx)
                    }
                    val outlineStart = outlineRect?.let { roundedRectPoint(it, 24f * dpPx, 0f) }
                    when {
                        t < 0.10f -> {
                            alpha = 0f
                        }
                        t < 0.20f -> {
                            if (gStart != null && greet != null) {
                                val from = Offset(greet.center.x - 40f * dpPx, greet.top - 110f * dpPx)
                                pos = travel(t, 0.10f, 0.20f, from, gStart)
                                alpha = actorAlpha(t, 0.10f, 0.17f, 2f, 2.1f)
                            }
                        }
                        t < 0.38f -> {
                            if (gStart != null && gEnd != null) {
                                pos = lerpO(gStart, gEnd, easeInOut(seg(t, 0.20f, 0.38f)))
                                alpha = 1f
                            }
                        }
                        t < 0.56f -> {
                            if (gEnd != null && outlineStart != null) {
                                pos = travel(t, 0.38f, 0.56f, gEnd, outlineStart)
                                alpha = 1f
                            }
                        }
                        t < 0.76f -> {
                            if (outlineRect != null) {
                                pos = roundedRectPoint(outlineRect, 24f * dpPx, easeInOut(seg(t, 0.56f, 0.76f)))
                                alpha = 1f
                            }
                        }
                        t < 0.88f -> {
                            if (outlineRect != null && input != null) {
                                val from = roundedRectPoint(outlineRect, 24f * dpPx, 1f)
                                val exitTo = Offset(input.center.x - 30f * dpPx, input.bottom + 80f * dpPx)
                                pos = travel(t, 0.76f, 0.88f, from, exitTo)
                                alpha = actorAlpha(t, 0f, 0.01f, 0.76f, 0.88f)
                            }
                        }
                    }
                    val pNow = pos
                    if (pNow != null) drawCursorArrow(pNow, color, alpha * globalFade, dpPx)
                }

                // —— 光标 D（青）：最后进场 → 点发送 ——
                run {
                    val color = Color(0xFF14B8A6)
                    var pos: Offset? = null
                    var alpha = 0f
                    if (sendC != null && input != null) {
                        val hover = Offset(sendC.x + 24f * dpPx, sendC.y + 16f * dpPx)
                        when {
                            t < 0.60f -> {
                                alpha = 0f
                            }
                            t < 0.70f -> {
                                val from = Offset(input.right + 80f * dpPx, input.bottom + 60f * dpPx)
                                pos = travel(t, 0.60f, 0.70f, from, hover)
                                alpha = actorAlpha(t, 0.60f, 0.67f, 2f, 2.1f)
                            }
                            t < 0.79f -> {
                                pos = travel(t, 0.70f, 0.79f, hover, sendC)
                                alpha = 1f
                            }
                            t < 0.85f -> {
                                val dip = if (t < 0.815f) {
                                    seg(t, 0.79f, 0.815f) * 3f * dpPx
                                } else {
                                    (1f - seg(t, 0.815f, 0.85f)) * 3f * dpPx
                                }
                                pos = Offset(sendC.x, sendC.y + dip)
                                alpha = 1f
                            }
                            t < 0.88f -> {
                                pos = sendC
                                alpha = 1f
                            }
                            else -> {
                                pos = sendC
                                alpha = actorAlpha(t, 0f, 0.01f, 0.88f, 1f)
                            }
                        }
                    }
                    val pNow = pos
                    if (pNow != null) drawCursorArrow(pNow, color, alpha * globalFade, dpPx)
                }
            } catch (e: Exception) {
                animFailed = true
            }
        }
    }
}
