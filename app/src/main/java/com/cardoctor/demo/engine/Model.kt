package com.cardoctor.demo.engine

/** Part families that share environment weights (PRD 부록 "환경 가중치"). */
enum class PartGroup { LIGHTING, WIPER, TIRE, BRAKE, STEERING, COOLING, POWER, SAFETY, GENERAL }

/** Areas of the car diagram that can be highlighted. */
enum class CarPart(val label: String) {
    HEADLAMPS("전조등"),
    WIPERS("와이퍼"),
    TIRES("타이어"),
    BRAKES("제동 장치"),
    STEERING("조향 장치"),
    MOTOR("구동·냉각 계통"),
    BATTERY("배터리·전원"),
    CABIN("실내 안전장치"),
    WHOLE("차량 전체")
}

enum class LampColor(val label: String) {
    WHITE("흰색"), GREEN("녹색"), BLUE("파란색"), YELLOW("황색"), RED("적색"), GRAY("회색"), UNKNOWN("알 수 없음");

    companion object {
        /** Colour codes observed from Safety.getTelltalesInfo() on Pleos Connect v2.1. */
        fun fromCode(code: Int): LampColor = when (code) {
            0 -> WHITE
            1 -> GREEN
            2 -> BLUE
            3 -> YELLOW
            4 -> RED
            5 -> GRAY
            else -> UNKNOWN
        }
    }
}

/** One lit telltale, independent of the SDK types so the engine stays unit-testable. */
data class TelltaleSignal(
    val type: String,
    val blinking: Boolean = false,
    val color: LampColor = LampColor.UNKNOWN
)

/** The four wheels, in the order the tire tiles are drawn. */
enum class Wheel(val label: String) {
    LEFT_FRONT("앞 왼쪽"), RIGHT_FRONT("앞 오른쪽"), LEFT_REAR("뒤 왼쪽"), RIGHT_REAR("뒤 오른쪽")
}

/** One wheel's tire pressure in kPa, and whether it came from the demo panel instead of a sensor. */
data class TireReading(val kpa: Float, val simulated: Boolean = false)

/** Everything the app reads from the vehicle in one refresh. Null = not available. */
data class VehicleSnapshot(
    val telltales: List<TelltaleSignal>? = null,
    val lowBeamSwitchOn: Boolean? = null,
    val lowBeamStateOn: Boolean? = null,
    val wiperSwitchOn: Boolean? = null,
    val wiperStateOn: Boolean? = null,
    /** Per-wheel pressure. Missing wheel = no sensor value (the v2.1 emulator has none). */
    val tirePressure: Map<Wheel, TireReading> = emptyMap(),
    /** Per-wheel low-pressure threshold (CRITICALLY_LOW_TIRE_PRESSURE), kPa. */
    val tireThreshold: Map<Wheel, Float> = emptyMap()
) {
    /** Threshold to judge a wheel against: the vehicle's own value, else a typical passenger-car one. */
    fun thresholdOf(wheel: Wheel): Float = tireThreshold[wheel] ?: DEFAULT_LOW_TIRE_KPA

    /** Wheels whose known pressure is below their threshold. */
    val lowTires: List<Wheel>
        get() = Wheel.entries.filter { w -> tirePressure[w]?.let { it.kpa < thresholdOf(w) } == true }

    companion object {
        const val DEFAULT_LOW_TIRE_KPA = 137f
    }
}

data class Environment(
    val night: Boolean = false,
    val badWeather: Boolean = false,
    val highway: Boolean = false,
    val longDistance: Boolean = false
)

data class FaultRule(
    val id: String,
    val label: String,
    val group: PartGroup,
    val part: CarPart,
    val baseSeverity: Int,
    /** Minimum grade regardless of environment (PRD 부록 2). */
    val gradeFloor: Int? = null,
    val cause: String,
    val risk: String
)

data class DetectedFault(
    val rule: FaultRule,
    /** Where the fault came from, shown as evidence. */
    val source: String,
    val lampColor: LampColor? = null
)

data class AppliedWeight(val label: String, val points: Int)

data class Diagnosis(
    val fault: DetectedFault,
    val weights: List<AppliedWeight>,
    val score: Int,
    val grade: Int,
    val floorApplied: Boolean
) {
    val rule: FaultRule get() = fault.rule
}

data class DiagnosisReport(
    val diagnoses: List<Diagnosis>,
    /** Non-fault remarks, e.g. the parking-brake precheck (P-01). */
    val notices: List<String>
) {
    val overallGrade: Int get() = diagnoses.maxOfOrNull { it.grade } ?: 0

    fun gradeOf(part: CarPart): Int =
        diagnoses.filter { it.rule.part == part }.maxOfOrNull { it.grade } ?: 0
}

object Grades {
    fun title(grade: Int): String = when (grade) {
        4 -> "즉시 정차"
        3 -> "즉시 정비"
        2 -> "목적지 후 정비"
        1 -> "여유 있게 정비"
        else -> "정상"
    }

    fun action(grade: Int): String = when (grade) {
        4 -> "안전한 곳에 정차하고 견인·긴급출동을 부르세요."
        3 -> "가장 가까운 정비소로 바로 가세요."
        2 -> "목적지까지는 운행할 수 있습니다. 오늘 중 점검을 권장합니다."
        1 -> "주말이나 다음 정기점검 때 정비소를 방문하세요."
        else -> "감지된 이상이 없습니다."
    }
}
