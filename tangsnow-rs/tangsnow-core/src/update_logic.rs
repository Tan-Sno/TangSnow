//! 更新链路与安全目标判定的纯逻辑（2026-10-08 深度语义重构批）。
//!
//! 对齐 Kotlin：`update/UpdateChecker.kt`（normalizeVersion/isNewer/isTrustedApkTarget/
//! pickAsset）、`data/PreferenceStore.kt`（SearchEngines.normalizeUrl）、
//! `extension/ExtensionCatalog.kt`（isAmoTarget——与 APK 目标同构，参数化 expected_host）。
//!
//! 移植口径：
//! - `trim()` / `isWhitespace` 用 [`java_is_whitespace`] 对齐 Java `Character.isWhitespace`；
//! - scheme/host 的大小写折叠域是 ASCII，`eq_ignore_ascii_case` 与 Kotlin `ignoreCase` 等价；
//! - `VERSION_PREFIX` 正则按 ASCII 数字实现，无需 regex crate；
//! - 数组入参（pickAsset）由 JNI 层以 `\n` 连接传入——GitHub 资产名与 ABI 串都不含换行，
//!   语义安全；返回 None 对齐 Kotlin `null`。

use crate::{java_is_whitespace, java_trim};

/// [`is_trusted_target`] 的可信主机常量：APK 下载目标（GitHub Releases）。
pub const GITHUB_HOST: &str = "github.com";
/// [`is_trusted_target`] 的可信主机常量：扩展目录（AMO）。
pub const AMO_HOST: &str = "addons.mozilla.org";

/// 把 tag 名规整成可比较的版本号：去掉前缀 `v` / `V`，截掉首个非数字后缀。
///
/// `v2.1.0` → `2.1.0`；`2.1.0-beta1` → `2.1.0`；无法识别时返回空串。
/// 对齐 Kotlin：`raw.trim().removePrefix("v").removePrefix("V")` 后做
/// `^(\d+(?:\.\d+)*)` 的贪婪捕获 —— 即「各剥一次 v/V」（`vv1` 只剥一层）。
pub fn normalize_version(raw: &str) -> String {
    let trimmed = java_trim(raw);
    let trimmed = trimmed
        .strip_prefix('v')
        .or_else(|| trimmed.strip_prefix('V'))
        .unwrap_or(trimmed);
    // ^(\d+(?:\.\d+)*)：数字开头，其后是若干「.数字」段；遇非数字即止。
    let bytes = trimmed.as_bytes();
    let mut i = 0usize;
    // 首段必须至少一位数字
    while i < bytes.len() && bytes[i].is_ascii_digit() {
        i += 1;
    }
    if i == 0 {
        return String::new();
    }
    let mut end = i; // 首段长度；后续「.数字」段在此之上贪婪延伸
    loop {
        if i >= bytes.len() || bytes[i] != b'.' {
            break;
        }
        let mut j = i + 1;
        while j < bytes.len() && bytes[j].is_ascii_digit() {
            j += 1;
        }
        if j == i + 1 {
            break; // `.` 后没有数字：贪婪捕获在此结束
        }
        end = j;
        i = j;
    }
    trimmed[..end].to_string()
}

/// [`normalize_version`] 的段化形态；任一段非数字（含空串）时返回 None。
fn segments(v: &str) -> Option<Vec<i32>> {
    let norm = normalize_version(v);
    if norm.is_empty() {
        return None;
    }
    let mut out = Vec::with_capacity(4);
    for p in norm.split('.') {
        // Kotlin `toIntOrNull`：解析成 **Int**（i32），超界即视为「不可解析」→ `return null`。
        // 段由 `\d+` 构成，词法上只可能是「数字太大」，所以必须用 i32 而不是更宽的 i64 ——
        // 否则 `99999999999.0` 这种 tag 在 Rust 侧会被判成可比较、进而提示「有新版本」，
        // 而 Kotlin 侧判不可解析（2026-10-17 修）。
        match p.parse::<i32>() {
            Ok(n) => out.push(n),
            Err(_) => return None,
        }
    }
    Some(out)
}

/// 远端版本是否比本机新（逐段整数比较；任一侧无法解析时返回 false）。
///
/// `2.10.0` > `2.9.0`（字符串比较会得反）；缺失段按 0 补齐（`2.1` vs `2.1.0` 为相等）。
pub fn is_newer(remote: &str, local: &str) -> bool {
    let r = match segments(remote) {
        Some(s) => s,
        None => return false,
    };
    let l = match segments(local) {
        Some(s) => s,
        None => return false,
    };
    let n = r.len().max(l.len());
    for i in 0..n {
        let a = r.get(i).copied().unwrap_or(0);
        let b = l.get(i).copied().unwrap_or(0);
        if a != b {
            return a > b;
        }
    }
    false
}

/// 可信跳转目标的纯判定核心：原文不含反斜杠 + https（忽略大小写）+ host 严格等于
/// `expected_host`（忽略大小写）。
///
/// 严格等值而非前缀/包含：`github.com.evil.example` 与 `github.com.cn` 都能骗过前缀式判断。
/// 「拒反斜杠」以**原文**为准：`https://evil.com\@github.com/` 在 RFC 3986 解析下
/// host = github.com，而 WHATWG 消费方把 `\` 当 `/` ⇒ 实际连 evil.com；整体拒掉收干分歧面。
pub fn is_trusted_target(
    raw_url: &str,
    scheme: Option<&str>,
    host: Option<&str>,
    expected_host: &str,
) -> bool {
    !raw_url.contains('\\')
        && scheme
            .map(|s| s.eq_ignore_ascii_case("https"))
            .unwrap_or(false)
        && host
            .map(|h| h.eq_ignore_ascii_case(expected_host))
            .unwrap_or(false)
}

/// 从资产名里挑出与本机 ABI 匹配的 APK（`pick_asset`）。
///
/// `assets` / `abis` 均为 `\n` 分隔的名单；匹配口径与 Kotlin 逐条对齐：
///  1. 先滤出以 `.apk` 结尾（忽略大小写）的资产；
///  2. 按 `abis` **给定顺序**找第一个 `contains("-<abi>-", ignoreCase)` 的命中；
///  3. 全部落空返回 None。
///
/// ## ⚠️ 接线时必须补 flavor 维度（2026-10-23）
/// Kotlin 侧的 `UpdateChecker.pickAsset` 已加第三个参数 `flavor`：正式版只挑
/// `app-coreKotlin-…`、Rust 版只挑 `app-coreRust-…`，因为 2.3.0 起两条线**同版本号**、
/// 可以挂在同一个发布页上，而两者都含 `-arm64-v8a-` 这类 ABI 段。
/// 本函数仍是 **ABI-only** —— 目前安全：W3 未接线，coreRust 走的也是 Kotlin 实现。
/// 将来把更新检查接到这里时**必须同时加 flavor 段**，否则 `app-coreRust-*` 会被正式版用户
/// 命中，下下来装成包名带 `.rust` 的**另一个应用**（不是升级）。
pub fn pick_asset(assets_joined: &str, abis_joined: &str) -> Option<String> {
    let apks: Vec<&str> = assets_joined
        .split('\n')
        .filter(|s| s.to_ascii_lowercase().ends_with(".apk"))
        .collect();
    if apks.is_empty() {
        return None;
    }
    for abi in abis_joined.split('\n') {
        // Kotlin 侧是 `abi.isBlank()`（UpdateChecker.kt:214）—— 空白项也要跳过；
        // 判空白用的是 java_is_whitespace（= Java isWhitespace ∪ isSpaceChar），与主包同口径。
        if abi.is_empty() || abi.chars().all(crate::java_is_whitespace) {
            continue;
        }
        // haystack 已 lowercase，needle 也必须 lowercase —— Kotlin 是
        // `contains("-${abi}-", ignoreCase = true)`。ABI 串含大写时旧实现恒不命中（2026-10-17 修）。
        let needle = format!("-{}-", abi.to_ascii_lowercase());
        for a in &apks {
            if a.to_ascii_lowercase().contains(&needle) {
                return Some((*a).to_string());
            }
        }
    }
    None
}

/// 自定义搜索引擎 / 主页快捷方式的 URL 规范化（`PreferenceStore.normalizeUrl`）。
///
/// 仅接受 http/https 或「裸域名」（自动补 https）；含 `://` 的其它 scheme 一律拒绝
/// （不放行 javascript:/file:/intent: —— 安全边界）；含空白一律无效。
/// 注意：`contains("://")` / `contains('.')` 判定按 Kotlin 用**原串**；
/// startsWith 用小写串（scheme 等值判定对大小写不敏感）。
pub fn normalize_custom_url(input: &str) -> Option<String> {
    let trimmed = java_trim(input);
    if trimmed.is_empty() || trimmed.chars().any(java_is_whitespace) {
        return None;
    }
    let lower = trimmed.to_lowercase();
    if lower.starts_with("https://") || lower.starts_with("http://") {
        return Some(trimmed.to_string());
    }
    if trimmed.contains("://") {
        return None;
    }
    if trimmed.contains('.') {
        return Some(format!("https://{}", trimmed));
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalize_version_basics() {
        assert_eq!(normalize_version("v2.1.0"), "2.1.0");
        assert_eq!(normalize_version("V2.1.0"), "2.1.0");
        assert_eq!(normalize_version("2.1.0-beta1"), "2.1.0");
        assert_eq!(normalize_version("  v2.10.0\n"), "2.10.0");
        assert_eq!(normalize_version("vv1.0"), "");
        assert_eq!(normalize_version(""), "");
        assert_eq!(normalize_version("release"), "");
        assert_eq!(normalize_version("2."), "2");
        assert_eq!(normalize_version("2.x.1"), "2");
    }

    #[test]
    fn is_newer_semantics() {
        assert!(is_newer("v2.10.0", "2.9.0"));
        assert!(!is_newer("2.9.0", "v2.10.0"));
        assert!(!is_newer("2.1.0", "v2.1.0"));
        assert!(is_newer("2.1", "2.0.9"));
        assert!(!is_newer("2.1", "2.1.0")); // 缺失段补 0
        assert!(!is_newer("beta", "1.0.0")); // 任一侧不可解析 → false
        assert!(!is_newer("1.0.0", "beta"));
    }

    #[test]
    fn trusted_target_edges() {
        assert!(is_trusted_target(
            "https://github.com/a/b.apk",
            Some("https"),
            Some("github.com"),
            GITHUB_HOST
        ));
        assert!(is_trusted_target(
            "HTTPS://GITHUB.COM/x",
            Some("HTTPS"),
            Some("GITHUB.COM"),
            GITHUB_HOST
        ));
        // 前缀/包含式陷阱
        assert!(!is_trusted_target(
            "https://github.com.evil.example/x",
            Some("https"),
            Some("github.com.evil.example"),
            GITHUB_HOST
        ));
        assert!(!is_trusted_target(
            "https://github.com.cn/x",
            Some("https"),
            Some("github.com.cn"),
            GITHUB_HOST
        ));
        // 反斜杠分歧面
        assert!(!is_trusted_target(
            "https://evil.com\\@github.com/",
            Some("https"),
            Some("github.com"),
            GITHUB_HOST
        ));
        // http 拒
        assert!(!is_trusted_target(
            "http://github.com/x",
            Some("http"),
            Some("github.com"),
            GITHUB_HOST
        ));
        assert!(!is_trusted_target(
            "https://evil.com/x",
            Some("https"),
            Some("evil.com"),
            GITHUB_HOST
        ));
        // AMO 同构
        assert!(is_trusted_target(
            "https://addons.mozilla.org/x.xpi",
            Some("https"),
            Some("addons.mozilla.org"),
            AMO_HOST
        ));
    }

    #[test]
    fn pick_asset_edges() {
        // 黄金样本用 AGP splits 的真实资产名（app-<abi>-release.apk）：
        // needle `-<abi>-` 要求 ABI **两侧**都是连字符 —— `tangsnow-2.2.0-arm64-v8a.apk`
        // 这种 ABI 紧贴 .apk 的命名本来就不命中（Kotlin 同口径），这是语义的一部分。
        let assets = "app-arm64-v8a-release.apk\napp-armeabi-v7a-release.apk\nnotes.txt";
        assert_eq!(
            pick_asset(assets, "arm64-v8a\narmeabi-v7a\nx86_64").as_deref(),
            Some("app-arm64-v8a-release.apk")
        );
        // armeabi 是 armeabi-v7a 的前缀陷阱：abis 顺序保持 SUPPORTED_ABIS 首选优先
        assert_eq!(
            pick_asset(assets, "armeabi\narm64-v8a").as_deref(),
            Some("app-armeabi-v7a-release.apk")
        );
        // 大小写不敏感
        assert_eq!(
            pick_asset("APP-ARM64-V8A-RELEASE.APK", "arm64-v8a").as_deref(),
            Some("APP-ARM64-V8A-RELEASE.APK")
        );
        // ABI 紧贴 .apk（无尾随连字符段）不命中 —— needle 语义边界
        assert_eq!(pick_asset("tangsnow-2.2.0-arm64-v8a.apk", "arm64-v8a"), None);
        // 无 apk / 无命中 / 空名单
        assert_eq!(pick_asset("notes.txt", "arm64-v8a"), None);
        assert_eq!(pick_asset("x.apk", "x86"), None);
        assert_eq!(pick_asset("", "arm64-v8a"), None);
        // 空白 ABI 项不参与匹配 —— Kotlin 侧是 `abi.isBlank()`（UpdateChecker.kt:214）。
        assert_eq!(pick_asset("app- -release.apk", " "), None);
        assert_eq!(pick_asset("app-\u{00A0}-release.apk", "\u{00A0}"), None);  // U+00A0：Java isSpaceChar ⇒ 同样算空白
    }

    #[test]
    fn normalize_custom_url_edges() {
        assert_eq!(
            normalize_custom_url(" example.com "),
            Some("https://example.com".into())
        );
        assert_eq!(
            normalize_custom_url("https://EXAMPLE.com"),
            Some("https://EXAMPLE.com".into())
        );
        assert_eq!(normalize_custom_url("http://a.b/c"), Some("http://a.b/c".into()));
        assert_eq!(normalize_custom_url("javascript:alert(1)"), None);
        assert_eq!(normalize_custom_url("file:///sdcard"), None);
        assert_eq!(normalize_custom_url("intent://x"), None);
        assert_eq!(normalize_custom_url("no dot here"), None);
        assert_eq!(normalize_custom_url(""), None);
        assert_eq!(normalize_custom_url("  \u{00A0}"), None); // NBSP 是 Java isWhitespace=false！
    }
}
