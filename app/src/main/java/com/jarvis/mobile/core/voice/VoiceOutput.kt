package com.jarvis.mobile.core.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import com.jarvis.mobile.data.settings.SettingsRepository
import com.jarvis.mobile.util.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/** Local TTS output (spec: VOICE OUTPUT). Concise status speech, never a narrator. */
class VoiceOutput(context: Context, settings: SettingsRepository) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var enabled = true

    init {
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                runCatching { tts?.language = Locale.getDefault() }
                Logx.i("tts", "TTS ready (${tts?.defaultEngine})")
            } else {
                Logx.w("tts", "TTS init failed: $status")
            }
        }
        CoroutineScope(Dispatchers.Default).launch {
            settings.voiceOutput.collect { enabled = it }
        }
    }

    fun isReady(): Boolean = ready

    fun speak(text: String) {
        if (!enabled || !ready) return
        val trimmed = text.take(220)
        runCatching { tts?.speak(trimmed, TextToSpeech.QUEUE_ADD, null, "jarvis-${System.currentTimeMillis()}") }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}
