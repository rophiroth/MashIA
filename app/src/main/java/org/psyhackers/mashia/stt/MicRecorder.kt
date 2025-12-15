package org.psyhackers.mashia.stt

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

class MicRecorder(
    private val targetSampleRate: Int = 16000,
    private val onChunk: (ShortArray) -> Unit,
    private val onReady: ((source: Int, rate: Int, bufferSize: Int) -> Unit)? = null,
    private val onLog: ((String) -> Unit)? = null
) {
    companion object { private const val TAG = "MicRecorder" }

    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private var actualRate: Int = targetSampleRate
    private var selectedSource: Int = MediaRecorder.AudioSource.MIC

    fun start() {
        if (running) return
        // Source order matters a lot between ROMs/devices.
        // Prefer MIC first (usually has sane gain), then voice sources; try UNPROCESSED last.
        val sources = buildList {
            add(MediaRecorder.AudioSource.MIC)
            add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            add(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                add(MediaRecorder.AudioSource.VOICE_PERFORMANCE)
            }
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                add(MediaRecorder.AudioSource.UNPROCESSED)
            }
            add(MediaRecorder.AudioSource.CAMCORDER)
            add(MediaRecorder.AudioSource.DEFAULT)
        }.toIntArray()
        val rates = intArrayOf(targetSampleRate, 16000, 11025, 8000, 44100, 48000, 22050, 32000)
        var rec: AudioRecord? = null
        var bufSize = 0
        outer@ for (src in sources) {
            for (rate in rates) {
                val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minBuf <= 0 || minBuf == AudioRecord.ERROR_BAD_VALUE) continue
                val bsz = (minBuf * 2).coerceAtLeast(rate / 2)
                val tmp = if (android.os.Build.VERSION.SDK_INT >= 23) {
                    val fmt = AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                    val builder = AudioRecord.Builder()
                        .setAudioSource(src)
                        .setAudioFormat(fmt)
                        .setBufferSizeInBytes(bsz)
                    builder.build()
                } else {
                    AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bsz)
                }
                if (tmp.state == AudioRecord.STATE_INITIALIZED) {
                    rec = tmp
                    actualRate = rate
                    selectedSource = src
                    bufSize = bsz
                    Log.i(TAG, "mic init ok: src=$src rate=$rate buf=$bsz")
                    break@outer
                } else {
                    try { tmp.release() } catch (_: Throwable) {}
                }
            }
        }
        if (rec == null) {
            Log.e(TAG, "AudioRecord init failed for all configs")
            onLog?.invoke("mic: init failed all configs")
            running = false
            return
        }
        record = rec
        try { onReady?.invoke(selectedSource, actualRate, bufSize) } catch (_: Throwable) {}
        running = true
        thread = Thread({ loop(bufSize) }, "MicRecorder").also { it.start() }
    }

    private fun loop(bufferSize: Int) {
        val rec = record ?: return
        var zeroReads = 0
        var totalReads = 0
        var loggedReads = 0
        var agc: AutomaticGainControl? = null
        var ns: NoiseSuppressor? = null
        var aec: AcousticEchoCanceler? = null
        try {
            val buf = ShortArray(bufferSize)
            try {
                rec.startRecording()
            } catch (e: SecurityException) {
                Log.e(TAG, "startRecording security exception: ${e.message}")
                onLog?.invoke("mic: startRecording security ${e.message}")
                return
            } catch (e: Throwable) {
                Log.e(TAG, "startRecording failed: ${e.message}")
                onLog?.invoke("mic: startRecording failed ${e.message}")
                return
            }
            val state = rec.recordingState
            if (state != AudioRecord.RECORDSTATE_RECORDING) {
                onLog?.invoke("mic: recordingState=$state (expect ${AudioRecord.RECORDSTATE_RECORDING})")
            } else {
                onLog?.invoke("mic: recordingState=RECORDING")
            }
            try {
                val sid = rec.audioSessionId
                if (AutomaticGainControl.isAvailable()) {
                    agc = AutomaticGainControl.create(sid)
                    agc?.enabled = true
                }
                if (NoiseSuppressor.isAvailable()) {
                    ns = NoiseSuppressor.create(sid)
                    ns?.enabled = true
                }
                if (AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(sid)
                    aec?.enabled = true
                }
                onLog?.invoke(
                    "mic: audioSessionId=$sid agc=" + (agc != null) + " ns=" + (ns != null) + " aec=" + (aec != null)
                )
            } catch (_: Throwable) {}
            while (running) {
                val n = if (android.os.Build.VERSION.SDK_INT >= 23) {
                    rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                } else rec.read(buf, 0, buf.size)
                totalReads++
                if (loggedReads < 5) {
                    val first = if (n > 0) buf[0] else 0
                    onLog?.invoke("mic: read#$totalReads n=$n first=$first")
                    loggedReads++
                }
                if (n > 0) {
                    val raw = if (n == buf.size) buf.copyOf() else buf.copyOfRange(0, n)
                    val chunk = if (actualRate == targetSampleRate) raw else resample(raw, actualRate, targetSampleRate)
                    try { onChunk(chunk) } catch (e: Throwable) { Log.w(TAG, "onChunk error", e) }
                } else {
                    zeroReads++
                    if (zeroReads == 1 || zeroReads == 10 || zeroReads == 50 || zeroReads == 100 || zeroReads % 200 == 0) {
                        onLog?.invoke("mic: read returned $n (zeroReads=$zeroReads totalReads=$totalReads)")
                    }
                    if (n < 0) {
                        Log.e(TAG, "read error=$n (stopping)")
                        onLog?.invoke("mic: read error=$n stopping")
                        break
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "mic loop error", e)
            onLog?.invoke("mic loop error ${e.message}")
        } finally {
            try { agc?.release() } catch (_: Throwable) {}
            try { ns?.release() } catch (_: Throwable) {}
            try { aec?.release() } catch (_: Throwable) {}
            try { rec.stop() } catch (_: Throwable) {}
            try { rec.release() } catch (_: Throwable) {}
            onLog?.invoke("mic loop exit reads=$totalReads zeroReads=$zeroReads")
        }
    }

    private fun resample(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate <= 0 || toRate <= 0) return input
        val ratio = toRate.toDouble() / fromRate.toDouble()
        val outLen = Math.max(1, (input.size * ratio).toInt())
        val out = ShortArray(outLen)
        var srcPos = 0.0
        for (i in 0 until outLen) {
            val idx = srcPos.toInt().coerceIn(0, input.size - 1)
            val next = (idx + 1).coerceAtMost(input.size - 1)
            val frac = (srcPos - idx)
            val s = input[idx].toInt()
            val s2 = input[next].toInt()
            val v = (s + (s2 - s) * frac).toInt()
            out[i] = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            srcPos += 1.0 / ratio
        }
        return out
    }

    fun stop() {
        running = false
        val t = thread
        // If stop() is called from the recorder thread, don't join (it would deadlock).
        if (t != null && t === Thread.currentThread()) {
            thread = null
            record = null
            return
        }
        try { t?.join(500) } catch (_: Throwable) {}
        thread = null
        record = null
    }
}
