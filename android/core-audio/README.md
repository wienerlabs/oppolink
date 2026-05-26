# `:core-audio`

Audio capture, playback, and hardware effects (AEC / NS / AGC). Wraps Android's
`AudioRecord` and `AudioTrack` with allocation-free, real-time-priority threads.

## Boundary
- **In**: `:core-protocol` for Opus encode/decode invocations via UniFFI.
- **Out**: a `VoiceSession` interface that the app drives - `start()`, `stop()`,
  `setMuted()`.

## Sprint 1 scope
Stubs only. Real capture/playback pipeline lands in Sprint 2.

## Hard rules
- **No allocations** after `start()`. Pre-allocate ring buffers in `prepare()`.
- Capture and playback run on dedicated `Thread` instances at
  `THREAD_PRIORITY_URGENT_AUDIO`. No coroutines on the hot path.
