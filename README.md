<p align="center">
  <img src="docs/assets/oppolink-logo.webp" alt="OppoLink" width="280" />
</p>

<h1 align="center">OppoLink</h1>

<p align="center">
  <em>BLE-native, full-duplex voice calls between two Android phones — no internet, no router, no account.</em>
</p>

<p align="center">
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-AGPL--3.0-blue.svg" /></a>
  <a href="CHANGELOG.md"><img alt="Version" src="https://img.shields.io/badge/version-0.1.0-brightgreen.svg" /></a>
  <a href="docs/PROTOCOL.md"><img alt="Wire format" src="https://img.shields.io/badge/wire-v2-purple.svg" /></a>
  <img alt="Min SDK" src="https://img.shields.io/badge/Android-10%2B-3DDC84.svg" />
  <img alt="Reference platform" src="https://img.shields.io/badge/ColorOS-13%E2%80%9315-4F46E5.svg" />
</p>

---

## What is OppoLink?

OppoLink is an open-source Android app that places **full-duplex voice
calls directly over Bluetooth Low Energy** — no servers, no SIM, no
Wi-Fi. Two phones in range (≈10 m Class 2, up to ≈100 m Class 1) handshake
over GATT, open an L2CAP Connection-Oriented Channel, and stream Opus
audio frames encrypted with Curve25519 + ChaCha20-Poly1305.

The reference platform is **OPPO ColorOS 13+** on Reno / Find / A-series
hardware — the first-run wizard deep-links into the four ColorOS settings
surfaces that gate background BLE. Stock Android 10+ works too.

> _Not affiliated with Guangdong OPPO Mobile Telecommunications Corp. See [DISCLAIMER.md](DISCLAIMER.md)._

## Highlights

- **Zero infrastructure.** No internet, no carrier, no router, no
  account. The audio never touches IP.
- **End-to-end encrypted.** Curve25519 ECDH at handshake, ChaCha20-Poly1305
  AEAD on every 20 ms Opus frame. 6-digit decimal SAS for out-of-band
  MITM verification. (BLE pairing alone is **not** trusted.)
- **Adaptive jitter buffer.** Reorders out-of-order frames, dedupes
  duplicates, PLCs missing frames, grows depth on packet loss.
- **Survives screen-off.** Foreground service holds the call alive while
  the screen is locked; ColorOS battery-whitelist wizard preempts the
  OEM kill policy.
- **Reconnect on drop.** L2CAP socket break → silent reconnect inside
  5 seconds → resume the same call.
- **Push-to-talk.** Hardware mic toggle (real `AudioRecord.stop()`),
  hold-to-talk button.

## Quick start

### Run on real hardware

1. Install the signed APK on **two** Android 10+ phones (Reno-class
   recommended). See [Releases](https://github.com/wienerlabs/oppolink/releases)
   once `v0.1.0` ships.
2. Open OppoLink on both. Grant `BLUETOOTH_*` + `RECORD_AUDIO` + notifications.
3. On ColorOS, walk the battery / startup wizard once.
4. One side taps the other's name in the discovery list. Verify the
   6-digit SAS matches. Tap **Start full-duplex call**.

### Build from source

Prerequisites: Android Studio Ladybug+ (SDK 35, NDK r27+), Rust stable
with `aarch64-linux-android` + `armv7-linux-androideabi` targets,
`cargo-ndk`, JDK 21, CMake 3.5+.

```bash
git clone https://github.com/wienerlabs/oppolink.git
cd oppolink

rustup target add aarch64-linux-android armv7-linux-androideabi
cargo install cargo-ndk

cd android
gradle wrapper --gradle-version 8.10.2          # first run only
./gradlew :app:assembleDebug
```

Signed release builds: see [RELEASE.md](RELEASE.md).

## Architecture

```
oppolink/
├── android/                # Gradle multi-module Android project
│   ├── app/                # Compose UI, MainActivity, CallForegroundService
│   ├── core-bluetooth/     # GATT + L2CAP + Tx/Rx/Play thread pool
│   ├── core-audio/         # AudioRecord / AudioTrack wrappers + AEC/NS/AGC
│   ├── core-protocol/      # UniFFI bridge into Rust
│   └── coloros-compat/     # ColorOS battery / startup deep-link table
├── rust/                   # Cargo workspace, exposed via UniFFI
│   ├── oppolink-codec/     # libopus VoIP encoder/decoder + framing
│   ├── oppolink-jitter/    # adaptive jitter buffer + PLC orchestration
│   └── oppolink-protocol/  # wire format, ECDH, AEAD, UniFFI surface
└── docs/                   # PROTOCOL, ARCHITECTURE, COLOROS_COMPAT, THREAT_MODEL
```

| Layer | Tech |
| --- | --- |
| UI | Jetpack Compose + Material 3, single-activity, no nav library |
| DI | Hilt |
| BLE | Android `BluetoothLeAdvertiser` / `BluetoothLeScanner` + L2CAP CoC |
| Audio | `AudioRecord(VOICE_COMMUNICATION)` + `AudioTrack(USAGE_VOICE_COMMUNICATION)` at 16 kHz mono, 20 ms frames |
| Codec | libopus (24 kbps VoIP, complexity 5, FEC on, DTX off) via Rust + UniFFI |
| Crypto | x25519-dalek + chacha20poly1305 + hkdf-sha256 |
| Build | Gradle 8.10.2, AGP 8.7.3, Kotlin 2.1.0, JDK 21, cargo-ndk |

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the threading model
and the UniFFI build pipeline.

## Wire protocol — v2

Locked in Sprint 4 D13. Full spec in [docs/PROTOCOL.md](docs/PROTOCOL.md).

```
L2CAP socket payload (server side accepts):
  [client_pubkey:32]                         ← Curve25519, sent once
  [audio_frame_len:u16 BE | audio_frame …]   ← repeats
  [audio_frame_len:u16 BE | audio_frame …]
  …

audio_frame:
  [seq:u16 BE | ts:u16 BE | nonce:[12] | ciphertext | tag:[16]]
```

Key schedule:

```
shared_secret = X25519(my_secret, peer_pubkey)
salt = byte-min(my_pubkey, peer_pubkey)
ikm  = shared_secret
info = b"oppolink/v2/aead" || byte-max(my_pubkey, peer_pubkey)

HKDF-SHA256(salt, ikm, info, 47) =
  [chacha_key:32 | nonce_prefix:12 | sas_bytes:3]

per-frame nonce = nonce_prefix XOR (seq big-endian, right-aligned)
SAS = u24(sas_bytes) % 1_000_000  →  6 decimal digits
```

## Roadmap

The full sprint plan + detailed retros are in
[docs/TASK_LIST.md](docs/TASK_LIST.md). Top-level status:

| Sprint | Scope | Status |
| --- | --- | --- |
| **1 — Foundations** | Skeleton, BLE discovery, GATT handshake, L2CAP echo | ✅ all 4 shipped |
| **2 — Audio MVP** | One-way audio, full-duplex, AEC validation | ✅ D5+D6 shipped · ⏸ D7 hardware-pending |
| **3 — ColorOS hardening** | Jitter buffer, foreground service, battery wizard, reconnect | ✅ all 4 shipped |
| **4 — Release** | Push-to-talk, encryption (wire v2), battery profile, signed release | ✅ D12+D13+D15 shipped · ⏸ D14 hardware-pending |

## Quality bars

| Metric | Target | Current |
| --- | --- | --- |
| Mouth-to-ear latency p50 | < 100 ms | pending hardware |
| Mouth-to-ear latency p95 | < 150 ms | pending hardware |
| MOS (PESQ, quiet room, 5 m) | ≥ 4.0 | pending hardware |
| Battery drain during active call | < 5 %/hour on Reno 11 | pending hardware |
| Cold start tap-to-audio | < 3 s | pending hardware |
| Crash-free sessions | > 99.5 % | pending field data |

## Reference device matrix

| Tier | Devices | Status |
| --- | --- | --- |
| **1 (must work flawlessly)** | Oppo Reno 11, Reno 12, Find X7, Find N3 | pending |
| **2 (must work)** | A78, A98, K12 | pending |
| **3 (best-effort)** | Generic Android 10+ on Snapdragon / MediaTek / Exynos | best-effort |

## Permissions

```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
                 android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

## Non-goals

- **No video.** BLE bandwidth prohibits it.
- **No multi-party calls in v1.** Point-to-point only.
- **No Wi-Fi Direct fallback.** Purity of "BLE-only" is the thesis.
- **No iOS in v1.** CoreBluetooth's L2CAP CoC API exists but
  cross-platform handshake adds scope.
- **No cellular / Wi-Fi / internet dependency, ever.** Offline by design.

## Contributing

OppoLink is AGPL-3.0. Pull requests welcome on
[wienerlabs/oppolink](https://github.com/wienerlabs/oppolink). Read
[CLAUDE.md](CLAUDE.md) for the project's non-negotiables (no-allocations
audio hot path, no telemetry, trademark hygiene) before opening a PR.

## License

[AGPL-3.0](LICENSE) — copyleft. If you ship a modified version, the source
must be available to your users.

## Trademark

Not affiliated with, endorsed by, or sponsored by **Guangdong OPPO Mobile
Telecommunications Corp., Ltd.** "Oppo" and "ColorOS" are trademarks of
their respective owner. See [DISCLAIMER.md](DISCLAIMER.md).
