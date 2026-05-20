# Wire Protocol — v1 (draft)

Status: **draft**. Locked at the end of Sprint 1 D4 once the L2CAP echo test
validates round-trip latency. Subsequent breaking changes require a version
byte bump.

## Identifiers

- **Service UUID prefix**: `0xOPPL` (full 128-bit UUID to be assigned before
  Sprint 1 D2). Used in BLE advertising and GATT service discovery.
- **Handshake characteristic**: `0xOPPL0001`.
- **Magic**: ASCII `"OPL1"` (4 bytes, big-endian on the wire).

## Handshake (over GATT characteristic `0xOPPL0001`)

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
