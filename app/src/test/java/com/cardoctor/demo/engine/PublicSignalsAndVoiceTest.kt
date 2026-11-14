package com.cardoctor.demo.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rules that run on public-permission signals only (gear, EV battery, headlamp switch) and the voice parser. */
class PublicSignalsAndVoiceTest {

    @Test
    fun inMotionGearOnlyForMovingGears() {
        assertTrue(VehicleSnapshot(gear = "DRIVE").inMotionGear)
        assertTrue(VehicleSnapshot(gear = "REVERSE").inMotionGear)
        assertFalse(VehicleSnapshot(gear = "PARK").inMotionGear)
        assertFalse(VehicleSnapshot(gear = "NEUTRAL").inMotionGear)
        assertFalse(VehicleSnapshot().inMotionGear)
    }

    @Test
    fun headlampsOffAtNightIsFlaggedOnlyWhileDriving() {
        val snapshot = VehicleSnapshot(telltales = emptyList(), lowBeamSwitchOn = false)
        val night = Environment(night = true)
        assertTrue(SeverityEngine.diagnose(snapshot, night).diagnoses.isEmpty())

        val d = SeverityEngine.diagnose(snapshot, night.copy(driving = true)).diagnoses.single()
        assertEquals("night_lights_off", d.rule.id)
        assertTrue(d.grade >= 2)
        assertTrue(SeverityEngine.diagnose(snapshot, Environment(driving = true)).diagnoses.isEmpty())
        val lightsOn = snapshot.copy(lowBeamSwitchOn = true)
        assertTrue(SeverityEngine.diagnose(lightsOn, night.copy(driving = true)).diagnoses.isEmpty())
    }

    @Test
    fun lowEvBatteryGetsMoreUrgentOnLongTrips() {
        val snapshot = VehicleSnapshot(telltales = emptyList(), evBatteryPercent = 9f)
        val near = SeverityEngine.diagnose(snapshot, Environment()).diagnoses.single()
        assertEquals("low_ev_battery", near.rule.id)
        assertEquals(2, near.grade)
        assertTrue(near.fault.source.contains("9%"))
        val far = SeverityEngine.diagnose(snapshot, Environment(longDistance = true)).diagnoses.single()
        assertTrue(far.grade > near.grade)
        assertTrue(SeverityEngine.diagnose(snapshot.copy(evBatteryPercent = 60f), Environment()).diagnoses.isEmpty())
    }

    @Test
    fun voiceQuestionsMapToCommands() {
        assertEquals(VoiceCommand.STATUS, VoiceCommands.parse("지금 정비소 가야 돼?"))
        assertEquals(VoiceCommand.ROUTE_TO_SHOP, VoiceCommands.parse("정비소 안내해줘"))
        assertEquals(VoiceCommand.EXPLAIN, VoiceCommands.parse("왜 위험해?"))
        assertEquals(VoiceCommand.REPORT, VoiceCommands.parse("리포트 보여줘"))
        assertEquals(VoiceCommand.UNKNOWN, VoiceCommands.parse("노래 틀어줘"))
        assertEquals(VoiceCommand.UNKNOWN, VoiceCommands.parse("  "))
    }

    @Test
    fun statusAnswerLeadsWithGradeAndAction() {
        val ok = SeverityEngine.diagnose(VehicleSnapshot(telltales = emptyList()), Environment())
        assertTrue(VoiceCommands.statusAnswer(ok, Environment()).contains("없습니다"))

        val snapshot = VehicleSnapshot(telltales = emptyList(), evBatteryPercent = 9f)
        val env = Environment(driving = true)
        val answer = VoiceCommands.statusAnswer(SeverityEngine.diagnose(snapshot, env), env)
        assertTrue(answer, answer.startsWith("구동 배터리") || answer.contains("2등급"))
        assertTrue(answer, answer.contains("2등급"))
    }
}
