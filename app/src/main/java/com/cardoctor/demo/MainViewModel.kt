package com.cardoctor.demo

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cardoctor.demo.demo.DemoBus
import com.cardoctor.demo.demo.DemoCommand
import com.cardoctor.demo.demo.LogKind
import com.cardoctor.demo.engine.Diagnosis
import com.cardoctor.demo.engine.DiagnosisReport
import com.cardoctor.demo.engine.Environment
import com.cardoctor.demo.engine.Grades
import com.cardoctor.demo.engine.ServiceReport
import com.cardoctor.demo.engine.SeverityEngine
import com.cardoctor.demo.engine.TireReading
import com.cardoctor.demo.engine.VehicleSnapshot
import com.cardoctor.demo.engine.Wheel
import com.cardoctor.demo.services.Explanation
import com.cardoctor.demo.services.GeminiExplainer
import com.cardoctor.demo.services.LocationSource
import com.cardoctor.demo.services.NaviLauncher
import com.cardoctor.demo.services.Place
import com.cardoctor.demo.services.PlaceKind
import com.cardoctor.demo.services.Position
import com.cardoctor.demo.services.Speaker
import com.cardoctor.demo.services.VoiceInput
import com.cardoctor.demo.engine.VoiceCommand
import com.cardoctor.demo.engine.VoiceCommands
import com.cardoctor.demo.services.TmapPlaces
import com.cardoctor.demo.services.Weather
import com.cardoctor.demo.services.WeatherApi
import com.cardoctor.demo.vehicle.PleosSignalSource
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** AUTO follows the vehicle or the clock; the other two force a value for demos. */
enum class Override(val suffix: String) { AUTO("자동"), FORCE_ON("강제"), FORCE_OFF("강제") }

data class EnvironmentControls(
    val night: Override = Override.AUTO,
    val weather: Override = Override.AUTO,
    val highway: Boolean = false,
    val longDistance: Boolean = false,
    /** AUTO follows the gear (D/R = driving). Forced only for demos. */
    val driving: Override = Override.AUTO
) {
    val anyForced: Boolean
        get() = night != Override.AUTO || weather != Override.AUTO || highway || longDistance || driving != Override.AUTO
}

/** Values set from the PC demo tool that exist only inside the app (see demo/DemoCommands.kt). */
data class DemoState(
    /** F1 demo: wheels whose pressure is simulated as low. */
    val lowTires: Set<Wheel> = emptySet()
)

/** Everything that comes from outside the car: position, weather, places, AI, speech, navigation. */
data class Assist(
    val position: Position? = null,
    val weather: Weather? = null,
    val places: Map<PlaceKind, List<Place>> = emptyMap(),
    val placesSearching: Boolean = false,
    val explanations: Map<String, Explanation> = emptyMap(),
    val naviStatus: String? = null,
    val lastSpoken: String? = null,
    val tireSensor: Boolean? = null,
    /** F2: the diagnosis whose "자세히보기" is open. */
    val detailId: String? = null,
    /** F5: report sheet. */
    val reportOpen: Boolean = false,
    val exportedPath: String? = null,
    /** Short banner, e.g. why a screen is locked while driving. Cleared after a few seconds. */
    val notice: String? = null,
    val voice: com.cardoctor.demo.services.VoiceState = com.cardoctor.demo.services.VoiceState()
)

data class UiState(
    val connected: Boolean = false,
    val snapshot: VehicleSnapshot = VehicleSnapshot(),
    val controls: EnvironmentControls = EnvironmentControls(),
    val environment: Environment = Environment(),
    val report: DiagnosisReport = DiagnosisReport(emptyList(), emptyList()),
    val errors: Map<String, String> = emptyMap(),
    val demo: DemoState = DemoState(),
    val assist: Assist = Assist()
) {
    /** Where "비·눈" came from, for the chip and the report. */
    val weatherLabel: String
        get() = when {
            controls.weather == Override.FORCE_ON -> "비·눈 (강제)"
            controls.weather == Override.FORCE_OFF -> "맑음 (강제)"
            snapshot.wiperSwitchOn == true -> "와이퍼 작동 (강우)"
            assist.weather != null -> assist.weather.label
            else -> "맑음"
        }

    fun explanationOf(d: Diagnosis): Explanation = assist.explanations[key(d)] ?: GeminiExplainer.template(d)

    companion object {
        /** A new explanation is needed when the grade or its reasons change, not on every refresh. */
        fun key(d: Diagnosis) = "${d.rule.id}|${d.grade}|${d.weights.joinToString { it.label }}"
    }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val source = PleosSignalSource(application)
    private val controls = MutableStateFlow(EnvironmentControls())
    private val connected = MutableStateFlow(false)
    private val clockNight = MutableStateFlow(isNightNow())

    private val demo = MutableStateFlow(DemoState())
    private val assist = MutableStateFlow(Assist())
    private var lastInjectionAt = 0L

    private val speaker = Speaker(application)
    private val navi = NaviLauncher(application)
    private val location = LocationSource(application)
    private val voice = VoiceInput(application) { text -> answer(text) }

    private val vehicleState = combine(
        combine(source.snapshot, demo) { snapshot, demo -> withDemoTires(snapshot, demo.lowTires) },
        source.errors, controls, connected, combine(clockNight, assist) { night, a -> night to a.weather }
    ) { snapshot, errors, controls, connected, (clockNight, weather) ->
        val environment = Environment(
            night = resolve(controls.night, clockNight),
            // F7: rain or snow from the weather API, or a running wiper (PRD: 와이퍼 → 강우).
            badWeather = resolve(controls.weather, snapshot.wiperSwitchOn == true || weather?.wet == true),
            highway = controls.highway,
            longDistance = controls.longDistance,
            driving = resolve(controls.driving, snapshot.inMotionGear)
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

    val uiState: StateFlow<UiState> = combine(vehicleState, demo, assist) { state, demo, assist ->
        state.copy(demo = demo, assist = assist)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    private val spoken = mutableSetOf<String>()
    private val explaining = mutableSetOf<String>()

    init {
        GeminiExplainer.init(application)
        speaker.start()
        navi.start()
        location.start()
        voice.start()
        viewModelScope.launch { voice.state.collect { v -> assist.update { it.copy(voice = v) } } }

        viewModelScope.launch {
            connected.value = source.initialize()
            delay(1_500) // the SDK binds to Vehicle Service asynchronously
            while (isActive) {
                if (!connected.value) connected.value = source.initialize()
                source.refresh()
                clockNight.value = isNightNow()
                assist.update { it.copy(tireSensor = source.tirePressureSupported) }
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
                onReport(current)
            }
        }
        // Demo control from the PC tool (adb broadcast → DemoCommandReceiver).
        viewModelScope.launch { DemoBus.commands.collect(::onDemoCommand) }
        // F4/F7: position → weather and nearby places. Re-search after moving about 1 km.
        viewModelScope.launch {
            var searchedAt: Position? = null
            var weatherAt = 0L
            // Use the default position if no fix arrives, so the card is never empty for long.
            delay(4_000)
            while (isActive) {
                val pos = location.position.value
                assist.update { it.copy(position = pos) }
                if (System.currentTimeMillis() - weatherAt > WEATHER_MILLIS || searchedAt?.fixed != pos.fixed) {
                    WeatherApi.fetch(pos.lat, pos.lng)?.let { w ->
                        if (assist.value.weather?.label != w.label) log(LogKind.ENVIRONMENT, "날씨 API: ${w.label}")
                        assist.update { it.copy(weather = w) }
                        weatherAt = System.currentTimeMillis()
                    }
                }
                val last = searchedAt
                if (last == null || last.fixed != pos.fixed || distanceKm(last, pos) > 1.0) {
                    searchPlaces(pos)
                    searchedAt = pos
                }
                delay(10_000)
            }
        }
        viewModelScope.launch { navi.status.collect { s -> assist.update { it.copy(naviStatus = s) } } }
    }

    // ---- Environment chips -------------------------------------------------------------------

    fun cycleDriving() = controls.update { it.copy(driving = it.driving.next()) }

    /** Before filming (PC tool): day, clear weather — so live weather and the clock cannot change grades. */
    private fun lockDemoEnvironment() {
        controls.update { it.copy(night = Override.FORCE_OFF, weather = Override.FORCE_OFF, highway = false, longDistance = false) }
        log(LogKind.ENVIRONMENT, "시연 환경 고정: 낮 · 맑음 (날씨 API 무시)")
    }

    // ---- Voice questions ---------------------------------------------------------------------

    fun listen() = voice.listen()
    fun dismissVoice() = voice.dismiss()

    /** Spoken or typed question → answer on screen and aloud. */
    fun answer(question: String) {
        voice.cancel() // the mic must be closed before the answer is spoken
        val state = uiState.value
        val reply = when (VoiceCommands.parse(question)) {
            VoiceCommand.STATUS -> VoiceCommands.statusAnswer(state.report, state.environment)
            VoiceCommand.EXPLAIN -> state.report.diagnoses.firstOrNull()?.let { d ->
                val exp = state.explanationOf(d)
                "${d.rule.label}. ${exp.risk} ${exp.action}"
            } ?: "지금은 위험한 항목이 없습니다."
            VoiceCommand.ROUTE_TO_SHOP -> state.assist.places[PlaceKind.REPAIR]?.firstOrNull()?.let { shop ->
                startGuidance(shop)
                "가장 가까운 ${shop.name}(으)로 안내를 시작합니다."
            } ?: "근처 정비소를 찾지 못했습니다."
            VoiceCommand.REPORT -> if (state.environment.driving) {
                VoiceCommands.REPORT_WHILE_DRIVING
            } else {
                openReport(true)
                "정비소 사전 리포트를 열었습니다. QR을 정비소에 보여 주세요."
            }
            VoiceCommand.UNKNOWN -> VoiceCommands.UNKNOWN_ANSWER
        }
        voice.show(question, reply)
        speaker.speak(reply)
        log(LogKind.OVERALL, "음성 질문: \"$question\" → $reply")
    }

    fun cycleNight() = controls.update { it.copy(night = it.night.next()) }
    fun cycleWeather() = controls.update { it.copy(weather = it.weather.next()) }
    fun toggleHighway() = controls.update { it.copy(highway = !it.highway) }
    fun toggleLongDistance() = controls.update { it.copy(longDistance = !it.longDistance) }

    // ---- F2 / F4 / F5 -----------------------------------------------------------------------

    /** F2 + driver distraction: long text only while parked; while driving, point to voice instead. */
    fun openDetail(ruleId: String?) {
        if (ruleId != null && uiState.value.environment.driving) {
            showNotice(DRIVING_NOTICE)
            return
        }
        assist.update { it.copy(detailId = ruleId) }
    }

    private fun showNotice(text: String) {
        assist.update { it.copy(notice = text) }
        viewModelScope.launch {
            delay(4_000)
            assist.update { if (it.notice == text) it.copy(notice = null) else it }
        }
    }

    /** Only on the driver's request: the "안내 시작" button or "정비소 안내해줘" (PRD F4: no automatic guidance). */
    fun startGuidance(place: Place) {
        navi.routeTo(place)
        log(LogKind.ENVIRONMENT, "안내 시작 요청: ${place.kind.label} ${place.name}")
    }

    fun openReport(open: Boolean) {
        if (open && uiState.value.environment.driving) {
            showNotice(DRIVING_NOTICE)
            return
        }
        assist.update { it.copy(reportOpen = open, exportedPath = if (open) it.exportedPath else null) }
    }

    fun reportJson(state: UiState): String = ServiceReport.build(
        state.report, state.snapshot,
        ServiceReport.Context(
            generatedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date()),
            environment = state.environment,
            environmentSimulated = state.controls.anyForced || state.demo.lowTires.isNotEmpty(),
            weatherLabel = state.weatherLabel
        )
    )

    /** PRD FR-2: saves the JSON next to the app's files; never throws. */
    fun exportReport() {
        val json = reportJson(uiState.value)
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) {
                runCatching {
                    val app = getApplication<Application>()
                    val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "reports").apply { mkdirs() }
                    val name = "cardoctor_report_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(Date()) + ".json"
                    File(dir, name).apply { writeText(json) }.absolutePath
                }.onFailure { Log.w(TAG, "export: ${it.message}") }.getOrNull()
            }
            assist.update { it.copy(exportedPath = path ?: "저장하지 못했습니다") }
        }
    }

    // ---- Demo control (PC tool) -------------------------------------------------------------

    private fun onDemoCommand(command: DemoCommand) {
        lastInjectionAt = System.currentTimeMillis()
        when (command) {
            is DemoCommand.Tire -> {
                demo.update { it.copy(lowTires = if (command.low) it.lowTires + command.wheel else it.lowTires - command.wheel) }
                log(LogKind.INJECT, "${command.wheel.label} 타이어 공기압 ${if (command.low) "저하" else "정상"} (시연값)")
            }
            DemoCommand.ClearTires -> {
                demo.update { it.copy(lowTires = emptySet()) }
                log(LogKind.INJECT, "타이어 시연값 모두 해제")
            }
            is DemoCommand.Env -> setEnvironment(command.key, command.value)
            DemoCommand.LockEnvironment -> lockDemoEnvironment()
            DemoCommand.ResetEnvironment -> {
                controls.value = EnvironmentControls()
                log(LogKind.ENVIRONMENT, "주행 환경 자동으로 복귀")
            }
            is DemoCommand.Ask -> VoiceCommands.SAMPLES.getOrNull(command.index)?.let(::answer)
        }
    }

    private fun setEnvironment(key: String, value: String) {
        val override = when (value) {
            "on" -> Override.FORCE_ON
            "off" -> Override.FORCE_OFF
            else -> Override.AUTO
        }
        controls.update {
            when (key) {
                "night" -> it.copy(night = override)
                "weather" -> it.copy(weather = override)
                "driving" -> it.copy(driving = override)
                "highway" -> it.copy(highway = value == "on")
                "long" -> it.copy(longDistance = value == "on")
                else -> it
            }
        }
        log(LogKind.ENVIRONMENT, "환경 설정: $key = $value")
    }

    fun onLocationPermission() = location.retry()

    override fun onCleared() {
        source.release()
        voice.stop()
        speaker.stop()
        navi.stop()
        location.stop()
    }

    // ---- Internals --------------------------------------------------------------------------

    /** Demo pressures replace sensor values only while at least one wheel is switched low. */
    private fun withDemoTires(snapshot: VehicleSnapshot, low: Set<Wheel>): VehicleSnapshot {
        if (low.isEmpty()) return snapshot
        val pressures = Wheel.entries.associateWith { TireReading(if (it in low) DEMO_LOW_KPA else DEMO_OK_KPA, simulated = true) }
        return snapshot.copy(tirePressure = pressures)
    }

    /** F6 explanations for new diagnoses; F3 speech for diagnoses that newly reach grade 3+. */
    private fun onReport(state: UiState) {
        // Starting to drive closes anything long that was open.
        if (state.environment.driving && (assist.value.detailId != null || assist.value.reportOpen)) {
            assist.update { it.copy(detailId = null, reportOpen = false) }
            showNotice("주행을 시작해 상세 화면을 닫았습니다")
        }
        val current = state.report.diagnoses
        val currentKeys = current.map { UiState.key(it) }.toSet()
        spoken.retainAll(currentKeys)

        current.forEach { d ->
            val key = UiState.key(d)
            if (key !in assist.value.explanations && key !in explaining) {
                explaining += key
                viewModelScope.launch {
                    val exp = GeminiExplainer.explain(d, state.environment, state.weatherLabel)
                    assist.update { it.copy(explanations = it.explanations + (key to exp)) }
                    explaining -= key
                    if (exp.byAi) log(LogKind.JUDGE, "AI 설명 생성: ${d.rule.label}")
                }
            }
            if (d.grade >= SPEAK_FROM_GRADE && key !in spoken) {
                spoken += key
                viewModelScope.launch {
                    // Prefer the AI sentence, but never hold a grade-3+ warning for long.
                    val exp = withTimeoutOrNull(SPEECH_WAIT_MILLIS) {
                        while (assist.value.explanations[key] == null) delay(200)
                        assist.value.explanations[key]
                    } ?: GeminiExplainer.template(d)
                    speaker.speak(exp.speech)
                    assist.update { it.copy(lastSpoken = exp.speech) }
                    log(LogKind.OVERALL, "음성 안내: ${exp.speech}")
                }
            }
        }
    }

    private suspend fun searchPlaces(pos: Position) {
        assist.update { it.copy(placesSearching = true) }
        val found = PlaceKind.entries.associateWith { TmapPlaces.search(it, pos.lat, pos.lng) }
        assist.update { it.copy(places = found, placesSearching = false) }
        log(
            LogKind.ENVIRONMENT,
            "Tmap 검색 (${pos.source}): 정비소 ${found[PlaceKind.REPAIR].orEmpty().size}곳, 주차장 ${found[PlaceKind.PARKING].orEmpty().size}곳"
        )
    }

    private fun distanceKm(a: Position, b: Position): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(a.lat)) * Math.cos(Math.toRadians(b.lat)) * Math.sin(dLng / 2).let { it * it }
        return 6371.0 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h))
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
        Log.i(MONITOR_TAG, "${kind.name}|$text")
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
        const val TAG = "MainViewModel"
        const val POLL_MILLIS = 500L
        const val MONITOR_TAG = "CarDoctorLog"
        const val WEATHER_MILLIS = 10 * 60_000L
        const val SPEAK_FROM_GRADE = 3
        const val SPEECH_WAIT_MILLIS = 4_000L
        const val DEMO_LOW_KPA = 120f
        const val DEMO_OK_KPA = 235f
        const val DRIVING_NOTICE = "주행 중에는 정차 후 확인할 수 있습니다 · 음성으로 \"왜 위험해?\"라고 물어보세요"
    }
}
