//! 崩溃日志的 URL 脱敏与文件名展示戳。
//!
//! Kotlin 源：`util/CrashLogger.kt` 的 `redactUrls` / `displayStamp`（纯字符串函数，
//! 其余成员依赖 Android Context/进程状态，不移植）。
//!
//! 口径要点：
//! - Java `\S` 默认 **ASCII** 白空语义（skill 坑①），Rust `\S` 默认 Unicode——
//!   此处显式写 `(?-u:\S+)` 与 java.util.regex 逐字节对齐（nbsp 等按 Java 口径计入 URL）。
//! - `redactUrls` 先截 authority（第一个 `/`?`#` 之前）**再**剥 userinfo，
//!   顺序不能反（路径里 `@` 不能误伤 host）。

use regex::Regex;
use std::sync::LazyLock;

/// Kotlin `Regex("""^(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})(\d{2})(?:-\d{3})?$""")`
static CRASH_NAME_STAMP: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"^(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})(\d{2})(?:-\d{3})?$").unwrap()
});

/// Kotlin `Regex("""([a-z][a-z0-9+.\-]*)://(\S+)""", IGNORE_CASE)`——`\S` 按库惯例写成
/// ASCII 空白补集（Java 默认 `\S` 语义，nbsp 等非 ASCII 字符计入 URL）。
/// 开头 `(?i-u)`：Java `IGNORE_CASE` 只折叠 ASCII（`ſ` 不等于 `s`），裸 `(?i)`
/// 的 Unicode 折叠会让 `httpſ://user:pw@…` 在 Rust 侧被脱敏、Kotlin 侧原样落盘 ——
/// 与 `url_utils::URL_IN_TEXT_RE` 同一坑，见 regression_2026_10_17。
/// rest 段用 `(?u:…)` 壳**包住捕获组**：`-u` 下裸取反类会退化成字节类（可匹配非法
/// UTF-8，`Regex::new` 直接拒），壳把字符级 Unicode 开回来；壳内必须是捕获组 ——
/// `redact_urls` 取 `caps[2]`，两个捕获组都不能少。
/// 该字面量受 `CrossLanguageLiteralParityTest` 的归一比对钉住（剥 `(?i-u)` 与 `(?u:)` 壳）。
static URL_IN_TEXT: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i-u)([a-z][a-z0-9+.\-]*)://(?u:([^ \t\n\x0B\x0C\r]+))").unwrap());

/// 崩溃日志**文件名** → 展示时间戳：`crash-20261001-121500-123.txt` → `2026-10-01 12:15:00`。
///
/// 毫秒段丢弃；无法解析的名单原样呈现（只去 `crash-` 前缀与 `.txt` 后缀）。
pub fn display_stamp(file_name: &str) -> String {
    let core = file_name.strip_prefix("crash-").unwrap_or(file_name);
    let core = core.strip_suffix(".txt").unwrap_or(core);
    match CRASH_NAME_STAMP.captures(core) {
        Some(m) => format!(
            "{}-{}-{} {}:{}:{}",
            &m[1], &m[2], &m[3], &m[4], &m[5], &m[6]
        ),
        None => core.to_string(),
    }
}

/// 写盘前的 URL 脱敏：文本里的 **`scheme://` 形式 URL** 收敛成 `scheme://host[:port]/…`。
///
/// - 覆盖范围刻意不限于 http(s)（`file:///…` 同样是「完整位置」）；
/// - `data:` 这类不带 `//` 的形式不匹配（无副作用）；
/// - 裸路径（无 scheme）刻意保留（排障价值更高）；
/// - 非 URL 文本一字不动。
pub fn redact_urls(text: &str) -> String {
    URL_IN_TEXT
        .replace_all(text, |caps: &regex::Captures| {
            let scheme = &caps[1];
            let rest = &caps[2];
            // 先截 authority（第一个 '/'|'?'|'#' 之前），再剥 userinfo：
            // userinfo 的 `@` 只在 authority 里合法；顺序反了会把 host 连路径前缀误剥。
            let authority_end = rest
                .find(['/', '?', '#'])
                .unwrap_or(rest.len());
            let authority = &rest[..authority_end];
            // Kotlin substringAfterLast('@')：无 `@` 时返回原串
            let host_port = match authority.rfind('@') {
                Some(i) => &authority[i + 1..],
                None => authority,
            };
            format!("{scheme}://{host_port}/…")
        })
        .into_owned()
}

#[cfg(test)]
mod tests {
    use super::*;

    // ---- redact_urls：与 JVM 单测 CrashLoggerRedactTest 逐条对齐 ----

    #[test]
    fn full_url_collapses_to_scheme_and_host() {
        assert_eq!(
            "java.io.FileNotFoundException: https://example.com/…",
            redact_urls("java.io.FileNotFoundException: https://example.com/a/b?q=1#f")
        );
    }

    #[test]
    fn port_kept_path_query_fragment_dropped() {
        assert_eq!(
            "java.net.ConnectException: http://10.0.0.2:8080/…",
            redact_urls("java.net.ConnectException: http://10.0.0.2:8080/download?token=secret")
        );
    }

    #[test]
    fn userinfo_credentials_must_not_leak() {
        let out = redact_urls("boom https://user:pa55@example.com/private/x.pdf");
        assert!(!out.contains("pa55"));
        assert!(!out.contains("user:"));
        assert_eq!("boom https://example.com/…", out);
    }

    #[test]
    fn at_sign_in_path_does_not_strip_host() {
        assert_eq!(
            "fail https://example.com/…",
            redact_urls("fail https://example.com/docs/@user?page=2")
        );
    }

    #[test]
    fn userinfo_and_at_sign_in_path_both_handled() {
        let out = redact_urls("fetch https://alice@s3.example.com/blobs/@me/x");
        assert!(!out.contains("alice"));
        assert_eq!("fetch https://s3.example.com/…", out);
    }

    #[test]
    fn uppercase_scheme_handled_and_case_preserved() {
        assert_eq!("HTTPS://example.com/…", redact_urls("HTTPS://example.com/x"));
    }

    #[test]
    fn multiple_urls_on_one_line() {
        assert_eq!(
            "a https://a.com/… b http://b.com/…",
            redact_urls("a https://a.com/1 b http://b.com/2")
        );
    }

    #[test]
    fn non_url_text_untouched() {
        let stack = "java.lang.IllegalStateException: session is not open\n\
                     at io.github.tan_sno.tangsnow.MainActivity.onDestroy(MainActivity.kt:666)\n\
                     at android.app.Activity.performDestroy(Activity.java:8305)";
        assert_eq!(stack, redact_urls(stack));
    }

    #[test]
    fn bare_scheme_without_host_untouched() {
        // `\S+` 一个字符都没吃到 ⇒ 不匹配，原样保留
        assert_eq!("no url here: https://", redact_urls("no url here: https://"));
    }

    // ---- 口径坑哨兵：Java \S 是 ASCII 白空语义（nbsp 计入 URL） ----

    #[test]
    fn nbsp_is_part_of_url_like_java() {
        // Java `\S` 匹配 U+00A0（nbsp 不是 Java 白空）⇒ URL 吞到 nbsp 为止
        assert_eq!(
            "x https://a.com/…",
            redact_urls("x https://a.com/1\u{00A0}tail")
        );
    }

    // ---- display_stamp：与 JVM 单测 CrashLoggerDisplayTest 逐条对齐 ----

    #[test]
    fn old_format_formatted() {
        assert_eq!("2026-09-08 10:15:30", display_stamp("crash-20260908-101530.txt"));
    }

    #[test]
    fn new_format_formatted_millis_dropped() {
        assert_eq!(
            "2026-10-01 12:15:00",
            display_stamp("crash-20261001-121500-123.txt")
        );
    }

    #[test]
    fn unparseable_name_shown_as_is() {
        assert_eq!("weird-name", display_stamp("crash-weird-name.txt"));
    }
}
