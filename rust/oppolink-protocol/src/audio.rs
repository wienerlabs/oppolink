//! UniFFI surface for the Opus encoder / decoder + the audio frame
//! header. The pure-Rust audio logic lives in `oppolink-codec`; this module
//! wraps it with the `Mutex` interior mutability UniFFI Objects need and
//! re-shapes the error type into something UniFFI can serialize.
//!
//! The 4-byte framing header is the same layout Sprint 1 D4 echo packets
//! use for their leading bytes, but the field semantics differ - see
//! [`docs/PROTOCOL.md`](../../../docs/PROTOCOL.md) "Audio frame".

use std::sync::{Arc, Mutex};

use oppolink_codec as codec;

/// UniFFI-serializable analogue of [`codec::CodecError`]. The string
/// variants pin the libopus error at the call site; the structured ones
/// surface PCM-length and short-frame mistakes the Kotlin side can format
/// without parsing free-form strings.
///
/// NB: field is named `detail` rather than `message` - UniFFI's generated
/// Kotlin maps each variant to a class extending `Throwable` and a field
/// called `message` would shadow `Throwable.message` without the
/// `override` modifier the codegen does not emit.
#[derive(thiserror::Error, Debug, uniffi::Error)]
pub enum AudioError {
    #[error("libopus encoder error: {detail}")]
    Encoder { detail: String },
    #[error("libopus decoder error: {detail}")]
    Decoder { detail: String },
    #[error("PCM frame must be exactly {expected} samples, got {actual}")]
    BadPcmLen { expected: u32, actual: u32 },
    #[error("frame too short to parse header (need {needed}, got {got})")]
    FrameTooShort { needed: u32, got: u32 },
}

impl From<codec::CodecError> for AudioError {
    fn from(e: codec::CodecError) -> Self {
        match e {
            codec::CodecError::OpusEncoder(m) => Self::Encoder { detail: m },
            codec::CodecError::OpusDecoder(m) => Self::Decoder { detail: m },
            codec::CodecError::PcmFrameLen { expected, actual } => {
                Self::BadPcmLen { expected, actual }
            }
            codec::CodecError::FrameTooShort { needed, got } => Self::FrameTooShort { needed, got },
        }
    }
}

/// 4-byte framing header that prefixes every audio frame on the wire.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct AudioFrameHeader {
    pub seq: u16,
    pub ts: u16,
}

/// A parsed audio frame: header bytes pulled out, Opus payload copied into
/// `opus_packet` so the Kotlin side can hand it straight to a decoder.
#[derive(Debug, Clone, uniffi::Record)]
pub struct ParsedAudioFrame {
    pub header: AudioFrameHeader,
    pub opus_packet: Vec<u8>,
}

/// Stateful Opus VoIP encoder, owned by the Kotlin side as a long-lived
/// handle. The `Mutex` is necessary because UniFFI Objects must be
/// `Send + Sync` and libopus state is mutated on every encode.
#[derive(uniffi::Object)]
pub struct VoipEncoder {
    inner: Mutex<codec::VoipEncoder>,
}

#[uniffi::export]
impl VoipEncoder {
    /// Build a fresh encoder configured for the OppoLink VoIP profile.
    #[uniffi::constructor]
    pub fn new() -> Result<Arc<Self>, AudioError> {
        Ok(Arc::new(Self {
            inner: Mutex::new(codec::VoipEncoder::new()?),
        }))
    }

    /// Encode one 20 ms PCM frame ([`pcm_samples_per_frame`] samples mono
    /// at 16 kHz). Returns the raw Opus packet bytes.
    pub fn encode(&self, pcm: Vec<i16>) -> Result<Vec<u8>, AudioError> {
        let mut inner = self.inner.lock().expect("VoipEncoder mutex poisoned");
        let mut dst = Vec::with_capacity(codec::MAX_OPUS_PACKET_LEN);
        inner.encode_into(&pcm, &mut dst)?;
        Ok(dst)
    }
}

/// Stateful Opus VoIP decoder; mirrors [`VoipEncoder`] for the receive side.
#[derive(uniffi::Object)]
pub struct VoipDecoder {
    inner: Mutex<codec::VoipDecoder>,
}

#[uniffi::export]
impl VoipDecoder {
    #[uniffi::constructor]
    pub fn new() -> Result<Arc<Self>, AudioError> {
        Ok(Arc::new(Self {
            inner: Mutex::new(codec::VoipDecoder::new()?),
        }))
    }

    /// Decode an Opus packet into PCM. Always returns exactly
    /// [`pcm_samples_per_frame`] samples.
    pub fn decode(&self, packet: Vec<u8>) -> Result<Vec<i16>, AudioError> {
        let mut inner = self.inner.lock().expect("VoipDecoder mutex poisoned");
        let mut dst = Vec::with_capacity(codec::SAMPLES_PER_FRAME);
        inner.decode_into(&packet, &mut dst)?;
        Ok(dst)
    }

    /// Synthesize one frame's worth of PCM with libopus' built-in PLC when
    /// no packet arrived. Sprint 3 D8 (jitter buffer) will be the heavy
    /// caller; D5 surfaces it now so the API doesn't churn.
    pub fn decode_plc(&self) -> Result<Vec<i16>, AudioError> {
        let mut inner = self.inner.lock().expect("VoipDecoder mutex poisoned");
        let mut dst = Vec::with_capacity(codec::SAMPLES_PER_FRAME);
        inner.decode_plc_into(&mut dst)?;
        Ok(dst)
    }
}

/// Build the on-wire `[header | opus]` bytes for one audio frame.
#[uniffi::export]
pub fn build_audio_frame(header: AudioFrameHeader, opus_packet: Vec<u8>) -> Vec<u8> {
    codec::build_frame(
        codec::FrameHeader {
            seq: header.seq,
            ts: header.ts,
        },
        &opus_packet,
    )
}

/// Parse `[header | opus]` from a slice; returns the header and a copy of
/// the Opus payload sub-slice.
#[uniffi::export]
pub fn parse_audio_frame(bytes: Vec<u8>) -> Result<ParsedAudioFrame, AudioError> {
    let (header, opus) = codec::parse_frame(&bytes)?;
    Ok(ParsedAudioFrame {
        header: AudioFrameHeader {
            seq: header.seq,
            ts: header.ts,
        },
        opus_packet: opus.to_vec(),
    })
}

/// PCM samples per 20 ms frame. Source of truth lives in `oppolink-codec`.
#[uniffi::export]
pub fn pcm_samples_per_frame() -> u32 {
    codec::SAMPLES_PER_FRAME as u32
}

/// PCM sample rate (Hz).
#[uniffi::export]
pub fn pcm_sample_rate_hz() -> u32 {
    codec::SAMPLE_RATE_HZ
}

/// Frame duration (milliseconds).
#[uniffi::export]
pub fn pcm_frame_duration_ms() -> u32 {
    codec::FRAME_DURATION_MS
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn audio_frame_header_roundtrips() {
        let header = AudioFrameHeader { seq: 7, ts: 14 };
        let opus = vec![1u8, 2, 3, 4, 5];
        let bytes = build_audio_frame(header, opus.clone());
        let parsed = parse_audio_frame(bytes).unwrap();
        assert_eq!(parsed.header, header);
        assert_eq!(parsed.opus_packet, opus);
    }

    #[test]
    fn parse_audio_frame_rejects_truncated_input() {
        let result = parse_audio_frame(vec![0u8, 1]);
        assert!(matches!(result, Err(AudioError::FrameTooShort { .. })));
    }

    #[test]
    fn encoder_decoder_roundtrip_silence() {
        let enc = VoipEncoder::new().unwrap();
        let dec = VoipDecoder::new().unwrap();
        let pcm = vec![0i16; codec::SAMPLES_PER_FRAME];
        let packet = enc.encode(pcm).unwrap();
        let out = dec.decode(packet).unwrap();
        assert_eq!(out.len(), codec::SAMPLES_PER_FRAME);
    }
}
