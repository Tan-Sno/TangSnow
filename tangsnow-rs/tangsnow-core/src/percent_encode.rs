//! 按 Android `Uri.encode(String)` 的语义做百分号编码。
//!
//! Kotlin 源：`util/UrlUtils.kt` 顶层 `percentEncode`。
//! 保留 `A-Za-z0-9` 与 `_-!.~'()*`，其余按 UTF-8 逐字节转 `%XX`（大写十六进制）。
//! 与 `Uri.encode` 逐字节一致，保证搜索 URL 与 Kotlin 实现完全相同。

const HEX: &[u8; 16] = b"0123456789ABCDEF";

pub fn percent_encode(value: &str) -> String {
    let mut sb = String::with_capacity(value.len() * 3);
    // Kotlin 遍历的是 UTF-8 字节（toByteArray 后逐字节处理），此处一致
    for &b in value.as_bytes() {
        let c = b as char; // 字节 → Latin-1 字符，与 Kotlin `b.toInt().toChar()` 等价
        let keep = c.is_ascii_uppercase()
            || c.is_ascii_lowercase()
            || c.is_ascii_digit()
            || matches!(c, '_' | '-' | '!' | '.' | '~' | '\'' | '(' | ')' | '*');
        if keep {
            sb.push(c);
        } else {
            sb.push('%');
            sb.push(HEX[(b >> 4) as usize] as char);
            sb.push(HEX[(b & 0x0F) as usize] as char);
        }
    }
    sb
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keeps_unreserved_ascii() {
        let keep = "abcXYZ0189_-!.~'()*";
        assert_eq!(percent_encode(keep), keep);
    }

    #[test]
    fn encodes_reserved() {
        assert_eq!(percent_encode(" "), "%20");
        assert_eq!(percent_encode("/"), "%2F");
        assert_eq!(percent_encode("?"), "%3F");
        assert_eq!(percent_encode("&"), "%26");
        assert_eq!(percent_encode("="), "%3D");
        assert_eq!(percent_encode("+"), "%2B");
    }

    #[test]
    fn encodes_utf8_byte_wise() {
        // 「棠」U+68E0 = E6 A3 A0，逐字节编码
        assert_eq!(percent_encode("棠"), "%E6%A3%A0");
        // 高位字节（≥0x80）不走 char 保留集
        assert_eq!(percent_encode("雪"), "%E9%9B%AA");
    }
}
