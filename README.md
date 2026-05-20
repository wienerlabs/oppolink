# OppoLink — BLE-Native Full-Duplex Voice Protocol for Oppo Devices

## Mission
Build an Android application enabling **infrastructure-free, internet-free, router-free full-duplex voice calls** between two Oppo (ColorOS) devices over **Bluetooth Low Energy** alone. Target: FaceTime Audio-grade UX with <100ms mouth-to-ear latency, ~10m range (Class 2) up to 100m (Class 1), zero servers, zero accounts, zero telemetry. Functions on any Android 10+ device but UX, battery profiling, and OEM permission flows are tuned for **ColorOS 13+** on Reno / Find / A-series hardware.

## Non-Goals
- No video — BLE bandwidth prohibits it.
- No multi-party calls in v1 (point-to-point only).
- No Wi-Fi Direct fallback in v1 — purity of "BLE-only" is the product thesis.
- No iOS in v1 — CoreBluetooth's L2CAP CoC API exists but cross-platform handshake adds scope.
- No cellular / Wi-Fi / internet dependency, ever. Offline by design.

## Why OppoLink (and not generic Android)
ColorOS imposes aggressive process lifecycle management that breaks generic BLE background apps: **Startup Manager**, **Battery Optimization**, **App Auto-Launch**, and **Floating Window** permissions all gate persistent foreground services in ways stock AOSP does not. OppoLink ships first-run flows that deep-link into the exact ColorOS settings screens with version-aware screenshot guidance for ColorOS 13, 14, and 15. Generic Android compatibility is preserved but Oppo is the reference platform.

## Architecture

### Transport Stack
- **Discovery**: BLE GATT advertising. Custom 128-bit service UUID `0xOPPL`-prefixed. Manufacturer-specific data carries a short device nickname (max 16 bytes) and a 4-byte session-capability bitmap.
- **Negotiation**: GATT characteristic exchange establishes which peer becomes L2CAP server (lower BD_ADDR wins, deterministic tie-break).
- **Data plane**: **L2CAP Connection-Oriented Channel** via `BluetoothDevice.createInsecureL2capChannel(psm)` (Android 10+, API 29). PSM is dynamically allocated by the server peer and advertised through a GATT characteristic before connect.
- **PHY**: Request **LE 2M PHY** via `BluetoothGatt.setPreferredPhy(PHY_LE_2M, PHY_LE_2M, PHY_OPTION_NO_PREFERRED)` for ~2 Mbps raw throughput.
- **Connection interval**: Request 7.5–15 ms via connection parameter update for low latency.

### Audio Pipeline
- **Capture**: `AudioRecord` with `MediaRecorder.AudioSource.VOICE_COMMUNICATION`, 16 kHz mono, 16-bit PCM, 20 ms frames (320 samples).
- **Hardware effects**: Enable `AcousticEchoCanceler`, `NoiseSuppressor`, `AutomaticGainControl` on the capture session — check `isAvailable()` first. Oppo's MTK and Snapdragon platforms both expose these.
- **Codec**: **libopus** via JNI. Application type `OPUS_APPLICATION_VOIP`, bitrate 24 kbps, complexity 5, FEC enabled, DTX optional (off in v1 — simplifies jitter buffer).
- **Framing**: Each Opus packet wrapped in a 4-byte header: `[seq:u16][timestamp:u16]` (timestamp in 20ms ticks, wraps every ~21 min — fine for VoIP).
- **Jitter buffer**: Adaptive, 40–100 ms target, with PLC for lost packets (Opus has built-in `opus_decode(NULL, ...)` PLC).
- **Playback**: `AudioTrack` with `STREAM_VOICE_CALL`, 16 kHz, mono, `MODE_STREAM`, minimum buffer × 2.

### Concurrency Model
- **Capture thread**: real-time priority, dedicated, blocks on `AudioRecord.read()`, encodes, hands frame to lock-free SPSC ring buffer.
- **TX thread**: reads from ring, writes to L2CAP socket.
- **RX thread**: reads L2CAP socket, parses header, pushes into jitter buffer.
- **Playback thread**: pops from jitter buffer on 20 ms tick, decodes, writes to `AudioTrack`.
- **Control thread**: GATT events, connection state, UI updates.

## Tech Stack
- **Language**: Kotlin 2.x for app/control, **Rust** for codec + framing + jitter buffer (cross-compiled to `arm64-v8a` and `armeabi-v7a` via `cargo-ndk`, exposed via UniFFI). Keeps the audio core portable for a future iOS port.
- **Build**: Gradle 8.x, AGP 8.x, Kotlin DSL, version catalog.
- **Min SDK**: 29 (Android 10 — required for L2CAP CoC).
- **Target SDK**: 35 (Android 15 / ColorOS 15).
- **UI**: Jetpack Compose, Material 3, single-activity, no navigation library (3 screens max in v1).
- **DI**: Hilt.
- **Async**: Kotlin Coroutines + Flow for UI state; raw threads for the audio hot path (coroutines have unacceptable scheduling jitter for real-time audio).

## Repository Layout

```
oppolink/
├── CLAUDE.md
├── README.md
├── LICENSE                          # AGPL-3.0
├── DISCLAIMER.md                    # not affiliated with OPPO trademark statement
├── android/
│   ├── app/                         # Compose UI, MainActivity, ForegroundService
│   ├── core-bluetooth/              # GATT + L2CAP transport
│   ├── core-audio/                  # AudioRecord/AudioTrack wrappers, effects
│   ├── core-protocol/               # UniFFI bindings to Rust crate
│   └── coloros-compat/              # OEM-specific deep links, Startup Manager wizard
├── rust/
│   ├── oppolink-codec/              # libopus FFI + framing
│   ├── oppolink-jitter/             # jitter buffer + PLC orchestration
│   └── oppolink-protocol/           # wire format, handshake state machine
├── docs/
│   ├── PROTOCOL.md                  # wire format spec
│   ├── ARCHITECTURE.md              # threading + data flow diagrams
│   ├── COLOROS_COMPAT.md            # tested device matrix
│   └── THREAT_MODEL.md              # what BLE-only buys, what it doesn't
└── .github/workflows/               # CI: build, test, lint
```

## Sprint 1 — Foundations (Week 1)

1. **Project skeleton**: Gradle multi-module Android project + Rust workspace + UniFFI integration verified by a `hello_world()` call from Kotlin into Rust returning a string. CI green on push.
2. **BLE discovery**: Two devices see each other's advertising packets. UI shows a list of nearby OppoLink peers by nickname + RSSI.
3. **GATT handshake**: Tap a peer → GATT connect → exchange L2CAP PSM via a custom characteristic → tear down GATT (or keep open for control plane).
4. **L2CAP echo test**: Open L2CAP CoC, send 1 KB packets back and forth, measure round-trip latency. Target: <30 ms median on LE 2M PHY.

## Sprint 2 — Audio MVP (Week 2)

5. **One-way audio**: Capture → Opus encode → L2CAP TX → L2CAP RX → Opus decode → playback. Mono, no PLC yet, fixed bitrate.
6. **Full-duplex**: Both directions simultaneous. Verify no priority inversion between capture and playback threads.
7. **AEC validation**: Confirm hardware AEC engages on Reno 11 / Find X7; speaker-phone test in a small room without howling.

## Sprint 3 — ColorOS Hardening (Week 3)

8. **Jitter buffer + PLC**: Adaptive depth, smooth handling of 0–5% packet loss.
9. **Foreground service** with persistent notification ("OppoLink — call active 02:14").
10. **ColorOS battery whitelist wizard**: First-run flow deep-links into Startup Manager (`com.coloros.safecenter`) and Battery Optimization screens with version-detected screenshots.
11. **Reconnect on drop**: If L2CAP socket breaks, attempt reconnect within 5 seconds before terminating the call.

## Sprint 4 — Release (Week 4)

12. **Push-to-talk mode** as an option (saves battery, mutes mic until held).
13. **Encryption**: Curve25519 ECDH at handshake, ChaCha20-Poly1305 on the L2CAP stream. BLE pairing alone is insufficient — bond-less operation is a feature, so we layer our own AEAD.
14. **Battery profiling**: Target <5%/hour drain during an active call on Reno-class hardware.
15. **Release**: Signed APK, GitHub release, F-Droid manifest. Play Store deferred pending trademark review.

## Wire Format (v1 draft)

```
Handshake (over GATT characteristic 0xOPPL0001):
  ┌─────────────────────────────────────────────┐
  │ magic: "OPL1" (4B)                          │
  │ version: u8                                 │
  │ role: u8  (0=server, 1=client)              │
  │ pubkey: [u8; 32]  (Curve25519)              │
  │ psm: u16  (L2CAP PSM, server→client only)   │
  │ nick_len: u8                                │
  │ nick: [u8; nick_len]                        │
  └─────────────────────────────────────────────┘

Audio frame (over L2CAP):
  ┌─────────────────────────────────────────────┐
  │ seq: u16  (big-endian)                      │
  │ ts:  u16  (20ms units)                      │
  │ nonce: [u8; 12]  (ChaCha20-Poly1305)        │
  │ ciphertext: variable  (encrypted Opus)      │
  │ tag: [u8; 16]                               │
  └─────────────────────────────────────────────┘
```

## Permissions

```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
                 android:usesPermissionFlags="neverForLocation"/>
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT"/>
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE"/>
<uses-permission android:name="android.permission.RECORD_AUDIO"/>
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
```

## Reference Device Matrix
- **Tier 1** (must work flawlessly): Oppo Reno 11, Reno 12, Find X7, Find N3.
- **Tier 2** (must work): A78, A98, K12.
- **Tier 3** (best-effort): generic Android 10+ on Snapdragon / MediaTek / Exynos.

## Quality Bars
- **Latency**: <100 ms mouth-to-ear at p50, <150 ms at p95.
- **MOS**: ≥4.0 on PESQ in a quiet room at 5m.
- **Battery**: <5%/hour active call on Reno 11.
- **Cold start to call**: <3 seconds tap-to-audio.
- **Crash-free sessions**: >99.5%.

## Conventions
- Conventional Commits (`feat:`, `fix:`, `perf:`, `refactor:`).
- One PR = one logical change. No mega-PRs.
- Every public Rust function gets a doc comment with `# Safety` if `unsafe`.
- Every Kotlin module has a `README.md` explaining its boundary.
- Audio hot-path code: **no allocations** after the call starts. Pre-allocate everything in `prepare()`.

## Build

Prerequisites: Android Studio Ladybug+ (with Android SDK 35 and NDK r27+), Rust stable + `aarch64-linux-android` and `armv7-linux-androideabi` targets, `cargo-ndk`, JDK 21.

```bash
# Install Rust targets and cargo-ndk
rustup target add aarch64-linux-android armv7-linux-androideabi
cargo install cargo-ndk

# Generate Gradle wrapper (first time)
cd android && gradle wrapper --gradle-version 8.10.2

# Build Rust → JNI libs → APK
cd android && ./gradlew :app:assembleDebug
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for module boundaries and the UniFFI build pipeline.

## Trademark & Affiliation
OppoLink is an independent open-source project. **Not affiliated with, endorsed by, or sponsored by Guangdong OPPO Mobile Telecommunications Corp., Ltd.** "Oppo" and "ColorOS" are trademarks of their respective owner. See [DISCLAIMER.md](DISCLAIMER.md).

## License
[AGPL-3.0](LICENSE) — copyleft. If you ship a modified version, the source must be available to your users.
