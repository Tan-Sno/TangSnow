//! 地址栏输入解析：网址 vs 搜索词、局域网判定、scheme 闸门、分享文本提链接。
//!
//! Kotlin 源：`util/UrlUtils.kt`。所有判定与 Kotlin 实现逐口径对齐：
//!  - 正则中的 `\s`/`\S` 按 Java 默认语义改写为 ASCII 类；
//!  - `trim()` / `isWhitespace()` 走 [`crate::java_is_whitespace`]；
//!  - `HOST_PATTERN` 的 `(?=.{1,253}$)` 前瞻改为等价字符数预检（模式只能匹配
//!    ASCII 输入，char 计数与 Java UTF-16 计数在该前提下等价）。

use crate::search_engines::SearchEngine;
use crate::{java_is_whitespace, java_trim};
use regex::Regex;
use std::sync::LazyLock;

static HOST_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(concat!(
        r"^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?",
        r"(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+",
        r"(?::[0-9]{1,5})?",
        r#"(?:/[^ \t\n\x0B\x0C\r]*)?$"#,
    ))
    .unwrap()
});

static SCHEME_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^[a-zA-Z][a-zA-Z0-9+.-]*://[^ \t\n\x0B\x0C\r]+$").unwrap());

/// 本机地址：localhost 及其子域，可带端口与路径（按 http 处理——本机服务默认不启用 TLS）。
static LOCALHOST_RE: LazyLock<Regex> = LazyLock::new(|| {
    // `(?i-u)`：Java 的 `IGNORE_CASE`（未开 `UNICODE_CASE`）只折叠 ASCII，而 Rust `(?i)`
    // 默认走 Unicode 简单大小写折叠（`ſ`↔`s`、`K`↔`k`）⇒ 会把 `localhoſt` 判成地址；
    // 关掉 `u` 后 `[0-9]` 也回到 Java 的 ASCII 口径（2026-10-17 修）。
    // 但 `-u` 下**取反字符类**会退化成字节类、可匹配非法 UTF-8，`Regex::new` 直接报
    // "pattern can match invalid UTF-8"（bench_smoke 抓到）——故路径那一段用 `(?u:…)`
    // 局部把 Unicode 打开：既保留「`/` 后可含 CJK」的原语义，又不许匹配非法字节。
    Regex::new(
        r"(?i-u)^(?:[A-Za-z0-9-]+\.)*localhost(?::[0-9]{1,5})?(?:/(?u:[^ \t\n\x0B\x0C\r]*))?$",
    )
    .unwrap()
});

/// 单标签主机 + 端口（如 `nas:5000`）：不含点会被 HOST_RE 判非域名，但有明确端口，
/// 是「地址」而非「搜索词」。
static LAN_HOST_PORT_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"^[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?:[0-9]{1,5}(?:/[^ \t\n\x0B\x0C\r]*)?$")
        .unwrap()
});

/// 从分享文本里抓第一个 http(s) 链接。`\S` 按 Java 默认语义 = ASCII 空白的补集
///（**包含** CJK 等全部非 ASCII 字符——这是 CJK 截断必要性的前提）。
/// 取反类同样用 `(?u:…)` 局部开 Unicode，理由见 `LOCALHOST_RE`。
static URL_IN_TEXT_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i-u)https?://(?u:[^ \t\n\x0B\x0C\r]+)").unwrap());

/// CJK 标点：从这里起硬截断（真实 URL 几乎不含，是说明文字黏连的切点）。
static CJK_URL_CUTTER_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"[。，、；：！？「」『』《》〈〉【】（）…—]").unwrap());

/// 链接尾随的标点集合：英文标点 + 全角/CJK 闭合符号（连续剥到不再命中）。
const TRAILING_URL_PUNCTUATION: &str = ".,;:!?'\"()[]{}<>\"“”‘’）］｝]》」』›»。";

/// 地址栏输入解析结果：`url` = 可加载地址；`is_search` = 是否走了搜索引擎。
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Resolved {
    pub url: String,
    pub is_search: bool,
}

/// Kotlin `String.startsWith(prefix, ignoreCase = true)`。
fn starts_with_ci(s: &str, prefix: &str) -> bool {
    s.as_bytes().len() >= prefix.len()
        && s.as_bytes()[..prefix.len()].eq_ignore_ascii_case(prefix.as_bytes())
}

/// `HOST_PATTERN` 的 Rust 等价实现（前瞻改为长度预检）。
fn host_pattern_matches(s: &str) -> bool {
    let n = s.chars().count();
    (1..=253).contains(&n) && HOST_RE.is_match(s)
}

/// 把可能的 Unicode 域名（如「例子.测试」）转成 ASCII（punycode）形式；保留端口与路径。
///
/// Kotlin 用 `java.net.IDN.toASCII(hostOnly, ALLOW_UNASSIGNED)`（IDNA2003）；
/// Rust `idna` crate 是 UTS46（IDNA2008）。常用 CJK 域名（已分配码位）两者结果一致，
/// 极少数未分配码位输入的差异见 README「已知口径差异」表。
pub fn to_ascii_url(input: &str) -> Option<String> {
    if input.chars().any(java_is_whitespace) {
        return None;
    }
    // 不含点就不是域名形态（「例子」这类单词仍应走搜索）
    let host = input.split('/').next().unwrap(); // Kotlin substringBefore('/')
                                                 // authority 段含 @ 即 userinfo（trusted.com@evil.com）——钓鱼面，按搜索处理。
                                                 // 只查 authority：路径里的 @（twitter.com/@jack）是合法用法，不能误伤。
    if host.contains('@') {
        return None;
    }
    if !host.contains('.') {
        return None;
    }
    let rest = &input[host.len()..]; // Kotlin removePrefix(host)
    let host_only = host.split(':').next().unwrap();
    let port = &host[host_only.len()..];
    // 空标签口径以**主线 `IDN.toASCII` 的实测行为**为准（2026-10-15 用 JBR 25.0.3 逐条量过）：
    // 开头或中间的空白标签抛 IllegalArgumentException（Kotlin 侧 runCatching → null），
    // 但**尾点被容忍**（`example.com.` 原样通过；`idna` 自己就保留这个根点，别再补一次——
    // 补了会输出 `example.com..`）。此前 Rust 把 `a..b.com` 直接放过，比浏览器和主线都宽松：
    // 那条主机会被"当网址导航"，而主线把它当搜索词。
    // 唯一有意分歧：退化输入 `.` 这里拒（Java 返回 "."），已记入 README「已知口径差异」表。
    let trimmed_host = host_only.strip_suffix('.').unwrap_or(host_only);
    if trimmed_host.split('.').any(|label| label.is_empty()) {
        return None;
    }
    let ascii = idna::domain_to_ascii(host_only).ok()?;
    // 转出的结果必须仍是合法域名形态（含点、无空白/控制符），避免把搜索词硬转
    if !ascii.contains('.') || ascii.chars().any(|c| java_is_whitespace(c) || (c as u32) < 0x20) {
        return None;
    }
    // **映射后复检**（2026-10-23）：UTS46 把全角/半角句点（U+FF0E / U+FF61）当**标签分隔符**，
    // 这类空标签只会在映射**之后**才出现 —— 上面那段预检跑在映射前，拦不住它，于是会转出
    // `..com` 这种非法主机（首标签为空）。Java 侧 `IDN.toASCII` 是映射后判空标签、抛异常 ⇒ null，
    // 因此两侧此前在 `．.com` / `｡.com` 上分歧：主线当搜索词、Rust 当网址。
    // 同一处顺带对齐 DNS 长度：Java 强制每个标签 ≤ 63 字符（超了抛异常），而 `idna` 默认
    // `verify_dns_length=false` ⇒ 此前 64 / 128 字符的标签被原样放行（DNS 非法）。
    // 尾点（`example.com.`）两侧都保留，故只剥一个再分段。
    let ascii_body = ascii.strip_suffix('.').unwrap_or(&ascii);
    if ascii_body
        .split('.')
        .any(|label| label.is_empty() || label.len() > 63)
    {
        return None;
    }
    Some(format!("{ascii}{port}{rest}"))
}

/// 输入本身是否像一个网址（带协议，或裸域名形态，或本机/内网地址）。
pub fn looks_like_uri(input: &str) -> bool {
    let trimmed = java_trim(input);
    if trimmed.is_empty() {
        return false;
    }
    // Kotlin trimmed.contains(WHITESPACE)：单字符 ASCII `\s` 命中
    if trimmed
        .chars()
        .any(|c| matches!(c, ' ' | '\t' | '\n' | '\u{0B}' | '\u{0C}' | '\r'))
    {
        return false;
    }
    starts_with_ci(trimmed, "http://")
        || starts_with_ci(trimmed, "https://")
        || SCHEME_RE.is_match(trimmed)
        || host_pattern_matches(trimmed)
        || LOCALHOST_RE.is_match(trimmed)
        || LAN_HOST_PORT_RE.is_match(trimmed)
        || to_ascii_url(trimmed).is_some()
}

/// 目标是否落在**局域网**上（RFC 1918 私有段 / 链路本地 / IPv6 ULA / `.local`）。
///
/// 口径：回环（localhost、127/8）**不算**；IPv4 只认 RFC 1918 三段与 169.254/16；
/// IPv6 只认 fe80::/10 与 fc00::/7（IPv4-mapped 形式按内嵌 IPv4 判）；
/// 主机名只认 `.local` 后缀。其余单标签名宁可漏判也不误判。
pub fn is_local_network_address(input: &str) -> bool {
    let host = match host_of(input) {
        Some(h) => java_trim(&h).trim_end_matches('.').to_lowercase(),
        None => String::new(),
    };
    if host.is_empty() {
        return false;
    }
    if host == "localhost" || host.ends_with(".localhost") {
        return false;
    }
    if host.ends_with(".local") {
        return true;
    }
    if host.contains(':') {
        is_local_ipv6(&host)
    } else {
        is_local_ipv4(&host)
    }
}

/// 从「URL」或「裸 host[:port]」里取主机名；取不到返回 None。IPv6 字面量的方括号会被剥掉。
fn host_of(input: &str) -> Option<String> {
    let s = java_trim(input);
    if s.is_empty() {
        return None;
    }
    let scheme_end = s.find("://");
    let after_scheme = match scheme_end {
        Some(i) => &s[i + 3..],
        None => s,
    };
    // Kotlin takeWhile { it != '/' && it != '?' && it != '#' }
    let authority: String = after_scheme
        .chars()
        .take_while(|&c| c != '/' && c != '?' && c != '#')
        .collect();
    let host_port = authority.rsplit('@').next().unwrap(); // substringAfterLast('@')
    if host_port.is_empty() {
        return None;
    }
    if host_port.starts_with('[') {
        let end = host_port.find(']')?;
        return if end > 1 {
            Some(host_port[1..end].to_string())
        } else {
            None
        };
    }
    let h = host_port.split(':').next().unwrap(); // substringBefore(':')
    if h.is_empty() {
        None
    } else {
        Some(h.to_string())
    }
}

/// 仅 RFC 1918 三段 + 169.254/16；回环 `127.0.0.0/8` 明确排除。
fn is_local_ipv4(host: &str) -> bool {
    let parts: Vec<&str> = host.split('.').collect();
    if parts.len() != 4 {
        return false;
    }
    let mut o = [0u8; 4];
    for (i, part) in parts.iter().enumerate() {
        // 严格点分十进制：拒绝空段 / 带符号 / 前导零（"010"）——内核不认这类字面量
        let p = *part;
        if p.is_empty() || p.len() > 3 || !p.bytes().all(|b| b.is_ascii_digit()) {
            return false;
        }
        if p.len() > 1 && p.starts_with('0') {
            return false;
        }
        let v: u32 = p.parse().unwrap(); // ≤3 位纯数字，必 ≤999
        if v > 255 {
            return false;
        }
        o[i] = v as u8;
    }
    o[0] == 10                                   // 10.0.0.0/8
        || (o[0] == 172 && (16..=31).contains(&o[1])) // 172.16.0.0/12
        || (o[0] == 192 && o[1] == 168)          // 192.168.0.0/16
        || (o[0] == 169 && o[1] == 254)          // 169.254.0.0/16 链路本地
}

/// fe80::/10（链路本地）与 fc00::/7（ULA）；IPv4-mapped 形式按其内嵌的 IPv4 判。
fn is_local_ipv6(host: &str) -> bool {
    let h = host.to_lowercase();
    // IPv4-mapped（`::ffff:0:0/96`）：内核按内嵌的 IPv4 处理，但字面量是 IPv6
    // ⇒ 不认它就会漏判（Android 17 起未授权时局域网被静默拦截）
    if let Some(v4) = mapped_ipv4(&h) {
        return is_local_ipv4(&v4);
    }
    h.starts_with("fe8")
        || h.starts_with("fe9")
        || h.starts_with("fea")
        || h.starts_with("feb") // fe80::/10
        || h.starts_with("fc")
        || h.starts_with("fd") // fc00::/7
}

/// 从 IPv6 字面量里识别 **IPv4-mapped** 形式并抽出内嵌的点分 IPv4。
///
/// 结构（RFC 4291 §2.5.5.2）：前 80 位全零、81–96 位为 `ffff`、低 32 位是 IPv4：
///  - 点分尾：`::ffff:192.168.1.1`、`0:0:0:0:0:ffff:192.168.1.1`；
///  - 十六进制内嵌：`::ffff:c0a8:101`（8 组，末两组 hi:lo 拼成 32 位）。
///
/// 只做字面量识别，不判「是否局域网」。除零与 ffff 外出现任何别的组，一律不是 mapped。
fn mapped_ipv4(host: &str) -> Option<String> {
    let h = host.to_lowercase();
    if !h.contains(':') {
        return None;
    }
    let last_colon = h.rfind(':').unwrap();
    let tail = &h[last_colon + 1..];
    if tail.contains('.') {
        // 点分尾：末段即内嵌 IPv4，前缀必须恰是「5 零 + ffff」
        if !is_mapped_prefix(&h[..last_colon]) {
            return None;
        }
        return Some(tail.to_string());
    }
    // 十六进制内嵌：需要倒数第二个组作 hi，与 tail（lo）拼 32 位
    if last_colon == 0 {
        return None;
    }
    let prev = h[..last_colon].rfind(':')?;
    let hi = &h[prev + 1..last_colon];
    if !is_hex_group(hi) {
        return None;
    }
    if !is_mapped_prefix(&h[..prev]) {
        return None;
    }
    if !is_hex_group(tail) {
        return None;
    }
    let hi_v = u32::from_str_radix(hi, 16).unwrap();
    let lo_v = u32::from_str_radix(tail, 16).unwrap();
    Some(format!(
        "{}.{}.{}.{}",
        hi_v >> 8,
        hi_v & 0xFF,
        lo_v >> 8,
        lo_v & 0xFF
    ))
}

/// 校验 mapped 前缀：除 `0` 与 `ffff` 外不允许任何其他组，`ffff` 是最后一个非空组
/// 且必须存在；零组总数（显式 0 + 被 `::` 压缩掉的）必须恰为 5。
fn is_mapped_prefix(prefix: &str) -> bool {
    let compressed = prefix.contains("::");
    let mut zeros = 0;
    let mut ffff_seen = false;
    for g in prefix.split(':') {
        if g.is_empty() {
            if ffff_seen {
                return false; // `::` 只能压 ffff 之前的零
            }
        } else if g == "0" {
            if ffff_seen {
                return false;
            }
            zeros += 1;
        } else if g == "ffff" {
            if ffff_seen {
                return false;
            }
            ffff_seen = true;
        } else {
            return false;
        }
    }
    if !ffff_seen {
        return false;
    }
    if compressed {
        zeros <= 4
    } else {
        zeros == 5
    }
}

/// 1–4 位十六进制组（mapped 内嵌 IPv4 的 hi/lo 段）；小写口径（上游已 lowercase）。
fn is_hex_group(g: &str) -> bool {
    (1..=4).contains(&g.len())
        && g
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

/// 取 URL 的 scheme（小写、不含 `:`）；没有合法 scheme 时返回空串。
///
/// 合法 scheme 字符集按 RFC 3986 §3.1：ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )。
pub fn scheme_of(input: &str) -> String {
    let colon = match input.find(':') {
        Some(0) | None => return String::new(),
        Some(c) => c,
    };
    let raw = &input[..colon];
    let first = raw.chars().next().unwrap();
    if !first.is_alphabetic() {
        return String::new();
    }
    let legal = raw
        .chars()
        .all(|c| c.is_alphabetic() || c.is_numeric() || c == '+' || c == '-' || c == '.');
    if legal {
        raw.to_lowercase()
    } else {
        String::new()
    }
}

/// 内核能自行处理的「网页导航」scheme 白名单。
/// `SessionManager.onLoadRequest` 与 `MainActivity.onOpenInCurrentTab` 必须共用本判定。
pub fn is_web_navigation_scheme(scheme: Option<&str>) -> bool {
    matches!(scheme, Some("http") | Some("https") | Some("about") | Some("data") | Some("blob"))
}

/// 网页内容**绝不该驱动**的 scheme（与 [`is_web_navigation_scheme`] 互补，构成三分法）：
/// `file:`/`moz-extension:`（应用私有与扩展内部页）、`javascript:`（脚本注入当前文档）。
/// 刻意不含 mailto/tel/market/intent/geo——它们该交给系统打开，误挡即功能回归。
pub fn is_never_web_content_scheme(scheme: Option<&str>) -> bool {
    matches!(scheme, Some("file") | Some("moz-extension") | Some("javascript"))
}

/// 内部页与「超长 / 临时 / 不可恢复」URL：分享、复制、写入会话快照三处共用的判据。
const NON_PORTABLE_SCHEMES: [&str; 5] = ["about", "data", "blob", "file", "content"];

/// **可携带 / 可落盘**的 URL 判定（见 `NON_PORTABLE_SCHEMES` 注释）。
pub fn is_portable_url(url: Option<&str>) -> bool {
    match url {
        Some(u) if !u.is_empty() && !u.chars().all(java_is_whitespace) => {
            let scheme = scheme_of(u);
            !NON_PORTABLE_SCHEMES.contains(&scheme.as_str())
        }
        _ => false,
    }
}

/// 把地址栏输入解析为可加载的 URL（或搜索词）。
///
/// 判定顺序与 Kotlin 一致：http(s) 前缀透传 → 裸域名补 https → localhost/内网
/// 补 http → IDN 转 punycode 补 https → 其余交给搜索引擎。
pub fn resolve_info(input: &str, engine: &SearchEngine) -> Option<Resolved> {
    let trimmed = java_trim(input);
    if trimmed.is_empty() {
        return None;
    }
    if starts_with_ci(trimmed, "http://") || starts_with_ci(trimmed, "https://") {
        Some(Resolved {
            url: trimmed.to_string(),
            is_search: false,
        })
    } else if starts_with_ci(trimmed, "about:") {
        // Firefox 系自省页族（about:license / about:buildinfo / about:blank…）：地址栏直达而非搜索。
        // 刻意**只放行 about:** —— `data:` 的顶层导航 Firefox 59 起已封禁（反钓鱼，与 externalUrl
        // 口径同向），`blob:` 从地址栏新输入时会话里没有注册对象、打开必为空，两者维持进搜索。
        // 内核侧 onLoadRequest 的 webScheme 白名单本就放行 about:，无需其他改动。
        // 与主包 `37e0b0d` 同口径：本分支在移植时漏了，敲 `about:license` 会被当搜索词丢给引擎。
        Some(Resolved {
            url: trimmed.to_string(),
            is_search: false,
        })
    } else if host_pattern_matches(trimmed) {
        Some(Resolved {
            url: format!("https://{trimmed}"),
            is_search: false,
        })
    } else if LOCALHOST_RE.is_match(trimmed) || LAN_HOST_PORT_RE.is_match(trimmed) {
        Some(Resolved {
            url: format!("http://{trimmed}"),
            is_search: false,
        })
    } else {
        // 中文域名 / IDN：先转 punycode 再判断，避免被误当搜索词
        match to_ascii_url(trimmed) {
            Some(ascii) => Some(Resolved {
                url: format!("https://{ascii}"),
                is_search: false,
            }),
            None => Some(Resolved {
                url: engine.search_url(trimmed),
                is_search: true,
            }),
        }
    }
}

/// 可直接交给 GeckoView.loadUri 的 URL；输入为空时返回 None。
pub fn resolve(input: &str, engine: &SearchEngine) -> Option<String> {
    resolve_info(input, engine).map(|r| r.url)
}

/// 从 ACTION_SEND 分享来的任意文本里提取第一个 http(s) 链接。
///
/// 两层收口：① CJK 标点硬截断（分享文案常把说明文字直接黏在链接后）；
/// ② 尾随标点剥离（句号、逗号、右括号、引号；成对右括号也会被剥——刻意取舍）；
/// 末尾再清一次「被截断的百分号编码残渣」。
pub fn extract_url_from_text(text: &str) -> Option<String> {
    let raw = URL_IN_TEXT_RE.find(text)?.as_str();
    let cut = CJK_URL_CUTTER_RE
        .find(raw)
        .map(|m| m.start())
        .unwrap_or(raw.len());
    let mut url = &raw[..cut];
    // 剥离连续的尾随标点（如句尾的「.」、右括号、引号）
    while let Some(last) = url.chars().last() {
        if TRAILING_URL_PUNCTUATION.contains(last) {
            url = &url[..url.len() - last.len_utf8()];
        } else {
            break;
        }
    }
    // 截断的百分号编码残渣：合法编码恒为「% + 两位十六进制」，结尾若是 '%'
    // 或 '%+一位十六进制' 则剥回完整边界；完整的 %XY 保留（如 %20）
    loop {
        let b = url.as_bytes();
        let n = b.len();
        if n > 0 && b[n - 1] == b'%' {
            url = &url[..n - 1];
        } else if n >= 2 && b[n - 2] == b'%' && b[n - 1].is_ascii_hexdigit() {
            url = &url[..n - 2];
        } else {
            break;
        }
    }
    if url.is_empty() {
        None
    } else {
        Some(url.to_string())
    }
}
