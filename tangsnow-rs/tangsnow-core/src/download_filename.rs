//! 下载文件名解析、清洗与 MIME 归一（安全关键纯函数）。
//!
//! Kotlin 源：`data/repo/DownloadRepo.kt` 的 `parseFileName` / `sanitizeFileName` /
//! `truncateKeepingExtension` / `isExecutableName` / `percentDecode` /
//! `lastPathSegmentOf` / `bareMimeType`（其余成员依赖 Context/DownloadManager，不移植）。
//!
//! 口径要点：
//! - 所有来自 Kotlin 的 `\s` 都写成 ASCII 白空类（skill 坑①：Java 默认 `\s` 不含 Unicode 空白）；
//! - `percentDecode` 与 `android.net.Uri.decode` 一致：只解 `%XX`、**不**把 `+` 当空格、
//!   非法序列原样保留、按**码点**整块处理（星平面字符不拆代理）；
//! - 限长量纲是 **UTF-8 字节**且按码点整块收（P3-5），扩展名必须保留（防可执行警示绕过）。

use regex::Regex;
use std::sync::LazyLock;

use crate::java_trim;

pub const MAX_FILE_NAME_BYTES: usize = 200;
pub const DEFAULT_FILE_NAME: &str = "download";

/// 结构性非法字符 → `_`（含 C0 控制段）
static ILLEGAL_FILE_CHARS: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r#"[/\\:*?"<>|\x00-\x1F]"#).unwrap());

/// 不可见/双向字符族：DEL、零宽与连接符、双向控制符、BOM、阿拉伯序标记——**删除**而非替换：
/// 替换成 `_` 会留下带 `_` 的伪扩展名，可执行警示仍可被零宽字符绕过（外部审查 P4）。
///
/// 2026-10-15 追加 Unicode 类别 `\p{Cf}`/`\p{Cc}`/`\p{Zl}`/`\p{Zp}`：上面那串是**码位清单**，
/// 而 U+2060 WORD JOINER、U+00AD 软连字符、U+180E、U+08E2 都是 Cf，C1（U+0080–U+009F）都是 Cc，
/// 全不在清单里 ⇒ `evil.apk⁠` 照旧判不中。旧清单成员全部落在 Cc/Cf 内，故这是**严格超集**，
/// 不会少删任何一个。刻意不加 `\p{Zs}`：普通空格 U+0020 就在 Zs 里，删它等于改坏正常文件名。
/// 与主包 `DownloadRepo.INVISIBLE_FILE_CHARS` 同口径（2026-10-15 两侧一起改）。
static INVISIBLE_FILE_CHARS: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(
        r"[\u{007F}\u{200B}-\u{200F}\u{202A}-\u{202E}\u{2066}-\u{2069}\u{FEFF}\u{061C}]|\p{Cf}|\p{Cc}|\p{Zl}|\p{Zp}",
    )
    .unwrap()
});

/// 结尾的空白与点（Windows 静默丢弃或造成路径穿越）。`\s` 用 ASCII 白空类。
static TRAILING_SPACE_OR_DOT: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"[ \t\n\x0B\x0C\r.]+$").unwrap());

/// RFC 5987：`filename*=UTF-8''…`（参数名与 charset 均大小写不敏感）
static RFC5987_FILENAME: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?i)filename\*[ \t\n\x0B\x0C\r]*=[ \t\n\x0B\x0C\r]*UTF-8''[ \t\n\x0B\x0C\r]*"?([^";]+)"?"#)
        .unwrap()
});

/// 普通形式：`filename="a.pdf"` / `filename=a.pdf`
static PLAIN_FILENAME: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r#"(?i)filename[ \t\n\x0B\x0C\r]*=[ \t\n\x0B\x0C\r]*"?([^";]+)"?"#).unwrap());

/// 可执行 / 安装类扩展名（取舍结论见 Kotlin 侧注释：`xpi`/`crx` 刻意不收）
const EXECUTABLE_EXTENSIONS: &[&str] = &[
    "apk", "aab", "apks", "xapk", "apkm", "dex", "ipa", "exe", "msi", "msp", "bat", "cmd", "com",
    "scr", "jar", "vbs", "vbe", "ps1", "sh", "deb", "rpm", "dmg", "pkg", "appimage",
];

/// 解析下载文件名（Content-Disposition 优先，其次 URL 末段）。
pub fn parse_file_name(content_disposition: Option<&str>, url: Option<&str>) -> String {
    let from_header = content_disposition.and_then(|header| {
        RFC5987_FILENAME
            .captures(header)
            .and_then(|c| c.get(1))
            .map(|m| m.as_str().to_string())
            .or_else(|| {
                PLAIN_FILENAME.captures(header).and_then(|c| c.get(1)).map(|m| {
                    crate::java_trim(m.as_str())
                        .trim_matches('"')
                        .to_string()
                })
            })
            .filter(|s| !s.chars().all(crate::java_is_whitespace) && !s.is_empty())
    });

    let raw = from_header
        .or_else(|| last_path_segment_of(url.unwrap_or_default()))
        .unwrap_or_default();

    let source = if raw.chars().all(crate::java_is_whitespace) {
        DEFAULT_FILE_NAME.to_string()
    } else {
        raw
    };
    sanitize_file_name(&percent_decode(&source))
}

/// 文件名清洗：删不可见 → 非法字符替换 `_` → 删结尾点/空格 → trim → 限长（保扩展名）。
pub fn sanitize_file_name(raw: &str) -> String {
    let cleaned = INVISIBLE_FILE_CHARS.replace_all(raw, "").into_owned();
    let cleaned = ILLEGAL_FILE_CHARS.replace_all(&cleaned, "_").into_owned();
    let cleaned = TRAILING_SPACE_OR_DOT.replace(&cleaned, "").into_owned();
    let cleaned = java_trim(&cleaned);
    let cleaned = truncate_keeping_extension(cleaned, MAX_FILE_NAME_BYTES);
    if cleaned.chars().all(crate::java_is_whitespace) {
        DEFAULT_FILE_NAME.to_string()
    } else {
        cleaned
    }
}

/// 限长（UTF-8 字节）但**保留扩展名**，按码点整块收，绝不在多字节字符中间截断。
fn truncate_keeping_extension(name: &str, max_bytes: usize) -> String {
    if utf8_len(name) <= max_bytes {
        return name.to_string();
    }
    // Kotlin `lastIndexOf('.')` 按 UTF-16 索引；Rust `rfind('.')` 按字节——
    // 对「找最后一个 ASCII 点」语义一致（'.' 是单字节，不会落在多字节序列内部）。
    if let Some(dot) = name.rfind('.') {
        let dot_is_valid = dot > 0 && dot < name.len() - 1;
        if dot_is_valid {
            let ext = &name[dot..];
            let ext_bytes = utf8_len(ext);
            if ext_bytes < max_bytes {
                let head = take_within_utf8_bytes(&name[..dot], max_bytes - ext_bytes);
                if !head.is_empty() {
                    return format!("{head}{ext}");
                }
            }
        }
    }
    take_within_utf8_bytes(name, max_bytes)
}

/// 取前缀，保证 UTF-8 字节数不超 maxBytes；按码点整块收（代理对/多字节不拆开）。
fn take_within_utf8_bytes(name: &str, max_bytes: usize) -> String {
    if max_bytes == 0 {
        return String::new();
    }
    let mut bytes = 0usize;
    let mut end = 0usize;
    for (i, c) in name.char_indices() {
        let width = utf8_width(c);
        if bytes + width > max_bytes {
            break;
        }
        bytes += width;
        end = i + c.len_utf8();
    }
    name[..end].to_string()
}

/// 字符串的 UTF-8 字节数（`&str` 本身即 UTF-8）
fn utf8_len(s: &str) -> usize {
    s.len()
}

/// 单个码点的 UTF-8 字节数（RFC 3629：1 / 2 / 3 / 4）
fn utf8_width(c: char) -> usize {
    c.len_utf8()
}

/// 文件名是否为可执行 / 安装类（取**最后一段**扩展名，大小写不敏感）。
pub fn is_executable_name(file_name: &str) -> bool {
    // Kotlin `substringAfterLast('.', "")`：无 '.' 时返回空串 ⇒ 判 false
    let ext = file_name.rsplit('.').next().unwrap_or("");
    if file_name.rfind('.').is_none() {
        return false;
    }
    let ext = ext.to_lowercase();
    !ext.is_empty() && EXECUTABLE_EXTENSIONS.contains(&ext.as_str())
}

/// 百分号解码：只解 `%XX`，**不**把 `+` 当空格；非法序列原样保留；按码点整块。
pub fn percent_decode(raw: &str) -> String {
    if !raw.contains('%') {
        return raw.to_string();
    }
    let mut out: Vec<u8> = Vec::with_capacity(raw.len());
    let mut chars = raw.chars().peekable();
    while let Some(ch) = chars.next() {
        if ch == '%' {
            // Kotlin 边界是 `i + 2 < raw.length`（char 索引），语义 = 「后面还剩至少两个 char」；
            // Rust 侧用 peek 二连——任意两个 char（含星平面）都算完整候选。
            let hex2: Option<(char, char)> = (|| {
                let hi = chars.peek().copied()?;
                let lo = chars.clone().nth(1)?;
                Some((hi, lo))
            })();
            if let Some((hi, lo)) = hex2 {
                // Kotlin 侧 2026-10-24 起也用纯 ASCII 判定（原 `Character.digit(c, 16)` 对
                // Unicode 十进制数字同样返回数值，两侧对同一响应会产出不同文件名）：
                // '0'-'9' → 0-9，'a'-'f'/'A'-'F' → 10-15，其余 -1。
                let digit = |c: char| -> i32 {
                    match c {
                        '0'..='9' => c as u8 as i32 - b'0' as i32,
                        'a'..='f' => c as u8 as i32 - b'a' as i32 + 10,
                        'A'..='F' => c as u8 as i32 - b'A' as i32 + 10,
                        _ => -1,
                    }
                };
                let hv = digit(hi);
                let lv = digit(lo);
                if hv >= 0 && lv >= 0 {
                    out.push(((hv << 4) | lv) as u8);
                    chars.next(); // 消费 hi
                    chars.next(); // 消费 lo
                    continue;
                }
            }
        }
        // 按码点写（Rust 的 &str 迭代天然按码点，星平面字符不拆代理）
        let mut buf = [0u8; 4];
        out.extend_from_slice(ch.encode_utf8(&mut buf).as_bytes());
    }
    // Kotlin `String(bytes, UTF_8)` 对无效字节做替换；Rust lossy 同语义（U+FFFD）
    String::from_utf8_lossy(&out).into_owned()
}

/// 取 URL 路径的最后一段（`Uri.lastPathSegment` 的纯实现）。
pub fn last_path_segment_of(url: &str) -> Option<String> {
    if url.chars().all(crate::java_is_whitespace) {
        return None;
    }
    let no_fragment = url.split('#').next().unwrap_or(url);
    let no_query = no_fragment.split('?').next().unwrap_or(no_fragment);
    let after_scheme = match no_query.find("://") {
        Some(i) => &no_query[i + 3..],
        None => no_query,
    };
    let path_start = after_scheme.find('/')?;
    Some(
        after_scheme[path_start + 1..]
            .rsplit('/')
            .next()
            .unwrap_or("")
            .to_string(),
    )
}

/// MIME 归一：剥掉 `;` 之后的参数并 trim；空值 / 只有参数 ⇒ None。
pub fn bare_mime_type(raw: Option<&str>) -> Option<String> {
    raw.and_then(|r| r.split(';').next())
        .map(|s| s.trim())
        .filter(|s| !s.is_empty())
        .map(str::to_string)
}

#[cfg(test)]
mod tests {
    use super::*;

    // ---- 与 JVM 单测 DownloadFileNameTest 逐条对齐 ----

    #[test]
    fn bidi_controls_deleted_so_exec_check_cannot_be_tricked() {
        let sneaky = "setup.apk\u{202E}txt.pdf";
        assert!(!sanitize_file_name(sneaky).contains('\u{202E}'));
        assert!(!is_executable_name(&sanitize_file_name(sneaky)));
        for cp in [0x202Au32, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069] {
            let raw = format!("a{}b.pdf", char::from_u32(cp as u32).unwrap());
            assert_eq!("ab.pdf", sanitize_file_name(&raw), "U+{cp:04X} 未被删除");
        }
    }

    #[test]
    fn del_also_cleaned() {
        assert_eq!("ab.txt", sanitize_file_name("a\u{007F}b.txt"));
    }

    #[test]
    fn zero_width_family_deleted_exec_warning_not_bypassed() {
        for cp in [0x200Bu32, 0x200C, 0x200D, 0x200E, 0x200F, 0xFEFF, 0x061C] {
            let cleaned = sanitize_file_name(&format!("evil.apk{}", char::from_u32(cp).unwrap()));
            assert_eq!("evil.apk", cleaned, "U+{cp:04X} 未被删除");
            assert!(is_executable_name(&cleaned), "U+{cp:04X} 清洗后必须判为可执行");
        }
    }

    #[test]
    fn content_disposition_wins_over_url() {
        assert_eq!(
            "report.pdf",
            parse_file_name(
                Some("attachment; filename=\"report.pdf\""),
                Some("https://a.com/other.zip"),
            )
        );
    }

    #[test]
    fn content_disposition_unquoted() {
        assert_eq!("a.pdf", parse_file_name(Some("attachment; filename=a.pdf"), None));
    }

    #[test]
    fn rfc5987_chinese_decoded() {
        assert_eq!(
            "报告.pdf",
            parse_file_name(
                Some("attachment; filename*=UTF-8''%E6%8A%A5%E5%91%8A.pdf"),
                None,
            )
        );
    }

    #[test]
    fn rfc5987_charset_case_insensitive() {
        assert_eq!(
            "报告.pdf",
            parse_file_name(
                Some("attachment; filename*=Utf-8''%E6%8A%A5%E5%91%8A.pdf"),
                None,
            )
        );
    }

    #[test]
    fn content_disposition_param_case_insensitive() {
        assert_eq!("a.pdf", parse_file_name(Some("attachment; FILENAME=\"a.pdf\""), None));
        assert_eq!("a.pdf", parse_file_name(Some("attachment; Filename=a.pdf"), None));
    }

    #[test]
    fn rfc5987_param_case_insensitive() {
        assert_eq!(
            "报告.pdf",
            parse_file_name(
                Some("attachment; Filename*=utf-8''%E6%8A%A5%E5%91%8A.pdf"),
                None,
            )
        );
    }

    #[test]
    fn url_last_segment_when_no_header() {
        assert_eq!(
            "file.zip",
            parse_file_name(None, Some("https://a.com/dir/file.zip"))
        );
    }

    #[test]
    fn url_query_stripped() {
        assert_eq!(
            "report.pdf",
            parse_file_name(None, Some("https://a.com/report.pdf?token=secret&x=1"))
        );
    }

    #[test]
    fn url_percent_decoded() {
        assert_eq!("报告.pdf", parse_file_name(None, Some("https://a.com/%E6%8A%A5%E5%91%8A.pdf")));
    }

    #[test]
    fn fragment_stripped() {
        assert_eq!("a.pdf", parse_file_name(None, Some("https://a.com/a.pdf#page=2")));
    }

    #[test]
    fn fallback_when_nothing_works() {
        assert_eq!("download", parse_file_name(None, None));
        assert_eq!("download", parse_file_name(None, Some("")));
        assert_eq!("download", parse_file_name(Some("attachment"), Some("https://a.com")));
        assert_eq!("download", parse_file_name(Some("attachment"), Some("https://a.com/")));
    }

    #[test]
    fn path_separators_replaced_no_traversal() {
        let name = parse_file_name(Some("attachment; filename=\"../../etc/passwd\""), None);
        assert!(!name.contains('/'));
        assert!(!name.contains('\\'));
    }

    #[test]
    fn windows_illegal_chars_replaced() {
        let name = parse_file_name(Some("attachment; filename=\"a:b*c?d\""), None);
        for ch in [':', '*', '?'] {
            assert!(!name.contains(ch), "不得含 {ch}");
        }
    }

    #[test]
    fn control_chars_replaced() {
        let name = parse_file_name(Some("attachment; filename=\"a\u{0001}b.txt\""), None);
        assert!(!name.contains('\u{0001}'));
    }

    #[test]
    fn trailing_dots_and_spaces_removed() {
        assert_eq!("a", parse_file_name(Some("attachment; filename=\"a... \""), None));
    }

    fn utf8_len_of(s: &str) -> usize {
        s.len()
    }

    #[test]
    fn overlong_name_truncated() {
        let long = format!("{}{}.pdf", "x".repeat(500), "");
        let name = parse_file_name(Some(&format!("attachment; filename=\"{long}\"")), None);
        assert!(utf8_len_of(&name) <= MAX_FILE_NAME_BYTES);
    }

    #[test]
    fn overlong_truncation_keeps_extension() {
        let long = format!("{}.apk", "x".repeat(400));
        let name = parse_file_name(Some(&format!("attachment; filename=\"{long}\"")), None);
        assert!(utf8_len_of(&name) <= MAX_FILE_NAME_BYTES);
        assert!(name.ends_with(".apk"), "实际 {name}");
        assert!(is_executable_name(&name));

        let no_ext = parse_file_name(Some(&format!("attachment; filename=\"{}\"", "y".repeat(400))), None);
        assert!(utf8_len_of(&no_ext) <= MAX_FILE_NAME_BYTES);
    }

    #[test]
    fn overlong_cjk_truncated_by_bytes() {
        let cjk150: String = "中".repeat(150);
        let name = parse_file_name(Some(&format!("attachment; filename=\"{cjk150}.pdf\"")), None);
        assert!(utf8_len_of(&name) <= MAX_FILE_NAME_BYTES);
        assert!(name.ends_with(".pdf"));
        assert!(name.contains('中'));
    }

    #[test]
    fn byte_truncation_never_splits_codepoints() {
        let cjk150: String = "中".repeat(150);
        let cjk = parse_file_name(Some(&format!("attachment; filename=\"{cjk150}.txt\"")), None);
        assert!(!cjk.contains('\u{FFFD}'));

        let emoji80: String = "😀".repeat(80);
        let emoji = parse_file_name(Some(&format!("attachment; filename=\"{emoji80}.apk\"")), None);
        assert!(utf8_len_of(&emoji) <= MAX_FILE_NAME_BYTES);
        assert!(emoji.ends_with(".apk"));
        assert!(is_executable_name(&emoji));
        assert!(!emoji.contains('\u{FFFD}'));
        // 49 个完整 emoji（49×4=196B）+ ".apk" = 200B
        assert_eq!(
            49,
            emoji.chars().count() - ".apk".chars().count()
        );
    }

    #[test]
    fn short_and_boundary_names_untouched() {
        assert_eq!(
            "报告.pdf",
            parse_file_name(Some("attachment; filename=\"报告.pdf\""), None)
        );
        let exact = format!("{}{}.pdf", "a".repeat(196), "");
        assert_eq!(
            exact,
            parse_file_name(Some(&format!("attachment; filename=\"{exact}\"")), None)
        );
    }

    #[test]
    fn blank_after_sanitizing_falls_back() {
        assert_eq!("download", parse_file_name(Some("attachment; filename=\"...\""), None));
    }

    #[test]
    fn percent_decode_only_percent_plus_untouched() {
        assert_eq!("a b", percent_decode("a%20b"));
        assert_eq!("a+b", percent_decode("a+b"));
        assert_eq!("中", percent_decode("%E4%B8%AD"));
        assert_eq!("100%zz", percent_decode("100%zz"));
        assert_eq!("50%", percent_decode("50%"));
    }

    #[test]
    fn last_path_segment_matches_url_structure() {
        assert_eq!(
            Some("c.pdf".to_string()),
            last_path_segment_of("https://a.com/b/c.pdf")
        );
        assert_eq!(
            Some("c.pdf".to_string()),
            last_path_segment_of("https://a.com/b/c.pdf?q=1#f")
        );
        assert_eq!(Some(String::new()), last_path_segment_of("https://a.com/"));
        assert_eq!(None, last_path_segment_of("https://a.com"));
        assert_eq!(None, last_path_segment_of(""));
    }

    #[test]
    fn executables_recognized() {
        for it in [
            "app.apk", "APP.APK", "bundle.apks", "mod.xapk", "pack.apkm", "setup.exe",
            "installer.msi", "run.bat", "run.cmd", "script.ps1", "tool.jar", "macro.vbs",
            "pkg.deb", "pkg.rpm", "dmg.dmg", "lin.appimage",
        ] {
            assert!(is_executable_name(it), "应判为可执行: {it}");
        }
    }

    #[test]
    fn plain_documents_not_executable() {
        for it in [
            "report.pdf", "photo.jpeg", "data.csv", "note.txt", "book.epub", "archive.zip",
            "video.mp4", "page.html", "font.ttf", "noext", "", "trailing.", "中文名.文档",
        ] {
            assert!(!is_executable_name(it), "不应判为可执行: {it}");
        }
        // 双扩展名伪装：取最后一段，仍判可执行
        assert!(is_executable_name("invoice.pdf.apk"));
    }

    #[test]
    fn mime_params_stripped() {
        assert_eq!(
            Some("text/html".to_string()),
            bare_mime_type(Some("text/html; charset=utf-8"))
        );
        assert_eq!(
            Some("text/html".to_string()),
            bare_mime_type(Some("text/html;charset=UTF-8"))
        );
        assert_eq!(
            Some("application/pdf".to_string()),
            bare_mime_type(Some("  application/pdf  "))
        );
        assert_eq!(
            Some("image/jpeg".to_string()),
            bare_mime_type(Some("image/jpeg"))
        );
        assert_eq!(None, bare_mime_type(None));
        assert_eq!(None, bare_mime_type(Some("")));
        assert_eq!(None, bare_mime_type(Some("; charset=utf-8")));
    }

    #[test]
    fn 隐藏扩展名的格式控制字符按类别删除() {
        // 旧实现只列码位，U+2060/U+00AD/U+180E/U+08E2 都是 Cf、C1 都是 Cc，全不在清单里 ⇒
        // `evil.apk` 挂个不可见尾巴，末段扩展名就成了 `apk⁠`，强确认被肉眼不可见地绕过。
        for tail in ['\u{2060}', '\u{00AD}', '\u{180E}', '\u{08E2}', '\u{0085}', '\u{009F}'] {
            let clean = sanitize_file_name(&format!("evil.apk{tail}"));
            let cp = tail as u32;
            assert!(clean.ends_with(".apk"), "U+{cp:04X} 未被删除：{clean}");
            assert!(is_executable_name(&clean), "U+{cp:04X} 逃过了可执行判定：{clean}");
        }
        // 普通空格**不在**删除之列（Zs 刻意不加，U+0020 就在其中，删它是伤内容）
        assert_eq!(sanitize_file_name("季度 报告.pdf"), "季度 报告.pdf");
    }
}
