package com.cardoctor.demo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cardoctor.demo.engine.DiagnosisReport
import com.cardoctor.demo.engine.Environment
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

data class UiState(
    val connected: Boolean = false,
    val snapshot: VehicleSnapshot = VehicleSnapshot(),
    val controls: EnvironmentControls = EnvironmentControls(),
    val environment: Environment = Environment(),
    val report: DiagnosisReport = DiagnosisReport(emptyList(), emptyList()),
    val errors: Map<String, String> = emptyMap()
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val source = PleosSignalSource(application)
    private val controls = MutableStateFlow(EnvironmentControls())
    private val connected = MutableStateFlow(false)
    private val clockNight = MutableStateFlow(isNightNow())

    val uiState: StateFlow<UiState> = combine(
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
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    init {
        viewModelScope.launch {
            connected.value = source.initialize()
            delay(1_500) // the SDK binds to Vehicle Service asynchronously
            while (isActive) {
                if (!connected.value) connected.value = source.initialize()
                source.refresh()
                clockNight.value = isNightNow()
                delay(1_000)
            }
        }
    }

    fun cycleNight() = controls.update { it.copy(night = it.night.next()) }
    fun cycleWeather() = controls.update { it.copy(weather = it.weather.next()) }
    fun toggleHighway() = controls.update { it.copy(highway = !it.highway) }
    fun toggleLongDistance() = controls.update { it.copy(longDistance = !it.longDistance) }

    override fun onCleared() {
        source.release()
    }

    private fun MutableStateFlow<EnvironmentControls>.update(
        transform: (EnvironmentControls) -> EnvironmentControls
    ) {
        value = transform(value)
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
}
