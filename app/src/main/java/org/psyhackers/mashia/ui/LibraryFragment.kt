package org.psyhackers.mashia.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.SearchView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import org.psyhackers.mashia.R
import org.psyhackers.mashia.util.ConversationStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LibraryFragment : Fragment() {
    private var all: List<ConversationStore.Conversation> = emptyList()
    private var filtered: List<ConversationStore.Conversation> = emptyList()
    private lateinit var adapter: ConversationsAdapter
    private var syncingChips: Boolean = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_library, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val list = view.findViewById<RecyclerView>(R.id.projects_list)
        val search = view.findViewById<SearchView>(R.id.search_view)
        val folderChips = view.findViewById<ChipGroup>(R.id.folder_chips)
        val tagChips = view.findViewById<ChipGroup>(R.id.tag_chips)

        adapter = ConversationsAdapter(
            filtered,
            onClick = { item -> openConversation(item) },
            onLongClick = { item -> showEditDialog(item) },
        )
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter

        search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                filter()
                return true
            }
            override fun onQueryTextChange(newText: String?): Boolean {
                filter()
                return true
            }
        })

        folderChips.setOnCheckedStateChangeListener { _, _ -> if (!syncingChips) filter() }
        tagChips.setOnCheckedStateChangeListener { group, checkedIds ->
            if (syncingChips) return@setOnCheckedStateChangeListener
            syncingChips = true
            try {
                val chips = group.childrenChips()
                val allChip = chips.firstOrNull { it.tag?.toString()?.trim()?.lowercase(Locale.ROOT) == "all" }
                val checkedChips = checkedIds.mapNotNull { id -> group.findViewById<Chip>(id) }
                val allChecked = checkedChips.any { it.tag?.toString()?.trim()?.lowercase(Locale.ROOT) == "all" }
                val nonAllChecked = checkedChips.any { it.tag?.toString()?.trim()?.lowercase(Locale.ROOT) != "all" }

                when {
                    allChecked && nonAllChecked -> {
                        for (c in chips) {
                            val isAll = c.tag?.toString()?.trim()?.lowercase(Locale.ROOT) == "all"
                            c.isChecked = isAll
                        }
                    }
                    nonAllChecked -> allChip?.isChecked = false
                    else -> allChip?.isChecked = true
                }
            } catch (_: Throwable) {
            } finally {
                syncingChips = false
            }
            filter()
        }

        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val ctx = context ?: return
        Thread {
            val items = try { ConversationStore.loadAll(ctx) } catch (_: Throwable) { emptyList() }
            view?.post {
                all = items
                rebuildChips()
                filter()
            }
        }.start()
    }

    private fun rebuildChips() {
        val v = view ?: return
        val folderChips = v.findViewById<ChipGroup>(R.id.folder_chips)
        val tagChips = v.findViewById<ChipGroup>(R.id.tag_chips)

        val folders = all
            .map { normalizeFolder(it.folder) }
            .distinct()
            .sorted()

        val tags = all
            .flatMap { it.tags }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()

        syncingChips = true
        try {
            folderChips.removeAllViews()
            addChip(folderChips, "all", checked = true)
            for (f in folders) addChip(folderChips, f)

            tagChips.removeAllViews()
            addChip(tagChips, "all", checked = true)
            for (t in tags) addChip(tagChips, t)
        } finally {
            syncingChips = false
        }
    }

    private fun filter() {
        val v = view ?: return
        val qRaw = v.findViewById<SearchView>(R.id.search_view).query?.toString()?.trim().orEmpty()

        val folderChips = v.findViewById<ChipGroup>(R.id.folder_chips)
        val tagChips = v.findViewById<ChipGroup>(R.id.tag_chips)

        val folder = folderChips.checkedChipIds.firstOrNull()?.let { id ->
            folderChips.findViewById<Chip>(id)?.tag?.toString()?.trim().orEmpty()
        }.orEmpty().ifBlank { "all" }

        val selectedTags = tagChips.childrenChips()
            .filter { it.isChecked }
            .mapNotNull { it.tag?.toString()?.trim() }
            .filter { it.isNotBlank() && it.lowercase(Locale.ROOT) != "all" }
            .map { it.lowercase(Locale.ROOT) }
            .toSet()

        data class QueryParts(
            val textTokens: List<String>,
            val folder: String?,
            val tags: Set<String>,
        )

        fun parseQuery(raw: String): QueryParts {
            val tokens = raw
                .split(Regex("\\s+"))
                .map { it.trim() }
                .filter { it.isNotBlank() }

            var folderFilter: String? = null
            val tagFilters = linkedSetOf<String>()
            val textTokens = ArrayList<String>()

            for (t0 in tokens) {
                val t = t0.lowercase(Locale.ROOT)
                when {
                    t.startsWith("folder:") -> {
                        val f = t.removePrefix("folder:").trim()
                        if (f.isNotBlank()) folderFilter = f
                    }
                    t.startsWith("f:") -> {
                        val f = t.removePrefix("f:").trim()
                        if (f.isNotBlank()) folderFilter = f
                    }
                    t.startsWith("tag:") -> {
                        val tg = t.removePrefix("tag:").trim().trimStart('#')
                        if (tg.isNotBlank()) tagFilters.add(tg)
                    }
                    t.startsWith("t:") -> {
                        val tg = t.removePrefix("t:").trim().trimStart('#')
                        if (tg.isNotBlank()) tagFilters.add(tg)
                    }
                    t.startsWith("#") && t.length > 1 -> tagFilters.add(t.removePrefix("#").trim())
                    else -> textTokens.add(t)
                }
            }

            return QueryParts(textTokens, folderFilter, tagFilters)
        }

        val qp = parseQuery(qRaw)

        fun matchesText(it: ConversationStore.Conversation): Boolean {
            if (qp.textTokens.isEmpty()) return true
            val hay = buildString {
                append(it.title).append('\n')
                append(normalizeFolder(it.folder)).append('\n')
                if (it.tags.isNotEmpty()) append(it.tags.joinToString(" ")).append('\n')
                for (m in it.messages.takeLast(120)) append(m.text).append('\n')
            }.lowercase(Locale.ROOT)
            return qp.textTokens.all { tok -> hay.contains(tok) }
        }

        fun matchesFolder(it: ConversationStore.Conversation): Boolean {
            val f = normalizeFolder(it.folder).lowercase(Locale.ROOT)
            val chipFolder = folder.lowercase(Locale.ROOT)
            if (chipFolder != "all" && f != chipFolder) return false
            val qFolder = qp.folder?.trim()?.lowercase(Locale.ROOT)
            if (!qFolder.isNullOrBlank() && f != qFolder) return false
            return true
        }

        fun matchesTags(it: ConversationStore.Conversation): Boolean {
            val itemTags = it.tags.map { t -> t.trim().lowercase(Locale.ROOT) }.filter { it.isNotBlank() }.toSet()
            if (selectedTags.isNotEmpty() && itemTags.none { t -> selectedTags.contains(t) }) return false
            if (qp.tags.isNotEmpty() && !itemTags.containsAll(qp.tags)) return false
            return true
        }

        filtered = all.filter { matchesFolder(it) && matchesTags(it) && matchesText(it) }
        adapter.update(filtered)
    }

    private fun openConversation(item: ConversationStore.Conversation) {
        val ctx = context ?: return
        try { ConversationStore.setCurrentId(ctx, item.id) } catch (_: Throwable) {}
        try {
            requireActivity().supportFragmentManager.commit {
                replace(R.id.fragment_container, ChatFragment())
            }
        } catch (_: Throwable) {}
    }

    private fun showEditDialog(item: ConversationStore.Conversation) {
        val ctx = context ?: return
        val layout = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val inputTitle = EditText(ctx).apply { hint = "Title"; setText(item.title) }
        val inputFolder = EditText(ctx).apply { hint = "Folder (e.g. inbox, work)"; setText(normalizeFolder(item.folder)) }
        val inputTags = EditText(ctx).apply { hint = "Tags (comma separated)"; setText(item.tags.joinToString(", ")) }
        val inputIcon = EditText(ctx).apply { hint = "Icon (emoji)"; setText(item.icon.ifBlank { "💬" }) }
        val inputColor = EditText(ctx).apply { hint = "Color hex (e.g. FF1565C0)"; setText(Integer.toHexString(item.color)) }

        val btnPickIcon = android.widget.Button(ctx).apply {
            text = "Pick icon"
            setOnClickListener {
                val icons = arrayOf("💬", "📚", "🧠", "🧘", "💼", "🛠️", "❤️", "⭐", "🔥", "✅")
                AlertDialog.Builder(ctx)
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
                AlertDialog.Builder(ctx)
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

        AlertDialog.Builder(ctx)
            .setTitle("Edit chat")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                Thread {
                    try {
                        val hex = inputColor.text?.toString()?.trim().orEmpty().trimStart('#')
                        val c = hex.toLongOrNull(16)?.toInt() ?: item.color
                        val updated = ConversationStore.updateMeta(
                            item,
                            title = inputTitle.text?.toString(),
                            folder = inputFolder.text?.toString(),
                            tagsCsv = inputTags.text?.toString(),
                            icon = inputIcon.text?.toString(),
                            color = c,
                        )
                        ConversationStore.updateConversation(ctx, updated)
                    } catch (_: Throwable) {}
                    view?.post { reload() }
                }.start()
            }
            .setNeutralButton("Delete") { _, _ ->
                Thread {
                    try { ConversationStore.deleteConversation(ctx, item.id) } catch (_: Throwable) {}
                    view?.post { reload() }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun copyToClipboard(label: String, text: String) {
        val ctx = context ?: return
        val t = text.trim()
        if (t.isBlank()) return
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            cm.setPrimaryClip(ClipData.newPlainText(label, t))
            try { android.widget.Toast.makeText(ctx, "Copied $label", android.widget.Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }
}

private fun normalizeFolder(folder: String): String {
    val f = folder.trim()
    return if (f.isBlank()) "inbox" else f
}

private fun ChipGroup.childrenChips(): List<Chip> {
    val out = ArrayList<Chip>(childCount)
    for (i in 0 until childCount) {
        val v = getChildAt(i)
        if (v is Chip) out.add(v)
    }
    return out
}

private fun addChip(group: ChipGroup, label: String, checked: Boolean = false) {
    val chip = Chip(group.context)
    chip.text = label
    chip.isCheckable = true
    chip.isChecked = checked
    chip.tag = label
    try {
        chip.isChipIconVisible = true
        val isAll = label.trim().equals("all", ignoreCase = true)
        val iconRes =
            if (isAll) android.R.drawable.ic_menu_search
            else if (group.id == R.id.tag_chips) android.R.drawable.ic_menu_edit
            else android.R.drawable.ic_menu_agenda
        chip.chipIcon = group.context.getDrawable(iconRes)
    } catch (_: Throwable) {}
    group.addView(chip)
}

class ConversationsAdapter(
    private var items: List<ConversationStore.Conversation>,
    private val onClick: (ConversationStore.Conversation) -> Unit,
    private val onLongClick: (ConversationStore.Conversation) -> Unit,
) : RecyclerView.Adapter<ConversationVH>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConversationVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_project, parent, false)
        return ConversationVH(v, onClick, onLongClick)
    }
    override fun onBindViewHolder(holder: ConversationVH, position: Int) = holder.bind(items[position])
    override fun getItemCount(): Int = items.size
    fun update(newItems: List<ConversationStore.Conversation>) {
        items = newItems
        notifyDataSetChanged()
    }
}

class ConversationVH(
    view: View,
    private val onClick: (ConversationStore.Conversation) -> Unit,
    private val onLongClick: (ConversationStore.Conversation) -> Unit,
) : RecyclerView.ViewHolder(view) {
    private val title: android.widget.TextView = view.findViewById(R.id.project_title)
    private val meta: android.widget.TextView = view.findViewById(R.id.project_tag)
    private val snippet: android.widget.TextView = view.findViewById(R.id.project_snippet)

    fun bind(item: ConversationStore.Conversation) {
        val icon = item.icon.trim().ifBlank { "💬" }
        val t = item.title.ifBlank { "(untitled)" }
        title.text = "$icon  $t"

        val folder = normalizeFolder(item.folder)
        val tags = item.tags
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(5)
            .joinToString(" ") { "#$it" }
        val date = try { fmt.format(Date(item.updatedAt)) } catch (_: Throwable) { "" }

        meta.text = buildString {
            append("📁 ").append(folder)
            if (tags.isNotBlank()) append("  •  ").append(tags)
            if (date.isNotBlank()) append("  •  ").append(date)
        }

        val last = item.messages.lastOrNull()?.text.orEmpty().trim()
        val s = last.replace("\n", " ")
        snippet.text = if (s.length <= 160) s else s.take(160).trimEnd() + "."

        itemView.setOnClickListener { onClick(item) }
        itemView.setOnLongClickListener { onLongClick(item); true }
    }

    private companion object {
        private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)
    }
}

