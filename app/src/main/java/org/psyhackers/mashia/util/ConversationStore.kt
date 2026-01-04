package org.psyhackers.mashia.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object ConversationStore {
    data class ChatMessage(
        val text: String,
        val isMe: Boolean,
        val ts: Long,
    )

    data class Conversation(
        val id: String,
        val title: String,
        val folder: String,
        val tags: List<String>,
        val icon: String,
        val color: Int,
        val createdAt: Long,
        val updatedAt: Long,
        val messages: List<ChatMessage>,
    )

    private const val FILE_NAME = "conversations.json"
    private const val META_FILE = "conversations_meta.json"

    fun newId(): String = UUID.randomUUID().toString()

    fun loadAll(ctx: Context): List<Conversation> {
        val f = File(ctx.filesDir, FILE_NAME)
        if (!f.exists()) return emptyList()
        val raw = try { f.readText() } catch (_: Throwable) { "" }
        if (raw.isBlank()) return emptyList()
        val arr = try { JSONArray(raw) } catch (_: Throwable) { JSONArray() }
        val out = ArrayList<Conversation>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(o.toConversation())
        }
        return out.sortedByDescending { it.updatedAt }
    }

    fun saveAll(ctx: Context, items: List<Conversation>) {
        val arr = JSONArray()
        for (c in items) arr.put(c.toJson())
        File(ctx.filesDir, FILE_NAME).writeText(arr.toString())
    }

    fun loadOrCreateCurrent(ctx: Context): Conversation {
        val all = loadAll(ctx).toMutableList()
        val currentId = loadCurrentId(ctx)
        val existing = all.firstOrNull { it.id == currentId }
        if (existing != null) return existing

        val created = newConversation()
        all.add(0, created)
        saveAll(ctx, all)
        saveCurrentId(ctx, created.id)
        return created
    }

    fun setCurrentId(ctx: Context, id: String) {
        saveCurrentId(ctx, id)
    }

    fun updateConversation(ctx: Context, updated: Conversation) {
        val all = loadAll(ctx).toMutableList()
        val idx = all.indexOfFirst { it.id == updated.id }
        if (idx >= 0) {
            all[idx] = updated
        } else {
            all.add(0, updated)
        }
        saveAll(ctx, all)
        saveCurrentId(ctx, updated.id)
    }

    fun deleteConversation(ctx: Context, id: String) {
        val all = loadAll(ctx).filterNot { it.id == id }
        saveAll(ctx, all)
        val cur = loadCurrentId(ctx)
        if (cur == id) {
            val next = all.firstOrNull()
            if (next != null) saveCurrentId(ctx, next.id) else saveCurrentId(ctx, "")
        }
    }

    fun newConversation(
        title: String = "New prompt",
        folder: String = "inbox",
        tags: List<String> = emptyList(),
        icon: String = "💬",
        color: Int = 0xFF1565C0.toInt(),
    ): Conversation {
        val now = System.currentTimeMillis()
        return Conversation(
            id = newId(),
            title = title,
            folder = folder.trim().ifBlank { "inbox" },
            tags = normalizeTags(tags),
            icon = icon.trim().ifBlank { "💬" },
            color = color,
            createdAt = now,
            updatedAt = now,
            messages = emptyList(),
        )
    }

    fun appendMessage(c: Conversation, text: String, isMe: Boolean, ts: Long = System.currentTimeMillis()): Conversation {
        val msg = ChatMessage(text = text, isMe = isMe, ts = ts)
        return c.copy(
            updatedAt = System.currentTimeMillis(),
            messages = c.messages + msg,
            title = c.title.ifBlank { inferTitleFrom(text) },
        )
    }

    fun updateMeta(
        c: Conversation,
        title: String? = null,
        folder: String? = null,
        tagsCsv: String? = null,
        icon: String? = null,
        color: Int? = null,
    ): Conversation {
        val nextTags =
            if (tagsCsv == null) c.tags
            else normalizeTags(tagsCsv.split(",").map { it.trim() }.filter { it.isNotBlank() })

        return c.copy(
            updatedAt = System.currentTimeMillis(),
            title = (title ?: c.title).trim(),
            folder = (folder ?: c.folder).trim().ifBlank { "inbox" },
            tags = nextTags,
            icon = (icon ?: c.icon).trim().ifBlank { "💬" },
            color = color ?: c.color,
        )
    }

    private fun normalizeTags(tags: List<String>): List<String> {
        return tags
            .map { it.trim().trimStart('#') }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .take(12)
    }

    private fun inferTitleFrom(text: String): String {
        val t = text.trim()
        if (t.isBlank()) return ""
        val lower = t.lowercase()
        if (lower.startsWith("debug")) return ""
        if (t.startsWith("??")) return ""
        if (lower.contains("transcribiendo") || lower.contains("escuchando")) return ""
        val clean = t.replace("\n", " ").trim()
        return if (clean.length <= 44) clean else clean.take(44).trimEnd() + "…"
    }

    private fun Conversation.toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("title", title)
        o.put("folder", folder)
        o.put("tags", JSONArray(tags))
        o.put("icon", icon)
        o.put("color", color)
        o.put("createdAt", createdAt)
        o.put("updatedAt", updatedAt)
        val arr = JSONArray()
        for (m in messages) {
            arr.put(
                JSONObject()
                    .put("text", m.text)
                    .put("isMe", m.isMe)
                    .put("ts", m.ts)
            )
        }
        o.put("messages", arr)
        return o
    }

    private fun JSONObject.toConversation(): Conversation {
        val id = optString("id").orEmpty()
        val title = optString("title").orEmpty()
        val folder = optString("folder").orEmpty().ifBlank { "inbox" }
        val tagsArr = optJSONArray("tags") ?: JSONArray()
        val tags = ArrayList<String>(tagsArr.length())
        for (i in 0 until tagsArr.length()) tags.add(tagsArr.optString(i).orEmpty())
        val icon = optString("icon").orEmpty().ifBlank { "💬" }
        val color = try { optInt("color") } catch (_: Throwable) { 0xFF1565C0.toInt() }
        val createdAt = optLong("createdAt", 0L)
        val updatedAt = optLong("updatedAt", 0L)
        val msgs = optJSONArray("messages") ?: JSONArray()
        val messages = ArrayList<ChatMessage>(msgs.length())
        for (i in 0 until msgs.length()) {
            val m = msgs.optJSONObject(i) ?: continue
            messages.add(
                ChatMessage(
                    text = m.optString("text").orEmpty(),
                    isMe = m.optBoolean("isMe", true),
                    ts = m.optLong("ts", 0L)
                )
            )
        }
        return Conversation(
            id = id,
            title = title,
            folder = folder,
            tags = normalizeTags(tags),
            icon = icon,
            color = color,
            createdAt = createdAt,
            updatedAt = updatedAt,
            messages = messages,
        )
    }

    private fun metaFile(ctx: Context): File = File(ctx.filesDir, META_FILE)

    private fun loadCurrentId(ctx: Context): String {
        val f = metaFile(ctx)
        if (!f.exists()) return ""
        val raw = try { f.readText() } catch (_: Throwable) { "" }
        if (raw.isBlank()) return ""
        val o = try { JSONObject(raw) } catch (_: Throwable) { JSONObject() }
        return o.optString("currentId").orEmpty()
    }

    private fun saveCurrentId(ctx: Context, id: String) {
        val o = JSONObject().put("currentId", id.trim())
        metaFile(ctx).writeText(o.toString())
    }
}

