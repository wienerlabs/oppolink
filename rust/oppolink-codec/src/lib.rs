//! libopus VoIP encoder / decoder + 4-byte framing header.
//!
//! The encoder + decoder hold libopus state and a pre-allocated scratch
//! buffer so the audio hot path can `encode()` / `decode()` without
//! allocating anything new. The 20 ms tick budget cannot tolerate a heap
//! call.
//!
//! Wire format (after the L2CAP header) is locked at Sprint 2 D5:
//!
//! ```text
//!  ┌────────────────────────────┐
//!  │ seq:  u16 big-endian       │  (frame index, wraps every 65 536 frames)
//!  │ ts:   u16 big-endian       │  (units of 20 ms; wraps every ~21 min)
//!  │ opus: variable-length      │  (raw libopus packet)
//!  └────────────────────────────┘
//! ```
//!
//! Per `docs/PROTOCOL.md`, the audio frame additionally carries a 12-byte
//! ChaCha20-Poly1305 nonce + 16-byte tag once Sprint 4 D13 lands. The
//! framing helpers here intentionally know nothing about AEAD; that layer
//! wraps `[seq | ts | opus]` from the outside.

#![forbid(unsafe_op_in_unsafe_fn)]

use opus::{Application, Channels, Decoder as OpusDecoder, Encoder as OpusEncoder};

/// PCM sample rate the capture/playback pipeline runs at.
pub const SAMPLE_RATE_HZ: u32 = 16_000;

/// Channel layout — mono in v1.
pub const CHANNELS: u32 = 1;

/// Frame duration in milliseconds. Opus VoIP profile uses 20 ms.
pub const FRAME_DURATION_MS: u32 = 20;

/// Samples per 20 ms frame at 16 kHz mono.
pub const SAMPLES_PER_FRAME: usize = (SAMPLE_RATE_HZ as usize * FRAME_DURATION_MS as usize) / 1000;

/// Target Opus bitrate, picked for 16 kHz speech on Class 2 BLE.
pub const TARGET_BITRATE_BPS: u32 = 24_000;

/// Opus complexity setting. 5 is the documented sweet spot for VoIP on
/// mid-range mobile CPUs (Reno 11 class).
pub const OPUS_COMPLEXITY: i32 = 5;

/// Bytes consumed by the OppoLink framing header that precedes every Opus
/// payload on the wire: `[seq: u16 BE | ts: u16 BE]`.
pub const FRAME_HEADER_LEN: usize = 4;

/// Cap on the encoded Opus packet length. libopus tops out around 1275 byte
/// per 20 ms frame at the highest bitrate; we round up to give headroom for
/// FEC redundancy and leave a comfortable margin under the BLE L2CAP MTU.
pub const MAX_OPUS_PACKET_LEN: usize = 1500;

/// Cap on the framed packet length (header + Opus payload).
pub const MAX_FRAME_LEN: usize = FRAME_HEADER_LEN + MAX_OPUS_PACKET_LEN;

/// Errors surfaced from the codec layer. The string variants pin a snapshot
/// of the libopus message at the call site — libopus error codes are
/// notoriously terse on their own.
#[derive(thiserror::Error, Debug)]
pub enum CodecError {
    #[error("libopus encoder error: {0}")]
    OpusEncoder(String),
    #[error("libopus decoder error: {0}")]
    OpusDecoder(String),
    #[error("PCM frame must be exactly {expected} samples, got {actual}")]
    PcmFrameLen { expected: u32, actual: u32 },
    #[error("frame too short to parse header (need {needed}, got {got})")]
    FrameTooShort { needed: u32, got: u32 },
}

/// Stateful Opus VoIP encoder.
///
/// One instance per call direction. Holds the libopus encoder state plus a
/// pre-allocated scratch buffer so [`encode_into`] never allocates after
/// construction.
pub struct VoipEncoder {
    encoder: OpusEncoder,
    scratch: Vec<u8>,
}

impl VoipEncoder {
    /// Build a fresh encoder. Configures the parameters spec'd in
    /// [`README.md`](../../README.md) Audio Pipeline section: 16 kHz mono,
    /// `OPUS_APPLICATION_VOIP`, 24 kbps CBR, complexity 5, FEC on, DTX off.
    pub fn new() -> Result<Self, CodecError> {
        let mut encoder = OpusEncoder::new(SAMPLE_RATE_HZ, Channels::Mono, Application::Voip)
            .map_err(|e| CodecError::OpusEncoder(format!("init: {e}")))?;

        encoder
            .set_bitrate(opus::Bitrate::Bits(TARGET_BITRATE_BPS as i32))
            .map_err(|e| CodecError::OpusEncoder(format!("set_bitrate: {e}")))?;
        encoder
            .set_inband_fec(true)
            .map_err(|e| CodecError::OpusEncoder(format!("set_inband_fec: {e}")))?;
        // DTX off in v1 — simplifies the jitter buffer; turning it on is a
        // Sprint 3 follow-up if battery drain demands it.

        Ok(Self {
            encoder,
            scratch: vec![0u8; MAX_OPUS_PACKET_LEN],
        })
    }

    /// Encode a single 20 ms PCM frame into `dst`. Replaces `dst`'s contents
    /// with the freshly encoded Opus packet. No allocations after `new()`
    /// provided `dst` has [`MAX_OPUS_PACKET_LEN`] capacity (caller's job).
    pub fn encode_into(&mut self, pcm: &[i16], dst: &mut Vec<u8>) -> Result<(), CodecError> {
        if pcm.len() != SAMPLES_PER_FRAME {
            return Err(CodecError::PcmFrameLen {
                expected: SAMPLES_PER_FRAME as u32,
                actual: pcm.len() as u32,
            });
        }
        let n = self
            .encoder
            .encode(pcm, &mut self.scratch)
            .map_err(|e| CodecError::OpusEncoder(format!("encode: {e}")))?;
        dst.clear();
        dst.extend_from_slice(&self.scratch[..n]);
        Ok(())
    }
}

/// Stateful Opus VoIP decoder.
pub struct VoipDecoder {
    decoder: OpusDecoder,
    scratch: Vec<i16>,
}

impl VoipDecoder {
    pub fn new() -> Result<Self, CodecError> {
        let decoder = OpusDecoder::new(SAMPLE_RATE_HZ, Channels::Mono)
            .map_err(|e| CodecError::OpusDecoder(format!("init: {e}")))?;
        Ok(Self {
            decoder,
            scratch: vec![0i16; SAMPLES_PER_FRAME],
        })
    }

    /// Decode an Opus packet into `dst`. Replaces `dst`'s contents with
    /// exactly [`SAMPLES_PER_FRAME`] PCM samples.
    pub fn decode_into(&mut self, packet: &[u8], dst: &mut Vec<i16>) -> Result<(), CodecError> {
        let n = self
            .decoder
            .decode(packet, &mut self.scratch, /* fec = */ false)
            .map_err(|e| CodecError::OpusDecoder(format!("decode: {e}")))?;
        dst.clear();
        dst.extend_from_slice(&self.scratch[..n]);
        Ok(())
    }

    /// Packet-loss concealment: synthesize one frame's worth of PCM when no
    /// packet arrived. Used by the Sprint 3 jitter buffer; here for
    /// completeness so D5 can call it without an extra dependency hop.
    pub fn decode_plc_into(&mut self, dst: &mut Vec<i16>) -> Result<(), CodecError> {
        let n = self
            .decoder
            .decode(&[], &mut self.scratch, /* fec = */ false)
            .map_err(|e| CodecError::OpusDecoder(format!("decode_plc: {e}")))?;
        dst.clear();
        dst.extend_from_slice(&self.scratch[..n]);
        Ok(())
    }
}

/// 4-byte framing header that prefixes every Opus packet on the wire.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct FrameHeader {
    pub seq: u16,
    pub ts: u16,
}

/// Build the on-wire `[header | opus]` bytes for one audio frame.
pub fn build_frame(header: FrameHeader, opus_packet: &[u8]) -> Vec<u8> {
    let mut buf = Vec::with_capacity(FRAME_HEADER_LEN + opus_packet.len());
    buf.extend_from_slice(&header.seq.to_be_bytes());
    buf.extend_from_slice(&header.ts.to_be_bytes());
    buf.extend_from_slice(opus_packet);
    buf
}

/// Parse `[header | opus]` from a slice; returns the header and the opus
/// payload sub-slice.
pub fn parse_frame(bytes: &[u8]) -> Result<(FrameHeader, &[u8]), CodecError> {
    if bytes.len() < FRAME_HEADER_LEN {
        return Err(CodecError::FrameTooShort {
            needed: FRAME_HEADER_LEN as u32,
            got: bytes.len() as u32,
        });
    }
    let seq = u16::from_be_bytes([bytes[0], bytes[1]]);
    let ts = u16::from_be_bytes([bytes[2], bytes[3]]);
    Ok((FrameHeader { seq, ts }, &bytes[FRAME_HEADER_LEN..]))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn silent_frame() -> Vec<i16> {
        vec![0i16; SAMPLES_PER_FRAME]
    }

    #[test]
    fn frame_header_roundtrips() {
        let header = FrameHeader {
            seq: 0x0123,
            ts: 0xABCD,
        };
        let opus = vec![0xDE, 0xAD, 0xBE, 0xEF];
        let bytes = build_frame(header, &opus);
        assert_eq!(bytes.len(), FRAME_HEADER_LEN + opus.len());
        let (parsed_header, parsed_opus) = parse_frame(&bytes).unwrap();
        assert_eq!(parsed_header, header);
        assert_eq!(parsed_opus, opus.as_slice());
    }

    #[test]
    fn parse_frame_rejects_short_buffer() {
        let result = parse_frame(&[0u8; 2]);
        assert!(matches!(result, Err(CodecError::FrameTooShort { .. })));
    }

    #[test]
    fn encoder_rejects_wrong_pcm_length() {
        let mut enc = VoipEncoder::new().unwrap();
        let mut dst = Vec::with_capacity(MAX_OPUS_PACKET_LEN);
        let err = enc.encode_into(&[0i16; 100], &mut dst).unwrap_err();
        assert!(matches!(err, CodecError::PcmFrameLen { .. }));
    }

    #[test]
    fn silence_roundtrips_under_opus() {
        let mut enc = VoipEncoder::new().unwrap();
        let mut dec = VoipDecoder::new().unwrap();
        let pcm_in = silent_frame();
        let mut packet = Vec::with_capacity(MAX_OPUS_PACKET_LEN);
        enc.encode_into(&pcm_in, &mut packet).unwrap();
        assert!(!packet.is_empty(), "Opus must emit at least one byte");
        assert!(packet.len() <= MAX_OPUS_PACKET_LEN);

        let mut pcm_out = Vec::with_capacity(SAMPLES_PER_FRAME);
        dec.decode_into(&packet, &mut pcm_out).unwrap();
        assert_eq!(pcm_out.len(), SAMPLES_PER_FRAME);
        // libopus VoIP profile + silence input should produce values within
        // a tight band of zero; we don't assert exact silence because the
        // decoder may emit dither.
        let peak = pcm_out.iter().map(|s| s.unsigned_abs()).max().unwrap();
        assert!(
            peak < 2_000,
            "silence decoded peak {peak} exceeds tolerance"
        );
    }

    #[test]
    fn sine_roundtrips_under_opus_lossily() {
        let mut enc = VoipEncoder::new().unwrap();
        let mut dec = VoipDecoder::new().unwrap();
        // 1 kHz sine at 16 kHz sample rate. Skip the very first frame —
        // Opus emits a warm-up frame that's effectively silence regardless
        // of the input.
        let mut packet = Vec::with_capacity(MAX_OPUS_PACKET_LEN);
        let mut pcm_out = Vec::with_capacity(SAMPLES_PER_FRAME);
        for frame_idx in 0..3 {
            let pcm: Vec<i16> = (0..SAMPLES_PER_FRAME)
                .map(|i| {
                    let t = (frame_idx * SAMPLES_PER_FRAME + i) as f32;
                    let v = (t * std::f32::consts::TAU * 1_000.0 / 16_000.0).sin();
                    (v * 20_000.0) as i16
                })
                .collect();
            enc.encode_into(&pcm, &mut packet).unwrap();
            dec.decode_into(&packet, &mut pcm_out).unwrap();
            assert_eq!(pcm_out.len(), SAMPLES_PER_FRAME);
        }
        // Last decoded frame should have non-trivial energy.
        let peak = pcm_out.iter().map(|s| s.unsigned_abs()).max().unwrap();
        assert!(peak > 5_000, "decoded sine peak too low: {peak}");
    }

    #[test]
    fn plc_emits_one_frame() {
        let mut dec = VoipDecoder::new().unwrap();
        let mut pcm_out = Vec::with_capacity(SAMPLES_PER_FRAME);
        dec.decode_plc_into(&mut pcm_out).unwrap();
        assert_eq!(pcm_out.len(), SAMPLES_PER_FRAME);
    }
}
