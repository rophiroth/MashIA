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
import android.content.res.ColorStateList
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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.MotionEvent
import android.view.Gravity
import android.text.Editable
import android.text.TextWatcher
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
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
        private const val ARG_SHOW_LIBRARY = "arg_show_library"

        fun newInstance(prefillText: String? = null, showLibrary: Boolean = false): ChatFragment {
            return ChatFragment().apply {
                if (!prefillText.isNullOrBlank() || showLibrary) {
                    arguments = Bundle().apply {
                        putString(ARG_PREFILL_TEXT, prefillText)
                        if (showLibrary) putBoolean(ARG_SHOW_LIBRARY, true)
                    }
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
    private var sendButton: ImageButton? = null
    private var inputField: EditText? = null
    private var whisperDecoding = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private val saveHistoryRunnable = Runnable { saveChatHistoryNow() }
    private var conversation: ConversationStore.Conversation? = null
    private var tabItems: List<ConversationStore.Conversation> = emptyList()
    private var tabsAdapter: ChatTabsAdapter? = null
    private var tabsList: RecyclerView? = null
    private var pendingImageDataUrl: String? = null
    private var pendingImageLabel: String? = null
    private var pickingImage: Boolean = false
    private var pendingCameraAfterPermission: Boolean = false
    private var attachMode: String = "camera"
    private var pulseUp = true
    private val TAG = "ChatFragment"
    private var appCtx: android.content.Context? = null
    private var tts: TtsPlayer? = null
    private var autoSendButton: ImageButton? = null
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
    private var libraryVisible: Boolean = false
    private var openTabIds: MutableList<String> = mutableListOf()
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
        val mic = view.findViewById<ImageButton>(R.id.btn_mic)
        val ttsBtn = view.findViewById<ImageButton>(R.id.btn_tts)
        val btnNewChat = view.findViewById<ImageButton>(R.id.btn_new_chat)
        val btnMore = view.findViewById<ImageButton>(R.id.btn_chat_more)
        val chatTitle = view.findViewById<TextView>(R.id.chat_title)
        val chatApp = view.findViewById<TextView>(R.id.chat_app_label)
        val tabs = view.findViewById<RecyclerView>(R.id.chat_tabs)
        val inputBar = view.findViewById<android.widget.LinearLayout>(R.id.chat_input_bar)
        val toolbarPrompt = activity?.findViewById<TextView>(R.id.toolbar_prompt)
        val toolbarTts = activity?.findViewById<ImageButton>(R.id.toolbar_tts)
        val toolbarAutoSend = activity?.findViewById<ImageButton>(R.id.toolbar_autosend)
        val toolbarMore = activity?.findViewById<ImageButton>(R.id.toolbar_more)
        if (toolbarTts != null) {
            ttsBtn.visibility = View.GONE
        }
        if (toolbarMore != null) {
            btnMore.visibility = View.GONE
        }
        if (toolbarPrompt != null) {
            chatTitle.visibility = View.GONE
            chatApp.visibility = View.GONE
        }
        tabs.layoutManager = LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        val tabsAdapterLocal = ChatTabsAdapter(
            emptyList(),
            activeId = null,
            onClick = { item -> openConversationTab(item) },
            onClose = { item -> confirmCloseChat(item) },
            onLongClick = { item -> showTabMenu(item) },
        )
        tabs.adapter = tabsAdapterLocal
        tabsAdapter = tabsAdapterLocal
        tabsList = tabs
        ttsButton = toolbarTts ?: ttsBtn
        autoSendButton = toolbarAutoSend
        micButton = mic
        sendButton = send
        inputField = input
        // Idle style: white icon on dark background
        try {
            mic.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            mic.background?.setTint(Color.parseColor("#263238"))
        } catch (_: Throwable) {}

        try {
            val prefs = requireContext().getSharedPreferences("settings", 0)
            val micLeft = prefs.getBoolean(SettingsFragment.KEY_MIC_LEFT, false)
            if (micLeft) {
                inputBar.removeView(mic)
                inputBar.addView(mic, 0)
            }
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
                setPromptTitle(c)
            } catch (_: Throwable) {}
        }
        if (chatTitle.text.isNullOrBlank()) {
            conversation?.let { setPromptTitle(it) }
        }
        try {
            if (arguments?.getBoolean(ARG_SHOW_LIBRARY, false) == true) {
                setLibraryVisible(true)
                arguments?.remove(ARG_SHOW_LIBRARY)
            }
        } catch (_: Throwable) {}
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
        refreshTabs(scrollToCurrent = true)
        updateAutoSendButton()
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
        toolbarMore?.setOnClickListener { showChatMoreMenu() }
        toolbarTts?.setOnClickListener { ttsBtn.performClick() }

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

        updateSendVisibility()
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateSendVisibility()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        attachFile.setOnClickListener {
            if (pendingImageDataUrl != null) {
                pendingImageDataUrl = null
                pendingImageLabel = null
                updateAttachButton(attachFile)
                try { Toast.makeText(requireContext(), "Attachment cleared", Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
                return@setOnClickListener
            }
            showAttachMenu(attachFile)
        }
        attachFile.setOnLongClickListener {
            pickImageFromGallery()
            true
        }
        updateAttachButton(attachFile)

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
            view?.findViewById<RecyclerView>(R.id.messages_list)?.scrollToPosition(messages.lastIndex)
        } catch (_: Throwable) {}
        try {
            val ctx = (appCtx ?: context)?.applicationContext
            if (ctx != null) {
                val c0 = conversation ?: ConversationStore.loadOrCreateCurrent(ctx).also { conversation = it }
                val titleBefore = c0.title
                conversation = ConversationStore.appendMessage(c0, text, isMe)
                if (!isMe && isUntitled(titleBefore)) {
                    conversation?.let { maybeAutoTitle(it) }
                }
            }
        } catch (_: Throwable) {}
        scheduleSaveChatHistory()
    }

    private fun openConversationTab(item: ConversationStore.Conversation) {
        val app = appCtx ?: return
        if (conversation?.id == item.id) return
        saveChatHistoryNow()
        ConversationStore.setCurrentId(app, item.id)
        ensureTabOpen(item.id)
        applyConversation(item, scrollToBottom = true)
    }

    fun openConversationFromLibrary(item: ConversationStore.Conversation) {
        openConversationTab(item)
    }

    private fun applyConversation(next: ConversationStore.Conversation, scrollToBottom: Boolean) {
        conversation = next
        messages.clear()
        messages.addAll(next.messages.map { Message(it.text, it.isMe) })
        adapter.notifyDataSetChanged()
        setPromptTitle(next)
        if (scrollToBottom && messages.isNotEmpty()) {
            try {
                view?.findViewById<RecyclerView>(R.id.messages_list)?.scrollToPosition(messages.lastIndex)
            } catch (_: Throwable) {}
        }
        tabsAdapter?.setActiveId(next.id)
        val idx = tabItems.indexOfFirst { it.id == next.id }
        if (idx >= 0) tabsList?.scrollToPosition(idx)
    }

    private fun refreshTabs(scrollToCurrent: Boolean = false) {
        val ctx = appCtx ?: context ?: return
        Thread {
            val items = try { ConversationStore.loadAll(ctx) } catch (_: Throwable) { emptyList() }
            view?.post {
                val currentId = conversation?.id
                val openIds = loadOpenTabIds(ctx)
                if (currentId != null && !openIds.contains(currentId)) {
                    openIds.add(0, currentId)
                }
                if (openIds.isEmpty() && items.isNotEmpty()) {
                    openIds.add(items.first().id)
                }
                // Keep only existing conversations, preserve order.
                val byId = items.associateBy { it.id }
                val filtered = openIds.mapNotNull { byId[it] }
                openTabIds = openIds.filter { byId.containsKey(it) }.toMutableList()
                saveOpenTabIds(ctx, openTabIds)
                tabItems = filtered
                tabsAdapter?.update(filtered, conversation?.id)
                if (scrollToCurrent) {
                    val idx = filtered.indexOfFirst { it.id == conversation?.id }
                    if (idx >= 0) tabsList?.scrollToPosition(idx)
                }
            }
        }.start()
    }

    private fun loadOpenTabIds(ctx: Context): MutableList<String> {
        try {
            val prefs = ctx.getSharedPreferences("settings", 0)
            val raw = prefs.getString("open_tab_ids", "").orEmpty()
            val ids = raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
            return ids.toMutableList()
        } catch (_: Throwable) {}
        return mutableListOf()
    }

    private fun saveOpenTabIds(ctx: Context, ids: List<String>) {
        try {
            val prefs = ctx.getSharedPreferences("settings", 0)
            prefs.edit().putString("open_tab_ids", ids.joinToString(",")).apply()
        } catch (_: Throwable) {}
    }

    private fun ensureTabOpen(id: String) {
        val ctx = appCtx ?: context ?: return
        val ids = loadOpenTabIds(ctx)
        if (!ids.contains(id)) {
            ids.add(0, id)
            saveOpenTabIds(ctx, ids)
            openTabIds = ids.toMutableList()
        }
    }

    private fun closeTabId(id: String) {
        val ctx = appCtx ?: context ?: return
        val ids = loadOpenTabIds(ctx).filter { it != id }
        saveOpenTabIds(ctx, ids)
        openTabIds = ids.toMutableList()
    }

    private fun confirmNewChat() {
        try {
            val app = appCtx ?: return
            // Persist current first
            saveChatHistoryNow()
            val created = ConversationStore.newConversation()
            ConversationStore.updateConversation(app, created)
            ConversationStore.setCurrentId(app, created.id)
            ensureTabOpen(created.id)
            applyConversation(created, scrollToBottom = false)
            refreshTabs(scrollToCurrent = true)
            scheduleSaveChatHistory()
        } catch (_: Throwable) {}
    }

    private fun showChatMoreMenu() {
        val ctx = context ?: return
        val opts = arrayOf("Edit chat (folder/tags/icon)", "Settings", "Share chat", "Delete chat")
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Chat")
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> showEditChatDialog()
                    1 -> {
                        try {
                            requireActivity().supportFragmentManager.commit {
                                replace(R.id.fragment_container, SettingsFragment())
                            }
                        } catch (_: Throwable) {}
                    }
                    2 -> shareChatToClipboard()
                    3 -> confirmDeleteChat()
                }
            }
            .show()
    }

    fun setLibraryVisible(visible: Boolean) {
        val container = view?.findViewById<View>(R.id.chat_library_container) ?: return
        val list = view?.findViewById<View>(R.id.messages_list)
        libraryVisible = visible
        container.visibility = if (visible) View.VISIBLE else View.GONE
        list?.visibility = if (visible) View.GONE else View.VISIBLE
        if (visible) {
            val tag = "library_panel"
            val fm = childFragmentManager
            val existing = fm.findFragmentByTag(tag)
            if (existing == null) {
                fm.commit {
                    replace(R.id.chat_library_container, LibraryFragment(), tag)
                }
            }
        }
    }

    fun toggleLibraryVisible() {
        setLibraryVisible(!libraryVisible)
    }

    private fun confirmCloseChat(item: ConversationStore.Conversation) {
        val ctx = context ?: return
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Close chat")
            .setMessage("Close this chat? It will stay in Library.")
            .setPositiveButton("Close") { _, _ ->
                try {
                    val app = appCtx ?: return@setPositiveButton
                    closeTabId(item.id)
                    if (conversation?.id == item.id) {
                        val all = try { ConversationStore.loadAll(app) } catch (_: Throwable) { emptyList() }
                        val byId = all.associateBy { it.id }
                        val next = openTabIds.firstOrNull()?.let { byId[it] }
                        if (next != null) {
                            ConversationStore.setCurrentId(app, next.id)
                            applyConversation(next, scrollToBottom = true)
                        } else if (all.isNotEmpty()) {
                            val pick = all.first()
                            ConversationStore.setCurrentId(app, pick.id)
                            ensureTabOpen(pick.id)
                            applyConversation(pick, scrollToBottom = true)
                        } else {
                            confirmNewChat()
                        }
                    }
                    refreshTabs(scrollToCurrent = true)
                } catch (_: Throwable) {}
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTabMenu(item: ConversationStore.Conversation) {
        val ctx = context ?: return
        val opts = arrayOf("AI title", "Edit chat", "Close chat")
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Tab")
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> confirmAiTitle(item)
                    1 -> showEditChatDialog(item)
                    2 -> confirmCloseChat(item)
                }
            }
            .show()
    }

    private fun showEditChatDialog(item: ConversationStore.Conversation? = null) {
        val ctx = context ?: return
        val app = appCtx ?: return
        val c0 = item ?: (conversation ?: ConversationStore.loadOrCreateCurrent(app).also { conversation = it })

        val layout = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        var selectedIcon = c0.icon.ifBlank { "💬" }
        val inputTitle = EditText(ctx).apply { hint = "Title"; setText(normalizeTitle(c0.title)) }
        val iconButton = TextView(ctx).apply {
            text = selectedIcon
            textSize = 22f
            setPadding(8, 4, 12, 4)
            setOnClickListener {
                showIconPicker(ctx, selectedIcon) { picked ->
                    selectedIcon = picked
                    text = picked
                }
            }
        }
        val titleRow = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        titleRow.addView(iconButton)
        titleRow.addView(
            inputTitle,
            android.widget.LinearLayout.LayoutParams(
                0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        var folderPath = c0.folder.trim().ifBlank { "inbox" }
        val folderRow = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
        }
        val folderLabel = TextView(ctx).apply {
            text = folderPath
            setPadding(12, 6, 12, 6)
            setBackgroundResource(R.drawable.bg_btn)
        }
        val btnSetFolder = android.widget.Button(ctx).apply {
            text = "Set folder"
            setOnClickListener {
                val allFolders =
                    try {
                        ConversationStore.loadAll(app).map { it.folder.trim().ifBlank { "inbox" } }
                            .distinct()
                            .sorted()
                    } catch (_: Throwable) { emptyList() }
                val items = mutableListOf("Inbox", "New folder…").apply { addAll(allFolders) }.distinct()
                androidx.appcompat.app.AlertDialog.Builder(ctx)
                    .setTitle("Set folder")
                    .setItems(items.toTypedArray()) { _, which ->
                        val pick = items[which]
                        if (pick == "New folder…") {
                            val input = EditText(ctx).apply { hint = "Folder"; setText(folderPath) }
                            androidx.appcompat.app.AlertDialog.Builder(ctx)
                                .setTitle("New folder")
                                .setView(input)
                                .setPositiveButton("Set") { _, _ ->
                                    val v = input.text?.toString()?.trim().orEmpty()
                                    folderPath = if (v.isBlank() || v.equals("inbox", true)) "inbox" else v
                                    folderLabel.text = folderPath
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        } else {
                            folderPath = if (pick.equals("inbox", true)) "inbox" else pick
                            folderLabel.text = folderPath
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        val btnAddSub = android.widget.Button(ctx).apply {
            text = "+"
            setOnClickListener {
                val input = EditText(ctx).apply { hint = "Subfolder" }
                androidx.appcompat.app.AlertDialog.Builder(ctx)
                    .setTitle("Add subfolder")
                    .setView(input)
                    .setPositiveButton("Add") { _, _ ->
                        val v = input.text?.toString()?.trim().orEmpty()
                        if (v.isNotBlank()) {
                            folderPath = folderPath.trimEnd('/') + "/" + v
                            folderLabel.text = folderPath
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        folderRow.addView(folderLabel)
        folderRow.addView(btnSetFolder)
        folderRow.addView(btnAddSub)

        val tags = c0.tags.toMutableList()
        val tagsGroup = ChipGroup(ctx).apply {
            isSingleSelection = false
            setPadding(0, 8, 0, 8)
        }
        fun renderTags() {
            tagsGroup.removeAllViews()
            for (t in tags) {
                val chip = Chip(ctx)
                chip.text = t
                chip.isCloseIconVisible = true
                chip.setOnCloseIconClickListener {
                    tags.remove(t)
                    renderTags()
                }
                tagsGroup.addView(chip)
            }
        }
        fun addTags(raw: String) {
            val pieces = raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
            for (p in pieces) {
                if (tags.none { it.equals(p, ignoreCase = true) }) {
                    tags.add(p)
                }
            }
            renderTags()
        }
        val existingTags = try {
            ConversationStore.loadAll(app)
                .flatMap { it.tags }
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .sortedBy { it.lowercase() }
        } catch (_: Throwable) {
            emptyList()
        }
        val btnAddTag = android.widget.Button(ctx).apply {
            text = "Add tag"
            setOnClickListener {
                var dialog: androidx.appcompat.app.AlertDialog? = null
                val wrap = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(36, 12, 36, 0)
                }
                val input = android.widget.AutoCompleteTextView(ctx).apply {
                    hint = "tag, tag2"
                    if (existingTags.isNotEmpty()) {
                        setAdapter(
                            android.widget.ArrayAdapter(
                                ctx,
                                android.R.layout.simple_dropdown_item_1line,
                                existingTags
                            )
                        )
                        threshold = 1
                        setOnClickListener { showDropDown() }
                    }
                }
                wrap.addView(input)
                if (existingTags.isNotEmpty()) {
                    val label = TextView(ctx).apply {
                        text = "Existing"
                        setPadding(0, 12, 0, 6)
                    }
                    val group = ChipGroup(ctx).apply {
                        isSingleSelection = false
                    }
                    for (t in existingTags) {
                        val chip = Chip(ctx).apply {
                            text = t
                            setOnClickListener {
                                addTags(t)
                                try { dialog?.dismiss() } catch (_: Throwable) {}
                            }
                        }
                        group.addView(chip)
                    }
                    wrap.addView(label)
                    wrap.addView(group)
                }
                dialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
                    .setTitle("Add tag")
                    .setView(wrap)
                    .setPositiveButton("Add") { _, _ ->
                        addTags(input.text?.toString().orEmpty())
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        renderTags()
        var selectedColor = c0.color
        val colorRow = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
        }
        val colorDot = View(ctx).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(24, 24)
        }
        val colorLabel = TextView(ctx).apply {
            text = colorNameFor(selectedColor)
            setPadding(12, 0, 0, 0)
        }
        colorRow.addView(colorDot)
        colorRow.addView(colorLabel)
        updateColorDot(colorDot, selectedColor)

        val btnAiTitle = android.widget.Button(ctx).apply {
            text = "AI title"
            setOnClickListener {
                confirmAiTitle(c0)
            }
        }
        val btnPickColor = android.widget.Button(ctx).apply {
            text = "Pick color"
            setOnClickListener {
                showColorPicker(ctx, selectedColor) { picked ->
                    selectedColor = picked.value
                    updateColorDot(colorDot, selectedColor)
                    colorLabel.text = picked.name
                }
            }
        }

        layout.addView(titleRow)
        layout.addView(folderRow)
        layout.addView(tagsGroup)
        layout.addView(btnAddTag)
        layout.addView(colorRow)
        layout.addView(btnPickColor)
        layout.addView(btnAiTitle)

        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Edit chat")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                try {
                    val color = selectedColor
                    val safeTitle = normalizeTitle(inputTitle.text?.toString().orEmpty())
                    val updated = ConversationStore.updateMeta(
                        c0,
                        title = safeTitle,
                        folder = folderPath,
                        tagsCsv = tags.joinToString(", "),
                        icon = selectedIcon,
                        color = color,
                    )
                    ConversationStore.updateConversation(app, updated)
                    if (conversation?.id == updated.id) {
                        conversation = updated
                    setPromptTitle(updated)
                    }
                    refreshTabs(scrollToCurrent = true)
                    try {
                        val lib = activity?.supportFragmentManager?.findFragmentById(R.id.drawer_library_container)
                        if (lib is LibraryFragment) lib.refreshFromStore()
                    } catch (_: Throwable) {}
                } catch (_: Throwable) {}
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteChat(item: ConversationStore.Conversation? = null) {
        val ctx = context ?: return
        val app = appCtx ?: return
        val c0 = item ?: conversation ?: return
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Delete chat")
            .setMessage("Delete this conversation from Library?")
            .setPositiveButton("Delete") { _, _ ->
                try {
                    ConversationStore.deleteConversation(app, c0.id)
                    val next = ConversationStore.loadOrCreateCurrent(app)
                    applyConversation(next, scrollToBottom = true)
                    refreshTabs(scrollToCurrent = true)
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

    private fun formatConversationTitleDisplay(c: ConversationStore.Conversation): CharSequence {
        val icon = c.icon.trim().ifBlank { "??" }
        val title = c.title.trim().ifBlank { "Chat" }
        val dot = "\u25CF "
        val text = dot + icon + "  " + title
        val out = SpannableStringBuilder(text)
        try {
            out.setSpan(ForegroundColorSpan(c.color), 0, dot.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } catch (_: Throwable) {}
        return out
    }

    private fun setPromptTitle(c: ConversationStore.Conversation) {
        try {
            val safe = normalizeTitle(c.title)
            if (safe != c.title) {
                val app = appCtx
                if (app != null) {
                    val updated = ConversationStore.updateMeta(c, title = safe)
                    conversation = updated
                    ConversationStore.updateConversation(app, updated)
                }
            }
            val updatedTitle = safe
            view?.findViewById<TextView>(R.id.chat_title)?.text = formatConversationTitleDisplay(c.copy(title = updatedTitle))
            val toolbarTitle = activity?.findViewById<TextView>(R.id.toolbar_prompt)
            toolbarTitle?.text = updatedTitle
        } catch (_: Throwable) {}
    }

    private fun normalizeTitle(raw: String): String {
        val t = raw.trim()
        if (t.isBlank()) return "New prompt"
        val lower = t.lowercase()
        if (t.startsWith("??")) return "New prompt"
        if (lower.contains("transcribiendo") || lower.contains("escuchando")) return "New prompt"
        if (lower.contains("transcribe")) return "New prompt"
        return t
    }

    private fun confirmAiTitle(item: ConversationStore.Conversation?) {
        val ctx = context ?: return
        val target = item ?: conversation ?: return
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("AI title")
            .setMessage("Generate a new title with AI?")
            .setPositiveButton("Generate") { _, _ -> generateAiTitle(target) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun generateAiTitle(target: ConversationStore.Conversation) {
        val seed = buildTitleSeed(target)
        if (seed.isNullOrBlank()) {
            try { Toast.makeText(requireContext(), "No text to title yet", Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
            return
        }
        Thread {
            val title = generateTitleFromText(seed) ?: compactTitle(seed, seed)
            if (title.isBlank()) return@Thread
            uiHandler.post {
                val app = appCtx ?: return@post
                val updated = ConversationStore.updateMeta(target, title = normalizeTitle(title))
                ConversationStore.updateConversation(app, updated)
                if (conversation?.id == updated.id) {
                    conversation = updated
                    setPromptTitle(updated)
                }
                refreshTabs(scrollToCurrent = true)
            }
        }.start()
    }

    private fun buildTitleSeed(target: ConversationStore.Conversation): String? {
        val msgs = target.messages
            .map { it.text.trim() to it.isMe }
            .filter { it.first.isNotBlank() }
            .filterNot { it.first.startsWith("??") }
            .filterNot { it.first.contains("transcribiendo", ignoreCase = true) }
            .filterNot { it.first.contains("escuchando", ignoreCase = true) }
        if (msgs.isEmpty()) return null
        val sb = StringBuilder()
        for ((text, isMe) in msgs.take(12)) {
            val clean = text.replace(Regex("\\s+"), " ").trim()
            if (clean.isBlank()) continue
            sb.append(if (isMe) "User: " else "AI: ")
            sb.append(clean)
            sb.append('\n')
            if (sb.length > 1400) break
        }
        return sb.toString().trim()
    }

    private fun isUntitled(raw: String): Boolean {
        val t = raw.trim().lowercase(Locale.ROOT)
        return t.isBlank() || t == "new prompt" || t == "chat"
    }

    private fun updateSendVisibility() {
        val send = sendButton ?: return
        val input = inputField
        val hasText = input?.text?.toString()?.trim()?.isNotEmpty() == true
        val hasAttachment = !pendingImageDataUrl.isNullOrBlank()
        val show = hasText || hasAttachment
        send.isEnabled = show
        send.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun updateAttachButton(btn: ImageButton) {
        val attached = pendingImageDataUrl != null
        btn.alpha = if (attached) 1.0f else 0.8f
        val icon =
            if (attached) android.R.drawable.ic_menu_close_clear_cancel
            else android.R.drawable.ic_menu_add
        btn.setImageResource(icon)
        btn.contentDescription = if (attached) "Clear attachment" else "Attach"
        try {
            btn.background?.setTint(if (attached) Color.parseColor("#2E7D32") else Color.parseColor("#263238"))
            btn.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        } catch (_: Throwable) {}
        updateSendVisibility()
    }

    private fun showAttachMenu(anchor: View) {
        val ctx = context ?: return
        val menu = android.widget.PopupMenu(ctx, anchor)
        menu.menu.add(0, 1, 0, "Camera").setIcon(android.R.drawable.ic_menu_camera)
        menu.menu.add(0, 2, 1, "Gallery").setIcon(android.R.drawable.ic_menu_gallery)
        menu.menu.add(0, 3, 2, "File").setIcon(android.R.drawable.ic_menu_save)
        try {
            val f = menu.javaClass.getDeclaredField("mPopup")
            f.isAccessible = true
            val helper = f.get(menu)
            val setIcons = helper.javaClass.getDeclaredMethod("setForceShowIcon", Boolean::class.javaPrimitiveType)
            setIcons.invoke(helper, true)
        } catch (_: Throwable) {}
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    attachMode = "camera"
                    updateAttachButton(anchor as ImageButton)
                    startCameraCapture()
                }
                2 -> {
                    attachMode = "gallery"
                    updateAttachButton(anchor as ImageButton)
                    pickImageFromGallery()
                }
                3 -> {
                    attachMode = "file"
                    updateAttachButton(anchor as ImageButton)
                    pickFileFromStorage()
                }
            }
            true
        }
        menu.show()
    }

    private fun startCameraCapture() {
        val ctx = context ?: return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingCameraAfterPermission = true
            requestPermissionCompat(Manifest.permission.CAMERA)
            return
        }
        launchCameraIntent()
    }

    private fun launchCameraIntent() {
        try {
            val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
            startActivityForResult(intent, REQ_CAMERA_CAPTURE)
        } catch (t: Throwable) {
            try { Toast.makeText(requireContext(), "Camera failed: ${t.message}", Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
        }
    }

    private fun pickFileFromStorage() {
        try {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(Intent.createChooser(intent, "Pick file"), REQ_FILE_PICK)
        } catch (t: Throwable) {
            try { Toast.makeText(requireContext(), "Pick failed: ${t.message}", Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
        }
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
        if (requestCode == REQ_CAMERA_CAPTURE) {
            val bmp = data?.extras?.get("data") as? Bitmap
            if (bmp == null) {
                try { Toast.makeText(requireContext(), "Camera canceled", Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
                return
            }
            Thread {
                val encoded = try { encodeBitmapAsDataUrl(bmp) } catch (_: Throwable) { null }
                view?.post {
                    if (encoded.isNullOrBlank()) {
                        try { Toast.makeText(requireContext(), "Couldn't attach photo", Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
                        return@post
                    }
                    pendingImageDataUrl = encoded
                    pendingImageLabel = "camera"
                    val btn = view?.findViewById<ImageButton>(R.id.btn_attach)
                    if (btn != null) updateAttachButton(btn)
                    try { Toast.makeText(requireContext(), "Photo attached", Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
                }
            }.start()
            return
        }
        if (requestCode == REQ_FILE_PICK) {
            val uri = data?.data ?: return
            Thread {
                val encoded = try { encodeFileAsDataUrl(uri) } catch (_: Throwable) { null }
                val name = try { queryFileName(uri) } catch (_: Throwable) { null }
                view?.post {
                    if (encoded.isNullOrBlank()) {
                        try { Toast.makeText(requireContext(), "Couldn't attach file", Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
                        return@post
                    }
                    pendingImageDataUrl = encoded
                    pendingImageLabel = name ?: "file"
                    val btn = view?.findViewById<ImageButton>(R.id.btn_attach)
                    if (btn != null) updateAttachButton(btn)
                    try { Toast.makeText(requireContext(), "File attached", Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
                }
            }.start()
            return
        }
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

    private fun encodeFileAsDataUrl(uri: Uri): String? {
        val ctx = context ?: return null
        val cr = ctx.contentResolver
        val mime = cr.getType(uri) ?: "application/octet-stream"
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (bytes.isEmpty()) return null
        if (bytes.size > 2_500_000) return null
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "data:$mime;base64,$b64"
    }

    private fun queryFileName(uri: Uri): String? {
        val ctx = context ?: return null
        val cr = ctx.contentResolver
        val cursor = cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return it.getString(idx)
            }
        }
        return null
    }

    private fun encodeBitmapAsDataUrl(bmp: Bitmap): String? {
        val maxDim = 1024
        val w = bmp.width
        val h = bmp.height
        val scale = if (w >= h) (maxDim.toFloat() / w.toFloat()) else (maxDim.toFloat() / h.toFloat())
        val scaled =
            if (scale >= 1f) bmp
            else Bitmap.createScaledBitmap(
                bmp,
                (w * scale).toInt().coerceAtLeast(1),
                (h * scale).toInt().coerceAtLeast(1),
                true
            )
        val out = java.io.ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
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
        if (old.isBlank()) return

        val input = EditText(ctx).apply {
            setText(old)
            setSelection(text?.length ?: 0)
        }

        val note = TextView(ctx).apply {
            text = "Re-runs from here."
            textSize = 10f
            setTextColor(Color.parseColor("#78909C"))
            setPadding(0, 6, 0, 0)
            alpha = 0.8f
        }
        val wrap = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        wrap.addView(input)
        wrap.addView(note)

        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Edit message")
            .setView(wrap)
            .setMessage(null)
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
            refreshTabs(scrollToCurrent = false)
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
                            addDebug("backend_error url=$url status=${resp.code} body=${raw.take(200)}")
                        }
                        callOpenAIAndPost(userText, vaultId, imageDataUrl)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Backend request failed, intentando OpenAI directo", e)
                Handler(Looper.getMainLooper()).post {
                    addDebug("backend_exception url=$url err=${e.message}")
                }
                callOpenAIAndPost(userText, vaultId, imageDataUrl)
            }
        }.start()
    }

    private fun sendDirectToOpenAI(userText: String, vaultId: String?, imageDataUrl: String?) {
        Thread { callOpenAIAndPost(userText, vaultId, imageDataUrl) }.start()
    }

    private fun maybeAutoTitle(c0: ConversationStore.Conversation) {
        val ctx = appCtx ?: context ?: return
        val prefs = ctx.getSharedPreferences("settings", 0)
        val key = "title_generated_" + c0.id
        if (prefs.getBoolean(key, false)) return
        val seed = buildTitleSeed(c0) ?: return
        if (seed.length < 6) return
        prefs.edit().putBoolean(key, true).apply()
        Thread {
            val title = generateTitleFromText(seed) ?: compactTitle(seed, seed)
            if (title.isBlank()) return@Thread
            uiHandler.post {
                val app = appCtx ?: return@post
                val current = conversation
                if (current == null || current.id != c0.id) return@post
                val updated = ConversationStore.updateMeta(current, title = title)
                conversation = updated
                ConversationStore.updateConversation(app, updated)
                setPromptTitle(updated)
                refreshTabs(scrollToCurrent = true)
            }
        }.start()
    }

    private fun generateTitleFromText(seed: String): String? {
        val ctx = appCtx ?: context ?: return null
        val groqKey = try { ctx.getString(R.string.groq_api_key).trim() } catch (_: Throwable) { "" }
        val openaiKey = try { ctx.getString(R.string.openai_api_key).trim() } catch (_: Throwable) { "" }
        val useGroq = groqKey.isNotBlank()
        val key = if (useGroq) groqKey else openaiKey
        if (key.isBlank()) return null
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
        val prompt = "Return only a 2-4 word keyword title. Use key nouns only, no articles, no punctuation, no quotes, no extra text."
        val payload = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", prompt))
                put(JSONObject().put("role", "user").put("content", seed))
            })
        }.toString()
        return try {
            val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $key")
                .post(body)
                .build()
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    addDebug("title api_error status=${resp.code}")
                    return compactTitle(seed, seed)
                }
                val content =
                    try {
                        val root = JSONObject(raw)
                        val choices = root.optJSONArray("choices")
                        val first = choices?.optJSONObject(0)
                        first?.optJSONObject("message")?.optString("content").orEmpty()
                    } catch (_: Throwable) {
                        ""
                    }
                val clean = content.trim().trim('"', '\'', '`').replace(Regex("\\s+"), " ")
                if (isRefusal(clean)) return compactTitle(seed, seed)
                val compact = normalizeAiTitle(clean, seed)
                if (compact.length > 44) compact.take(44).trimEnd() else compact
            }
        } catch (t: Throwable) {
            addDebug("title api_exception err=${t.message}")
            compactTitle(seed, seed)
        }
    }

    private fun isRefusal(text: String): Boolean {
        val t = text.lowercase(Locale.ROOT)
        return t.contains("cannot") ||
            t.contains("can't") ||
            t.contains("cannot comply") ||
            t.contains("i can't") ||
            t.contains("as an ai") ||
            t.contains("can't help")
    }

    private fun normalizeAiTitle(raw: String, seed: String): String {
        var text = raw.trim()
        if (text.contains('\n')) {
            text = text.lineSequence().firstOrNull().orEmpty().trim()
        }
        text = text.replace(Regex("^(title|titulo|prompt|chat)\\s*[:\\-]\\s*", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("^(title|titulo|prompt|chat)\\s+", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("[\"'`]+"), "")
        text = text.replace(Regex("\\s+"), " ").trim()
        val compact = compactTitle(text, seed)
        val lower = compact.lowercase(Locale.ROOT)
        if (lower.contains("transcribiendo") || lower.contains("escuchando") || lower.contains("transcribe")) {
            return compactTitle(seed, seed)
        }
        return compact
    }

    private fun compactTitle(raw: String, seed: String): String {
        fun keywordsFrom(text: String): List<String> {
            val tokens = Regex("[\\p{L}\\p{N}]+").findAll(text).map { it.value }.toList()
            if (tokens.isEmpty()) return emptyList()
            val stop = setOf(
                "de", "la", "el", "los", "las", "y", "o", "a", "en", "para", "por", "con", "sin",
                "un", "una", "unos", "unas", "the", "and", "or", "to", "of", "in", "for", "with",
                "on", "at", "by", "from", "is", "are", "be", "this", "that", "these", "those",
                "here", "some", "title", "titulo", "prompt", "chat", "keyword", "keywords", "word", "words"
            )
            val filtered = tokens.filter { t ->
                t.any { it.isLetter() } && !stop.contains(t.lowercase())
            }
            return (if (filtered.isNotEmpty()) filtered else tokens).take(4)
        }
        val kw = keywordsFrom(raw).ifEmpty { keywordsFrom(seed) }
        return if (kw.isEmpty()) "New prompt" else kw.joinToString(" ")
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
                    addDebug("vision_model_required model=$model use a Groq vision model in settings")
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
                        addDebug("api_error provider=" + (if (useGroq) "groq" else "openai") + " status=${resp.code} body=${raw.take(200)}")
                    }
                    addMessage(reply, false)
                    maybeAutoSpeakReply(reply)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "API request failed", e)
            Handler(Looper.getMainLooper()).post {
                addDebug("api_exception err=${e.message}")
                addMessage("Provider fallo: ${e.message}", false)
            }
        }
    }

    private val REQ_AUDIO = 1001
    private val REQ_CAMERA = 1002
    private val REQ_CAMERA_CAPTURE = 2203
    private val REQ_FILE_PICK = 2204
    private fun requestPermissionCompat(permission: String) {
        if (ContextCompat.checkSelfPermission(requireContext(), permission) != PackageManager.PERMISSION_GRANTED) {
            val code = when (permission) {
                Manifest.permission.RECORD_AUDIO -> REQ_AUDIO
                Manifest.permission.CAMERA -> REQ_CAMERA
                else -> 0
            }
            ActivityCompat.requestPermissions(requireActivity(), arrayOf(permission), code)
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
        val status = "Whisper: " + text
        tv.visibility = View.GONE
        try {
            val prefs = requireContext().getSharedPreferences("settings", 0)
            prefs.edit()
                .putString("whisper_status_text", status)
                .putBoolean("whisper_status_ok", ok)
                .apply()
        } catch (_: Throwable) {}
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
                val preview = prefs?.getBoolean(SettingsFragment.KEY_MIC_PREVIEW, false) == true
                val autoSend = prefs?.getBoolean(SettingsFragment.KEY_MIC_AUTO_SEND, true) == true
                if (preview || !autoSend) {
                    val input = inputField
                    if (input != null) {
                        val existing = input.text?.toString().orEmpty().trim()
                        val merged = if (existing.isBlank()) text else (existing + " " + text)
                        input.setText(merged)
                        input.setSelection(merged.length)
                        updateSendVisibility()
                    }
                    return
                }
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
        }
    }

    private fun shouldShowDebugInChat(msg: String): Boolean {
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
            addDebug("speech: no recognizer available")
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
            override fun onReadyForSpeech(params: Bundle?) { addDebug("speech: ready") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { isListening = false }
            override fun onError(error: Int) { isListening = false; addDebug("speech error: $error") }
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
        if (requestCode == REQ_CAMERA) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            val shouldLaunch = granted && pendingCameraAfterPermission
            pendingCameraAfterPermission = false
            if (shouldLaunch) launchCameraIntent()
        }
    }

    private fun updateAutoSendButton() {
        val btn = autoSendButton ?: return
        val prefs = appCtx?.getSharedPreferences("settings", 0)
        val auto = prefs?.getBoolean(SettingsFragment.KEY_MIC_AUTO_SEND, true) == true
        btn.visibility = View.VISIBLE
        btn.isEnabled = true
        btn.setImageResource(android.R.drawable.ic_menu_upload)
        btn.contentDescription = if (auto) "Auto-send ON" else "Auto-send OFF"
        btn.alpha = if (auto) 1.0f else 0.55f
        try {
            btn.background?.setTint(if (auto) Color.parseColor("#2E7D32") else Color.parseColor("#263238"))
            btn.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        } catch (_: Throwable) {}
        btn.setOnClickListener { toggleAutoSend() }
    }

    private fun toggleAutoSend() {
        try {
            val prefs = appCtx?.getSharedPreferences("settings", 0) ?: return
            val next = !prefs.getBoolean(SettingsFragment.KEY_MIC_AUTO_SEND, true)
            prefs.edit().putBoolean(SettingsFragment.KEY_MIC_AUTO_SEND, next).apply()
            updateAutoSendButton()
        } catch (_: Throwable) {}
    }
}

class ChatTabsAdapter(
    private var items: List<ConversationStore.Conversation>,
    private var activeId: String?,
    private val onClick: (ConversationStore.Conversation) -> Unit,
    private val onClose: (ConversationStore.Conversation) -> Unit,
    private val onLongClick: (ConversationStore.Conversation) -> Unit,
) : RecyclerView.Adapter<ChatTabVH>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatTabVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_tab, parent, false)
        return ChatTabVH(v, onClick, onClose, onLongClick)
    }
    override fun onBindViewHolder(holder: ChatTabVH, position: Int) {
        holder.bind(items[position], items[position].id == activeId)
    }
    override fun getItemCount(): Int = items.size
    fun update(newItems: List<ConversationStore.Conversation>, activeId: String?) {
        items = newItems
        this.activeId = activeId
        notifyDataSetChanged()
    }
    fun setActiveId(id: String?) {
        activeId = id
        notifyDataSetChanged()
    }
}

class ChatTabVH(
    view: View,
    private val onClick: (ConversationStore.Conversation) -> Unit,
    private val onClose: (ConversationStore.Conversation) -> Unit,
    private val onLongClick: (ConversationStore.Conversation) -> Unit,
) : RecyclerView.ViewHolder(view) {
    private val label: TextView = view.findViewById(R.id.tab_label)
    private val closeBtn: ImageButton = view.findViewById(R.id.tab_close)
    private fun shortenTitle(raw: String, maxChars: Int = 18): String {
        val t = raw.trim()
        if (t.length <= maxChars) return t
        return t.take(maxChars - 3).trimEnd() + "..."
    }
    private fun buildTabTitle(icon: String, title: String, color: Int): CharSequence {
        val dot = "● "
        val text = dot + icon + "  " + title
        val out = SpannableStringBuilder(text)
        try {
            out.setSpan(ForegroundColorSpan(color), 0, dot.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } catch (_: Throwable) {}
        return out
    }
    fun bind(item: ConversationStore.Conversation, isActive: Boolean) {
        val icon = item.icon.trim().ifBlank { "??" }
        val title = item.title.trim().ifBlank { "Chat" }
        label.text = buildTabTitle(icon, shortenTitle(title), item.color)
        val bg = label.background?.mutate()
        if (bg != null) {
            val color = if (isActive) item.color else Color.parseColor("#263238")
            try { bg.setTint(color) } catch (_: Throwable) {}
        }
        label.alpha = if (isActive) 1.0f else 0.82f
        try {
            val closeBg = if (isActive) item.color else Color.parseColor("#263238")
            closeBtn.background?.mutate()?.setTint(closeBg)
            closeBtn.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        } catch (_: Throwable) {}
        itemView.setOnClickListener { onClick(item) }
        itemView.setOnLongClickListener { onLongClick(item); true }
        closeBtn.setOnClickListener { onClose(item) }
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
    private val editMe: ImageButton = view.findViewById(R.id.btn_edit_me)
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
            editMe.visibility = View.VISIBLE
            editMe.isEnabled = true
            editMe.isClickable = true
            editMe.isFocusable = true
            editMe.alpha = 1.0f
            editMe.imageTintList = ColorStateList.valueOf(Color.WHITE)
            editMe.imageAlpha = 255
            editMe.setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
            editMe.setBackgroundColor(Color.TRANSPARENT)
            editMe.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onEdit(p)
            }
            val editLongClick = View.OnLongClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onEdit(p)
                true
            }
            bubbleMe.setOnLongClickListener(editLongClick)
            textMe.setOnLongClickListener(null)
            itemView.setOnLongClickListener(null)
        } else {
            bubbleMe.visibility = View.GONE
            bubbleOther.visibility = View.VISIBLE
            textOther.text = m.text
            speakOther.visibility = if (speakable) View.VISIBLE else View.GONE
            speakOther.setOnClickListener { onSpeak(m) }
            editMe.visibility = View.GONE
            bubbleOther.setOnLongClickListener(null)
            textOther.setOnLongClickListener(null)
            itemView.setOnLongClickListener(null)
        }
    }

}
