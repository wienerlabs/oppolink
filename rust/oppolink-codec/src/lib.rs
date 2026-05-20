//! libopus bindings + 20 ms VoIP framing for OppoLink.
//!
//! Sprint 1 D1: types only; real Opus FFI lands in Sprint 2.

#![forbid(unsafe_op_in_unsafe_fn)]

/// Framing constants — these are wire-format and MUST NOT drift between
/// peers without a protocol version bump.
pub mod constants {
    /// PCM sample rate the capture/playback pipeline runs at.
    pub const SAMPLE_RATE_HZ: u32 = 16_000;
    /// Frame duration as required by Opus VoIP profile.
    pub const FRAME_DURATION_MS: u32 = 20;
    /// Samples per 20 ms frame at 16 kHz mono.
    pub const SAMPLES_PER_FRAME: usize = 320;
    /// Target Opus bitrate, picked for 16 kHz speech on Class 2 BLE.
    pub const TARGET_BITRATE_BPS: u32 = 24_000;
}

#[derive(thiserror::Error, Debug)]
pub enum CodecError {
    #[error("codec is not yet implemented in Sprint 1 D1")]
    NotImplemented,
}
