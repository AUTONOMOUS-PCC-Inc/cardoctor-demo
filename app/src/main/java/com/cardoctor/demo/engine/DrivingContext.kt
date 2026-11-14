package com.cardoctor.demo.engine

/**
 * Signals for the automatic driving context. None needs a private permission:
 * speed comes from location fixes, the road's speed limit and the distance left from the car's
 * navigation (Pleos NaviHelper).
 */
data class DriveSignals(
    /** Ground speed from location fixes, km/h. Null until two fixes or a GPS speed arrive. */
    val speedKmh: Float? = null,
    /** Speed limit of the current road from the navigation, km/h. Null when unknown. */
    val roadSpeedLimit: Int? = null,
    /** Distance left to the destination while guidance is on, metres. Null without a route. */
    val remainMeters: Int? = null
)

/** Turns [DriveSignals] (and the gear) into the environment flags the severity engine weighs. */
object DrivingContext {

    const val MOVING_KMH = 5f
    const val HIGHWAY_KMH = 80f
    const val HIGHWAY_LIMIT_KMH = 80
    const val LONG_DISTANCE_METERS = 50_000

    /**
     * Driving = a gear that moves the car, or moving faster than walking pace.
     * D/R at 0 km/h (waiting at a light) still counts: like Android Automotive's "idling" state,
     * the screen stays restricted until the car is in park.
     */
    fun driving(inMotionGear: Boolean, speedKmh: Float?): Boolean =
        inMotionGear || (speedKmh ?: 0f) >= MOVING_KMH

    /** Expressway or urban expressway: the road's limit is 80+ km/h, or the car is going that fast. */
    fun highway(signals: DriveSignals): Boolean =
        (signals.roadSpeedLimit ?: 0) >= HIGHWAY_LIMIT_KMH || (signals.speedKmh ?: 0f) >= HIGHWAY_KMH

    /** 50 km or more left on the active route. */
    fun longDistance(signals: DriveSignals): Boolean =
        (signals.remainMeters ?: 0) >= LONG_DISTANCE_METERS
}
