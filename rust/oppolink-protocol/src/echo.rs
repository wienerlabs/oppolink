//! L2CAP echo packet framing + RTT statistics (Sprint 1 D4).
//!
//! Used by the Android side to drive the round-trip-latency test that locks
//! the wire protocol at the end of Sprint 1. The packet format is intentionally
//! boring: a 4-byte big-endian sequence number followed by 1020 zero bytes,
//! totalling 1024 bytes per direction.
//!
//! The percentile computation lives in Rust so that the Sprint 2 audio path
//! can reuse it for the same RTT instrumentation, and the Sprint 3 reconnect
//! decision can stay in lockstep with whatever ranking algorithm Android
//! displays to the user.

/// Number of bytes in an echo packet on the wire (sequence prefix + body).
pub const ECHO_PACKET_LEN: usize = 1024;

/// Number of bytes consumed by the sequence prefix at the start of every packet.
pub const ECHO_SEQ_PREFIX_LEN: usize = 4;

/// Number of payload (body) bytes after the sequence prefix.
pub const ECHO_PAYLOAD_LEN: usize = ECHO_PACKET_LEN - ECHO_SEQ_PREFIX_LEN;

/// Result of a finished echo run.
///
/// `*_ms` fields hold millisecond round-trip times computed from microsecond
/// samples; `f64` is used so the Kotlin UI can format with sub-millisecond
/// precision without losing accuracy in the conversion.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct EchoStats {
    pub sample_count: u32,
    pub p50_ms: f64,
    pub p95_ms: f64,
    pub min_ms: f64,
    pub max_ms: f64,
}

pub(crate) fn build_packet(seq: u32) -> Vec<u8> {
    let mut buf = vec![0u8; ECHO_PACKET_LEN];
    buf[..ECHO_SEQ_PREFIX_LEN].copy_from_slice(&seq.to_be_bytes());
    buf
}

pub(crate) fn parse_seq(bytes: &[u8]) -> Option<u32> {
    if bytes.len() < ECHO_SEQ_PREFIX_LEN {
        return None;
    }
    Some(u32::from_be_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
}

/// Summarize a list of RTT samples measured in microseconds using the
/// **nearest-rank** percentile (index = ceil(p/100 · n) − 1). Empty input
/// returns a zeroed [`EchoStats`] so the UI can render a placeholder without
/// branching on `Option`.
pub(crate) fn summarize(samples_us: &[u32]) -> EchoStats {
    if samples_us.is_empty() {
        return EchoStats {
            sample_count: 0,
            p50_ms: 0.0,
            p95_ms: 0.0,
            min_ms: 0.0,
            max_ms: 0.0,
        };
    }
    let mut sorted: Vec<u32> = samples_us.to_vec();
    sorted.sort_unstable();
    let n = sorted.len();
    let us_to_ms = |us: u32| f64::from(us) / 1000.0;
    EchoStats {
        sample_count: n as u32,
        p50_ms: us_to_ms(sorted[percentile_index(50, n)]),
        p95_ms: us_to_ms(sorted[percentile_index(95, n)]),
        min_ms: us_to_ms(sorted[0]),
        max_ms: us_to_ms(sorted[n - 1]),
    }
}

/// Nearest-rank percentile index into a sorted slice of length `n`.
fn percentile_index(p: usize, n: usize) -> usize {
    debug_assert!(n > 0);
    // ceil(p · n / 100) − 1, clamped to [0, n − 1].
    let raw = p * n + 99;
    (raw / 100).saturating_sub(1).min(n - 1)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn build_packet_writes_seq_prefix_and_zero_body() {
        let buf = build_packet(0x01020304);
        assert_eq!(buf.len(), ECHO_PACKET_LEN);
        assert_eq!(&buf[..4], &[0x01, 0x02, 0x03, 0x04]);
        assert!(buf[4..].iter().all(|&b| b == 0));
    }

    #[test]
    fn parse_seq_roundtrips_built_packet() {
        let bytes = build_packet(0xDEAD_BEEF);
        assert_eq!(parse_seq(&bytes), Some(0xDEAD_BEEF));
    }

    #[test]
    fn parse_seq_rejects_short_buffer() {
        assert_eq!(parse_seq(&[0u8, 1, 2]), None);
        assert_eq!(parse_seq(&[]), None);
    }

    #[test]
    fn summarize_empty_returns_zeros() {
        let s = summarize(&[]);
        assert_eq!(s.sample_count, 0);
        assert_eq!(s.p50_ms, 0.0);
        assert_eq!(s.p95_ms, 0.0);
        assert_eq!(s.min_ms, 0.0);
        assert_eq!(s.max_ms, 0.0);
    }

    #[test]
    fn summarize_single_sample_collapses_to_one_value() {
        let s = summarize(&[30_000]);
        assert_eq!(s.sample_count, 1);
        assert_eq!(s.p50_ms, 30.0);
        assert_eq!(s.p95_ms, 30.0);
        assert_eq!(s.min_ms, 30.0);
        assert_eq!(s.max_ms, 30.0);
    }

    #[test]
    fn summarize_ten_samples_takes_nearest_rank() {
        // 10 ms .. 100 ms; nearest-rank p50 → sorted[4] = 50 ms,
        // p95 → sorted[9] = 100 ms.
        let samples_us: Vec<u32> = (1..=10).map(|i| i * 10_000).collect();
        let s = summarize(&samples_us);
        assert_eq!(s.sample_count, 10);
        assert_eq!(s.min_ms, 10.0);
        assert_eq!(s.max_ms, 100.0);
        assert_eq!(s.p50_ms, 50.0);
        assert_eq!(s.p95_ms, 100.0);
    }

    #[test]
    fn summarize_ignores_input_order() {
        let samples_us = vec![80_000, 10_000, 50_000, 30_000, 70_000];
        let s = summarize(&samples_us);
        assert_eq!(s.min_ms, 10.0);
        assert_eq!(s.max_ms, 80.0);
        // n=5: p50 → sorted[2] = 50, p95 → sorted[4] = 80.
        assert_eq!(s.p50_ms, 50.0);
        assert_eq!(s.p95_ms, 80.0);
    }

    #[test]
    fn percentile_index_examples() {
        assert_eq!(percentile_index(50, 1), 0);
        assert_eq!(percentile_index(95, 1), 0);
        assert_eq!(percentile_index(50, 10), 4);
        assert_eq!(percentile_index(95, 10), 9);
        assert_eq!(percentile_index(50, 100), 49);
        assert_eq!(percentile_index(95, 100), 94);
    }
}
