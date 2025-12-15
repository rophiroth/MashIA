package org.psyhackers.mashia.asr

import android.content.Context
import android.content.SharedPreferences
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.psyhackers.mashia.R
import java.util.concurrent.TimeUnit
import kotlin.math.min

class CloudAsr(private val context: Context) : AsrEngine {
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(75, TimeUnit.SECONDS)
            .build()
    }

    private var baseUrl: String = ""
    private var apiKey: String = ""
    private var model: String = ""
    private var modelPref: String = "auto"
    private var language: String = "auto"
    private var prompt: String = DEFAULT_PROMPT

    @Volatile private var lastErr: String = ""

    override fun isReady(): Boolean = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()

    override fun applyPrefs(prefs: SharedPreferences) {
        val groqKey = try { context.getString(R.string.groq_api_key).trim() } catch (_: Throwable) { "" }
        val openaiKey = try { context.getString(R.string.openai_api_key).trim() } catch (_: Throwable) { "" }
        modelPref = prefs.getString(org.psyhackers.mashia.ui.SettingsFragment.KEY_CLOUD_STT_MODEL, "auto")
            ?.trim()
            ?.lowercase()
            .orEmpty()
            .ifBlank { "auto" }

        val forceOpenAi = modelPref == "openai_whisper1" && openaiKey.isNotBlank()
        val useGroq = !forceOpenAi && groqKey.isNotBlank()

        apiKey = if (useGroq) groqKey else openaiKey
        baseUrl =
            (if (useGroq) context.getString(R.string.ai_base_groq) else context.getString(R.string.ai_base_openai)).trimEnd('/')

        model =
            if (useGroq) {
                when (modelPref) {
                    "groq_v3" -> "whisper-large-v3"
                    "groq_turbo", "auto" -> "whisper-large-v3-turbo"
                    else -> "whisper-large-v3-turbo"
                }
            } else {
                "whisper-1"
            }

        language = prefs.getString(org.psyhackers.mashia.ui.SettingsFragment.KEY_LANGUAGE, "auto") ?: "auto"
        lastErr = ""
    }

    fun lastError(): String = lastErr
    fun lastModel(): String = model
    fun usingGroq(): Boolean = baseUrl.contains("groq.com", ignoreCase = true)

    override fun transcribeShortPcm(pcm: ShortArray, sampleRate: Int): String? {
        if (!isReady()) {
            lastErr = "cloud: missing apiKey/baseUrl/model"
            return null
        }
        if (pcm.isEmpty()) return ""

        val wav = pcm16ToWavMono(pcm, sampleRate)
        val fileBody = wav.toRequestBody("audio/wav".toMediaType())
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "audio.wav", fileBody)
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("temperature", "0")
            .apply {
                if (language.isNotBlank() && language != "auto") {
                    addFormDataPart("language", language)
                }
                val p = prompt.trim()
                if (p.isNotBlank()) {
                    addFormDataPart("prompt", p.take(900))
                }
            }
            .build()

        val url = baseUrl + "/v1/audio/transcriptions"
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(multipart)
            .build()

        try {
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    // Groq model differences: if turbo isn't supported, fallback once to v3.
                    if (usingGroq() &&
                        model == "whisper-large-v3-turbo" &&
                        (modelPref == "auto" || modelPref == "groq_turbo") &&
                        (resp.code == 400 || resp.code == 404)
                    ) {
                        model = "whisper-large-v3"
                        return transcribeShortPcm(pcm, sampleRate)
                    }
                    lastErr = "cloud http ${resp.code}: " + raw.take(280)
                    return ""
                }
                val text = try { JSONObject(raw).optString("text").orEmpty() } catch (_: Throwable) { "" }
                if (text.isBlank()) {
                    lastErr = "cloud ok but empty: " + raw.take(280)
                } else {
                    lastErr = ""
                }
                return text
            }
        } catch (t: Throwable) {
            lastErr = "cloud exception ${t.javaClass.simpleName}: ${t.message}"
            return ""
        }
    }

    private fun pcm16ToWavMono(pcm: ShortArray, sampleRate: Int): ByteArray {
        val sr = sampleRate.coerceIn(8000, 48000)
        val dataSize = pcm.size * 2
        val totalSize = 44 + dataSize
        val out = ByteArray(totalSize)

        fun putAscii(pos: Int, s: String) {
            val bytes = s.toByteArray(Charsets.US_ASCII)
            System.arraycopy(bytes, 0, out, pos, min(bytes.size, out.size - pos))
        }
        fun putLe16(pos: Int, v: Int) {
            out[pos] = (v and 0xff).toByte()
            out[pos + 1] = ((v shr 8) and 0xff).toByte()
        }
        fun putLe32(pos: Int, v: Int) {
            out[pos] = (v and 0xff).toByte()
            out[pos + 1] = ((v shr 8) and 0xff).toByte()
            out[pos + 2] = ((v shr 16) and 0xff).toByte()
            out[pos + 3] = ((v shr 24) and 0xff).toByte()
        }

        putAscii(0, "RIFF")
        putLe32(4, totalSize - 8)
        putAscii(8, "WAVE")
        putAscii(12, "fmt ")
        putLe32(16, 16) // PCM chunk size
        putLe16(20, 1) // PCM format
        putLe16(22, 1) // channels
        putLe32(24, sr)
        putLe32(28, sr * 2) // byte rate
        putLe16(32, 2) // block align
        putLe16(34, 16) // bits
        putAscii(36, "data")
        putLe32(40, dataSize)

        var p = 44
        for (s in pcm) {
            val v = s.toInt()
            out[p] = (v and 0xff).toByte()
            out[p + 1] = ((v shr 8) and 0xff).toByte()
            p += 2
        }
        return out
    }

    companion object {
        private const val DEFAULT_PROMPT =
            "Transcribe speech accurately. The speaker may switch between Spanish and English. " +
                "Preserve the original language (do not translate), punctuation, and proper nouns."
    }
}
