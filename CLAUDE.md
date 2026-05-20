# CLAUDE.md — OppoLink Session Brief

This file is loaded into every Claude Code session in this repo. Keep it terse, current, and load-bearing.

## What this project is
BLE-only full-duplex voice protocol for Android, tuned for Oppo / ColorOS. Two phones, no internet, no router, no cellular, FaceTime-Audio-grade UX. Reference: [README.md](README.md).

## Non-negotiables
- **Offline-only**. Never add a dependency that opens a socket to the public internet. No telemetry, no crash reporters that phone home, no analytics. Even "anonymous" telemetry is a hard no.
- **No allocations in the audio hot path** after `call.start()`. Pre-allocate ring buffers, jitter buffers, encode/decode scratch in `prepare()`. The audio thread budget is 20 ms — a GC pause kills the call.
- **No coroutines on the audio hot path**. Coroutine dispatcher scheduling jitter is unacceptable for 20 ms ticks. Use a dedicated `Thread` at `THREAD_PRIORITY_URGENT_AUDIO`. Coroutines are fine for UI / GATT / control plane.
- **Trademark hygiene**. Do not use the word "Oppo" in package names, signing certificates, store listings, or any artifact that could be construed as official. "OppoLink" is acceptable as a compatibility-targeting indie project name. See [DISCLAIMER.md](DISCLAIMER.md).
- **AGPL-3.0 + copyleft awareness**. Any code you pull in must be AGPL-compatible. No proprietary SDKs, no "free for non-commercial" licenses.

## Architecture quick reference
- `android/app` — Compose UI, MainActivity, ForegroundService. Single-activity.
- `android/core-bluetooth` — GATT advertising/scanning, L2CAP CoC socket.
- `android/core-audio` — `AudioRecord` / `AudioTrack` wrappers, hardware effects (AEC/NS/AGC).
- `android/core-protocol` — UniFFI-generated Kotlin bindings to the Rust core.
- `android/coloros-compat` — version-detected deep links to ColorOS settings screens.
- `rust/oppolink-codec` — libopus FFI, 20 ms frame encode/decode, framing header.
- `rust/oppolink-jitter` — adaptive jitter buffer + PLC orchestration.
- `rust/oppolink-protocol` — wire format, handshake state machine, AEAD (ChaCha20-Poly1305).

## Tech stack lock
- Kotlin 2.1.0, AGP 8.7.3, Gradle 8.10.2, JDK 21.
- Compose BOM 2024.12.01, Material 3.
- Rust stable (1.83+), UniFFI 0.28 (proc-macros, **no UDL files**).
- cargo-ndk for cross-compilation, integrated via Gradle `Exec` task (no `rust-android-gradle` plugin).
- Hilt for DI on the JVM side. No DI inside Rust.

## Build flow
1. `cargo ndk -t arm64-v8a -t armeabi-v7a -o android/core-protocol/src/main/jniLibs build --release`
2. UniFFI bindgen emits `oppolink_protocol.kt` into `android/core-protocol/build/generated/uniffi/`.
3. Gradle compiles + packages everything into the APK.

The `:core-protocol:preBuild` task depends on both steps above. See `android/core-protocol/build.gradle.kts`.

## Conventions
- **Conventional Commits**: `feat:`, `fix:`, `perf:`, `refactor:`, `docs:`, `test:`, `chore:`, `ci:`.
- **One PR = one logical change**. No mega-PRs.
- **Audio hot path code**: comment any allocation. If you must allocate, the comment must justify why it's safe (e.g., "only on cold-start, never on the 20 ms tick").
- **Rust public functions**: doc comments mandatory. `unsafe` functions: `# Safety` section mandatory.
- **Kotlin modules**: each has a `README.md` stating the module boundary in 1–3 sentences.

## What to do when starting a Sprint deliverable
1. Read [README.md](README.md) sprint section for the deliverable.
2. Read the relevant `docs/*.md` if architecture is touched.
3. Plan first (write a checklist), then implement.
4. Tests for Rust (`cargo test`), instrumented tests for Android where it touches Bluetooth or audio.
5. Commit with a Conventional Commit subject. Open PR. Squash on merge.

## Things that have burned us before (anti-corpus)
*(Empty for now — fill as we hit them.)*

## Useful pointers
- Android L2CAP CoC docs: <https://developer.android.com/develop/connectivity/bluetooth/ble/connect-gatt-server#l2cap-channels>
- UniFFI proc-macro guide: <https://mozilla.github.io/uniffi-rs/proc_macro/index.html>
- Opus VoIP recommendations: <https://datatracker.ietf.org/doc/html/rfc7587>
- ColorOS Startup Manager intent: `com.coloros.safecenter/.startupapp.StartupAppListActivity` (verify on device, varies by version).
