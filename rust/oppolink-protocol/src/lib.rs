//! OppoLink wire-protocol crate.
//!
//! This crate owns:
//!   - the 128-bit BLE service UUID and the on-air manufacturer-data layout,
//!   - the handshake state machine (Curve25519 ECDH at v1) — Sprint 4,
//!   - the AEAD layer (ChaCha20-Poly1305 at v1) — Sprint 4,
//!   - typed framing of 20 ms Opus packets — Sprint 2.
//!
//! Sprint 1 D2 ships the discovery primitives: service UUID, manufacturer-data
//! encode/decode, and the capability bitmap.

uniffi::setup_scaffolding!();

mod echo;
mod handshake;
mod manufacturer;

pub use echo::{EchoStats, ECHO_PACKET_LEN, ECHO_PAYLOAD_LEN, ECHO_SEQ_PREFIX_LEN};
pub use handshake::{
    decide_role, HandshakeError, HandshakeMessage, Role, HANDSHAKE_MAGIC, HANDSHAKE_MAX_LEN,
    HANDSHAKE_VERSION, PUBKEY_LEN,
};
pub use manufacturer::{
    Capabilities, ManufacturerData, MANUFACTURER_DATA_MAX_LEN, MANUFACTURER_ID, MAX_NICKNAME_BYTES,
    PROTOCOL_VERSION, WIRE_MAGIC,
};

/// 128-bit service UUID used in BLE advertising. ASCII "OPPL" on word-boundaries
/// keeps the UUID easy to spot in scan dumps. **Do not change without bumping
/// [`PROTOCOL_VERSION`] — peers filter on this UUID.**
pub const SERVICE_UUID: &str = "4f50504c-0001-4f50-504c-000000000001";

/// 16-bit characteristic ID used inside the GATT service for the handshake.
/// Mounted under [`SERVICE_UUID`] as `4f50504c-0001-4f50-504c-000000000002`
/// when the GATT layer lands in Sprint 1 D3.
pub const HANDSHAKE_CHAR_SUFFIX: &str = "00000002";

/// UniFFI accessor — Kotlin can't read Rust `const &str`, so we expose a fn.
#[uniffi::export]
pub fn service_uuid() -> String {
    SERVICE_UUID.to_string()
}

/// UniFFI accessor for the manufacturer ID used in BLE manufacturer-specific data.
#[uniffi::export]
pub fn manufacturer_id() -> u16 {
    MANUFACTURER_ID
}

/// 128-bit GATT characteristic UUID that holds the handshake payload.
/// `4F50504C-0001-4F50-504C-000000000002` — the [`SERVICE_UUID`] with its
/// trailing suffix bumped from `…0001` to `…0002`.
#[uniffi::export]
pub fn handshake_char_uuid() -> String {
    "4f50504c-0001-4f50-504c-000000000002".to_string()
}

/// Encode a handshake message to its on-air byte form. See the doc comment on
/// [`handshake`] for the layout.
#[uniffi::export]
pub fn encode_handshake(msg: HandshakeMessage) -> Vec<u8> {
    handshake::encode(&msg)
}

/// Parse a handshake message from a GATT characteristic read. Returns `None`
/// if the magic, version, role byte, or length prefix doesn't match.
#[uniffi::export]
pub fn parse_handshake(bytes: Vec<u8>) -> Option<HandshakeMessage> {
    handshake::parse(&bytes)
}

/// Encode a [`ManufacturerData`] payload to its on-air byte form.
///
/// Layout:
/// ```text
/// [0..2]  magic   "OP"          (constant, 0x4F 0x50)
/// [2..3]  ver     u8            (PROTOCOL_VERSION = 0x01)
/// [3..7]  caps    u32 little-endian
/// [7..8]  nicklen u8            (0..=16)
/// [8..N]  nick    UTF-8 bytes
/// ```
///
/// `nickname` is truncated at a UTF-8 char boundary to fit
/// [`MAX_NICKNAME_BYTES`]; never panics on multi-byte input.
#[uniffi::export]
pub fn encode_manufacturer_data(data: ManufacturerData) -> Vec<u8> {
    manufacturer::encode(&data)
}

/// Parse a manufacturer-data byte slice that the BLE scanner handed us back.
/// Returns `None` if the magic, version, or layout doesn't match.
#[uniffi::export]
pub fn parse_manufacturer_data(bytes: Vec<u8>) -> Option<ManufacturerData> {
    manufacturer::parse(&bytes)
}

/// UniFFI accessor for the fixed echo packet size in bytes. The Kotlin side
/// allocates its receive buffer with this constant so any future protocol
/// bump only needs to touch Rust.
#[uniffi::export]
pub fn echo_packet_size() -> u32 {
    ECHO_PACKET_LEN as u32
}

/// Build the on-wire bytes for an echo packet at sequence number `seq`.
/// Always returns exactly [`ECHO_PACKET_LEN`] bytes — 4-byte big-endian seq
/// prefix followed by zero-filled body.
#[uniffi::export]
pub fn build_echo_packet(seq: u32) -> Vec<u8> {
    echo::build_packet(seq)
}

/// Parse the leading sequence number from an echo packet. Returns `None` if
/// the buffer is shorter than [`ECHO_SEQ_PREFIX_LEN`]; never reads past the
/// prefix, so a truncated body is still parseable.
#[uniffi::export]
pub fn parse_echo_seq(bytes: Vec<u8>) -> Option<u32> {
    echo::parse_seq(&bytes)
}

/// Summarize a list of RTT samples (microseconds) into an [`EchoStats`] using
/// the nearest-rank percentile. Empty input returns a zeroed result so the UI
/// can render a placeholder without branching on `Option`.
#[uniffi::export]
pub fn summarize_echo_samples(samples_us: Vec<u32>) -> EchoStats {
    echo::summarize(&samples_us)
}
