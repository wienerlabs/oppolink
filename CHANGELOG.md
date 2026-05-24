# Changelog

All notable changes to OppoLink go here. The format follows
[Keep a Changelog](https://keepachangelog.com/) and the project adheres to
[Semantic Versioning](https://semver.org/) (see `RELEASE.md`).

## v0.1.0 — Sprint 1–4 ship (UNRELEASED, build-ready)

First end-to-end release candidate. Two ColorOS / Android 10+ phones
can pair, hold an indefinite full-duplex voice call, ride out a 5-second
L2CAP socket drop, and the audio path is AEAD-encrypted with
Curve25519 + ChaCha20-Poly1305.

### Added

- **Discovery (Sprint 1 D2)** — BLE GATT advertising under service UUID
  `4F50504C-0001-4F50-504C-000000000001`; manufacturer-data payload
  carries a UTF-8 nickname (≤16 bytes) + capability bitmap.
- **Handshake (Sprint 1 D3)** — GATT characteristic exchanges PSM,
  Curve25519 ephemeral pubkey, role tag, nickname. Tap-to-connect UI
  picks the client role; tapped peer stays passive.
- **L2CAP echo test (Sprint 1 D4)** — 10-round-trip latency probe with
  `EchoStats { p50_ms, p95_ms, min_ms, max_ms }` summary. Used as the
  Sprint 1 close-out check.
- **One-way audio (Sprint 2 D5)** — `AudioRecord` (VOICE_COMMUNICATION,
  16 kHz mono, 20 ms frames) → Opus encode (24 kbps, complexity 5,
  FEC on) → length-prefixed L2CAP frame.
- **Full-duplex (Sprint 2 D6)** — symmetric Tx + Rx threads on both
  peers, sharing one `BluetoothSocket`. Independent input/output
  streams; no wrapper lock.
- **Adaptive jitter buffer (Sprint 3 D8)** — `rust/oppolink-jitter` with
  signed-16-bit-delta `seq` ordering, dedup, late-drop, PLC trigger,
  adaptive depth between 2–5 frames.
- **Foreground service (Sprint 3 D9)** — `CallForegroundService` holds
  the call across screen-off via `foregroundServiceType="microphone"`.
  Notification ticker shows `mm:ss` elapsed time; "End call" action
  posts back to the service.
- **ColorOS battery wizard (Sprint 3 D10)** — first-run flow deep-links
  into Startup Manager, Battery Optimization, Floating Window, and
  Auto-Launch settings. Reflective version detect via
  `ro.build.version.opporom`.
- **Reconnect on drop (Sprint 3 D11)** — client polls `openL2capSocket`
  every 500 ms for up to 5 s; server `accept` loop rebinds; `seq`
  continues from `framesSent.get()` so the peer's jitter buffer treats
  the gap as PLC.
- **Push-to-talk (Sprint 4 D12)** — `AudioCapture.setMuted` actually
  stops the mic hardware. UI Switch + tall "Hold to talk" button.
- **Encryption (Sprint 4 D13)** — Curve25519 ECDH + HKDF-SHA256 →
  ChaCha20-Poly1305 per-frame AEAD. AAD binds `[seq | ts]` so an
  attacker can't tamper with frame ordering. 6-digit decimal SAS on
  the PsmExchanged screen for OOB MITM verification.
- **Release infrastructure (Sprint 4 D15)** — `signingConfigs.release`
  reads `signing.properties` (local) or env vars (CI). Tag-triggered
  CI workflow at `.github/workflows/release.yml` builds + signs +
  publishes a GitHub Release. F-Droid metadata at
  `metadata/link.oppolink.yml`.

### Wire format

`PROTOCOL_VERSION = 0x02`. Peers running v1 (cleartext audio) are
incompatible — the version byte hard-rejects on handshake.

### Known limitations

- **Hardware tests pending** (Sprint 2 D7, Sprint 4 D14): AEC howling
  validation + battery drain target (<5 %/hr on Reno 11) need two Tier
  1 devices in hand. Numbers will be back-filled into this changelog
  once captured.
- **CI verification gap**: the `wienerlabs/oppolink` org's GitHub
  Actions billing has been failing since 2026-05-23 — code-side ships
  continued but the Android assembleDebug CI job has been skipped
  since then. Locally verified at every step.
- **MITM SAS UX**: today the SAS is rendered as a 6-digit decimal.
  Signal-style emoji rendering + explicit "matches / doesn't match"
  confirmation is a v0.2 polish item.
