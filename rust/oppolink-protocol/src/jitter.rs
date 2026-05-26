//! UniFFI surface for the receive-side jitter buffer.
//!
//! The pure Rust [`oppolink_jitter::JitterBuffer`] is wrapped in a UniFFI
//! Object with `Mutex` interior mutability so the Android Rx thread can
//! push freshly arrived frames while the Playback thread independently
//! pulls the next packet for decode.

use std::sync::{Arc, Mutex};

use oppolink_jitter as jb;

/// What [`JitterBuffer::push`] decided about the incoming packet.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum JitterPushResult {
    Accepted,
    LateArrival,
    Duplicate,
}

/// What [`JitterBuffer::pop_next`] handed back to the playback thread.
///
/// Modeled as an enum rather than a tuple because UniFFI's Kotlin output
/// surfaces this as a sealed class hierarchy on the Kotlin side, which
/// makes the consumer easy to pattern-match.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum JitterPopResult {
    /// Decode and play this Opus packet.
    Packet { opus_packet: Vec<u8> },
    /// Synthesize a packet via `opus_decode(NULL)`.
    Plc,
    /// No data yet - caller should keep idling. Used during prewarm.
    Empty,
}

/// Counters exposed for telemetry; the Kotlin UI can render them inside
/// the InCall card.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct JitterStats {
    pub buffered: u32,
    pub target_depth: u32,
    pub push_count: u64,
    pub pop_count: u64,
    pub plc_count: u64,
    pub late_arrival_count: u64,
    pub duplicate_count: u64,
}

#[derive(uniffi::Object)]
pub struct JitterBuffer {
    inner: Mutex<jb::JitterBuffer>,
}

#[uniffi::export]
impl JitterBuffer {
    /// Build a buffer with the OppoLink default bounds (40–100 ms = 2–5
    /// frames).
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            inner: Mutex::new(jb::JitterBuffer::with_defaults()),
        })
    }

    /// Build a buffer with explicit depth bounds. `max_depth` must be
    /// `>= min_depth >= 1`. Mostly useful in tests; production code should
    /// stick with [`new()`].
    #[uniffi::constructor]
    pub fn with_bounds(min_depth: u32, max_depth: u32) -> Arc<Self> {
        Arc::new(Self {
            inner: Mutex::new(jb::JitterBuffer::new(
                min_depth.max(1) as usize,
                max_depth.max(min_depth.max(1)) as usize,
            )),
        })
    }

    pub fn push(&self, seq: u16, opus_packet: Vec<u8>) -> JitterPushResult {
        let mut inner = self.inner.lock().expect("JitterBuffer mutex poisoned");
        match inner.push(seq, opus_packet) {
            jb::PushResult::Accepted => JitterPushResult::Accepted,
            jb::PushResult::LateArrival => JitterPushResult::LateArrival,
            jb::PushResult::Duplicate => JitterPushResult::Duplicate,
        }
    }

    pub fn pop_next(&self) -> JitterPopResult {
        let mut inner = self.inner.lock().expect("JitterBuffer mutex poisoned");
        match inner.pop_next() {
            jb::PopResult::Packet(p) => JitterPopResult::Packet { opus_packet: p },
            jb::PopResult::Plc => JitterPopResult::Plc,
            jb::PopResult::Empty => JitterPopResult::Empty,
        }
    }

    pub fn stats(&self) -> JitterStats {
        let inner = self.inner.lock().expect("JitterBuffer mutex poisoned");
        let s = inner.stats();
        JitterStats {
            buffered: s.buffered,
            target_depth: s.target_depth,
            push_count: s.push_count,
            pop_count: s.pop_count,
            plc_count: s.plc_count,
            late_arrival_count: s.late_arrival_count,
            duplicate_count: s.duplicate_count,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn push_pop_roundtrip() {
        let b = JitterBuffer::with_bounds(2, 4);
        assert_eq!(b.push(0, vec![1]), JitterPushResult::Accepted);
        assert_eq!(b.push(1, vec![2]), JitterPushResult::Accepted);
        match b.pop_next() {
            JitterPopResult::Packet { opus_packet } => assert_eq!(opus_packet, vec![1]),
            other => panic!("expected Packet, got {other:?}"),
        }
        match b.pop_next() {
            JitterPopResult::Packet { opus_packet } => assert_eq!(opus_packet, vec![2]),
            other => panic!("expected Packet, got {other:?}"),
        }
    }
}
