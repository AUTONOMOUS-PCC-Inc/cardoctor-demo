package com.cardoctor.demo.services

import android.util.Log
import com.cardoctor.demo.BuildConfig
import com.cardoctor.demo.engine.Diagnosis
import com.cardoctor.demo.engine.Environment
import com.cardoctor.demo.engine.Grades
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Driver-facing wording for one diagnosis. The grade itself always comes from the rule engine. */
data class Explanation(
    val summary: String,
    val cause: String,
    val risk: String,
    val action: String,
    /** Short sentence for the spoken warning (F3). */
    val speech: String,
    val byAi: Boolean
)

/**
 * F6: Gemini rewrites the rule engine's result in plain words (원인 · 위험 · 할 일). It never
 * decides or changes the grade; it is told the grade and must keep the action consistent with it.
 * Without a key, or when the call fails, the rule table's own sentences are used.
 */
object GeminiExplainer {

    val enabled: Boolean get() = BuildConfig.GEMINI_API_KEY.isNotBlank()

    /** Fast model first; the larger one only when the first fails. */
    private val MODELS = listOf("gemini-3.5-flash-lite", "gemini-3.5-flash")

    fun template(d: Diagnosis): Explanation {
        val action = Grades.action(d.grade)
        return Explanation(
            summary = "${d.rule.label} · ${Grades.title(d.grade)}",
            cause = d.rule.cause,
            risk = d.rule.risk,
            action = action,
            speech = "${d.rule.label}. ${d.grade}등급 ${Grades.title(d.grade)}입니다. $action",
            byAi = false
        )
    }

    suspend fun explain(d: Diagnosis, env: Environment, weatherLabel: String): Explanation {
        if (!enabled) return template(d)
        val prompt = prompt(d, env, weatherLabel)
        for (model in MODELS) {
            call(model, prompt)?.let { json ->
                return runCatching {
                    val o = JSONObject(json)
                    Explanation(
                        summary = o.getString("summary"),
                        cause = o.getString("cause"),
                        risk = o.getString("risk"),
                        action = o.getString("action"),
                        speech = o.getString("speech"),
                        byAi = true
                    )
                }.getOrElse { template(d) }
            }
        }
        return template(d)
    }

    private fun prompt(d: Diagnosis, env: Environment, weatherLabel: String): String {
        val situation = buildList {
            add(if (env.night) "밤" else "낮")
            add("날씨 $weatherLabel")
            if (env.highway) add("고속도로")
            if (env.longDistance) add("목적지까지 50km 이상")
        }.joinToString(", ")
        val weights = d.weights.joinToString(", ") { "${it.label} +${it.points}" }.ifEmpty { "없음" }
        return """
            너는 차량 정비 안내원이다. 아래 판정은 규칙 엔진이 이미 확정했다. 등급을 바꾸거나 다른 등급을 암시하지 마라.
            고장: ${d.rule.label}
            감지 근거: ${d.fault.source}
            규칙표 설명: ${d.rule.cause} ${d.rule.risk}
            주행 상황: $situation
            판정: ${d.grade}등급 ${Grades.title(d.grade)} (기본 심각도 ${d.rule.baseSeverity}, 가중치 $weights, 점수 ${d.score})
            이 등급의 권고: ${Grades.action(d.grade)}

            운전 중인 운전자가 바로 이해하도록 한국어로, 아래 JSON으로만 답하라.
            {"summary": "주행 중 화면용 한 줄 요약, 25자 이내",
             "cause": "원인 한 문장",
             "risk": "지금 상황에서 위험한 이유 한 문장",
             "action": "권고와 같은 방향의 할 일 한 문장",
             "speech": "음성 안내용 문장, 45자 이내, 등급 숫자를 포함"}
        """.trimIndent()
    }

    private suspend fun call(model: String, prompt: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.3))
            val conn = (URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 6000
                readTimeout = 20000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("x-goog-api-key", BuildConfig.GEMINI_API_KEY)
            }
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode != 200) {
                    Log.w(TAG, "$model HTTP ${conn.responseCode}")
                    return@runCatching null
                }
                JSONObject(conn.inputStream.bufferedReader().readText())
                    .getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
            } finally {
                conn.disconnect()
            }
        }.onFailure { Log.w(TAG, "$model: ${it.message}") }.getOrNull()
    }

    private const val TAG = "GeminiExplainer"
}
