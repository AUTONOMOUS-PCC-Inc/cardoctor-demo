package com.cardoctor.demo.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Automatic driving context from speed, road speed limit and distance left. */
class DrivingContextTest {

    @Test
    fun drivingFromGearOrSpeed() {
        assertTrue(DrivingContext.driving(inMotionGear = true, speedKmh = 0f))   // D at a red light
        assertTrue(DrivingContext.driving(inMotionGear = false, speedKmh = 30f)) // gear signal missing
        assertFalse(DrivingContext.driving(inMotionGear = false, speedKmh = 2f)) // GPS jitter while parked
        assertFalse(DrivingContext.driving(inMotionGear = false, speedKmh = null))
    }

    @Test
    fun highwayFromRoadLimitOrSpeed() {
        assertTrue(DrivingContext.highway(DriveSignals(roadSpeedLimit = 100)))
        assertTrue(DrivingContext.highway(DriveSignals(roadSpeedLimit = 80)))
        assertFalse(DrivingContext.highway(DriveSignals(roadSpeedLimit = 50, speedKmh = 45f)))
        assertTrue(DrivingContext.highway(DriveSignals(speedKmh = 95f)))
        assertFalse(DrivingContext.highway(DriveSignals()))
    }

    @Test
    fun longDistanceFromRouteOnly() {
        assertTrue(DrivingContext.longDistance(DriveSignals(remainMeters = 62_000)))
        assertFalse(DrivingContext.longDistance(DriveSignals(remainMeters = 940)))
        assertFalse(DrivingContext.longDistance(DriveSignals()))
    }

    @Test
    fun automaticHighwayAddsTireWeight() {
        val snapshot = VehicleSnapshot(telltales = listOf(TelltaleSignal("TPMS")))
        val city = Environment(highway = DrivingContext.highway(DriveSignals(roadSpeedLimit = 50)))
        val expressway = Environment(highway = DrivingContext.highway(DriveSignals(roadSpeedLimit = 110)))
        val inCity = SeverityEngine.diagnose(snapshot, city).diagnoses.single()
        val onHighway = SeverityEngine.diagnose(snapshot, expressway).diagnoses.single()
        assertEquals(inCity.score + 1, onHighway.score)
        assertTrue(onHighway.weights.any { it.label == "고속도로" })
        // With rain as well, the tire crosses into grade 3 (2 + 2 + 1 = 5).
        assertEquals(3, SeverityEngine.diagnose(snapshot, expressway.copy(badWeather = true)).diagnoses.single().grade)
    }
}
