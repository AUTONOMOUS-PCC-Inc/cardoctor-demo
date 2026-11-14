package com.cardoctor.demo.services

import android.util.Log
import com.cardoctor.demo.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class PlaceKind(val label: String, val keyword: String) {
    REPAIR("정비소", "자동차정비"),
    PARKING("주차장", "주차장")
}

data class Place(
    val id: String,
    val kind: PlaceKind,
    val name: String,
    val address: String,
    val lat: Double,
    val lng: Double,
    val distanceKm: Double
)

/**
 * F4: nearby repair shops and parking lots from the Tmap POI search, nearest first.
 * Returns an empty list without a key or on any error; the UI says so instead of inventing places.
 */
object TmapPlaces {

    val enabled: Boolean get() = BuildConfig.TMAP_APP_KEY.isNotBlank()

    suspend fun search(kind: PlaceKind, lat: Double, lng: Double, count: Int = 3): List<Place> {
        if (!enabled) return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                val q = URLEncoder.encode(kind.keyword, "UTF-8")
                val url = URL(
                    "https://apis.openapi.sk.com/tmap/pois?version=1&searchKeyword=$q" +
                        "&centerLat=$lat&centerLon=$lng&radius=5&count=$count&searchtypCd=R" +
                        "&reqCoordType=WGS84GEO&resCoordType=WGS84GEO"
                )
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 6000
                    setRequestProperty("appKey", BuildConfig.TMAP_APP_KEY)
                    setRequestProperty("Accept", "application/json")
                }
                try {
                    if (conn.responseCode == 204) return@runCatching emptyList<Place>() // no results
                    parse(kind, conn.inputStream.bufferedReader().readText())
                } finally {
                    conn.disconnect()
                }
            }.onFailure { Log.w("TmapPlaces", "${kind.label}: ${it.message}") }.getOrDefault(emptyList())
        }
    }

    private fun parse(kind: PlaceKind, body: String): List<Place> {
        val pois = JSONObject(body).getJSONObject("searchPoiInfo").getJSONObject("pois").getJSONArray("poi")
        return (0 until pois.length()).map { i ->
            val p = pois.getJSONObject(i)
            // front* is the entrance a car should drive to; fall back to the POI centre.
            val lat = p.optString("frontLat").toDoubleOrNull() ?: p.getString("noorLat").toDouble()
            val lng = p.optString("frontLon").toDoubleOrNull() ?: p.getString("noorLon").toDouble()
            val address = listOf("upperAddrName", "middleAddrName", "lowerAddrName", "roadName")
                .map { p.optString(it) }.filter { it.isNotBlank() }.joinToString(" ")
            Place(
                id = "tmap-" + p.optString("id"),
                kind = kind,
                name = p.getString("name"),
                address = address,
                lat = lat,
                lng = lng,
                distanceKm = p.optString("radius").toDoubleOrNull() ?: 0.0
            )
        }
    }
}

/** Road route for the preview: the line to draw plus Tmap's own time and distance. */
data class RoutePreview(
    /** [lng, lat] pairs along the road, start to end. */
    val points: List<Pair<Double, Double>>,
    val distanceMeters: Int,
    val timeSeconds: Int
) {
    val summary: String
        get() {
            val minutes = (timeSeconds + 59) / 60
            val distance = if (distanceMeters < 1000) "${distanceMeters}m" else "%.1fkm".format(distanceMeters / 1000.0)
            return "약 ${minutes}분 · $distance"
        }
}

/**
 * F4 preview: the car route from here to a place (Tmap 자동차 경로안내), drawn on the parked map before
 * "안내 시작". Guidance itself still runs in the car's navigation. Null without a key or on any error.
 */
object TmapRoutes {

    suspend fun route(fromLat: Double, fromLng: Double, to: Place): RoutePreview? {
        if (!TmapPlaces.enabled) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("startX", fromLng.toString()).put("startY", fromLat.toString())
                    .put("endX", to.lng.toString()).put("endY", to.lat.toString())
                    .put("startName", URLEncoder.encode("현재 위치", "UTF-8"))
                    .put("endName", URLEncoder.encode(to.name, "UTF-8"))
                    .put("reqCoordType", "WGS84GEO").put("resCoordType", "WGS84GEO")
                    .put("searchOption", "0")
                val conn = (URL("https://apis.openapi.sk.com/tmap/routes?version=1&format=json")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 5000
                    readTimeout = 8000
                    doOutput = true
                    setRequestProperty("appKey", BuildConfig.TMAP_APP_KEY)
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                }
                try {
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    if (conn.responseCode != 200) {
                        Log.w("TmapRoutes", "HTTP ${conn.responseCode}")
                        return@runCatching null
                    }
                    parse(conn.inputStream.bufferedReader().readText())
                } finally {
                    conn.disconnect()
                }
            }.onFailure { Log.w("TmapRoutes", "${to.name}: ${it.message}") }.getOrNull()
        }
    }

    private fun parse(body: String): RoutePreview? {
        val features = JSONObject(body).getJSONArray("features")
        var distance = 0
        var time = 0
        val points = mutableListOf<Pair<Double, Double>>()
        for (i in 0 until features.length()) {
            val f = features.getJSONObject(i)
            val props = f.optJSONObject("properties")
            if (props != null && props.has("totalDistance")) {
                distance = props.optInt("totalDistance")
                time = props.optInt("totalTime")
            }
            val geometry = f.getJSONObject("geometry")
            if (geometry.getString("type") == "LineString") {
                val coords = geometry.getJSONArray("coordinates")
                for (j in 0 until coords.length()) {
                    val c = coords.getJSONArray(j)
                    val point = c.getDouble(0) to c.getDouble(1)
                    if (points.lastOrNull() != point) points += point
                }
            }
        }
        return if (points.size >= 2) RoutePreview(points, distance, time) else null
    }
}
