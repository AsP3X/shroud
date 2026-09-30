//! Random share codes for contact discovery (QR / deep links).

use rand::Rng;

/// Crockford-like alphabet without ambiguous I/L/O/U/0/1.
const ALPHABET: &[u8] = b"23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
const CODE_LEN: usize = 10;

/// Generates a fresh random share code (not derived from user id).
pub fn generate_share_code() -> String {
    let mut rng = rand::thread_rng();
    (0..CODE_LEN)
        .map(|_| {
            let idx = rng.gen_range(0..ALPHABET.len());
            ALPHABET[idx] as char
        })
        .collect()
}

/// Normalizes user input (trim, uppercase, strip @ and spaces/dashes).
pub fn normalize_share_code(raw: &str) -> String {
    raw.trim()
        .trim_start_matches('@')
        .chars()
        .filter(|c| !c.is_whitespace() && *c != '-')
        .map(|c| c.to_ascii_uppercase())
        .collect()
}

/// True when `code` matches the stored share-code format (after normalization).
pub fn is_valid_share_code_format(code: &str) -> bool {
    let len = code.len();
    (8..=16).contains(&len)
        && code
            .chars()
            .all(|c| c.is_ascii_uppercase() || c.is_ascii_digit())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generates_expected_length_and_alphabet() {
        let code = generate_share_code();
        assert_eq!(code.len(), CODE_LEN);
        assert!(code.chars().all(|c| ALPHABET.contains(&(c as u8))));
    }

    #[test]
    fn normalize_strips_noise() {
        assert_eq!(normalize_share_code(" ab-cd 23 "), "ABCD23");
        assert_eq!(normalize_share_code("@alice"), "ALICE");
    }

    #[test]
    fn validates_format() {
        assert!(is_valid_share_code_format("ABCD234567"));
        assert!(!is_valid_share_code_format("SHORT"));
        assert!(!is_valid_share_code_format("has-dash!!"));
    }
}
