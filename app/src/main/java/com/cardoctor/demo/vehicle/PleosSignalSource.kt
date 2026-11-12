package com.cardoctor.demo.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.control.WiperSwitch
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import android.content.Context
import com.cardoctor.demo.engine.LampColor
import com.cardoctor.demo.engine.TelltaleSignal
import com.cardoctor.demo.engine.VehicleSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Reads the signals CarDoctor needs through the Pleos Vehicle SDK. Read-only: it never calls a
 * set / move API. Every call is asynchronous; results are merged into [snapshot] as they arrive.
 */
class PleosSignalSource(context: Context) {

    private val vehicle = Vehicle(context.applicationContext)
    private val frontWindshield = WindowArea(WindowValue.FRONT_WINDSHIELD)

    private val _snapshot = MutableStateFlow(VehicleSnapshot())
    val snapshot: StateFlow<VehicleSnapshot> = _snapshot

    /** Latest problem per signal, e.g. a missing permission. Empty when everything reads. */
    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = _errors

    private var initialized = false

    fun initialize(): Boolean {
        if (initialized) return true
        return try {
            vehicle.initialize()
            initialized = true
            true
        } catch (e: Exception) {
            report("연결", e)
            false
        }
    }

    fun release() {
        if (!initialized) return
        runCatching { vehicle.release() }
        initialized = false
    }

    /** Requests every signal once. Call periodically. */
    fun refresh() {
        if (!initialized) return

        call("경고등") {
            vehicle.getSafety().getTelltalesInfo(
                { telltales ->
                    val lit = telltales.telltales
                        .filter { it.state != TelltaleState.OFF }
                        .map {
                            TelltaleSignal(
                                type = it.type.name,
                                blinking = it.state == TelltaleState.BLINK,
                                color = LampColor.fromCode(it.color)
                            )
                        }
                    _snapshot.update { it.copy(telltales = lit) }
                    clear("경고등")
                },
                { report("경고등", it) }
            )
        }

        call("전조등") {
            val light = vehicle.getLight()
            light.getLowBeamLightSwitch(
                { switch ->
                    _snapshot.update { it.copy(lowBeamSwitchOn = switch == LightSwitch.ON) }
                    clear("전조등")
                },
                { report("전조등", it) }
            )
            light.getCurrentLowBeamLightState(
                { state -> _snapshot.update { it.copy(lowBeamStateOn = state == LightState.ON) } },
                { report("전조등", it) }
            )
        }

        call("와이퍼") {
            val wiper = vehicle.getWiper()
            wiper.getCurrentWiperSwitch(
                frontWindshield,
                { switch ->
                    val on = switch != WiperSwitch.OFF && switch != WiperSwitch.OTHER
                    _snapshot.update { it.copy(wiperSwitchOn = on) }
                    clear("와이퍼")
                },
                { report("와이퍼", it) }
            )
            wiper.getCurrentWiperState(
                frontWindshield,
                { state -> _snapshot.update { it.copy(wiperStateOn = state == WiperState.ON) } },
                { report("와이퍼", it) }
            )
        }
    }

    private inline fun call(signal: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            report(signal, e)
        }
    }

    private fun report(signal: String, e: Exception) {
        val message = when {
            e is SecurityException || e.message?.contains("Permission") == true -> "권한 없음"
            else -> e.message?.take(80) ?: e.javaClass.simpleName
        }
        _errors.update { it + (signal to message) }
    }

    private fun clear(signal: String) {
        _errors.update { it - signal }
    }
}
