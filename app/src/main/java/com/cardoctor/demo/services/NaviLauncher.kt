package com.cardoctor.demo.services

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * F4 "안내 시작": hands a place to the car's own navigation through Pleos NaviHelper.
 * Only called from a button press — the app never starts guidance on its own (PRD F4).
 */
class NaviLauncher(context: Context) {

    private val navi = NaviHelper(context.applicationContext)
    private var ready = false

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status

    /** Road speed limit and distance left on the route, for the automatic driving context. */
    private val _drive = MutableStateFlow(com.cardoctor.demo.engine.DriveSignals())
    val drive: StateFlow<com.cardoctor.demo.engine.DriveSignals> = _drive

    private val listener = object : NaviHelperEventListener {
        override fun onDrivingInfo(info: DrivingInfo) {
            info.destination?.let { d ->
                _status.value = "안내 중 · ${d.name} · 남은 거리 ${"%.1f".format(d.distance / 1000.0)}km"
                _drive.value = _drive.value.copy(remainMeters = d.distance.toInt())
            }
        }
        override fun onCurrentRoadSpeedLimit(limit: Int) {
            _drive.value = _drive.value.copy(roadSpeedLimit = limit.takeIf { it > 0 })
        }
        override fun onRouteStarted(info: ai.pleos.playground.navi.data.RouteStartInfo) {
            _status.value = "안내 시작 · ${info.name}"
        }
        override fun onDestinationArrived(info: ai.pleos.playground.navi.data.DestinationArrivedInfo) {
            _status.value = "목적지에 도착했습니다"
            _drive.value = _drive.value.copy(remainMeters = null)
        }
        override fun onRouteEnded() { _status.value = null; _drive.value = _drive.value.copy(remainMeters = null) }
        override fun onRouteCancelled() { _status.value = null; _drive.value = _drive.value.copy(remainMeters = null) }
        override fun onError(code: NaviErrorCode) { _status.value = "내비 오류: ${code.name}" }
    }

    fun start() {
        try {
            navi.initialize(
                { ready = true; navi.addListener(listener) },
                { e -> Log.w(TAG, "NaviHelper init failed: ${e.message}") },
            )
        } catch (e: Exception) {
            Log.w(TAG, "NaviHelper unavailable: ${e.message}")
        }
    }

    fun routeTo(place: Place): Boolean {
        if (!ready) {
            _status.value = "차량 내비에 연결되지 않았습니다"
            return false
        }
        return try {
            navi.requestRoute(
                RouteInfo(
                    place.lat, place.lng, place.lat, place.lng,
                    place.name, place.id, place.address, "",
                    RouteOption.RECOMMENDED, "", false,
                )
            )
            _status.value = "${place.name}(으)로 경로를 요청했습니다"
            true
        } catch (e: Exception) {
            _status.value = "경로 요청 실패: ${e.message}"
            false
        }
    }

    fun stop() {
        if (!ready) return
        runCatching { navi.removeListener(listener); navi.release() }
        ready = false
    }

    private companion object { const val TAG = "NaviLauncher" }
}
