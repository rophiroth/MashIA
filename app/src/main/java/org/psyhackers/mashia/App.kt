package org.psyhackers.mashia

import android.app.Application
import android.util.Log
import java.io.File
import org.psyhackers.mashia.util.DebugFileLogger

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            DebugFileLogger.init(this)
            DebugFileLogger.log("app", "onCreate")
        } catch (_: Throwable) {}
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val f = File(filesDir, "last_crash.txt")
                f.writeText("thread=${t?.name}\n${e.stackTraceToString()}")
            } catch (_: Throwable) {}
            Log.e("Crash", "Uncaught", e)
            // Delegate to system default after writing
            try { previousHandler?.uncaughtException(t, e) } catch (_: Throwable) {}
        }
    }
}
