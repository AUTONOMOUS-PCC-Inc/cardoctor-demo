package com.cardoctor.demo.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F1 (wheel-level tire pressure) and F5 (service report) on top of the original engine. */
class IntegrationFeaturesTest {

    private val day = Environment()
    private val rain = Environment(badWeather = true)

    private fun tires(vararg low: Wheel, simulated: Boolean = true) = VehicleSnapshot(
        telltales = emptyList(),
        tirePressure = Wheel.entries.associateWith { TireReading(if (it in low) 120f else 230f, simulated) }
    )

    @Test
    fun lowWheelIsNamedAndJudgedLikeTpms() {
        val report = SeverityEngine.diagnose(tires(Wheel.RIGHT_REAR), day)
        val d = report.diagnoses.single()
        assertEquals("tire_pressure_right_rear", d.rule.id)
        assertEquals("뒤 오른쪽 타이어 공기압 저하", d.rule.label)
        assertEquals(2, d.grade)
        assertTrue(d.fault.source.contains("시연값"))
        assertEquals(3, SeverityEngine.diagnose(tires(Wheel.RIGHT_REAR), rain).diagnoses.single().grade)
    }

    @Test
    fun namedWheelReplacesTheGenericTpmsFault() {
        val snapshot = tires(Wheel.LEFT_FRONT).copy(telltales = listOf(TelltaleSignal("TPMS")))
        val ids = SeverityEngine.diagnose(snapshot, day).diagnoses.map { it.rule.id }
        assertEquals(listOf("tire_pressure_left_front"), ids)
    }

    @Test
    fun tpmsLampStillWorksWithoutPressureValues() {
        val snapshot = VehicleSnapshot(telltales = listOf(TelltaleSignal("TPMS")))
        assertEquals("tire_pressure", SeverityEngine.diagnose(snapshot, day).diagnoses.single().rule.id)
    }

    @Test
    fun vehicleThresholdIsUsedWhenKnown() {
        val snapshot = VehicleSnapshot(
            tirePressure = mapOf(Wheel.LEFT_REAR to TireReading(150f)),
            tireThreshold = mapOf(Wheel.LEFT_REAR to 160f)
        )
        assertEquals(listOf(Wheel.LEFT_REAR), snapshot.lowTires)
        assertTrue(VehicleSnapshot(tirePressure = mapOf(Wheel.LEFT_REAR to TireReading(150f))).lowTires.isEmpty())
    }

    @Test
    fun reportKeepsEngineResultAndPrdFields() {
        val snapshot = tires(Wheel.RIGHT_REAR).copy(
            telltales = listOf(TelltaleSignal("LED_HEADLAMP", color = LampColor.YELLOW))
        )
        val env = Environment(night = true, badWeather = true)
        val report = SeverityEngine.diagnose(snapshot, env)
        val json = ServiceReport.build(
            report, snapshot,
            ServiceReport.Context("2026-11-14 10:00:00", env, environmentSimulated = true, weatherLabel = "비")
        )
        assertTrue(json.startsWith("{\"schemaVersion\":1,"))
        assertTrue(json.contains("\"totalFindings\":2"))
        assertTrue(json.contains("\"worstLevel\":3"))
        assertTrue(json.contains("\"title\":\"전조등 이상\""))
        assertTrue(json.contains("{\"reason\":\"야간\",\"points\":3}"))
        assertTrue(json.contains("\"detectionPattern\":\"임계값 초과\""))
        assertTrue(json.contains("\"simulated\":true"))
        assertTrue(json.contains(ServiceReport.DISCLAIMER))
        // urgency = base severity + weights, as in the engine
        report.diagnoses.forEach { assertEquals(it.rule.baseSeverity + it.weights.sumOf { w -> w.points }, it.score) }
    }

    @Test
    fun emptyReportIsValid() {
        val report = SeverityEngine.diagnose(VehicleSnapshot(telltales = emptyList()), day)
        val json = ServiceReport.build(
            report, VehicleSnapshot(),
            ServiceReport.Context("2026-11-14 10:00:00", day, environmentSimulated = false, weatherLabel = "맑음")
        )
        assertTrue(json.contains("\"totalFindings\":0"))
        assertTrue(json.contains("\"worstLevelName\":\"이상 없음\""))
        assertTrue(json.contains("\"findings\":[]"))
        assertFalse(json.contains("\"notices\""))
    }

    @Test
    fun jsonStringsAreEscaped() {
        assertEquals("\"a\\\"b\\\\c\\nd\"", ServiceReport.str("a\"b\\c\nd"))
    }
}
