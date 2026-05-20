//! Adaptive jitter buffer + PLC orchestration.
//!
//! Sprint 1 D1: types only; real adaptive logic lands in Sprint 3 D8.

#![forbid(unsafe_op_in_unsafe_fn)]

/// Target depth bounds in 20 ms units (40–100 ms range from the README).
pub const MIN_DEPTH_FRAMES: usize = 2;
pub const MAX_DEPTH_FRAMES: usize = 5;

#[derive(thiserror::Error, Debug)]
pub enum JitterError {
    #[error("jitter buffer is not yet implemented in Sprint 1 D1")]
    NotImplemented,
}
