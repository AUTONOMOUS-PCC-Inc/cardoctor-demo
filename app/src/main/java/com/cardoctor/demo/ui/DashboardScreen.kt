package com.cardoctor.demo.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardoctor.demo.Override
import com.cardoctor.demo.UiState
import com.cardoctor.demo.engine.CarPart
import com.cardoctor.demo.engine.Diagnosis
import com.cardoctor.demo.engine.Environment
import com.cardoctor.demo.engine.Grades

@Composable
fun DashboardScreen(
    state: UiState,
    onCycleNight: () -> Unit,
    onCycleWeather: () -> Unit,
    onToggleHighway: () -> Unit,
    onToggleLongDistance: () -> Unit
) {
    val grade = state.report.overallGrade
    val waiting = state.snapshot.telltales == null
    // The room light follows the overall state and fades between colours.
    val ambient by animateColorAsState(
        Palette.ambient(grade, waiting), tween(900), label = "ambient"
    )

    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(
                    Brush.verticalGradient(
                        listOf(Color(0xFF0A0D12), Palette.Ink, Color(0xFF030405))
                    )
                )
                // Ambient light pooling under the car and a fainter wash from the top right.
                drawRect(
                    Brush.radialGradient(
                        listOf(ambient.copy(alpha = 0.30f), Color.Transparent),
                        center = Offset(size.width * 0.22f, size.height * 1.08f),
                        radius = size.width * 0.62f
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(ambient.copy(alpha = 0.10f), Color.Transparent),
                        center = Offset(size.width * 1.02f, -size.height * 0.1f),
                        radius = size.width * 0.5f
                    )
                )
                // Light strip along the bottom edge, like a cabin ambient line.
                drawRect(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, ambient, ambient, Color.Transparent)
                    ),
                    topLeft = Offset(0f, size.height - 4.dp.toPx()),
                    size = Size(size.width, 4.dp.toPx())
                )
            }
            .systemBarsPadding()
    ) {
        Column(
            Modifier.fillMaxSize().padding(start = 60.dp, end = 60.dp, top = 36.dp, bottom = 30.dp)
        ) {
            TopBar(state, onCycleNight, onCycleWeather, onToggleHighway, onToggleLongDistance)
            Spacer(Modifier.height(28.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Hairline))
            Spacer(Modifier.height(28.dp))

            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(44.dp)) {
                VehiclePanel(state, ambient, Modifier.weight(0.72f).fillMaxHeight())
                Column(
                    Modifier.weight(1.28f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    OverallCard(grade, waiting, ambient)
                    FaultList(state, Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(20.dp))
            Footer(state)
        }
    }
}

@Composable
private fun TopBar(
    state: UiState,
    onCycleNight: () -> Unit,
    onCycleWeather: () -> Unit,
    onToggleHighway: () -> Unit,
    onToggleLongDistance: () -> Unit
) {
    val env = state.environment
    val controls = state.controls
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text(
                "CARDOCTOR",
                style = Type.display(40).copy(letterSpacing = 14.sp, fontWeight = FontWeight.Light)
            )
            Spacer(Modifier.height(6.dp))
            Text("지금 정비소에 가야 할까요", style = Type.body(22, Palette.TextSecondary))
        }
        Spacer(Modifier.weight(1f))
        Text("DRIVING CONTEXT", style = Type.Eyebrow.copy(fontSize = 16.sp))
        Spacer(Modifier.width(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            EnvChip(
                text = if (env.night) "밤" else "낮",
                detail = controls.night.suffix,
                active = env.night,
                forced = controls.night != Override.AUTO,
                onClick = onCycleNight
            )
            EnvChip(
                text = if (env.badWeather) "비·눈" else "맑음",
                detail = if (controls.weather == Override.AUTO) "와이퍼 신호" else controls.weather.suffix,
                active = env.badWeather,
                forced = controls.weather != Override.AUTO,
                onClick = onCycleWeather
            )
            EnvChip(
                text = if (env.highway) "고속도로" else "일반도로",
                detail = "수동",
                active = env.highway,
                forced = false,
                onClick = onToggleHighway
            )
            EnvChip(
                text = if (env.longDistance) "50km 이상" else "근거리",
                detail = "남은 거리",
                active = env.longDistance,
                forced = false,
                onClick = onToggleLongDistance
            )
        }
    }
}

@Composable
private fun EnvChip(text: String, detail: String, active: Boolean, forced: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(40.dp)
    val edge by animateColorAsState(
        if (active) Palette.Accent else Color.White.copy(alpha = 0.14f), tween(300), label = "chip"
    )
    Row(
        Modifier
            .heightIn(min = 78.dp)
            .clip(shape)
            .background(
                if (active) {
                    Brush.verticalGradient(
                        listOf(Palette.Accent.copy(alpha = 0.26f), Palette.Accent.copy(alpha = 0.08f))
                    )
                } else {
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.07f), Color.White.copy(alpha = 0.02f))
                    )
                }
            )
            .border(1.5.dp, edge, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 30.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, style = Type.title(27))
        Spacer(Modifier.width(14.dp))
        if (forced) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Palette.Caution))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            detail,
            style = Type.body(18, if (forced) Palette.Caution else Palette.TextSecondary)
                .copy(letterSpacing = 1.sp)
        )
    }
}

@Composable
private fun VehiclePanel(state: UiState, ambient: Color, modifier: Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("VEHICLE STATUS", style = Type.Eyebrow)
            Spacer(Modifier.weight(1f))
            Legend("주의", Palette.Caution)
            Spacer(Modifier.width(22.dp))
            Legend("위험", Palette.Danger)
        }
        Car3DView(state.report, ambient, Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp))
        val affected = CarPart.entries.filter { state.report.gradeOf(it) > 0 }
        Text(
            if (affected.isEmpty()) "모든 부위 정상" else affected.joinToString("  ·  ") { it.label },
            style = Type.title(28).copy(
                color = if (affected.isEmpty()) Palette.TextSecondary else Palette.TextPrimary
            ),
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}

@Composable
private fun Legend(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Text(text, style = Type.body(20, Palette.TextSecondary))
    }
}

@Composable
private fun OverallCard(grade: Int, waiting: Boolean, ambient: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .glass(36.dp)
            .drawBehind {
                // A wash of the ambient colour from the dial side.
                drawRect(
                    Brush.horizontalGradient(
                        listOf(ambient.copy(alpha = 0.22f), Color.Transparent),
                        endX = size.width * 0.75f
                    )
                )
            }
            .padding(horizontal = 44.dp, vertical = 36.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GradeDial(grade, waiting, ambient)
        Spacer(Modifier.width(48.dp))
        Column {
            Text("OVERALL", style = Type.Eyebrow)
            Spacer(Modifier.height(10.dp))
            Text(
                when {
                    waiting -> "차량 신호 대기 중"
                    grade == 0 -> "정상"
                    else -> Grades.title(grade)
                },
                style = Type.display(68)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                if (waiting) "경고등 정보를 아직 읽지 못했습니다." else Grades.action(grade),
                style = Type.body(30, Palette.TextBody)
            )
        }
    }
}

/** Instrument-style dial: a 270° arc filled in proportion to the grade. */
@Composable
private fun GradeDial(grade: Int, waiting: Boolean, color: Color) {
    val fraction by animateFloatAsState(
        if (waiting) 0f else if (grade == 0) 1f else grade / 4f, tween(700), label = "dial"
    )
    val transition = rememberInfiniteTransition(label = "dialPulse")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse"
    )
    val glowAlpha = if (grade == 4) pulse else 1f

    Box(Modifier.size(210.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 7.dp.toPx()
            val inset = 18.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            drawArc(
                Color.White.copy(alpha = 0.10f), 135f, 270f, false, topLeft, arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
            if (fraction > 0f) {
                drawArc(
                    color.copy(alpha = 0.22f * glowAlpha), 135f, 270f * fraction, false, topLeft, arcSize,
                    style = Stroke(stroke * 3.4f, cap = StrokeCap.Round)
                )
                drawArc(
                    color.copy(alpha = glowAlpha), 135f, 270f * fraction, false, topLeft, arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round)
                )
            }
            // Tick marks at each grade boundary.
            for (i in 0..4) {
                val angle = Math.toRadians(135.0 + 270.0 * i / 4)
                val outer = size.width / 2 - 2.dp.toPx()
                val inner = outer - 9.dp.toPx()
                val c = Offset(size.width / 2, size.height / 2)
                drawLine(
                    Color.White.copy(alpha = 0.35f),
                    Offset(c.x + (inner * Math.cos(angle)).toFloat(), c.y + (inner * Math.sin(angle)).toFloat()),
                    Offset(c.x + (outer * Math.cos(angle)).toFloat(), c.y + (outer * Math.sin(angle)).toFloat()),
                    strokeWidth = 2.dp.toPx()
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when {
                    waiting -> "–"
                    grade == 0 -> "OK"
                    else -> "$grade"
                },
                style = Type.display(if (grade == 0 && !waiting) 60 else 96).copy(color = color)
            )
            Text(
                if (grade == 0 || waiting) "STATUS" else "GRADE",
                style = Type.Eyebrow.copy(fontSize = 14.sp, letterSpacing = 4.sp)
            )
        }
    }
}

@Composable
private fun FaultList(state: UiState, modifier: Modifier) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        items(state.report.notices) { NoticeCard(it) }
        items(state.report.diagnoses, key = { it.rule.id }) { FaultCard(it, state.environment) }
    }
}

@Composable
private fun NoticeCard(text: String) {
    Row(
        Modifier.fillMaxWidth().glass(28.dp).padding(horizontal = 36.dp, vertical = 28.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("CHECK", style = Type.Eyebrow.copy(color = Palette.Accent))
        Spacer(Modifier.width(28.dp))
        Text(text, style = Type.body(26))
    }
}

@Composable
private fun FaultCard(diagnosis: Diagnosis, environment: Environment) {
    val color = Palette.grade(diagnosis.grade)
    Row(Modifier.fillMaxWidth().glass(28.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
        // Grade-coloured edge light.
        Box(
            Modifier.width(6.dp).fillMaxHeight()
                .background(Brush.verticalGradient(listOf(color, color.copy(alpha = 0.25f))))
        )
        Column(
            Modifier.padding(horizontal = 36.dp, vertical = 30.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(diagnosis.rule.label, style = Type.title(38))
                Spacer(Modifier.width(22.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(color.copy(alpha = 0.16f))
                        .border(1.5.dp, color, RoundedCornerShape(20.dp))
                        .padding(horizontal = 18.dp, vertical = 6.dp)
                ) {
                    Text(
                        "${diagnosis.grade}등급 · ${Grades.title(diagnosis.grade)}",
                        style = Type.title(22).copy(color = color)
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(diagnosis.fault.source, style = Type.body(20, Palette.TextTertiary))
            }

            // Why this grade: the evidence is always visible (PRD design principle 3).
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("판정 근거", style = Type.body(20, Palette.TextSecondary))
                Spacer(Modifier.width(4.dp))
                ReasonChip("기본 심각도 ${diagnosis.rule.baseSeverity}", Color.White.copy(alpha = 0.22f))
                diagnosis.weights.forEach {
                    Text("+", style = Type.body(22, Palette.TextTertiary))
                    ReasonChip("${it.label} ${it.points}", Palette.Accent)
                }
                Text("=", style = Type.body(22, Palette.TextTertiary))
                ReasonChip("${diagnosis.score}점", Color.White.copy(alpha = 0.45f))
                if (diagnosis.floorApplied) {
                    ReasonChip("안전 핵심 부품 · 최소 ${diagnosis.rule.gradeFloor}등급", Palette.Danger)
                }
            }

            Text(
                buildString {
                    append(diagnosis.rule.cause)
                    append(' ')
                    append(diagnosis.rule.risk)
                    if (diagnosis.weights.isNotEmpty()) {
                        append(" 지금은 ")
                        append(diagnosis.weights.joinToString(", ") { it.label })
                        append(" 조건이라 더 급합니다.")
                    } else if (diagnosis.rule.gradeFloor == null && !environment.night && !environment.badWeather) {
                        append(" 지금 주행 환경에서는 급하지 않습니다.")
                    }
                },
                style = Type.body(25)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(28.dp).height(2.dp).background(color))
                Spacer(Modifier.width(16.dp))
                Text(Grades.action(diagnosis.grade), style = Type.title(27).copy(color = color))
            }
        }
    }
}

@Composable
private fun ReasonChip(text: String, edge: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.5.dp, edge, RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Text(text, style = Type.body(21, Palette.TextPrimary))
    }
}

@Composable
private fun Footer(state: UiState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(10.dp).clip(CircleShape)
                .background(if (state.connected && state.errors.isEmpty()) Palette.Ok else Palette.Caution)
        )
        Spacer(Modifier.width(14.dp))
        Text(
            when {
                !state.connected -> "차량 서비스에 연결하지 못했습니다"
                state.errors.isNotEmpty() ->
                    "읽지 못한 신호: " + state.errors.entries.joinToString(", ") { "${it.key}(${it.value})" }
                else -> "Pleos Vehicle SDK 연결됨 · 경고등 · 전조등 · 와이퍼 신호 실시간 수신"
            },
            style = Type.body(19, Palette.TextTertiary)
        )
        Spacer(Modifier.weight(1f))
        Text(
            "등급은 규칙으로 판정하며 안내 문구는 간략 안내입니다. 최종 판단은 정비 전문가와 상의하세요.",
            style = Type.body(19, Palette.TextTertiary)
        )
    }
}
