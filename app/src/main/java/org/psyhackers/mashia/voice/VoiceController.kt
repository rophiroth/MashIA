package org.psyhackers.mashia.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import org.psyhackers.mashia.asr.AsrEngine
import org.psyhackers.mashia.stt.MicRecorder

class VoiceController(
    private val context: Context,
    private val asr: AsrEngine,
    private val onDebug: (String) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onState: (Boolean) -> Unit,
) {
    private var mic: MicRecorder? = null
    private val ui = Handler(Looper.getMainLooper())

    private val sessionCounter = AtomicInteger(0)
    @Volatile private var sessionId = 0

    @Volatile private var isListening = false
    @Volatile private var capturing = false
    private var continuous = false

    private val bufferLock = Any()
    private val pcmChunks = ArrayList<ShortArray>()
    private var totalSamples = 0

    private var startedAt = 0L
    @Volatile private var lastChunkAt = 0L
    private var silentMs = 0L
    private var heardVoice = false
    private var maxRms = 0.0f
    private var maxPeak = 0
    private var maxGainApplied = 1.0f
    private var chunkLogCount = 0

    private val decodeInFlight = AtomicBoolean(false)

    private val watchdog = object : Runnable {
        private var lastWarnAt = 0L
        override fun run() {
            if (!isListening) return
            if (!capturing) {
                ui.postDelayed(this, 1000)
                return
            }
            val now = System.currentTimeMillis()
            // watchdog solo informa; no corta la sesión
            if ((now - lastChunkAt) > 15000 && (now - lastWarnAt) > 5000) {
                onDebug("mic: no audio frames en 15s (permiso/dispositivo)")
                lastWarnAt = now
            }
            ui.postDelayed(this, 1000)
        }
    }

    // tunables
    var sampleRate = 16000
    // En móviles, el streaming parcial suele ser demasiado caro: decodificamos al final.
    var decodeIntervalMs = 1200L
    var silenceThreshold = 0.01f
    var silenceHoldMs = 1400L
    var maxUtteranceMs = 20000L
    var minUtteranceMs = 2500L
    var enableAutoGain = true
    var maxAutoGain = 16.0f
    var targetPeak = 12000 // PCM16 abs peak target
    var minPeakForVoice = 400 // ignore tiny noise

    fun start(continuous: Boolean) {
        if (isListening) return
        this.continuous = continuous
        val newSession = sessionCounter.incrementAndGet()
        sessionId = newSession

        isListening = true
        capturing = true
        silentMs = 0L
        heardVoice = false
        maxRms = 0.0f
        maxPeak = 0
        maxGainApplied = 1.0f
        chunkLogCount = 0
        startedAt = System.currentTimeMillis()
        lastChunkAt = startedAt
        decodeInFlight.set(false)
        synchronized(bufferLock) {
            pcmChunks.clear()
            totalSamples = 0
        }

        onDebug("whisper: listening start (cont=$continuous)")
        onState(true)

        mic = MicRecorder(
            targetSampleRate = sampleRate,
            onChunk = { chunk -> onMicChunk(newSession, chunk) },
            onReady = { src, rate, buf -> onDebug("mic: source=$src rate=$rate buf=$buf") },
            onLog = { msg -> onDebug(msg) },
        ).also { it.start() }

        ui.postDelayed(watchdog, 1000)
    }

    fun stop() {
        if (!isListening) return
        // Invalidate any in-flight decode results.
        sessionId = sessionCounter.incrementAndGet()
        isListening = false
        capturing = false
        ui.removeCallbacks(watchdog)
        try { mic?.stop() } catch (_: Throwable) {}
        mic = null
        ui.post { onState(false) }
    }

    fun stopAndFinalize(reason: String = "tap") {
        if (!isListening) return
        val session = sessionId
        capturing = false
        isListening = false
        ui.removeCallbacks(watchdog)
        try { mic?.stop() } catch (_: Throwable) {}
        mic = null
        ui.post { onState(false) }

        if (decodeInFlight.get()) {
            onDebug("whisper: decode already running (stop)")
            return
        }
        requestFinalDecode(session, reason)
    }

    private fun onMicChunk(session: Int, chunk: ShortArray) {
        if (!isListening || !capturing || sessionId != session) return

        val now = System.currentTimeMillis()
        lastChunkAt = now

        val chunkMs = (chunk.size * 1000L) / sampleRate

        var peak = 0
        var acc = 0.0
        for (s in chunk) {
            val v = s.toInt()
            val a = abs(v)
            if (a > peak) peak = a
            acc += v.toDouble() * v.toDouble()
        }
        val rms = (sqrt(acc / chunk.size) / 32768.0).toFloat()

        var gain = 1.0f
        var peakAfter = peak
        if (enableAutoGain && peak > 0) {
            gain = (targetPeak.toFloat() / peak.toFloat()).coerceIn(1.0f, maxAutoGain)
            if (gain > 1.01f) {
                var maxScaled = 0
                for (i in chunk.indices) {
                    val scaled = (chunk[i] * gain).toInt()
                    val clipped = scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    chunk[i] = clipped.toShort()
                    val absClipped = abs(clipped)
                    if (absClipped > maxScaled) maxScaled = absClipped
                }
                peakAfter = maxScaled
            } else {
                peakAfter = peak
            }
        }
        if (gain > maxGainApplied) maxGainApplied = gain
        val rmsAfter = rms * gain
        if (rmsAfter > maxRms) maxRms = rmsAfter
        maxPeak = max(maxPeak, peakAfter)

        if (chunkLogCount < 3) {
            chunkLogCount++
            onDebug("mic: chunk#$chunkLogCount peak=$peak gain=$gain peakAfter=$peakAfter rms=$rms rmsAfter=$rmsAfter")
        }

        val isVoiceFrame = rmsAfter >= silenceThreshold && peakAfter >= minPeakForVoice
        if (isVoiceFrame) {
            heardVoice = true
            silentMs = 0L
        } else {
            silentMs += chunkMs
        }

        synchronized(bufferLock) {
            pcmChunks.add(chunk)
            totalSamples += chunk.size
            trimBufferLocked()
        }

        val sinceStart = now - startedAt
        val shouldFinalize =
            ((sinceStart >= minUtteranceMs) && (silentMs >= silenceHoldMs)) || (sinceStart >= maxUtteranceMs)
        if (shouldFinalize) {
            capturing = false
            try { mic?.stop() } catch (_: Throwable) {}
            mic = null
            val reason = if (sinceStart >= maxUtteranceMs) "max" else "silence"
            requestFinalDecode(session, reason)
        }
    }

    private fun trimBufferLocked() {
        val maxSamples = (sampleRate * (maxUtteranceMs / 1000.0 + 2.0))
            .toInt()
            .coerceAtLeast(sampleRate * 8)
        while (totalSamples > maxSamples && pcmChunks.isNotEmpty()) {
            val removed = pcmChunks.removeAt(0)
            totalSamples -= removed.size
        }
    }

    private fun snapshotAudioLocked(): ShortArray {
        if (pcmChunks.isEmpty() || totalSamples <= 0) return ShortArray(0)
        val out = ShortArray(totalSamples)
        var p = 0
        for (arr in pcmChunks) {
            System.arraycopy(arr, 0, out, p, arr.size)
            p += arr.size
        }
        return out
    }

    private fun requestFinalDecode(session: Int, reason: String) {
        val audio = synchronized(bufferLock) { snapshotAudioLocked() }
        val durMs = if (sampleRate > 0) (audio.size * 1000L) / sampleRate else 0L
        onDebug(
            "whisper: finalize reason=$reason samples=${audio.size} durMs=$durMs silentMs=$silentMs maxRms=$maxRms maxPeak=$maxPeak maxGain=$maxGainApplied voice=$heardVoice"
        )

        if (audio.isEmpty()) {
            ui.post {
                if (sessionId != session) return@post
                onFinal("")
                if (continuous) restartCapture(session) else stop()
            }
            return
        }
        if (!decodeInFlight.compareAndSet(false, true)) {
            onDebug("whisper: decode already running")
            return
        }

        Thread({
            val t0 = System.currentTimeMillis()
            onDebug("whisper: decode start samples=${audio.size}")
            val result = try {
                asr.transcribeShortPcm(audio, sampleRate)?.trim().orEmpty()
            } catch (t: Throwable) {
                onDebug("whisper: decode exception ${t.javaClass.simpleName}: ${t.message}")
                ""
            }
            val dt = System.currentTimeMillis() - t0
            decodeInFlight.set(false)
            ui.post {
                if (sessionId != session) return@post
                onDebug("whisper: decoded ${dt}ms len=${result.length}")
                if (result.isBlank()) {
                    onDebug("whisper: text=(empty)")
                } else {
                    val oneLine = result.replace('\n', ' ').replace('\r', ' ')
                    onDebug("whisper: text=" + oneLine.take(220))
                }
                onFinal(result)
                if (continuous) restartCapture(session) else stop()
            }
        }, "WhisperDecode").start()
    }

    private fun restartCapture(session: Int) {
        if (!isListening || sessionId != session) return
        synchronized(bufferLock) {
            pcmChunks.clear()
            totalSamples = 0
        }
        capturing = true
        silentMs = 0L
        heardVoice = false
        maxRms = 0.0f
        maxPeak = 0
        maxGainApplied = 1.0f
        chunkLogCount = 0
        startedAt = System.currentTimeMillis()
        lastChunkAt = startedAt

        mic = MicRecorder(
            targetSampleRate = sampleRate,
            onChunk = { chunk -> onMicChunk(session, chunk) },
            onReady = { src, rate, buf -> onDebug("mic: source=$src rate=$rate buf=$buf") },
            onLog = { msg -> onDebug(msg) },
        ).also { it.start() }
    }

    private fun rms(buf: ShortArray): Float {
        var acc = 0.0
        for (s in buf) { val v = s.toDouble(); acc += v * v }
        val mean = acc / buf.size
        return (Math.sqrt(mean) / 32768.0).toFloat()
    }
}
