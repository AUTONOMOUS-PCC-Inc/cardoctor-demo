package com.cardoctor.demo.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardoctor.demo.UiState
import com.cardoctor.demo.engine.Diagnosis
import com.cardoctor.demo.engine.Grades
import com.cardoctor.demo.engine.ServiceReport
import com.cardoctor.demo.engine.Wheel
import com.cardoctor.demo.services.GeminiExplainer
import com.cardoctor.demo.services.Place
import com.cardoctor.demo.services.PlaceKind
import com.cardoctor.demo.services.TmapPlaces
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

// ---------------------------------------------------------------------------------------------
// F1  제동 · 타이어
// ---------------------------------------------------------------------------------------------

/** Brake state and the four tires, under the car. Values the car does not give are said plainly. */
@Composable
fun ChassisCard(state: UiState, modifier: Modifier = Modifier) {
    val lit = state.snapshot.telltales.orEmpty().map { it.type }.toSet()
    val brakeDiagnosis = state.report.diagnoses.firstOrNull { it.rule.id in setOf("brake_system", "abs", "esc") }
    val parkingBrake = lit.any { it == "PARKING_BRAKE" || it == "ELECTRONIC_PARKING_BRAKE" }
    val (brakeText, brakeColor) = when {
        brakeDiagnosis != null -> brakeDiagnosis.rule.label to Palette.grade(brakeDiagnosis.grade)
        parkingBrake -> "주차 브레이크 체결" to Palette.Accent
        state.snapshot.telltales == null -> "신호 대기" to Palette.TextTertiary
        else -> "정상" to Palette.Ok
    }

    Column(modifier.fillMaxWidth().glass(24.dp).padding(horizontal = 26.dp, vertical = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("BRAKE · TIRES", style = Type.Eyebrow.copy(fontSize = 14.sp, letterSpacing = 4.sp))
            Spacer(Modifier.weight(1f))
            val simulated = state.snapshot.tirePressure.values.any { it.simulated }
            Text(
                when {
                    simulated -> "공기압: 시연값"
                    state.snapshot.tirePressure.isNotEmpty() -> "공기압: 차량 센서"
                    state.assist.tireSensor == false -> "공기압 센서값 없음 · TPMS 경고등으로 판정"
                    else -> "공기압 확인 중"
                },
                style = Type.body(15, if (simulated) Palette.Caution else Palette.TextTertiary)
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("브레이크", style = Type.body(20, Palette.TextSecondary), modifier = Modifier.width(96.dp))
            Box(Modifier.size(12.dp).clip(CircleShape).background(brakeColor))
            Spacer(Modifier.width(12.dp))
            Text(brakeText, style = Type.title(24).copy(color = if (brakeColor == Palette.Ok) Palette.TextPrimary else brakeColor))
        }
        Spacer(Modifier.height(14.dp))
        val tpmsLit = "TPMS" in lit
        val rows = listOf(listOf(Wheel.LEFT_FRONT, Wheel.RIGHT_FRONT), listOf(Wheel.LEFT_REAR, Wheel.RIGHT_REAR))
        rows.forEachIndexed { i, pair ->
            if (i > 0) Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { wheel -> TireTile(state, wheel, tpmsLit, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun TireTile(state: UiState, wheel: Wheel, tpmsLit: Boolean, modifier: Modifier) {
    val reading = state.snapshot.tirePressure[wheel]
    val low = wheel in state.snapshot.lowTires
    val grade = state.report.diagnoses.firstOrNull { it.rule.id == "tire_pressure_${wheel.name.lowercase()}" }?.grade ?: 0
    val color = when {
        low -> Palette.grade(grade.coerceAtLeast(1))
        reading == null && tpmsLit -> Palette.Caution
        else -> Color.White.copy(alpha = 0.16f)
    }
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .clip(shape)
            .background(if (low) color.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.03f))
            .border(1.5.dp, color, shape)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(wheel.label, style = Type.body(18, Palette.TextSecondary))
        Spacer(Modifier.weight(1f))
        Text(
            when {
                reading != null -> "${reading.kpa.toInt()} kPa"
                tpmsLit -> "경고"
                else -> "—"
            },
            style = Type.title(22).copy(color = if (low || (reading == null && tpmsLit)) color else Palette.TextPrimary)
        )
    }
}

// ---------------------------------------------------------------------------------------------
// F4  가까운 정비소 · 주차장 + 안내 시작,  F5 진입
// ---------------------------------------------------------------------------------------------

@Composable
fun GuidanceCard(
    state: UiState,
    onStartGuidance: (Place) -> Unit,
    onOpenMap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val grade = state.report.overallGrade
    Column(modifier.fillMaxWidth().glass(28.dp).padding(horizontal = 32.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("NEARBY", style = Type.Eyebrow.copy(fontSize = 15.sp))
            Spacer(Modifier.width(16.dp))
            val pos = state.assist.position
            Text(
                when {
                    !TmapPlaces.enabled -> "Tmap 키 없음 · 장소 검색 꺼짐"
                    state.assist.placesSearching -> "Tmap에서 찾는 중…"
                    pos == null -> "위치 확인 중"
                    !pos.fixed -> "현재 위치를 못 받아 기본 위치 기준"
                    else -> "현재 위치 기준 · Tmap"
                },
                style = Type.body(16, Palette.TextTertiary)
            )
            Spacer(Modifier.weight(1f))
            if (state.assist.places.values.any { it.isNotEmpty() }) {
                ActionPill(
                    if (state.environment.driving) "지도 · 정차 후" else "지도에서 보기",
                    if (state.environment.driving) Palette.TextTertiary else Palette.Accent,
                    onOpenMap
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PlaceTile(
                state.assist.places[PlaceKind.REPAIR]?.firstOrNull(), PlaceKind.REPAIR,
                emphasized = grade >= 3, onStartGuidance, Modifier.weight(1f)
            )
            PlaceTile(
                state.assist.places[PlaceKind.PARKING]?.firstOrNull(), PlaceKind.PARKING,
                emphasized = grade == 4, onStartGuidance, Modifier.weight(1f)
            )
        }
        state.assist.naviStatus?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = Type.body(17, Palette.Accent))
        }
    }
}

@Composable
private fun PlaceTile(
    place: Place?,
    kind: PlaceKind,
    emphasized: Boolean,
    onStartGuidance: (Place) -> Unit,
    modifier: Modifier
) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier
            .clip(shape)
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
            .padding(start = 22.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (kind == PlaceKind.REPAIR) "가까운 정비소" else "가까운 주차장",
                style = Type.body(16, Palette.TextSecondary)
            )
            Text(
                place?.name ?: "찾은 곳이 없습니다",
                style = Type.title(24),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            place?.let {
                val distance = if (it.distanceKm < 1.0) "${(it.distanceKm * 1000).toInt()} m" else "%.1f km".format(it.distanceKm)
                Text(distance, style = Type.body(18, Palette.TextSecondary))
            }
        }
        if (place != null) {
            Spacer(Modifier.width(12.dp))
            val color = if (emphasized) Palette.Accent else Color.White.copy(alpha = 0.35f)
            Box(
                Modifier
                    .heightIn(min = 64.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (emphasized) Palette.Accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f))
                    .border(1.5.dp, color, RoundedCornerShape(18.dp))
                    .clickable { onStartGuidance(place) }
                    .padding(horizontal = 22.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("안내 시작", style = Type.title(22).copy(color = if (emphasized) Palette.Accent else Palette.TextPrimary))
            }
        }
    }
}

@Composable
fun ActionPill(text: String, color: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier
            .clip(shape)
            .border(1.5.dp, color.copy(alpha = 0.7f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) { Text(text, style = Type.title(17).copy(color = color)) }
}

// ---------------------------------------------------------------------------------------------
// F2  자세히보기
// ---------------------------------------------------------------------------------------------

/** Full explanation of one diagnosis, over a dimmed dashboard. Tapping outside closes it. */
@Composable
fun DetailOverlay(state: UiState, diagnosis: Diagnosis, onClose: () -> Unit) {
    val exp = state.explanationOf(diagnosis)
    val color = Palette.grade(diagnosis.grade)
    Scrim(onClose) {
        Column(
            Modifier
                .fillMaxWidth(0.62f)
                .glassSolid()
                .padding(horizontal = 48.dp, vertical = 40.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(diagnosis.rule.label, style = Type.title(40))
                Spacer(Modifier.width(20.dp))
                GradeBadge(diagnosis.grade)
                Spacer(Modifier.weight(1f))
                ActionPill("닫기", Palette.TextSecondary, onClose)
            }
            Spacer(Modifier.height(10.dp))
            Text(diagnosis.fault.source, style = Type.body(19, Palette.TextTertiary))
            Spacer(Modifier.height(26.dp))
            DetailLine("원인", exp.cause)
            DetailLine("위험", exp.risk)
            DetailLine("할 일", exp.action, color)
            Spacer(Modifier.height(18.dp))
            Text("판정 근거", style = Type.body(19, Palette.TextSecondary))
            Spacer(Modifier.height(8.dp))
            Text(
                buildString {
                    append("기본 심각도 ${diagnosis.rule.baseSeverity}")
                    diagnosis.weights.forEach { append(" + ${it.label} ${it.points}") }
                    append(" = ${diagnosis.score}점 → ${diagnosis.grade}등급")
                    if (diagnosis.floorApplied) append(" (안전 핵심 부품 · 최소 ${diagnosis.rule.gradeFloor}등급)")
                },
                style = Type.title(24)
            )
            Spacer(Modifier.height(22.dp))
            Text(
                if (exp.byAi) "AI 설명 (Gemini) · 등급은 규칙 엔진이 정했습니다 · 최종 판단은 정비 전문가와 상의하세요"
                else if (GeminiExplainer.enabled) "AI 설명을 준비 중이거나 받지 못해 규칙표 문구로 표시합니다"
                else "규칙표 문구 · Gemini 키가 없어 AI 설명을 쓰지 않습니다",
                style = Type.body(16, Palette.TextTertiary)
            )
        }
    }
}

@Composable
private fun DetailLine(label: String, text: String, color: Color = Palette.TextPrimary) {
    Row(Modifier.padding(vertical = 8.dp)) {
        Text(label, style = Type.body(22, Palette.TextSecondary), modifier = Modifier.width(96.dp))
        Text(text, style = Type.body(26, color))
    }
}

@Composable
fun GradeBadge(grade: Int) {
    val color = Palette.grade(grade)
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.16f))
            .border(1.5.dp, color, RoundedCornerShape(20.dp))
            .padding(horizontal = 18.dp, vertical = 6.dp)
    ) { Text("${grade}등급 · ${Grades.title(grade)}", style = Type.title(22).copy(color = color)) }
}

// ---------------------------------------------------------------------------------------------
// F5  정비소 사전 리포트 (QR)
// ---------------------------------------------------------------------------------------------

@Composable
fun ReportOverlay(state: UiState, json: String, onExport: () -> Unit, onClose: () -> Unit) {
    Scrim(onClose) {
        Row(
            Modifier
                .fillMaxWidth(0.8f)
                .fillMaxHeight(0.82f)
                .glassSolid()
                .padding(40.dp)
        ) {
            Column(Modifier.weight(0.42f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("정비소 사전 리포트", style = Type.title(32))
                Spacer(Modifier.height(16.dp))
                QrCode(json, Modifier.weight(1f).fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Text(
                    "정비소에서 QR을 찍으면 고장 부위·등급·판정 근거를 미리 받습니다",
                    style = Type.body(16, Palette.TextSecondary)
                )
            }
            Spacer(Modifier.width(36.dp))
            Column(Modifier.weight(0.58f).fillMaxHeight()) {
                Text("담긴 내용", style = Type.Eyebrow.copy(fontSize = 15.sp))
                Spacer(Modifier.height(12.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Text(ServiceReport.summaryText(state.report), style = Type.body(22, Palette.TextPrimary))
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "주행 환경: ${if (state.environment.night) "밤" else "낮"} · ${state.weatherLabel}" +
                            (if (state.environment.highway) " · 고속도로" else "") +
                            (if (state.controls.anyForced || state.demo.lowTires.isNotEmpty()) " · 시연값 포함" else ""),
                        style = Type.body(19, Palette.TextSecondary)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "형식: JSON (schemaVersion ${ServiceReport.SCHEMA_VERSION}) · ${json.toByteArray().size} bytes",
                        style = Type.body(16, Palette.TextTertiary)
                    )
                    Text(ServiceReport.DISCLAIMER, style = Type.body(16, Palette.TextTertiary))
                    state.assist.exportedPath?.let {
                        Spacer(Modifier.height(12.dp))
                        Text("저장됨 · $it", style = Type.body(16, Palette.Ok))
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ActionPill("JSON 파일로 저장", Palette.Accent, onExport)
                    ActionPill("닫기", Palette.TextSecondary, onClose)
                }
            }
        }
    }
}

@Composable
private fun Scrim(onClose: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.clickable(enabled = false) {}) { content() }
    }
}

/** Opaque variant of the glass panel, for overlays that sit above the dashboard. */
private fun Modifier.glassSolid(): Modifier =
    this.clip(RoundedCornerShape(32.dp))
        .background(Brush.verticalGradient(listOf(Color(0xFF151B23), Color(0xFF0B0F14))))
        .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(32.dp))

/** UTF-8 QR code (Korean is fine); null when the text is too long for one code. */
fun encodeQr(text: String): BitMatrix? = runCatching {
    QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.MARGIN to 2
        )
    )
}.getOrNull()

@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) { encodeQr(text) }
    if (matrix == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("내용이 길어 QR 하나에 담지 못했습니다. JSON 파일로 저장하세요.", style = Type.body(18, Palette.Caution))
        }
        return
    }
    Canvas(modifier) {
        val side = minOf(size.width, size.height)
        val cell = side / matrix.width
        val ox = (size.width - cell * matrix.width) / 2f
        val oy = (size.height - cell * matrix.height) / 2f
        drawRect(Color.White, Offset(ox, oy), Size(cell * matrix.width, cell * matrix.height))
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(Color.Black, Offset(ox + x * cell, oy + y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
