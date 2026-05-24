# OppoLink Task List

Persistent roadmap for any Claude Code session picking the project up. Holds
**what to do next**, **what is already locked**, **what we deliberately said
no to**, and **open questions** that have to be answered before v1. Update
this file at the end of every deliverable.

Last updated: 2026-05-24 after **Sprint 4 closed** (D12 + D13 + D15; D14 hardware-pending; CI verify deferred — wienerlabs org Actions billing). **v0.1.0 build-ready.**

---

## Status snapshot

| # | Deliverable | Commit | CI | Notes |
| --- | --- | --- | --- | --- |
| D1 | Multi-module skeleton + UniFFI hello_world | `dfd7c5c` | green | scaffold only |
| D2 | BLE discovery (advertise + scan + UI) | `f41ae3c` | green | required 6 CI fixes — see "Burns" below |
| D3 | GATT handshake + L2CAP PSM exchange | `12f5622` | green first-shot | UI-driven role |
| D4 | L2CAP echo test, RTT <30 ms median | `6f7936a` | green first-shot | echo loops on dedicated Thread; real p50/p95 needs hardware |
| D5 | One-way audio MVP (capture → Opus → L2CAP → playback) | `4653086` | green (1 fix) | libopus via `opus` 0.3.1; `.cargo/config.toml` pins `CMAKE_POLICY_VERSION_MINIMUM=3.5`; AudioError `detail` not `message` |
| D6 | Full-duplex (Tx + Rx threads per side) | `879d901` | _billing-blocked_ | wienerlabs org Actions billing failed; pure code-side ship complete |
| D7 | AEC validation Reno 11 / Find X7 | _hardware-pending_ | n/a | speaker-phone howl test; no code change required |
| D8 | Adaptive jitter buffer + PLC | `3938a69` | _billing-blocked_ | `oppolink-jitter` crate + UniFFI Object; Rx → JB → Play thread split |
| D9 | Foreground service + persistent notification | `58ff599` | _billing-blocked_ | survives screen-off; "End call" notification action; mm:ss ticker |
| D10 | ColorOS battery whitelist wizard | `b615cc2` | _billing-blocked_ | reflective version detect; first-run wizard; OEM intents fall back to platform Settings |
| D11 | Reconnect on drop (5 s window) | `4b56c77` | _billing-blocked_ | client reopen loop on cached PSM; server accept loop |
| D12 | Push-to-talk mode | `b1f242f` | _billing-blocked_ | mic hardware off via AudioRecord.stop; PTT toggle + hold-to-talk button |
| D13 | Encryption (Curve25519 + ChaCha20-Poly1305) | `79037db` | _billing-blocked_ | **wire format v2 lock**; SAS code on PsmExchanged; HKDF-derived nonce prefix |
| D14 | Battery profiling (<5%/hour on Reno 11) | _hardware-pending_ | n/a | needs Tier 1 device + 30-min test call |
| D15 | Signed APK + GitHub release + F-Droid manifest | _pending — see commit row_ | _billing-blocked_ | release.yml triggered on v* tags; metadata/link.oppolink.yml; v0.1.0 ready |

---

## Sprint 1 — Foundations (Week 1)

### D4 — L2CAP echo test (shipped)

Open the L2CAP CoC socket on top of the PSM that D3 exchanges, send 1024-byte
packets back and forth, measure round-trip latency. Target p50 <30 ms on LE
2M PHY.

**What landed**
- Rust `oppolink-protocol::echo`: `EchoStats` record (sample_count + p50_ms
  + p95_ms + min_ms + max_ms), `build_echo_packet(seq)`, `parse_echo_seq`,
  `summarize_echo_samples` using nearest-rank percentile. Eight new unit
  tests; workspace at 31/31.
- Kotlin `:core-bluetooth`:
  - `L2capChannel` — blocking `send` / `receiveExact` over `BluetoothSocket`,
    `AutoCloseable`.
  - `GattServerHost.acceptL2cap()` — blocking accept on the listening L2CAP
    server socket; the server echo loop reads + mirrors 1024-byte packets
    until the client closes.
  - `GattClient.openL2capSocket(peer, psm)` — client side `createInsecureL2capChannel`
    + blocking `connect()`.
  - `GattClient.fetchHandshake` now snapshots negotiated PHY via
    `BluetoothGatt.readPhy()` and returns `GattHandshakeResult`.
  - `PeerConnector.runEchoTest(peer, samples=10)` drives the round-trip
    loop on `OppoLinkEchoClient` thread (`Thread.MAX_PRIORITY`), pushes
    `EchoInProgress` / `EchoCompleted` onto the state flow.
  - `ConnectionState` adds `EchoInProgress` and `EchoCompleted`
    (carries `stats` + `negotiatedPhy`).
- Kotlin `:app`: `ConnectionScreen` gains "Run echo" button after
  `PsmExchanged`, `EchoResultCard` with p50 / p95 / min / max + LE 2M / 1M
  / Coded chip, `LinearProgressIndicator` while running. ViewModel
  `runEcho(peer)` swallows exceptions — `PeerConnector` already publishes
  the `Failed` state.
- Docs: `PROTOCOL.md` "L2CAP echo packet" section locked,
  `core-bluetooth/README.md` D4 done section.

**Sprint 1 close-out check (pending real-hardware run)**
After two Tier 1 devices (Reno 11 / Find X7) are paired:
1. Install the debug APK on both.
2. Tap each other (each side hosts a passive GATT server already).
3. Press "Run echo" on the client side; record p50 / p95 / PHY in
   `COLOROS_COMPAT.md`.
4. If p50 > 30 ms on LE 2M, log it as a Sprint 2 risk before starting D5.

---

## Sprint 2 — Audio MVP (Week 2)

### D5 — One-way audio (shipped)

What landed:
- Rust `oppolink-codec`: `VoipEncoder` / `VoipDecoder` over the
  `opus = "0.3.1"` crate. 16 kHz mono, 20 ms frame, 24 kbps CBR,
  complexity 5, FEC on, DTX off. Pre-allocated scratch buffers — the
  hot path encoder/decoder calls never allocate after construction. Six
  unit tests covering frame header roundtrip, silence/sine roundtrips,
  PLC, and PCM-length guard. Workspace at 34/34.
- Rust `oppolink-protocol::audio`: UniFFI Object wrappers around the
  codec types, `AudioFrameHeader` record, `build_audio_frame` /
  `parse_audio_frame`, constants accessors. `AudioError` re-shaped from
  `CodecError` for UniFFI serializability.
- `rust/.cargo/config.toml`: `CMAKE_POLICY_VERSION_MINIMUM = "3.5"`
  because `audiopus_sys`' vendored libopus has `cmake_minimum_required(2.x)`
  and CMake 4.x refuses to honor it otherwise.
- Kotlin `:core-audio`: `AudioCapture` (VOICE_COMMUNICATION,
  16 kHz / mono / 16-bit, AEC/NS/AGC effects with `isAvailable()`
  guards), `AudioPlayback` (USAGE_VOICE_COMMUNICATION,
  PERFORMANCE_MODE_LOW_LATENCY, MODE_STREAM).
- Kotlin `:core-bluetooth`: `PeerConnector.runCall(peer, durationMs)`
  on `OppoLinkCallClient` thread with `Process.THREAD_PRIORITY_URGENT_AUDIO`;
  capture → encode → length-prefix → send loop. Server `OppoLinkAccept`
  thread now decodes + plays instead of mirroring bytes. The D4 echo
  path is retired in the UI but the Rust helpers remain for ad-hoc
  latency probing.
- Kotlin `:app`: `ConnectionScreen` swaps "Run echo" for "Start one-way
  call"; new `InCallCard` (frames sent/received + PHY chip) and
  `CallEndedCard`. `ConnectionViewModel.startCall(peer)`.
- Docs: `PROTOCOL.md` "Audio frame" section locked; v1 ships
  **without AEAD** — Sprint 4 D13 reshapes the frame to wrap
  ChaCha20-Poly1305 around the Opus payload.

Hardware testing: emulator has no L2CAP CoC; two Reno-class devices
needed to validate end-to-end audio + AEC.

### D6 — Full-duplex (shipped)

What landed:
- `PeerConnector` gained a `runDuplexCall(socket, role, peer, psm, phy, durationMs)`
  shared helper. Owns `AudioCapture` + `AudioPlayback` + `VoipEncoder` +
  `VoipDecoder` lifecycle and spawns two threads — `OppoLinkCallTx`
  (capture → Opus → L2CAP write) and `OppoLinkCallRx` (L2CAP read →
  Opus → AudioTrack), both at `Process.THREAD_PRIORITY_URGENT_AUDIO`.
- Client side: `runCall(peer, durationMs)` opens the socket then hands
  it to `runDuplexCall`. Tx exits when the duration elapses, drives the
  shutdown sequence (close socket → wait Rx briefly → tear down).
- Server side: passive `OppoLinkAccept` thread now hands the accepted
  socket to `runDuplexCall(role=SERVER, durationMs=null)`. Runs until
  the client closes — Rx exits on EOF and the cleanup path fires.
- `L2capChannel` docs the **one-sender / one-receiver** invariant:
  `BluetoothSocket.inputStream` and `outputStream` are independent OS
  handles so duplex needs no wrapper lock.
- `PROTOCOL.md` audio-frame section calls out the symmetric usage and
  per-direction `seq` / `ts` counters.
- UI: button copy "Start full-duplex call" and `InCallCard` flavor text
  now reads "Capture ⇄ Opus ⇄ L2CAP ⇄ playback on both ends".

Open follow-ups (Sprint 3):
- Systrace 60-second call should be GC-free on the 20 ms tick. The
  UniFFI `List<Short>` → `ShortArray` copy in Rx is a known allocation;
  D8 (jitter buffer) and a custom UniFFI type will eliminate it.
- Priority inversion risk: capture and playback both run at
  URGENT_AUDIO; Tx/Rx network threads run at JVM `Thread.MAX_PRIORITY`.
  Linux scheduling already favors the OS-managed audio path, but
  validate with `systrace` once we have a Tier 1 device.

### D7 — AEC validation on Reno 11 / Find X7
- Speaker-phone test in a small quiet room, mic 0.5 m / 1 m / 2 m.
- Tolerable howling threshold: no echo coupling at 0.5 m at 70% volume.
- Failure mode: fall back to mic + earpiece (no speaker) and surface a
  banner in the UI explaining the constraint.

---

## Sprint 3 — ColorOS Hardening (Week 3)

### D8 — Jitter buffer + PLC (shipped)

What landed:
- `rust/oppolink-jitter` ships `JitterBuffer` with `BTreeMap<u16, Vec<u8>>`
  ordered storage, signed-16-bit `seq` delta arithmetic for
  wrap-around-safe ordering, prewarm gate at `target_depth`, and an
  adaptive controller that grows `target_depth` (up to `max_depth`) on
  PLC and shrinks after `CLEAN_SHRINK_THRESHOLD = 50` consecutive clean
  pops. Nine unit tests covering empty / prewarm / out-of-order /
  duplicate / late / PLC trigger / adaptive grow / wrap-around / delta.
- `rust/oppolink-protocol::jitter` wraps it in a UniFFI Object with
  `Mutex` interior mutability. `JitterPushResult` enum exposes
  `Accepted` / `LateArrival` / `Duplicate`; `JitterPopResult` is a
  sealed-class-style enum with `Packet { opus_packet }` / `Plc` /
  `Empty` variants.
- Kotlin `:core-bluetooth` `runDuplexCall` now spawns a third
  `OppoLinkCallPlay` thread (`URGENT_AUDIO`) that ticks against the
  jitter buffer; the Rx thread is now strictly `receive → push`. PLC
  decoding uses `decodePlc()` from the existing VoipDecoder UniFFI
  Object.
- Shutdown sequence: Tx exit → close socket → wait Rx grace → wait
  Play grace. The Play thread drains the buffer (returning Plc or
  Packet) until `running` flips and the loop exits.

Open follow-ups:
- Synthetic loss test (0/1/3/5%) with PESQ MOS-LQO needs real hardware.
  Track in Sprint 4 D14 (battery profiling) since both need Tier 1
  devices.
- The 1 ms `Thread.sleep` in the Play prewarm path is the only
  remaining hot-path sleep; once we ship a `BlockingQueue`-style
  primitive in Rust the prewarm path can park instead.

### D9 — Foreground service (shipped, `:app` side)

What landed:
- `link.oppolink.service.CallForegroundService` (`@AndroidEntryPoint`)
  with notification channel `oppolink_calls` (IMPORTANCE_LOW). Ongoing
  notification "OppoLink — call active mm:ss" updated every second by
  a ticker coroutine; carries `CATEGORY_CALL` + "End call" action
  PendingIntent.
- `foregroundServiceType="microphone"` in the Manifest (Android 14+
  requirement).
- `PeerConnector.runCall` signature became `durationMs: Long? = null` —
  the service passes `null` for indefinite duration. The legacy 10 s
  test call constant is renamed `SHORT_TEST_CALL_DURATION_MS`.
- `ConnectionViewModel.startCall()` no longer drives the pipeline
  itself; it fires the service intent. The service reads the current
  peer from `connector.state` (`PsmExchanged` / `InCall` / `CallEnded`)
  so we don't have to parcel `Peer` through an `Intent`. `cancel()` and
  the notification both send `ACTION_STOP`.
- `ConnectionScreen` `Footer` now shows "End call" while `InCall` and
  hides the redundant "Cancel" button — the call is the cancel.
- `ConnectionViewModel.onCleared()` deliberately does NOT cancel — the
  call must survive activity recreation.

Open follow-ups:
- The ticker uses `delay(1_000)` which on ColorOS will be coalesced by
  Doze on long calls; if minutes start drifting we can switch to an
  `AlarmManager` setExactAndAllowWhileIdle once Sprint 3 D11 lands.
- Notification permission (API 33+) — the user can deny it; the call
  still runs but the foreground status indicator goes through the
  system "ongoing call" path instead of the custom notification.

### D10 — ColorOS battery whitelist wizard (shipped)

What landed:
- `:coloros-compat/ColorOsSettings`:
  - `isColorOs()` heuristic via `Build.MANUFACTURER` + reflective
    `SystemProperties.get("ro.build.version.opporom")` (with `…oplusrom`
    fallback for OnePlus / Realme).
  - `colorOsVersion()` display string for the wizard chip.
  - `intentFor(context, capability)` returns the first candidate Intent
    the PackageManager actually resolves. Component names cover ColorOS
    13 → 15 plus legacy `com.oppo.safe`. Falls back to
    `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` / generic
    application-details on no match.
- `:app/.../setup`:
  - `SetupPreferences` SharedPreferences wrapper, single key
    `coloros_setup_complete`, Hilt `@Singleton`.
  - `SetupViewModel.openCapability` wraps `startActivity` in
    `try/catch (ActivityNotFoundException | SecurityException)` — OEM
    intent rot is the norm, not the exception.
  - `ColorOsSetupScreen` (Compose) — four `CapabilityCard`s + "Skip" /
    "All set" footer. Skipping and completing both write the same
    "wizard done" flag; the wizard never re-appears.
- `MainScreen` flow: `PermissionGate → setup gate → DiscoveryScreen /
  ConnectionScreen`. Stock Android short-circuits the gate via
  `isColorOs()`.

Open follow-ups:
- Per-version screenshot guidance (ColorOS 13 / 14 / 15) — the spec
  asked for it but the assets need to come from real device captures.
  Track separately once we have Tier 1 devices in hand.
- Settings entry-point to re-open the wizard from inside the app
  (currently one-shot). Add when there's a settings screen for the
  v1.x release polish pass.

### D11 — Reconnect on drop (shipped)

What landed:
- `ConnectionState.Reconnecting(role, peer, psm, attempt)` carries the
  attempt counter so the UI can render "Reconnecting (attempt 3)".
- `PeerConnector` split into outer reconnect loop + inner
  `runDuplexSession`. `SessionEndReason { Normal, SocketLost }` flows
  back via an `AtomicReference` written from Tx / Rx on `IOException`.
- Client `attemptReconnect(peer, psm)` polls `openL2capSocket` every
  500 ms up to the 5 s deadline. On success the outer loop reuses the
  same audio resources and re-spawns the per-session encoder / decoder
  / jitter buffer. On timeout we push `Failed` with a precise message.
- Server `OppoLinkAccept` thread became an `accept` loop — when the
  client tears the socket down, the server's `runDuplexCall` returns
  on `SocketLost`, the outer thread loops back to `host.acceptL2cap()`,
  and the next client reconnect binds against the same listener.
- `seq` / `ts` resume from `framesSent.get()` so the peer's jitter
  buffer sees the resumed stream as future frames (signed-delta arith
  accepts wraparound and gaps).
- UI: ConnectionScreen describes the new state inline; Footer keeps
  the "End call" button live during Reconnecting so the user can give
  up early.

Open follow-ups:
- Telemetry: count reconnect attempts per session so we can tune the
  500 ms backoff if Tier 1 hardware shows we're hammering the radio.
- Listening server PSM can sometimes be rejected by ColorOS after a
  forced disconnect; if that happens we may need to reallocate the
  server socket on stop+restart. Watch out during D9 + D11 hardware
  testing.

---

## Sprint 4 — Release (Week 4)

### D12 — Push-to-talk mode (shipped)

What landed:
- `AudioCapture.setMuted(boolean)` flips the underlying `AudioRecord`
  between `startRecording()` and `stop()`. The mic hardware actually
  powers down — real battery + ALSA savings — and the AEC / NS / AGC
  effects survive the cycle (they hang off the session id which
  doesn't change).
- `PeerConnector.setMuted(boolean)` updates an instance-level
  `AtomicBoolean` + propagates to whichever `AudioCapture` is in
  scope (held via `AtomicReference` set at `runDuplexCall` start /
  cleared at finally).
- Tx loop reads `muted` once per tick. Muted ticks emit
  `InCall(muted = true)`, sleep 20 ms, skip encode + send. The peer's
  jitter buffer PLCs through the silent gap; unmute resumes inside
  one tick.
- `ConnectionState.InCall.muted: Boolean` (defaults false).
- UI: `ConnectionScreen` keeps a local `pttMode: Boolean`. The Switch
  inside `InCallCard` toggles it; a `LaunchedEffect` mirrors it onto
  `viewModel.setMuted`. When PTT mode is on, a tall `HoldToTalkButton`
  uses `pointerInput { detectTapGestures(onPress = …, awaitRelease) }`
  to setMuted(false) on press + setMuted(true) on release.
  `InCallCard` also surfaces a red "Muted" assist chip while the wire
  is silent.

Open follow-ups:
- Hardware-side: confirm the Reno 11 actually drops the mic LED while
  muted — some OEMs only nominally stop the radio.
- Symmetric PTT: today only the local mic can be muted. We could
  surface a "remote muted" indicator if the peer's stream stalls for
  >N ms (the jitter buffer's `late_arrival_count` could feed this).

### D13 — Encryption (shipped, wire format v2)

What landed:
- Rust deps: `x25519-dalek 2`, `chacha20poly1305 0.10`, `hkdf 0.12`,
  `sha2 0.10`, `rand_core 0.6` (CSPRNG via `OsRng`). All RustCrypto,
  pure Rust, Android-NDK friendly.
- `rust/oppolink-protocol/src/crypto.rs`: `EphemeralKeyPair` UniFFI
  Object (CSPRNG-generated, `public_key`, `derive_session`). ECDH
  result fed into HKDF-SHA256 with `salt = min(my_pub, peer_pub)`,
  `info = b"oppolink/v2/aead" || max(my_pub, peer_pub)`. Output: 32-byte
  ChaCha key + 12-byte nonce prefix + 3-byte SAS material.
- `rust/oppolink-protocol/src/session.rs`: `SessionKey` UniFFI Object
  holding `ChaCha20Poly1305` cipher + nonce prefix + cached SAS.
  `encrypt_frame(seq, ts, opus)` builds wire bytes
  `[seq | ts | nonce | ciphertext | tag]`. `decrypt_frame(bytes)` parses
  + AEAD-verifies + returns `DecryptedAudioFrame { header, opus_packet }`.
  AAD = the 4-byte header so a flipped `seq` / `ts` invalidates the tag.
- `PROTOCOL_VERSION` bumped from `0x01` to `0x02`. `HANDSHAKE_VERSION`
  matched. Peers receiving an unknown version drop the GATT connection.
- 9 new tests (4 crypto + 5 session: roundtrip, tamper, truncation,
  wrong-session, nonce XOR). Workspace at 44/44.
- `GattServerHost.start` builds an `EphemeralKeyPair` and puts its
  pubkey into the handshake characteristic (was zero-padded in v1).
  `deriveServerSession(socket)` reads the first 32 bytes of the L2CAP
  payload (client pubkey), runs ECDH, returns the matching
  `SessionKey`. Reconnect-safe — ECDH is deterministic.
- `GattClient.fetchHandshake` generates its own ephemeral keypair,
  ECDHs against the server pubkey from the handshake, returns
  `GattHandshakeResult { handshake, negotiatedPhy, sessionKey, clientPubkey }`.
- `GattClient.openL2capSocket(peer, psm, clientPubkey)` writes the
  32-byte client pubkey as the L2CAP socket's first payload before any
  audio frame.
- `PeerConnector.activeSession: { sessionKey, clientPubkey }` survives
  across reconnects. Tx loop uses `sessionKey.encryptFrame`; Rx uses
  `sessionKey.decryptFrame` and drops AEAD failures (jitter buffer
  PLCs the gap).
- `ConnectionState.PsmExchanged.sasCode: UInt` — 6-digit decimal SAS;
  UI shows "SAS 482301 (read aloud to verify)". InCallCard adds an
  "Encrypted" assist chip.

Open follow-ups (Sprint 4 polish):
- SAS verification UX: emoji rendering instead of digits + explicit
  "matches / doesn't match" confirm tap. Today the user just reads
  digits; Signal-style 6-emoji is friendlier.
- Three-strike AEAD-failure disconnect policy — today a stream of
  garbage just floods the log without terminating the session.
- Pubkey commitment in the GATT advertisement (hashed pubkey) so a
  MITM can't substitute pubkeys without the SAS hash changing
  pre-connection.

### D14 — Battery profiling (hardware-pending)

Spec: Reno 11 active-call drain budget <5 %/hour, measured by polling
`BatteryManager` every 60 s during a 30-min test call. No code change
needed — the existing `CallForegroundService` keeps the call alive
through screen-off, which is the only state where this is meaningful.

Mitigations queued for the day we miss the budget:
- Drop Opus complexity from 5 to 3 (`oppolink-codec::OPUS_COMPLEXITY`).
- Drop BLE PHY back to LE 1M (cheaper radio, halves throughput which
  is still 4× our 24 kbps budget).
- Reduce TX power (`AdvertiseSettings.ADVERTISE_TX_POWER_LOW`).

### D15 — Signed APK + GitHub release + F-Droid manifest (shipped)

What landed:
- `signing.properties.example` at repo root + `.gitignore` updated to
  exclude `signing.properties` + `release.jks`. Documents the
  one-time `keytool` invocation that produces a 4096-bit RSA keystore
  with 100-year validity.
- `android/app/build.gradle.kts` gains a `signingConfigs.release` block
  that reads `signing.properties` for local dev or env vars for CI.
  `buildTypes.release` conditionally attaches the release signing
  config when secrets are present, else falls back to debug signing so
  unsigned smoke builds still produce an APK.
- `.github/workflows/release.yml` — tag-triggered (`v*`) workflow that
  decodes `RELEASE_KEYSTORE_B64` to disk, runs `./gradlew
  :app:assembleRelease`, uploads the signed APK as a build artifact,
  then `gh release create`s a GitHub Release. Release notes are
  pulled from the matching `## vX.Y.Z` section of `CHANGELOG.md`.
- `metadata/link.oppolink.yml` — canonical F-Droid build metadata.
  Documents Categories, License, Build steps (Rust + cargo-ndk init
  before Gradle), CurrentVersion / CurrentVersionCode. The actual
  fdroiddata MR is a manual follow-up.
- `RELEASE.md` — checklist + secret list + Play Store deferral note.
- `CHANGELOG.md` seeded with the v0.1.0 entry summarising every
  shipped Sprint 1–4 deliverable.
- `android/app/build.gradle.kts` versionName bumped from `0.1.0-dev`
  to `0.1.0`; versionCode stays at `1` for the first real RC.

Org-level GitHub secrets the workflow expects on `wienerlabs`:

| Name | Contents |
| --- | --- |
| `RELEASE_KEYSTORE_B64` | `base64 oppolink-release.jks` |
| `RELEASE_STORE_PASSWORD` | keystore password |
| `RELEASE_KEY_ALIAS` | usually `oppolink` |
| `RELEASE_KEY_PASSWORD` | key password |

Once the Actions billing is restored, tagging `v0.1.0` produces a
signed APK release.

---

## Locked decisions (immutable without a `PROTOCOL_VERSION` bump)

| Surface | Value | Source of truth |
| --- | --- | --- |
| Service UUID | `4F50504C-0001-4F50-504C-000000000001` | `rust/oppolink-protocol/src/lib.rs::SERVICE_UUID` |
| Handshake characteristic UUID | `4F50504C-0001-4F50-504C-000000000002` | `handshake_char_uuid()` |
| Manufacturer ID | `0xFFFF` (test) | `manufacturer::MANUFACTURER_ID` |
| Manuf-data magic | `"OP"` (2 bytes) | `manufacturer::WIRE_MAGIC` |
| Handshake magic | `"OPL1"` (4 bytes) | `handshake::HANDSHAKE_MAGIC` |
| Protocol version | `0x01` | `manufacturer::PROTOCOL_VERSION` + `handshake::HANDSHAKE_VERSION` |
| Max nickname | 16 byte UTF-8, truncated at char boundary | `MAX_NICKNAME_BYTES` |
| Capabilities bitmap | bit 0 = PCM_16K_MONO, 1 = OPUS, 2 = AEAD | `manufacturer::Capabilities` |
| Pubkey length | 32 byte (Curve25519; zeros until D13) | `handshake::PUBKEY_LEN` |
| PSM direction | server emits its allocated PSM, client emits 0 | `HandshakeMessage::psm` |
| Role assignment | UI-driven; tapper = client | docs/PROTOCOL.md |
| Android namespace | `link.oppolink` (debug suffix `.debug`) | `android/app/build.gradle.kts` |
| Min / target / compile SDK | 29 / 35 / 35 | `android/gradle/libs.versions.toml` |
| Build tool versions | Kotlin 2.1.0, AGP 8.7.3, Gradle 8.10.2, Compose BOM 2024.12.01, Hilt 2.52, JNA 5.15.0@aar | `libs.versions.toml` |
| UniFFI | 0.28 proc-macros, **no UDL files** | `rust/oppolink-protocol/Cargo.toml` |
| Rust release profile | `strip = OFF` (Linux ELF strip kills UniFFI metadata) | `rust/Cargo.toml` |
| Generated Kotlin path | `:core-protocol/src/main/kotlin/uniffi/<crate>/<crate>.kt` | bindgen `--out-dir` |

---

## Things we deliberately said no to

- **No `rust-android-gradle` plugin.** Two `Exec` tasks (`hostBuild`,
  `cargoNdkBuild`, `uniffiBindgen`) give us explicit input/output, cache
  keys we control, and a debuggable command line. Reconsider if the plugin
  ever gets first-party support from JetBrains or Google.
- **No coroutines on the audio hot path.** The 20 ms tick budget can't
  absorb dispatcher scheduling jitter. Coroutines are fine for GATT,
  control plane, and UI.
- **No mock for the audio path in tests.** Audio correctness only matters
  on real hardware; a passing mock test would mislead more than it would
  protect. Rust codec / framing / jitter logic gets unit tests in isolation.
- **No BD_ADDR-based role assignment in v1.** Android 8+ returns
  `02:00:00:00:00:00` for `BluetoothAdapter.getAddress()`. We considered
  embedding a random session ID in the manufacturer data, but it adds wire
  surface that's only useful for one corner case (both peers simultaneously
  tap each other within 100 ms). Sprint 4 D13 handles it via the handshake.
- **No Navigation library.** Three screens (PermissionGate, Discovery,
  Connection) are small enough that a hand-rolled `selectedPeer: Peer?`
  state machine reads cleaner than `androidx.navigation`.
- **No iOS port in v1.** CoreBluetooth's L2CAP CoC API exists, but the
  cross-platform handshake QA budget is too large for v1. Rust core is
  written portably so the door stays open.

---

## Burns (cross-link: `CLAUDE.md` for terse summaries, here for context)

The longest debugging session of the project — Sprint 1 D2 took six CI fix
commits before going green. Future-Claude: if you hit any of the symptoms
below, jump straight to the cited fix.

| Symptom | Root cause | Fix |
| --- | --- | --- |
| `Unresolved reference 'uniffi'` in `:core-bluetooth` Kotlin compile, but `:core-protocol` task graph looks fine | UniFFI bindgen runs but writes zero bytes. Library was built with `[profile.release] strip = "symbols"`, which on **Linux ELF** strips the UniFFI metadata sections together with the symbol table. macOS Mach-O is unaffected. | `rust/Cargo.toml`: never set `strip` on the release profile. cargo-ndk strips the Android-shipped .so itself. |
| `:core-protocol:compileDebugKotlin NO-SOURCE` despite `kotlin.srcDir(layout.buildDirectory.dir("generated/uniffi"))` | AGP + KGP source-set surface is unreliable for generated Kotlin in this combo. Neither the legacy DSL nor `androidComponents.onVariants.sources.kotlin.addStaticSourceDirectory` reach `compileXxxKotlin`. | Make `uniffiBindgen` write straight into `src/main/kotlin/uniffi/<crate>/`. AGP's default Kotlin source-set finds it with no plumbing. `.gitkeep` keeps the directory alive; `.gitignore` excludes the `uniffi/` subtree. |
| `Configuration cache problems: cannot serialize Gradle script object references` on `:core-protocol:uniffiBindgen` | `doFirst { uniffiOutDir.asFile.mkdirs() }` captured a script-level `Directory` reference. | Drop the `doFirst`. UniFFI creates the `uniffi/<crate>/` subtree itself; AGP creates `src/main/kotlin/` as part of the source set. |
| `Only safe (?.) or non-null asserted (!!.) calls are allowed on a nullable receiver` inside a `runCatching { … }` block, after a null-guard | Kotlin smart-cast doesn't carry across lambda boundaries. | Pin the receiver to a local `val` before the lambda. |
| `Variable 'scope' must be initialized` on a property that derives from another via `.stateIn(scope, …)` | Class property initialization order — `peers` referenced `scope` before `scope` was declared. | Declare `scope` first; let derived flows reference it. |
| UniFFI `--library` mode fails on cross-arch input | Linux x86_64 bindgen binary cannot `dlopen` an Android arm64 `.so`. | Add a `hostBuild` task that builds the host-triple library; point bindgen at it. `cargoNdkBuild` separately produces the Android `.so`. Share `target/`, serialize via `mustRunAfter`. |
| Kotlin compile races bindgen (NO-SOURCE) despite `preBuild dependsOn uniffiBindgen` | `compile*Kotlin` is not parented under `preBuild`. | `afterEvaluate { tasks.matching { it.name.startsWith("compile") && it.name.endsWith("Kotlin") }.configureEach { dependsOn(uniffiBindgen) } }`. |

---

## Open questions to answer before public v1

1. **Trademark counsel** on the name "OppoLink". Reading the disclaimer
   carefully suggests we're OK, but a 30-min legal sanity check before
   the F-Droid listing is cheap insurance. Fallback names: _Whisperlink_,
   _Lattice_, _Rideau_.
2. **Bluetooth SIG manufacturer ID registration.** `0xFFFF` is the public
   test ID; sufficient for development, almost certainly required to be
   replaced for App Store distribution. Cost ~USD 8 000 for non-members
   last I checked.
3. **ColorOS deep-link intent table.** The entries in `COLOROS_COMPAT.md`
   are unverified. Sprint 3 D10 owns the verification but the intents
   may have shifted under ColorOS 15.
4. **Privacy policy URL.** Required for Play Store. F-Droid does not
   strictly require it but it's good form. Probably a one-page static
   document hosted on the GitHub Pages branch.
5. **Maintainer-of-record + GPG signing key** for F-Droid.
6. **`.well-known` for Universal Links** (Sprint 5+ if we add `oppolink://`
   peer-invite deep links).

---

## How to use this file

- **Starting a new Claude Code session?** Read this file top-to-bottom,
  then the status snapshot tells you which deliverable is in flight.
- **About to write code?** Cross-check Locked Decisions before introducing
  any new wire-format constant.
- **Hit a weird CI failure?** Skim Burns before opening a fresh
  investigation. We've already lost a lot of hours to those.
- **Finishing a deliverable?** Update the status snapshot row, move the
  detailed plan into the post-mortem section (or delete it if it landed
  as-described), and append any new burns / open questions.
