package org.psyhackers.mashia.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

object PromptVaultStore {
    private const val FILE_NAME = "prompt_vault.json"
    private const val MAX_ITEMS = 2000

    private val io = Executors.newSingleThreadExecutor()
    private val lock = Any()

    data class VaultItem(
        val id: String,
        val title: String,
        val prompt: String,
        val reply: String,
        val folder: String,
        val tags: List<String>,
        val createdAt: Long,
        val updatedAt: Long,
    )

    fun newId(): String = UUID.randomUUID().toString()

    fun savePromptAsync(ctx: Context, id: String, prompt: String) {
        val appCtx = ctx.applicationContext
        val cleaned = prompt.trim()
        if (cleaned.isBlank()) return
        val now = System.currentTimeMillis()
        io.execute { savePromptSync(appCtx, id, cleaned, now) }
    }

    fun saveReplyAsync(ctx: Context, id: String?, reply: String) {
        if (id.isNullOrBlank()) return
        val appCtx = ctx.applicationContext
        val cleaned = reply.trim()
        if (cleaned.isBlank()) return
        val now = System.currentTimeMillis()
        io.execute { saveReplySync(appCtx, id, cleaned, now) }
    }

    fun updateMetaAsync(ctx: Context, id: String, title: String?, folder: String?, tagsCsv: String?) {
        val appCtx = ctx.applicationContext
        val cleanTitle = title?.trim().orEmpty()
        val cleanFolder = folder?.trim().orEmpty()
        val cleanTags = parseTags(tagsCsv.orEmpty())
        val now = System.currentTimeMillis()
        io.execute { updateMetaSync(appCtx, id, cleanTitle, cleanFolder, cleanTags, now) }
    }

    fun updateMeta(ctx: Context, id: String, title: String?, folder: String?, tagsCsv: String?) {
        val appCtx = ctx.applicationContext
        val cleanTitle = title?.trim().orEmpty()
        val cleanFolder = folder?.trim().orEmpty()
        val cleanTags = parseTags(tagsCsv.orEmpty())
        val now = System.currentTimeMillis()
        updateMetaSync(appCtx, id, cleanTitle, cleanFolder, cleanTags, now)
    }

    fun deleteAsync(ctx: Context, id: String) {
        val appCtx = ctx.applicationContext
        io.execute { deleteSync(appCtx, id) }
    }

    fun delete(ctx: Context, id: String) {
        val appCtx = ctx.applicationContext
        deleteSync(appCtx, id)
    }

    fun loadAll(ctx: Context): List<VaultItem> {
        val appCtx = ctx.applicationContext
        return synchronized(lock) { readItems(appCtx) }
            .sortedByDescending { it.updatedAt }
    }

    private fun file(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

    private fun savePromptSync(ctx: Context, id: String, prompt: String, now: Long) {
        synchronized(lock) {
            val items = readItems(ctx).toMutableList()
            val idx = items.indexOfFirst { it.id == id }
            val existing = items.getOrNull(idx)
            val title = existing?.title?.takeIf { it.isNotBlank() } ?: defaultTitle(prompt)
            val updated = VaultItem(
                id = id,
                title = title,
                prompt = prompt,
                reply = existing?.reply.orEmpty(),
                folder = existing?.folder.orEmpty(),
                tags = existing?.tags ?: emptyList(),
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
            if (idx >= 0) items[idx] = updated else items.add(updated)
            writeItems(ctx, items)
        }
    }

    private fun saveReplySync(ctx: Context, id: String, reply: String, now: Long) {
        synchronized(lock) {
            val items = readItems(ctx).toMutableList()
            val idx = items.indexOfFirst { it.id == id }
            val existing = items.getOrNull(idx)
            val title = existing?.title?.takeIf { it.isNotBlank() } ?: defaultTitle(existing?.prompt.orEmpty())
            val updated = VaultItem(
                id = id,
                title = title,
                prompt = existing?.prompt.orEmpty(),
                reply = reply,
                folder = existing?.folder.orEmpty(),
                tags = existing?.tags ?: emptyList(),
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
            if (idx >= 0) items[idx] = updated else items.add(updated)
            writeItems(ctx, items)
        }
    }

    private fun updateMetaSync(ctx: Context, id: String, title: String, folder: String, tags: List<String>, now: Long) {
        synchronized(lock) {
            val items = readItems(ctx).toMutableList()
            val idx = items.indexOfFirst { it.id == id }
            val existing = items.getOrNull(idx) ?: return
            val finalTitle = title.ifBlank { existing.title }
            val updated = existing.copy(
                title = finalTitle,
                folder = folder,
                tags = tags,
                updatedAt = now,
            )
            items[idx] = updated
            writeItems(ctx, items)
        }
    }

    private fun deleteSync(ctx: Context, id: String) {
        synchronized(lock) {
            val items = readItems(ctx).toMutableList()
            val before = items.size
            items.removeAll { it.id == id }
            if (items.size != before) writeItems(ctx, items)
        }
    }

    private fun readItems(ctx: Context): List<VaultItem> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        val raw = try { f.readText(Charsets.UTF_8) } catch (_: Throwable) { "" }
        val s = raw.trim()
        if (s.isBlank()) return emptyList()
        return try {
            val root = JSONObject(s)
            val arr = root.optJSONArray("items") ?: JSONArray()
            val out = ArrayList<VaultItem>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").trim()
                if (id.isBlank()) continue
                val title = o.optString("title").trim()
                val prompt = o.optString("prompt").orEmpty()
                val reply = o.optString("reply").orEmpty()
                val folder = o.optString("folder").trim()
                val createdAt = o.optLong("createdAt", 0L)
                val updatedAt = o.optLong("updatedAt", createdAt)
                val tags = mutableListOf<String>()
                val tagsArr = o.optJSONArray("tags")
                if (tagsArr != null) {
                    for (j in 0 until tagsArr.length()) {
                        val t = tagsArr.optString(j).trim()
                        if (t.isNotBlank()) tags.add(t)
                    }
                }
                out.add(
                    VaultItem(
                        id = id,
                        title = title,
                        prompt = prompt,
                        reply = reply,
                        folder = folder,
                        tags = tags.distinct(),
                        createdAt = createdAt,
                        updatedAt = updatedAt,
                    )
                )
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun writeItems(ctx: Context, items: List<VaultItem>) {
        val trimmed = if (items.size <= MAX_ITEMS) items else items.sortedByDescending { it.updatedAt }.take(MAX_ITEMS)
        val root = JSONObject().apply {
            put("v", 1)
            put("items", JSONArray().apply {
                for (it in trimmed) {
                    put(
                        JSONObject()
                            .put("id", it.id)
                            .put("title", it.title)
                            .put("prompt", it.prompt)
                            .put("reply", it.reply)
                            .put("folder", it.folder)
                            .put("tags", JSONArray(it.tags))
                            .put("createdAt", it.createdAt)
                            .put("updatedAt", it.updatedAt)
                    )
                }
            })
        }
        val payload = root.toString()

        val dir = ctx.filesDir
        val dst = File(dir, FILE_NAME)
        val tmp = File(dir, "$FILE_NAME.tmp")
        try { tmp.writeText(payload, Charsets.UTF_8) } catch (_: Throwable) { return }
        try { if (dst.exists()) dst.delete() } catch (_: Throwable) {}
        if (!tmp.renameTo(dst)) {
            try {
                dst.writeText(payload, Charsets.UTF_8)
                tmp.delete()
            } catch (_: Throwable) {}
        }
    }

    private fun defaultTitle(prompt: String): String {
        val firstLine = prompt.trim().lineSequence().firstOrNull().orEmpty().trim()
        val t = if (firstLine.isNotBlank()) firstLine else prompt.trim()
        return if (t.length <= 40) t else t.take(40).trimEnd() + "…"
    }

    private fun parseTags(csv: String): List<String> {
        return csv.split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { it.lowercase() }
            .distinct()
            .take(20)
    }
}
