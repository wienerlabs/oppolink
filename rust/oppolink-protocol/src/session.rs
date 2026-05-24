//! Sprint 4 D13 — per-session AEAD layer.
//!
//! Once [`crate::EphemeralKeyPair::derive_session`] runs, both peers
//! hold a [`SessionKey`] backed by the same ChaCha20-Poly1305 key and
//! 12-byte nonce prefix. The audio path encrypts every 20 ms Opus frame
//! by:
//!
//!   1. Building the header `[seq:u16 BE | ts:u16 BE]`.
//!   2. Computing `nonce = prefix XOR seq_padded_be_to_12_bytes`. The
//!      `seq` is unique within a session (Sprint 1 D2 locked) so the
//!      nonce never repeats.
//!   3. ChaCha20-Poly1305 encrypts the Opus payload with the AAD = header.
//!   4. Wire: `[seq | ts | nonce | ciphertext | tag]`.
//!
//! On the receive side: parse header, recompute nonce, AEAD-decrypt.
//! Tag mismatch → [`SessionDecryptError::TagMismatch`]; the Rx loop
//! drops the frame, the jitter buffer PLCs the gap.

use std::sync::{Arc, Mutex};

use chacha20poly1305::{
    aead::{Aead, KeyInit, Payload},
    ChaCha20Poly1305, Key, Nonce,
};

use crate::audio::AudioFrameHeader;
use crate::crypto::{NONCE_PREFIX_LEN, SESSION_KEY_LEN};

/// Wire-format constants for the encrypted audio frame.
pub const FRAME_HEADER_LEN: usize = 4; // seq:u16 BE | ts:u16 BE
pub const AEAD_TAG_LEN: usize = 16;

/// Minimum encrypted frame size on the wire: header + nonce + tag.
/// A frame strictly smaller than this cannot possibly be valid.
pub const MIN_ENCRYPTED_FRAME_LEN: usize = FRAME_HEADER_LEN + NONCE_PREFIX_LEN + AEAD_TAG_LEN;

/// Errors surfaced by the session-layer AEAD.
#[derive(thiserror::Error, Debug, uniffi::Error)]
pub enum SessionDecryptError {
    #[error("frame too short: need at least {needed} bytes, got {got}")]
    FrameTooShort { needed: u32, got: u32 },
    #[error("AEAD tag mismatch — wrong key or tampered ciphertext")]
    TagMismatch,
}

/// Sprint 4 D13 session key + AEAD cipher.
///
/// Lives for one call: derived from the handshake's ECDH, used for every
/// `runDuplexCall` (including reconnects on the same PSM). A new
/// handshake creates a new key.
#[derive(uniffi::Object)]
pub struct SessionKey {
    inner: Mutex<SessionKeyInner>,
    sas_value: u32,
}

impl std::fmt::Debug for SessionKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // Don't print the underlying key material — even at Debug time.
        f.debug_struct("SessionKey")
            .field("sas", &self.sas_value)
            .finish_non_exhaustive()
    }
}

struct SessionKeyInner {
    cipher: ChaCha20Poly1305,
    nonce_prefix: [u8; NONCE_PREFIX_LEN],
}

impl SessionKey {
    /// Internal constructor; called from [`crate::EphemeralKeyPair::derive_session`].
    pub(crate) fn new(
        key: [u8; SESSION_KEY_LEN],
        nonce_prefix: [u8; NONCE_PREFIX_LEN],
        sas: u32,
    ) -> Arc<Self> {
        let cipher = ChaCha20Poly1305::new(Key::from_slice(&key));
        Arc::new(Self {
            inner: Mutex::new(SessionKeyInner {
                cipher,
                nonce_prefix,
            }),
            sas_value: sas,
        })
    }
}

#[uniffi::export]
impl SessionKey {
    /// 6-digit decimal SAS for OOB MITM verification. Both peers see the
    /// same number; the user reads it aloud to confirm there's no
    /// person-in-the-middle. UI hookup is a Sprint 4 D14 polish task.
    pub fn sas(&self) -> u32 {
        self.sas_value
    }

    /// Encrypt one Opus packet. Returns the on-wire bytes
    /// `[seq | ts | nonce | ciphertext | tag]` ready to drop into the
    /// L2CAP send leg (still preceded by the 2-byte length delimiter
    /// the caller writes itself).
    pub fn encrypt_frame(&self, seq: u16, ts: u16, opus_packet: Vec<u8>) -> Vec<u8> {
        let header = build_header(seq, ts);
        let inner = self.inner.lock().expect("SessionKey mutex poisoned");
        let nonce_bytes = nonce_for_seq(&inner.nonce_prefix, seq);
        let nonce = Nonce::from_slice(&nonce_bytes);
        let payload = Payload {
            msg: &opus_packet,
            aad: &header,
        };
        // ChaCha20Poly1305.encrypt cannot fail under normal usage — only
        // on a 64-GiB-class plaintext. We `expect` because 20 ms of audio
        // is at most a few kilobytes.
        let ciphertext = inner
            .cipher
            .encrypt(nonce, payload)
            .expect("ChaCha20-Poly1305 encrypt should not fail for VoIP-size input");

        let mut wire = Vec::with_capacity(FRAME_HEADER_LEN + NONCE_PREFIX_LEN + ciphertext.len());
        wire.extend_from_slice(&header);
        wire.extend_from_slice(&nonce_bytes);
        wire.extend_from_slice(&ciphertext);
        wire
    }

    /// Decrypt the on-wire bytes `[seq | ts | nonce | ciphertext | tag]`.
    /// Returns the parsed header + the recovered Opus packet, or a
    /// `SessionDecryptError` on tag mismatch / truncation.
    pub fn decrypt_frame(
        &self,
        bytes: Vec<u8>,
    ) -> Result<DecryptedAudioFrame, SessionDecryptError> {
        if bytes.len() < MIN_ENCRYPTED_FRAME_LEN {
            return Err(SessionDecryptError::FrameTooShort {
                needed: MIN_ENCRYPTED_FRAME_LEN as u32,
                got: bytes.len() as u32,
            });
        }
        let header = &bytes[..FRAME_HEADER_LEN];
        let seq = u16::from_be_bytes([header[0], header[1]]);
        let ts = u16::from_be_bytes([header[2], header[3]]);
        let nonce_slice = &bytes[FRAME_HEADER_LEN..FRAME_HEADER_LEN + NONCE_PREFIX_LEN];
        let ciphertext = &bytes[FRAME_HEADER_LEN + NONCE_PREFIX_LEN..];

        let inner = self.inner.lock().expect("SessionKey mutex poisoned");
        let nonce = Nonce::from_slice(nonce_slice);
        let payload = Payload {
            msg: ciphertext,
            aad: header,
        };
        let opus_packet = inner
            .cipher
            .decrypt(nonce, payload)
            .map_err(|_| SessionDecryptError::TagMismatch)?;

        Ok(DecryptedAudioFrame {
            header: AudioFrameHeader { seq, ts },
            opus_packet,
        })
    }
}

/// Successful decrypt result.
#[derive(Debug, Clone, uniffi::Record)]
pub struct DecryptedAudioFrame {
    pub header: AudioFrameHeader,
    pub opus_packet: Vec<u8>,
}

fn build_header(seq: u16, ts: u16) -> [u8; FRAME_HEADER_LEN] {
    let mut header = [0u8; FRAME_HEADER_LEN];
    header[0..2].copy_from_slice(&seq.to_be_bytes());
    header[2..4].copy_from_slice(&ts.to_be_bytes());
    header
}

/// nonce = prefix XOR (seq, big-endian, right-aligned in 12 bytes).
/// `seq` is unique within a session so the resulting nonce never repeats.
fn nonce_for_seq(prefix: &[u8; NONCE_PREFIX_LEN], seq: u16) -> [u8; NONCE_PREFIX_LEN] {
    let mut nonce = *prefix;
    let seq_bytes = seq.to_be_bytes();
    nonce[NONCE_PREFIX_LEN - 2] ^= seq_bytes[0];
    nonce[NONCE_PREFIX_LEN - 1] ^= seq_bytes[1];
    nonce
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::EphemeralKeyPair;

    fn paired_sessions() -> (Arc<SessionKey>, Arc<SessionKey>) {
        let alice = EphemeralKeyPair::new();
        let bob = EphemeralKeyPair::new();
        let alice_pub = alice.public_key();
        let bob_pub = bob.public_key();
        let alice_session = alice.derive_session(bob_pub).unwrap();
        let bob_session = bob.derive_session(alice_pub).unwrap();
        (alice_session, bob_session)
    }

    #[test]
    fn encrypt_decrypt_roundtrip() {
        let (alice, bob) = paired_sessions();
        let opus = vec![0xDE, 0xAD, 0xBE, 0xEF, 0x10, 0x20];
        let wire = alice.encrypt_frame(0x0123, 0x4567, opus.clone());
        let decrypted = bob.decrypt_frame(wire).unwrap();
        assert_eq!(decrypted.header.seq, 0x0123);
        assert_eq!(decrypted.header.ts, 0x4567);
        assert_eq!(decrypted.opus_packet, opus);
    }

    #[test]
    fn tampered_ciphertext_rejected() {
        let (alice, bob) = paired_sessions();
        let mut wire = alice.encrypt_frame(7, 14, vec![1u8, 2, 3]);
        // Flip a bit in the ciphertext body.
        let body_index = FRAME_HEADER_LEN + NONCE_PREFIX_LEN;
        wire[body_index] ^= 0x80;
        let err = bob.decrypt_frame(wire).unwrap_err();
        assert!(matches!(err, SessionDecryptError::TagMismatch));
    }

    #[test]
    fn truncated_frame_rejected() {
        let (_alice, bob) = paired_sessions();
        let err = bob.decrypt_frame(vec![0u8; 5]).unwrap_err();
        assert!(matches!(err, SessionDecryptError::FrameTooShort { .. }));
    }

    #[test]
    fn wrong_session_key_rejected() {
        let (alice, _bob) = paired_sessions();
        // Build a second, unrelated session pair.
        let (eve, _) = paired_sessions();
        let wire = alice.encrypt_frame(1, 1, vec![9, 9, 9]);
        let err = eve.decrypt_frame(wire).unwrap_err();
        assert!(matches!(err, SessionDecryptError::TagMismatch));
    }

    #[test]
    fn nonce_xor_with_seq() {
        let prefix = [0u8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x12, 0x34];
        let nonce = nonce_for_seq(&prefix, 0x00FF);
        // Bottom two bytes XOR'd with seq's BE bytes (0x00, 0xFF).
        assert_eq!(nonce[10], 0x12);
        assert_eq!(nonce[11], 0x34 ^ 0xFF);
    }
}
