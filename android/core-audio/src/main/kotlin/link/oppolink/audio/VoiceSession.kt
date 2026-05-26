package link.oppolink.audio

/**
 * Lifecycle the `:app` and `:core-bluetooth` layers drive during a call.
 *
 * Sprint 2 D5 ships [AudioCapture] and [AudioPlayback] directly rather
 * than behind a `VoiceSession` facade - the call orchestration in
 * `PeerConnector.runCall(…)` wires them up itself so that priority +
 * thread affinity choices live next to the L2CAP socket loop.
 *
 * Kept around as documentation for Sprint 3 D8 / D9, where the
 * foreground service will want a single `VoiceSession` interface to
 * `start()` / `stop()` / `setMuted()`.
 */
interface VoiceSession {
    fun prepare()
    fun start()
    fun stop()
    fun release()
    fun setMuted(muted: Boolean)
}
