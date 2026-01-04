package org.psyhackers.mashia.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.graphics.Color
import android.content.Intent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import kotlin.math.abs
import androidx.drawerlayout.widget.DrawerLayout
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageButton
import android.widget.SearchView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.psyhackers.mashia.R
import org.psyhackers.mashia.util.ConversationStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LibraryFragment : Fragment() {
    companion object {
        private const val ARG_EMBEDDED = "arg_embedded"
        private const val ARG_FULLSCREEN = "arg_fullscreen"
        fun newInstance(embedded: Boolean = false, fullScreen: Boolean = false): LibraryFragment {
            return LibraryFragment().apply {
                if (embedded || fullScreen) {
                    arguments = Bundle().apply {
                        if (embedded) putBoolean(ARG_EMBEDDED, true)
                        if (fullScreen) putBoolean(ARG_FULLSCREEN, true)
                    }
                }
            }
        }
    }
    private var all: List<ConversationStore.Conversation> = emptyList()
    private var filtered: List<ConversationStore.Conversation> = emptyList()
    private lateinit var adapter: ConversationsAdapter
    private var syncingChips: Boolean = false
    private var viewMode: String = "list"
    private var sortMode: String = "recent"
    private var sortAscending: Boolean = true
    private var folderScope: String = ""
    private var fullScreen: Boolean = false
    private var folderChips: ChipGroup? = null
    private var tagChips: ChipGroup? = null
    private var btnClearFolder: ImageButton? = null
    private var btnClearTag: ImageButton? = null
    private var folderTreeAdapter: FolderTreeAdapter? = null
    private var folderGridAdapter: FolderGridAdapter? = null
    private val expandedFolders = mutableSetOf<String>()
    private var folderViewTree: Boolean = true

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_library, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val list = view.findViewById<RecyclerView>(R.id.projects_list)
        val search = view.findViewById<SearchView>(R.id.search_view)
        val searchRow = view.findViewById<View>(R.id.search_row)
        folderChips = view.findViewById<ChipGroup>(R.id.folder_chips)
        tagChips = view.findViewById<ChipGroup>(R.id.tag_chips)
        val folderScroll = view.findViewById<android.widget.HorizontalScrollView>(R.id.folder_scroll)
        val tagScroll = view.findViewById<android.widget.HorizontalScrollView>(R.id.tag_scroll)
        val folderRow = view.findViewById<View>(R.id.folder_row)
        val tagRow = view.findViewById<View>(R.id.tag_row)
        btnClearFolder = view.findViewById<ImageButton>(R.id.btn_clear_folder)
        btnClearTag = view.findViewById<ImageButton>(R.id.btn_clear_tag)
        val btnClose = view.findViewById<ImageButton>(R.id.btn_library_close)
        val btnViewToggle = view.findViewById<ImageButton>(R.id.btn_view_toggle)
        val spinnerSort = view.findViewById<Spinner>(R.id.spinner_sort)
        val btnSortDir = view.findViewById<ImageButton>(R.id.btn_sort_dir)
        val embedded = arguments?.getBoolean(ARG_EMBEDDED, false) == true
        fullScreen = arguments?.getBoolean(ARG_FULLSCREEN, false) == true
        val btnFullScreen = view.findViewById<ImageButton>(R.id.btn_library_fullscreen)
        val treeList = view.findViewById<RecyclerView>(R.id.folder_tree_list)
        val gridList = view.findViewById<RecyclerView>(R.id.folder_grid_list)
        val btnFolderView = view.findViewById<ImageButton>(R.id.btn_folder_view_toggle)
        if (embedded) {
            btnClose.visibility = View.GONE
        }
        if (btnFullScreen != null) {
            btnFullScreen.visibility = if (embedded) View.VISIBLE else View.GONE
            btnFullScreen.setOnClickListener {
                try {
                    startActivity(Intent(requireContext(), LibraryFullActivity::class.java))
                } catch (_: Throwable) {}
            }
        }
        if (treeList != null) {
            if (fullScreen) {
                treeList.visibility = View.VISIBLE
                treeList.layoutManager = LinearLayoutManager(requireContext())
                folderTreeAdapter = FolderTreeAdapter(emptyList()) { path ->
                    onFolderSelectedFromTree(path)
                }.also { treeList.adapter = it }
            } else {
                treeList.visibility = View.GONE
            }
        }
        if (gridList != null) {
            if (fullScreen) {
                gridList.visibility = View.GONE
                gridList.layoutManager = GridLayoutManager(requireContext(), 3)
                folderGridAdapter = FolderGridAdapter(emptyList()) { item ->
                    onFolderSelectedFromGrid(item)
                }.also { gridList.adapter = it }
            } else {
                gridList.visibility = View.GONE
            }
        }
        if (btnFolderView != null) {
            btnFolderView.visibility = if (fullScreen) View.VISIBLE else View.GONE
            btnFolderView.setOnClickListener {
                folderViewTree = !folderViewTree
                updateFolderView(treeList, gridList, btnFolderView)
            }
            updateFolderView(treeList, gridList, btnFolderView)
        }
        val prefs = requireContext().getSharedPreferences("settings", 0)
        viewMode = prefs.getString("library_view_mode", "list").orEmpty().ifBlank { "list" }
        sortMode = prefs.getString("library_sort_mode", "recent").orEmpty().ifBlank { "recent" }
        sortAscending = prefs.getBoolean("library_sort_asc", sortMode != "recent")

        btnClose.setOnClickListener {
            val parent = parentFragment
            if (parent is ChatFragment) {
                parent.setLibraryVisible(false)
            } else {
                try {
                    requireActivity().supportFragmentManager.commit {
                        replace(R.id.fragment_container, ChatFragment())
                    }
                } catch (_: Throwable) {}
            }
        }

        adapter = ConversationsAdapter(
            filtered,
            onClick = { item -> openConversation(item) },
            onLongClick = { item -> showEditDialog(item) },
        ).apply { setViewMode(viewMode) }
        applyViewMode(list, viewMode)
        list.adapter = adapter

        spinnerSort.adapter =
            ArrayAdapter.createFromResource(requireContext(), R.array.library_sort_options, android.R.layout.simple_spinner_dropdown_item)
        spinnerSort.setSelection(sortIndex(sortMode))
        spinnerSort.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, v: View?, pos: Int, id: Long) {
                sortMode = sortModeAt(pos)
                prefs.edit().putString("library_sort_mode", sortMode).apply()
                if (sortMode == "recent" && !prefs.contains("library_sort_asc")) {
                    sortAscending = false
                }
                updateSortDir(btnSortDir)
                filter()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        }

        btnViewToggle.setOnClickListener {
            viewMode = if (viewMode == "grid") "list" else "grid"
            prefs.edit().putString("library_view_mode", viewMode).apply()
            applyViewMode(list, viewMode)
            adapter.setViewMode(viewMode)
            updateViewToggle(btnViewToggle)
        }
        updateViewToggle(btnViewToggle)
        updateSortDir(btnSortDir)

        btnSortDir.setOnClickListener {
            sortAscending = !sortAscending
            prefs.edit().putBoolean("library_sort_asc", sortAscending).apply()
            updateSortDir(btnSortDir)
            filter()
        }

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
        try {
            val plate = search.findViewById<View>(androidx.appcompat.R.id.search_plate)
            val submit = search.findViewById<View>(androidx.appcompat.R.id.submit_area)
            plate?.setBackgroundColor(Color.TRANSPARENT)
            submit?.setBackgroundColor(Color.TRANSPARENT)
        } catch (_: Throwable) {}
        searchRow.setOnClickListener {
            try {
                search.isIconified = false
                search.requestFocus()
            } catch (_: Throwable) {}
        }
        searchRow.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                try {
                    search.isIconified = false
                    search.requestFocus()
                } catch (_: Throwable) {}
            }
            false
        }

        val drawer = activity?.findViewById<DrawerLayout>(R.id.drawer_layout)
        fun setDrawerLock(lockedOpen: Boolean) {
            try {
                drawer?.setDrawerLockMode(
                    if (lockedOpen) DrawerLayout.LOCK_MODE_LOCKED_OPEN else DrawerLayout.LOCK_MODE_UNLOCKED
                )
            } catch (_: Throwable) {}
        }
        fun allowHorizontalScroll(v: View) {
            var downX = 0f
            var downY = 0f
            v.setOnTouchListener { viewTouched, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.x
                        downY = ev.y
                        setDrawerLock(true)
                        try { drawer?.requestDisallowInterceptTouchEvent(true) } catch (_: Throwable) {}
                        viewTouched.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = abs(ev.x - downX)
                        val dy = abs(ev.y - downY)
                        if (dx > dy) {
                            viewTouched.parent?.requestDisallowInterceptTouchEvent(true)
                            setDrawerLock(true)
                            try { drawer?.requestDisallowInterceptTouchEvent(true) } catch (_: Throwable) {}
                        }
                    }
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        viewTouched.postDelayed({ setDrawerLock(false) }, 200L)
                    }
                }
                false
            }
        }
        allowHorizontalScroll(folderScroll)
        allowHorizontalScroll(tagScroll)
        folderChips?.let { allowHorizontalScroll(it) }
        tagChips?.let { allowHorizontalScroll(it) }
        allowHorizontalScroll(folderRow)
        allowHorizontalScroll(tagRow)

        updateClearButtons()

        folderChips?.setOnCheckedStateChangeListener { _, _ ->
            if (syncingChips) return@setOnCheckedStateChangeListener
            val selected = folderChips?.checkedChipIds?.mapNotNull { id ->
                folderChips?.findViewById<Chip>(id)?.tag?.toString()?.trim().orEmpty().ifBlank { null }
            }.orEmpty()
            val newScope = computeFolderScope(folderScope, selected, all.map { normalizeFolder(it.folder) })
            if (newScope != folderScope) {
                folderScope = newScope
                rebuildChips()
            }
            updateClearButtons()
            filter()
        }
        tagChips?.setOnCheckedStateChangeListener { _, _ ->
            if (!syncingChips) {
                updateClearButtons()
                filter()
            }
        }

        btnClearFolder?.setOnClickListener {
            syncingChips = true
            try {
                folderChips?.childrenChips()?.forEach { it.isChecked = false }
                folderScope = ""
                rebuildChips()
            } finally {
                syncingChips = false
            }
            updateClearButtons()
            filter()
        }
        btnClearTag?.setOnClickListener {
            syncingChips = true
            try {
                tagChips?.childrenChips()?.forEach { it.isChecked = false }
            } finally {
                syncingChips = false
            }
            updateClearButtons()
            filter()
        }

        reload()
    }

    private fun applyViewMode(list: RecyclerView, mode: String) {
        list.layoutManager =
            if (mode == "grid") GridLayoutManager(requireContext(), 2) else LinearLayoutManager(requireContext())
    }

    private fun updateClearButtons() {
        val folders = folderChips
        val tags = tagChips
        val hasFolder = (folders?.checkedChipIds?.isNotEmpty() == true) || folderScope.isNotBlank()
        btnClearFolder?.visibility = if (hasFolder) View.VISIBLE else View.GONE
        btnClearTag?.visibility = if (tags?.checkedChipIds?.isNotEmpty() == true) View.VISIBLE else View.GONE
    }

    private fun updateFolderView(treeList: RecyclerView?, gridList: RecyclerView?, btn: ImageButton?) {
        if (!fullScreen) return
        val showTree = folderViewTree
        treeList?.visibility = if (showTree) View.VISIBLE else View.GONE
        gridList?.visibility = if (showTree) View.GONE else View.VISIBLE
        btn?.setImageResource(if (showTree) android.R.drawable.ic_menu_view else android.R.drawable.ic_menu_sort_by_size)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    fun refreshFromStore() {
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
        val folderChips = this.folderChips ?: v.findViewById<ChipGroup>(R.id.folder_chips)
        val tagChips = this.tagChips ?: v.findViewById<ChipGroup>(R.id.tag_chips)

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

        val showFolders = buildFolderChips(folders, folderScope)

        val selectedSet = folderChips.checkedChipIds.mapNotNull { id ->
            folderChips.findViewById<Chip>(id)?.tag?.toString()?.trim().orEmpty().ifBlank { null }
        }.toSet()

        syncingChips = true
        try {
            folderChips.removeAllViews()
            for (f in showFolders) addChip(folderChips, f.display, f.path, checked = f.path in selectedSet)

            tagChips.removeAllViews()
            for (t in tags) addChip(tagChips, t)
        } finally {
            syncingChips = false
        }
        updateClearButtons()
        rebuildFolderTree()
    }

    private fun rebuildFolderTree() {
        if (!fullScreen) return
        val adapter = folderTreeAdapter ?: return
        val folders = all
            .map { normalizeFolder(it.folder) }
            .filter { it.isNotBlank() }
            .distinct()
        val rows = buildFolderTreeRows(folders)
        adapter.update(rows)
        rebuildFolderGrid(folders)
    }

    private fun rebuildFolderGrid(folders: List<String>) {
        val adapter = folderGridAdapter ?: return
        val rows = buildFolderGridItems(folders)
        adapter.update(rows)
    }

    private fun buildFolderTreeRows(folders: List<String>): List<FolderRow> {
        data class Node(val path: String, val name: String, val children: MutableList<Node>)
        val root = Node("", "", mutableListOf())
        val nodeByPath = HashMap<String, Node>()
        for (raw in folders) {
            val parts = raw.split("/").map { it.trim() }.filter { it.isNotBlank() }
            var current = root
            var acc = ""
            for (p in parts) {
                acc = if (acc.isEmpty()) p else "$acc/$p"
                val existing = current.children.firstOrNull { it.name == p }
                val next = existing ?: Node(acc, p, mutableListOf()).also { current.children.add(it) }
                nodeByPath[acc] = next
                current = next
            }
        }
        if (expandedFolders.isEmpty()) {
            expandedFolders.addAll(nodeByPath.keys)
        }
        val rows = ArrayList<FolderRow>()
        rows.add(FolderRow(path = "", name = "All", depth = 0, hasChildren = false, expanded = false, selected = folderScope.isBlank()))
        fun addChildren(n: Node, depth: Int) {
            val children = n.children.sortedBy { it.name.lowercase(Locale.ROOT) }
            for (child in children) {
                val expanded = expandedFolders.contains(child.path)
                val selected = folderScope == child.path
                rows.add(
                    FolderRow(
                        path = child.path,
                        name = child.name,
                        depth = depth,
                        hasChildren = child.children.isNotEmpty(),
                        expanded = expanded,
                        selected = selected,
                    )
                )
                if (expanded) addChildren(child, depth + 1)
            }
        }
        addChildren(root, 0)
        return rows
    }

    private fun buildFolderGridItems(folders: List<String>): List<FolderGridItem> {
        val scope = folderScope.trim().trimEnd('/')
        val prefix = if (scope.isBlank()) "" else "$scope/"
        val children = folders
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix).substringBefore("/") }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
        val out = ArrayList<FolderGridItem>()
        if (scope.isNotBlank()) {
            val parent = scope.substringBeforeLast("/", "")
            out.add(FolderGridItem(path = parent, name = "..", isUp = true, hasChildren = true, selected = false))
        }
        for (c in children) {
            val path = if (prefix.isBlank()) c else prefix + c
            val hasChildren = folders.any { it.startsWith(path + "/") }
            out.add(FolderGridItem(path = path, name = c, isUp = false, hasChildren = hasChildren, selected = folderScope == path))
        }
        return out
    }

    private fun onFolderSelectedFromTree(path: String) {
        val norm = path.trim()
        if (norm.isNotBlank()) {
            val prefix = norm + "/"
            val hasChildren = all.any { normalizeFolder(it.folder).startsWith(prefix) }
            if (hasChildren) {
                if (expandedFolders.contains(norm)) expandedFolders.remove(norm) else expandedFolders.add(norm)
            }
        }
        syncingChips = true
        try {
            folderChips?.childrenChips()?.forEach { it.isChecked = false }
            folderScope = norm
            rebuildChips()
        } finally {
            syncingChips = false
        }
        updateClearButtons()
        filter()
    }

    private fun onFolderSelectedFromGrid(item: FolderGridItem) {
        syncingChips = true
        try {
            folderChips?.childrenChips()?.forEach { it.isChecked = false }
            folderScope = item.path
            rebuildChips()
        } finally {
            syncingChips = false
        }
        updateClearButtons()
        filter()
    }

    private fun filter() {
        val v = view ?: return
        val qRaw = v.findViewById<SearchView>(R.id.search_view).query?.toString()?.trim().orEmpty()

        val folderChips = v.findViewById<ChipGroup>(R.id.folder_chips)
        val tagChips = v.findViewById<ChipGroup>(R.id.tag_chips)
        val includeSubfolders = try {
            requireContext().getSharedPreferences("settings", 0)
                .getBoolean(SettingsFragment.KEY_LIBRARY_INCLUDE_SUBFOLDERS, true)
        } catch (_: Throwable) { true }

        val selectedFolders = folderChips.checkedChipIds.mapNotNull { id ->
            folderChips.findViewById<Chip>(id)?.tag?.toString()?.trim().orEmpty().ifBlank { null }
        }.toMutableSet().apply {
            if (isEmpty() && folderScope.isNotBlank()) add(folderScope)
        }

        val selectedTags = tagChips.childrenChips()
            .filter { it.isChecked }
            .mapNotNull { it.tag?.toString()?.trim() }
            .filter { it.isNotBlank() }
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
            if (selectedFolders.isNotEmpty()) {
                val ok = selectedFolders.any { sel ->
                    val s = sel.lowercase(Locale.ROOT)
                    if (includeSubfolders) f == s || f.startsWith(s.trimEnd('/') + "/") else f == s
                }
                if (!ok) return false
            }
            val qFolder = qp.folder?.trim()?.lowercase(Locale.ROOT)
            if (!qFolder.isNullOrBlank()) {
                val ok = if (includeSubfolders) f == qFolder || f.startsWith(qFolder.trimEnd('/') + "/") else f == qFolder
                if (!ok) return false
            }
            return true
        }

        fun matchesTags(it: ConversationStore.Conversation): Boolean {
            val itemTags = it.tags.map { t -> t.trim().lowercase(Locale.ROOT) }.filter { it.isNotBlank() }.toSet()
            if (selectedTags.isNotEmpty() && itemTags.none { t -> selectedTags.contains(t) }) return false
            if (qp.tags.isNotEmpty() && !itemTags.containsAll(qp.tags)) return false
            return true
        }

        filtered = all.filter { matchesFolder(it) && matchesTags(it) && matchesText(it) }
        filtered = sortFiltered(filtered, sortMode, sortAscending)
        adapter.update(filtered)
    }

    private fun sortFiltered(
        items: List<ConversationStore.Conversation>,
        mode: String,
        asc: Boolean,
    ): List<ConversationStore.Conversation> {
        val base = when (mode) {
            "title" -> items.sortedBy { it.title.trim().lowercase(Locale.ROOT) }
            "folder" -> items.sortedBy { normalizeFolder(it.folder).lowercase(Locale.ROOT) }
            "tags" -> items.sortedBy { it.tags.joinToString(",").lowercase(Locale.ROOT) }
            "color" -> items.sortedBy { it.color }
            "oldest" -> items.sortedBy { it.updatedAt }
            else -> items.sortedByDescending { it.updatedAt }
        }
        return if (asc) base else base.asReversed()
    }

    private fun sortIndex(mode: String): Int = when (mode) {
        "recent" -> 0
        "title" -> 1
        "folder" -> 2
        "tags" -> 3
        "color" -> 4
        "oldest" -> 5
        else -> 0
    }

    private fun sortModeAt(index: Int): String = when (index) {
        1 -> "title"
        2 -> "folder"
        3 -> "tags"
        4 -> "color"
        5 -> "oldest"
        else -> "recent"
    }

    private fun updateViewToggle(btn: ImageButton) {
        val icon =
            if (viewMode == "grid") android.R.drawable.ic_menu_view else android.R.drawable.ic_menu_sort_by_size
        btn.setImageResource(icon)
    }

    private fun updateSortDir(btn: ImageButton) {
        val icon = if (sortAscending) android.R.drawable.arrow_up_float else android.R.drawable.arrow_down_float
        btn.setImageResource(icon)
        btn.contentDescription = if (sortAscending) "Sort A-Z" else "Sort Z-A"
    }

    private fun openConversation(item: ConversationStore.Conversation) {
        val ctx = context ?: return
        try { ConversationStore.setCurrentId(ctx, item.id) } catch (_: Throwable) {}
        val parent = parentFragment
        if (parent is ChatFragment) {
            parent.openConversationFromLibrary(item)
            return
        }
        try {
            requireActivity().supportFragmentManager.commit {
                replace(R.id.fragment_container, ChatFragment())
            }
        } catch (_: Throwable) {}
        try {
            val main = activity as? org.psyhackers.mashia.MainActivity
            main?.closeDrawer()
        } catch (_: Throwable) {}
    }

    private fun showEditDialog(item: ConversationStore.Conversation) {
        val ctx = context ?: return
        val layout = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        var selectedIcon = item.icon.ifBlank { "💬" }
        val inputTitle = EditText(ctx).apply { hint = "Title"; setText(item.title) }
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
        val inputFolder = EditText(ctx).apply { hint = "Folder (e.g. inbox, work)"; setText(normalizeFolder(item.folder)) }
        val tags = item.tags.toMutableList()
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
                if (tags.none { it.equals(p, ignoreCase = true) }) tags.add(p)
            }
            renderTags()
        }
        val existingTags = all
            .flatMap { it.tags }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedBy { it.lowercase() }
        val btnAddTag = android.widget.Button(ctx).apply {
            text = "Add tag"
            setOnClickListener {
                var dialog: AlertDialog? = null
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
                dialog = AlertDialog.Builder(ctx)
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
        var selectedColor = item.color
        val colorRow = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
        }
        val colorDot = View(ctx).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(24, 24)
        }
        val colorLabel = android.widget.TextView(ctx).apply {
            text = colorNameFor(selectedColor)
            setPadding(12, 0, 0, 0)
        }
        colorRow.addView(colorDot)
        colorRow.addView(colorLabel)
        updateColorDot(colorDot, selectedColor)

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
        layout.addView(inputFolder)
        layout.addView(tagsGroup)
        layout.addView(btnAddTag)
        layout.addView(colorRow)
        layout.addView(btnPickColor)

        AlertDialog.Builder(ctx)
            .setTitle("Edit chat")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                Thread {
                    try {
                        val c = selectedColor
                        val updated = ConversationStore.updateMeta(
                            item,
                            title = inputTitle.text?.toString(),
                            folder = inputFolder.text?.toString(),
                            tagsCsv = tags.joinToString(", "),
                            icon = selectedIcon,
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

private data class FolderChip(val display: String, val path: String)

private fun buildFolderChips(allFolders: List<String>, selected: String): List<FolderChip> {
    if (allFolders.isEmpty()) return emptyList()
    val sel = selected.trim().trimEnd('/')
    if (sel.isBlank() || sel.equals("all", ignoreCase = true)) {
        return allFolders
            .map { it.substringBefore("/") }
            .distinct()
            .sorted()
            .map { FolderChip(it, it) }
    }
    val prefix = sel + "/"
    val children = allFolders
        .filter { it.startsWith(prefix) }
        .map { it.removePrefix(prefix).substringBefore("/") }
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()
        .map { FolderChip(it, sel + "/" + it) }
    return children
}

private fun computeFolderScope(current: String, selected: List<String>, allFolders: List<String>): String {
    if (selected.size != 1) return current
    val sel = selected.first().trim().trimEnd('/')
    if (sel.isBlank()) return current
    val prefix = sel + "/"
    val hasChildren = allFolders.any { it.startsWith(prefix) }
    return if (hasChildren) sel else current
}

private fun ChipGroup.childrenChips(): List<Chip> {
    val out = ArrayList<Chip>(childCount)
    for (i in 0 until childCount) {
        val v = getChildAt(i)
        if (v is Chip) out.add(v)
    }
    return out
}

private fun addChip(group: ChipGroup, label: String, tag: String = label, checked: Boolean = false) {
    val chip = Chip(group.context)
    chip.text = label
    chip.isCheckable = true
    chip.isChecked = checked
    chip.tag = tag
    try {
        chip.isChipIconVisible = false
        chip.chipIcon = null
    } catch (_: Throwable) {}
    group.addView(chip)
}

private fun coloredTitle(view: android.widget.TextView, icon: String, title: String, color: Int): CharSequence {
    val dot = "● "
    val prefix = dot + (if (icon.isNotBlank()) "$icon  " else "")
    val text = prefix + title
    val out = SpannableStringBuilder(text)
    try {
        out.setSpan(ForegroundColorSpan(color), 0, dot.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    } catch (_: Throwable) {}
    return out
}

private data class FolderRow(
    val path: String,
    val name: String,
    val depth: Int,
    val hasChildren: Boolean,
    val expanded: Boolean,
    val selected: Boolean,
)

private data class FolderGridItem(
    val path: String,
    val name: String,
    val isUp: Boolean,
    val hasChildren: Boolean,
    val selected: Boolean,
)

private class FolderTreeAdapter(
    private var items: List<FolderRow>,
    private val onSelect: (String) -> Unit,
) : RecyclerView.Adapter<FolderTreeVH>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderTreeVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_folder_tree, parent, false)
        return FolderTreeVH(v, onSelect)
    }
    override fun onBindViewHolder(holder: FolderTreeVH, position: Int) = holder.bind(items[position])
    override fun getItemCount(): Int = items.size
    fun update(newItems: List<FolderRow>) {
        items = newItems
        notifyDataSetChanged()
    }
}

private class FolderGridAdapter(
    private var items: List<FolderGridItem>,
    private val onSelect: (FolderGridItem) -> Unit,
) : RecyclerView.Adapter<FolderGridVH>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderGridVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_folder_grid, parent, false)
        return FolderGridVH(v, onSelect)
    }
    override fun onBindViewHolder(holder: FolderGridVH, position: Int) = holder.bind(items[position])
    override fun getItemCount(): Int = items.size
    fun update(newItems: List<FolderGridItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}

private class FolderTreeVH(
    view: View,
    private val onSelect: (String) -> Unit,
) : RecyclerView.ViewHolder(view) {
    private val arrow: android.widget.ImageView = view.findViewById(R.id.folder_tree_arrow)
    private val label: android.widget.TextView = view.findViewById(R.id.folder_tree_label)
    fun bind(item: FolderRow) {
        val base = (12 * itemView.resources.displayMetrics.density).toInt()
        val indent = base + (item.depth * (14 * itemView.resources.displayMetrics.density).toInt())
        itemView.setPadding(indent, itemView.paddingTop, itemView.paddingRight, itemView.paddingBottom)
        label.text = item.name
        if (item.hasChildren) {
            arrow.visibility = View.VISIBLE
            arrow.setImageResource(if (item.expanded) android.R.drawable.arrow_down_float else android.R.drawable.arrow_up_float)
        } else {
            arrow.visibility = View.INVISIBLE
        }
        if (item.selected) {
            itemView.setBackgroundResource(R.drawable.bg_btn)
            try { itemView.background?.mutate()?.setTint(Color.parseColor("#2E7D32")) } catch (_: Throwable) {}
        } else {
            itemView.background = null
        }
        itemView.setOnClickListener { onSelect(item.path) }
    }
}

private class FolderGridVH(
    view: View,
    private val onSelect: (FolderGridItem) -> Unit,
) : RecyclerView.ViewHolder(view) {
    private val icon: android.widget.ImageView = view.findViewById(R.id.folder_grid_icon)
    private val label: android.widget.TextView = view.findViewById(R.id.folder_grid_label)
    fun bind(item: FolderGridItem) {
        label.text = item.name
        icon.setImageResource(
            if (item.isUp) android.R.drawable.ic_menu_revert
            else android.R.drawable.ic_menu_agenda
        )
        if (item.selected) {
            itemView.setBackgroundResource(R.drawable.bg_btn)
            try { itemView.background?.mutate()?.setTint(Color.parseColor("#2E7D32")) } catch (_: Throwable) {}
        } else {
            itemView.background = null
        }
        itemView.setOnClickListener { onSelect(item) }
    }
}

class ConversationsAdapter(
    private var items: List<ConversationStore.Conversation>,
    private val onClick: (ConversationStore.Conversation) -> Unit,
    private val onLongClick: (ConversationStore.Conversation) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var viewMode: String = "list"

    override fun getItemViewType(position: Int): Int = if (viewMode == "grid") 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layoutId = if (viewType == 1) R.layout.item_project_grid else R.layout.item_project
        val v = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return if (viewType == 1) ConversationGridVH(v, onClick, onLongClick) else ConversationVH(v, onClick, onLongClick)
    }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is ConversationVH -> holder.bind(item)
            is ConversationGridVH -> holder.bind(item)
        }
    }
    override fun getItemCount(): Int = items.size
    fun update(newItems: List<ConversationStore.Conversation>) {
        items = newItems
        notifyDataSetChanged()
    }
    fun setViewMode(mode: String) {
        viewMode = mode
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
        title.text = coloredTitle(title, icon, t, item.color)

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

class ConversationGridVH(
    view: View,
    private val onClick: (ConversationStore.Conversation) -> Unit,
    private val onLongClick: (ConversationStore.Conversation) -> Unit,
) : RecyclerView.ViewHolder(view) {
    private val iconView: android.widget.TextView = view.findViewById(R.id.project_grid_icon)
    private val titleView: android.widget.TextView = view.findViewById(R.id.project_grid_title)

    fun bind(item: ConversationStore.Conversation) {
        val icon = item.icon.trim().ifBlank { "??" }
        val t = item.title.ifBlank { "(untitled)" }
        iconView.text = icon
        titleView.text = coloredTitle(titleView, "", t, item.color)
        itemView.setOnClickListener { onClick(item) }
        itemView.setOnLongClickListener { onLongClick(item); true }
    }
}
