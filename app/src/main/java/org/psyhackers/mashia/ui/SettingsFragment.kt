package org.psyhackers.mashia.ui

import android.os.Bundle
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import org.psyhackers.mashia.R
import org.psyhackers.mashia.util.DebugFileLogger

class SettingsFragment : Fragment() {
    private var btnTtsPickEs: Button? = null
    private var btnTtsPickEn: Button? = null
    private var btnTtsPickDefault: Button? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val switchDark = view.findViewById<Switch>(R.id.switch_dark)
        switchDark.isChecked = true
        switchDark.setOnCheckedChangeListener { _, checked ->
            AppCompatDelegate.setDefaultNightMode(if (checked) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
        }

        val keepAudio = view.findViewById<Switch>(R.id.switch_keep_audio)
        val prefs = requireContext().getSharedPreferences("settings", 0)
        keepAudio.isChecked = prefs.getBoolean(KEY_KEEP_AUDIO, false)
        keepAudio.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_KEEP_AUDIO, checked).apply()
        }

        val debugSwitch = view.findViewById<Switch>(R.id.switch_debug)
        debugSwitch.isChecked = prefs.getBoolean(KEY_DEBUG_ENABLED, true)
        debugSwitch.setOnCheckedChangeListener { _, v ->
            prefs.edit().putBoolean(KEY_DEBUG_ENABLED, v).apply()
            DebugFileLogger.setEnabled(v)
        }

        // Whisper settings
        val useWhisper = view.findViewById<Switch>(R.id.switch_use_whisper)
        useWhisper.isChecked = prefs.getBoolean(KEY_USE_WHISPER, true)
        useWhisper.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean(KEY_USE_WHISPER, v).apply() }

        val cloudStt = view.findViewById<Switch>(R.id.switch_cloud_stt)
        cloudStt.isChecked = prefs.getBoolean(KEY_USE_CLOUD_STT, false)
        cloudStt.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean(KEY_USE_CLOUD_STT, v).apply() }

        val spinnerCloudModel = view.findViewById<Spinner>(R.id.spinner_cloud_stt_model)
        spinnerCloudModel.adapter =
            ArrayAdapter.createFromResource(requireContext(), R.array.cloud_stt_models, android.R.layout.simple_spinner_dropdown_item)
        val curModel = prefs.getString(KEY_CLOUD_STT_MODEL, "auto")?.trim().orEmpty().ifBlank { "auto" }
        spinnerCloudModel.setSelection(cloudModelIndex(curModel))
        spinnerCloudModel.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, v: View?, pos: Int, id: Long) {
                prefs.edit().putString(KEY_CLOUD_STT_MODEL, cloudModelAt(pos)).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        }

        val spinnerGroqChat = view.findViewById<Spinner>(R.id.spinner_groq_chat_model)
        spinnerGroqChat.adapter =
            ArrayAdapter.createFromResource(requireContext(), R.array.groq_chat_models, android.R.layout.simple_spinner_dropdown_item)
        val curChat = prefs.getString(KEY_GROQ_CHAT_MODEL, getString(R.string.groq_model))?.trim().orEmpty().ifBlank { getString(R.string.groq_model) }
        spinnerGroqChat.setSelection(groqChatModelIndex(curChat))
        spinnerGroqChat.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, v: View?, pos: Int, id: Long) {
                prefs.edit().putString(KEY_GROQ_CHAT_MODEL, groqChatModelAt(pos)).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        }

        val micAutoSend = view.findViewById<Switch>(R.id.switch_mic_auto_send)
        micAutoSend.isChecked = prefs.getBoolean(KEY_MIC_AUTO_SEND, true)
        micAutoSend.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean(KEY_MIC_AUTO_SEND, v).apply() }

        val micPreview = view.findViewById<Switch>(R.id.switch_mic_preview)
        micPreview.isChecked = prefs.getBoolean(KEY_MIC_PREVIEW, false)
        micPreview.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean(KEY_MIC_PREVIEW, v).apply() }

        val micHoldToTalk = view.findViewById<Switch>(R.id.switch_mic_hold_to_talk)
        micHoldToTalk.isChecked = prefs.getBoolean(KEY_MIC_HOLD_TO_TALK, true)
        micHoldToTalk.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean(KEY_MIC_HOLD_TO_TALK, v).apply() }

        val micLeft = view.findViewById<Switch>(R.id.switch_mic_left)
        micLeft.isChecked = prefs.getBoolean(KEY_MIC_LEFT, false)
        micLeft.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean(KEY_MIC_LEFT, v).apply() }

        val ttsAutoReply = view.findViewById<Switch>(R.id.switch_tts_auto_reply)
        ttsAutoReply.isChecked = prefs.getBoolean(KEY_TTS_AUTO_REPLY, false)
        ttsAutoReply.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean(KEY_TTS_AUTO_REPLY, v).apply() }

        val ttsAutoTranscript = view.findViewById<Switch>(R.id.switch_tts_auto_transcript)
        ttsAutoTranscript.isChecked = prefs.getBoolean(KEY_TTS_AUTO_TRANSCRIPT, false)
        ttsAutoTranscript.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean(KEY_TTS_AUTO_TRANSCRIPT, v).apply() }

        val ttsForceVoice = view.findViewById<Switch>(R.id.switch_tts_force_voice)
        ttsForceVoice.isChecked = prefs.getBoolean(KEY_TTS_FORCE_VOICE, false)
        ttsForceVoice.setOnCheckedChangeListener { _, v ->
            prefs.edit()
                .putBoolean(KEY_TTS_FORCE_VOICE, v)
                .putBoolean(KEY_TTS_FORCE_VOICE_USER_SET, true)
                .apply()
        }

        view.findViewById<Button>(R.id.btn_tts_use_system).setOnClickListener {
            prefs.edit()
                .putBoolean(KEY_TTS_FORCE_VOICE, false)
                .putBoolean(KEY_TTS_FORCE_VOICE_USER_SET, true)
                .remove(KEY_TTS_VOICE_ES)
                .remove(KEY_TTS_VOICE_EN)
                .remove(KEY_TTS_VOICE_HE)
                .remove(KEY_TTS_VOICE_DEFAULT)
                .apply()
            ttsForceVoice.isChecked = false
            updateTtsVoiceButtons(prefs)
            try { Toast.makeText(requireContext(), "Using system TTS voices", Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
        }

        val editTtsRate = view.findViewById<EditText>(R.id.edit_tts_rate)
        if (editTtsRate.text.isNullOrBlank()) editTtsRate.setText((prefs.getFloat(KEY_TTS_RATE, 1.0f)).toString())
        editTtsRate.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveFloat(prefs, KEY_TTS_RATE, editTtsRate) }

        val editTtsPitch = view.findViewById<EditText>(R.id.edit_tts_pitch)
        if (editTtsPitch.text.isNullOrBlank()) editTtsPitch.setText((prefs.getFloat(KEY_TTS_PITCH, 1.0f)).toString())
        editTtsPitch.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveFloat(prefs, KEY_TTS_PITCH, editTtsPitch) }

        val btnTtsSettings = view.findViewById<Button>(R.id.btn_tts_settings)
        btnTtsSettings.setOnClickListener {
            try {
                val pm = requireContext().packageManager
                val googleVoicePacks = Intent().setClassName(
                    "com.google.android.tts",
                    "com.google.android.apps.speech.tts.googletts.local.voicepack.ui.VoiceDataInstallActivity"
                )
                if (googleVoicePacks.resolveActivity(pm) != null) {
                    startActivity(googleVoicePacks)
                    return@setOnClickListener
                }
            } catch (_: Throwable) {}
            try {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            } catch (_: Throwable) {
                try { startActivity(Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) } catch (_: Throwable) {}
            }
        }

        btnTtsPickEs = view.findViewById<Button>(R.id.btn_tts_pick_es).also { btn ->
            btn.setOnClickListener {
                startActivity(
                    Intent(requireContext(), TtsVoicePickerActivity::class.java)
                        .putExtra(TtsVoicePickerActivity.EXTRA_LANG, "es")
                )
            }
        }
        btnTtsPickEn = view.findViewById<Button>(R.id.btn_tts_pick_en).also { btn ->
            btn.setOnClickListener {
                startActivity(
                    Intent(requireContext(), TtsVoicePickerActivity::class.java)
                        .putExtra(TtsVoicePickerActivity.EXTRA_LANG, "en")
                )
            }
        }
        btnTtsPickDefault = view.findViewById<Button>(R.id.btn_tts_pick_default).also { btn ->
            btn.setOnClickListener {
                startActivity(
                    Intent(requireContext(), TtsVoicePickerActivity::class.java)
                        .putExtra(TtsVoicePickerActivity.EXTRA_LANG, "default")
                )
            }
        }
        updateTtsVoiceButtons(prefs)

        val spinnerStrategy = view.findViewById<Spinner>(R.id.spinner_strategy)
        spinnerStrategy.adapter = ArrayAdapter.createFromResource(requireContext(), R.array.whisper_strategy, android.R.layout.simple_spinner_dropdown_item)
        val strat = prefs.getString(KEY_STRATEGY, "beam")
        spinnerStrategy.setSelection(if (strat == "beam") 1 else 0)
        spinnerStrategy.onItemSelectedListener = object: android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, v: View?, pos: Int, id: Long) {
                val value = if (pos == 1) "beam" else "greedy"
                prefs.edit().putString(KEY_STRATEGY, value).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        }

        val editThreads = view.findViewById<EditText>(R.id.edit_threads)
        if (editThreads.text.isNullOrBlank()) editThreads.setText(prefs.getInt(KEY_THREADS, Runtime.getRuntime().availableProcessors()).toString())
        editThreads.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveInt(prefs, KEY_THREADS, editThreads) }

        val editBeam = view.findViewById<EditText>(R.id.edit_beam_size)
        if (editBeam.text.isNullOrBlank()) editBeam.setText(prefs.getInt(KEY_BEAM, 5).toString())
        editBeam.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveInt(prefs, KEY_BEAM, editBeam) }

        val switchTs = view.findViewById<Switch>(R.id.switch_timestamps)
        switchTs.isChecked = prefs.getBoolean(KEY_ENABLE_TS, false)
        switchTs.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean(KEY_ENABLE_TS, v).apply() }

        val editTemp = view.findViewById<EditText>(R.id.edit_temperature)
        if (editTemp.text.isNullOrBlank()) editTemp.setText((prefs.getFloat(KEY_TEMP, 0.0f)).toString())
        editTemp.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveFloat(prefs, KEY_TEMP, editTemp) }

        val editTempInc = view.findViewById<EditText>(R.id.edit_temperature_inc)
        if (editTempInc.text.isNullOrBlank()) editTempInc.setText((prefs.getFloat(KEY_TEMP_INC, 0.2f)).toString())
        editTempInc.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveFloat(prefs, KEY_TEMP_INC, editTempInc) }

        val spinnerLang = view.findViewById<Spinner>(R.id.spinner_language)
        spinnerLang.adapter = ArrayAdapter.createFromResource(requireContext(), R.array.whisper_languages, android.R.layout.simple_spinner_dropdown_item)
        val lang = prefs.getString(KEY_LANGUAGE, "auto")
        spinnerLang.setSelection(languageIndex(lang))
        spinnerLang.onItemSelectedListener = object: android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, v: View?, pos: Int, id: Long) {
                val code = languageCodeAt(pos)
                prefs.edit().putString(KEY_LANGUAGE, code).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        }

        val editDecInt = view.findViewById<EditText>(R.id.edit_decode_interval)
        if (editDecInt.text.isNullOrBlank()) editDecInt.setText(prefs.getInt(KEY_DECODE_INTERVAL, 750).toString())
        editDecInt.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveInt(prefs, KEY_DECODE_INTERVAL, editDecInt) }

        val editSilTh = view.findViewById<EditText>(R.id.edit_silence_threshold)
        if (editSilTh.text.isNullOrBlank()) editSilTh.setText(prefs.getFloat(KEY_SILENCE_THRESHOLD, 0.01f).toString())
        editSilTh.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveFloat(prefs, KEY_SILENCE_THRESHOLD, editSilTh) }

        val editSilHold = view.findViewById<EditText>(R.id.edit_silence_hold)
        if (editSilHold.text.isNullOrBlank()) editSilHold.setText(prefs.getInt(KEY_SILENCE_HOLD, 1400).toString())
        editSilHold.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveInt(prefs, KEY_SILENCE_HOLD, editSilHold) }
    }

    override fun onResume() {
        super.onResume()
        val prefs = try { requireContext().getSharedPreferences("settings", 0) } catch (_: Throwable) { null } ?: return
        updateTtsVoiceButtons(prefs)
    }

    private fun updateTtsVoiceButtons(prefs: android.content.SharedPreferences) {
        fun labelFor(key: String): String {
            val v = prefs.getString(key, "")?.trim().orEmpty()
            return if (v.isBlank()) "System default" else v
        }
        btnTtsPickEs?.text = "Spanish voice: " + labelFor(KEY_TTS_VOICE_ES)
        btnTtsPickEn?.text = "English voice: " + labelFor(KEY_TTS_VOICE_EN)
        btnTtsPickDefault?.text = "Default voice: " + labelFor(KEY_TTS_VOICE_DEFAULT)
    }

    private fun saveInt(p: android.content.SharedPreferences, key: String, e: EditText) {
        e.text?.toString()?.toIntOrNull()?.let { p.edit().putInt(key, it).apply() }
    }
    private fun saveFloat(p: android.content.SharedPreferences, key: String, e: EditText) {
        e.text?.toString()?.replace(',', '.')?.toFloatOrNull()?.let { p.edit().putFloat(key, it).apply() }
    }

    private fun languageIndex(code: String?): Int = when (code) {
        "auto" -> 0; "en" -> 1; "es" -> 2; "he" -> 3; "pt" -> 4; "de" -> 5; "fr" -> 6; else -> 0
    }
    private fun languageCodeAt(index: Int): String = when (index) {
        0 -> "auto"; 1 -> "en"; 2 -> "es"; 3 -> "he"; 4 -> "pt"; 5 -> "de"; 6 -> "fr"; else -> "auto"
    }

    private fun cloudModelIndex(code: String?): Int = when (code?.trim()?.lowercase()) {
        "auto" -> 0
        "groq_turbo" -> 1
        "groq_v3" -> 2
        "openai_whisper1" -> 3
        else -> 0
    }

    private fun cloudModelAt(index: Int): String = when (index) {
        0 -> "auto"
        1 -> "groq_turbo"
        2 -> "groq_v3"
        3 -> "openai_whisper1"
        else -> "auto"
    }

    private fun groqChatModelIndex(code: String?): Int = when (code?.trim()?.lowercase()) {
        "llama-3.1-8b-instant" -> 0
        "llama-3.2-11b-vision-preview" -> 1
        "llama-3.2-90b-vision-preview" -> 2
        else -> 0
    }

    private fun groqChatModelAt(index: Int): String = when (index) {
        0 -> "llama-3.1-8b-instant"
        1 -> "llama-3.2-11b-vision-preview"
        2 -> "llama-3.2-90b-vision-preview"
        else -> "llama-3.1-8b-instant"
    }

    companion object {
        const val KEY_KEEP_AUDIO = "keep_audio_transcripts"
        const val KEY_USE_WHISPER = "use_whisper"
        const val KEY_STRATEGY = "whisper_strategy" // greedy|beam
        const val KEY_THREADS = "whisper_threads"
        const val KEY_BEAM = "whisper_beam_size"
        const val KEY_ENABLE_TS = "whisper_enable_timestamps"
        const val KEY_TEMP = "whisper_temperature"
        const val KEY_TEMP_INC = "whisper_temperature_inc"
        const val KEY_LANGUAGE = "whisper_language" // auto|en|es|he|pt|de|fr
        const val KEY_DECODE_INTERVAL = "whisper_decode_interval"
        const val KEY_SILENCE_THRESHOLD = "whisper_silence_threshold"
        const val KEY_SILENCE_HOLD = "whisper_silence_hold"
        const val KEY_USE_CLOUD_STT = "use_cloud_stt"
        const val KEY_CLOUD_STT_MODEL = "cloud_stt_model"
        const val KEY_GROQ_CHAT_MODEL = "groq_chat_model"
        const val KEY_QUALITY_DEFAULTS_APPLIED = "whisper_quality_defaults_applied_v1"
        const val KEY_TTS_AUTO_REPLY = "tts_auto_reply"
        const val KEY_TTS_AUTO_TRANSCRIPT = "tts_auto_transcript"
        const val KEY_TTS_FORCE_VOICE = "tts_force_voice"
        const val KEY_TTS_FORCE_VOICE_USER_SET = "tts_force_voice_user_set"
        const val KEY_TTS_RATE = "tts_rate"
        const val KEY_TTS_PITCH = "tts_pitch"
        const val KEY_TTS_VOICE_ES = "tts_voice_es"
        const val KEY_TTS_VOICE_EN = "tts_voice_en"
        const val KEY_TTS_VOICE_HE = "tts_voice_he"
        const val KEY_TTS_VOICE_DEFAULT = "tts_voice_default"
        const val KEY_MIC_AUTO_SEND = "mic_auto_send"
        const val KEY_MIC_PREVIEW = "mic_preview_before_send"
        const val KEY_MIC_HOLD_TO_TALK = "mic_hold_to_talk"
        const val KEY_MIC_LEFT = "mic_left"
        const val KEY_LIBRARY_INCLUDE_SUBFOLDERS = "library_include_subfolders"
        const val KEY_DEBUG_ENABLED = "debug_enabled"
    }
}
