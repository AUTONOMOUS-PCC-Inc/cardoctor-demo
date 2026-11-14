package com.cardoctor.demo.services

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class Position(
    val lat: Double,
    val lng: Double,
    val fixed: Boolean,
    val source: String,
    /** Ground speed, km/h: GPS speed when the fix has one, else from the last two fixes. */
    val speedKmh: Float? = null
)

/**
 * Car position: Pleos FusedLocation first, Android LocationManager as a fallback. Until the first
 * fix the position is Seoul City Hall and [Position.fixed] is false.
 */
class LocationSource(context: Context) {

    private val appContext = context.applicationContext
    private val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val _position = MutableStateFlow(Position(37.5665, 126.9780, false, "기본 위치"))
    val position: StateFlow<Position> = _position

    private val fused = runCatching { FusedLocation(appContext) }.getOrNull()
    private var fusedActive = false
    private val fusedListener = object : FusedLocationListener {
        override fun onChanged(data: LocationData?) {
            data?.let { onFix(it.latitude, it.longitude, "FusedLocation", it.timestampMillis.takeIf { t -> t > 0 }) }
        }
        override fun onError(e: Exception) { Log.w(TAG, "fused: ${e.message}") }
    }
    private val androidListener = LocationListener {
        onFix(it.latitude, it.longitude, "GPS", it.time, if (it.hasSpeed()) it.speed * 3.6f else null)
    }

    fun start() {
        startFused()
        startAndroid() // both feed the same flow; whichever answers first wins
    }

    private fun startFused() {
        val f = fused ?: return
        try {
            if (!f.isSupported()) return
            f.initialize()
            f.getLastKnownLocation(fusedListener)
            f.registerFusedLocationCallback(fusedListener)
            fusedActive = true
        } catch (e: Exception) {
            Log.w(TAG, "FusedLocation unavailable: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAndroid() {
        val granted = appContext.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (!lm.isProviderEnabled(provider)) continue
                lm.getLastKnownLocation(provider)?.let { onFix(it.latitude, it.longitude, "GPS") }
                // Every second, no distance filter: speed must also drop back to 0 when the car stops.
                lm.requestLocationUpdates(provider, 1_000L, 0f, androidListener, Looper.getMainLooper())
            } catch (e: Exception) {
                Log.w(TAG, "location $provider: ${e.message}")
            }
        }
    }

    /** Called again after the user grants the location permission. */
    fun retry() = startAndroid()

    /** Previous fix per source: FusedLocation and GPS report the same place at different moments. */
    private val lastFix = mutableMapOf<String, Triple<Double, Double, Long>>()
    private val speeds = mutableMapOf<String, Pair<Float, Long>>()

    private fun onFix(lat: Double, lng: Double, source: String, timeMillis: Long? = null, gpsKmh: Float? = null) {
        if (lat == 0.0 && lng == 0.0) return
        val now = timeMillis ?: System.currentTimeMillis()
        val derived = lastFix[source]?.let { (pLat, pLng, pTime) ->
            val seconds = (now - pTime) / 1000.0
            if (seconds in 0.5..30.0) (metersBetween(pLat, pLng, lat, lng) / seconds * 3.6).toFloat() else null
        }
        lastFix[source] = Triple(lat, lng, now)
        (gpsKmh ?: derived)?.let { speeds[source] = it to System.currentTimeMillis() }
        // Highest fresh speed across sources: one source may lag (on the emulator FusedLocation follows the
        // navigation's own position). Parked GPS jitter stays far below the 5 km/h moving threshold.
        val fresh = speeds.values.filter { System.currentTimeMillis() - it.second <= SPEED_FRESH_MILLIS }
            .maxOfOrNull { it.first }
        _position.value = Position(lat, lng, true, source, fresh)
    }

    private fun metersBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(a))
    }

    fun stop() {
        runCatching { lm.removeUpdates(androidListener) }
        if (fusedActive) runCatching {
            fused?.unregisterFusedLocationCallback(fusedListener)
            fused?.release()
        }
        fusedActive = false
    }

    private companion object {
        const val TAG = "LocationSource"
        const val SPEED_FRESH_MILLIS = 5_000L // releasing the driving lock late is the safe side
    }
}

/** F7: current weather. No API key: MET Norway first, Open-Meteo as a fallback. */
data class Weather(val code: Int, val label: String, val wet: Boolean, val tempC: Double?, val source: String = "")

object WeatherApi {
    suspend fun fetch(lat: Double, lng: Double): Weather? = metNo(lat, lng) ?: openMeteo(lat, lng)

    /** api.met.no asks every client to identify itself with a User-Agent. */
    private suspend fun metNo(lat: Double, lng: Double): Weather? = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(
                "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=${"%.4f".format(lat)}&lon=${"%.4f".format(lng)}"
            )
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 8000
                setRequestProperty("User-Agent", "cardoctor-demo/0.2 (Pleos Connect contest app)")
            }
            try {
                val now = JSONObject(conn.inputStream.bufferedReader().readText())
                    .getJSONObject("properties").getJSONArray("timeseries").getJSONObject(0).getJSONObject("data")
                val symbol = now.optJSONObject("next_1_hours")?.getJSONObject("summary")?.getString("symbol_code") ?: "cloudy"
                val temp = now.getJSONObject("instant").getJSONObject("details").optDouble("air_temperature")
                Weather(-1, metLabel(symbol), metWet(symbol), temp.takeIf { !it.isNaN() }, "MET Norway")
            } finally {
                conn.disconnect()
            }
        }.onFailure { Log.w("WeatherApi", "met.no: ${it.message}") }.getOrNull()
    }

    fun metLabel(symbol: String): String = when {
        "snow" in symbol || "sleet" in symbol -> "눈"
        "rain" in symbol || "thunder" in symbol -> "비"
        "fog" in symbol -> "안개"
        "clearsky" in symbol || "fair" in symbol -> "맑음"
        else -> "흐림"
    }

    fun metWet(symbol: String): Boolean = listOf("rain", "snow", "sleet", "thunder").any { it in symbol }

    private suspend fun openMeteo(lat: Double, lng: Double): Weather? = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lng" +
                    "&current=weather_code,temperature_2m"
            )
            val conn = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 5000; readTimeout = 5000 }
            try {
                val current = JSONObject(conn.inputStream.bufferedReader().readText()).getJSONObject("current")
                val code = current.getInt("weather_code")
                Weather(code, label(code), wet(code), current.optDouble("temperature_2m").takeIf { !it.isNaN() }, "Open-Meteo")
            } finally {
                conn.disconnect()
            }
        }.onFailure { Log.w("WeatherApi", "weather: ${it.message}") }.getOrNull()
    }

    /** WMO weather interpretation codes. */
    fun label(code: Int): String = when (code) {
        0, 1 -> "맑음"
        2, 3 -> "흐림"
        45, 48 -> "안개"
        in 51..67, in 80..82, in 95..99 -> "비"
        in 71..77, 85, 86 -> "눈"
        else -> "흐림"
    }

    fun wet(code: Int): Boolean = code in 51..67 || code in 71..77 || code in 80..86 || code in 95..99
}
