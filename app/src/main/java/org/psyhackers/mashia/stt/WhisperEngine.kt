package org.psyhackers.mashia.stt

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class WhisperEngine(private val context: Context) {
    companion object {
        private const val TAG = "WhisperEngine"
        init {
            try {
                System.loadLibrary("mashwhisper")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to load native lib", e)
            }
        }
    }

    private external fun nativeInitModel(modelPath: String, translate: Boolean, threads: Int): Boolean
    private external fun nativeTranscribeShort(pcm: ShortArray, sampleRate: Int): String
    private external fun nativeGetLastError(): String
    private external fun nativeConfigure(
        strategy: Int,
        beamSize: Int,
        noTimestamps: Boolean,
        temperature: Float,
        temperatureInc: Float,
        language: String
    )

    var lastInitTried: MutableList<String> = mutableListOf()
        private set
    var lastModelOk: String? = null
        private set

    fun isNativeReady(): Boolean = try {
        // crude test: call with empty path -> should return false
        false
    } catch (_: Throwable) { false }

    private fun ensureModelOnDisk(assetName: String): File? {
        val out = File(context.filesDir, assetName)
        if (out.exists() && out.length() > 0L) return out
        out.parentFile?.mkdirs()
        return try {
            context.assets.open(assetName).use { inS ->
                FileOutputStream(out).use { outS ->
                    val buf = ByteArray(1024 * 256)
                    var n: Int
                    while (inS.read(buf).also { n = it } > 0) outS.write(buf, 0, n)
                }
            }
            if (out.length() > 0L) out else null
        } catch (e: Exception) {
            Log.w(TAG, "Model asset not available: $assetName")
            null
        }
    }

    fun ensureBaseModel(): File? {
        return ensureModelOnDisk("models/ggml-base.bin") ?: ensureModelOnDisk("models/ggml-tiny.bin")
    }

    fun ensureTinyEnModel(): File? {
        return ensureModelOnDisk("models/ggml-tiny.en.bin") ?: ensureModelOnDisk("models/ggml-tiny.bin")
    }

    fun initBase(translateToEnglish: Boolean = false, threads: Int = Runtime.getRuntime().availableProcessors() / 2): Boolean {
        // Prefer base for accuracy; fallback to tiny for low-memory devices.
        val candidates = listOf("models/ggml-base.bin", "models/ggml-tiny.bin")
        val threadOptions = linkedSetOf(2, 3, 4, threads.coerceAtLeast(2).coerceAtMost(8))
        lastInitTried.clear()
        lastModelOk = null
        for (asset in candidates) {
            val f = ensureModelOnDisk(asset) ?: continue
            for (th in threadOptions) {
                try {
                    val ok = nativeInitModel(f.absolutePath, translateToEnglish, th)
                    lastInitTried.add("${f.absolutePath} (threads=$th)")
                    Log.i(TAG, "init model ${f.name} with $th threads: ${if (ok) "OK" else "FAIL"}")
                    if (ok) { lastModelOk = f.absolutePath; return true }
                    else {
                        Log.w(TAG, "native init failed detail: ${nativeGetLastError()}")
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "nativeInitModel failed for ${f.name} (threads=$th)", e)
                }
            }
        }
        return false
    }

    fun lastNativeError(): String = try { nativeGetLastError() } catch (_: Throwable) { "" }
    fun lastModelName(): String? = lastModelOk?.substringAfterLast('/')

    fun transcribeShortPcm(pcm: ShortArray, sampleRate: Int = 16000): String? = try {
        nativeTranscribeShort(pcm, sampleRate)
    } catch (e: Throwable) {
        Log.e(TAG, "nativeTranscribeShort failed", e)
        null
    }

    fun setConfig(
        strategyBeam: Boolean = true,
        beamSize: Int = 5,
        enableTimestamps: Boolean = false,
        temperature: Float = 0.0f,
        temperatureInc: Float = 0.2f,
        language: String = "auto"
    ) {
        try {
            nativeConfigure(
                if (strategyBeam) 1 else 0,
                beamSize.coerceIn(1, 8),
                !enableTimestamps,
                temperature,
                temperatureInc,
                language
            )
        } catch (_: Throwable) {}
    }

    fun applyPrefs(p: android.content.SharedPreferences) {
        val strategy = if (p.getString("whisper_strategy", "beam") == "beam") 1 else 0
        val beam = p.getInt("whisper_beam_size", 5).coerceIn(1, 8)
        val noTimestamps = !p.getBoolean("whisper_enable_timestamps", false)
        val temp = p.getFloatCompat("whisper_temperature", 0.0f)
        val tempInc = p.getFloatCompat("whisper_temperature_inc", 0.2f)
        val lang = p.getString("whisper_language", "auto") ?: "auto"
        try { nativeConfigure(strategy, beam, noTimestamps, temp, tempInc, lang) } catch (_: Throwable) {}
    }

    fun initSpecificAsset(assetPath: String, translateToEnglish: Boolean = false, threads: Int = 2): Boolean {
        val f = ensureModelOnDisk(assetPath) ?: return false
        return try {
            val ok = nativeInitModel(f.absolutePath, translateToEnglish, threads)
            if (ok) { lastModelOk = f.absolutePath }
            ok
        } catch (_: Throwable) { false }
    }
}

private fun android.content.SharedPreferences.getFloatCompat(key: String, def: Float): Float {
    return if (this.contains(key)) this.all[key]?.toString()?.toFloatOrNull() ?: def else def
}
