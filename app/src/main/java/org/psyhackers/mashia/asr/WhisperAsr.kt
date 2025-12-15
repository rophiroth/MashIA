package org.psyhackers.mashia.asr

import android.content.Context
import org.psyhackers.mashia.stt.WhisperEngine

class WhisperAsr(context: Context, threads: Int = 3): AsrEngine {
    private val engine = WhisperEngine(context)
    private var ready = false

    init {
        // Prefer base for quality; fallback to tiny if needed.
        ready = engine.initBase(translateToEnglish = false, threads = threads)
    }

    override fun isReady(): Boolean = ready

    override fun transcribeShortPcm(pcm: ShortArray, sampleRate: Int): String? =
        engine.transcribeShortPcm(pcm, sampleRate)

    override fun applyPrefs(prefs: android.content.SharedPreferences) {
        engine.applyPrefs(prefs)
    }

    fun lastError(): String = engine.lastNativeError()
    fun lastModelName(): String? = engine.lastModelName()

    fun configure(strategyBeam: Boolean = true, beam: Int = 5, lang: String = "auto", enableTimestamps: Boolean = false) {
        engine.setConfig(strategyBeam, beam, enableTimestamps, 0.0f, 0.2f, lang)
    }

    fun reinitTinyEn(threads: Int = 3): Boolean {
        // Prefer tiny.en if present; fallback to tiny
        val ok = engine.initSpecificAsset("models/ggml-tiny.en.bin", translateToEnglish = false, threads = threads)
        ready = ok || ready
        return ok
    }
}
