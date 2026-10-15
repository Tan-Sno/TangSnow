//! 法律文本的 `**…**` 加粗标记解析（纯函数）。
//!
//! Kotlin 源：`util/LegalText.kt` 的 `parse`（`emphasize` 依赖 Spannable，不移植；
//! Rust 侧返回正文与字符区间，渲染由调用方处理）。
//!
//! 规则：只认**成对**的 `**`；落单的标记连同其后的正文**原样保留**；
//! 空标记（`****`）被丢弃且不产生区间。区间为**字符索引**的闭区间
//! （Kotlin `IntRange` 按 UTF-16 code unit 计数——对 BMP 内中英文与 Rust 的
//! char 索引一致； astral 平面字符（如 emoji）会差一，法律文本不含此类字符）。

pub struct Marked {
    pub plain: String,
    /// 应加粗的区间（闭区间 first..=last，按字符计）
    pub bold_ranges: Vec<(usize, usize)>,
}

/// Kotlin `LegalText.parse` 的对齐实现。
pub fn parse(raw: &str) -> Marked {
    let marker = "**";
    let mut plain = String::with_capacity(raw.len());
    let mut bold_ranges = Vec::new();
    let mut chars_consumed = 0usize; // plain 侧已写入的字符数
    let mut cursor = 0usize; // raw 侧的字节游标
    while cursor < raw.len() {
        let Some(open) = raw[cursor..].find(marker).map(|o| cursor + o) else {
            break;
        };
        let Some(close) = raw[open + marker.len()..]
            .find(marker)
            .map(|o| open + marker.len() + o)
        else {
            break; // 落单：连同后面的正文一起按原样收尾
        };
        plain.push_str(&raw[cursor..open]);
        // 对齐 Kotlin `plain.length`：前缀写入后立即累计字符数
        chars_consumed += raw[cursor..open].chars().count();
        let start = chars_consumed;
        let body = &raw[open + marker.len()..close];
        plain.push_str(body);
        chars_consumed += body.chars().count();
        // Kotlin `if (plain.length > start)`：非空区间才记录（空标记被丢弃）
        if chars_consumed > start {
            bold_ranges.push((start, chars_consumed - 1));
        }
        cursor = close + marker.len();
    }
    if cursor < raw.len() {
        plain.push_str(&raw[cursor..]);
    }
    Marked { plain, bold_ranges }
}

#[cfg(test)]
mod tests {
    use super::*;

    // 与 JVM 单测 LegalTextTest 逐条对齐

    #[test]
    fn paired_markers_stripped_with_correct_ranges() {
        let m = parse("a**bc**d");
        assert_eq!("abcd", m.plain);
        assert_eq!(vec![(1, 2)], m.bold_ranges);
    }

    #[test]
    fn multiple_markers_independent_segments() {
        let m = parse("**甲**在中间**乙**尾");
        assert_eq!("甲在中间乙尾", m.plain);
        assert_eq!(vec![(0, 0), (4, 4)], m.bold_ranges);
    }

    #[test]
    fn no_markers_passes_through() {
        let m = parse("没有任何标记");
        assert_eq!("没有任何标记", m.plain);
        assert!(m.bold_ranges.is_empty());
    }

    #[test]
    fn lone_marker_kept_verbatim() {
        let m = parse("前面**后面没有配对");
        assert_eq!("前面**后面没有配对", m.plain);
        assert!(m.bold_ranges.is_empty());
    }

    #[test]
    fn empty_marker_dropped_without_range() {
        let m = parse("a****b");
        assert_eq!("ab", m.plain);
        assert!(m.bold_ranges.is_empty());
    }

    #[test]
    fn marker_at_end_still_paired() {
        let m = parse("结尾**加粗**");
        assert_eq!("结尾加粗", m.plain);
        assert_eq!(vec![(2, 3)], m.bold_ranges);
    }
}
