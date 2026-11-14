package com.cardoctor.demo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardoctor.demo.UiState
import com.cardoctor.demo.demo.DemoFault
import com.cardoctor.demo.demo.LogEntry
import com.cardoctor.demo.demo.LogKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Demo console (debug builds only). Left: switches that inject faults through the emulator's
 * Virtual Console. Right: a live monitor listing, in order, the injection request, the signal the
 * SDK then reports, what the engine decided, and the change in the overall grade.
 *
 * A switch shows what the car actually reports, not what was requested, so it only lights up
 * once the signal has made the round trip.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DemoPanel(
    state: UiState,
    onInject: (DemoFault, Boolean) -> Unit,
    onClearAll: () -> Unit,
    onToggleTire: (com.cardoctor.demo.engine.Wheel) -> Unit,
    modifier: Modifier = Modifier
) {
    val lit = state.snapshot.telltales.orEmpty().map { it.type }.toSet()
    fun isOn(fault: DemoFault): Boolean =
        if (fault.sdkType != null) fault.sdkType in lit else state.snapshot.wiperSwitchOn == true

    Row(modifier.fillMaxWidth().glass(28.dp).padding(horizontal = 28.dp, vertical = 22.dp)) {
        Column(Modifier.weight(0.9f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("FAULT INJECTION", style = Type.Eyebrow.copy(fontSize = 14.sp, letterSpacing = 4.sp))
                Spacer(Modifier.width(14.dp))
                Box(
                    Modifier.size(8.dp).clip(CircleShape)
                        .background(if (state.demo.consoleOnline) Palette.Ok else Palette.Caution)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (state.demo.consoleOnline) "가상 콘솔 연결됨" else "가상 콘솔 연결 중…",
                    style = Type.body(15, Palette.TextSecondary)
                )
            }
            Spacer(Modifier.height(14.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DemoFault.entries.forEach { fault ->
                    val on = isOn(fault)
                    InjectSwitch(fault.label, on, if (fault.color == "Red") Palette.Danger else Palette.Caution) {
                        onInject(fault, !on)
                    }
                }
                InjectSwitch("모두 해제", false, Palette.Accent, onClearAll)
            }
            Spacer(Modifier.height(12.dp))
            // F1: the emulator has no tire pressure values, so wheels are simulated in the app.
            Text("TIRE PRESSURE · 시연값", style = Type.Eyebrow.copy(fontSize = 13.sp, letterSpacing = 3.sp))
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                com.cardoctor.demo.engine.Wheel.entries.forEach { wheel ->
                    InjectSwitch("${wheel.label} 저압", wheel in state.demo.lowTires, Palette.Caution) {
                        onToggleTire(wheel)
                    }
                }
            }
        }

        Spacer(Modifier.width(24.dp))
        Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.Hairline))
        Spacer(Modifier.width(24.dp))

        Column(Modifier.weight(1.1f).fillMaxHeight()) {
            Text("LIVE MONITOR", style = Type.Eyebrow.copy(fontSize = 14.sp, letterSpacing = 4.sp))
            Spacer(Modifier.height(12.dp))
            // Newest entry first; keep the list pinned to the top as entries arrive.
            val listState = rememberLazyListState()
            LaunchedEffect(state.demo.log.firstOrNull()?.id) { listState.scrollToItem(0) }
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(state.demo.log, key = { it.id }) { LogRow(it) }
            }
        }
    }
}

@Composable
private fun InjectSwitch(label: String, on: Boolean, color: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Row(
        Modifier
            .heightIn(min = 46.dp)
            .clip(shape)
            .background(if (on) color.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.05f))
            .border(1.5.dp, if (on) color else Color.White.copy(alpha = 0.16f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(10.dp).clip(CircleShape)
                .background(if (on) color else Color.White.copy(alpha = 0.25f))
        )
        Spacer(Modifier.width(10.dp))
        Text(label, style = Type.title(18))
    }
}

private val clock = SimpleDateFormat("HH:mm:ss.S", Locale.US)

@Composable
private fun LogRow(entry: LogEntry) {
    val color = when (entry.kind) {
        LogKind.INJECT -> Palette.Accent
        LogKind.SIGNAL -> Palette.Ok
        LogKind.JUDGE -> Palette.Caution
        LogKind.ENVIRONMENT -> Palette.Stale
        LogKind.OVERALL -> Palette.TextPrimary
    }
    Row(verticalAlignment = Alignment.Top) {
        Text(clock.format(Date(entry.timeMillis)), style = Type.body(14, Palette.TextTertiary), modifier = Modifier.width(92.dp))
        Text(entry.kind.tag, style = Type.title(14).copy(color = color), modifier = Modifier.width(40.dp))
        Text(entry.text, style = Type.body(15, Palette.TextBody).copy(lineHeight = 20.sp))
    }
}
