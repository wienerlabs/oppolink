package link.oppolink.audio

/**
 * The audio-pipeline lifecycle that the app drives during a call.
 *
 * Stubbed in Sprint 1 D1. Real `AudioRecord` + Opus encode + L2CAP TX wiring
 * arrives in Sprint 2.
 */
interface VoiceSession {
    fun prepare()
    fun start()
    fun stop()
    fun release()
    fun setMuted(muted: Boolean)
}
