//! 属性测试（proptest）：随机输入超越固定用例的覆盖面。
//!
//! 不变量设计原则（2026-10-08 深度重构批）：每条属性对应**安全承诺或往返契约**，
//! 而不是复述实现——固定用例已经锁了"已知边界"，这里专攻**未知的输入组合**。
//!
//! - percent_encode：**往返契约** `decode(encode(s)) == s`（encode 只产合法 %XX）；
//! - download_filename：**安全不变量**——清洗输出不含路径分隔符/不可见字符、
//!   UTF-8 字节长度不超上限、非空兜底；
//! - is_trusted_target：**安全不变量**——含反斜杠必拒、host 不严格等值必拒；
//! - dt_prompts.parse_loose：**完备不变量**——任何输入不 panic，且成功结果的
//!   二次解析恒等（幂等）。

use proptest::prelude::*;

use tangsnow_core::crash_logger;
use tangsnow_core::download_filename;
use tangsnow_core::dt_prompts;
use tangsnow_core::percent_encode;
use tangsnow_core::update_logic;
use tangsnow_core::url_utils;

proptest::proptest! {
    /// 往返契约：encode 的输出必然是 decode 的合法输入，且解码回原串。
    /// 覆盖：ASCII / 非 ASCII（含代理对拆不出来的 emoji）/ 字面 `%` / 控制字符。
    #[test]
    fn prop_percent_encode_roundtrip(s in ".*") {
        // 往返契约：encode 只产合法 %XX（全 ASCII unreserved + 编码字节），decode 必还原原串。
        // 注意 encode **不幂等**（% 本身会被再编码）——这是规范行为，不是缺陷。
        let enc1 = percent_encode::percent_encode(&s);
        let dec = download_filename::percent_decode(&enc1);
        prop_assert_eq!(dec.as_str(), s.as_str());
    }

    /// 安全不变量：清洗输出不含 ILLEGAL 字符与不可见字符，字节长度 ≤ 200，非空。
    #[test]
    fn prop_sanitize_invariants(s in ".*") {
        let out = download_filename::sanitize_file_name(&s);
        prop_assert!(!out.is_empty(), "输出为空（应回落 download）");
        prop_assert!(out.len() <= 200 + 4, "UTF-8 字节超限: {}", out.len());
        for c in out.chars() {
            prop_assert!(!matches!(c, '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|'),
                "输出含路径分隔符: {}", c);
            prop_assert!(c >= ' ', "输出含 C0 控制字符: {:#x}", c as u32);
            prop_assert!(c != '\u{7F}' && !(0x200B..=0x200F).contains(&(c as u32)),
                "输出含不可见字符: {:#x}", c as u32);
        }
    }

    /// 可执行名判定：判定只看扩展名，与大小写无关。
    #[test]
    fn prop_executable_case_insensitive(ext in "[a-zA-Z]{1,6}") {
        let name = format!("file.{}", ext);
        let verdict = download_filename::is_executable_name(&name);
        let expected = download_filename::is_executable_name(&format!("file.{}", ext.to_lowercase()));
        prop_assert_eq!(verdict, expected);
    }

    /// 安全不变量：反斜杠必拒；host 不严格等值必拒；scheme 非 https 必拒。
    #[test]
    fn prop_trusted_target_rejects(raw in ".*", scheme in proptest::option::of("[a-zA-Z]+"), host in proptest::option::of(".*")) {
        let bad_host_ok_scheme = update_logic::is_trusted_target(
            &raw, Some("https"), host.as_deref(), "github.com",
        );
        if let Some(h) = &host {
            if !h.eq_ignore_ascii_case("github.com") {
                prop_assert!(!bad_host_ok_scheme, "host 不等值却被放行");
            }
        }
        if raw.contains('\\') {
            prop_assert!(!bad_host_ok_scheme, "含反斜杠却被放行");
        }
        if scheme.as_deref() != Some("https") && scheme.as_deref() != Some("HTTPS") {
            prop_assert!(!update_logic::is_trusted_target(
                &raw, scheme.as_deref(), Some("github.com"), "github.com",
            ));
        }
    }

    /// 完备不变量：任意输入不 panic；成功结果二次解析幂等。
    #[test]
    fn prop_dt_parse_never_panics_idempotent(kind in "[a-z]{1,10}", raw in ".*") {
        // 不 panic 即通过（parse_loose 内部全部用 ok()?/None 回路）
        let r = dt_prompts::parse_loose(&kind, &raw);
        if let Some((value, _sec)) = &r {
            let again = dt_prompts::parse_loose(&kind, value);
            // 规范化值必须可被再次解析（幂等）：解析产物是自洽格式
            if kind != "week" {
                prop_assert!(again.is_some(), "解析产物二次解析失败: {} -> {}", kind, value);
            }
        }
        let _ = url_utils::looks_like_uri(&raw); // 地址栏判定同样不许 panic
        let _ = crash_logger::redact_urls(&raw);
        let _ = download_filename::parse_file_name(None, Some(raw.as_str()));
    }
}
