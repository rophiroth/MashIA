package org.psyhackers.mashia.ui

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.psyhackers.mashia.R
import java.util.Locale

class TtsVoicePickerActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private lateinit var recycler: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var search: EditText

    private lateinit var adapter: VoicesAdapter

    private var lang: String = "default"
    private var all: List<VoiceRow> = emptyList()
    private var filtered: List<VoiceRow> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tts_voice_picker)

        lang = intent.getStringExtra(EXTRA_LANG)?.trim().orEmpty().ifBlank { "default" }

        supportActionBar?.title = when (lang) {
            "es" -> "Spanish voice"
            "en" -> "English voice"
            "he" -> "Hebrew voice"
            else -> "Default voice"
        }
        supportActionBar?.subtitle = "Tap to select · ▶ to preview"

        recycler = findViewById(R.id.recycler_voices)
        progress = findViewById(R.id.progress_loading)
        status = findViewById(R.id.txt_status)
        search = findViewById(R.id.edit_search)

        adapter = VoicesAdapter(
            onSelect = { row ->
                saveSelection(row.name)
                finish()
            },
            onPreview = { row ->
                preview(row.name)
            }
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        findViewById<View>(R.id.btn_use_system_default).setOnClickListener {
            saveSelection("", setForceVoice = null)
            finish()
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilter()
            }
        })

        showLoading(true, "Loading voices…")
        tts = TextToSpeech(this, this)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Throwable) {}
        tts = null
    }

    override fun onInit(statusCode: Int) {
        if (statusCode != TextToSpeech.SUCCESS) {
            showLoading(false, "TTS init failed (status=$statusCode)")
            return
        }
        val engine = tts ?: run {
            showLoading(false, "TTS not available")
            return
        }
        val voices = try { engine.voices } catch (_: Throwable) { null }
        if (voices == null) {
            showLoading(false, "No voices exposed by engine")
            return
        }

        val rows = voices
            .mapNotNull { v -> v.toRowOrNull() }
            .filter { row ->
                when (lang) {
                    "es" -> row.language == "es"
                    "en" -> row.language == "en"
                    "he" -> row.language == "he" || row.language == "iw"
                    else -> true
                }
            }
            .sortedWith(
                compareBy<VoiceRow> { it.isLegacy }
                    .thenByDescending { it.quality }
                    .thenBy { it.latency }
                    .thenBy { it.networkRequired }
                    .thenBy { it.localeTag }
                    .thenBy { it.name }
            )

        all = rows
        applyFilter()

        showLoading(false, if (rows.isEmpty()) "No voices found for this language" else "${rows.size} voices")
    }

    private fun showLoading(loading: Boolean, msg: String) {
        progress.visibility = if (loading) View.VISIBLE else View.GONE
        status.text = msg
        recycler.visibility = if (loading) View.GONE else View.VISIBLE
    }

    private fun applyFilter() {
        val q = search.text?.toString()?.trim()?.lowercase(Locale.getDefault()).orEmpty()
        filtered =
            if (q.isBlank()) {
                all
            } else {
                all.filter { r ->
                    r.name.lowercase(Locale.getDefault()).contains(q) ||
                        r.localeTag.lowercase(Locale.getDefault()).contains(q) ||
                        r.language.lowercase(Locale.getDefault()).contains(q)
                }
            }
        adapter.submit(
            filtered,
            selectedName = currentSelectionName()
        )
    }

    private fun prefs(): android.content.SharedPreferences {
        return getSharedPreferences("settings", 0)
    }

    private fun selectionKey(): String = when (lang) {
        "es" -> SettingsFragment.KEY_TTS_VOICE_ES
        "en" -> SettingsFragment.KEY_TTS_VOICE_EN
        "he", "iw" -> SettingsFragment.KEY_TTS_VOICE_HE
        else -> SettingsFragment.KEY_TTS_VOICE_DEFAULT
    }

    private fun currentSelectionName(): String {
        return prefs().getString(selectionKey(), "")?.trim().orEmpty()
    }

    private fun saveSelection(name: String, setForceVoice: Boolean? = true) {
        val clean = name.trim()
        val editor = prefs().edit().putString(selectionKey(), clean)
        if (setForceVoice != null) {
            editor.putBoolean(SettingsFragment.KEY_TTS_FORCE_VOICE, setForceVoice)
            if (setForceVoice) {
                editor.putBoolean(SettingsFragment.KEY_TTS_FORCE_VOICE_USER_SET, true)
            }
        }
        editor.apply()
    }

    private fun preview(voiceName: String) {
        val engine = tts ?: return
        val target = voiceName.trim()
        if (target.isBlank()) return

        val voice = try { engine.voices?.firstOrNull { it.name == target } } catch (_: Throwable) { null }
        if (voice == null) return

        try { engine.stop() } catch (_: Throwable) {}
        try { engine.language = voice.locale } catch (_: Throwable) {}
        try { engine.voice = voice } catch (_: Throwable) {}

        val prefs = prefs()
        val rate = prefs.getFloat(SettingsFragment.KEY_TTS_RATE, 1.0f).coerceIn(0.1f, 2.5f)
        val pitch = prefs.getFloat(SettingsFragment.KEY_TTS_PITCH, 1.0f).coerceIn(0.1f, 2.5f)
        try { engine.setSpeechRate(rate) } catch (_: Throwable) {}
        try { engine.setPitch(pitch) } catch (_: Throwable) {}

        val sample = when (lang) {
            "es" -> "Hola. Esta es una prueba de voz."
            "en" -> "Hello. This is a voice test."
            "he" -> "שלום. זו בדיקת קול."
            else -> "This is a voice test."
        }
        try {
            engine.speak(sample, TextToSpeech.QUEUE_FLUSH, null, "preview")
        } catch (_: Throwable) {}
    }

    private data class VoiceRow(
        val name: String,
        val localeTag: String,
        val language: String,
        val quality: Int,
        val latency: Int,
        val networkRequired: Boolean,
        val isLegacy: Boolean,
    )

    private fun isLegacyVoiceName(name: String): Boolean {
        return name.trim().endsWith("-language", ignoreCase = true)
    }

    private fun Voice.toRowOrNull(): VoiceRow? {
        val n = name?.trim().orEmpty()
        if (n.isBlank()) return null
        val loc = locale ?: return null
        val tag = try { loc.toLanguageTag() } catch (_: Throwable) { loc.toString() }
        val q = try { quality } catch (_: Throwable) { -1 }
        val lat = try { latency } catch (_: Throwable) { -1 }
        val net = try { isNetworkConnectionRequired } catch (_: Throwable) { false }
        return VoiceRow(
            name = n,
            localeTag = tag,
            language = loc.language ?: "",
            quality = q,
            latency = lat,
            networkRequired = net,
            isLegacy = isLegacyVoiceName(n),
        )
    }

    private class VoicesAdapter(
        private val onSelect: (VoiceRow) -> Unit,
        private val onPreview: (VoiceRow) -> Unit,
    ) : RecyclerView.Adapter<VoiceVH>() {
        private var items: List<VoiceRow> = emptyList()
        private var selectedName: String = ""

        fun submit(items: List<VoiceRow>, selectedName: String) {
            this.items = items
            this.selectedName = selectedName.trim()
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VoiceVH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_tts_voice, parent, false)
            return VoiceVH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VoiceVH, position: Int) {
            val row = items[position]
            val isSelected = selectedName.isNotBlank() && row.name == selectedName
            holder.bind(row, isSelected, onSelect, onPreview)
        }
    }

    private class VoiceVH(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.txt_voice_title)
        private val meta: TextView = view.findViewById(R.id.txt_voice_meta)
        private val btnPreview: ImageButton = view.findViewById(R.id.btn_voice_preview)

        fun bind(
            row: VoiceRow,
            selected: Boolean,
            onSelect: (VoiceRow) -> Unit,
            onPreview: (VoiceRow) -> Unit,
        ) {
            title.text = row.name
            val net = if (row.networkRequired) "net" else "local"
            val legacy = if (row.isLegacy) " · legacy" else ""
            meta.text = "${row.localeTag} · q=${row.quality} · lat=${row.latency} · $net$legacy"

            itemView.alpha = if (selected) 1.0f else 0.88f
            itemView.setOnClickListener { onSelect(row) }
            btnPreview.setOnClickListener { onPreview(row) }
        }
    }

    companion object {
        const val EXTRA_LANG = "lang"
    }
}
