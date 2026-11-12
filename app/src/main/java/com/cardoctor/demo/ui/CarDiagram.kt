package com.cardoctor.demo.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.cardoctor.demo.engine.CarPart
import com.cardoctor.demo.engine.DiagnosisReport

/**
 * Top view of a silver car on a showroom floor (front at the top). A healthy car stays clean;
 * a faulty part lights up in its grade colour with a soft glow: amber = grade 1-2,
 * red = grade 3, pulsing red = grade 4.
 */
@Composable
fun CarDiagram(report: DiagnosisReport, ambient: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "grade4")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse"
    )

    /** Grade colour of a part, or null when the part is healthy. */
    fun fault(part: CarPart): Color? {
        val grade = report.gradeOf(part)
        if (grade == 0) return null
        val base = Palette.grade(grade)
        return if (grade == 4) base.copy(alpha = pulse) else base
    }

    Canvas(modifier) {
        // Work in a 400 x 800 design space, scaled to fit and centred.
        val scale = minOf(size.width / 400f, size.height / 800f)
        val origin = Offset((size.width - 400f * scale) / 2f, (size.height - 800f * scale) / 2f)
        fun pt(x: Float, y: Float) = Offset(origin.x + x * scale, origin.y + y * scale)
        fun sz(w: Float, h: Float) = Size(w * scale, h * scale)
        fun round(r: Float) = CornerRadius(r * scale, r * scale)
        fun glow(x: Float, y: Float, radius: Float, color: Color, strength: Float = 0.55f) {
            val center = pt(x, y)
            drawCircle(
                Brush.radialGradient(
                    listOf(color.copy(alpha = color.alpha * strength), Color.Transparent),
                    center = center,
                    radius = radius * scale
                ),
                radius = radius * scale,
                center = center
            )
        }

        // Showroom floor: ambient pool of light and turntable rings.
        glow(200f, 400f, 430f, ambient, strength = 0.20f)
        for ((i, r) in listOf(250f, 320f, 390f).withIndex()) {
            drawOval(
                Color.White.copy(alpha = 0.07f - i * 0.02f),
                topLeft = pt(200f - r, 400f - r * 1.12f),
                size = sz(r * 2f, r * 2.24f),
                style = Stroke(1.5f * scale)
            )
        }
        // Contact shadow.
        drawRoundRect(
            Brush.radialGradient(
                listOf(Color.Black.copy(alpha = 0.5f), Color.Black.copy(alpha = 0.35f), Color.Transparent),
                center = pt(200f, 400f),
                radius = 400f * scale
            ),
            pt(72f, 22f), sz(256f, 756f), round(96f)
        )

        // Tyres.
        val tyreFault = fault(CarPart.TIRES)
        val brakeFault = fault(CarPart.BRAKES)
        for ((x, y) in listOf(62f to 150f, 300f to 150f, 62f to 556f, 300f to 556f)) {
            if (tyreFault != null) glow(x + 19f, y + 56f, 95f, tyreFault)
            drawRoundRect(tyreFault ?: Color(0xFF14181D), pt(x, y), sz(38f, 112f), round(14f))
            if (brakeFault != null) {
                glow(x + 19f, y + 56f, 80f, brakeFault)
                drawRoundRect(brakeFault, pt(x + 11f, y + 28f), sz(16f, 56f), round(7f))
            }
        }

        // Body: brushed silver, lit from above.
        val bodyBrush = Brush.horizontalGradient(
            listOf(
                Color(0xFF596270), Color(0xFFAEB6C0), Color(0xFFE3E7EC),
                Color(0xFFF4F6F8), Color(0xFFE3E7EC), Color(0xFFAEB6C0), Color(0xFF596270)
            ),
            startX = pt(90f, 0f).x,
            endX = pt(310f, 0f).x
        )
        drawRoundRect(Color(0xFF8E97A3), pt(72f, 262f), sz(24f, 14f), round(6f)) // mirrors
        drawRoundRect(Color(0xFF8E97A3), pt(304f, 262f), sz(24f, 14f), round(6f))
        drawRoundRect(bodyBrush, pt(90f, 40f), sz(220f, 720f), round(80f))
        // Soft darkening towards nose and tail gives the body some depth.
        drawRoundRect(
            Brush.verticalGradient(
                listOf(Color.Black.copy(alpha = 0.28f), Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.34f)),
                startY = pt(0f, 40f).y,
                endY = pt(0f, 760f).y
            ),
            pt(90f, 40f), sz(220f, 720f), round(80f)
        )
        // Bonnet creases.
        val crease = Color.Black.copy(alpha = 0.16f)
        drawLine(crease, pt(140f, 70f), pt(124f, 232f), 2f * scale, StrokeCap.Round)
        drawLine(crease, pt(260f, 70f), pt(276f, 232f), 2f * scale, StrokeCap.Round)

        // Motor / cooling under the bonnet.
        fault(CarPart.MOTOR)?.let { color ->
            glow(200f, 146f, 170f, color)
            drawRoundRect(color.copy(alpha = color.alpha * 0.9f), pt(132f, 88f), sz(136f, 118f), round(24f))
        }

        // Glasshouse.
        val glass = Brush.verticalGradient(
            listOf(Color(0xFF1D2530), Color(0xFF0A0D11)),
            startY = pt(0f, 236f).y,
            endY = pt(0f, 700f).y
        )
        drawPath(quad(pt(110f, 236f), pt(290f, 236f), pt(272f, 320f), pt(128f, 320f)), glass)
        drawRoundRect(glass, pt(124f, 326f), sz(152f, 296f), round(26f)) // panoramic roof
        drawPath(quad(pt(130f, 628f), pt(270f, 628f), pt(284f, 694f), pt(116f, 694f)), glass)
        // A highlight streak across the glass.
        drawLine(Color.White.copy(alpha = 0.06f), pt(150f, 344f), pt(238f, 600f), 26f * scale, StrokeCap.Round)

        // Wipers.
        val wiperFault = fault(CarPart.WIPERS)
        if (wiperFault != null) glow(200f, 286f, 130f, wiperFault)
        val wiper = wiperFault ?: Color.White.copy(alpha = 0.22f)
        val wiperWidth = (if (wiperFault != null) 7f else 3f) * scale
        drawLine(wiper, pt(146f, 312f), pt(196f, 262f), wiperWidth, StrokeCap.Round)
        drawLine(wiper, pt(212f, 312f), pt(262f, 262f), wiperWidth, StrokeCap.Round)

        // Cabin safety, steering, battery: only drawn when something is wrong.
        fault(CarPart.CABIN)?.let { color ->
            glow(200f, 430f, 170f, color, strength = 0.4f)
            drawRoundRect(color, pt(132f, 334f), sz(136f, 150f), round(22f), style = Stroke(5f * scale))
        }
        fault(CarPart.STEERING)?.let { color ->
            glow(166f, 366f, 70f, color)
            drawCircle(color, 20f * scale, pt(166f, 366f), style = Stroke(7f * scale))
        }
        fault(CarPart.BATTERY)?.let { color ->
            glow(200f, 540f, 180f, color)
            drawRoundRect(color.copy(alpha = color.alpha * 0.9f), pt(134f, 478f), sz(132f, 128f), round(18f))
        }

        // Headlamps and tail lamp.
        val lampFault = fault(CarPart.HEADLAMPS)
        for (x in listOf(108f, 232f)) {
            if (lampFault != null) glow(x + 30f, 60f, 90f, lampFault, strength = 0.7f)
            drawRoundRect(lampFault ?: Color(0xFFF2F7FF), pt(x, 51f), sz(60f, 17f), round(9f))
        }
        drawRoundRect(Color(0xFF8E1F28), pt(116f, 744f), sz(168f, 7f), round(4f))

        // Whole-vehicle warning: an outline around the body.
        fault(CarPart.WHOLE)?.let { color ->
            drawRoundRect(color, pt(90f, 40f), sz(220f, 720f), round(80f), style = Stroke(5f * scale))
        }
    }
}

private fun DrawScope.quad(a: Offset, b: Offset, c: Offset, d: Offset) = Path().apply {
    moveTo(a.x, a.y)
    lineTo(b.x, b.y)
    lineTo(c.x, c.y)
    lineTo(d.x, d.y)
    close()
}
