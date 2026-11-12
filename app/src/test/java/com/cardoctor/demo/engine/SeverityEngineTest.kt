package com.cardoctor.demo.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the PRD "부품별 판정 예시" table and the detection special cases. */
class SeverityEngineTest {

    private val day = Environment()
    private val nightRain = Environment(night = true, badWeather = true)

    private fun lamps(vararg types: String) =
        VehicleSnapshot(telltales = types.map { TelltaleSignal(it, color = LampColor.RED) })

    private fun gradeOf(snapshot: VehicleSnapshot, env: Environment, ruleId: String): Int =
        SeverityEngine.diagnose(snapshot, env).diagnoses.first { it.rule.id == ruleId }.grade

    @Test
    fun scoreThresholds() {
        assertEquals(1, SeverityEngine.gradeForScore(1))
        assertEquals(2, SeverityEngine.gradeForScore(2))
        assertEquals(2, SeverityEngine.gradeForScore(3))
        assertEquals(3, SeverityEngine.gradeForScore(4))
        assertEquals(3, SeverityEngine.gradeForScore(5))
        assertEquals(4, SeverityEngine.gradeForScore(6))
    }

    @Test
    fun headlampIsGrade1ByDayAndGrade3ByNight() {
        assertEquals(1, gradeOf(lamps("LED_HEADLAMP"), day, "headlamp"))
        assertEquals(3, gradeOf(lamps("LED_HEADLAMP"), Environment(night = true), "headlamp"))
    }

    @Test
    fun wiperIsGrade1WhenDryAndGrade3InRain() {
        val broken = VehicleSnapshot(wiperSwitchOn = true, wiperStateOn = false)
        assertEquals(1, gradeOf(broken, day, "wiper"))
        assertEquals(3, gradeOf(broken, nightRain, "wiper"))
    }

    @Test
    fun tirePressureIsGrade2ByDayAndGrade3InRain() {
        assertEquals(2, gradeOf(lamps("TPMS"), day, "tire_pressure"))
        assertEquals(3, gradeOf(lamps("TPMS"), nightRain, "tire_pressure"))
    }

    @Test
    fun tirePressureInRainOnHighwayStaysGrade3() {
        val env = Environment(badWeather = true, highway = true)
        val diagnosis = SeverityEngine.diagnose(lamps("TPMS"), env).diagnoses.single()
        assertEquals(5, diagnosis.score)
        assertEquals(3, diagnosis.grade)
    }

    @Test
    fun overheatIsGrade3RegardlessOfEnvironment() {
        val byDay = SeverityEngine.diagnose(lamps("ENGINE_TEMPERATURE"), day).diagnoses.single()
        assertEquals(3, byDay.grade)
        assertTrue(byDay.floorApplied)
        assertEquals(3, gradeOf(lamps("ENGINE_TEMPERATURE"), nightRain, "overheat"))
    }

    @Test
    fun brakeLampWithAbsIsGrade4RegardlessOfEnvironment() {
        assertEquals(4, gradeOf(lamps("PARKING_BRAKE", "ABS"), day, "brake_system"))
        assertEquals(4, gradeOf(lamps("ELECTRONIC_PARKING_BRAKE", "ABS"), nightRain, "brake_system"))
    }

    @Test
    fun parkingBrakeAloneIsANoticeNotAFault() {
        val report = SeverityEngine.diagnose(lamps("PARKING_BRAKE"), day)
        assertTrue(report.diagnoses.isEmpty())
        assertEquals(1, report.notices.size)
        assertEquals(0, report.overallGrade)
    }

    @Test
    fun absAloneDependsOnWeather() {
        assertEquals(2, gradeOf(lamps("ABS"), day, "abs"))
        assertEquals(3, gradeOf(lamps("ABS"), Environment(badWeather = true), "abs"))
    }

    @Test
    fun headlampSwitchMismatchIsDetectedOnce() {
        val snapshot = VehicleSnapshot(
            telltales = listOf(TelltaleSignal("LED_HEADLAMP")),
            lowBeamSwitchOn = true,
            lowBeamStateOn = false
        )
        assertEquals(1, SeverityEngine.diagnose(snapshot, day).diagnoses.size)
    }

    @Test
    fun masterWarningOnlyCountsWhenNothingElseIsLit() {
        assertEquals("master_warning", SeverityEngine.diagnose(lamps("MASTER_WARNING"), day).diagnoses.single().rule.id)
        assertEquals("tire_pressure", SeverityEngine.diagnose(lamps("MASTER_WARNING", "TPMS"), day).diagnoses.single().rule.id)
    }

    @Test
    fun indicatorLampsAreIgnored() {
        val report = SeverityEngine.diagnose(lamps("TURN_SIGNAL_LEFT", "HEADLAMP_LOW", "SEAT_BELT"), nightRain)
        assertTrue(report.diagnoses.isEmpty())
    }

    @Test
    fun reportIsSortedByGradeAndHighlightsParts() {
        val report = SeverityEngine.diagnose(lamps("TPMS", "ENGINE_TEMPERATURE"), day)
        assertEquals(listOf("overheat", "tire_pressure"), report.diagnoses.map { it.rule.id })
        assertEquals(3, report.overallGrade)
        assertEquals(2, report.gradeOf(CarPart.TIRES))
        assertEquals(0, report.gradeOf(CarPart.HEADLAMPS))
    }
}
