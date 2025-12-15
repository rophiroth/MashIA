package org.psyhackers.mashia.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Base64
import java.util.Locale
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import android.graphics.Color
import android.graphics.BitmapFactory
import android.view.MotionEvent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.psyhackers.mashia.R
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import com.google.mlkit.nl.languageid.LanguageIdentification
import org.psyhackers.mashia.asr.AsrEngine
import org.psyhackers.mashia.asr.CloudAsr
import org.psyhackers.mashia.asr.WhisperAsr
import org.psyhackers.mashia.tts.TtsPlayer
import org.psyhackers.mashia.voice.VoiceController
import org.psyhackers.mashia.util.ConversationStore
import org.psyhackers.mashia.util.DebugFileLogger

data class Message(val text: String, val isMe: Boolean)

class ChatFragment : Fragment() {
    companion object {
        private const val STATE_MSG_TEXTS = "state_msg_texts"
        private const val STATE_MSG_ISME = "state_msg_isme"
        private const val STATE_DRAFT = "state_draft"
        private const val HISTORY_SAVE_DELAY_MS = 900L
        private const val ARG_PREFILL_TEXT = "arg_prefill_text"

        fun newInstance(prefillText: String? = null): ChatFragment {
            return ChatFragment().apply {
                if (!prefillText.isNullOrBlank()) {
                    arguments = Bundle().apply { putString(ARG_PREFILL_TEXT, prefillText) }
                }
            }
        }
    }

    private val messages = mutableListOf<Message>()
    private lateinit var adapter: MessagesAdapter
    private var isListening = false
    private var continuousMode = false
    private var currentTranscript = StringBuilder()
    private var speech: android.speech.SpeechRecognizer? = null
    private var lastVoiceIndex: Int = -1
    private var micButton: ImageButton? = null
    private var whisperDecoding = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private val saveHistoryRunnable = Runnable { saveChatHistoryNow() }
    private var conversation: ConversationStore.Conversation? = null
    private var pendingImageDataUrl: String? = null
    private var pendingImageLabel: String? = null
    private var pickingImage: Boolean = false
    private var pulseUp = true
    private val TAG = "ChatFragment"
    private var appCtx: android.content.Context? = null
    private var tts: TtsPlayer? = null
    private var ttsButton: ImageButton? = null
    private var micDownAt: Long = 0L
    private var micPressWillStopOnUp: Boolean = false
    private var micPressStartedSession: Boolean = false
    private var micHoldMode: Boolean = false
    private var micHoldUsesWhisper: Boolean = false
    private val micHoldThresholdMs: Long = 220L
    private val micHoldActivateRunnable = object : Runnable {
        override fun run() {
            if (!micPressStartedSession || micHoldMode) return
            micHoldMode = true
            if (micHoldUsesWhisper) {
                try {
                    val prefs = requireContext().getSharedPreferences("settings", 0)
                    applyVoiceRuntimePrefs(prefs, forHold = true)
                } catch (_: Throwable) {}
            }
        }
    }
    private val http by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
        val logging = HttpLoggingInterceptor { msg -> Log.d(TAG, msg) }
        logging.level = HttpLoggingInterceptor.Level.BODY
        builder.addInterceptor(logging)
        builder.build()
    }
    // Language ID (on-device) for auto-switch
    private val langId by lazy { LanguageIdentification.getClient() }
    private var currentLangTag: String? = Locale.getDefault().toLanguageTag() // e.g., es-ES, en-US, he-IL
    private var lastDetectedLang: String? = null // es, en, he
    private val allowedLangs = setOf("es", "en", "he")
    private var lastSwitchAt: Long = 0
    private val switchCooldownMs: Long = 4000
    private var whisperReady: Boolean = false
    private var asr: AsrEngine? = null
    private var voice: VoiceController? = null
    @Volatile private var whisperInitToken: Int = 0
    @Volatile private var whisperInitializing: Boolean = false
    private var pcmChunks = mutableListOf<ShortArray>()
    private var lastDecodeAt = 0L
    private var silentMs = 0L
    private var decodeIntervalMs = 900L
    private var silenceThreshold = 0.03f // RMS
    private var silenceHoldMs = 900L
    private var startedAt = 0L
    private var lastTextAt = 0L
    private var maxUtteranceMs = 8000L
    private var decoding = false
    private var micChunkCount = 0
    private var lastChunkLogAt = 0L

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_chat, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        DebugFileLogger.init(requireContext())
        appCtx = requireContext().applicationContext
        val list = view.findViewById<RecyclerView>(R.id.messages_list)
        val whisperStatus = view.findViewById<android.widget.TextView>(R.id.whisper_status)
        val input = view.findViewById<EditText>(R.id.input_text)
        val send = view.findViewById<ImageButton>(R.id.btn_send)
        val attachFile = view.findViewById<ImageButton>(R.id.btn_attach)
        val camera = view.findViewById<ImageButton>(R.id.btn_camera)
        val mic = view.findViewById<ImageButton>(R.id.btn_mic)
        val ttsBtn = view.findViewById<ImageButton>(R.id.btn_tts)
        val libraryBtn = view.findViewById<ImageButton>(R.id.btn_library)
        val btnNewChat = view.findViewById<ImageButton>(R.id.btn_new_chat)
        val btnMore = view.findViewById<ImageButton>(R.id.btn_chat_more)
        val chatTitle = view.findViewById<TextView>(R.id.chat_title)
        ttsButton = ttsBtn
        micButton = mic
        // Idle style: white icon on dark background
        try {
            mic.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            mic.background?.setTint(Color.parseColor("#263238"))
        } catch (_: Throwable) {}

        // Setup list + adapter first (avoid crashes if we log before)
        try {
            val texts = savedInstanceState?.getStringArrayList(STATE_MSG_TEXTS)
            val isMe = savedInstanceState?.getBooleanArray(STATE_MSG_ISME)
            if (texts != null && isMe != null && texts.size == isMe.size) {
                messages.clear()
                for (i in texts.indices) {
                    messages.add(Message(texts[i], isMe[i]))
                }
            }
            val draft = savedInstanceState?.getString(STATE_DRAFT)
            if (draft != null && input.text.isNullOrBlank()) {
                input.setText(draft)
            }
        } catch (_: Throwable) {}
        try {
            val prefill = arguments?.getString(ARG_PREFILL_TEXT)?.trim().orEmpty()
            if (prefill.isNotBlank() && input.text.isNullOrBlank()) {
                input.setText(prefill)
            }
        } catch (_: Throwable) {}
        if (messages.isEmpty()) {
            try {
                val c = ConversationStore.loadOrCreateCurrent(requireContext())
                conversation = c
                messages.addAll(c.messages.map { Message(it.text, it.isMe) })
                chatTitle.text = formatConversationTitle(c)
            } catch (_: Throwable) {}
        }
        if (chatTitle.text.isNullOrBlank()) {
            chatTitle.text = conversation?.let { formatConversationTitle(it) } ?: "Chat"
        }
        adapter = MessagesAdapter(
            messages,
            onSpeak = { msg -> speakText(msg.text) },
            onEdit = { pos -> showEditMessageDialog(pos) },
        )
        list.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        list.adapter = adapter
        try {
            if (messages.isNotEmpty()) list.scrollToPosition(messages.lastIndex)
        } catch (_: Throwable) {}
        // Show last crash (if any) to avoid blind debugging
        try {
            val f = java.io.File(requireContext().filesDir, "last_crash.txt")
            if (f.exists()) {
                val txt = f.readText()
                addDebug("crash: \n" + txt.take(800))
                f.delete()
            }
        } catch (_: Throwable) {}
        try {
            val loc = requireContext().filesDir.absolutePath + "/diag_log.txt"
            addDebug("logger: escribiendo en $loc (últimas ~50KB)")
        } catch (_: Throwable) {}
        // Show runtime app version for clarity
        addDebug("app: v" + appVersion())

        // Text-to-speech (playback of transcripts/replies)
        val ctxForTts = appCtx
        if (ctxForTts != null) {
            tts = TtsPlayer(
                ctxForTts,
                onStateChanged = { speaking, paused -> updateTtsButton(speaking, paused) },
                onDebug = { msg -> addDebug("tts: $msg") },
            )
        }
        ttsBtn.setOnClickListener {
            val player = tts ?: return@setOnClickListener
            if (player.isSpeaking()) {
                player.pause()
            } else if (player.isPaused()) {
                player.resume()
            } else {
                val enabled = toggleAutoSpeakReplies()
                try {
                    Toast.makeText(
                        requireContext(),
                        if (enabled) "Auto-voz: ON" else "Auto-voz: OFF",
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (_: Throwable) {}
            }
            updateTtsButton(player.isSpeaking(), player.isPaused())
        }
        ttsBtn.setOnLongClickListener {
            val player = tts
            if (player != null && (player.isSpeaking() || player.isPaused())) {
                player.stop()
                updateTtsButton(player.isSpeaking(), player.isPaused())
            } else {
                openTtsSettings()
            }
            true
        }
        updateTtsButton()

        btnNewChat.setOnClickListener { confirmNewChat() }
        btnMore.setOnClickListener { showChatMoreMenu() }

        libraryBtn.setOnClickListener {
            try {
                requireActivity().supportFragmentManager.commit {
                    replace(R.id.fragment_container, LibraryFragment())
                }
            } catch (_: Throwable) {}
        }

        // Init transcription engine (local or cloud depending on settings).
        initSttEngine(whisperStatus)

        // Long-press Whisper status: mic self-test (debug)
        whisperStatus.setOnLongClickListener {
            try { startMicSelfTest() } catch (_: Throwable) {}
            true
        }

        send.setOnClickListener {
            val txt = input.text.toString().trim()
            val img = pendingImageDataUrl
            if (txt.isBlank() && img.isNullOrBlank()) return@setOnClickListener

            val label = if (!img.isNullOrBlank()) (pendingImageLabel ?: "image") else null
            val shown = buildString {
                if (!label.isNullOrBlank()) append("🖼️ ").append(label)
                if (txt.isNotBlank()) {
                    if (isNotEmpty()) append("\n")
                    append(txt)
                }
            }.trim()
            addMessage(shown, true)
            input.setText("")
            pendingImageDataUrl = null
            pendingImageLabel = null
            updateAttachButton(attachFile)

            sendToBackend(txt, null, imageDataUrl = img)
        }

        attachFile.setOnClickListener {
            if (pendingImageDataUrl != null) {
                pendingImageDataUrl = null
                pendingImageLabel = null
                updateAttachButton(attachFile)
                try { Toast.makeText(requireContext(), "Attachment cleared", Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
                return@setOnClickListener
            }
            pickImageFromGallery()
        }
        attachFile.setOnLongClickListener {
            pickImageFromGallery()
            true
        }
        updateAttachButton(attachFile)
        camera.setOnClickListener { requestPermissionCompat(Manifest.permission.CAMERA) }

        // Mic modes (Settings): tap, tap-to-stop, hold-to-talk
        mic.setOnTouchListener { v, ev ->
            if (whisperDecoding) return@setOnTouchListener true

            val ctx = context ?: return@setOnTouchListener true
            val prefs = ctx.getSharedPreferences("settings", 0)
            val holdEnabled = prefs.getBoolean(SettingsFragment.KEY_MIC_HOLD_TO_TALK, true)
            val autoSend = prefs.getBoolean(SettingsFragment.KEY_MIC_AUTO_SEND, true)
            val useWhisper = prefs.getBoolean(SettingsFragment.KEY_USE_WHISPER, true)

            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    micDownAt = ev.eventTime.toLong()
                    micPressWillStopOnUp = isListening
                    micPressStartedSession = false
                    micHoldMode = false
                    micHoldUsesWhisper = false
                    uiHandler.removeCallbacks(micHoldActivateRunnable)

                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissionCompat(Manifest.permission.RECORD_AUDIO)
                        return@setOnTouchListener true
                    }

                    if (!isListening) {
                        if (whisperReady && useWhisper) {
                            micHoldUsesWhisper = true
                            applyVoiceRuntimePrefs(prefs, forHold = false)
                            addDebug("whisper: start (tap autoSend=$autoSend)")
                            startListeningWhisper(continuous = false)
                        } else {
                            addDebug("speech: start (tap autoSend=$autoSend)")
                            startListening(continuous = true)
                        }
                        micPressStartedSession = true
                    }

                    if (holdEnabled && micPressStartedSession) {
                        uiHandler.postDelayed(micHoldActivateRunnable, micHoldThresholdMs)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    uiHandler.removeCallbacks(micHoldActivateRunnable)
                    val heldMs = (ev.eventTime.toLong() - micDownAt).coerceAtLeast(0L)
                    val isHoldPress = holdEnabled && micPressStartedSession && heldMs >= micHoldThresholdMs
                    if (isHoldPress) micHoldMode = true

                    val shouldStop = micPressWillStopOnUp || isHoldPress
                    if (shouldStop && isAdded && isListening) {
                        if (whisperReady && useWhisper) {
                            addDebug("whisper: stop+decode (" + (if (isHoldPress) "hold" else "tap") + ")")
                            setWhisperDecoding(true)
                            if (lastVoiceIndex >= 0 && lastVoiceIndex < messages.size) {
                                messages[lastVoiceIndex] = Message("?? . (transcribiendo.)", true)
                                adapter.notifyItemChanged(lastVoiceIndex)
                                scheduleSaveChatHistory()
                            }
                            voice?.stopAndFinalize(if (isHoldPress) "hold" else "tap")
                        } else {
                            stopListening(sendNow = true)
                        }
                    }

                    micDownAt = 0L
                    micPressWillStopOnUp = false
                    micPressStartedSession = false
                    micHoldMode = false
                    micHoldUsesWhisper = false
                    true
                }

                else -> true
            }
        }

        mic.setOnClickListener(null)
    }

    // Records ~3s of PCM and runs a single decode to isolate capture vs decoding
    private fun startMicSelfTest() {
        addDebug("selftest: start (~3s)")
        val acc = ArrayList<Short>()
        setListeningUI(true)
        var stopped = false
        var tester: org.psyhackers.mashia.stt.MicRecorder? = null
        tester = org.psyhackers.mashia.stt.MicRecorder(16000, onChunk = { chunk ->
            if (stopped) return@MicRecorder
            acc.addAll(chunk.asList())
        }, onReady = { src, rate, buf ->
            addDebug("selftest: mic source=$src rate=$rate buf=$buf")
        })
        try {
            tester.start()
            // Stop after ~3.2s wall-clock regardless of device sample rate
            uiHandler.postDelayed({
                if (!stopped) {
                    stopped = true
                    try { tester?.stop() } catch (_: Throwable) {}
                    setListeningUI(false)
                    addDebug("selftest: captured ${acc.size} samples (~${(acc.size/16000.0f)}s @16k)")
                    Thread {
                        val engine = asr
                        if (engine == null || !engine.isReady()) {
                            addDebug("selftest: stt not ready")
                            return@Thread
                        }
                        try {
                            val wa = (engine as? org.psyhackers.mashia.asr.WhisperAsr)
                            // Try to reinit with tiny.en for a deterministic self‑test
                            if (wa != null) {
                                val okTiny = wa.reinitTinyEn(threads = 3)
                                addDebug("selftest: model=" + (if (okTiny) (wa.lastModelName() ?: "tiny.en") else (wa.lastModelName() ?: "?")))
                                wa.configure(strategyBeam = true, beam = 5, lang = "en", enableTimestamps = false)
                            }
                        } catch (_: Throwable) {}
                        val pcm = ShortArray(acc.size)
                        for (i in acc.indices) pcm[i] = acc[i]
                        val txt = engine.transcribeShortPcm(pcm, 16000).orEmpty()
                        uiHandler.post {
                            if (txt.isBlank()) addDebug("selftest: decode empty") else addDebug("selftest: text=${txt.take(160)}")
                            try {
                                val diag = when (engine) {
                                    is org.psyhackers.mashia.asr.WhisperAsr -> engine.lastError()
                                    is org.psyhackers.mashia.asr.CloudAsr -> engine.lastError()
                                    else -> ""
                                }
                                if (diag.isNotBlank()) addDebug("stt: $diag")
                            } catch (_: Throwable) {}
                        }
                    }.start()
                }
            }, 3200)
        } catch (e: Throwable) {
            setListeningUI(false)
            addDebug("selftest: start error ${e.message}")
        }
    }

    private fun addMessage(text: String, isMe: Boolean) {
        messages.add(Message(text, isMe))
        adapter.notifyItemInserted(messages.lastIndex)
        try {
            val ctx = (appCtx ?: context)?.applicationContext
            if (ctx != null) {
                val c0 = conversation ?: ConversationStore.loadOrCreateCurrent(ctx).also { conversation = it }
                conversation = ConversationStore.appendMessage(c0, text, isMe)
            }
        } catch (_: Throwable) {}
        scheduleSaveChatHistory()
    }

    private fun confirmNewChat() {
        val ctx = context ?: return
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("New chat")
            .setMessage("Start a new conversation? (This one stays in Library.)")
            .setPositiveButton("New") { _, _ ->
                try {
                    val app = appCtx ?: return@setPositiveButton
                    // Persist current first
                    saveChatHistoryNow()
                    val created = ConversationStore.newConversation()
                    ConversationStore.updateConversation(app, created)
                    ConversationStore.setCurrentId(app, created.id)
                    conversation = created
                    messages.clear()
                    adapter.notifyDataSetChanged()
                    val t = view?.findViewById<TextView>(R.id.chat_title)
                    if (t != null) t.text = formatConversationTitle(created)
                    scheduleSaveChatHistory()
                } catch (_: Throwable) {}
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showChatMoreMenu() {
        val ctx = context ?: return
        val opts = arrayOf("Edit chat (folder/tags/icon)", "Library", "Settings", "Share chat", "Delete chat")
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Chat")
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> showEditChatDialog()
                    1 -> {
                        try {
                            requireActivity().supportFragmentManager.commit {
                                replace(R.id.fragment_container, LibraryFragment())
                            }
                        } catch (_: Throwable) {}
                    }
                    2 -> {
                        try {
                            requireActivity().supportFragmentManager.commit {
                                replace(R.id.fragment_container, SettingsFragment())
                            }
                        } catch (_: Throwable) {}
                    }
                    3 -> shareChatToClipboard()
                    4 -> confirmDeleteChat()
                }
            }
            .show()
    }

    private fun showEditChatDialog() {
        val ctx = context ?: return
        val app = appCtx ?: return
        val c0 = conversation ?: ConversationStore.loadOrCreateCurrent(app).also { conversation = it }

        val layout = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val inputTitle = EditText(ctx).apply { hint = "Title"; setText(c0.title) }
        val inputFolder = EditText(ctx).apply { hint = "Folder"; setText(c0.folder) }
        val inputTags = EditText(ctx).apply { hint = "Tags (comma separated)"; setText(c0.tags.joinToString(", ")) }
        val inputIcon = EditText(ctx).apply { hint = "Icon (emoji)"; setText(c0.icon.ifBlank { "💬" }) }
        val inputColor = EditText(ctx).apply { hint = "Color hex (e.g. FF1565C0)"; setText(Integer.toHexString(c0.color)) }

        val btnPickIcon = android.widget.Button(ctx).apply {
            text = "Pick icon"
            setOnClickListener {
                val icons = arrayOf("💬", "📚", "🧠", "🧘", "💼", "🛠️", "❤️", "⭐", "🔥", "✅")
                androidx.appcompat.app.AlertDialog.Builder(ctx)
                    .setTitle("Icon")
                    .setItems(icons) { _, which -> inputIcon.setText(icons[which]) }
                    .show()
            }
        }
        val btnPickColor = android.widget.Button(ctx).apply {
            text = "Pick color"
            setOnClickListener {
                val labels = arrayOf("Blue", "Green", "Orange", "Red", "Purple", "Gray")
                val values = arrayOf("FF1565C0", "FF2E7D32", "FFF57C00", "FFC62828", "FF6A1B9A", "FF37474F")
                androidx.appcompat.app.AlertDialog.Builder(ctx)
                    .setTitle("Color")
                    .setItems(labels) { _, which -> inputColor.setText(values[which]) }
                    .show()
            }
        }

        layout.addView(inputTitle)
        layout.addView(inputFolder)
        layout.addView(inputTags)
        layout.addView(inputIcon)
        layout.addView(btnPickIcon)
        layout.addView(inputColor)
        layout.addView(btnPickColor)

        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Edit chat")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                try {
                    val hex = inputColor.text?.toString()?.trim().orEmpty().trimStart('#')
                    val color = hex.toLongOrNull(16)?.toInt()
                    val updated = ConversationStore.updateMeta(
                        c0,
                        title = inputTitle.text?.toString(),
                        folder = inputFolder.text?.toString(),
                        tagsCsv = inputTags.text?.toString(),
                        icon = inputIcon.text?.toString(),
                        color = color,
                    )
                    conversation = updated
                    ConversationStore.updateConversation(app, updated)
                    view?.findViewById<TextView>(R.id.chat_title)?.text = formatConversationTitle(updated)
                } catch (_: Throwable) {}
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteChat() {
        val ctx = context ?: return
        val app = appCtx ?: return
        val c0 = conversation ?: return
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Delete chat")
            .setMessage("Delete this conversation from Library?")
            .setPositiveButton("Delete") { _, _ ->
                try {
                    ConversationStore.deleteConversation(app, c0.id)
                    val next = ConversationStore.loadOrCreateCurrent(app)
                    conversation = next
                    messages.clear()
                    messages.addAll(next.messages.map { Message(it.text, it.isMe) })
                    adapter.notifyDataSetChanged()
                    view?.findViewById<TextView>(R.id.chat_title)?.text = formatConversationTitle(next)
                } catch (_: Throwable) {}
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shareChatToClipboard() {
        val ctx = context ?: return
        val app = appCtx ?: return
        val c0 = syncConversationFromUi(app) ?: (conversation ?: return)
        val txt = buildString {
            append(formatConversationTitle(c0)).append("\n\n")
            for (m in c0.messages) {
                append(if (m.isMe) "You: " else "AI: ")
                append(m.text.trim()).append("\n\n")
            }
        }.trim()
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            cm.setPrimaryClip(ClipData.newPlainText("chat", txt))
            try { Toast.makeText(ctx, "Chat copied", Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }

    private fun formatConversationTitle(c: ConversationStore.Conversation): String {
        val icon = c.icon.trim().ifBlank { "💬" }
        val folder = c.folder.trim().ifBlank { "inbox" }
        val title = c.title.trim().ifBlank { "Chat" }
        return "$icon  $title  •  $folder"
    }

    private fun updateAttachButton(btn: ImageButton) {
        val attached = pendingImageDataUrl != null
        btn.alpha = if (attached) 1.0f else 0.8f
        btn.setImageResource(if (attached) android.R.drawable.ic_menu_close_clear_cancel else android.R.drawable.ic_menu_add)
        btn.contentDescription = if (attached) "Clear attachment" else "Attach"
        try {
            btn.background?.setTint(if (attached) Color.parseColor("#2E7D32") else Color.parseColor("#263238"))
            btn.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        } catch (_: Throwable) {}
    }

    private fun pickImageFromGallery() {
        if (pickingImage) return
        pickingImage = true
        try {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(Intent.createChooser(intent, "Pick image"), 2202)
        } catch (t: Throwable) {
            pickingImage = false
            try { Toast.makeText(requireContext(), "Pick failed: ${t.message}", Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 2202) return
        pickingImage = false
        val uri = data?.data ?: return
        Thread {
            val encoded = try { encodeImageAsDataUrl(uri) } catch (_: Throwable) { null }
            view?.post {
                if (encoded.isNullOrBlank()) {
                    try { Toast.makeText(requireContext(), "Couldn't attach image", Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
                    return@post
                }
                pendingImageDataUrl = encoded
                pendingImageLabel = "attached"
                val btn = view?.findViewById<ImageButton>(R.id.btn_attach)
                if (btn != null) updateAttachButton(btn)
                try { Toast.makeText(requireContext(), "Image attached", Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
            }
        }.start()
    }

    private fun encodeImageAsDataUrl(uri: Uri): String? {
        val ctx = context ?: return null
        val cr = ctx.contentResolver
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (bytes.isEmpty()) return null

        val bmp = try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } catch (_: Throwable) { null } ?: return null
        val maxDim = 1024
        val w = bmp.width
        val h = bmp.height
        val scale = if (w >= h) (maxDim.toFloat() / w.toFloat()) else (maxDim.toFloat() / h.toFloat())
        val scaled =
            if (scale >= 1f) bmp
            else android.graphics.Bitmap.createScaledBitmap(
                bmp,
                (w * scale).toInt().coerceAtLeast(1),
                (h * scale).toInt().coerceAtLeast(1),
                true
            )

        val out = java.io.ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
        val jpeg = out.toByteArray()
        if (jpeg.size > 2_500_000) return null
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        return "data:image/jpeg;base64,$b64"
    }

    private fun showEditMessageDialog(position: Int) {
        val ctx = context ?: return
        if (position !in messages.indices) return
        val m = messages[position]
        if (!m.isMe) return
        val old = m.text.trim()
        if (old.isBlank() || old.startsWith("🎤") || old.startsWith("DEBUG", ignoreCase = true)) return

        val input = EditText(ctx).apply {
            setText(old)
            setSelection(text?.length ?: 0)
        }

        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Edit message")
            .setView(input)
            .setMessage("This will re-run the chat from this point (messages after it will be replaced).")
            .setPositiveButton("Update & resend") { _, _ ->
                val updated = input.text?.toString()?.trim().orEmpty()
                if (updated.isBlank()) return@setPositiveButton
                try {
                    // Replace this message and drop everything after it.
                    messages[position] = Message(updated, true)
                    if (position < messages.lastIndex) {
                        messages.subList(position + 1, messages.size).clear()
                    }
                    adapter.notifyDataSetChanged()
                    // Rebuild conversation from UI messages.
                    val app = appCtx ?: return@setPositiveButton
                    val c0 = conversation ?: ConversationStore.loadOrCreateCurrent(app).also { conversation = it }
                    val rebuilt = c0.copy(
                        updatedAt = System.currentTimeMillis(),
                        messages = messages.map { mm ->
                            ConversationStore.ChatMessage(
                                text = mm.text,
                                isMe = mm.isMe,
                                ts = System.currentTimeMillis(),
                            )
                        }
                    )
                    conversation = rebuilt
                    scheduleSaveChatHistory()
                    sendToBackend(updated, null)
                } catch (_: Throwable) {}
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun scheduleSaveChatHistory() {
        uiHandler.removeCallbacks(saveHistoryRunnable)
        uiHandler.postDelayed(saveHistoryRunnable, HISTORY_SAVE_DELAY_MS)
    }

    private fun saveChatHistoryNow() {
        try {
            val ctx = appCtx ?: return
            val c = syncConversationFromUi(ctx) ?: return
            ConversationStore.updateConversation(ctx, c)
        } catch (_: Throwable) {}
    }

    private fun syncConversationFromUi(ctx: Context): ConversationStore.Conversation? {
        val c0 =
            try { conversation ?: ConversationStore.loadOrCreateCurrent(ctx).also { conversation = it } }
            catch (_: Throwable) { null }
                ?: return null

        fun isEphemeral(t: String): Boolean {
            val s = t.trim()
            if (s.isBlank()) return true
            if (s.startsWith("DEBUG", ignoreCase = true)) return true
            if (s.startsWith("?? .")) return true
            if (s.contains("(escuchando)", ignoreCase = true)) return true
            if (s.contains("(transcribiendo)", ignoreCase = true)) return true
            return false
        }

        fun inferTitleFrom(text: String): String {
            val t = text.trim()
            if (t.isBlank()) return ""
            val clean = t.replace("\n", " ").trim()
            return if (clean.length <= 44) clean else clean.take(44).trimEnd() + "."
        }

        val kept = messages.filter { !isEphemeral(it.text) }
        val rebuiltMessages = kept.map { m ->
            ConversationStore.ChatMessage(
                text = m.text,
                isMe = m.isMe,
                ts = System.currentTimeMillis(),
            )
        }

        val titleCandidate =
            kept.firstOrNull { it.isMe }?.text?.let(::inferTitleFrom)
                ?: kept.firstOrNull()?.text?.let(::inferTitleFrom)
                ?: ""

        val rebuilt = c0.copy(
            updatedAt = System.currentTimeMillis(),
            title = c0.title.ifBlank { titleCandidate },
            messages = rebuiltMessages,
        )
        conversation = rebuilt
        return rebuilt
    }

    private fun sendToBackend(userText: String, vaultId: String? = null, imageDataUrl: String? = null) {
        val ctx = appCtx ?: context ?: return
        val base = ctx.getString(R.string.backend_url).trim()
        if (base.isEmpty()) {
            // Sin backend: llamar directo a OpenAI
            sendDirectToOpenAI(userText, vaultId, imageDataUrl)
            return
        }
        val url = base.trimEnd('/') + "/api/chat"
        Log.d(TAG, "backend_url=$url")
        val json = "{" + "\"message\":\"" + userText.replace("\"", "\\\"") + "\"}"
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder().url(url).post(body).build()

        Thread {
            try {
                http.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string() ?: ""
                    if (resp.isSuccessful) {
                        val reply = if (raw.isNotBlank()) raw else "(respuesta vacía)"
                        Log.d(TAG, "HTTP ${resp.code} body=${raw.take(500)}")
                        Handler(Looper.getMainLooper()).post {
                            addMessage(reply, false)
                            maybeAutoSpeakReply(reply)
                        }
                    } else {
                        // Fallback a OpenAI directo si hay clave
                        Log.w(TAG, "Backend error ${resp.code}: ${raw.take(200)} - intentando OpenAI directo")
                        Handler(Looper.getMainLooper()).post {
                            addMessage(
                                "DEBUG backend_error\nurl=$url\nstatus=${resp.code}\nbody=${raw.take(200)}",
                                false
                            )
                        }
                        callOpenAIAndPost(userText, vaultId, imageDataUrl)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Backend request failed, intentando OpenAI directo", e)
                Handler(Looper.getMainLooper()).post {
                    addMessage("DEBUG backend_exception\nurl=$url\nerr=${e.message}", false)
                }
                callOpenAIAndPost(userText, vaultId, imageDataUrl)
            }
        }.start()
    }

    private fun sendDirectToOpenAI(userText: String, vaultId: String?, imageDataUrl: String?) {
        Thread { callOpenAIAndPost(userText, vaultId, imageDataUrl) }.start()
    }

    private fun callOpenAIAndPost(userText: String, vaultId: String?, imageDataUrl: String?) {
        val ctx = appCtx ?: context ?: return
        val groqKey = try { ctx.getString(R.string.groq_api_key).trim() } catch (_: Throwable) { "" }
        val openaiKey = try { ctx.getString(R.string.openai_api_key).trim() } catch (_: Throwable) { "" }
        val useGroq = groqKey.isNotBlank()
        val key = if (useGroq) groqKey else openaiKey
        if (key.isBlank()) {
            Handler(Looper.getMainLooper()).post {
                addMessage("API key vacía. Setea GROQ_API_KEY u OPENAI_API_KEY en local.properties (no se commitea) o como variable de entorno.", false)
            }
            return
        }
        try {
            val base = if (useGroq) ctx.getString(R.string.ai_base_groq) else ctx.getString(R.string.ai_base_openai)
            val prefs = try { ctx.getSharedPreferences("settings", 0) } catch (_: Throwable) { null }
            val model = if (useGroq) {
                prefs?.getString(SettingsFragment.KEY_GROQ_CHAT_MODEL, ctx.getString(R.string.groq_model))
                    ?.trim()
                    .orEmpty()
                    .ifBlank { ctx.getString(R.string.groq_model) }
            } else {
                "gpt-4o-mini"
            }
            val url = base.trimEnd('/') + "/v1/chat/completions"
            val hasImage = !imageDataUrl.isNullOrBlank()
            if (hasImage && useGroq && !model.contains("vision", ignoreCase = true)) {
                Handler(Looper.getMainLooper()).post {
                    addMessage(
                        "DEBUG vision_model_required\nSelected model=$model\nPick a Groq vision model in Settings → Groq chat model.",
                        false
                    )
                }
                return
            }
            val payload = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", "You are a concise helpful assistant."))
                    put(
                        JSONObject().put("role", "user").apply {
                            if (hasImage) {
                                val parts = JSONArray()
                                if (userText.isNotBlank()) {
                                    parts.put(JSONObject().put("type", "text").put("text", userText))
                                } else {
                                    parts.put(JSONObject().put("type", "text").put("text", "Describe this image."))
                                }
                                parts.put(
                                    JSONObject().put("type", "image_url").put(
                                        "image_url",
                                        JSONObject().put("url", imageDataUrl)
                                    )
                                )
                                put("content", parts)
                            } else {
                                put("content", userText)
                            }
                        }
                    )
                })
            }.toString()
            val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $key")
                .post(body)
                .build()
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string() ?: ""
                val ok = resp.isSuccessful
                val reply = if (ok) {
                    try {
                        val root = JSONObject(raw)
                        val choices = root.optJSONArray("choices")
                        val first = choices?.optJSONObject(0)
                        val msg = first?.optJSONObject("message")
                        msg?.optString("content")?.takeIf { it.isNotBlank() } ?: raw.take(300)
                    } catch (e: Exception) { raw.take(300) }
                } else {
                    (if (useGroq) "Groq" else "OpenAI") + " error ${resp.code}: ${raw.take(200)}"
                }
                Log.d(TAG, (if (useGroq) "Groq" else "OpenAI") + " HTTP ${resp.code} body=${raw.take(500)}")
                Handler(Looper.getMainLooper()).post {
                    if (!ok) {
                        addMessage("DEBUG api_error\nprovider=" + (if (useGroq) "groq" else "openai") + "\nstatus=${resp.code}\nbody=${raw.take(200)}", false)
                    }
                    addMessage(reply, false)
                    maybeAutoSpeakReply(reply)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "API request failed", e)
            Handler(Looper.getMainLooper()).post {
                addMessage("DEBUG api_exception\nerr=${e.message}", false)
                addMessage("Provider fallo: ${e.message}", false)
            }
        }
    }

    private val REQ_AUDIO = 1001
    private fun requestPermissionCompat(permission: String) {
        if (ContextCompat.checkSelfPermission(requireContext(), permission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(requireActivity(), arrayOf(permission), if (permission == Manifest.permission.RECORD_AUDIO) REQ_AUDIO else 0)
        }
    }

    private fun setListeningUI(active: Boolean) {
        val mic = micButton ?: return
        if (active) {
            try { mic.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE) } catch (_: Throwable) {}
            mic.background?.setTint(Color.parseColor("#D32F2F")) // red
            startPulse()
        } else {
            try { mic.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE) } catch (_: Throwable) {}
            mic.background?.setTint(Color.parseColor("#263238")) // dark
            stopPulse()
        }
    }

    private fun startPulse() {
        uiHandler.removeCallbacks(pulseRunnable)
        uiHandler.post(pulseRunnable)
    }

    private fun stopPulse() {
        uiHandler.removeCallbacks(pulseRunnable)
        micButton?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(120)?.start()
    }

    private val pulseRunnable = object : Runnable {
        override fun run() {
            if (!isListening) return
            val target = if (pulseUp) 1.15f else 1.0f
            micButton?.animate()?.scaleX(target)?.scaleY(target)?.setDuration(260)?.start()
            pulseUp = !pulseUp
            uiHandler.postDelayed(this, 280)
        }
    }

    // Whisper local listening via VoiceController
    private fun startListeningWhisper(continuous: Boolean) {
        try { tts?.stop() } catch (_: Throwable) {}
        isListening = true
        continuousMode = continuous
        currentTranscript.setLength(0)
        prepareLiveBubble()
        voice?.start(continuous)
    }

    // legacy whisper path removed

    private fun startListening(continuous: Boolean) {
        try { tts?.stop() } catch (_: Throwable) {}
        isListening = true
        continuousMode = continuous
        currentTranscript.setLength(0)
        prepareLiveBubble()
        setListeningUI(true)

        val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
            putExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1600)
            currentLangTag?.let { putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, it) }
        }
        if (speech == null) speech = android.speech.SpeechRecognizer.createSpeechRecognizer(requireContext())
        speech?.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { addDebug("speech: ready") }
            override fun onBeginningOfSpeech() { addDebug("speech: begin") }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { addDebug("speech: end"); if (!continuousMode) stopListening(sendNow = true) }
            override fun onError(error: Int) {
                addDebug("speech error: $error")
                if (error == android.speech.SpeechRecognizer.ERROR_CLIENT) {
                    // Avoid immediate restart storms
                    isListening = false
                    setListeningUI(false)
                } else {
                    stopListening(sendNow = currentTranscript.isNotBlank())
                }
            }
            override fun onResults(results: Bundle) {
                handleResults(results, finalResult = true)
                val matches = results.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                val piece = matches?.firstOrNull()?.trim().orEmpty()
                if (piece.length >= 8) maybeSwitchLanguage(piece)
                if (continuousMode) {
                    val prefs = appCtx?.getSharedPreferences("settings", 0)
                    val autoSend = prefs?.getBoolean(SettingsFragment.KEY_MIC_AUTO_SEND, true) == true
                    if (autoSend && !micHoldMode) {
                        stopListening(sendNow = true)
                    }
                }
            }
            override fun onPartialResults(partialResults: Bundle) { handleResults(partialResults, finalResult = false) }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speech?.startListening(intent)
    }

    

    private fun collectWindowSeconds(maxSeconds: Int): ShortArray {
        if (pcmChunks.isEmpty()) return ShortArray(0)
        var samplesNeeded = 16000 * maxSeconds
        val selected = ArrayList<ShortArray>()
        var total = 0
        for (i in pcmChunks.size - 1 downTo 0) {
            val arr = pcmChunks[i]
            selected.add(arr)
            total += arr.size
            if (total >= samplesNeeded) break
        }
        // flatten reverse
        val out = ShortArray(total)
        var p = total
        for (arr in selected) {
            p -= arr.size
            System.arraycopy(arr, 0, out, p, arr.size)
        }
        return out
    }

    private fun rms(buf: ShortArray): Float {
        var acc = 0.0
        for (s in buf) { val v = s.toDouble(); acc += v * v }
        val mean = acc / buf.size
        val valf = Math.sqrt(mean) / 32768.0
        return valf.toFloat()
    }

    private fun stopWhisper() {
        try { voice?.stop() } catch (_: Exception) {}
    }

    private fun applyVoiceRuntimePrefs(prefs: android.content.SharedPreferences, forHold: Boolean) {
        val v = voice ?: return
        v.decodeIntervalMs = prefs.getInt(SettingsFragment.KEY_DECODE_INTERVAL, 750).toLong()
        v.silenceThreshold = prefs.getFloat(SettingsFragment.KEY_SILENCE_THRESHOLD, 0.01f)
        val autoSend = prefs.getBoolean(SettingsFragment.KEY_MIC_AUTO_SEND, true)
        if (!autoSend || forHold) {
            v.silenceHoldMs = Long.MAX_VALUE
            v.maxUtteranceMs = 60_000L
        } else {
            v.silenceHoldMs = prefs.getInt(SettingsFragment.KEY_SILENCE_HOLD, 1400).toLong()
            v.maxUtteranceMs = 20_000L
        }
    }

    private fun isAutoSpeakRepliesEnabled(): Boolean {
        return try {
            appCtx?.getSharedPreferences("settings", 0)
                ?.getBoolean(SettingsFragment.KEY_TTS_AUTO_REPLY, false) == true
        } catch (_: Throwable) { false }
    }

    private fun toggleAutoSpeakReplies(): Boolean {
        return try {
            val prefs = appCtx?.getSharedPreferences("settings", 0) ?: return false
            val next = !prefs.getBoolean(SettingsFragment.KEY_TTS_AUTO_REPLY, false)
            prefs.edit().putBoolean(SettingsFragment.KEY_TTS_AUTO_REPLY, next).apply()
            next
        } catch (_: Throwable) { false }
    }

    private fun openTtsSettings() {
        val ctx = context ?: return
        try {
            val pm = ctx.packageManager
            val googleVoicePacks = Intent().setClassName(
                "com.google.android.tts",
                "com.google.android.apps.speech.tts.googletts.local.voicepack.ui.VoiceDataInstallActivity"
            )
            if (googleVoicePacks.resolveActivity(pm) != null) {
                startActivity(googleVoicePacks)
                return
            }
        } catch (_: Throwable) {}
        try {
            startActivity(Intent("com.android.settings.TTS_SETTINGS"))
        } catch (_: Throwable) {
            try {
                startActivity(Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
            } catch (_: Throwable) {}
        }
    }

    private fun updateTtsButton(speaking: Boolean? = null, paused: Boolean? = null) {
        val btn = ttsButton ?: return
        val player = tts ?: run {
            btn.visibility = View.GONE
            return
        }
        val isSpeakingNow = speaking ?: player.isSpeaking()
        val isPausedNow = paused ?: player.isPaused()
        btn.visibility = View.VISIBLE
        btn.isEnabled = true
        if (isSpeakingNow) {
            btn.setImageResource(android.R.drawable.ic_media_pause)
            btn.contentDescription = "Pause voice"
            btn.alpha = 1.0f
            try {
                btn.background?.setTint(Color.parseColor("#1565C0"))
                btn.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            } catch (_: Throwable) {}
            return
        }
        if (isPausedNow) {
            btn.setImageResource(android.R.drawable.ic_media_play)
            btn.contentDescription = "Resume voice"
            btn.alpha = 1.0f
            try {
                btn.background?.setTint(Color.parseColor("#1565C0"))
                btn.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            } catch (_: Throwable) {}
            return
        }

        val auto = isAutoSpeakRepliesEnabled()
        btn.setImageResource(android.R.drawable.ic_lock_silent_mode_off)
        btn.contentDescription = if (auto) "Auto-voice ON" else "Auto-voice OFF"
        btn.alpha = if (auto) 1.0f else 0.55f
        try {
            btn.background?.setTint(if (auto) Color.parseColor("#2E7D32") else Color.parseColor("#263238"))
            btn.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        } catch (_: Throwable) {}
    }

    private fun speakText(raw: String) {
        val player = tts ?: return
        val cleaned = cleanForSpeech(raw)
        if (cleaned.isBlank()) return

        val token = player
        langId.identifyLanguage(cleaned)
            .addOnSuccessListener { code ->
                if (tts !== token) return@addOnSuccessListener
                val def = Locale.getDefault()
                val locale = when (code) {
                    "es" -> if (def.language == "es") def else Locale("es")
                    "en" -> if (def.language == "en") def else Locale.US
                    "he", "iw" -> Locale("he")
                    else -> Locale.getDefault()
                }
                token.speak(cleaned, locale)
            }
            .addOnFailureListener {
                if (tts !== token) return@addOnFailureListener
                token.speak(cleaned, Locale.getDefault())
            }
    }

    private fun cleanForSpeech(raw: String): String {
        var t = raw.trim()
        if (t.isBlank()) return ""
        if (t.startsWith("DEBUG", ignoreCase = true)) return ""
        if (t.startsWith("??")) t = t.removePrefix("??").trim()
        t = t.replace(" (escuchando)", "").trim()
        t = t.replace("(sin transcripción)", "").trim()
        if (t == "." || t.startsWith(". (")) return ""
        return t
    }

    private fun maybeAutoSpeakReply(reply: String) {
        try {
            val prefs = appCtx?.getSharedPreferences("settings", 0)
            if (prefs?.getBoolean(SettingsFragment.KEY_TTS_AUTO_REPLY, false) == true) {
                speakText(reply)
            }
        } catch (_: Throwable) {}
    }

    private fun setWhisperBanner(tv: android.widget.TextView?, ok: Boolean, text: String) {
        tv ?: return
        tv.visibility = View.VISIBLE
        tv.text = "Whisper v" + appVersion() + ": " + text
        val color = if (ok) Color.parseColor("#2E7D32") else Color.parseColor("#B71C1C")
        tv.setBackgroundColor(color)
    }

    private fun appVersion(): String {
        return try {
            val ctx = requireContext()
            val pm = ctx.packageManager
            val pkg = ctx.packageName
            val pi = if (android.os.Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(pkg, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            }
            pi.versionName ?: "?"
        } catch (_: Throwable) { "?" }
    }

    private fun initSttEngine(whisperStatus: android.widget.TextView?) {
        val prefs = requireContext().getSharedPreferences("settings", 0)
        val useWhisper = prefs.getBoolean(SettingsFragment.KEY_USE_WHISPER, true)
        if (!useWhisper) {
            whisperReady = false
            whisperInitializing = false
            asr = null
            voice = null
            setWhisperBanner(whisperStatus, true, "System STT")
            return
        }
        val useCloud = if (prefs.contains(SettingsFragment.KEY_USE_CLOUD_STT)) {
            prefs.getBoolean(SettingsFragment.KEY_USE_CLOUD_STT, false)
        } else {
            // If keys exist, default to cloud for better quality/speed (user can toggle off).
            val hasKey = try {
                getString(R.string.groq_api_key).trim().isNotEmpty() || getString(R.string.openai_api_key).trim().isNotEmpty()
            } catch (_: Throwable) { false }
            if (hasKey) {
                prefs.edit().putBoolean(SettingsFragment.KEY_USE_CLOUD_STT, true).apply()
                true
            } else {
                false
            }
        }
        if (useCloud) {
            initCloudStt(prefs, whisperStatus)
        } else {
            initWhisperAsync(whisperStatus)
        }
    }

    private fun initCloudStt(prefs: android.content.SharedPreferences, whisperStatus: android.widget.TextView?) {
        whisperReady = false
        whisperInitializing = false
        asr = null
        voice = null

        val engine = CloudAsr(requireContext().applicationContext).also { it.applyPrefs(prefs) }
        asr = engine
        whisperReady = engine.isReady()
        if (!whisperReady) {
            addDebug("stt: cloud not ready (${engine.lastError()})")
            setWhisperBanner(whisperStatus, false, "Cloud STT: missing API key")
            whisperStatus?.setOnClickListener { initCloudStt(prefs, whisperStatus) }
            return
        }
        addDebug("stt: cloud ready (" + (if (engine.usingGroq()) "groq" else "openai") + " model=" + engine.lastModel() + ")")
        setWhisperBanner(whisperStatus, true, "Cloud STT (" + engine.lastModel() + ")")
        whisperStatus?.setOnClickListener(null)
        wireVoiceController(engine, prefs)
    }

    private fun initWhisperAsync(whisperStatus: android.widget.TextView?) {
        if (whisperInitializing) return
        whisperInitializing = true
        val initToken = ++whisperInitToken
        whisperReady = false
        asr = null
        voice = null

        val prefs = requireContext().getSharedPreferences("settings", 0)
        // One-time quality defaults (users can change them later in settings).
        try {
            if (!prefs.getBoolean(SettingsFragment.KEY_QUALITY_DEFAULTS_APPLIED, false)) {
                val editor = prefs.edit()
                editor.putString(SettingsFragment.KEY_STRATEGY, "beam")
                editor.putInt(SettingsFragment.KEY_BEAM, 5)
                editor.putBoolean(SettingsFragment.KEY_ENABLE_TS, false)
                editor.putFloat(SettingsFragment.KEY_TEMP, 0.0f)
                editor.putFloat(SettingsFragment.KEY_TEMP_INC, 0.0f)
                editor.putBoolean(SettingsFragment.KEY_QUALITY_DEFAULTS_APPLIED, true)
                editor.commit()
                addDebug("whisper: quality defaults applied (beam=5, ts=off, temp=0)")
            }
        } catch (_: Throwable) {}
        val threads = prefs.getInt(
            SettingsFragment.KEY_THREADS,
            Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        )
        setWhisperBanner(whisperStatus, false, "Loading model… (1ª vez puede tardar)")
        addDebug("whisper: init start (threads=$threads)")

        val appCtx = requireContext().applicationContext
        Thread({
            val asrInst = try {
                WhisperAsr(appCtx, threads = threads).also { it.applyPrefs(prefs) }
            } catch (t: Throwable) {
                uiHandler.post {
                    if (!isAdded || whisperInitToken != initToken) return@post
                    whisperInitializing = false
                    val msg = "init exception: ${t.message}"
                    addDebug("whisper: $msg")
                    setWhisperBanner(whisperStatus, false, msg + " - tap to retry")
                    whisperStatus?.setOnClickListener { initWhisperAsync(whisperStatus) }
                }
                return@Thread
            }

            val ok = try { asrInst.isReady() } catch (_: Throwable) { false }
            val modelName = try { asrInst.lastModelName() } catch (_: Throwable) { null }
            val err = try { asrInst.lastError() } catch (_: Throwable) { "" }

            uiHandler.post {
                if (!isAdded || whisperInitToken != initToken) return@post
                whisperInitializing = false
                asr = asrInst
                whisperReady = ok

                if (ok) {
                    addDebug("whisper: ready (" + (modelName ?: "?") + ")")
                    setWhisperBanner(whisperStatus, true, "Ready (" + (modelName ?: "?") + ")")
                    whisperStatus?.setOnClickListener(null)
                    wireVoiceController(asrInst, prefs)
                } else {
                    val msg = if (err.isNotBlank()) "init failed: $err" else "init failed"
                    addDebug("whisper: $msg")
                    setWhisperBanner(whisperStatus, false, msg + " - tap to retry")
                    whisperStatus?.setOnClickListener { initWhisperAsync(whisperStatus) }
                }
            }
        }, "WhisperInit").start()
    }

    private fun wireVoiceController(asrInst: AsrEngine, prefs: android.content.SharedPreferences) {
        voice = VoiceController(
            requireContext(),
            asrInst,
            onDebug = { addDebug(it) },
            onPartial = { txt ->
                val label = if (isListening && continuousMode) " (escuchando)" else ""
                if (lastVoiceIndex >= 0 && lastVoiceIndex < messages.size) {
                    messages[lastVoiceIndex] = Message("?? ${txt.trim()}" + label, true)
                    adapter.notifyItemChanged(lastVoiceIndex)
                    scheduleSaveChatHistory()
                }
            },
            onFinal = { final ->
                currentTranscript.setLength(0)
                currentTranscript.append(final)
                finalizeTranscriptAndSend()
            },
            onState = { active ->
                isListening = active
                setListeningUI(active)
            }
        ).apply {
            decodeIntervalMs = prefs.getInt(SettingsFragment.KEY_DECODE_INTERVAL, 750).toLong()
            silenceThreshold = prefs.getFloat(SettingsFragment.KEY_SILENCE_THRESHOLD, 0.01f)
            val autoSend = prefs.getBoolean(SettingsFragment.KEY_MIC_AUTO_SEND, true)
            if (!autoSend) {
                silenceHoldMs = Long.MAX_VALUE
                maxUtteranceMs = 60_000L
            } else {
                silenceHoldMs = prefs.getInt(SettingsFragment.KEY_SILENCE_HOLD, 1400).toLong()
                maxUtteranceMs = 20_000L
            }
        }
    }

    private fun stopListening(sendNow: Boolean) {
        isListening = false
        setListeningUI(false)
        try { speech?.stopListening() } catch (_: Exception) {}
        stopWhisper()
        if (sendNow && currentTranscript.isNotBlank()) finalizeTranscriptAndSend()
        continuousMode = false
    }

    private fun prepareLiveBubble() {
        val keep = requireContext().getSharedPreferences("settings", 0).getBoolean(SettingsFragment.KEY_KEEP_AUDIO, false)
        if (!keep && lastVoiceIndex >= 0 && lastVoiceIndex < messages.size) {
            // Only remove the previous placeholder bubble; keep real transcripts so the user can see them.
            val prev = messages[lastVoiceIndex].text
            if (prev.startsWith("🎤 …")) {
                messages.removeAt(lastVoiceIndex)
                adapter.notifyDataSetChanged()
                lastVoiceIndex = -1
                scheduleSaveChatHistory()
            }
        }
        messages.add(Message("🎤 … (escuchando)", true))
        lastVoiceIndex = messages.lastIndex
        adapter.notifyItemInserted(lastVoiceIndex)
        scheduleSaveChatHistory()
    }

    private fun handleResults(results: Bundle, finalResult: Boolean) {
        val matches = results.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
        val piece = matches?.firstOrNull()?.trim().orEmpty()
        if (piece.isBlank()) return
        if (finalResult) currentTranscript.append(piece) else currentTranscript.append(piece).append(' ')
        if (lastVoiceIndex >= 0 && lastVoiceIndex < messages.size) {
            val label = if (isListening && continuousMode) " (escuchando)" else ""
            messages[lastVoiceIndex] = Message("🎤 ${currentTranscript.toString().trim()}" + label, true)
            adapter.notifyItemChanged(lastVoiceIndex)
            scheduleSaveChatHistory()
        }
        if (finalResult && continuousMode && isListening) {
            try { speech?.cancel() } catch (_: Exception) {}
            uiHandler.postDelayed({ if (isListening) startListening(continuous = true) }, 250)
        }
    }

    private fun finalizeTranscriptAndSend() {
        val text = currentTranscript.toString().trim()
        currentTranscript.setLength(0)
        if (lastVoiceIndex >= 0 && lastVoiceIndex < messages.size) {
            messages[lastVoiceIndex] = if (text.isBlank()) {
                Message("🎤 (sin transcripción)", true)
            } else {
                Message("🎤 $text", true)
            }
            adapter.notifyItemChanged(lastVoiceIndex)
            scheduleSaveChatHistory()
        }
        if (text.isNotBlank()) {
            try {
                val prefs = appCtx?.getSharedPreferences("settings", 0)
                if (prefs?.getBoolean(SettingsFragment.KEY_TTS_AUTO_TRANSCRIPT, false) == true) {
                    speakText(text)
                }
            } catch (_: Throwable) {}
            sendToBackend(text, null)
        }
    }

    private fun maybeSwitchLanguage(text: String) {
        // Simple debounce: don't switch too often
        if (text.length < 8) return
        com.google.mlkit.nl.languageid.LanguageIdentification.getClient()
            .identifyPossibleLanguages(text)
            .addOnSuccessListener { langs ->
                val best = langs.maxByOrNull { it.confidence } ?: return@addOnSuccessListener
                val iso = best.languageTag ?: return@addOnSuccessListener
                if (!allowedLangs.contains(iso)) return@addOnSuccessListener
                lastDetectedLang = iso
                val newTag = when (iso) {
                    "es" -> "es-ES"
                    "en" -> "en-US"
                    "he", "iw" -> "he-IL"
                    else -> return@addOnSuccessListener
                }
                val now = System.currentTimeMillis()
                if (currentLangTag != newTag && best.confidence >= 0.8f && (now - lastSwitchAt) > switchCooldownMs) {
                    currentLangTag = newTag
                    addDebug("auto lang -> $iso")
                    lastSwitchAt = now
                    if (isListening) {
                        // Restart recognizer with new language
                        try { speech?.cancel() } catch (_: Exception) {}
                        uiHandler.postDelayed({ if (isListening) startListening(continuous = continuousMode) }, 150)
                    }
                }
            }
            .addOnFailureListener { }
    }

    private fun addDebug(msg: String) {
        DebugFileLogger.log("chat", msg)
        uiHandler.post {
            if (msg.startsWith("whisper: decode start")) {
                setWhisperDecoding(true)
                if (lastVoiceIndex >= 0 && lastVoiceIndex < messages.size) {
                    messages[lastVoiceIndex] = Message("🎤 … (transcribiendo…)", true)
                    adapter.notifyItemChanged(lastVoiceIndex)
                    scheduleSaveChatHistory()
                }
            } else if (msg.startsWith("whisper: decoded") || msg.startsWith("whisper: decode exception")) {
                setWhisperDecoding(false)
            }
            if (shouldShowDebugInChat(msg)) {
                messages.add(Message("DEBUG $msg", false))
                adapter.notifyItemInserted(messages.lastIndex)
                scheduleSaveChatHistory()
            }
        }
    }

    private fun shouldShowDebugInChat(msg: String): Boolean {
        val m = msg.trim()
        if (m.isBlank()) return false
        if (m.startsWith("whisper: text=")) return false
        if (m.startsWith("whisper:")) return true
        if (m.startsWith("perm:")) return true
        if (m.contains("no audio frames", ignoreCase = true)) return true
        if (m.contains("error", ignoreCase = true) || m.contains("exception", ignoreCase = true) || m.contains("failed", ignoreCase = true)) return true
        return false
    }

    private fun setWhisperDecoding(active: Boolean) {
        whisperDecoding = active
        val mic = micButton ?: return
        mic.isEnabled = !active
        mic.alpha = if (active) 0.55f else 1.0f
    }

    private fun toggleListening() {
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(requireContext())) {
            addMessage("DEBUG speech: no recognizer available", false)
            return
        }
        if (!isListening) startListening() else stopListening()
    }

    private fun startListening() {
        isListening = true
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        if (speech == null) speech = android.speech.SpeechRecognizer.createSpeechRecognizer(requireContext())
        speech?.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { addMessage("DEBUG speech: ready", false) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { isListening = false }
            override fun onError(error: Int) { isListening = false; addMessage("DEBUG speech error: $error", false) }
            override fun onResults(results: Bundle) { handleResults(results) }
            override fun onPartialResults(partialResults: Bundle) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speech?.startListening(intent)
    }

    private fun stopListening() { isListening = false; speech?.stopListening() }

    private fun handleResults(results: Bundle) {
        val matches = results.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
        val txt = matches?.firstOrNull()?.trim().orEmpty()
        if (txt.isBlank()) return

        val keep = requireContext().getSharedPreferences("settings", 0).getBoolean(SettingsFragment.KEY_KEEP_AUDIO, false)
        if (!keep && lastVoiceIndex >= 0 && lastVoiceIndex < messages.size) {
            messages.removeAt(lastVoiceIndex)
            adapter.notifyDataSetChanged()
            lastVoiceIndex = -1
            scheduleSaveChatHistory()
        }

        messages.add(Message("🎤 $txt", true))
        lastVoiceIndex = messages.lastIndex
        adapter.notifyItemInserted(lastVoiceIndex)
        scheduleSaveChatHistory()
        sendToBackend(txt, null)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        uiHandler.removeCallbacks(micHoldActivateRunnable)
        uiHandler.removeCallbacks(saveHistoryRunnable)
        speech?.destroy()
        speech = null
        try { tts?.shutdown() } catch (_: Throwable) {}
        tts = null
        stopWhisper()
    }

    override fun onStop() {
        super.onStop()
        try { saveChatHistoryNow() } catch (_: Throwable) {}
    }

    override fun onPause() {
        super.onPause()
        if (activity?.isChangingConfigurations == true) return
        if (isListening) stopListening(sendNow = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        try {
            val texts = ArrayList<String>(messages.size)
            val isMe = BooleanArray(messages.size)
            for (i in messages.indices) {
                texts.add(messages[i].text)
                isMe[i] = messages[i].isMe
            }
            outState.putStringArrayList(STATE_MSG_TEXTS, texts)
            outState.putBooleanArray(STATE_MSG_ISME, isMe)
        } catch (_: Throwable) {}
        try {
            val draft = view?.findViewById<EditText>(R.id.input_text)?.text?.toString()
            if (!draft.isNullOrBlank()) outState.putString(STATE_DRAFT, draft)
        } catch (_: Throwable) {}
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                addDebug("perm: RECORD_AUDIO granted")
                val prefs = requireContext().getSharedPreferences("settings", 0)
                val useWhisper = prefs.getBoolean(SettingsFragment.KEY_USE_WHISPER, true)
                if (whisperReady && useWhisper) {
                    applyVoiceRuntimePrefs(prefs, forHold = false)
                    startListeningWhisper(continuous = false)
                } else {
                    startListening(continuous = true)
                }
            } else {
                addDebug("perm: RECORD_AUDIO denied")
            }
        }
    }
}

class MessagesAdapter(
    private val items: List<Message>,
    private val onSpeak: (Message) -> Unit,
    private val onEdit: (Int) -> Unit,
) : RecyclerView.Adapter<MessageVH>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return MessageVH(v, onSpeak, onEdit)
    }
    override fun onBindViewHolder(holder: MessageVH, position: Int) = holder.bind(items[position])
    override fun getItemCount(): Int = items.size
}

class MessageVH(
    view: View,
    private val onSpeak: (Message) -> Unit,
    private val onEdit: (Int) -> Unit,
) : RecyclerView.ViewHolder(view) {
    private val bubbleMe: View = view.findViewById(R.id.bubble_me)
    private val bubbleOther: View = view.findViewById(R.id.bubble_other)
    private val textMe: android.widget.TextView = view.findViewById(R.id.text_me)
    private val textOther: android.widget.TextView = view.findViewById(R.id.text_other)
    private val speakMe: ImageButton = view.findViewById(R.id.btn_speak_me)
    private val speakOther: ImageButton = view.findViewById(R.id.btn_speak_other)
    fun bind(m: Message) {
        val t = m.text.trim()
        val speakable =
            t.isNotBlank() &&
                !t.startsWith("DEBUG", ignoreCase = true) &&
                !t.startsWith("?? .") &&
                !t.contains("(escuchando)") &&
                !t.contains("(transcribiendo)") &&
                !t.contains("sin transcrip", ignoreCase = true)
        if (m.isMe) {
            bubbleMe.visibility = View.VISIBLE
            bubbleOther.visibility = View.GONE
            textMe.text = m.text
            speakMe.visibility = if (speakable) View.VISIBLE else View.GONE
            speakMe.setOnClickListener { onSpeak(m) }
            val editLongClick = View.OnLongClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onEdit(p)
                true
            }
            bubbleMe.setOnLongClickListener(editLongClick)
            textMe.setOnLongClickListener(editLongClick)
            itemView.setOnLongClickListener(editLongClick)
        } else {
            bubbleMe.visibility = View.GONE
            bubbleOther.visibility = View.VISIBLE
            textOther.text = m.text
            speakOther.visibility = if (speakable) View.VISIBLE else View.GONE
            speakOther.setOnClickListener { onSpeak(m) }
            bubbleOther.setOnLongClickListener(null)
            textOther.setOnLongClickListener(null)
            itemView.setOnLongClickListener(null)
        }
    }
}
