//! `DownloadRepo`（下载文件名）差分测试。
//!
//! 这是「下载」链路上最容易出安全问题的环节（文件名直接落盘）：
//! 既要正确解出服务端给的名字（含 RFC 5987 的中文名），
//! 又绝不能把路径分隔符、控制字符或结尾点带进文件名。
//!
//! ## 期望值来自**两侧共享的 JSON 夹具**（2026-10-20 审查报告 C-14）
//!
//! 此前这里与 JVM 侧 `DownloadFileNameTest.kt` **各写一份期望值** —— 那是**镜像**：
//! 改实现 + 同步改本侧的镜像期望值 ⇒ 两侧照样全绿，而两边的清洗口径已经漂了
//! （「可执行文件一律强确认」被绕过这类后果，正是靠两侧口径一致才没发生）。
//! 现在两边读**同一份**夹具（`tests/fixtures/download_filename_cases.json`）⇒ 改夹具必然两侧一起红。
//!
//! 「超长名」那几条是**性质**（字节上限 / 扩展名保留 / emoji 计数），两侧各留一条性质用例，不进夹具。

mod common;

use tangsnow_core::download_filename::*;

/// 与 JVM 侧 MAX_BYTES 一致：文件系统单段 255 字节的保守取值
const MAX_BYTES: usize = 200;

/// 共享夹具（编译期读入；路径相对本文件）。
const CASES: &str = include_str!("fixtures/download_filename_cases.json");

#[test]
fn 夹具里的每条用例两侧同口径() {
    let v = common::load(CASES);
    for c in v["cases"].as_array().unwrap() {
        let id = c["id"].as_str().expect("id 必须是字符串");
        let kind = c["kind"].as_str().expect("kind 必须是字符串");
        let a = c["args"].as_array().expect("args 必须是数组");
        let want = c.get("expected").and_then(|e| e.as_str());
        match kind {
            "parseFileName" => {
                let got = parse_file_name(a[0].as_str(), a[1].as_str());
                assert_eq!(
                    got,
                    want.expect("parseFileName 的 expected 必须是字符串"),
                    "夹具用例「{id}」"
                );
            }
            "sanitizeFileName" => {
                let got = sanitize_file_name(a[0].as_str().expect("args[0] 必须是字符串"));
                assert_eq!(
                    got,
                    want.expect("sanitizeFileName 的 expected 必须是字符串"),
                    "夹具用例「{id}」"
                );
            }
            "percentDecode" => {
                let got = percent_decode(a[0].as_str().expect("args[0] 必须是字符串"));
                assert_eq!(
                    got,
                    want.expect("percentDecode 的 expected 必须是字符串"),
                    "夹具用例「{id}」"
                );
            }
            "lastPathSegmentOf" => {
                let got = last_path_segment_of(a[0].as_str().expect("args[0] 必须是字符串"));
                assert_eq!(got.as_deref(), want, "夹具用例「{id}」");
            }
            other => panic!("夹具里的 kind「{other}」本文件不认（改夹具要同步两侧的派发）"),
        }
    }
}

// ------------------------------------------------------------- 清洗（安全边界）

#[test]
fn 双向文本控制符被删除() {
    // U+202E(RLO) 视觉反转骗过可执行文件判定（JVM 侧注释详述）
    let sneaky = "setup.apk\u{202E}txt.pdf";
    assert!(!sanitize_file_name(sneaky).contains('\u{202E}'));
    assert!(!is_executable_name(&sanitize_file_name(sneaky)));
}

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

// ------------------------------------------------------------- 辅助函数

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

fn utf8_len(s: &str) -> usize {
    s.len() // Rust String 即 UTF-8 字节串
}
