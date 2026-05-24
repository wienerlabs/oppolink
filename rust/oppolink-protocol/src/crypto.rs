//! Sprint 4 D13 — Curve25519 ECDH + HKDF-SHA256 → ChaCha20-Poly1305.
//!
//! The handshake characteristic now carries each peer's **ephemeral
//! 32-byte Curve25519 public key** (was zero-padded in v1). Both sides
//! Diffie-Hellman against the peer's pubkey to derive a 32-byte shared
//! secret, then expand it with HKDF-SHA256 into:
//!
//!   - 32 bytes: ChaCha20-Poly1305 key
//!   - 12 bytes: nonce prefix
//!   - 3 bytes: SAS-bytes (rendered as a 6-digit decimal)
//!
//! HKDF salt is the byte-wise **smaller** of the two public keys. HKDF
//! info is `b"oppolink/v2/aead"`. The protocol version byte in the
//! handshake bumps to `0x02` for the AEAD-mandatory wire format.
//!
//! `EphemeralKeyPair` is a UniFFI Object — Android holds it briefly
//! during the handshake; once `derive_session` runs the secret half is
//! consumed (cleared on drop) and only the resulting [`SessionKey`]
//! remains.

use std::sync::Arc;

use hkdf::Hkdf;
use rand_core::OsRng;
use sha2::Sha256;
use x25519_dalek::{PublicKey, StaticSecret};

use crate::session::SessionKey;

/// HKDF "info" label. Bump together with [`crate::PROTOCOL_VERSION`]
/// whenever the AEAD layer changes shape.
pub const HKDF_INFO: &[u8] = b"oppolink/v2/aead";

/// Length of the derived ChaCha20-Poly1305 key.
pub const SESSION_KEY_LEN: usize = 32;

/// Length of the derived nonce prefix that XOR'd with `seq` makes the
/// 12-byte AEAD nonce per frame.
pub const NONCE_PREFIX_LEN: usize = 12;

/// Length of the derived SAS material before rendering to decimal.
pub const SAS_LEN: usize = 3;

/// Errors surfaced by the handshake crypto layer.
#[derive(thiserror::Error, Debug, uniffi::Error)]
pub enum CryptoError {
    #[error("peer public key must be exactly 32 bytes, got {got}")]
    BadPublicKeyLen { got: u32 },
    #[error("HKDF expansion failed (this should never happen)")]
    HkdfExpand,
}

/// Sprint 4 D13 ephemeral keypair. Generated fresh per call so a leaked
/// session key never compromises past traffic — Perfect Forward Secrecy
/// per Signal-style design.
///
/// `derive_session` is intentionally non-consuming: a Sprint 3 D11
/// reconnect on the same call needs to re-derive the same key against
/// the same peer pubkey. The secret half is dropped when the keypair
/// goes out of scope (UniFFI Arc).
#[derive(uniffi::Object)]
pub struct EphemeralKeyPair {
    secret: StaticSecret,
    public: PublicKey,
}

#[uniffi::export]
impl EphemeralKeyPair {
    /// Generate a fresh keypair via the OS CSPRNG (`OsRng`).
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        let secret = StaticSecret::random_from_rng(OsRng);
        let public = PublicKey::from(&secret);
        Arc::new(Self { secret, public })
    }

    /// Returns the public half. Always 32 bytes.
    pub fn public_key(&self) -> Vec<u8> {
        self.public.as_bytes().to_vec()
    }

    /// Perform ECDH against `peer_public_key` (32-byte Curve25519 pubkey
    /// pulled out of the peer's handshake message) and derive a
    /// [`SessionKey`]. Safe to call multiple times with the same peer
    /// pubkey — the result is deterministic, which is what Sprint 3 D11
    /// reconnect relies on.
    pub fn derive_session(&self, peer_public_key: Vec<u8>) -> Result<Arc<SessionKey>, CryptoError> {
        if peer_public_key.len() != 32 {
            return Err(CryptoError::BadPublicKeyLen {
                got: peer_public_key.len() as u32,
            });
        }
        let peer_pub_array: [u8; 32] =
            peer_public_key
                .as_slice()
                .try_into()
                .map_err(|_| CryptoError::BadPublicKeyLen {
                    got: peer_public_key.len() as u32,
                })?;
        let peer_public = PublicKey::from(peer_pub_array);

        let shared = self.secret.diffie_hellman(&peer_public);
        let my_public = self.public;

        let (salt, ikm) = sorted_pubkey_pair(my_public.as_bytes(), peer_public.as_bytes());

        let hkdf = Hkdf::<Sha256>::new(Some(&salt), shared.as_bytes());
        let mut okm = [0u8; SESSION_KEY_LEN + NONCE_PREFIX_LEN + SAS_LEN];
        // Mix the HKDF info with the byte-wise larger pubkey so two peers
        // that swap roles derive an identical key (commutative under the
        // canonical ordering we picked for the salt).
        let mut info = Vec::with_capacity(HKDF_INFO.len() + 32);
        info.extend_from_slice(HKDF_INFO);
        info.extend_from_slice(&ikm);
        hkdf.expand(&info, &mut okm)
            .map_err(|_| CryptoError::HkdfExpand)?;

        let mut key = [0u8; SESSION_KEY_LEN];
        key.copy_from_slice(&okm[..SESSION_KEY_LEN]);
        let mut nonce_prefix = [0u8; NONCE_PREFIX_LEN];
        nonce_prefix.copy_from_slice(&okm[SESSION_KEY_LEN..SESSION_KEY_LEN + NONCE_PREFIX_LEN]);

        // SAS = 24 bits of HKDF output → 6 decimal digits (0..999_999).
        let sas_raw = &okm[SESSION_KEY_LEN + NONCE_PREFIX_LEN..];
        let sas_u32 =
            ((sas_raw[0] as u32) << 16) | ((sas_raw[1] as u32) << 8) | (sas_raw[2] as u32);
        let sas = sas_u32 % 1_000_000;

        Ok(SessionKey::new(key, nonce_prefix, sas))
    }
}

/// Returns (salt, ikm) where `salt` is the byte-wise smaller of the two
/// pubkeys and `ikm` is the larger. This gives a canonical ordering so
/// both peers (each holding `my_pub` ≠ `peer_pub`) derive the same key.
fn sorted_pubkey_pair(a: &[u8; 32], b: &[u8; 32]) -> ([u8; 32], [u8; 32]) {
    if a.as_slice() <= b.as_slice() {
        (*a, *b)
    } else {
        (*b, *a)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keypair_generates_distinct_pubkeys() {
        let a = EphemeralKeyPair::new();
        let b = EphemeralKeyPair::new();
        assert_eq!(a.public_key().len(), 32);
        assert_eq!(b.public_key().len(), 32);
        assert_ne!(a.public_key(), b.public_key());
    }

    #[test]
    fn ecdh_derives_matching_session_keys() {
        let alice = EphemeralKeyPair::new();
        let bob = EphemeralKeyPair::new();
        let alice_pub = alice.public_key();
        let bob_pub = bob.public_key();

        let alice_session = alice.derive_session(bob_pub).unwrap();
        let bob_session = bob.derive_session(alice_pub).unwrap();

        assert_eq!(alice_session.sas(), bob_session.sas());
    }

    #[test]
    fn ecdh_rejects_wrong_pubkey_length() {
        let kp = EphemeralKeyPair::new();
        let err = kp.derive_session(vec![0u8; 16]).unwrap_err();
        assert!(matches!(err, CryptoError::BadPublicKeyLen { got: 16 }));
    }

    #[test]
    fn pubkey_sort_is_canonical() {
        let a = [0u8; 32];
        let mut b = [0u8; 32];
        b[0] = 1;
        let (salt, ikm) = sorted_pubkey_pair(&a, &b);
        assert_eq!(salt, a);
        assert_eq!(ikm, b);
        // Order independence:
        let (salt2, ikm2) = sorted_pubkey_pair(&b, &a);
        assert_eq!(salt2, a);
        assert_eq!(ikm2, b);
    }
}
