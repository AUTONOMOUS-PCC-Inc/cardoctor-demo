package com.cardoctor.demo.engine

/**
 * Severity rule table. Base severities and grade floors follow PRD 부록 / 부록 2;
 * entries the PRD does not list are marked "초안" and need team review.
 */
object Rules {

    val HEADLAMP = FaultRule(
        id = "headlamp",
        label = "전조등 이상",
        group = PartGroup.LIGHTING,
        part = CarPart.HEADLAMPS,
        baseSeverity = 1,
        cause = "전조등(LED 헤드램프)에 이상이 감지됐습니다.",
        risk = "밝을 때는 운행에 지장이 적지만, 어두울 때는 시야 확보가 어렵고 상대 차량이 내 차를 보기 어렵습니다."
    )

    val WIPER = FaultRule(
        id = "wiper",
        label = "와이퍼 작동 불량",
        group = PartGroup.WIPER,
        part = CarPart.WIPERS,
        baseSeverity = 1,
        cause = "와이퍼 스위치를 켰지만 와이퍼가 움직이지 않습니다.",
        risk = "맑은 날에는 급하지 않지만, 비나 눈이 오면 앞이 보이지 않아 위험합니다."
    )

    val TIRE_PRESSURE = FaultRule(
        id = "tire_pressure",
        label = "타이어 공기압 저하",
        group = PartGroup.TIRE,
        part = CarPart.TIRES,
        baseSeverity = 2,
        cause = "타이어 공기압 경고등(TPMS)이 켜졌습니다.",
        risk = "공기압이 낮으면 제동 거리가 길어지고, 빗길이나 고속 주행에서 미끄러지거나 파손될 수 있습니다."
    )

    /** The same rule, named for one wheel when its pressure is known (F1). */
    fun tirePressureAt(wheel: Wheel): FaultRule = TIRE_PRESSURE.copy(
        id = "tire_pressure_${wheel.name.lowercase()}",
        label = "${wheel.label} 타이어 공기압 저하",
        cause = "${wheel.label} 타이어 공기압이 경고 기준보다 낮습니다."
    )

    val ABS = FaultRule(
        id = "abs",
        label = "ABS 경고",
        group = PartGroup.BRAKE,
        part = CarPart.BRAKES,
        baseSeverity = 2,
        cause = "ABS(잠김 방지 제동 장치) 경고등이 켜졌습니다.",
        risk = "일반 제동은 되지만 급제동 때 바퀴가 잠길 수 있어, 미끄러운 길에서 특히 위험합니다."
    )

    val ESC = FaultRule(
        id = "esc",
        label = "차체 자세 제어(ESC) 경고",
        group = PartGroup.BRAKE,
        part = CarPart.BRAKES,
        baseSeverity = 2, // 초안
        cause = "차체 자세 제어 장치(ESC) 경고등이 켜졌습니다.",
        risk = "미끄러운 길이나 급한 조향에서 차가 미끄러지는 것을 잡아주지 못할 수 있습니다."
    )

    val BRAKE_SYSTEM = FaultRule(
        id = "brake_system",
        label = "브레이크 시스템 경고",
        group = PartGroup.BRAKE,
        part = CarPart.BRAKES,
        baseSeverity = 3,
        gradeFloor = 4,
        cause = "브레이크 경고등과 ABS 경고등이 함께 켜졌습니다.",
        risk = "제동 계통에 결함이 있을 수 있습니다. 점검 전까지 주행하면 안 됩니다."
    )

    val STEERING = FaultRule(
        id = "steering",
        label = "조향 보조(MDPS) 경고",
        group = PartGroup.STEERING,
        part = CarPart.STEERING,
        baseSeverity = 3, // 초안
        gradeFloor = 3,
        cause = "전동식 조향 보조 장치(MDPS) 경고등이 켜졌습니다.",
        risk = "핸들이 갑자기 무거워질 수 있어 방향 전환이 어려워집니다."
    )

    val OVERHEAT = FaultRule(
        id = "overheat",
        label = "과열 경고",
        group = PartGroup.COOLING,
        part = CarPart.MOTOR,
        baseSeverity = 3,
        gradeFloor = 3,
        cause = "구동 계통 온도 경고등이 켜졌습니다.",
        risk = "계속 주행하면 구동 계통이 손상되어 큰 수리로 이어질 수 있습니다."
    )

    val OIL_PRESSURE = FaultRule(
        id = "oil_pressure",
        label = "엔진 오일 압력 경고",
        group = PartGroup.POWER,
        part = CarPart.MOTOR,
        baseSeverity = 3,
        gradeFloor = 4,
        cause = "엔진 오일 압력 경고등이 켜졌습니다.",
        risk = "윤활이 되지 않으면 엔진이 곧바로 손상됩니다. 즉시 시동을 꺼야 합니다."
    )

    val CHARGING = FaultRule(
        id = "charging",
        label = "저전압 배터리(충전) 경고",
        group = PartGroup.POWER,
        part = CarPart.BATTERY,
        baseSeverity = 3,
        gradeFloor = 3,
        cause = "12V 배터리 충전 경고등이 켜졌습니다.",
        risk = "배터리가 방전되면 주행 중 전장 장치가 꺼지거나 다시 시동이 걸리지 않을 수 있습니다."
    )

    val EV_SYSTEM = FaultRule(
        id = "ev_system",
        label = "전기차 시스템 경고",
        group = PartGroup.POWER,
        part = CarPart.BATTERY,
        baseSeverity = 3, // 초안
        gradeFloor = 3,
        cause = "전기차 구동 시스템 경고등이 켜졌습니다.",
        risk = "고전압 배터리나 구동 모터에 이상이 있을 수 있어 주행이 갑자기 제한될 수 있습니다."
    )

    val POWER_DOWN = FaultRule(
        id = "power_down",
        label = "출력 제한 경고",
        group = PartGroup.POWER,
        part = CarPart.BATTERY,
        baseSeverity = 3, // 초안
        gradeFloor = 3,
        cause = "출력 제한 경고등이 켜졌습니다.",
        risk = "가속이 제한되어 합류나 추월이 어려워집니다."
    )

    val AIRBAG = FaultRule(
        id = "airbag",
        label = "에어백 경고",
        group = PartGroup.SAFETY,
        part = CarPart.CABIN,
        baseSeverity = 2, // 초안
        cause = "에어백 경고등이 켜졌습니다.",
        risk = "주행은 가능하지만 사고가 났을 때 에어백이 작동하지 않을 수 있습니다."
    )

    val MASTER_WARNING = FaultRule(
        id = "master_warning",
        label = "통합 경고",
        group = PartGroup.GENERAL,
        part = CarPart.WHOLE,
        baseSeverity = 2, // 초안
        cause = "통합 경고등이 켜졌습니다.",
        risk = "차량에 점검이 필요한 항목이 있습니다. 계기판 안내를 함께 확인하세요."
    )

    /** Telltale type (SDK TelltaleType name) -> rule. Indicator-only lamps are not listed. */
    val BY_TELLTALE: Map<String, FaultRule> = linkedMapOf(
        "TPMS" to TIRE_PRESSURE,
        "LED_HEADLAMP" to HEADLAMP,
        "ABS" to ABS,
        "ESC" to ESC,
        "MDPS" to STEERING,
        "ENGINE_TEMPERATURE" to OVERHEAT,
        "ENGINE_OIL_PRESSURE" to OIL_PRESSURE,
        "LOW_VOLT_BATTERY" to CHARGING,
        "EV_SYSTEM" to EV_SYSTEM,
        "POWER_DOWN" to POWER_DOWN,
        "AIRBAG" to AIRBAG
    )
}
