# Threat Model

What OppoLink protects against, what it doesn't, and what the BLE-only thesis
actually buys you.

## What "BLE-only" buys

- **No server-side log exists** of who called whom. There is no server.
- **No metadata trail** at carriers / Wi-Fi / ISPs. The audio never touches
  IP. SS7 does not see this call.
- **Range-limited blast radius**: an attacker must be within Bluetooth range
  (~10 m for Class 2, ~100 m for Class 1) to even attempt interception.
- **No account == no identity**. There is nothing to phish, leak, or correlate
  across services.

## What "BLE-only" does NOT buy

- **Anonymity from a co-located attacker**. BLE advertising broadcasts your
  nickname and a (rotating, eventually) MAC address. A sniffer within range
  knows OppoLink users are nearby and roughly which ones are paired.
- **Tamper-evident audio** without our AEAD. BLE encryption alone is **not**
  sufficient — see "Why we don't trust BLE pairing" below.
- **Resilience to a compromised handset**. If your phone is owned, OppoLink
  doesn't help you. (Nothing does.)
- **Forward secrecy beyond a single session** — handled by ephemeral
  Curve25519, but each session's keys live in memory until call end.

## In-scope threats (v1 must defeat)

1. **Passive RF eavesdropping** within Bluetooth range. Mitigation: AEAD
   (ChaCha20-Poly1305) keyed by ECDH; the BLE link is treated as a public
   channel.
2. **Active replay** of captured frames. Mitigation: per-frame nonce derived
   from `seq` xored with a session-unique nonce prefix; receiver tracks the
   highest accepted `seq` and rejects rewinds.
3. **MITM at handshake** by a co-located attacker forwarding GATT exchanges.
   Mitigation: out-of-band visual verification of a short authentication
   string (SAS) derived from both pubkeys. Lands in Sprint 4 D13.
4. **Battery / radio DoS**: hostile peer keeps the L2CAP socket open and
   sends garbage. Mitigation: AEAD verification short-circuits decode;
   ≥3 consecutive AEAD failures terminate the session and back off advertising
   for 60 s.

## Out-of-scope threats (v1)

- **Targeted RF intercept by a state actor** with custom gear. We are not
  building NSA-grade COMSEC.
- **Acoustic side channels** (microphone capturing keystrokes etc.).
- **Compromised baseband / TEE on the host phone.** Out of our reach.
- **Long-term traffic-analysis** of advertising patterns. Future work might
  randomize nickname + manufacturer-data payload on a timer; v1 ships
  static-during-session.

## Why we don't trust BLE pairing alone

- **Just Works** pairing is unauthenticated and MITM-vulnerable.
- **Passkey Entry** requires both peers to have a display + input. Possible
  but UX-hostile for a quick call.
- **Out-of-Band** would need a side channel we don't have.
- The Bluetooth security database is shared between all apps on a phone —
  any other app that pairs with the same peer can read our bond key on
  some Android versions.

The conclusion: we **always** layer AEAD on top of the L2CAP socket and we
do **not** rely on BLE pairing for confidentiality or authenticity. The
L2CAP channel is treated as if it were the public internet.

## Cryptographic primitives (v1)

| Purpose              | Primitive                          |
| -------------------- | ---------------------------------- |
| Key agreement        | Curve25519 ECDH (ephemeral)        |
| Key derivation       | HKDF-SHA256                        |
| AEAD                 | ChaCha20-Poly1305                  |
| Handshake authentic. | OOB SAS verification (Sprint 4)    |

All primitives are FIPS-irrelevant — this is a consumer voice app, not a
DoD-procured device.
