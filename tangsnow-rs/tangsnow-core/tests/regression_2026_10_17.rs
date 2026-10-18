//! 2026-10-17 回归批：把当轮修掉的跨语言口径缺陷逐条钉成红灯。
//!
//! 每条测试的注释里写明「旧实现会给出什么、为什么错」，便于日后有人质疑旧行为时
//! 一眼看到当时的复现。涉及修复：
//!   1. `java_is_whitespace` 集合（4 个码点不等价）+ `java_trim` 的连带影响；
//!   2. `display_for_url` 的「取首个同名参数」语义；
//!   3. `is_newer` 段解析按 Kotlin `toIntOrNull`（i32）；
//!   4. `pick_asset` 的 ABI 大小写不敏感匹配；
//!   5. `LOCALHOST_RE` 的 ASCII-only 大小写折叠与 `[0-9]` 端口；
//!   6. `URL_IN_TEXT_RE` 的 scheme 判定同样只折叠 ASCII。

use tangsnow_core::java_is_whitespace;
use tangsnow_core::java_trim;
use tangsnow_core::search_engines::{builtins, display_for_url};
use tangsnow_core::update_logic::{is_newer, pick_asset};
use tangsnow_core::url_utils::{extract_url_from_text, looks_like_uri};

/// Kotlin `Char.isWhitespace()` = `Character.isWhitespace(c) || Character.isSpaceChar(c)`，
/// 与 Rust `char::is_whitespace`（Unicode `White_Space`）的全部差异恰好是这 4 个码点。
///
/// 旧实现反向：多减了 Zs 一族（U+00A0/U+2007/U+202F 在 Kotlin 侧**算**空白），又多留了 NEL。
#[test]
fn java_whitespace_matches_kotlin_union_set() {
    for c in ['\u{00A0}', '\u{2007}', '\u{202F}'] {
        assert!(java_is_whitespace(c), "U+{:04X} 属 Zs，isSpaceChar 判真", c as u32);
    }
    assert!(!java_is_whitespace('\u{0085}'), "U+0085 NEL：Java 两个判定都判假");
    for c in '\u{001C}'..='\u{001F}' {
        assert!(java_is_whitespace(c), "U+{:04X} 信息分隔符：Java 判真", c as u32);
    }
    // 两侧本就一致的点不能被改坏
    for c in ['\t', '\n', '\u{0B}', '\u{0C}', '\r', ' '] {
        assert!(java_is_whitespace(c), "U+{:04X} 两边都是空白", c as u32);
    }
    for c in ['a', '0', '\u{200B}', '\u{FEFF}'] {
        assert!(!java_is_whitespace(c), "U+{:04X} 非 isWhitespace 亦非 isSpaceChar", c as u32);
    }
}

/// `String.trim()` 走 `isWhitespace()` ⇒ 不换行空格也应被剥掉。
#[test]
fn java_trim_strips_nbsp() {
    assert_eq!(java_trim("\u{00A0}x\u{00A0}"), "x");
    assert_eq!(java_trim("\u{202F}y\u{202F}"), "y");
    // NEL 不在集合里，不该被剥
    assert_eq!(java_trim("\u{0085}z\u{0085}"), "\u{0085}z\u{0085}");
}

/// Kotlin 用 `Uri.getQueryParameter(name)` —— 只取**第一个**同名参数。
///
/// 旧 Rust 遍历全部 `query_pairs`，于是 `q=&q=hello` 会回显 `hello`；主线拿到的是空串，
/// 按 `isNullOrBlank` 判为「没有关键词」。
#[test]
fn display_for_url_uses_first_query_param_only() {
    let engines = builtins();
    assert_eq!(
        display_for_url("https://cn.bing.com/search?q=&q=hello", engines),
        None,
        "第一个 q 为空 ⇒ 不采纳后面的 q"
    );
    assert_eq!(
        display_for_url("https://cn.bing.com/search?q=hello&q=world", engines).as_deref(),
        Some("hello"),
        "取第一个同名参数"
    );
    assert_eq!(
        display_for_url("https://cn.bing.com/search?q=rust", engines).as_deref(),
        Some("rust")
    );
    // 结果页以外的地址不回显
    assert_eq!(display_for_url("https://cn.bing.com/", engines), None);
}

/// Kotlin `toIntOrNull()` 溢出即 null ⇒ `isNewer` 返回 false。
///
/// 旧 Rust 用 i64 解析：`3000000000.0` 能解出来，会被判成「有更新」而把用户引到
/// 一个不该出现的升级提示上（虽然真实 tag 不会这么大，但口径必须一致）。
#[test]
fn is_newer_segments_parse_like_kotlin_int() {
    assert!(!is_newer("3000000000.0", "1.0"), "超 i32 的段 Kotlin 解不动 ⇒ false");
    assert!(!is_newer("2147483648", "1"), "i32::MAX+1 同理");
    assert!(is_newer("2147483647", "1"), "i32::MAX 本身可解");
    // 常规链路不受影响
    assert!(is_newer("2.10.0", "2.9.0"));
    assert!(!is_newer("2.1", "2.1.0"), "缺段补 0 ⇒ 相等不算更新");
    assert!(!is_newer("garbage", "1.0"));
}

/// Kotlin `contains("-${abi}-", ignoreCase = true)`：资产名与 ABI 串都忽略大小写。
///
/// 旧 Rust 只把资产名 `to_ascii_lowercase()`，needle 没跟着小写 ⇒ 传大写 ABI 时全落空。
#[test]
fn pick_asset_matches_abi_case_insensitively() {
    let assets = "app-arm64-v8a-release.apk\napp-universal-release.apk";
    assert_eq!(
        pick_asset(assets, "ARM64-V8A").as_deref(),
        Some("app-arm64-v8a-release.apk")
    );
    assert_eq!(
        pick_asset(assets, "x86_64"),
        None,
        "无匹配 ABI ⇒ None（universal 名里不含 -x86_64-）"
    );
    // 顺序按 abis 给定顺序，而不是 assets 顺序
    let two = "a-arm64-v8a-rel.apk\nb-x86_64-rel.apk";
    assert_eq!(pick_asset(two, "x86_64\narm64-v8a").as_deref(), Some("b-x86_64-rel.apk"));
    // 先按 .apk 过滤（忽略大小写）
    assert_eq!(
        pick_asset("app-arm64-v8a-REL.APK", "arm64-v8a").as_deref(),
        Some("app-arm64-v8a-REL.APK")
    );
    // 口径是 `-{abi}-`：ABI 两侧都要有 `-`，`…-v8a.apk` 不命中
    assert_eq!(pick_asset("x-arm64-v8a.APK", "arm64-v8a"), None);
    assert_eq!(pick_asset("notes.txt", "arm64-v8a"), None);
}

/// Java `Pattern.CASE_INSENSITIVE`（未开 `UNICODE_CASE`）只折叠 ASCII。
///
/// Rust `(?i)` 默认走 Unicode 简单折叠（`ſ`(U+017F)↔`s`），旧实现会把 `localhoſt` 判成
/// 本机地址；同理端口位置上的 `\d` 是 Unicode `\p{Nd}`，会把全角数字当端口。
#[test]
fn localhost_detection_stays_ascii_like_java() {
    assert!(!looks_like_uri("localho\u{017F}t"), "U+017F 不折叠 ⇒ 不是 localhost");
    assert!(looks_like_uri("localhost"));
    assert!(looks_like_uri("LOCALHOST"), "ASCII 大小写照旧折叠");
    assert!(looks_like_uri("https://localhost:8443/x"));
    assert!(looks_like_uri("localhost:8080"));
    assert!(!looks_like_uri("localhost:\u{FF18}\u{FF10}"), "全角数字不是 [0-9]");
}

/// `URL_IN_TEXT_RE` 的 `https?` 同样只折叠 ASCII：旧实现会把 `httpſ://` 当成 `https://`，
/// 从分享文本里提一条根本不存在的链接。
#[test]
fn extract_url_scheme_folds_ascii_only() {
    assert_eq!(extract_url_from_text("http\u{017F}://example.com"), None);
    assert_eq!(
        extract_url_from_text("看这个 https://example.com/a?b=1 。").as_deref(),
        Some("https://example.com/a?b=1")
    );
    // scheme 的 ASCII 大小写仍忽略
    assert_eq!(
        extract_url_from_text("HTTP://EXAMPLE.COM/x").as_deref(),
        Some("HTTP://EXAMPLE.COM/x")
    );
}