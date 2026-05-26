package link.oppolink.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.io.Closeable
import uniffi.oppolink_protocol.pcmSampleRateHz
import uniffi.oppolink_protocol.pcmSamplesPerFrame

/**
 * Speaker / earpiece playback pipeline (Sprint 2 D5).
 *
 * Configures `AudioTrack` for the `VOICE_CALL` usage so the OS routes the
 * stream through the correct audio policy (echo-canceller path, volume
 * curve, in-call notification volume). Write is **blocking**; call
 * `start()` once and loop `writeFrame(pcm)` from a dedicated [Thread] at
 * audio priority.
 */
class AudioPlayback : Closeable {

    private val sampleRate: Int = pcmSampleRateHz().toInt()
    private val samplesPerFrame: Int = pcmSamplesPerFrame().toInt()

    /** Number of `short`s in one 20 ms PCM frame. */
    val frameSamples: Int = samplesPerFrame

    private var track: AudioTrack? = null

    fun prepare() {
        check(track == null) { "AudioPlayback already prepared" }

        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBuffer != AudioTrack.ERROR && minBuffer != AudioTrack.ERROR_BAD_VALUE) {
            "AudioTrack.getMinBufferSize rejected configuration"
        }
        val bufferBytes = maxOf(minBuffer, samplesPerFrame * 2 * 4)

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        require(track!!.state == AudioTrack.STATE_INITIALIZED) {
            "AudioTrack failed to initialise"
        }
    }

    fun start() {
        val t = track ?: error("Call prepare() first")
        t.play()
    }

    /**
     * Blocking write. Returns the number of samples actually written; the
     * caller should compare against [frameSamples] to detect under-runs.
     */
    fun writeFrame(pcm: ShortArray): Int {
        val t = track ?: return 0
        require(pcm.size >= samplesPerFrame) {
            "frame must hold ≥ $samplesPerFrame samples (got ${pcm.size})"
        }
        var written = 0
        while (written < samplesPerFrame) {
            val n = t.write(pcm, written, samplesPerFrame - written, AudioTrack.WRITE_BLOCKING)
            if (n <= 0) return written
            written += n
        }
        return written
    }

    fun stop() {
        try {
            track?.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioTrack.stop ignored: ${e.message}")
        }
    }

    override fun close() {
        runCatching { track?.release() }
        track = null
    }

    @Suppress("unused")
    private fun forceSpeakerphone(audioManager: AudioManager) {
        // Helper kept around for the D7 AEC validation work - the
        // speaker-phone howl test needs the loudspeaker even when
        // headphones are connected.
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true
    }

    private companion object {
        const val TAG = "AudioPlayback"
    }
}
