package org.psyhackers.mashia.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import org.psyhackers.mashia.ui.SettingsFragment
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class TtsPlayer(
    ctx: Context,
    private val onStateChanged: ((speaking: Boolean, paused: Boolean) -> Unit)? = null,
    private val onDebug: ((String) -> Unit)? = null,
) : TextToSpeech.OnInitListener {
    private val appCtx = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private val audioManager: AudioManager? =
        appCtx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val audioAttrs: AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    @Volatile private var focusRequest: AudioFocusRequest? = null
    @Volatile private var focusHeld: Boolean = false

    @Volatile private var tts: TextToSpeech? = TextToSpeech(appCtx, this)
    @Volatile private var ready: Boolean = false
    @Volatile private var speaking: Boolean = false
    @Volatile private var paused: Boolean = false
    @Volatile private var lastError: String = ""

    private val lock = Any()
    private var chunks: List<String> = emptyList()
    private var chunkIndex: Int = 0
    private var currentLocale: Locale? = null
    private var lastLoggedVoiceName: String? = null
    private var lastMissingVoiceName: String? = null
    private var lastLangTag: String? = null
    private var lastRate: Float? = null
    private var lastPitch: Float? = null

    private val utterCounter = AtomicInteger(0)

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            ready = false
            lastError = "init failed status=$status"
            log(lastError)
            notifyState()
            return
        }
        val engine = tts
        if (engine == null) {
            ready = false
            lastError = "init: tts null"
            log(lastError)
            notifyState()
            return
        }
        try {
            try { engine.setAudioAttributes(audioAttrs) } catch (_: Throwable) {}
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {
                    speaking = true
                    notifyState()
                }

                override fun onDone(utteranceId: String) {
                    main.post { handleDone() }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) {
                    main.post { handleError("tts error") }
                }

                override fun onError(utteranceId: String, errorCode: Int) {
                    main.post { handleError("tts error code=$errorCode") }
                }

                override fun onStop(utteranceId: String, interrupted: Boolean) {
                    main.post {
                        // stop() is used for pause/stop; keep state as-is.
                        if (!paused) {
                            speaking = false
                            notifyState()
                        }
                    }
                }
            })
        } catch (t: Throwable) {
            log("init listener error: ${t.message}")
        }
        ready = true
        log("ready (engine=" + (try { engine.defaultEngine } catch (_: Throwable) { "?" }) + ")")
        log("net: internet=" + try { hasInternetConnection() } catch (_: Throwable) { false })
        try {
            maybeDisableLegacyForceVoicePrefs()
        } catch (t: Throwable) {
            log("voice legacy-reset error: ${t.message}")
        }
        try {
            migrateLegacyVoicePrefs(engine)
        } catch (t: Throwable) {
            log("voice migrate error: ${t.message}")
        }
        logTtsPrefsSnapshot(engine)
        try {
            logBestVoices(engine)
        } catch (_: Throwable) {}
        notifyState()
        // If speak() was called before init completed, start it now.
        main.post {
            val hasPending = synchronized(lock) { chunks.isNotEmpty() && chunkIndex < chunks.size }
            if (hasPending && !paused) {
                speakNext(flush = true)
            }
        }
    }

    private fun maybeDisableLegacyForceVoicePrefs() {
        val prefs = try { appCtx.getSharedPreferences("settings", 0) } catch (_: Throwable) { null } ?: return
        val force = try { prefs.getBoolean(SettingsFragment.KEY_TTS_FORCE_VOICE, false) } catch (_: Throwable) { false }
        if (!force) return
        val userSet = try { prefs.getBoolean(SettingsFragment.KEY_TTS_FORCE_VOICE_USER_SET, false) } catch (_: Throwable) { false }
        if (userSet) return

        try {
            prefs.edit().putBoolean(SettingsFragment.KEY_TTS_FORCE_VOICE, false).apply()
        } catch (_: Throwable) {}
        lastLangTag = null
        lastLoggedVoiceName = null
        log("voice: force voices was enabled (legacy); using system voices")
    }

    fun isReady(): Boolean = ready
    fun isSpeaking(): Boolean = speaking || (tts?.isSpeaking == true)
    fun isPaused(): Boolean = paused
    fun lastError(): String = lastError

    fun speak(text: String, locale: Locale? = null) {
        val cleaned = text.trim()
        if (cleaned.isBlank()) return
        synchronized(lock) {
            chunks = splitText(cleaned)
            chunkIndex = 0
            currentLocale = locale
            paused = false
        }
        main.post {
            if (!ready) {
                log("speak queued (not ready)")
                notifyState()
                return@post
            }
            speakNext(flush = true)
        }
    }

    fun pause() {
        if (paused) return
        paused = true
        speaking = false
        try { tts?.stop() } catch (_: Throwable) {}
        abandonAudioFocus()
        notifyState()
    }

    fun resume() {
        if (!paused) return
        paused = false
        main.post {
            if (!ready) {
                log("resume ignored (not ready)")
                notifyState()
                return@post
            }
            speakNext(flush = true)
        }
        notifyState()
    }

    fun stop() {
        paused = false
        speaking = false
        synchronized(lock) {
            chunks = emptyList()
            chunkIndex = 0
        }
        try { tts?.stop() } catch (_: Throwable) {}
        abandonAudioFocus()
        notifyState()
    }

    fun shutdown() {
        stop()
        val inst = tts
        tts = null
        ready = false
        try { inst?.shutdown() } catch (_: Throwable) {}
        abandonAudioFocus()
        notifyState()
    }

    private fun handleDone() {
        if (paused) {
            speaking = false
            notifyState()
            return
        }
        val hasNext = synchronized(lock) {
            chunkIndex++
            chunkIndex < chunks.size
        }
        if (hasNext) {
            speakNext(flush = false)
        } else {
            speaking = false
            abandonAudioFocus()
            notifyState()
        }
    }

    private fun handleError(msg: String) {
        lastError = msg
        speaking = false
        abandonAudioFocus()
        notifyState()
        log(msg)
    }

    private fun speakNext(flush: Boolean) {
        if (paused) return
        val engine = tts ?: return
        val (chunk, locale) = synchronized(lock) {
            val c = chunks.getOrNull(chunkIndex) ?: return
            Pair(c, currentLocale)
        }

        if (locale != null) {
            applyUserVoicePrefs(engine, locale)
            logSelectedVoice(engine)
        }

        val utterId = "utt-${SystemClock.uptimeMillis()}-${utterCounter.incrementAndGet()}"
        val queueMode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        requestAudioFocus()
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        val r = try {
            engine.speak(chunk, queueMode, params, utterId)
        } catch (t: Throwable) {
            lastError = "speak exception: ${t.message}"
            log(lastError)
            TextToSpeech.ERROR
        }
        if (r == TextToSpeech.ERROR) {
            lastError = "speak failed"
            speaking = false
            abandonAudioFocus()
        } else {
            speaking = true
        }
        log("speak: id=$utterId q=$queueMode len=${chunk.length} r=$r")
        notifyState()
    }

    private fun logSelectedVoice(engine: TextToSpeech) {
        val v = try { engine.voice } catch (_: Throwable) { null } ?: return
        val name = v.name ?: return
        if (name == lastLoggedVoiceName) return
        lastLoggedVoiceName = name
        val tag = try { v.locale.toLanguageTag() } catch (_: Throwable) { "?" }
        val q = try { v.quality } catch (_: Throwable) { -1 }
        val lat = try { v.latency } catch (_: Throwable) { -1 }
        val net = try { v.isNetworkConnectionRequired } catch (_: Throwable) { false }
        log("voice(using): $name ($tag q=$q lat=$lat net=$net)")
    }

    private fun applyUserVoicePrefs(engine: TextToSpeech, locale: Locale) {
        val prefs = try { appCtx.getSharedPreferences("settings", 0) } catch (_: Throwable) { null } ?: return

        val rate = prefs.getFloat(SettingsFragment.KEY_TTS_RATE, 1.0f).coerceIn(0.1f, 2.5f)
        val pitch = prefs.getFloat(SettingsFragment.KEY_TTS_PITCH, 1.0f).coerceIn(0.1f, 2.5f)
        if (lastRate != rate) {
            lastRate = rate
            try { engine.setSpeechRate(rate) } catch (_: Throwable) {}
        }
        if (lastPitch != pitch) {
            lastPitch = pitch
            try { engine.setPitch(pitch) } catch (_: Throwable) {}
        }

        var effectiveLocale: Locale = locale
        var voiceTarget: Voice? = null

        val lang = normalizeLangCode(locale.language)
        val voices = try { engine.voices } catch (_: Throwable) { null } ?: emptySet()

        val force = prefs.getBoolean(SettingsFragment.KEY_TTS_FORCE_VOICE, false)
        if (!force) {
            val preferNetwork = hasInternetConnection()
            val bestNonLegacy = try {
                val candidates =
                    if (preferNetwork) {
                        voices
                    } else {
                        voices.filter { v -> !isNetworkRequired(v) }.toSet()
                    }
                bestVoiceFor(candidates, effectiveLocale, avoidLegacy = true, preferNetwork = preferNetwork)
                    ?: bestVoiceFor(voices, effectiveLocale, avoidLegacy = true, preferNetwork = preferNetwork)
            } catch (_: Throwable) { null }

            if (bestNonLegacy != null && !isLegacyVoiceName(bestNonLegacy.name)) {
                voiceTarget = bestNonLegacy
                effectiveLocale = try { bestNonLegacy.locale } catch (_: Throwable) { effectiveLocale }
            } else {
                val key = "no-modern:" + (try { effectiveLocale.toLanguageTag() } catch (_: Throwable) { effectiveLocale.toString() })
                if (lastMissingVoiceName != key) {
                    lastMissingVoiceName = key
                    log("voice: no modern TTS voice found for " + (try { effectiveLocale.toLanguageTag() } catch (_: Throwable) { effectiveLocale.toString() }) + " (install/download a better voice in TTS settings)")
                }
            }

            val tag = try { effectiveLocale.toLanguageTag() } catch (_: Throwable) { effectiveLocale.toString() }
            if (!tag.equals(lastLangTag, ignoreCase = true)) {
                try {
                    val r = engine.setLanguage(effectiveLocale)
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                        log("lang not supported: $tag")
                    } else {
                        lastLangTag = tag
                    }
                    lastLoggedVoiceName = null
                } catch (t: Throwable) {
                    log("setLanguage error: ${t.message}")
                }
            }

            val target = voiceTarget
            if (target != null) {
                try {
                    if (engine.voice?.name != target.name) {
                        engine.voice = target
                        lastLoggedVoiceName = null
                    }
                } catch (t: Throwable) {
                    log("voice auto-select error: ${t.message}")
                }
            }
            return
        }

        if (force) {
            val key = when (lang) {
                "es" -> SettingsFragment.KEY_TTS_VOICE_ES
                "en" -> SettingsFragment.KEY_TTS_VOICE_EN
                "he" -> SettingsFragment.KEY_TTS_VOICE_HE
                else -> SettingsFragment.KEY_TTS_VOICE_DEFAULT
            }
            var desired = prefs.getString(key, "")?.trim().orEmpty()
            if (desired.isBlank() && key != SettingsFragment.KEY_TTS_VOICE_DEFAULT) {
                val defName = prefs.getString(SettingsFragment.KEY_TTS_VOICE_DEFAULT, "")?.trim().orEmpty()
                if (defName.isNotBlank()) {
                    val def = voices.firstOrNull { it.name == defName }
                        ?: voices.firstOrNull { it.name.equals(defName, ignoreCase = true) }
                    val defLang = try { def?.locale?.language?.let { normalizeLangCode(it) } } catch (_: Throwable) { null }
                    if (def != null && defLang != null && defLang.equals(lang, ignoreCase = true)) {
                        desired = defName
                    }
                }
            }
            if (desired.isBlank()) {
                val tag = try { effectiveLocale.toLanguageTag() } catch (_: Throwable) { effectiveLocale.toString() }
                if (!tag.equals(lastLangTag, ignoreCase = true)) {
                    try {
                        val r = engine.setLanguage(effectiveLocale)
                        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                            log("lang not supported: $tag")
                        } else {
                            lastLangTag = tag
                        }
                        lastLoggedVoiceName = null
                    } catch (t: Throwable) {
                        log("setLanguage error: ${t.message}")
                    }
                }
                return
            }
            if (desired.isNotBlank()) {
                var target = voices.firstOrNull { it.name == desired }
                    ?: voices.firstOrNull { it.name.equals(desired, ignoreCase = true) }

                if (target != null && isLegacyVoiceName(target.name)) {
                    val upgraded = bestVoiceFor(voices, locale, avoidLegacy = true, preferNetwork = false)
                    val upgradedName = upgraded?.name?.trim().orEmpty()
                    val oldName = target.name?.trim().orEmpty()
                    if (upgraded != null && upgradedName.isNotBlank() && upgradedName != oldName) {
                        log("voice auto-upgrade: $oldName -> $upgradedName (lang=$lang)")
                        try { prefs.edit().putString(key, upgradedName).apply() } catch (_: Throwable) {}
                        target = upgraded
                        lastMissingVoiceName = null
                    }
                }
                if (target != null) {
                    voiceTarget = target
                    effectiveLocale = try { target.locale } catch (_: Throwable) { locale }
                    lastMissingVoiceName = null
                } else {
                    if (lastMissingVoiceName != desired) {
                        lastMissingVoiceName = desired
                        val sample = try {
                            voices.asSequence()
                                .filter { v -> v.locale?.language?.equals(lang, ignoreCase = true) == true }
                                .mapNotNull { v -> v.name?.trim() }
                                .filter { it.isNotBlank() }
                                .take(5)
                                .joinToString(", ")
                        } catch (_: Throwable) { "" }
                        val suffix = if (sample.isBlank()) "" else " sample=[$sample]"
                        log("voice override missing: $desired (lang=$lang locale=" + try { locale.toLanguageTag() } catch (_: Throwable) { locale.toString() } + ")$suffix")
                    }
                    val tag = try { effectiveLocale.toLanguageTag() } catch (_: Throwable) { effectiveLocale.toString() }
                    if (!tag.equals(lastLangTag, ignoreCase = true)) {
                        try {
                            val r = engine.setLanguage(effectiveLocale)
                            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                                log("lang not supported: $tag")
                            } else {
                                lastLangTag = tag
                            }
                            lastLoggedVoiceName = null
                        } catch (t: Throwable) {
                            log("setLanguage error: ${t.message}")
                        }
                    }
                    return
                }
            }
        }

        val tag = try { effectiveLocale.toLanguageTag() } catch (_: Throwable) { effectiveLocale.toString() }
        if (!tag.equals(lastLangTag, ignoreCase = true)) {
            try {
                val r = engine.setLanguage(effectiveLocale)
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    log("lang not supported: $tag")
                } else {
                    lastLangTag = tag
                }
                lastLoggedVoiceName = null
            } catch (t: Throwable) {
                    log("setLanguage error: ${t.message}")
            }
        }

        val target = voiceTarget ?: return
        try {
            if (engine.voice?.name != target.name) {
                engine.voice = target
                lastLoggedVoiceName = null
            }
        } catch (t: Throwable) {
            log("voice override error: ${t.message}")
        }
    }

    private fun migrateLegacyVoicePrefs(engine: TextToSpeech) {
        val prefs = try { appCtx.getSharedPreferences("settings", 0) } catch (_: Throwable) { null } ?: return
        if (!prefs.getBoolean(SettingsFragment.KEY_TTS_FORCE_VOICE, false)) return

        val voices = try { engine.voices } catch (_: Throwable) { null } ?: return
        if (voices.isEmpty()) return

        fun findByName(name: String): Voice? {
            val n = name.trim()
            if (n.isBlank()) return null
            return voices.firstOrNull { it.name == n } ?: voices.firstOrNull { it.name.equals(n, ignoreCase = true) }
        }

        fun upgrade(key: String, desiredLocale: Locale) {
            val old = prefs.getString(key, "")?.trim().orEmpty()
            if (old.isBlank()) return

            val oldVoice = findByName(old)
            val effLocale = try { oldVoice?.locale } catch (_: Throwable) { null } ?: desiredLocale

            if (!isLegacyVoiceName(old)) return

            val best = bestVoiceFor(voices, effLocale, avoidLegacy = true, preferNetwork = false) ?: return
            val bestName = best.name?.trim().orEmpty()
            if (bestName.isBlank() || bestName == old) return

            log("voice auto-upgrade(pref): $old -> $bestName (key=$key)")
            try { prefs.edit().putString(key, bestName).apply() } catch (_: Throwable) {}
        }

        val def = Locale.getDefault()
        upgrade(SettingsFragment.KEY_TTS_VOICE_EN, if (def.language == "en") def else Locale.US)
        upgrade(SettingsFragment.KEY_TTS_VOICE_ES, if (def.language == "es") def else Locale("es", "US"))
        upgrade(SettingsFragment.KEY_TTS_VOICE_HE, Locale("he"))
        upgrade(SettingsFragment.KEY_TTS_VOICE_DEFAULT, def)
    }

    private fun logTtsPrefsSnapshot(engine: TextToSpeech) {
        val prefs = try { appCtx.getSharedPreferences("settings", 0) } catch (_: Throwable) { null } ?: return
        val force = try { prefs.getBoolean(SettingsFragment.KEY_TTS_FORCE_VOICE, false) } catch (_: Throwable) { false }
        val rate = try { prefs.getFloat(SettingsFragment.KEY_TTS_RATE, 1.0f) } catch (_: Throwable) { 1.0f }
        val pitch = try { prefs.getFloat(SettingsFragment.KEY_TTS_PITCH, 1.0f) } catch (_: Throwable) { 1.0f }
        val es = try { prefs.getString(SettingsFragment.KEY_TTS_VOICE_ES, "")?.trim().orEmpty() } catch (_: Throwable) { "" }
        val en = try { prefs.getString(SettingsFragment.KEY_TTS_VOICE_EN, "")?.trim().orEmpty() } catch (_: Throwable) { "" }
        val def = try { prefs.getString(SettingsFragment.KEY_TTS_VOICE_DEFAULT, "")?.trim().orEmpty() } catch (_: Throwable) { "" }
        val dv = try { engine.defaultVoice?.name?.trim().orEmpty() } catch (_: Throwable) { "" }
        log("tts prefs: force=$force rate=$rate pitch=$pitch es=$es en=$en default=$def engineDefaultVoice=$dv")
    }

    private fun splitText(text: String): List<String> {
        // Avoid splitting at every punctuation: TTS adds extra pauses at utterance boundaries.
        // Instead, keep longer chunks and only split when needed (length/newlines).
        val clean = text.trim()
        if (clean.isBlank()) return emptyList()

        val maxLen = 420
        val minSoftBreak = 120
        val parts = mutableListOf<String>()

        var i = 0
        while (i < clean.length) {
            val remaining = clean.length - i
            if (remaining <= maxLen) {
                parts.add(clean.substring(i).trim())
                break
            }

            val end = (i + maxLen).coerceAtMost(clean.length)
            val windowStart = (i + minSoftBreak).coerceAtMost(end)

            fun findBreak(): Int {
                for (j in (end - 1) downTo windowStart) {
                    val ch = clean[j]
                    if (ch == '\n') return j + 1
                }
                for (j in (end - 1) downTo windowStart) {
                    val ch = clean[j]
                    if (ch == '.' || ch == '!' || ch == '?' || ch == ';' || ch == ':' || ch == ',') {
                        return j + 1
                    }
                }
                for (j in (end - 1) downTo i) {
                    if (clean[j].isWhitespace()) return j + 1
                }
                return end
            }

            val splitAt = findBreak().coerceIn(i + 1, clean.length)
            parts.add(clean.substring(i, splitAt).trim())
            i = splitAt
        }

        return parts.filter { it.isNotBlank() }.ifEmpty { listOf(clean) }
    }

    private fun normalizeLangCode(code: String?): String {
        val lc = code?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when (lc) {
            "iw" -> "he"
            else -> lc
        }
    }

    private fun isLegacyVoiceName(name: String?): Boolean {
        return name?.trim()?.endsWith("-language", ignoreCase = true) == true
    }

    private fun isLikelyHighQualityVoiceName(name: String?): Boolean {
        val n = name?.trim().orEmpty()
        if (n.isBlank() || isLegacyVoiceName(n)) return false
        return n.contains("-x-", ignoreCase = true) ||
            n.endsWith("-network", ignoreCase = true) ||
            n.endsWith("-local", ignoreCase = true)
    }

    private fun bestVoiceFor(voices: Set<Voice>, locale: Locale, avoidLegacy: Boolean, preferNetwork: Boolean): Voice? {
        val desiredLang = normalizeLangCode(locale.language)
        val sameLang = voices.filter { v -> normalizeLangCode(v.locale?.language) == desiredLang }
        if (sameLang.isEmpty()) return null

        val withoutLegacy = sameLang.filter { v -> !isLegacyVoiceName(v.name) }
        val forMatch = if (avoidLegacy && withoutLegacy.isNotEmpty()) withoutLegacy else sameLang

        val netCandidates = forMatch.filter { v -> isNetworkRequired(v) }
        val filteredByNet = if (preferNetwork && netCandidates.isNotEmpty()) netCandidates else forMatch

        val exact = filteredByNet.filter {
            (it.locale.country.isNotEmpty() && it.locale.country == locale.country) ||
                (it.locale.toLanguageTag().equals(locale.toLanguageTag(), ignoreCase = true))
        }
        val candidates = if (exact.isNotEmpty()) exact else filteredByNet

        return candidates
            .sortedWith(
                compareByDescending<Voice> { isLikelyHighQualityVoiceName(it.name) }
                    .thenByDescending { it.quality }
                    .thenBy { it.latency }
                    .thenBy { isNetworkRequired(it) }
                    .thenBy { it.name?.trim().orEmpty() }
            )
            .firstOrNull()
    }

    private fun isNetworkRequired(v: Voice): Boolean {
        return try { v.isNetworkConnectionRequired } catch (_: Throwable) { false }
    }

    private fun pickNetworkVariantIfAvailable(target: Voice, voices: Set<Voice>, preferNetwork: Boolean): Voice {
        val name = target.name?.trim().orEmpty()
        if (name.isBlank()) return target

        fun find(name: String): Voice? {
            val n = name.trim()
            if (n.isBlank()) return null
            return voices.firstOrNull { it.name == n } ?: voices.firstOrNull { it.name.equals(n, ignoreCase = true) }
        }

        if (preferNetwork && name.endsWith("-local", ignoreCase = true)) {
            val candidateName = name.dropLast("-local".length) + "-network"
            val v = find(candidateName)
            if (v != null) {
                log("voice variant: $name -> ${v.name} (preferNetwork=true)")
                return v
            }
        }

        if (!preferNetwork && name.endsWith("-network", ignoreCase = true)) {
            val candidateName = name.dropLast("-network".length) + "-local"
            val v = find(candidateName)
            if (v != null) {
                log("voice variant: $name -> ${v.name} (preferNetwork=false)")
                return v
            }
        }

        return target
    }

    private fun hasInternetConnection(): Boolean {
        return try {
            val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Throwable) {
            false
        }
    }

    private fun logBestVoices(engine: TextToSpeech) {
        val voices = try { engine.voices } catch (_: Throwable) { null } ?: return
        val preferNetwork = hasInternetConnection()
        fun fmt(v: Voice?): String {
            if (v == null) return "(none)"
            val name = v.name?.trim().orEmpty()
            val tag = try { v.locale?.toLanguageTag().orEmpty() } catch (_: Throwable) { "" }
            val net = isNetworkRequired(v)
            return "$name ($tag net=$net)"
        }
        val bestEn = bestVoiceFor(voices, Locale.US, avoidLegacy = true, preferNetwork = preferNetwork)
        val bestEs = bestVoiceFor(voices, Locale("es"), avoidLegacy = true, preferNetwork = preferNetwork)
        log("voice(best en): " + fmt(bestEn))
        log("voice(best es): " + fmt(bestEs))
    }

    private fun log(msg: String) {
        onDebug?.invoke(msg)
    }

    private fun notifyState() {
        val cb = onStateChanged ?: return
        main.post { cb.invoke(isSpeaking(), isPaused()) }
    }

    private fun requestAudioFocus() {
        if (focusHeld) return
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(audioAttrs)
                    .setOnAudioFocusChangeListener { }
                    .build()
                focusRequest = req
                val r = am.requestAudioFocus(req)
                focusHeld = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                log("audioFocus: request r=$r held=$focusHeld")
            } else {
                @Suppress("DEPRECATION")
                val r = am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                focusHeld = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                log("audioFocus: request r=$r held=$focusHeld")
            }
        } catch (t: Throwable) {
            log("audioFocus: request error ${t.message}")
        }
    }

    private fun abandonAudioFocus() {
        if (!focusHeld) return
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val req = focusRequest
                if (req != null) {
                    val r = am.abandonAudioFocusRequest(req)
                    log("audioFocus: abandon r=$r")
                }
            } else {
                @Suppress("DEPRECATION")
                val r = am.abandonAudioFocus(null)
                log("audioFocus: abandon r=$r")
            }
        } catch (t: Throwable) {
            log("audioFocus: abandon error ${t.message}")
        } finally {
            focusHeld = false
        }
    }
}
