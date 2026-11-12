package com.cardoctor.demo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cardoctor.demo.demo.DemoConsole
import com.cardoctor.demo.demo.DemoFault
import com.cardoctor.demo.demo.LogEntry
import com.cardoctor.demo.demo.LogKind
import com.cardoctor.demo.engine.DiagnosisReport
import com.cardoctor.demo.engine.Environment
import com.cardoctor.demo.engine.Grades
import com.cardoctor.demo.engine.SeverityEngine
import com.cardoctor.demo.engine.VehicleSnapshot
import com.cardoctor.demo.vehicle.PleosSignalSource
import java.util.Calendar
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** AUTO follows the vehicle or the clock; the other two force a value for demos. */
enum class Override(val suffix: String) { AUTO("자동"), FORCE_ON("강제"), FORCE_OFF("강제") }

data class EnvironmentControls(
    val night: Override = Override.AUTO,
    val weather: Override = Override.AUTO,
    val highway: Boolean = false,
    val longDistance: Boolean = false
)

/** State of the demo console (debug builds only). */
data class DemoState(
    val available: Boolean = false,
    val open: Boolean = false,
    val consoleOnline: Boolean = false,
    val log: List<LogEntry> = emptyList()
)

data class UiState(
    val connected: Boolean = false,
    val snapshot: VehicleSnapshot = VehicleSnapshot(),
    val controls: EnvironmentControls = EnvironmentControls(),
    val environment: Environment = Environment(),
    val report: DiagnosisReport = DiagnosisReport(emptyList(), emptyList()),
    val errors: Map<String, String> = emptyMap(),
    val demo: DemoState = DemoState()
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val source = PleosSignalSource(application)
    private val controls = MutableStateFlow(EnvironmentControls())
    private val connected = MutableStateFlow(false)
    private val clockNight = MutableStateFlow(isNightNow())

    private val console = DemoConsole()
    private val demo = MutableStateFlow(DemoState(available = BuildConfig.DEBUG))
    private var nextLogId = 0L
    private var lastInjectionAt = 0L

    private val vehicleState = combine(
        source.snapshot, source.errors, controls, connected, clockNight
    ) { snapshot, errors, controls, connected, clockNight ->
        val environment = Environment(
            night = resolve(controls.night, clockNight),
            // A running wiper is taken as rain (PRD: 와이퍼 → 강우).
            badWeather = resolve(controls.weather, snapshot.wiperSwitchOn == true),
            highway = controls.highway,
            longDistance = controls.longDistance
        )
        UiState(
            connected = connected,
            snapshot = snapshot,
            controls = controls,
            environment = environment,
            report = SeverityEngine.diagnose(snapshot, environment),
            errors = errors
        )
    }

    val uiState: StateFlow<UiState> = combine(vehicleState, demo) { state, demo -> state.copy(demo = demo) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    init {
        viewModelScope.launch {
            connected.value = source.initialize()
            delay(1_500) // the SDK binds to Vehicle Service asynchronously
            while (isActive) {
                if (!connected.value) connected.value = source.initialize()
                source.refresh()
                clockNight.value = isNightNow()
                delay(POLL_MILLIS)
            }
        }
        // Live monitor: every change in what the car reports or what the engine decides is logged.
        viewModelScope.launch {
            var previous: UiState? = null
            vehicleState.collect { current ->
                if (current.snapshot.telltales == null) return@collect
                previous?.let { logChanges(it, current) } ?: log(LogKind.SIGNAL, "모니터링 시작 · 차량 신호 수신 중")
                previous = current
            }
        }
        // The Virtual Console accepts writes only while a session heartbeat is alive.
        viewModelScope.launch {
            while (isActive) {
                if (demo.value.open) {
                    val online = console.heartbeat()
                    demo.update { it.copy(consoleOnline = online) }
                }
                delay(1_000)
            }
        }
    }

    fun cycleNight() = controls.update { it.copy(night = it.night.next()) }
    fun cycleWeather() = controls.update { it.copy(weather = it.weather.next()) }
    fun toggleHighway() = controls.update { it.copy(highway = !it.highway) }
    fun toggleLongDistance() = controls.update { it.copy(longDistance = !it.longDistance) }

    fun toggleDemo() = demo.update { it.copy(open = !it.open) }

    fun inject(fault: DemoFault, on: Boolean) {
        viewModelScope.launch {
            lastInjectionAt = System.currentTimeMillis()
            val status = console.set(fault, on)
            val action = if (on) "켜기" else "끄기"
            log(
                LogKind.INJECT,
                when (status) {
                    200 -> "${fault.label} $action 요청 → 가상 콘솔 수락"
                    -1 -> "${fault.label} $action 실패 · 가상 콘솔에 연결할 수 없음"
                    503 -> "${fault.label} $action 실패 · 콘솔 세션 준비 중, 잠시 후 다시 시도"
                    else -> "${fault.label} $action 실패 · HTTP $status"
                }
            )
        }
    }

    fun clearAllFaults() {
        viewModelScope.launch {
            // Logged and timed before the requests go out, so the signals that follow line up.
            lastInjectionAt = System.currentTimeMillis()
            log(LogKind.INJECT, "모든 고장 해제 요청")
            DemoFault.entries.forEach { console.set(it, false) }
        }
    }

    override fun onCleared() {
        source.release()
    }

    private fun logChanges(before: UiState, after: UiState) {
        // 1. Raw signals read through the SDK.
        val litBefore = before.snapshot.telltales.orEmpty().associateBy { it.type }
        val litAfter = after.snapshot.telltales.orEmpty().associateBy { it.type }
        (litAfter.keys - litBefore.keys).forEach { type ->
            val signal = litAfter.getValue(type)
            val blink = if (signal.blinking) ", 점멸" else ""
            log(LogKind.SIGNAL, "경고등 점등: $type (${signal.color.label}$blink)${latency()}")
        }
        (litBefore.keys - litAfter.keys).forEach { log(LogKind.SIGNAL, "경고등 소등: $it${latency()}") }
        if (before.snapshot.wiperSwitchOn != after.snapshot.wiperSwitchOn && after.snapshot.wiperSwitchOn != null) {
            log(LogKind.SIGNAL, (if (after.snapshot.wiperSwitchOn) "와이퍼 작동" else "와이퍼 정지") + latency())
        }

        // 2. Driving context.
        val envBefore = before.environment
        val envAfter = after.environment
        if (envBefore.night != envAfter.night) log(LogKind.ENVIRONMENT, if (envAfter.night) "밤으로 변경" else "낮으로 변경")
        if (envBefore.badWeather != envAfter.badWeather) log(LogKind.ENVIRONMENT, if (envAfter.badWeather) "비·눈으로 변경" else "맑음으로 변경")
        if (envBefore.highway != envAfter.highway) log(LogKind.ENVIRONMENT, if (envAfter.highway) "고속도로로 변경" else "일반도로로 변경")
        if (envBefore.longDistance != envAfter.longDistance) {
            log(LogKind.ENVIRONMENT, if (envAfter.longDistance) "남은 거리 50km 이상으로 변경" else "남은 거리 근거리로 변경")
        }

        // 3. What the engine decided.
        val judgedBefore = before.report.diagnoses.associateBy { it.rule.id }
        val judgedAfter = after.report.diagnoses.associateBy { it.rule.id }
        judgedAfter.forEach { (id, diagnosis) ->
            val old = judgedBefore[id]
            val weights = diagnosis.weights.joinToString("") { " + ${it.label} ${it.points}" }
            val floor = if (diagnosis.floorApplied) ", 최소 ${diagnosis.rule.gradeFloor}등급 적용" else ""
            val basis = "기본 ${diagnosis.rule.baseSeverity}$weights = ${diagnosis.score}점$floor"
            when {
                old == null -> log(LogKind.JUDGE, "${diagnosis.rule.label} → ${diagnosis.grade}등급 ($basis)")
                old.grade != diagnosis.grade ->
                    log(LogKind.JUDGE, "${diagnosis.rule.label} ${old.grade} → ${diagnosis.grade}등급 ($basis)")
            }
        }
        (judgedBefore.keys - judgedAfter.keys).forEach { log(LogKind.JUDGE, "${judgedBefore.getValue(it).rule.label} 해제") }
        (after.report.notices - before.report.notices.toSet()).forEach { log(LogKind.JUDGE, "안내: 주차 브레이크 확인 (고장으로 보지 않음)") }

        // 4. The overall answer shown to the driver.
        val gradeBefore = before.report.overallGrade
        val gradeAfter = after.report.overallGrade
        if (gradeBefore != gradeAfter) {
            log(LogKind.OVERALL, "${name(gradeBefore)} → ${name(gradeAfter)}")
        }
    }

    private fun name(grade: Int) = if (grade == 0) "정상" else "${grade}등급 ${Grades.title(grade)}"

    /** Time since the last injection, when the change plausibly came from it. */
    private fun latency(): String {
        val elapsed = System.currentTimeMillis() - lastInjectionAt
        return if (elapsed in 0..5_000) " · 주입 후 ${"%.1f".format(elapsed / 1000.0)}초" else ""
    }

    private fun log(kind: LogKind, text: String) {
        val entry = LogEntry(nextLogId++, System.currentTimeMillis(), kind, text)
        demo.update { it.copy(log = (listOf(entry) + it.log).take(MAX_LOG)) }
    }

    private fun Override.next(): Override = when (this) {
        Override.AUTO -> Override.FORCE_ON
        Override.FORCE_ON -> Override.FORCE_OFF
        Override.FORCE_OFF -> Override.AUTO
    }

    private fun resolve(override: Override, auto: Boolean): Boolean = when (override) {
        Override.AUTO -> auto
        Override.FORCE_ON -> true
        Override.FORCE_OFF -> false
    }

    /** Simplified: night is 18:00–06:00 local time. Sunrise/sunset lookup is future work. */
    private fun isNightNow(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour < 6 || hour >= 18
    }

    private companion object {
        const val POLL_MILLIS = 500L
        const val MAX_LOG = 80
    }
}
