package link.oppolink.audio

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.annotation.RequiresPermission
import java.io.Closeable
import uniffi.oppolink_protocol.pcmFrameDurationMs
import uniffi.oppolink_protocol.pcmSampleRateHz
import uniffi.oppolink_protocol.pcmSamplesPerFrame

/**
 * Microphone capture pipeline (Sprint 2 D5).
 *
 * Configures `AudioRecord` for VoIP - 16 kHz mono PCM, 20 ms frames - and
 * attaches hardware AEC / NS / AGC effects when the OEM reports them
 * available. The audio session id wired into the effects is the one
 * `AudioRecord` allocates internally; that's the only path that lets the
 * stock effects engage on Snapdragon and MediaTek devices.
 *
 * Read is **blocking**. Call `start()` once, then loop `readFrame(buffer)`
 * from a dedicated [Thread] at audio priority. `release()` tears the
 * session down; it is safe to call twice.
 */
class AudioCapture : Closeable {

    private val samplesPerFrame: Int = pcmSamplesPerFrame().toInt()
    private val sampleRate: Int = pcmSampleRateHz().toInt()
    /** Number of `short`s in one 20 ms PCM frame. */
    val frameSamples: Int = samplesPerFrame

    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    /**
     * Initialise the underlying `AudioRecord`. Throws if the device denies
     * the configuration (rare on Reno-class hardware but possible on
     * generic-Android emulators).
     */
    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun prepare() {
        check(record == null) { "AudioCapture already prepared" }

        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBuffer != AudioRecord.ERROR && minBuffer != AudioRecord.ERROR_BAD_VALUE) {
            "AudioRecord.getMinBufferSize rejected configuration"
        }
        // 4× headroom against scheduler jitter; one 20 ms read consumes
        // `samplesPerFrame` shorts = 640 bytes.
        val bufferBytes = maxOf(minBuffer, samplesPerFrame * 2 * 4)

        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
        )
        require(rec.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord failed to initialise (state=${rec.state})"
        }

        // Hardware effects are best-effort - `isAvailable()` lies on some
        // OEMs but `create()` will return null if it lied.
        val sessionId = rec.audioSessionId
        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(sessionId)?.also {
                it.enabled = true
                Log.i(TAG, "AEC engaged for session $sessionId")
            }
        }
        if (NoiseSuppressor.isAvailable()) {
            ns = NoiseSuppressor.create(sessionId)?.also { it.enabled = true }
        }
        if (AutomaticGainControl.isAvailable()) {
            agc = AutomaticGainControl.create(sessionId)?.also { it.enabled = true }
        }

        record = rec
    }

    /** Begin recording. Must follow [prepare]. */
    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        val rec = record ?: error("Call prepare() first")
        rec.startRecording()
    }

    /**
     * Fill [dst] with [frameSamples] PCM samples. Blocks the calling
     * thread up to one frame's worth of wall-clock time. Returns the
     * number of samples written (== [frameSamples] on success).
     */
    fun readFrame(dst: ShortArray): Int {
        require(dst.size >= samplesPerFrame) {
            "buffer must hold ≥ $samplesPerFrame samples (got ${dst.size})"
        }
        val rec = record ?: return 0
        var read = 0
        while (read < samplesPerFrame) {
            val n = rec.read(dst, read, samplesPerFrame - read)
            if (n <= 0) return read
            read += n
        }
        return read
    }

    /** Stop the recording session; safe to call when not running. */
    fun stop() {
        try {
            record?.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioRecord.stop ignored: ${e.message}")
        }
    }

    /**
     * Toggle mute. `true` stops the underlying `AudioRecord` (the mic
     * truly goes off, saving battery on a push-to-talk call). `false`
     * resumes recording. Idempotent - calling twice with the same value
     * is a no-op, so the Tx loop can call this on every press / release
     * without ceremony.
     *
     * Hardware effects (AEC / NS / AGC) survive the stop/start cycle -
     * they're attached to the `AudioRecord` session id which doesn't
     * change.
     */
    fun setMuted(muted: Boolean) {
        val rec = record ?: return
        try {
            val recording = rec.recordingState == android.media.AudioRecord.RECORDSTATE_RECORDING
            if (muted && recording) {
                rec.stop()
                Log.d(TAG, "mic muted")
            } else if (!muted && !recording) {
                rec.startRecording()
                Log.d(TAG, "mic unmuted")
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "setMuted($muted) ignored: ${e.message}")
        }
    }

    override fun close() {
        runCatching { aec?.release() }
        runCatching { ns?.release() }
        runCatching { agc?.release() }
        aec = null; ns = null; agc = null
        runCatching { record?.release() }
        record = null
    }

    companion object {
        private const val TAG = "AudioCapture"

        /** Convenience: nominal 20 ms tick in milliseconds (== 20). */
        val frameDurationMs: Int get() = pcmFrameDurationMs().toInt()
    }
}
