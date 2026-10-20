//! 法律文本的 `**…**` 加粗标记解析（纯函数）。
//!
//! Kotlin 源：`util/LegalText.kt` 的 `parse`（`emphasize` 依赖 Spannable，不移植；
//! Rust 侧返回正文与字符区间，渲染由调用方处理）。
//!
//! 规则：只认**成对**的 `**`；落单的标记连同其后的正文**原样保留**；
//! 空标记（`****`）被丢弃且不产生区间。区间为 **UTF-16 码元索引**的闭区间
//! —— 与 Kotlin 的 `String.length`、`IntRange` 以及 `Spannable` 的下标**同一口径**
//! （2026-10-20 修正，见下）。
//!
//! ## 为什么是 UTF-16 而不是码点
//!
//! 这些区间唯一的消费者是 Kotlin 的 `Spannable.setSpan(...)`，而它的下标按
//! **UTF-16 码元**计（emoji 这类字符是代理对 = 2 个码元）。
//! 本实现原先按 Rust `char`（**码点**）计，遇到代理对就会整段左移 ——
//! Kotlin 侧于是把加粗套到「代理对后半截 + 下一个字」上，且**不报错**。
//! 当时靠一句注释兜着：「法律文本不含此类字符」—— 而那只是**当时**的事实，
//! 没有任何断言护着（2026-10-20 审查报告 B-1）。现在改成与 Kotlin 同口径，
//! 这个前提就不再需要成立了。

pub struct Marked {
    pub plain: String,
    /// 应加粗的区间（闭区间 first..=last，按 **UTF-16 码元**计）
    pub bold_ranges: Vec<(usize, usize)>,
}

/// Kotlin `LegalText.parse` 的对齐实现。
pub fn parse(raw: &str) -> Marked {
    let marker = "**";
    let mut plain = String::with_capacity(raw.len());
    let mut bold_ranges = Vec::new();
    let mut utf16_len = 0usize; // plain 侧已写入的 **UTF-16 码元**数
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
        // 对齐 Kotlin `plain.length`：前缀写入后立即累计长度。
        // ⚠️ 必须按 **UTF-16 码元**计（`encode_utf16().count()`），不能按 `chars().count()`：
        //    后者是**码点**，代理对字符（emoji）会让区间整段左移 ⇒ Kotlin 侧加粗落到错字上。
        utf16_len += raw[cursor..open].encode_utf16().count();
        let start = utf16_len;
        let body = &raw[open + marker.len()..close];
        plain.push_str(body);
        utf16_len += body.encode_utf16().count();
        // Kotlin `if (plain.length > start)`：非空区间才记录（空标记被丢弃）
        if utf16_len > start {
            bold_ranges.push((start, utf16_len - 1));
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

    #[test]
    fn astral_char_keeps_utf16_indices() {
        // 「a😀**bc**d」：emoji 是代理对（1 个码点、**2 个 UTF-16 码元**）。
        // Kotlin 的 `String.length` 与 `Spannable` 下标都是 UTF-16 码元 ⇒ 加粗区间是 3..4。
        // 若按码点计会得到 2..3 ⇒ Kotlin 侧会把加粗套到「代理对后半 + b」上（且不报错）。
        let m = parse("a😀**bc**d");
        assert_eq!("a😀bcd", m.plain);
        assert_eq!(vec![(3, 4)], m.bold_ranges);
    }
}
