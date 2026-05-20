//! BLE manufacturer-data wire format for OppoLink discovery.
//!
//! Compact, fixed-prefix layout that fits into a BLE scan-response payload
//! alongside our 128-bit service UUID in the primary advertising packet.
//!
//! See the doc comment on [`crate::encode_manufacturer_data`] for the byte
//! layout. The wire format is **stable** within a [`PROTOCOL_VERSION`];
//! breaking changes require a version bump and a peer-rejection rule.

/// On-air "OP" prefix that disambiguates OppoLink manufacturer-data from any
/// other app that might also use `MANUFACTURER_ID = 0xFFFF` for testing.
pub const WIRE_MAGIC: [u8; 2] = *b"OP";

/// Wire-format version. A peer receiving a value it doesn't understand MUST
/// drop the advertisement silently.
pub const PROTOCOL_VERSION: u8 = 0x01;

/// Bluetooth SIG manufacturer ID. `0xFFFF` is the reserved "test" ID used by
/// projects without a SIG registration. Replaced with a real allocation
/// before the public v1 release.
pub const MANUFACTURER_ID: u16 = 0xFFFF;

/// Nickname is UTF-8 truncated to this many bytes on the wire.
pub const MAX_NICKNAME_BYTES: usize = 16;

/// Header bytes that precede the nickname inside the manufacturer-data field.
const HEADER_LEN: usize = 2 /* magic */ + 1 /* ver */ + 4 /* caps */ + 1 /* nick_len */;

/// Maximum on-wire length: header + max nickname.
pub const MANUFACTURER_DATA_MAX_LEN: usize = HEADER_LEN + MAX_NICKNAME_BYTES;

/// Capability bits announced in the manufacturer-data payload.
///
/// Bits are independent — peers must AND their local capabilities with the
/// remote bitmap to decide which features are actually usable on this link.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct Capabilities {
    /// 16 kHz mono PCM capture/playback. **Always true in v1.**
    pub pcm_16k_mono: bool,
    /// libopus encode/decode available. **Always true in v1.**
    pub opus: bool,
    /// AEAD (ChaCha20-Poly1305) ready. False until Sprint 4 D13.
    pub aead: bool,
}

impl Default for Capabilities {
    fn default() -> Self {
        Self {
            pcm_16k_mono: true,
            opus: true,
            aead: false,
        }
    }
}

impl Capabilities {
    pub(crate) fn to_bits(self) -> u32 {
        let mut bits = 0u32;
        if self.pcm_16k_mono {
            bits |= 1 << 0;
        }
        if self.opus {
            bits |= 1 << 1;
        }
        if self.aead {
            bits |= 1 << 2;
        }
        bits
    }

    pub(crate) fn from_bits(bits: u32) -> Self {
        Self {
            pcm_16k_mono: bits & (1 << 0) != 0,
            opus: bits & (1 << 1) != 0,
            aead: bits & (1 << 2) != 0,
        }
    }
}

/// Decoded manufacturer-data payload — what the scanner surfaces and what the
/// advertiser consumes.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ManufacturerData {
    pub nickname: String,
    pub capabilities: Capabilities,
}

/// Truncate `s` to at most `max` bytes at a UTF-8 char boundary.
fn truncate_utf8(s: &str, max: usize) -> &str {
    if s.len() <= max {
        return s;
    }
    let mut end = max;
    while end > 0 && !s.is_char_boundary(end) {
        end -= 1;
    }
    &s[..end]
}

pub(crate) fn encode(data: &ManufacturerData) -> Vec<u8> {
    let nick = truncate_utf8(&data.nickname, MAX_NICKNAME_BYTES);
    let nick_bytes = nick.as_bytes();
    let mut out = Vec::with_capacity(HEADER_LEN + nick_bytes.len());
    out.extend_from_slice(&WIRE_MAGIC);
    out.push(PROTOCOL_VERSION);
    out.extend_from_slice(&data.capabilities.to_bits().to_le_bytes());
    out.push(nick_bytes.len() as u8);
    out.extend_from_slice(nick_bytes);
    out
}

pub(crate) fn parse(bytes: &[u8]) -> Option<ManufacturerData> {
    if bytes.len() < HEADER_LEN {
        return None;
    }
    if bytes[0..2] != WIRE_MAGIC {
        return None;
    }
    if bytes[2] != PROTOCOL_VERSION {
        return None;
    }
    let caps_bits = u32::from_le_bytes([bytes[3], bytes[4], bytes[5], bytes[6]]);
    let nick_len = bytes[7] as usize;
    if nick_len > MAX_NICKNAME_BYTES {
        return None;
    }
    if bytes.len() < HEADER_LEN + nick_len {
        return None;
    }
    let nick_bytes = &bytes[HEADER_LEN..HEADER_LEN + nick_len];
    let nickname = std::str::from_utf8(nick_bytes).ok()?.to_string();
    Some(ManufacturerData {
        nickname,
        capabilities: Capabilities::from_bits(caps_bits),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn capabilities_roundtrip_bits() {
        let c = Capabilities {
            pcm_16k_mono: true,
            opus: true,
            aead: true,
        };
        assert_eq!(Capabilities::from_bits(c.to_bits()), c);
    }

    #[test]
    fn default_capabilities_set_required_bits() {
        let bits = Capabilities::default().to_bits();
        assert_eq!(bits & 0b11, 0b11, "v1 must set PCM and Opus");
        assert_eq!(bits & 0b100, 0, "AEAD is false in v1 until Sprint 4");
    }

    #[test]
    fn encode_decode_ascii_roundtrip() {
        let original = ManufacturerData {
            nickname: "Reno-11".to_string(),
            capabilities: Capabilities::default(),
        };
        let bytes = encode(&original);
        assert!(bytes.len() <= MANUFACTURER_DATA_MAX_LEN);
        let decoded = parse(&bytes).expect("must parse");
        assert_eq!(decoded, original);
    }

    #[test]
    fn encode_decode_unicode_roundtrip() {
        let original = ManufacturerData {
            nickname: "Mëhmet's phöne".to_string(), // includes multibyte chars
            capabilities: Capabilities::default(),
        };
        let bytes = encode(&original);
        let decoded = parse(&bytes).expect("must parse");
        assert_eq!(decoded, original);
    }

    #[test]
    fn nickname_truncates_at_char_boundary() {
        // "ü" is 2 bytes in UTF-8; pad to land truncation on a multi-byte char.
        let original = ManufacturerData {
            nickname: "aüüüüüüüüü".to_string(), // 1 + 9*2 = 19 bytes
            capabilities: Capabilities::default(),
        };
        let bytes = encode(&original);
        let decoded = parse(&bytes).expect("must parse");
        assert!(decoded.nickname.len() <= MAX_NICKNAME_BYTES);
        // First char must survive, last char must NOT be a partial code point.
        assert!(decoded.nickname.starts_with('a'));
        assert!(decoded.nickname.chars().all(|c| c == 'a' || c == 'ü'));
    }

    #[test]
    fn parse_rejects_bad_magic() {
        let mut bytes = encode(&ManufacturerData {
            nickname: "x".to_string(),
            capabilities: Capabilities::default(),
        });
        bytes[0] = b'X';
        assert_eq!(parse(&bytes), None);
    }

    #[test]
    fn parse_rejects_wrong_version() {
        let mut bytes = encode(&ManufacturerData {
            nickname: "x".to_string(),
            capabilities: Capabilities::default(),
        });
        bytes[2] = 0xFE;
        assert_eq!(parse(&bytes), None);
    }

    #[test]
    fn parse_rejects_truncated_buffer() {
        let bytes = encode(&ManufacturerData {
            nickname: "x".to_string(),
            capabilities: Capabilities::default(),
        });
        let truncated = &bytes[..bytes.len() - 1];
        assert_eq!(parse(truncated), None);
    }

    #[test]
    fn parse_rejects_oversized_nick_len() {
        // Build a payload that lies about nickname length.
        let mut bytes = Vec::new();
        bytes.extend_from_slice(&WIRE_MAGIC);
        bytes.push(PROTOCOL_VERSION);
        bytes.extend_from_slice(&Capabilities::default().to_bits().to_le_bytes());
        bytes.push(17); // > MAX_NICKNAME_BYTES
        assert_eq!(parse(&bytes), None);
    }

    #[test]
    fn parse_rejects_invalid_utf8() {
        let mut bytes = Vec::new();
        bytes.extend_from_slice(&WIRE_MAGIC);
        bytes.push(PROTOCOL_VERSION);
        bytes.extend_from_slice(&Capabilities::default().to_bits().to_le_bytes());
        bytes.push(2);
        bytes.extend_from_slice(&[0xFF, 0xFE]); // invalid UTF-8
        assert_eq!(parse(&bytes), None);
    }

    #[test]
    fn empty_nickname_is_valid() {
        let original = ManufacturerData {
            nickname: String::new(),
            capabilities: Capabilities::default(),
        };
        let bytes = encode(&original);
        assert_eq!(parse(&bytes), Some(original));
    }
}
