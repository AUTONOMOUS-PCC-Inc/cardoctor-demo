package com.cardoctor.demo.ui

import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.cardoctor.demo.UiState
import com.cardoctor.demo.services.Place
import com.cardoctor.demo.services.PlaceKind
import org.json.JSONArray
import org.json.JSONObject

/**
 * F4 map: nearby repair shops and parking on an OpenFreeMap dark map (OpenStreetMap data, no key),
 * next to the list. Shown only while parked; guidance itself still goes to the car's navigation.
 * Places come from the Tmap search, which knows Korean shops far better than OpenStreetMap.
 */
@Composable
fun PlacesMapOverlay(state: UiState, onStartGuidance: (Place) -> Unit, onClose: () -> Unit) {
    val places = remember(state.assist.places) {
        state.assist.places[PlaceKind.REPAIR].orEmpty() + state.assist.places[PlaceKind.PARKING].orEmpty()
    }
    var selected by remember { mutableStateOf(places.firstOrNull()?.id) }
    var focusRequest by remember { mutableStateOf<String?>(null) }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)).clickable(onClick = onClose),
        contentAlignment = Alignment.Center
    ) {
        Row(
            Modifier
                .fillMaxWidth(0.86f)
                .height(780.dp)
                // The map sits inset by 10dp instead of being clipped to the rounded panel.
                .background(Brush.verticalGradient(listOf(Color(0xFF151B23), Color(0xFF0B0F14))), RoundedCornerShape(32.dp))
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(32.dp))
                .clickable(enabled = false) {}
        ) {
            PlacesMapView(
                state, places, selected, focusRequest,
                onSelect = { selected = it },
                modifier = Modifier.weight(1.65f).fillMaxHeight().padding(10.dp)
            )
            Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 32.dp, vertical = 30.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("NEARBY", style = Type.Eyebrow)
                    Spacer(Modifier.weight(1f))
                    ActionPill("닫기", Palette.TextSecondary, onClose)
                }
                Spacer(Modifier.height(8.dp))
                Text("정비소와 주차장 · Tmap 검색", style = Type.body(18, Palette.TextTertiary))
                Spacer(Modifier.height(18.dp))
                // Same numbering as the pins on the map: repair shops 1, 2, 3; parking P.
                val labels = remember(places) {
                    var n = 0
                    places.associate { it.id to if (it.kind == PlaceKind.REPAIR) (++n).toString() else "P" }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(places, key = { it.id }) { place ->
                        val label = labels[place.id] ?: ""
                        PlaceRow(place, label, place.id == selected, onPick = {
                            selected = place.id
                            focusRequest = place.id
                        }, onGuide = { onStartGuidance(place); onClose() })
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("안내는 차량 내비로 연결됩니다 · 지도는 정차 중에만 표시", style = Type.body(16, Palette.TextTertiary))
            }
        }
    }
}

@Composable
private fun PlaceRow(place: Place, label: String, selected: Boolean, onPick: () -> Unit, onGuide: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val pin = if (place.kind == PlaceKind.REPAIR) Color(0xFFFFB347) else Color(0xFF8FA3B8)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) pin.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.04f))
            .border(1.dp, if (selected) pin else Color.White.copy(alpha = 0.10f), shape)
            .clickable(onClick = onPick)
            .padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(pin), contentAlignment = Alignment.Center) {
            Text(label, style = Type.title(17).copy(color = Color(0xFF0B0F14)))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(place.name, style = Type.title(22), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val distance = if (place.distanceKm < 1.0) "${(place.distanceKm * 1000).toInt()} m" else "%.1f km".format(place.distanceKm)
            Text("${place.kind.label} · $distance", style = Type.body(17, Palette.TextSecondary))
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .heightIn(min = 54.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(1.5.dp, if (selected) Palette.Accent else Color.White.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                .clickable(onClick = onGuide)
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("안내 시작", style = Type.title(19).copy(color = if (selected) Palette.Accent else Palette.TextPrimary))
        }
    }
}

@Composable
private fun PlacesMapView(
    state: UiState,
    places: List<Place>,
    selected: String?,
    focusRequest: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier
) {
    var ready by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val payload = remember(places, state.assist.position) {
        JSONObject().apply {
            state.assist.position?.let { put("me", JSONObject().put("lat", it.lat).put("lng", it.lng)) }
            put("places", JSONArray().apply {
                places.forEach { p ->
                    put(JSONObject().put("id", p.id).put("kind", p.kind.name).put("name", p.name).put("lat", p.lat).put("lng", p.lng))
                }
            })
            places.firstOrNull()?.let { put("selected", it.id) }
        }.toString()
    }
    // What was last sent to the page, so recompositions do not re-fit the map or re-fly to a pin.
    val sent = remember { arrayOfNulls<String>(2) }

    Box(modifier.background(Color(0xFF0B0F14)), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    setBackgroundColor(AndroidColor.parseColor("#0B0F14"))
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.javaScriptEnabled = true
                    val main = Handler(Looper.getMainLooper())
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onReady(ok: Boolean) = main.post { if (ok) ready = true else failed = true }.let { }

                        @JavascriptInterface
                        fun onSelect(id: String) = main.post { onSelect(id) }.let { }
                    }, "Android")
                    val assets = WebViewAssetLoader.Builder()
                        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                        .build()
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                            assets.shouldInterceptRequest(request.url)
                    }
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                            android.util.Log.d("PlacesMap", "${message.messageLevel()} ${message.message()}")
                            return true
                        }
                    }
                    loadUrl("https://appassets.androidplatform.net/assets/map/index.html")
                }
            },
            update = { web ->
                if (ready) {
                    if (sent[0] != payload) {
                        sent[0] = payload
                        web.evaluateJavascript("window.showPlaces($payload)", null)
                    }
                    if (focusRequest != null && sent[1] != focusRequest) {
                        sent[1] = focusRequest
                        web.evaluateJavascript("window.focusPlace(${JSONObject.quote(focusRequest)})", null)
                    }
                }
            }
        )
        if (failed) {
            Text("지도를 불러오지 못했습니다 · 목록에서 안내를 시작하세요", style = Type.body(20, Palette.TextSecondary))
        }
    }
}
