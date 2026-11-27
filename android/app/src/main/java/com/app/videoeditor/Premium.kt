package com.app.videoeditor

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Design tokens: a deep midnight base with violet, cyan and pink light. */
object Pal {
    val bg0 = Color(0xFF0A0919)
    val bg1 = Color(0xFF15103A)
    val violet = Color(0xFF8B5CF6)
    val violetDeep = Color(0xFF6D4AFF)
    val cyan = Color(0xFF22D3EE)
    val pink = Color(0xFFFF4D9D)
    val amber = Color(0xFFFFB020)
    val mint = Color(0xFF34D399)
    val danger = Color(0xFFFF5C7A)
    val textHi = Color(0xFFF5F2FF)
    val textLo = Color(0xFFA9A5C9)

    val accent: Brush = Brush.linearGradient(listOf(violet, pink))
    val cool: Brush = Brush.linearGradient(listOf(violet, cyan))
    val glassBorder: Brush = Brush.linearGradient(listOf(Color(0x66FFFFFF), Color(0x10FFFFFF), Color(0x33FFFFFF)))
}

/** Slowly drifting colour light behind the whole app. Animation runs only while [animate] is true. */
@Composable
fun AuroraBackground(animate: Boolean, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val phaseState: State<Float>? = if (animate) {
        rememberInfiniteTransition(label = "aurora").animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(32000, easing = LinearEasing), RepeatMode.Restart),
            label = "phase"
        )
    } else null

    Box(
        modifier.fillMaxSize().drawBehind {
            val p = phaseState?.value ?: 0.25f      // read while drawing: no recomposition per frame
            drawRect(Brush.verticalGradient(listOf(Pal.bg0, Pal.bg1, Pal.bg0)))
            val w = size.width
            val h = size.height
            val a = (p * 2f * PI).toFloat()
            fun blob(color: Color, cx: Float, cy: Float, r: Float, alpha: Float) {
                drawCircle(
                    brush = Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent), center = Offset(cx, cy), radius = r),
                    radius = r, center = Offset(cx, cy)
                )
            }
            blob(Pal.violet, w * (0.18f + 0.15f * cos(a)), h * (0.16f + 0.07f * sin(a)), w * 0.85f, 0.55f)
            blob(Pal.cyan, w * (0.88f + 0.10f * sin(a * 1.3f)), h * (0.48f + 0.10f * cos(a * 0.9f)), w * 0.65f, 0.28f)
            blob(Pal.pink, w * (0.30f + 0.20f * sin(a * 0.7f)), h * (0.90f + 0.05f * cos(a)), w * 0.75f, 0.34f)
        },
        content = content
    )
}

/** Frosted glass panel: translucent fill, bright top edge, hairline gradient border. */
@Composable
fun Glass(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    strong: Boolean = false,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(if (strong) 0x30FFFFFF else 0x1CFFFFFF), Color(if (strong) 0x12FFFFFF else 0x0AFFFFFF))))
            .border(1.dp, Pal.glassBorder, shape),
        contentAlignment = contentAlignment,
        content = content
    )
}

/** Springy press feedback. Put it first in the modifier chain so everything after it scales. */
fun Modifier.pressable(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press"
    )
    this.graphicsLayer { scaleX = s; scaleY = s }
        .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
}

@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    brush: Brush = Pal.accent,
    shimmer: Boolean = false
) {
    val sweep: State<Float>? = if (shimmer && enabled) {
        rememberInfiniteTransition(label = "shine").animateFloat(
            initialValue = -0.3f, targetValue = 1.3f,
            animationSpec = infiniteRepeatable(tween(2800, easing = LinearEasing), RepeatMode.Restart),
            label = "x"
        )
    } else null
    val pill = RoundedCornerShape(50)
    Box(
        modifier
            .pressable(enabled, onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .shadow(if (enabled) 12.dp else 0.dp, pill, ambientColor = Pal.pink, spotColor = Pal.violet)
            .clip(pill)
            .background(brush)
            .drawWithContent {
                drawContent()
                val s = sweep?.value
                if (s != null) {
                    val x = size.width * s
                    drawRect(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, Color(0x55FFFFFF), Color.Transparent),
                            startX = x - size.width * 0.25f, endX = x + size.width * 0.25f
                        )
                    )
                }
            }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
    }
}

/** Round gradient play / pause button. */
@Composable
fun PlayButton(playing: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(46.dp).pressable(enabled, onClick).alpha(if (enabled) 1f else 0.4f)
            .shadow(if (enabled) 12.dp else 0.dp, CircleShape, ambientColor = Pal.violet, spotColor = Pal.violet)
            .clip(CircleShape).background(Pal.accent),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.animation.Crossfade(targetState = playing, animationSpec = tween(160), label = "playpause") { p ->
            Ico(if (p) Ic.Pause else Ic.Play, Color.White, 22.dp)
        }
    }
}

/** Small icon button used in the transport bar. */
@Composable
fun IconBtn(ic: Ic, enabled: Boolean = true, tint: Color = Pal.textHi, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).pressable(enabled, onClick).alpha(if (enabled) 1f else 0.3f),
        contentAlignment = Alignment.Center
    ) { Ico(ic, tint, 20.dp) }
}

@Composable
fun LogoMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        drawRoundRect(Pal.accent, cornerRadius = CornerRadius(s * 0.3f))
        val p = Path().apply {
            moveTo(s * 0.40f, s * 0.29f); lineTo(s * 0.73f, s * 0.50f); lineTo(s * 0.40f, s * 0.71f); close()
        }
        drawPath(p, Color.White)
    }
}

/** Illustration for the empty project: two floating gradient film frames and a play mark. */
@Composable
fun EmptyHero(modifier: Modifier = Modifier) {
    val bob = rememberInfiniteTransition(label = "hero").animateFloat(
        initialValue = -1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = androidx.compose.animation.core.FastOutSlowInEasing), RepeatMode.Reverse),
        label = "bob"
    )
    Canvas(modifier.graphicsLayer { translationY = bob.value * 6.dp.toPx() }) {
        val w = size.width
        val h = size.height
        rotate(-9f, Offset(w * 0.4f, h * 0.5f)) {
            drawRoundRect(
                Brush.linearGradient(listOf(Pal.violet, Pal.cyan)), Offset(w * 0.08f, h * 0.16f), Size(w * 0.62f, h * 0.52f),
                CornerRadius(w * 0.08f), alpha = 0.9f
            )
        }
        rotate(8f, Offset(w * 0.6f, h * 0.55f)) {
            drawRoundRect(
                Brush.linearGradient(listOf(Pal.pink, Pal.violetDeep)), Offset(w * 0.30f, h * 0.30f), Size(w * 0.62f, h * 0.52f),
                CornerRadius(w * 0.08f)
            )
            for (i in 0 until 6) {
                drawCircle(Color(0x55FFFFFF), w * 0.012f, Offset(w * (0.36f + i * 0.095f), h * 0.35f))
                drawCircle(Color(0x55FFFFFF), w * 0.012f, Offset(w * (0.36f + i * 0.095f), h * 0.77f))
            }
            val p = Path().apply {
                moveTo(w * 0.53f, h * 0.45f); lineTo(w * 0.69f, h * 0.56f); lineTo(w * 0.53f, h * 0.67f); close()
            }
            drawPath(p, Color.White)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Line icons drawn on a 24 x 24 grid
// ---------------------------------------------------------------------------------------------

enum class Ic {
    Plus, Music, Frame, Scissors, TrimL, TrimR, Speed, Volume, Sparkle, Fade, Diamond, Transition, Filters, Sliders,
    Crop, Rotate, TextT, Smile, Copy, ArrowL, ArrowR, Trash, NewDoc, Play, Pause, Undo, Redo, ZoomIn, ZoomOut
}

@Composable
fun Ico(ic: Ic, tint: Color, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 24f
        scale(scaleX = k, scaleY = k, pivot = Offset.Zero) { drawIc(ic, tint) }
    }
}

private fun DrawScope.drawIc(ic: Ic, c: Color) {
    val st = Stroke(width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun ln(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(c, Offset(x1, y1), Offset(x2, y2), strokeWidth = 1.9f, cap = StrokeCap.Round)
    fun circ(x: Float, y: Float, r: Float, fill: Boolean = false) =
        drawCircle(c, r, Offset(x, y), style = if (fill) Fill else st)
    fun path(fill: Boolean = false, block: Path.() -> Unit) =
        drawPath(Path().apply(block), c, style = if (fill) Fill else st)
    fun rr(x: Float, y: Float, w: Float, h: Float, r: Float) =
        drawRoundRect(color = c, topLeft = Offset(x, y), size = Size(w, h), cornerRadius = CornerRadius(r, r), style = st)

    when (ic) {
        Ic.Plus -> { ln(12f, 5f, 12f, 19f); ln(5f, 12f, 19f, 12f) }
        Ic.Music -> {
            path { moveTo(11f, 17f); lineTo(11f, 5f); lineTo(19f, 7f); lineTo(19f, 15f) }
            circ(8f, 17f, 3f); circ(16f, 15f, 3f)
        }
        Ic.Frame -> { rr(4f, 5f, 16f, 14f, 3f); ln(4f, 9.5f, 20f, 9.5f) }
        Ic.Scissors -> {
            circ(7f, 7f, 2.5f); circ(7f, 17f, 2.5f)
            ln(9f, 8.6f, 20f, 18f); ln(9f, 15.4f, 20f, 6f)
        }
        Ic.TrimL -> {
            ln(5f, 5f, 5f, 19f); ln(19f, 12f, 9f, 12f)
            path { moveTo(13f, 8f); lineTo(9f, 12f); lineTo(13f, 16f) }
        }
        Ic.TrimR -> {
            ln(19f, 5f, 19f, 19f); ln(5f, 12f, 15f, 12f)
            path { moveTo(11f, 8f); lineTo(15f, 12f); lineTo(11f, 16f) }
        }
        Ic.Speed -> {
            drawArc(c, 180f, 180f, false, Offset(3f, 5f), Size(18f, 18f), style = st)
            ln(12f, 14f, 17f, 8.5f); circ(12f, 14f, 1.4f, true)
        }
        Ic.Volume -> {
            path { moveTo(4f, 9f); lineTo(8f, 9f); lineTo(13f, 5f); lineTo(13f, 19f); lineTo(8f, 15f); lineTo(4f, 15f); close() }
            drawArc(c, -50f, 100f, false, Offset(9f, 8f), Size(8f, 8f), style = st)
            drawArc(c, -50f, 100f, false, Offset(7f, 5f), Size(14f, 14f), style = st)
        }
        Ic.Sparkle -> path {
            moveTo(12f, 3f); lineTo(14f, 10f); lineTo(21f, 12f); lineTo(14f, 14f)
            lineTo(12f, 21f); lineTo(10f, 14f); lineTo(3f, 12f); lineTo(10f, 10f); close()
        }
        Ic.Fade -> {
            circ(12f, 12f, 8f)
            drawArc(c, 90f, 180f, true, Offset(4f, 4f), Size(16f, 16f), style = Fill)
        }
        Ic.Diamond -> path { moveTo(12f, 3f); lineTo(21f, 12f); lineTo(12f, 21f); lineTo(3f, 12f); close() }
        Ic.Transition -> { rr(3f, 5f, 12f, 11f, 2.5f); rr(9f, 9f, 12f, 11f, 2.5f) }
        Ic.Filters -> { circ(9f, 9.5f, 5f); circ(15f, 9.5f, 5f); circ(12f, 15f, 5f) }
        Ic.Sliders -> {
            ln(4f, 7f, 20f, 7f); ln(4f, 12f, 20f, 12f); ln(4f, 17f, 20f, 17f)
            circ(8f, 7f, 2f, true); circ(16f, 12f, 2f, true); circ(10f, 17f, 2f, true)
        }
        Ic.Crop -> {
            path { moveTo(7f, 3f); lineTo(7f, 17f); lineTo(21f, 17f) }
            path { moveTo(3f, 7f); lineTo(17f, 7f); lineTo(17f, 21f) }
        }
        Ic.Rotate -> {
            drawArc(c, -40f, 290f, false, Offset(4f, 4f), Size(16f, 16f), style = st)
            path { moveTo(14f, 3.5f); lineTo(18.3f, 6.9f); lineTo(14.2f, 9.2f) }
        }
        Ic.TextT -> { ln(6f, 6f, 18f, 6f); ln(12f, 6f, 12f, 19f) }
        Ic.Smile -> {
            circ(12f, 12f, 9f); circ(9f, 10f, 1.1f, true); circ(15f, 10f, 1.1f, true)
            drawArc(c, 25f, 130f, false, Offset(8f, 10f), Size(8f, 6.5f), style = st)
        }
        Ic.Copy -> {
            rr(9f, 9f, 11f, 11f, 2.5f)
            path { moveTo(5f, 15f); lineTo(5f, 6.5f); quadraticBezierTo(5f, 5f, 6.5f, 5f); lineTo(15f, 5f) }
        }
        Ic.ArrowL -> { ln(20f, 12f, 5f, 12f); path { moveTo(10f, 6f); lineTo(4f, 12f); lineTo(10f, 18f) } }
        Ic.ArrowR -> { ln(4f, 12f, 19f, 12f); path { moveTo(14f, 6f); lineTo(20f, 12f); lineTo(14f, 18f) } }
        Ic.Trash -> {
            ln(4f, 7f, 20f, 7f)
            path { moveTo(9f, 7f); lineTo(9f, 4f); lineTo(15f, 4f); lineTo(15f, 7f) }
            path { moveTo(6f, 7f); lineTo(7f, 20f); lineTo(17f, 20f); lineTo(18f, 7f) }
            ln(10f, 11f, 10f, 16f); ln(14f, 11f, 14f, 16f)
        }
        Ic.NewDoc -> {
            path { moveTo(6f, 3f); lineTo(14f, 3f); lineTo(19f, 8f); lineTo(19f, 21f); lineTo(6f, 21f); close() }
            ln(12.5f, 11f, 12.5f, 17f); ln(9.5f, 14f, 15.5f, 14f)
        }
        Ic.Play -> path(fill = true) { moveTo(8f, 5f); lineTo(19f, 12f); lineTo(8f, 19f); close() }
        Ic.Pause -> {
            drawRoundRect(color = c, topLeft = Offset(6.5f, 5f), size = Size(4f, 14f), cornerRadius = CornerRadius(1.2f, 1.2f))
            drawRoundRect(color = c, topLeft = Offset(13.5f, 5f), size = Size(4f, 14f), cornerRadius = CornerRadius(1.2f, 1.2f))
        }
        Ic.Undo -> {
            path { moveTo(9f, 6f); lineTo(4f, 11f); lineTo(9f, 16f) }
            path { moveTo(4f, 11f); lineTo(14f, 11f); cubicTo(18.5f, 11f, 20f, 14f, 20f, 18f) }
        }
        Ic.Redo -> {
            path { moveTo(15f, 6f); lineTo(20f, 11f); lineTo(15f, 16f) }
            path { moveTo(20f, 11f); lineTo(10f, 11f); cubicTo(5.5f, 11f, 4f, 14f, 4f, 18f) }
        }
        Ic.ZoomIn -> {
            circ(11f, 11f, 6.5f); ln(16f, 16f, 21f, 21f); ln(8.5f, 11f, 13.5f, 11f); ln(11f, 8.5f, 11f, 13.5f)
        }
        Ic.ZoomOut -> { circ(11f, 11f, 6.5f); ln(16f, 16f, 21f, 21f); ln(8.5f, 11f, 13.5f, 11f) }
    }
}
