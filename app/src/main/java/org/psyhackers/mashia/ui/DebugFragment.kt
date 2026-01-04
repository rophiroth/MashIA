package org.psyhackers.mashia.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import org.psyhackers.mashia.R
import org.psyhackers.mashia.util.DebugFileLogger
import java.io.File

class DebugFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_debug, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        DebugFileLogger.init(requireContext())
        val txt = view.findViewById<TextView>(R.id.debug_text)
        val btnRefresh = view.findViewById<Button>(R.id.btn_debug_refresh)
        val btnClose = view.findViewById<Button>(R.id.btn_debug_close)
        val debugSwitch = view.findViewById<Switch>(R.id.switch_debug)

        btnRefresh.setOnClickListener { loadLog(txt) }
        btnClose.setOnClickListener {
            try {
                requireActivity().supportFragmentManager.commit {
                    replace(R.id.fragment_container, ChatFragment())
                }
            } catch (_: Throwable) {}
        }
        val prefs = requireContext().getSharedPreferences("settings", 0)
        debugSwitch.isChecked = prefs.getBoolean(SettingsFragment.KEY_DEBUG_ENABLED, true)
        debugSwitch.setOnCheckedChangeListener { _, v ->
            prefs.edit().putBoolean(SettingsFragment.KEY_DEBUG_ENABLED, v).apply()
            DebugFileLogger.setEnabled(v)
        }
        loadLog(txt)
    }

    private fun loadLog(target: TextView) {
        val ctx = context ?: return
        val f = File(ctx.filesDir, "diag_log.txt")
        val data = if (f.exists()) f.readText() else ""
        target.text = if (data.isBlank()) "(empty)" else data
        view?.findViewById<ScrollView>(R.id.debug_scroll)?.post {
            try { view?.findViewById<ScrollView>(R.id.debug_scroll)?.fullScroll(View.FOCUS_DOWN) } catch (_: Throwable) {}
        }
    }
}
