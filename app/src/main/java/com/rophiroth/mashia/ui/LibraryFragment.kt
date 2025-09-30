package com.rophiroth.mashia.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.rophiroth.mashia.R

data class Project(val title: String, val tag: String)

class LibraryFragment : Fragment() {
    private val all = listOf(
        Project("Daily Journal", "writing"),
        Project("Kotlin Helper", "code"),
        Project("Image Playground", "image"),
        Project("Spanish Tutor", "education"),
        Project("Prompt Vault", "writing")
    )
    private var filtered = all
    private lateinit var adapter: ProjectAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_library, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val list = view.findViewById<RecyclerView>(R.id.projects_list)
        val search = view.findViewById<SearchView>(R.id.search_view)
        val chips = view.findViewById<ChipGroup>(R.id.filter_chips)

        adapter = ProjectAdapter(filtered)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter

        search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean { filter(); return true }
            override fun onQueryTextChange(newText: String?): Boolean { filter(); return true }
        })

        listOf("all", "writing", "code", "image", "education").forEachIndexed { i, t ->
            val chip = Chip(requireContext())
            chip.text = t
            chip.isCheckable = true
            chip.isChecked = (i == 0)
            chip.tag = t
            chips.addView(chip)
        }
        chips.setOnCheckedStateChangeListener { _, _ -> filter() }
    }

    private fun filter() {
        val view = view ?: return
        val search = view.findViewById<SearchView>(R.id.search_view).query?.toString()?.trim()?.lowercase() ?: ""
        val chips = view.findViewById<ChipGroup>(R.id.filter_chips)
        val selected = chips.checkedChipIds.firstOrNull()?.let { id ->
            chips.findViewById<Chip>(id)?.tag?.toString()
        } ?: "all"

        filtered = all.filter { p ->
            val matchesText = p.title.lowercase().contains(search)
            val matchesTag = selected == "all" || p.tag == selected
            matchesText && matchesTag
        }
        adapter.update(filtered)
    }
}

class ProjectAdapter(private var items: List<Project>) : RecyclerView.Adapter<ProjectVH>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProjectVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_project, parent, false)
        return ProjectVH(v)
    }
    override fun onBindViewHolder(holder: ProjectVH, position: Int) = holder.bind(items[position])
    override fun getItemCount(): Int = items.size
    fun update(newItems: List<Project>) {
        items = newItems
        notifyDataSetChanged()
    }
}

class ProjectVH(view: View) : RecyclerView.ViewHolder(view) {
    private val title: android.widget.TextView = view.findViewById(R.id.project_title)
    private val tag: android.widget.TextView = view.findViewById(R.id.project_tag)
    fun bind(p: Project) {
        title.text = p.title
        tag.text = p.tag
    }
}

