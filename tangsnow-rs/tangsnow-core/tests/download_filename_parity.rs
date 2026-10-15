//! `DownloadRepo`（下载文件名）差分测试：用例与 JVM 侧 `DownloadFileNameTest.kt`
//! 同源（同输入 ⇒ 同输出）。这是「下载」链路上最容易出安全问题的环节，
//! 双侧锁定防止任一侧改动后口径漂移。

use tangsnow_core::download_filename::*;

/// 与 JVM 侧 MAX_BYTES 一致：文件系统单段 255 字节的保守取值
const MAX_BYTES: usize = 200;

fn utf8_len(s: &str) -> usize {
    s.len() // Rust String 即 UTF-8 字节串
}

// ------------------------------------------------------------- 清洗（安全边界）

#[test]
fn 双向文本控制符被删除() {
    // U+202E(RLO) 视觉反转骗过可执行文件判定（JVM 侧注释详述）
    let sneaky = "setup.apk\u{202E}txt.pdf";
    assert!(!sanitize_file_name(sneaky).contains('\u{202E}'));
    assert!(!is_executable_name(&sanitize_file_name(sneaky)));
    for cp in [0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069] {
        let raw = format!("a{}b.pdf", char::from_u32(cp).unwrap());
        assert_eq!(sanitize_file_name(&raw), "ab.pdf", "U+{cp:04X} 未被删除");
    }
}

#[test]
fn del_与_c0_之外的不可打印字符也被清掉() {
    assert_eq!(sanitize_file_name("a\u{007F}b.txt"), "ab.txt");
}

#[test]
fn 零宽字符族被删除() {
    for cp in [0x200B, 0x200C, 0x200D, 0x200E, 0x200F, 0xFEFF, 0x061C] {
        let raw = format!("evil.apk{}", char::from_u32(cp).unwrap());
        let cleaned = sanitize_file_name(&raw);
        assert_eq!(cleaned, "evil.apk", "U+{cp:04X} 未被删除");
        assert!(is_executable_name(&cleaned), "U+{cp:04X} 清洗后必须判为可执行");
    }
}

// ------------------------------------------------------------- 来源优先级

#[test]
fn content_disposition_优先于_url() {
    assert_eq!(
        parse_file_name(Some("attachment; filename=\"report.pdf\""), Some("https://a.com/other.zip")),
        "report.pdf"
    );
}

#[test]
fn content_disposition_无引号形式() {
    assert_eq!(parse_file_name(Some("attachment; filename=a.pdf"), None), "a.pdf");
}

#[test]
fn rfc5987_中文文件名被正确解码() {
    assert_eq!(
        parse_file_name(Some("attachment; filename*=UTF-8''%E6%8A%A5%E5%91%8A.pdf"), None),
        "报告.pdf"
    );
}

#[test]
fn rfc5987_charset_大小写不敏感() {
    assert_eq!(
        parse_file_name(Some("attachment; filename*=Utf-8''%E6%8A%A5%E5%91%8A.pdf"), None),
        "报告.pdf"
    );
}

#[test]
fn 参数名大小写不敏感() {
    assert_eq!(parse_file_name(Some("attachment; FILENAME=\"a.pdf\""), None), "a.pdf");
    assert_eq!(parse_file_name(Some("attachment; Filename=a.pdf"), None), "a.pdf");
    assert_eq!(
        parse_file_name(Some("attachment; Filename*=utf-8''%E6%8A%A5%E5%91%8A.pdf"), None),
        "报告.pdf"
    );
}

#[test]
fn 无头部时取_url_末段() {
    assert_eq!(parse_file_name(None, Some("https://a.com/dir/file.zip")), "file.zip");
}

#[test]
fn url_末段的查询串被剥离() {
    assert_eq!(
        parse_file_name(None, Some("https://a.com/report.pdf?token=secret&x=1")),
        "report.pdf"
    );
}

#[test]
fn url_末段里的百分号编码被解码() {
    assert_eq!(parse_file_name(None, Some("https://a.com/%E6%8A%A5%E5%91%8A.pdf")), "报告.pdf");
}

#[test]
fn 片段标识符被剥离() {
    assert_eq!(parse_file_name(None, Some("https://a.com/a.pdf#page=2")), "a.pdf");
}

#[test]
fn 完全拿不到名字时用兜底名() {
    assert_eq!(parse_file_name(None, None), "download");
    assert_eq!(parse_file_name(None, Some("")), "download");
    assert_eq!(parse_file_name(Some("attachment"), Some("https://a.com")), "download");
    assert_eq!(parse_file_name(Some("attachment"), Some("https://a.com/")), "download");
}

// ------------------------------------------------------------- 清洗（安全边界）

#[test]
fn 路径分隔符被替换() {
    let name = parse_file_name(Some("attachment; filename=\"../../etc/passwd\""), None);
    assert!(!name.contains('/'));
    assert!(!name.contains('\\'));
}

#[test]
fn windows_非法字符被替换() {
    let name = parse_file_name(Some("attachment; filename=\"a:b*c?d\""), None);
    for ch in [':', '*', '?'] {
        assert!(!name.contains(ch), "不得含 {ch}");
    }
}

#[test]
fn 控制字符被替换() {
    let name = parse_file_name(Some("attachment; filename=\"a\u{0001}b.txt\""), None);
    assert!(!name.contains('\u{0001}'));
}

#[test]
fn 结尾的点与空格被去掉() {
    assert_eq!(parse_file_name(Some("attachment; filename=\"a... \""), None), "a");
}

#[test]
fn 超长文件名被截断且保留扩展名() {
    let long = format!("{}{}.pdf", "x".repeat(500), "");
    let name = parse_file_name(Some(&format!("attachment; filename=\"{long}\"")), None);
    assert!(utf8_len(&name) <= MAX_BYTES, "字节数必须受限");

    let apk = parse_file_name(Some(&format!("attachment; filename=\"{}.apk\"", "x".repeat(400))), None);
    assert!(utf8_len(&apk) <= MAX_BYTES);
    assert!(apk.ends_with(".apk"), "扩展名必须保留，实际 {apk}");
    assert!(is_executable_name(&apk), "截断后必须仍判为可执行");
}

#[test]
fn 超长中文名按字节截断() {
    let cjk150: String = "中".repeat(150);
    let name = parse_file_name(Some(&format!("attachment; filename=\"{cjk150}.pdf\"")), None);
    assert!(utf8_len(&name) <= MAX_BYTES, "450 字节必须被截到 {MAX_BYTES} 内");
    assert!(name.ends_with(".pdf"), "扩展名必须保留");
    assert!(name.contains('中'), "截断后仍应是可读的中文名");
}

#[test]
fn 按字节截断不得劈开多字节字符() {
    // Rust 侧的等价风险：字节切片若不按 char 边界对齐会 panic（is_char_boundary）
    let cjk150: String = "中".repeat(150);
    let cjk = parse_file_name(Some(&format!("attachment; filename=\"{cjk150}.txt\"")), None);
    assert!(!cjk.contains('\u{FFFD}'), "不得出现替换字符");
    assert!(cjk.chars().all(|c| c != '\u{FFFD}'));

    let emoji80: String = "🎉".repeat(80);
    let emoji = parse_file_name(Some(&format!("attachment; filename=\"{emoji80}.apk\"")), None);
    assert!(utf8_len(&emoji) <= MAX_BYTES);
    assert!(emoji.ends_with(".apk"));
    assert!(is_executable_name(&emoji));
    // 成对 emoji 按码点数收敛：MAX_BYTES 内应恰好 49 个完整 emoji + ".apk"
    let n = emoji.chars().count() - ".apk".len();
    assert_eq!(n, 49, "49×4=196B + 4B = 200B 上限");
}

#[test]
fn 短名与边界名一字不动() {
    assert_eq!(parse_file_name(Some("attachment; filename=\"报告.pdf\""), None), "报告.pdf");
    let exact = format!("{}{}", "a".repeat(196), ".pdf"); // 恰 200 字节
    assert_eq!(parse_file_name(Some(&format!("attachment; filename=\"{exact}\"")), None), exact);
}

#[test]
fn 清洗后为空则回到兜底名() {
    assert_eq!(parse_file_name(Some("attachment; filename=\"...\""), None), "download");
}

// ------------------------------------------------------------- 辅助函数

#[test]
fn percent_decode_只解百分号不把加号当空格() {
    assert_eq!(percent_decode("a%20b"), "a b");
    assert_eq!(percent_decode("a+b"), "a+b");
    assert_eq!(percent_decode("%E4%B8%AD"), "中");
    assert_eq!(percent_decode("100%zz"), "100%zz");
    assert_eq!(percent_decode("50%"), "50%");
}

#[test]
fn last_path_segment_of_与_url_结构一致() {
    assert_eq!(last_path_segment_of("https://a.com/b/c.pdf"), Some("c.pdf".to_string()));
    assert_eq!(last_path_segment_of("https://a.com/b/c.pdf?q=1#f"), Some("c.pdf".to_string()));
    assert_eq!(last_path_segment_of("https://a.com/"), Some("".to_string()));
}

#[test]
fn 安装包与可执行文件被识别() {
    for ext in ["apk", "aab", "exe", "msi", "bat", "sh", "jar", "dmg"] {
        assert!(is_executable_name(&format!("f.{ext}")), "{ext} 应判为可执行");
        assert!(is_executable_name(&format!("f.{ext}").to_uppercase()), "大写 {ext} 应判为可执行");
    }
}

#[test]
fn 普通文档与无扩展名不被误判() {
    for name in ["a.pdf", "a.txt", "a.zip", "README", "报告"] {
        assert!(!is_executable_name(name), "{name} 不应判为可执行");
    }
}
