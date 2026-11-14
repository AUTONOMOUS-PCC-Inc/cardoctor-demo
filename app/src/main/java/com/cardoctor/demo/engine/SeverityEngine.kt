package com.cardoctor.demo.engine

/**
 * Rule-based judgement. Pure functions only: no Android, no SDK, no network.
 *
 *   urgency score = base severity (1..3) + sum of environment weights
 *   grade         = 4 (score >= 6), 3 (4..5), 2 (2..3), 1 (1), raised to the rule's grade floor
 */
object SeverityEngine {

    fun diagnose(snapshot: VehicleSnapshot, environment: Environment): DiagnosisReport {
        val detection = FaultDetector.detect(snapshot, environment)
        val diagnoses = detection.faults
            .map { judge(it, environment) }
            .sortedWith(compareByDescending<Diagnosis> { it.grade }.thenByDescending { it.score })
        return DiagnosisReport(diagnoses, detection.notices)
    }

    fun judge(fault: DetectedFault, environment: Environment): Diagnosis {
        val weights = weightsFor(fault.rule.group, environment)
        val score = fault.rule.baseSeverity + weights.sumOf { it.points }
        val computed = gradeForScore(score)
        val floor = fault.rule.gradeFloor ?: 0
        return Diagnosis(
            fault = fault,
            weights = weights,
            score = score,
            grade = maxOf(computed, floor),
            floorApplied = floor > computed
        )
    }

    fun gradeForScore(score: Int): Int = when {
        score >= 6 -> 4
        score >= 4 -> 3
        score >= 2 -> 2
        else -> 1
    }

    fun weightsFor(group: PartGroup, environment: Environment): List<AppliedWeight> = buildList {
        if (environment.night && group == PartGroup.LIGHTING) {
            add(AppliedWeight("야간", 3))
        }
        if (environment.badWeather) {
            when (group) {
                PartGroup.WIPER -> add(AppliedWeight("악천후", 3))
                PartGroup.TIRE, PartGroup.BRAKE -> add(AppliedWeight("악천후", 2))
                else -> Unit
            }
        }
        if (environment.highway &&
            group in setOf(PartGroup.TIRE, PartGroup.BRAKE, PartGroup.STEERING)
        ) {
            add(AppliedWeight("고속도로", 1))
        }
        if (environment.longDistance && group in setOf(PartGroup.COOLING, PartGroup.POWER)) {
            add(AppliedWeight("남은 거리 김", 1))
        }
        // A low traction battery matters most when the destination is far (근거리 2등급 → 장거리 3등급).
        if (environment.longDistance && group == PartGroup.ENERGY) {
            add(AppliedWeight("남은 거리 김", 2))
        }
    }
}

/** Turns raw vehicle signals into faults. */
object FaultDetector {

    data class Result(val faults: List<DetectedFault>, val notices: List<String>)

    private val PARKING_BRAKE_TYPES = setOf("PARKING_BRAKE", "ELECTRONIC_PARKING_BRAKE")

    fun detect(snapshot: VehicleSnapshot, environment: Environment = Environment()): Result {
        val lit = snapshot.telltales.orEmpty()
        val byType = lit.associateBy { it.type }
        val faults = mutableListOf<DetectedFault>()
        val notices = mutableListOf<String>()

        // Public-permission signals first: these keep working without PRIVATE_CAR_SAFETY.
        if (environment.night && environment.driving && snapshot.lowBeamSwitchOn == false) {
            faults += DetectedFault(Rules.NIGHT_LIGHTS_OFF, "신호: 야간 주행 중 전조등 스위치 OFF")
        }
        snapshot.evBatteryPercent?.let { soc ->
            if (soc < Rules.LOW_BATTERY_PERCENT) {
                faults += DetectedFault(Rules.LOW_EV_BATTERY, "신호: 구동 배터리 ${soc.toInt()}% (기준 ${Rules.LOW_BATTERY_PERCENT.toInt()}%)")
            }
        }

        val parkingBrake = lit.firstOrNull { it.type in PARKING_BRAKE_TYPES }
        val abs = byType["ABS"]

        // Red brake lamp together with ABS: do not drive (PRD 부록 2, 적색 즉시정차군).
        if (parkingBrake != null && abs != null) {
            faults += DetectedFault(
                Rules.BRAKE_SYSTEM,
                "경고등: 브레이크(${parkingBrake.color.label}) + ABS 동시 점등",
                parkingBrake.color
            )
        } else {
            if (abs != null) faults += fromTelltale(Rules.ABS, abs)
            // P-01: a brake lamp alone usually means the parking brake is still applied.
            if (parkingBrake != null) {
                notices += "주차 브레이크 경고등이 켜져 있습니다. 주차 브레이크를 완전히 해제한 뒤에도 " +
                    "꺼지지 않으면 브레이크 점검이 필요합니다."
            }
        }

        // F1: a measured pressure names the wheel; the TPMS lamp alone cannot.
        val lowTires = snapshot.lowTires
        lowTires.forEach { wheel ->
            val reading = snapshot.tirePressure.getValue(wheel)
            val simulated = if (reading.simulated) " · 시연값" else ""
            faults += DetectedFault(
                Rules.tirePressureAt(wheel),
                "공기압: ${reading.kpa.toInt()}kPa < 기준 ${snapshot.thresholdOf(wheel).toInt()}kPa$simulated",
                byType["TPMS"]?.color
            )
        }

        for ((type, rule) in Rules.BY_TELLTALE) {
            if (type == "ABS") continue
            if (type == "TPMS" && lowTires.isNotEmpty()) continue
            byType[type]?.let { faults += fromTelltale(rule, it) }
        }

        // Derived faults: the switch asks for it but the actuator does not follow.
        if (snapshot.lowBeamSwitchOn == true && snapshot.lowBeamStateOn == false &&
            faults.none { it.rule.id == Rules.HEADLAMP.id }
        ) {
            faults += DetectedFault(Rules.HEADLAMP, "신호: 전조등 스위치 ON, 실제 상태 OFF")
        }
        if (snapshot.wiperSwitchOn == true && snapshot.wiperStateOn == false) {
            faults += DetectedFault(Rules.WIPER, "신호: 와이퍼 스위치 ON, 실제 상태 OFF")
        }

        // The master warning only adds information when nothing more specific is lit.
        byType["MASTER_WARNING"]?.let {
            if (faults.isEmpty()) faults += fromTelltale(Rules.MASTER_WARNING, it)
        }

        return Result(faults, notices)
    }

    private fun fromTelltale(rule: FaultRule, signal: TelltaleSignal) = DetectedFault(
        rule = rule,
        source = "경고등: ${signal.type} (${signal.color.label}${if (signal.blinking) ", 점멸" else ""})",
        lampColor = signal.color
    )
}
