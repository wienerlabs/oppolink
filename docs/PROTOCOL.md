# Wire Protocol - v2

Status: **locked at v2** after Sprint 4 D13 (Curve25519 + ChaCha20-Poly1305).
v1 (Sprint 1–3) was cleartext audio frames + zero-padded pubkey; v2 mandates
ECDH + AEAD. Subsequent breaking changes require another `PROTOCOL_VERSION`
bump.

## Identifiers

- **Service UUID** (locked in Sprint 1 D2):
  `4F50504C-0001-4F50-504C-000000000001`. ASCII "OPPL" appears on word
  boundaries so the UUID is easy to spot in scan dumps.
- **Handshake characteristic** (Sprint 1 D3):
  `4F50504C-0001-4F50-504C-000000000002` - the same base UUID with the suffix
  bumped to `…0002`.
- **Manufacturer ID**: `0xFFFF` (Bluetooth SIG "test" range). Replaced with a
  real SIG-assigned ID before the public v1 release.
- **Wire magic**: ASCII `"OP"` (2 bytes) inside the manufacturer-specific data
  field, disambiguating OppoLink advertisements from any other app that also
  happens to use `0xFFFF`.

## Discovery - BLE advertising (Sprint 1 D2, locked)

Two packets per advertise cycle:

**Primary advertise** (≤31 byte budget) carries only the 128-bit service UUID:

```
┌───────────────────────────────────────────────┐
│ Service UUID: 4F50504C-0001-4F50-504C-…       │  (16 bytes + flags overhead)
└───────────────────────────────────────────────┘
```

**Scan response** carries the OppoLink manufacturer-specific data:

```
┌───────────────────────────────────────────────┐
│ Manuf ID: 0xFFFF  (LE, 2 bytes)               │
│ magic:    "OP"     (2 bytes)                  │
│ version:  u8       (PROTOCOL_VERSION = 0x01)  │
│ caps:     u32 LE   (capability bitmap)        │
│ nick_len: u8       (0..=16)                   │
│ nick:     [u8; nick_len]   UTF-8              │
└───────────────────────────────────────────────┘
```

Capability bitmap bits:

| Bit | Name             | Meaning                                       |
| --- | ---------------- | --------------------------------------------- |
| 0   | `PCM_16K_MONO`   | 16 kHz mono PCM capture/playback (v1: always) |
| 1   | `OPUS`           | libopus encode/decode (v1: always)            |
| 2   | `AEAD`           | ChaCha20-Poly1305 ready (Sprint 4 D13)        |
| 3..31 | reserved       | must be 0                                     |

Encode/decode is implemented in `rust/oppolink-protocol/src/manufacturer.rs`
and exposed to Kotlin via UniFFI as `encodeManufacturerData` /
`parseManufacturerData`. Kotlin must not parse the layout by hand - Rust is
the source of truth.

## Handshake (Sprint 1 D3, locked - over GATT characteristic `…0002`)

```
┌─────────────────────────────────────────────────────┐
│ magic:    "OPL1"            (4 bytes)               │
│ version:  u8                (PROTOCOL_VERSION 0x01) │
│ role:     u8                (0=server, 1=client)    │
│ pubkey:   [u8; 32]          (Curve25519; zeros in v1, filled in Sprint 4 D13) │
│ psm:      u16 big-endian    (L2CAP PSM, server-only; client sends 0) │
│ nick_len: u8                (0..=16)                │
│ nick:     [u8; nick_len]    (UTF-8, no null term)   │
└─────────────────────────────────────────────────────┘
```

- **Role assignment in v1 is UI-driven**, not BD_ADDR-driven. The user taps a
  peer in the discovery list; the tapping side takes the **client** role and
  the tapped side stays passive with its GATT server already advertising the
  OppoLink service. Modern Android refuses to expose the local BD_ADDR to
  apps, so the byte-wise comparison the early protocol drafts called for is
  not implementable.
  - The Rust `decide_role(local_bd_addr, remote_bd_addr) → Role` helper still
    exists; it returns the lower-BD_ADDR-wins role and is reserved for the
    Sprint 4 D13 ECDH handshake, where both peers exchange ephemeral pubkeys
    over an already-open L2CAP channel and can include their own identifier
    in the payload.
- **Server side** writes the handshake bytes into the read-only characteristic
  value when the GATT service is registered. A successful read takes one
  GATT round-trip.
- **PSM** is the only field that flows server → client; the client emits 0.
- `encode_handshake` / `parse_handshake` are exposed via UniFFI; Kotlin must
  not parse the layout by hand.

## L2CAP echo packet (Sprint 1 D4, locked)

The Sprint 1 close-out test that validates the round-trip latency budget
before the audio path lands. Carries no semantic payload - every packet is
1024 bytes of zero-filled body behind a 4-byte sequence prefix. The server
side simply mirrors each frame back to the sender; the client measures
`System.nanoTime()` deltas and computes nearest-rank p50 / p95 in
`rust/oppolink-protocol/src/echo.rs`.

```
┌─────────────────────────────────────────────────────┐
│ seq:     u32 big-endian      (sample index, 0-based) │
│ body:    [u8; 1020]          (zero-filled)           │
└─────────────────────────────────────────────────────┘
                              total: 1024 bytes
```

- The 1024-byte size is exposed to Kotlin via `echoPacketSize()` so a future
  protocol bump only touches Rust.
- Default sample count is 10. Result fields: `sample_count`, `p50_ms`,
  `p95_ms`, `min_ms`, `max_ms`. Empty input returns a zeroed [`EchoStats`]
  so the UI never has to branch on `Option`.
- Both sides MUST run the I/O on dedicated `Thread`s - coroutine dispatcher
  jitter is not tolerable on the 20 ms audio tick we're rehearsing for.

## Audio frame (Sprint 4 D13, locked - AEAD mandatory)

`PROTOCOL_VERSION` `0x02` mandates ChaCha20-Poly1305 on every audio
frame. The cleartext layout below is retained for archival purposes
only - peers that negotiate `version = 0x02` MUST drop any frame that
fails AEAD verification.

### v1 cleartext (archived - Sprint 2 D5 → Sprint 4 D13)

```
┌─────────────────────────────────────────────────────┐
│ len:        u16 big-endian   (length of [header | opus])  ← L2CAP delimiter
├─────────────────────────────────────────────────────┤
│ seq:        u16 big-endian   (frame index, wraps every 65 536)             │
│ ts:         u16 big-endian   (units of 20 ms; wraps every ~21 min)         │
│ opus:       variable          (libopus VoIP packet)                        │
└─────────────────────────────────────────────────────┘
```

- The leading `u16` `len` is the L2CAP-level delimiter so the receiver
  knows exactly how many bytes the next frame consumes. It is not part of
  the framed payload that Rust parses.
- The format is **symmetric**: both peers emit the same shape in both
  directions over a single L2CAP CoC socket (Sprint 2 D6 duplex). `seq`
  and `ts` counters are per-direction; a peer maintains one pair for
  outgoing frames and tracks the remote peer's pair on the incoming
  side independently.
- The receive side feeds an adaptive jitter buffer (Sprint 3 D8). The
  wire format is **unchanged** - the buffer is a pure receiver-side
  concern that handles reordering, dedup, and PLC trigger. Adaptive
  depth lives in 40–100 ms range (2–5 frames).
- **Reconnect** (Sprint 3 D11): when the L2CAP socket breaks the client
  reopens against the **same cached PSM** - the handshake is not
  re-run. The wire stream resumes with the next `seq` so the peer's
  jitter buffer treats the gap as PLC frames. If the reconnect window
  (5 s) elapses, the session ends and a fresh handshake is needed.
- **Push-to-talk** (Sprint 4 D12): when the local user mutes (PTT off
  by default, or PTT-mode released) the Tx loop stops `AudioRecord`
  and skips its send leg. **No frames are sent** during mute periods -
  the wire format is unchanged but the peer sees `seq` gaps that the
  jitter buffer treats as PLC. Unmuting resumes inside one tick and
  the peer's jitter PLC tail naturally fills the seam.
- `ts` wraps every ~21 minutes - receivers MUST tolerate wrap-around.
- `seq` increments per peer-direction, starting at 0 on session start. The
  receiver uses `seq` for jitter buffer ordering and PLC trigger detection.
- Opus is configured for VoIP: 16 kHz mono, 20 ms frame, 24 kbps,
  complexity 5, FEC on, DTX off. Source of truth is
  `rust/oppolink-codec/src/lib.rs`.

### v2 AEAD frame (current - Sprint 4 D13 locked)

```
┌─────────────────────────────────────────────────────┐
│ len:        u16 big-endian   (L2CAP delimiter; outside the AEAD AAD) │
├─────────────────────────────────────────────────────┤
│ seq:        u16 big-endian   (AAD)                  │
│ ts:         u16 big-endian   (AAD)                  │
│ nonce:      [u8; 12]         (ChaCha20-Poly1305)    │
│ ciphertext: variable          (encrypted Opus packet)│
│ tag:        [u8; 16]         (Poly1305 authentication tag)            │
└─────────────────────────────────────────────────────┘
```

- **AAD** (additional authenticated data) = the 4-byte header
  `[seq | ts]`. The peer cannot tamper with `seq` / `ts` without
  invalidating the AEAD tag.
- **Nonce** = HKDF-derived 12-byte `nonce_prefix` XOR
  (`seq` zero-padded big-endian to 12 bytes). `seq` is unique per
  session so the resulting nonce never repeats.
- **Tag mismatch** → receiver drops the frame; the jitter buffer PLCs
  the gap.

### v2 key schedule

```
ephemeral_keypair_a, ephemeral_keypair_b  (Curve25519, CSPRNG, fresh per call)
shared_secret = X25519(secret_a, public_b) = X25519(secret_b, public_a)

salt = byte-wise min(public_a, public_b)
ikm  = byte-wise max(public_a, public_b)
info = b"oppolink/v2/aead" || ikm

okm  = HKDF-SHA256(salt = salt, ikm = shared_secret, info = info, len = 47)
       = [chacha_key:32 | nonce_prefix:12 | sas_bytes:3]
```

- `salt = min(pubkey_a, pubkey_b)` gives a canonical ordering so both
  peers derive the same OKM regardless of who calls themselves "a".
- `sas_bytes` → rendered as `u24 % 1_000_000` → 6-digit decimal SAS,
  shown to both users for out-of-band MITM verification.

### Wire-level pubkey exchange (Sprint 4 D13)

The handshake characteristic v1 transmitted a zero-padded 32-byte
pubkey field. v2 makes that field load-bearing: the server publishes
its ephemeral pubkey via GATT. The **client** writes its own ephemeral
pubkey as the **first 32 bytes** of the L2CAP socket payload, before
any audio frame. Server reads those 32 bytes, ECDHs against its own
secret, derives the same OKM.

## Key schedule

- Each side generates an ephemeral Curve25519 keypair at session start.
- ECDH yields a 32-byte shared secret.
- HKDF-SHA256 with `info = b"oppolink/v1/aead"` and a 32-byte salt (the
  byte-wise minimum of the two public keys) derives:
  - 32 bytes: ChaCha20-Poly1305 key,
  - 12 bytes: nonce prefix (xored with `seq` for per-frame uniqueness).

## Versioning

- The `version` byte is a hard fence. A peer receiving an unknown version
  MUST close the GATT connection without further interaction.
- Backwards-compatible additions land as new GATT characteristics, not by
  growing the handshake struct.

## Out-of-scope for v1

- Multi-peer fan-out (point-to-point only).
- DTX / VBR (off; simplifies the jitter buffer).
- Forward error correction at the framing layer (Opus FEC is enabled inside
  the codec; that's enough for v1).
