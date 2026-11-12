package com.cardoctor.demo.demo

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One fault the demo console can switch on and off. */
enum class DemoFault(
    val label: String,
    /** Telltale type as the Virtual Console spells it, or null for non-telltale signals. */
    val consoleType: String?,
    /** The same telltale as the Vehicle SDK reports it. */
    val sdkType: String?,
    val color: String
) {
    HEADLAMP("전조등", "LEDHeadlamp", "LED_HEADLAMP", "Yellow"),
    TIRE("타이어 공기압", "TPMS", "TPMS", "Yellow"),
    ABS("ABS", "ABS", "ABS", "Yellow"),
    PARKING_BRAKE("브레이크 경고등", "ParkingBrake", "PARKING_BRAKE", "Red"),
    OVERHEAT("과열", "EngineTemperature", "ENGINE_TEMPERATURE", "Red"),
    BATTERY("저전압 배터리", "LowVoltBattery", "LOW_VOLT_BATTERY", "Red"),
    STEERING("조향 보조", "MDPS", "MDPS", "Red"),
    AIRBAG("에어백", "Airbag", "AIRBAG", "Yellow"),
    WIPER("와이퍼 작동(비)", null, null, "")
}

/**
 * Talks to the Pleos Connect emulator's Virtual Console (http://127.0.0.1:8088 on the device) to
 * inject vehicle signals. Demo / debug builds only: a real vehicle has no such console, and the
 * app itself never writes vehicle properties through the SDK.
 */
class DemoConsole {

    /** The console accepts writes only while a session heartbeat is alive (3 s timeout). */
    suspend fun heartbeat(): Boolean = request("POST", "/browser/session?client=cardoctor-app") == 200

    /** Returns the HTTP status, or -1 when the console cannot be reached. */
    suspend fun set(fault: DemoFault, on: Boolean): Int =
        if (fault.consoleType != null) {
            val state = if (on) "On" else "Off"
            request("PUT", "/vehicle/telltales/simulator?type=${fault.consoleType}&state=$state&color=${fault.color}")
        } else {
            request("PUT", "/vehicle/body?front_wiper=${if (on) "Low" else "Off"}")
        }

    private suspend fun request(method: String, path: String): Int = withContext(Dispatchers.IO) {
        try {
            val connection = URL(BASE + path).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 800
                connection.readTimeout = 2_000
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(0)
                connection.outputStream.close()
                connection.responseCode
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            -1
        }
    }

    private companion object {
        const val BASE = "http://127.0.0.1:8088"
    }
}

enum class LogKind(val tag: String) {
    INJECT("주입"), SIGNAL("신호"), JUDGE("판정"), ENVIRONMENT("환경"), OVERALL("종합")
}

data class LogEntry(val id: Long, val timeMillis: Long, val kind: LogKind, val text: String)
