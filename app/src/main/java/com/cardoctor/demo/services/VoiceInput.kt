package com.cardoctor.demo.services

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.util.Log
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class VoiceState(
    val available: Boolean = false,
    val listening: Boolean = false,
    val transcript: String? = null,
    val reply: String? = null
)

/**
 * Voice questions through Pleos SpeechToText: one utterance per [listen]; the final transcript
 * goes to [onFinal]. Works while driving — asking by voice is the safe way to get details.
 */
class VoiceInput(context: Context, private val onFinal: (String) -> Unit) {

    private val stt = runCatching { SpeechToText(context.applicationContext, Mode.ON_DEVICE) }.getOrNull()
    private val _state = MutableStateFlow(VoiceState())
    val state: StateFlow<VoiceState> = _state

    private val listener = object : ResultListener {
        override fun onUpdated(text: String, isFinal: Boolean) {
            // Only while a question is open: never act on our own spoken answer or late results.
            if (!_state.value.listening) return
            _state.update { it.copy(transcript = text) }
            if (isFinal && text.isNotBlank()) {
                cancel()
                onFinal(text)
            }
        }
        override fun onUpdatedEpdData(start: Long, end: Long) = Unit
        override fun onStartedRecognition() = _state.update { it.copy(listening = true) }
        override fun onEndedRecognition() = _state.update { it.copy(listening = false) }
        override fun onError(type: ErrorType) {
            Log.w(TAG, "STT error: $type")
            _state.update { it.copy(listening = false, reply = it.reply ?: "음성을 인식하지 못했습니다. 다시 말씀해 주세요.") }
        }
        override fun onReady() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
    }

    fun start() {
        val s = stt ?: return
        try {
            s.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    runCatching { s.setLocale(Locale.KOREA) }
                    runCatching { s.addListener(listener) }
                    _state.update { it.copy(available = true) }
                }
                override fun onFailure(type: ErrorType) { Log.w(TAG, "STT init failed: $type") }
            })
        } catch (e: Exception) {
            Log.w(TAG, "STT unavailable: ${e.message}")
        }
    }

    fun listen() {
        val s = stt
        if (s == null || !_state.value.available) {
            _state.update { it.copy(listening = false, transcript = null, reply = "음성 인식에 연결되지 않았습니다. DEMO 패널의 질문 버튼을 쓰세요.") }
            return
        }
        _state.update { it.copy(listening = true, transcript = null, reply = null) }
        runCatching { s.request() }.onFailure { _state.update { st -> st.copy(listening = false) } }
        // One question per tap; the mic never stays open.
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, LISTEN_TIMEOUT_MS)
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val timeout = Runnable {
        if (_state.value.listening) {
            cancel()
            _state.update { it.copy(reply = it.reply ?: "질문을 듣지 못했습니다. 마이크를 다시 눌러 주세요.") }
        }
    }

    /** Close the microphone (before speaking an answer, on timeout, after a final result). */
    fun cancel() {
        main.removeCallbacks(timeout)
        if (_state.value.listening) runCatching { stt?.stop() }
        _state.update { it.copy(listening = false) }
    }

    /** Typed question (DEMO panel) or the answer to a spoken one. */
    fun show(transcript: String, reply: String) {
        cancel()
        _state.update { it.copy(transcript = transcript, reply = reply, listening = false) }
    }

    fun dismiss() {
        cancel()
        _state.update { it.copy(transcript = null, reply = null) }
    }

    fun stop() {
        main.removeCallbacks(timeout)
        runCatching { stt?.stop(); stt?.removeListener(listener); stt?.release() }
    }

    private companion object {
        const val TAG = "VoiceInput"
        const val LISTEN_TIMEOUT_MS = 8_000L
    }
}
