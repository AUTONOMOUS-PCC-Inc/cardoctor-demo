package com.cardoctor.demo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardoctor.demo.R

/**
 * Visual language: a silver car in a dark showroom, lit by ambient light.
 * Piano-black ground, glass panels with hairline edges, thin type with wide tracking.
 * The ambient colour follows the overall grade, so the room itself tells the state.
 */
object Palette {
    val Ink = Color(0xFF05070A)
    val InkRaised = Color(0xFF0C1016)

    val TextPrimary = Color(0xFFF4F6F8)
    val TextBody = Color(0xFFCDD3DA)
    val TextSecondary = Color(0xFF929BA7)
    val TextTertiary = Color(0xFF5E6772)

    val Hairline = Color.White.copy(alpha = 0.10f)
    val Accent = Color(0xFF9CC9FF) // ice blue: interactive and active states

    val Ok = Color(0xFF63E0C6)
    val Caution = Color(0xFFF5B94A)
    val Danger = Color(0xFFFF4D57)
    val Stale = Color(0xFF8E7BFF)
    val Normal = Color(0xFF8892A0)

    fun grade(grade: Int): Color = when (grade) {
        4, 3 -> Danger
        2, 1 -> Caution
        else -> Normal
    }

    /** Colour of the room light for the overall state. */
    fun ambient(grade: Int, waiting: Boolean): Color = when {
        waiting -> Stale
        grade == 0 -> Ok
        else -> grade(grade)
    }
}

object Type {
    val Family = FontFamily(
        Font(R.font.pretendard_light, FontWeight.Light),
        Font(R.font.pretendard_regular, FontWeight.Normal),
        Font(R.font.pretendard_medium, FontWeight.Medium),
        Font(R.font.pretendard_semibold, FontWeight.SemiBold)
    )

    /** Small upper-case label with wide tracking, e.g. "VEHICLE STATUS". */
    val Eyebrow = TextStyle(
        fontFamily = Family,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        letterSpacing = 5.sp,
        color = Palette.TextSecondary
    )

    fun display(size: Int) = TextStyle(
        fontFamily = Family,
        fontWeight = FontWeight.Light,
        fontSize = size.sp,
        letterSpacing = (-0.5).sp,
        color = Palette.TextPrimary
    )

    fun title(size: Int) = TextStyle(
        fontFamily = Family,
        fontWeight = FontWeight.Medium,
        fontSize = size.sp,
        color = Palette.TextPrimary
    )

    fun body(size: Int, color: Color = Palette.TextBody) = TextStyle(
        fontFamily = Family,
        fontWeight = FontWeight.Normal,
        fontSize = size.sp,
        lineHeight = (size * 1.5f).sp,
        color = color
    )
}

/** Glass panel: faint top-lit fill with a hairline edge that fades towards the bottom. */
fun Modifier.glass(radius: Dp = 32.dp): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .clip(shape)
        .background(
            Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.075f), Color.White.copy(alpha = 0.02f))
            )
        )
        .border(
            1.dp,
            Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.04f))
            ),
            shape
        )
}
