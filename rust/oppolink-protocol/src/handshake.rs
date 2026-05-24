//! GATT handshake message — the only payload exchanged over the BLE GATT
//! characteristic before the peers hop to L2CAP.
//!
//! Layout (locked in Sprint 1 D3):
//!
//! ```text
//!   [0..4]   magic  "OPL1"             (4 bytes)
//!   [4..5]   ver    u8                 (PROTOCOL_VERSION = 0x01)
//!   [5..6]   role   u8                 (0=server, 1=client)
//!   [6..38]  pubkey [u8; 32]           (Curve25519 ephemeral; zeros in v1
//!                                       until Sprint 4 D13 wires real ECDH)
//!   [38..40] psm    u16 big-endian     (L2CAP PSM; server→client only,
//!                                       client sends 0)
//!   [40..41] nick_len u8               (0..=16)
//!   [41..N]  nick   [u8; nick_len]     (UTF-8)
//! ```

use crate::manufacturer::MAX_NICKNAME_BYTES;

/// Wire-format magic specific to the handshake characteristic. Distinct from
/// the manufacturer-data magic so a peer reading either surface can fail fast
/// on a mismatch.
pub const HANDSHAKE_MAGIC: [u8; 4] = *b"OPL1";

/// Same wire-format version as the manufacturer-data layout. Bumped to
/// `0x02` in Sprint 4 D13 when ECDH + AEAD audio frames became mandatory.
/// A peer receiving an unknown version MUST close the GATT connection
/// without further reads.
pub const HANDSHAKE_VERSION: u8 = 0x02;

/// Length of the embedded Curve25519 public key. Sprint 4 will fill these
/// bytes; v1 emits all zeros so the wire format is fixed and future versions
/// don't have to renegotiate the prefix.
pub const PUBKEY_LEN: usize = 32;

const HEADER_LEN: usize =
    4 /* magic */ + 1 /* ver */ + 1 /* role */ + PUBKEY_LEN + 2 /* psm */ + 1 /* nick_len */;

/// Maximum on-wire size of a handshake message — fits well inside a default
/// 23-byte MTU after we negotiate the standard 247-byte MTU on connect.
pub const HANDSHAKE_MAX_LEN: usize = HEADER_LEN + MAX_NICKNAME_BYTES;

/// Which side of the connection a peer plays.
///
/// Decided deterministically by comparing BD_ADDRs byte-wise: the **lower**
/// address takes the server role and allocates the L2CAP PSM.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Role {
    Server,
    Client,
}

/// Decoded handshake payload.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HandshakeMessage {
    pub role: Role,
    /// Ephemeral Curve25519 public key. All-zero in v1 (Sprint 4 fills it).
    pub pubkey: Vec<u8>,
    /// L2CAP PSM. Server sets this to its listening PSM; client emits 0.
    pub psm: u16,
    pub nickname: String,
}

/// Decide which side is the L2CAP server by comparing the two 6-byte BD_ADDRs
/// byte-wise (big-endian). Returns the role the **local** peer should take.
///
/// Both inputs MUST be parsed from canonical Android `00:11:22:33:44:55`
/// strings before calling — see `bd_addr_to_bytes`.
#[uniffi::export]
pub fn decide_role(local_bd_addr: String, remote_bd_addr: String) -> Result<Role, HandshakeError> {
    let local = bd_addr_to_bytes(&local_bd_addr)?;
    let remote = bd_addr_to_bytes(&remote_bd_addr)?;
    if local == remote {
        return Err(HandshakeError::SameBdAddress);
    }
    Ok(if local < remote {
        Role::Server
    } else {
        Role::Client
    })
}

/// Parse a `00:11:22:33:44:55` style colon-separated MAC address.
fn bd_addr_to_bytes(s: &str) -> Result<[u8; 6], HandshakeError> {
    let mut out = [0u8; 6];
    let mut count = 0;
    for chunk in s.split(':') {
        if count >= 6 {
            return Err(HandshakeError::BadBdAddress);
        }
        out[count] = u8::from_str_radix(chunk, 16).map_err(|_| HandshakeError::BadBdAddress)?;
        count += 1;
    }
    if count != 6 {
        return Err(HandshakeError::BadBdAddress);
    }
    Ok(out)
}

#[derive(thiserror::Error, Debug, uniffi::Error)]
pub enum HandshakeError {
    #[error("invalid BD_ADDR — expected six colon-separated hex octets")]
    BadBdAddress,
    #[error("local and remote BD_ADDR are identical")]
    SameBdAddress,
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

pub(crate) fn encode(msg: &HandshakeMessage) -> Vec<u8> {
    let nick = truncate_utf8(&msg.nickname, MAX_NICKNAME_BYTES);
    let nick_bytes = nick.as_bytes();

    // Zero-pad the pubkey to PUBKEY_LEN regardless of what the caller passed —
    // accepting a shorter input keeps the API forgiving until Sprint 4 wires
    // real ECDH keys.
    let mut pubkey = [0u8; PUBKEY_LEN];
    let copy_len = msg.pubkey.len().min(PUBKEY_LEN);
    pubkey[..copy_len].copy_from_slice(&msg.pubkey[..copy_len]);

    let mut out = Vec::with_capacity(HEADER_LEN + nick_bytes.len());
    out.extend_from_slice(&HANDSHAKE_MAGIC);
    out.push(HANDSHAKE_VERSION);
    out.push(match msg.role {
        Role::Server => 0,
        Role::Client => 1,
    });
    out.extend_from_slice(&pubkey);
    out.extend_from_slice(&msg.psm.to_be_bytes());
    out.push(nick_bytes.len() as u8);
    out.extend_from_slice(nick_bytes);
    out
}

pub(crate) fn parse(bytes: &[u8]) -> Option<HandshakeMessage> {
    if bytes.len() < HEADER_LEN {
        return None;
    }
    if bytes[0..4] != HANDSHAKE_MAGIC {
        return None;
    }
    if bytes[4] != HANDSHAKE_VERSION {
        return None;
    }
    let role = match bytes[5] {
        0 => Role::Server,
        1 => Role::Client,
        _ => return None,
    };
    let pubkey = bytes[6..6 + PUBKEY_LEN].to_vec();
    let psm = u16::from_be_bytes([bytes[38], bytes[39]]);
    let nick_len = bytes[40] as usize;
    if nick_len > MAX_NICKNAME_BYTES {
        return None;
    }
    if bytes.len() < HEADER_LEN + nick_len {
        return None;
    }
    let nick_bytes = &bytes[HEADER_LEN..HEADER_LEN + nick_len];
    let nickname = std::str::from_utf8(nick_bytes).ok()?.to_string();
    Some(HandshakeMessage {
        role,
        pubkey,
        psm,
        nickname,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn zero_pubkey() -> Vec<u8> {
        vec![0u8; PUBKEY_LEN]
    }

    #[test]
    fn encode_parse_server_roundtrip() {
        let original = HandshakeMessage {
            role: Role::Server,
            pubkey: zero_pubkey(),
            psm: 0x0080,
            nickname: "Reno-11".to_string(),
        };
        let bytes = encode(&original);
        assert!(bytes.len() <= HANDSHAKE_MAX_LEN);
        let decoded = parse(&bytes).expect("must parse");
        assert_eq!(decoded, original);
    }

    #[test]
    fn encode_parse_client_with_zero_psm() {
        let original = HandshakeMessage {
            role: Role::Client,
            pubkey: zero_pubkey(),
            psm: 0,
            nickname: "Mëhmet's phöne".to_string(),
        };
        let bytes = encode(&original);
        let decoded = parse(&bytes).expect("must parse");
        assert_eq!(decoded, original);
    }

    #[test]
    fn encode_truncates_nickname_at_char_boundary() {
        let original = HandshakeMessage {
            role: Role::Server,
            pubkey: zero_pubkey(),
            psm: 0x40,
            nickname: "aüüüüüüüüü".to_string(), // 19 bytes
        };
        let bytes = encode(&original);
        let decoded = parse(&bytes).expect("must parse");
        assert!(decoded.nickname.len() <= MAX_NICKNAME_BYTES);
        assert!(decoded.nickname.starts_with('a'));
    }

    #[test]
    fn encode_pads_short_pubkey_with_zeros() {
        let msg = HandshakeMessage {
            role: Role::Server,
            pubkey: vec![1, 2, 3],
            psm: 0,
            nickname: String::new(),
        };
        let bytes = encode(&msg);
        // Pubkey bytes start at offset 6.
        assert_eq!(&bytes[6..9], &[1, 2, 3]);
        assert!(bytes[9..6 + PUBKEY_LEN].iter().all(|b| *b == 0));
    }

    #[test]
    fn parse_rejects_bad_magic() {
        let mut bytes = encode(&HandshakeMessage {
            role: Role::Server,
            pubkey: zero_pubkey(),
            psm: 1,
            nickname: "x".to_string(),
        });
        bytes[0] = b'X';
        assert!(parse(&bytes).is_none());
    }

    #[test]
    fn parse_rejects_unknown_role() {
        let mut bytes = encode(&HandshakeMessage {
            role: Role::Server,
            pubkey: zero_pubkey(),
            psm: 1,
            nickname: "x".to_string(),
        });
        bytes[5] = 0xFE;
        assert!(parse(&bytes).is_none());
    }

    #[test]
    fn parse_rejects_truncated_buffer() {
        let bytes = encode(&HandshakeMessage {
            role: Role::Server,
            pubkey: zero_pubkey(),
            psm: 1,
            nickname: "x".to_string(),
        });
        assert!(parse(&bytes[..bytes.len() - 1]).is_none());
    }

    #[test]
    fn decide_role_lower_is_server() {
        let role = decide_role(
            "AA:BB:CC:DD:EE:01".to_string(),
            "AA:BB:CC:DD:EE:02".to_string(),
        )
        .unwrap();
        assert_eq!(role, Role::Server);
    }

    #[test]
    fn decide_role_higher_is_client() {
        let role = decide_role(
            "FF:00:00:00:00:00".to_string(),
            "00:00:00:00:00:00".to_string(),
        )
        .unwrap();
        assert_eq!(role, Role::Client);
    }

    #[test]
    fn decide_role_compares_byte_wise_big_endian() {
        // First byte 0x10 < 0x20, so local is server regardless of the
        // trailing bytes being numerically larger.
        let role = decide_role(
            "10:FF:FF:FF:FF:FF".to_string(),
            "20:00:00:00:00:00".to_string(),
        )
        .unwrap();
        assert_eq!(role, Role::Server);
    }

    #[test]
    fn decide_role_rejects_identical_addresses() {
        let err = decide_role(
            "AA:BB:CC:DD:EE:FF".to_string(),
            "AA:BB:CC:DD:EE:FF".to_string(),
        )
        .unwrap_err();
        matches!(err, HandshakeError::SameBdAddress);
    }

    #[test]
    fn decide_role_rejects_malformed_input() {
        let err =
            decide_role("not-a-bd-addr".to_string(), "AA:BB:CC:DD:EE:FF".to_string()).unwrap_err();
        matches!(err, HandshakeError::BadBdAddress);
    }
}
