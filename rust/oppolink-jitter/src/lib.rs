//! Adaptive jitter buffer + PLC orchestration for the OppoLink audio path.
//!
//! Sprint 3 D8 ships the receive-side scheduler that sits between the
//! L2CAP read loop and the playback decode loop:
//!
//! ```text
//!   Rx thread ─ push(seq, opus) ─▶  JitterBuffer  ─ pop_next() ─▶  Playback thread
//! ```
//!
//! Responsibilities:
//!   - **Re-ordering**: out-of-order arrivals are placed back in seq order.
//!     Older-than-next-expected arrivals are dropped (counted as late).
//!     Repeated seq numbers are deduped.
//!   - **Wrap-around** of the 16-bit `seq` field is handled correctly: we
//!     never compare two `seq` values with `<` directly; the signed
//!     16-bit delta is the canonical "is A before B" test.
//!   - **PLC trigger**: when the playback thread asks for the next frame
//!     and the expected `seq` has not arrived, the buffer returns
//!     [`PopResult::Plc`] so the playback code can synthesize one frame
//!     of PCM via `opus_decode(NULL)`.
//!   - **Adaptive depth**: target buffer depth grows when PLC fires often
//!     (peer's jitter is high) and shrinks back when frames arrive
//!     cleanly. Bounded by [`min_depth`, `max_depth`].
//!
//! Wire format is unchanged by the jitter buffer - this is a pure
//! receiver-side concern.

#![forbid(unsafe_op_in_unsafe_fn)]

use std::collections::BTreeMap;

/// Default adaptive bounds - 2–5 frames at 20 ms = 40–100 ms of buffering.
/// Matches the spec target in `README.md` (Audio Pipeline section).
pub const DEFAULT_MIN_DEPTH: usize = 2;
pub const DEFAULT_MAX_DEPTH: usize = 5;

/// Window over which the adaptive controller reacts to PLC fires.
const PLC_GROW_THRESHOLD: u32 = 1;
/// Window over which a stretch of clean pops shrinks the target depth.
const CLEAN_SHRINK_THRESHOLD: u32 = 50;

/// What [`JitterBuffer::push`] decided about the incoming packet.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PushResult {
    /// Stored in the buffer.
    Accepted,
    /// Older than `next_expected`; the playback loop has already moved past it.
    LateArrival,
    /// A frame with this `seq` is already in the buffer.
    Duplicate,
}

/// What [`JitterBuffer::pop_next`] handed back to the playback thread.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PopResult {
    /// Decode and play this Opus packet.
    Packet(Vec<u8>),
    /// Synthesize a packet via `opus_decode(NULL)`; the expected frame is
    /// missing and the buffer has advanced past it.
    Plc,
    /// No data yet - the caller should keep idling. Used during the
    /// initial prewarm before the buffer has filled to `target_depth`.
    Empty,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct JitterStats {
    pub buffered: u32,
    pub target_depth: u32,
    pub push_count: u64,
    pub pop_count: u64,
    pub plc_count: u64,
    pub late_arrival_count: u64,
    pub duplicate_count: u64,
}

pub struct JitterBuffer {
    entries: BTreeMap<u16, Vec<u8>>,
    next_expected: Option<u16>,
    target_depth: usize,
    min_depth: usize,
    max_depth: usize,
    consecutive_clean_pops: u32,
    pending_plc_credit: u32,
    /// Set to `true` once we've finished the initial prewarm (filled to
    /// `target_depth` at least once). Until then, [`pop_next`] returns
    /// [`PopResult::Empty`] so playback starts smoothly.
    primed: bool,
    push_count: u64,
    pop_count: u64,
    plc_count: u64,
    late_arrival_count: u64,
    duplicate_count: u64,
}

impl JitterBuffer {
    pub fn new(min_depth: usize, max_depth: usize) -> Self {
        assert!(min_depth >= 1, "min_depth must be >= 1");
        assert!(max_depth >= min_depth, "max_depth must be >= min_depth");
        Self {
            entries: BTreeMap::new(),
            next_expected: None,
            target_depth: min_depth,
            min_depth,
            max_depth,
            consecutive_clean_pops: 0,
            pending_plc_credit: 0,
            primed: false,
            push_count: 0,
            pop_count: 0,
            plc_count: 0,
            late_arrival_count: 0,
            duplicate_count: 0,
        }
    }

    /// Convenience for the OppoLink default bounds (40-100 ms).
    pub fn with_defaults() -> Self {
        Self::new(DEFAULT_MIN_DEPTH, DEFAULT_MAX_DEPTH)
    }

    pub fn target_depth(&self) -> usize {
        self.target_depth
    }

    pub fn stats(&self) -> JitterStats {
        JitterStats {
            buffered: self.entries.len() as u32,
            target_depth: self.target_depth as u32,
            push_count: self.push_count,
            pop_count: self.pop_count,
            plc_count: self.plc_count,
            late_arrival_count: self.late_arrival_count,
            duplicate_count: self.duplicate_count,
        }
    }

    /// Insert a freshly arrived frame.
    ///
    /// `next_expected` is intentionally **not** set on push - that is the
    /// playback loop's job in [`pop_next`]. Setting it on first push would
    /// classify an out-of-order arrival of a smaller seq as `LateArrival`,
    /// which it is not until playback has actually moved past it.
    pub fn push(&mut self, seq: u16, opus: Vec<u8>) -> PushResult {
        self.push_count += 1;

        if let Some(next) = self.next_expected {
            let delta = signed_seq_delta(seq, next);
            if delta < 0 {
                // Strictly older than next_expected - the playback loop
                // has already PLC'd or moved past it.
                self.late_arrival_count += 1;
                return PushResult::LateArrival;
            }
        }
        if self.entries.contains_key(&seq) {
            self.duplicate_count += 1;
            return PushResult::Duplicate;
        }

        self.entries.insert(seq, opus);
        if !self.primed && self.entries.len() >= self.target_depth {
            self.primed = true;
        }
        PushResult::Accepted
    }

    /// Pull the next frame for playback (or a [`PopResult::Plc`] /
    /// [`PopResult::Empty`] signal).
    pub fn pop_next(&mut self) -> PopResult {
        if !self.primed {
            return PopResult::Empty;
        }

        // First pop: anchor `next_expected` to the smallest seq currently
        // buffered. We can't take "first arrival" as the anchor because
        // arrivals are routinely out of order in jittery transports.
        if self.next_expected.is_none() {
            self.next_expected = self.entries.keys().next().copied();
        }
        let next = match self.next_expected {
            Some(seq) => seq,
            None => return PopResult::Empty,
        };

        if let Some(packet) = self.entries.remove(&next) {
            self.advance_next_expected();
            self.pop_count += 1;
            self.on_clean_pop();
            return PopResult::Packet(packet);
        }

        // Expected frame not here. If there's anything later in the
        // buffer, we PLC and advance. If the buffer is empty, the peer
        // has gone silent and we also PLC - capping consecutive PLCs is
        // the caller's policy.
        self.advance_next_expected();
        self.plc_count += 1;
        self.pop_count += 1;
        self.on_plc();
        PopResult::Plc
    }

    fn advance_next_expected(&mut self) {
        if let Some(seq) = self.next_expected {
            self.next_expected = Some(seq.wrapping_add(1));
        }
    }

    fn on_plc(&mut self) {
        self.consecutive_clean_pops = 0;
        self.pending_plc_credit = self.pending_plc_credit.saturating_add(1);
        if self.pending_plc_credit >= PLC_GROW_THRESHOLD && self.target_depth < self.max_depth {
            self.target_depth += 1;
            self.pending_plc_credit = 0;
        }
    }

    fn on_clean_pop(&mut self) {
        self.consecutive_clean_pops = self.consecutive_clean_pops.saturating_add(1);
        self.pending_plc_credit = 0;
        if self.consecutive_clean_pops >= CLEAN_SHRINK_THRESHOLD
            && self.target_depth > self.min_depth
        {
            self.target_depth -= 1;
            self.consecutive_clean_pops = 0;
        }
    }
}

/// Signed 16-bit delta of `a` relative to `b`. Positive when `a` is
/// strictly after `b` on the seq line, accounting for wrap-around.
fn signed_seq_delta(a: u16, b: u16) -> i32 {
    (a.wrapping_sub(b)) as i16 as i32
}

#[derive(thiserror::Error, Debug)]
pub enum JitterError {
    #[error("jitter buffer configuration is invalid: {0}")]
    BadConfig(&'static str),
}

#[cfg(test)]
mod tests {
    use super::*;

    fn packet(byte: u8) -> Vec<u8> {
        vec![byte]
    }

    #[test]
    fn empty_buffer_returns_empty() {
        let mut jb = JitterBuffer::with_defaults();
        assert_eq!(jb.pop_next(), PopResult::Empty);
    }

    #[test]
    fn prewarm_returns_empty_until_target_depth() {
        let mut jb = JitterBuffer::new(2, 5);
        assert_eq!(jb.push(0, packet(1)), PushResult::Accepted);
        // Below target depth; still empty.
        assert_eq!(jb.pop_next(), PopResult::Empty);
        assert_eq!(jb.push(1, packet(2)), PushResult::Accepted);
        // Hit target depth; first pop returns the first packet.
        assert_eq!(jb.pop_next(), PopResult::Packet(packet(1)));
        assert_eq!(jb.pop_next(), PopResult::Packet(packet(2)));
    }

    #[test]
    fn out_of_order_arrivals_are_played_in_seq_order() {
        let mut jb = JitterBuffer::new(2, 5);
        assert_eq!(jb.push(1, packet(10)), PushResult::Accepted);
        assert_eq!(jb.push(0, packet(20)), PushResult::Accepted);
        // Prewarm to 2 frames; pop in seq order.
        assert_eq!(jb.pop_next(), PopResult::Packet(packet(20))); // seq=0
        assert_eq!(jb.pop_next(), PopResult::Packet(packet(10))); // seq=1
    }

    #[test]
    fn duplicate_seq_is_rejected() {
        let mut jb = JitterBuffer::with_defaults();
        assert_eq!(jb.push(0, packet(1)), PushResult::Accepted);
        assert_eq!(jb.push(0, packet(2)), PushResult::Duplicate);
        assert_eq!(jb.stats().duplicate_count, 1);
    }

    #[test]
    fn late_arrival_is_dropped() {
        let mut jb = JitterBuffer::new(2, 5);
        jb.push(0, packet(1));
        jb.push(1, packet(2));
        // Prewarm done - pop the first frame so next_expected advances to 1.
        assert_eq!(jb.pop_next(), PopResult::Packet(packet(1)));
        // seq=0 now strictly behind next_expected (1).
        assert_eq!(jb.push(0, packet(99)), PushResult::LateArrival);
        assert_eq!(jb.stats().late_arrival_count, 1);
    }

    #[test]
    fn missing_frame_triggers_plc() {
        let mut jb = JitterBuffer::new(2, 5);
        jb.push(0, packet(10));
        jb.push(2, packet(30)); // seq=1 missing
                                // Two distinct seqs ⇒ buffered=2 ⇒ primed.
        assert_eq!(jb.pop_next(), PopResult::Packet(packet(10)));
        assert_eq!(jb.pop_next(), PopResult::Plc);
        assert_eq!(jb.pop_next(), PopResult::Packet(packet(30)));
        assert_eq!(jb.stats().plc_count, 1);
    }

    #[test]
    fn plc_fires_grow_target_depth_up_to_max() {
        let mut jb = JitterBuffer::new(2, 4);
        jb.push(0, packet(1));
        jb.push(2, packet(3));
        // Prewarm and PLC once.
        let _ = jb.pop_next(); // seq 0
        let _ = jb.pop_next(); // PLC for seq 1
        assert_eq!(jb.target_depth(), 3, "PLC should bump target depth");
    }

    #[test]
    fn seq_wraparound_is_handled() {
        let mut jb = JitterBuffer::new(2, 5);
        jb.push(0xFFFE, packet(1));
        jb.push(0xFFFF, packet(2));
        assert_eq!(jb.pop_next(), PopResult::Packet(packet(1)));
        // After advance, next_expected = 0xFFFF.
        // Push wraps past 0 and 1.
        assert_eq!(jb.push(0x0000, packet(3)), PushResult::Accepted);
        assert_eq!(jb.push(0x0001, packet(4)), PushResult::Accepted);
        assert_eq!(jb.pop_next(), PopResult::Packet(packet(2)));
        assert_eq!(jb.pop_next(), PopResult::Packet(packet(3)));
        assert_eq!(jb.pop_next(), PopResult::Packet(packet(4)));
    }

    #[test]
    fn signed_delta_handles_wraparound() {
        assert_eq!(signed_seq_delta(0x0001, 0xFFFF), 2);
        assert_eq!(signed_seq_delta(0xFFFF, 0x0001), -2);
        assert_eq!(signed_seq_delta(10, 5), 5);
        assert_eq!(signed_seq_delta(5, 10), -5);
    }
}
