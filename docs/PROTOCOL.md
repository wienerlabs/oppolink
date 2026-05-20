# Wire Protocol — v1 (draft)

Status: **draft**. Locked at the end of Sprint 1 D4 once the L2CAP echo test
validates round-trip latency. Subsequent breaking changes require a version
byte bump.

## Identifiers

- **Service UUID** (locked in Sprint 1 D2):
  `4F50504C-0001-4F50-504C-000000000001`. ASCII "OPPL" appears on word
  boundaries so the UUID is easy to spot in scan dumps.
- **Handshake characteristic** (Sprint 1 D3):
  `4F50504C-0001-4F50-504C-000000000002` — the same base UUID with the suffix
  bumped to `…0002`.
- **Manufacturer ID**: `0xFFFF` (Bluetooth SIG "test" range). Replaced with a
  real SIG-assigned ID before the public v1 release.
- **Wire magic**: ASCII `"OP"` (2 bytes) inside the manufacturer-specific data
  field, disambiguating OppoLink advertisements from any other app that also
  happens to use `0xFFFF`.

## Discovery — BLE advertising (Sprint 1 D2, locked)

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
`parseManufacturerData`. Kotlin must not parse the layout by hand — Rust is
the source of truth.

## Handshake (Sprint 1 D3, over GATT characteristic `…0002`)

```
┌─────────────────────────────────────────────────────┐
│ magic:    "OPL1"            (4 bytes)               │
│ version:  u8                (currently 0x01)        │
│ role:     u8                (0=server, 1=client)    │
│ pubkey:   [u8; 32]          (Curve25519 ephemeral)  │
│ psm:      u16 big-endian    (L2CAP PSM, server→client only; 0 from client) │
│ nick_len: u8                (0..=16)                │
│ nick:     [u8; nick_len]    (UTF-8, no null term)   │
└─────────────────────────────────────────────────────┘
```

- Role tie-break: the peer with the **lower BD_ADDR** (compared byte-wise
  big-endian) takes the **server** role and allocates the PSM.
- The PSM is the only field that flows server→client; the client sends
  `psm = 0`.

## Audio frame (over L2CAP CoC)

```
┌─────────────────────────────────────────────────────┐
│ seq:        u16 big-endian                          │
│ ts:         u16 big-endian   (units of 20 ms)       │
│ nonce:      [u8; 12]         (ChaCha20-Poly1305)    │
│ ciphertext: variable          (encrypted Opus)      │
│ tag:        [u8; 16]                                │
└─────────────────────────────────────────────────────┘
```

- `ts` wraps every ~21 minutes — receivers MUST tolerate wrap-around.
- `seq` increments per peer-direction, starting at 0 on session start. The
  receiver uses `seq` for jitter buffer ordering and PLC trigger detection.

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
