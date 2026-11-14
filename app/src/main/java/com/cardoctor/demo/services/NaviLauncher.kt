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

    private val appContext = context.applicationContext
    private val navi = NaviHelper(appContext)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var started = false
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
            started = true
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

    /**
     * The car's navigation drops route requests ("navi not ready") until its screen has been opened
     * once after boot. So: bring the navigation to the front first (the driver ends up there anyway),
     * send the route a moment later, and send it once more if guidance has not started.
     */
    fun routeTo(place: Place): Boolean {
        if (!ready) {
            _status.value = "차량 내비에 연결되지 않았습니다"
            return false
        }
        started = false
        main.removeCallbacksAndMessages(null)
        openNavigation()
        _status.value = "내비를 여는 중 · ${place.name}"
        main.postDelayed({ send(place) }, OPEN_DELAY_MS)
        main.postDelayed({
            if (!started) {
                Log.i(TAG, "guidance not started yet, sending the route again")
                openNavigation()
                send(place)
            }
        }, OPEN_DELAY_MS + RETRY_AFTER_MS)
        return true
    }

    private fun openNavigation() {
        val intent = appContext.packageManager.getLaunchIntentForPackage(NAVI_PACKAGE) ?: return
        runCatching { appContext.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.w(TAG, "open navigation: ${it.message}") }
    }

    private fun send(place: Place) {
        try {
            navi.requestRoute(
                RouteInfo(
                    place.lat, place.lng, place.lat, place.lng,
                    place.name, place.id, place.address, "",
                    RouteOption.RECOMMENDED, "", false,
                )
            )
            if (!started) _status.value = "${place.name}(으)로 경로를 요청했습니다"
        } catch (e: Exception) {
            _status.value = "경로 요청 실패: ${e.message}"
        }
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        if (!ready) return
        runCatching { navi.removeListener(listener); navi.release() }
        ready = false
    }

    private companion object {
        const val TAG = "NaviLauncher"
        const val NAVI_PACKAGE = "ai.umos.maps.android.navigation.app"
        const val OPEN_DELAY_MS = 1_500L
        const val RETRY_AFTER_MS = 3_500L
    }
}
