package com.cardoctor.demo.engine

/** What the driver asked by voice. Keyword rules keep it predictable and offline. */
enum class VoiceCommand { STATUS, ROUTE_TO_SHOP, REPORT, EXPLAIN, UNKNOWN }

object VoiceCommands {

    /** The questions offered as buttons where there is no microphone (emulator demo). */
    val SAMPLES = listOf("지금 정비소 가야 돼?", "왜 위험해?", "정비소 안내해줘", "리포트 보여줘")

    private val ROUTE = listOf("정비소", "카센터", "길안내", "안내해", "데려다")
    private val REPORT = listOf("리포트", "보고서", "견적", "QR")
    private val EXPLAIN = listOf("왜", "이유", "무슨뜻", "뭐야", "설명")
    private val STATUS = listOf("가야", "가도", "괜찮", "위험", "상태", "어때", "문제", "고장", "점검")

    fun parse(text: String): VoiceCommand {
        val t = text.replace(" ", "").ifBlank { return VoiceCommand.UNKNOWN }
        fun has(words: List<String>) = words.any { t.contains(it, ignoreCase = true) }
        return when {
            has(ROUTE) && !t.contains("가야") -> VoiceCommand.ROUTE_TO_SHOP
            has(REPORT) -> VoiceCommand.REPORT
            has(EXPLAIN) -> VoiceCommand.EXPLAIN
            has(STATUS) -> VoiceCommand.STATUS
            else -> VoiceCommand.UNKNOWN
        }
    }

    /** "지금 가야 돼?": grade first, then why, then the one action. */
    fun statusAnswer(report: DiagnosisReport, env: Environment): String {
        val top = report.diagnoses.firstOrNull()
            ?: return "지금은 점검이 필요한 항목이 없습니다. 안심하고 운행하세요."
        val head = "${top.rule.label}, ${top.grade}등급 ${Grades.title(top.grade)}입니다."
        val why = top.weights.firstOrNull()?.let { " ${it.label} 조건 때문에 더 급합니다." } ?: ""
        val action = when (top.grade) {
            4 -> " 비상등을 켜고 안전한 곳에 정차하세요."
            3 -> " 가장 가까운 정비소로 안내할까요? '정비소 안내해줘'라고 말씀하세요."
            2 -> if (env.driving) " 목적지까지는 가셔도 됩니다." else " 오늘 중에 점검하세요."
            else -> " 급하지 않습니다. 주말에 점검하세요."
        }
        val others = report.diagnoses.size - 1
        val tail = if (others > 0) " 그 밖에 ${others}개 항목이 더 있습니다." else ""
        return head + why + action + tail
    }

    const val UNKNOWN_ANSWER = "잘 못 알아들었어요. '지금 가야 돼?', '정비소 안내해줘', '왜 위험해?'처럼 말씀해 주세요."
    const val REPORT_WHILE_DRIVING = "리포트는 정차한 뒤에 화면으로 보여 드릴게요."
}
