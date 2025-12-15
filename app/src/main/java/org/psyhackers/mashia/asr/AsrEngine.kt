package org.psyhackers.mashia.asr

interface AsrEngine {
    fun isReady(): Boolean
    fun transcribeShortPcm(pcm: ShortArray, sampleRate: Int = 16000): String?
    fun applyPrefs(prefs: android.content.SharedPreferences)
}

