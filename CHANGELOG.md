# Changelog

All notable changes to OppoLink go here. The format follows
[Keep a Changelog](https://keepachangelog.com/) and the project adheres to
[Semantic Versioning](https://semver.org/) (see `RELEASE.md`).

## v0.1.0 - Sprint 1–4 ship (UNRELEASED, build-ready)

First end-to-end release candidate. Two ColorOS / Android 10+ phones
can pair, hold an indefinite full-duplex voice call, ride out a 5-second
L2CAP socket drop, and the audio path is AEAD-encrypted with
Curve25519 + ChaCha20-Poly1305.

### Added

- **Discovery (Sprint 1 D2)** - BLE GATT advertising under service UUID
  `4F50504C-0001-4F50-504C-000000000001`; manufacturer-data payload
  carries a UTF-8 nickname (≤16 bytes) + capability bitmap.
- **Handshake (Sprint 1 D3)** - GATT characteristic exchanges PSM,
  Curve25519 ephemeral pubkey, role tag, nickname. Tap-to-connect UI
  picks the client role; tapped peer stays passive.
- **L2CAP echo test (Sprint 1 D4)** - 10-round-trip latency probe with
  `EchoStats { p50_ms, p95_ms, min_ms, max_ms }` summary. Used as the
  Sprint 1 close-out check.
- **One-way audio (Sprint 2 D5)** - `AudioRecord` (VOICE_COMMUNICATION,
  16 kHz mono, 20 ms frames) → Opus encode (24 kbps, complexity 5,
  FEC on) → length-prefixed L2CAP frame.
- **Full-duplex (Sprint 2 D6)** - symmetric Tx + Rx threads on both
  peers, sharing one `BluetoothSocket`. Independent input/output
  streams; no wrapper lock.
- **Adaptive jitter buffer (Sprint 3 D8)** - `rust/oppolink-jitter` with
  signed-16-bit-delta `seq` ordering, dedup, late-drop, PLC trigger,
  adaptive depth between 2–5 frames.
- **Foreground service (Sprint 3 D9)** - `CallForegroundService` holds
  the call across screen-off via `foregroundServiceType="microphone"`.
  Notification ticker shows `mm:ss` elapsed time; "End call" action
  posts back to the service.
- **ColorOS battery wizard (Sprint 3 D10)** - first-run flow deep-links
  into Startup Manager, Battery Optimization, Floating Window, and
  Auto-Launch settings. Reflective version detect via
  `ro.build.version.opporom`.
- **Reconnect on drop (Sprint 3 D11)** - client polls `openL2capSocket`
  every 500 ms for up to 5 s; server `accept` loop rebinds; `seq`
  continues from `framesSent.get()` so the peer's jitter buffer treats
  the gap as PLC.
- **Push-to-talk (Sprint 4 D12)** - `AudioCapture.setMuted` actually
  stops the mic hardware. UI Switch + tall "Hold to talk" button.
- **Encryption (Sprint 4 D13)** - Curve25519 ECDH + HKDF-SHA256 →
  ChaCha20-Poly1305 per-frame AEAD. AAD binds `[seq | ts]` so an
  attacker can't tamper with frame ordering. 6-digit decimal SAS on
  the PsmExchanged screen for OOB MITM verification.
- **Release infrastructure (Sprint 4 D15)** - `signingConfigs.release`
  reads `signing.properties` (local) or env vars (CI). Tag-triggered
  CI workflow at `.github/workflows/release.yml` builds + signs +
  publishes a GitHub Release. F-Droid metadata at
  `metadata/link.oppolink.yml`.

### Wire format

`PROTOCOL_VERSION = 0x02`. Peers running v1 (cleartext audio) are
incompatible - the version byte hard-rejects on handshake.

### Known limitations

- **Hardware tests pending** (Sprint 2 D7, Sprint 4 D14): AEC howling
  validation + battery drain target (<5 %/hr on Reno 11) need two Tier
  1 devices in hand. Numbers will be back-filled into this changelog
  once captured.
- **CI verification gap**: the `wienerlabs/oppolink` org's GitHub
  Actions billing has been failing since 2026-05-23 - code-side ships
  continued but the Android assembleDebug CI job has been skipped
  since then. Locally verified at every step.
- **MITM SAS UX confirmation tap shipped post-D15**: the SAS card now
  carries "Matches" and "Doesn't match" buttons; the "Start
  full-duplex call" button stays disabled until the user taps
  "Matches". "Doesn't match" cancels the connection and returns to
  Discovery. Confirmation state is scoped to the current peer.

### Sprint 4 polish (post-D15)

- **Logo + redesigned README** with badges, quick start, and a
  centered hero image (`docs/assets/oppolink-logo.webp`).
- **Signal-style SAS emoji**: `SessionKey.sas_emoji() -> Vec<u8>`
  feeds a 64-emoji palette in the Compose UI. Decimal SAS retained
  as a fallback / log channel.
- **In-call jitter diagnostics card** rendered below the InCallCard -
  shows buffered/target depth, push/pop counts, PLC fires, late
  arrivals, duplicates. Sampled once per second by an instance-level
  coroutine.
- **Three-strike AEAD-fail disconnect**: three consecutive decrypt
  failures on a single session terminate the call with a "Suspicious
  traffic" error. Single-bit RF flips don't trigger it; sustained
  injection does. No automatic reconnect after this failure mode.
- **Settings screen** - reachable from the Discovery header. Surfaces
  app version + wire-protocol version + license; on ColorOS, lets the
  user re-open the four permission deep-links and toggle the first-run
  wizard back on (`SetupViewModel.refresh()` picks the flip up on
  return). Privacy section spells out the "no telemetry / no network /
  no accounts" stance.
- **Discovery list polish**: per-peer capability chips (PCM 16k, Opus,
  AEAD) + "Ns ago" last-seen text help spot a pre-v2 peer at a glance.
- **Em dash sweep**: project-wide `—` -> `-` replace; CLAUDE.md
  anti-corpus picks up a "no em dash anywhere" rule lifted to a
  global feedback on 2026-05-26.
- **Reconnect telemetry**: `ConnectionState.CallEnded.reconnectAttempts`
  surfaces the cumulative reconnect count on the CallEndedCard so the
  D14 hardware test can correlate radio scheduling against call
  resilience.
- **BatteryProbe diagnostic** (D14 instrumentation): samples
  `BATTERY_PROPERTY_CAPACITY` every 60 s while the foreground call
  service is alive; logs a `X% -> Y% over Zs = N%/hour` summary on
  call end. `adb logcat -s BatteryProbe` during the 30-min hardware
  test captures it.
- **Brand visual identity**: launcher adaptive icon now renders the
  OppoLink waveform (5 vertical bars) instead of the placeholder
  wordmark, matching `ic_notification` and the README hero. The
  Android 12+ `SplashScreen` API ships via
  `androidx.core:core-splashscreen` 1.0.1 so cold launches show the
  same logo on the brand background until the first Compose frame.
- **PermissionGate detail**: the waiting state lists each outstanding
  runtime permission with a one-line explanation (e.g. "Microphone -
  Capture your voice while a call is active") and a count-aware
  button. A lifecycle `ON_RESUME` observer re-checks permissions
  after a Settings round-trip so the gate self-unblocks without a
  process restart.
- **Discovery empty-state checklist**: replaces the single "Looking
  for nearby peers" line with a state-aware status line + a checklist
  card covering the three pre-conditions that cover almost every
  field failure (other phone on Discovery, both within ~10 m,
  Bluetooth toggle actually on).
- **Notification reflects call status**: the foreground service
  notification reads `\$peerNick · 02:34 · Encrypted` while the call
  is unmuted, `· Muted` while push-to-talk has the mic stopped, and
  swaps to `OppoLink - reconnecting · Attempt N` on socket drop. The
  ticker re-reads `connector.state` each second so there's no extra
  state subscription.
- **Elapsed format spills to hh:mm:ss past 60 min**: long calls
  (D14 battery test, indefinite hangouts) now render `1:30:00`
  instead of the clipped `90:00`.
- **AGPL transparency rows in Settings**: tappable "View source code"
  and "Read the license" rows in the About card hand off to the
  system browser via `Intent.ACTION_VIEW`. The OppoLink process never
  opens an internet socket; the browser is a separate process, so
  the offline-only invariant stays intact.
- **End-call confirm dialog**: the in-app End call button now opens
  a Material AlertDialog while the call is live so a fat-finger tap
  cannot drop the session key + L2CAP channel. Reconnecting state
  skips the prompt because the socket is already gone.
- **Audio meter smoothing**: the LinearProgressIndicator wraps its
  progress fraction in `animateFloatAsState(tween(120ms))` so the
  meter no longer flickers at every 20 ms audio frame boundary; the
  numeric readout next to the bar stays raw so power users can read
  instantaneous peaks.
- **BackHandler on ConnectionScreen**: the system back gesture during
  a live call now surfaces the same `EndCallConfirmDialog` the in-app
  button uses; pre-call / post-call states still pop straight back to
  Discovery. Dialog composable is shared so copy never drifts between
  the two entry points.
- **`formatElapsed` JUnit coverage**: extracted out of the Service
  into a pure `internal` function (`ElapsedFormatter.kt`) with
  boundary tests for zero, sub-second, 59:59, hour spillover, multi-
  hour, and negative-drift inputs. First unit-test entry in the
  `:app` test source set.
