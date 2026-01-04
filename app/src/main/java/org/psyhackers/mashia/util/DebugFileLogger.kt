package org.psyhackers.mashia.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pequeño logger a archivo para diagnósticos en dispositivos sin logcat.
 * Mantiene el archivo recortado para que no crezca indefinidamente.
 */
object DebugFileLogger {
    private var file: File? = null
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var enabled: Boolean = true

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        try {
            file = File(ctx.filesDir, "diag_log.txt")
            file?.parentFile?.mkdirs()
            val prefs = ctx.getSharedPreferences("settings", 0)
            enabled = prefs.getBoolean("debug_enabled", true)
        } catch (_: Throwable) {}
    }

    @Synchronized
    fun log(tag: String, msg: String) {
        if (!enabled) return
        val f = file ?: return
        try {
            val now = fmt.format(Date())
            f.appendText("$now [$tag] $msg\n")
            // recorta si pasa ~200 KB dejando los últimos ~50 KB
            if (f.length() > 200_000) {
                val content = f.readText()
                val keep = content.takeLast(50_000)
                f.writeText(keep)
            }
        } catch (_: Throwable) {}
    }

    fun setEnabled(v: Boolean) {
        enabled = v
    }

    fun isEnabled(): Boolean = enabled
}
