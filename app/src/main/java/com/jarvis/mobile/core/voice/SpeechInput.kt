package com.jarvis.mobile.core.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.jarvis.mobile.util.Logx
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Local voice input via Android SpeechRecognizer.
 * Requests the on-device recognizer where available (EXTRA_PREFER_OFFLINE);
 * falls back to whatever the device provides and reports honestly.
 */
class SpeechInput(private val context: Context) {

    sealed class VoiceEvent {
        data class Partial(val text: String) : VoiceEvent()
        data class Final(val text: String) : VoiceEvent()
        data class Error(val code: Int, val message: String) : VoiceEvent()
        data object Listening : VoiceEvent()
        data object Ended : VoiceEvent()
    }

    private val _events = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_LATEST)
    val events: SharedFlow<VoiceEvent> = _events

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening

    private var recognizer: SpeechRecognizer? = null

    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun onDeviceOnly(): Boolean = runCatching {
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    }.getOrDefault(false)

    fun start() {
        if (!available()) {
            _events.tryEmit(VoiceEvent.Error(-1, "Speech recognition is not available on this device."))
            return
        }
        stop()
        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _listening.value = true
                _events.tryEmit(VoiceEvent.Listening)
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                _listening.value = false
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "I didn't catch that. Try again."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
                    SpeechRecognizer.ERROR_NETWORK -> "Network recognition failed (no offline recognizer available)."
                    else -> "Speech error $error"
                }
                _events.tryEmit(VoiceEvent.Error(error, msg))
                _events.tryEmit(VoiceEvent.Ended)
            }
            override fun onResults(results: Bundle?) {
                _listening.value = false
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) _events.tryEmit(VoiceEvent.Final(text))
                _events.tryEmit(VoiceEvent.Ended)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                // Framework sends partials under the same results key.
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) _events.tryEmit(VoiceEvent.Partial(text))
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        Logx.i("voice", "Listening (onDevice=${onDeviceOnly()})")
        sr.startListening(intent)
    }

    fun stop() {
        recognizer?.destroy()
        recognizer = null
        _listening.value = false
    }
}
