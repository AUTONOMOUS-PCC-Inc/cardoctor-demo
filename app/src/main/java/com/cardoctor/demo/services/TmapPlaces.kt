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
