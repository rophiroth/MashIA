package org.psyhackers.mashia.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ChatHistoryStore {
    private const val FILE_NAME = "chat_history.json"
    private const val MAX_MESSAGES = 250

    fun load(ctx: Context): List<Message> {
        val f = File(ctx.filesDir, FILE_NAME)
        if (!f.exists()) return emptyList()

        val raw = try { f.readText(Charsets.UTF_8) } catch (_: Throwable) { "" }
        val s = raw.trim()
        if (s.isBlank()) return emptyList()

        val out = mutableListOf<Message>()
        try {
            val arr: JSONArray? = when {
                s.startsWith("[") -> JSONArray(s)
                else -> JSONObject(s).optJSONArray("messages")
            }
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val text = o.optString("t").orEmpty()
                    val isMe = o.optBoolean("me", false)
                    if (text.isNotBlank()) out.add(Message(text, isMe))
                }
            }
        } catch (_: Throwable) {
            return emptyList()
        }

        return out.takeLast(MAX_MESSAGES)
    }

    fun save(ctx: Context, messages: List<Message>) {
        val filtered = messages.filter { shouldPersist(it) }
        val cleaned = if (filtered.size <= MAX_MESSAGES) filtered else filtered.takeLast(MAX_MESSAGES)

        val root = JSONObject().apply {
            put("v", 1)
            put("messages", JSONArray().apply {
                for (m in cleaned) {
                    put(JSONObject().put("t", m.text).put("me", m.isMe))
                }
            })
        }

        val dir = ctx.filesDir
        val dst = File(dir, FILE_NAME)
        val tmp = File(dir, "$FILE_NAME.tmp")
        val payload = root.toString()

        try { tmp.writeText(payload, Charsets.UTF_8) } catch (_: Throwable) { return }
        try {
            if (dst.exists()) dst.delete()
        } catch (_: Throwable) {}
        if (!tmp.renameTo(dst)) {
            try {
                dst.writeText(payload, Charsets.UTF_8)
                tmp.delete()
            } catch (_: Throwable) {}
        }
    }

    private fun shouldPersist(m: Message): Boolean {
        val t = m.text.trim()
        if (t.isBlank()) return false
        if (t.startsWith("DEBUG", ignoreCase = true)) return false

        val lower = t.lowercase()
        val looksLiveAudio = t.startsWith("??") || t.startsWith("\uD83C\uDFA4")
        if (looksLiveAudio && (lower.contains("escuchando") || lower.contains("transcribiendo"))) return false
        if (t.startsWith("?? .")) return false
        if (lower.contains("sin transcrip")) return false

        return true
    }
}
