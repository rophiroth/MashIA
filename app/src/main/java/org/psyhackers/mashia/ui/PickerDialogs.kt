package org.psyhackers.mashia.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SearchView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.psyhackers.mashia.R

data class IconChoice(val icon: String, val name: String)
data class ColorChoice(val name: String, val value: Int)

fun colorNameFor(value: Int): String {
    return colorChoices().firstOrNull { it.value == value }?.name ?: "Custom"
}

fun updateColorDot(dot: View, color: Int) {
    val d = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setSize(24, 24)
        setStroke(1, Color.parseColor("#222222"))
    }
    dot.background = d
}

fun showIconPicker(ctx: Context, initial: String?, onPick: (String) -> Unit) {
    val v = LayoutInflater.from(ctx).inflate(R.layout.dialog_icon_picker, null, false)
    val search = v.findViewById<SearchView>(R.id.icon_search)
    val list = v.findViewById<RecyclerView>(R.id.icon_list)
    val all = iconChoices(ctx)
    var dialog: AlertDialog? = null
    val adapter = IconPickerAdapter(all) { picked ->
        onPick(picked)
        dialog?.dismiss()
    }
    list.layoutManager = GridLayoutManager(ctx, 6)
    list.adapter = adapter

    search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
        override fun onQueryTextSubmit(query: String?): Boolean {
            adapter.filter(query.orEmpty())
            return true
        }
        override fun onQueryTextChange(newText: String?): Boolean {
            adapter.filter(newText.orEmpty())
            return true
        }
    })

    dialog = AlertDialog.Builder(ctx)
        .setTitle("Pick icon")
        .setView(v)
        .setNegativeButton("Cancel", null)
        .show()
}

fun showColorPicker(ctx: Context, initial: Int, onPick: (ColorChoice) -> Unit) {
    val v = LayoutInflater.from(ctx).inflate(R.layout.dialog_color_picker, null, false)
    val list = v.findViewById<RecyclerView>(R.id.color_list)
    var dialog: AlertDialog? = null
    val adapter = ColorPickerAdapter(colorChoices()) { picked ->
        onPick(picked)
        dialog?.dismiss()
    }
    list.layoutManager = GridLayoutManager(ctx, 2)
    list.adapter = adapter
    dialog = AlertDialog.Builder(ctx)
        .setTitle("Colors")
        .setView(v)
        .setNegativeButton("Cancel", null)
        .show()
}

private var cachedEmoji: List<IconChoice>? = null

private fun iconChoices(ctx: Context): List<IconChoice> {
    val curated = curatedIconChoices()
    val emoji = loadEmojiFromAssets(ctx)
    if (emoji.isEmpty()) return curated
    val seen = HashSet<String>(curated.size + emoji.size)
    val merged = ArrayList<IconChoice>(curated.size + emoji.size)
    for (item in curated) {
        if (seen.add(item.icon)) merged.add(item)
    }
    for (item in emoji) {
        if (seen.add(item.icon)) merged.add(item)
    }
    return merged
}

private fun loadEmojiFromAssets(ctx: Context): List<IconChoice> {
    cachedEmoji?.let { return it }
    val out = ArrayList<IconChoice>()
    try {
        ctx.assets.open("emoji_list.txt").bufferedReader().useLines { seq ->
            seq.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isBlank()) return@forEach
                val parts = trimmed.split("|", limit = 2)
                if (parts.size == 2) {
                    val icon = parts[0].trim()
                    val name = parts[1].trim().lowercase()
                    if (icon.isNotBlank()) out.add(IconChoice(icon, name))
                }
            }
        }
    } catch (_: Throwable) {}
    cachedEmoji = out
    return out
}

private fun curatedIconChoices(): List<IconChoice> {
    return listOf(
        IconChoice("💬", "chat"),
        IconChoice("🔥", "fire"),
        IconChoice("⚡", "energy"),
        IconChoice("📌", "pin"),
        IconChoice("📎", "attach"),
        IconChoice("✅", "check"),
        IconChoice("⭐", "star"),
        IconChoice("🧠", "brain"),
        IconChoice("🧩", "puzzle"),
        IconChoice("💡", "idea"),
        IconChoice("📝", "notes"),
        IconChoice("📚", "library"),
        IconChoice("📅", "calendar"),
        IconChoice("📈", "chart"),
        IconChoice("🧾", "receipt"),
        IconChoice("📦", "box"),
        IconChoice("🔒", "lock"),
        IconChoice("🔓", "unlock"),
        IconChoice("🔧", "tools"),
        IconChoice("🛠️", "tools 2"),
        IconChoice("🧪", "lab"),
        IconChoice("🧬", "dna"),
        IconChoice("🩺", "health"),
        IconChoice("🚀", "rocket"),
        IconChoice("🛰️", "satellite"),
        IconChoice("🗂️", "folders"),
        IconChoice("🧳", "travel"),
        IconChoice("🎯", "target"),
        IconChoice("🎨", "art"),
        IconChoice("🎵", "music"),
        IconChoice("🎧", "audio"),
        IconChoice("📸", "camera"),
        IconChoice("🖼️", "gallery"),
        IconChoice("📄", "document"),
        IconChoice("💼", "work"),
        IconChoice("💰", "money"),
        IconChoice("🛒", "cart"),
        IconChoice("🧘", "meditation"),
        IconChoice("🌙", "moon"),
        IconChoice("☀️", "sun"),
        IconChoice("🌈", "rainbow"),
        IconChoice("🌊", "water"),
        IconChoice("🌿", "nature"),
        IconChoice("🪴", "plant"),
        IconChoice("🏠", "home"),
        IconChoice("🧭", "compass"),
        IconChoice("🔍", "search"),
        IconChoice("🧵", "thread"),
        IconChoice("🧱", "build"),
        IconChoice("🕹️", "game"),
        IconChoice("🤖", "robot"),
        IconChoice("👁️", "vision"),
        IconChoice("🧿", "focus"),
        IconChoice("🗣️", "voice"),
        IconChoice("🎤", "mic"),
        IconChoice("✍️", "write"),
        IconChoice("📍", "location"),
        IconChoice("🧑‍💻", "code"),
        IconChoice("📊", "stats"),
        IconChoice("🕒", "time"),
        IconChoice("🔔", "alerts"),
        IconChoice("❤️", "heart"),
        IconChoice("👍", "like"),
        IconChoice("👎", "dislike"),
        IconChoice("😊", "smile"),
        IconChoice("😂", "laugh"),
        IconChoice("😁", "grin"),
        IconChoice("😭", "tears"),
        IconChoice("😉", "wink"),
        IconChoice("😘", "kiss"),
        IconChoice("😍", "love"),
        IconChoice("😎", "cool"),
        IconChoice("😮", "surprise"),
        IconChoice("🤔", "thinking"),
        IconChoice("🤫", "shh"),
        IconChoice("😅", "sweat"),
        IconChoice("😴", "sleepy"),
        IconChoice("😡", "angry"),
        IconChoice("😢", "sad"),
        IconChoice("🥳", "party face"),
        IconChoice("😎", "cool"),
        IconChoice("🙏", "pray"),
        IconChoice("🤝", "handshake"),
        IconChoice("👏", "clap"),
        IconChoice("🙌", "raised hands"),
        IconChoice("✊", "fist"),
        IconChoice("💪", "muscle"),
        IconChoice("👌", "ok"),
        IconChoice("🎉", "party"),
        IconChoice("🏆", "trophy"),
        IconChoice("🏅", "medal"),
        IconChoice("💖", "sparkle heart"),
        IconChoice("💔", "broken heart"),
        IconChoice("🔥", "fire"),
        IconChoice("⭐", "star"),
        IconChoice("⚡", "zap"),
        IconChoice("☀️", "sun"),
        IconChoice("🌙", "moon"),
        IconChoice("☁️", "cloud"),
        IconChoice("🌧️", "rain"),
        IconChoice("❄️", "snow"),
        IconChoice("🍃", "leaf"),
        IconChoice("🌸", "flower"),
        IconChoice("🌵", "cactus"),
        IconChoice("🐱", "cat"),
        IconChoice("🐶", "dog"),
        IconChoice("🦊", "fox"),
        IconChoice("🐰", "rabbit"),
        IconChoice("🦄", "unicorn"),
        IconChoice("🤖", "robot"),
        IconChoice("💀", "skull"),
        IconChoice("📱", "phone"),
        IconChoice("💻", "laptop"),
        IconChoice("🖥️", "desktop"),
        IconChoice("🗒️", "note"),
        IconChoice("🧾", "bill"),
        IconChoice("🗓️", "date"),
        IconChoice("🧹", "clean"),
        IconChoice("🛒", "shopping"),
        IconChoice("🍀", "luck"),
        IconChoice("🌟", "sparkle"),
        IconChoice("🧨", "boom"),
        IconChoice("🚦", "traffic"),
        IconChoice("🚧", "construction"),
        IconChoice("🚗", "car"),
        IconChoice("✈️", "plane"),
        IconChoice("🛡️", "shield"),
        IconChoice("🔑", "key"),
        IconChoice("💎", "diamond"),
        IconChoice("🧷", "pin"),
        IconChoice("📌", "pin 2"),
        IconChoice("📁", "folder"),
        IconChoice("🗃️", "archive"),
        IconChoice("📂", "open folder"),
        IconChoice("🗑️", "trash"),
        IconChoice("🔗", "link"),
        IconChoice("🧮", "calculator"),
        IconChoice("🧠", "mind"),
        IconChoice("🛎️", "bell"),
        IconChoice("🧴", "bottle"),
        IconChoice("🧱", "brick"),
        IconChoice("🪙", "coin"),
        IconChoice("💳", "card"),
        IconChoice("📤", "upload"),
        IconChoice("📥", "download"),
        IconChoice("📬", "mailbox"),
        IconChoice("🗒️", "memo"),
        IconChoice("🧩", "piece"),
        IconChoice("🧯", "safety"),
        IconChoice("🧿", "evil eye"),
        IconChoice("📣", "megaphone"),
        IconChoice("📢", "announcement"),
        IconChoice("🎬", "video"),
        IconChoice("📺", "tv"),
        IconChoice("🎮", "console"),
        IconChoice("🛏️", "sleep"),
        IconChoice("💤", "zzz"),
        IconChoice("🧊", "ice"),
        IconChoice("🌋", "volcano"),
        IconChoice("🌌", "galaxy"),
        IconChoice("⭐️", "star outline"),
        IconChoice("🔮", "crystal"),
        IconChoice("🪄", "magic"),
        IconChoice("🧸", "toy"),
        IconChoice("🧃", "juice"),
        IconChoice("🍎", "apple"),
        IconChoice("☕", "coffee"),
        IconChoice("🍞", "bread"),
        IconChoice("🍩", "donut"),
        IconChoice("🥗", "salad"),
        IconChoice("🧁", "cupcake"),
        IconChoice("🎁", "gift"),
        IconChoice("🎈", "balloon"),
        IconChoice("🧭", "navigation"),
        IconChoice("🧷", "pin small"),
        IconChoice("⚙️", "settings"),
        IconChoice("🗜️", "clamp"),
        IconChoice("🔬", "microscope"),
        IconChoice("📡", "antenna"),
        IconChoice("🧫", "petri"),
        IconChoice("🧵", "thread"),
        IconChoice("🪡", "needle"),
        IconChoice("🧶", "yarn"),
        IconChoice("🧳", "suitcase"),
        IconChoice("🧲", "magnet"),
        IconChoice("🧪", "experiment"),
        IconChoice("🧰", "toolbox"),
        IconChoice("🧯", "extinguisher"),
        IconChoice("🪜", "ladder"),
        IconChoice("🧴", "soap"),
        IconChoice("🛋️", "sofa"),
        IconChoice("🏡", "house"),
        IconChoice("🏫", "school"),
        IconChoice("🏢", "office"),
        IconChoice("🏥", "hospital"),
        IconChoice("🏪", "store")
    )
}

private fun colorChoices(): List<ColorChoice> {
    return listOf(
        ColorChoice("Red", Color.parseColor("#E53935")),
        ColorChoice("Orange", Color.parseColor("#FB8C00")),
        ColorChoice("Yellow", Color.parseColor("#FDD835")),
        ColorChoice("Green", Color.parseColor("#43A047")),
        ColorChoice("Blue", Color.parseColor("#1E88E5")),
        ColorChoice("Indigo", Color.parseColor("#3949AB")),
        ColorChoice("Violet", Color.parseColor("#8E24AA")),
        ColorChoice("Black", Color.parseColor("#000000")),
        ColorChoice("White", Color.parseColor("#FFFFFF")),
        ColorChoice("Gray", Color.parseColor("#757575")),
    )
}

private class IconPickerAdapter(
    private val all: List<IconChoice>,
    private val onPick: (String) -> Unit,
) : RecyclerView.Adapter<IconPickerVH>() {
    private var items: List<IconChoice> = all

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IconPickerVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_icon_tile, parent, false)
        return IconPickerVH(v, onPick)
    }

    override fun onBindViewHolder(holder: IconPickerVH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    fun filter(q: String) {
        val t = q.trim().lowercase()
        items = if (t.isBlank()) {
            all
        } else {
            all.filter { it.name.contains(t) || it.icon.contains(t) }
        }
        notifyDataSetChanged()
    }
}

private class IconPickerVH(
    view: View,
    private val onPick: (String) -> Unit,
) : RecyclerView.ViewHolder(view) {
    private val text: TextView = view.findViewById(R.id.icon_tile_text)
    fun bind(item: IconChoice) {
        text.text = item.icon
        itemView.setOnClickListener { onPick(item.icon) }
    }
}

private class ColorPickerAdapter(
    private val items: List<ColorChoice>,
    private val onPick: (ColorChoice) -> Unit,
) : RecyclerView.Adapter<ColorPickerVH>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ColorPickerVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_color_swatch, parent, false)
        return ColorPickerVH(v, onPick)
    }
    override fun onBindViewHolder(holder: ColorPickerVH, position: Int) = holder.bind(items[position])
    override fun getItemCount(): Int = items.size
}

private class ColorPickerVH(
    view: View,
    private val onPick: (ColorChoice) -> Unit,
) : RecyclerView.ViewHolder(view) {
    private val dot: View = view.findViewById(R.id.color_dot)
    private val label: TextView = view.findViewById(R.id.color_label)
    fun bind(item: ColorChoice) {
        updateColorDot(dot, item.value)
        label.text = item.name
        itemView.setOnClickListener { onPick(item) }
    }
}
