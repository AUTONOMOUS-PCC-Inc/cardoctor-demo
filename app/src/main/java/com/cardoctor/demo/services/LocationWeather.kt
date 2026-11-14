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

data class Position(val lat: Double, val lng: Double, val fixed: Boolean, val source: String)

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
            data?.let { onFix(it.latitude, it.longitude, "FusedLocation") }
        }
        override fun onError(e: Exception) { Log.w(TAG, "fused: ${e.message}") }
    }
    private val androidListener = LocationListener { onFix(it.latitude, it.longitude, "GPS") }

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
                lm.requestLocationUpdates(provider, 5_000L, 20f, androidListener, Looper.getMainLooper())
            } catch (e: Exception) {
                Log.w(TAG, "location $provider: ${e.message}")
            }
        }
    }

    /** Called again after the user grants the location permission. */
    fun retry() = startAndroid()

    private fun onFix(lat: Double, lng: Double, source: String) {
        if (lat == 0.0 && lng == 0.0) return
        _position.value = Position(lat, lng, true, source)
    }

    fun stop() {
        runCatching { lm.removeUpdates(androidListener) }
        if (fusedActive) runCatching {
            fused?.unregisterFusedLocationCallback(fusedListener)
            fused?.release()
        }
        fusedActive = false
    }

    private companion object { const val TAG = "LocationSource" }
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
