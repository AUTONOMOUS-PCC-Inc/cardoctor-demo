package com.cardoctor.demo.engine

/**
 * F5 정비소 사전 리포트. Packs what the engine already decided into the team's report format
 * (PRD_정비소_사전리포트.md, schemaVersion 1) so a repair shop can check the grade, not just read it.
 *
 * Pure Kotlin (no Android, no org.json) so it is unit-testable. The report never changes a grade:
 * it copies the engine's result as is.
 */
object ServiceReport {

    const val SCHEMA_VERSION = 1
    const val DISCLAIMER = "등급은 규칙 기반으로 산정됐으며 참고용입니다. 최종 판단은 정비 전문가에게 받으십시오."

    data class Context(
        val generatedAt: String,
        val environment: Environment,
        /** True when any environment value was forced from the demo chips. */
        val environmentSimulated: Boolean,
        /** Where bad weather came from, e.g. "날씨 API: 비" or "와이퍼 작동". */
        val weatherLabel: String
    )

    fun build(report: DiagnosisReport, snapshot: VehicleSnapshot, ctx: Context): String {
        val worst = report.diagnoses.firstOrNull()
        val sb = StringBuilder()
        sb.append('{')
        field(sb, "schemaVersion", SCHEMA_VERSION); sb.append(',')
        field(sb, "generatedAt", ctx.generatedAt); sb.append(',')
        field(sb, "app", "카닥터 AI"); sb.append(',')

        sb.append("\"vehicle\":{")
        sb.append("\"tires\":[")
        sb.append(Wheel.entries.joinToString(",") { w ->
            val r = snapshot.tirePressure[w]
            "{\"wheel\":${str(w.label)},\"kpa\":${r?.kpa?.toInt() ?: "null"}," +
                "\"thresholdKpa\":${snapshot.thresholdOf(w).toInt()},\"simulated\":${r?.simulated ?: false}}"
        })
        sb.append("]},")

        sb.append("\"environment\":{")
        field(sb, "daylight", if (ctx.environment.night) "밤" else "낮"); sb.append(',')
        field(sb, "weather", ctx.weatherLabel); sb.append(',')
        field(sb, "roadType", if (ctx.environment.highway) "고속도로" else "일반도로"); sb.append(',')
        field(sb, "simulated", ctx.environmentSimulated)
        sb.append("},")

        sb.append("\"summary\":{")
        field(sb, "totalFindings", report.diagnoses.size); sb.append(',')
        field(sb, "worstLevel", worst?.grade ?: 0); sb.append(',')
        field(sb, "worstLevelName", worst?.let { Grades.title(it.grade) } ?: "이상 없음"); sb.append(',')
        field(sb, "recommendation", worst?.let { Grades.action(it.grade) } ?: "점검한 항목에서 이상이 발견되지 않았습니다")
        sb.append("},")

        sb.append("\"findings\":[")
        sb.append(report.diagnoses.joinToString(",") { d -> finding(d) })
        sb.append("],")

        if (report.notices.isNotEmpty()) {
            sb.append("\"notices\":[").append(report.notices.joinToString(",") { str(it) }).append("],")
        }
        field(sb, "disclaimer", DISCLAIMER)
        sb.append('}')
        return sb.toString()
    }

    private fun finding(d: Diagnosis): String {
        val sb = StringBuilder("{")
        field(sb, "part", d.rule.part.label); sb.append(',')
        field(sb, "title", d.rule.label); sb.append(',')
        field(sb, "evidence", d.fault.source); sb.append(',')
        field(sb, "detectionPattern", detectionPattern(d.fault.source)); sb.append(',')
        field(sb, "baseSeverity", d.rule.baseSeverity); sb.append(',')
        sb.append("\"appliedWeights\":[")
        sb.append(d.weights.joinToString(",") { "{\"reason\":${str(it.label)},\"points\":${it.points}}" })
        sb.append("],")
        field(sb, "urgencyScore", d.score); sb.append(',')
        field(sb, "adviceLevel", d.grade); sb.append(',')
        field(sb, "adviceLevelName", Grades.title(d.grade)); sb.append(',')
        field(sb, "recommendation", Grades.action(d.grade)); sb.append(',')
        field(sb, "safetyFloorApplied", d.floorApplied)
        sb.append('}')
        return sb.toString()
    }

    /** PRD 감지 방식 values, inferred from how the engine phrased the evidence. */
    fun detectionPattern(source: String): String = when {
        source.startsWith("공기압") -> "임계값 초과"
        source.startsWith("신호:") -> "명령·실제 불일치"
        source.contains("동시 점등") -> "상태 조합"
        source.startsWith("경고등") -> "경고등 점등"
        else -> "상태 조합"
    }

    /** Short plain-text version for people: shown next to the QR code. */
    fun summaryText(report: DiagnosisReport): String {
        if (report.diagnoses.isEmpty()) return "이상 없음 · 점검한 항목에서 이상이 발견되지 않았습니다."
        return report.diagnoses.joinToString("\n") { d ->
            val weights = d.weights.joinToString("") { " + ${it.label} ${it.points}" }
            "· ${d.rule.label}: ${d.grade}등급 ${Grades.title(d.grade)} (기본 ${d.rule.baseSeverity}$weights = ${d.score}점)"
        }
    }

    private fun field(sb: StringBuilder, name: String, value: String) { sb.append(str(name)).append(':').append(str(value)) }
    private fun field(sb: StringBuilder, name: String, value: Int) { sb.append(str(name)).append(':').append(value) }
    private fun field(sb: StringBuilder, name: String, value: Boolean) { sb.append(str(name)).append(':').append(value) }

    /** JSON string literal with the escapes JSON requires. */
    fun str(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append(String.format("\\u%04x", c.code))
            else -> sb.append(c)
        }
        return sb.append('"').toString()
    }
}
