//! OppoLink wire-protocol crate.
//!
//! This crate owns:
//!   - the 16-byte BLE service UUID prefix and the wire-format constants,
//!   - the handshake state machine (Curve25519 ECDH at v1),
//!   - the AEAD layer (ChaCha20-Poly1305 at v1),
//!   - typed framing of 20 ms Opus packets.
//!
//! Sprint 1 D1 only ships a `greet` function that the Android side calls
//! through UniFFI to verify the cross-compile + bindgen pipeline.

uniffi::setup_scaffolding!();

/// Returns a greeting from the Rust side.
///
/// Called from Kotlin during Sprint 1 D1 to verify the cargo-ndk → UniFFI
/// bindgen → Compose pipeline. Will be deleted in D2 once a real handshake
/// API replaces it.
#[uniffi::export]
pub fn greet(name: String) -> String {
    format!("Hello from Rust, {name}")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn greet_returns_expected_string() {
        assert_eq!(greet("world".to_string()), "Hello from Rust, world");
    }

    #[test]
    fn greet_handles_unicode() {
        assert_eq!(greet("dünya".to_string()), "Hello from Rust, dünya");
    }
}
