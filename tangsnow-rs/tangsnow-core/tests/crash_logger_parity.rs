//! `CrashLogger` 的 URL 脱敏差分测试。
//!
//! 崩溃日志直接落盘、会被用户复制分享 —— 脱敏漏一个凭据就是隐私事故，
//! 多剥一个字符就毁掉排障价值。
//!
//! ## 期望值来自**两侧共享的 JSON 夹具**（2026-10-20 审查报告 C-14）
//!
//! 此前这里与 JVM 侧 `CrashLoggerRedactTest.kt` **各写一份期望值** —— 那是**镜像**：
//! 改实现 + 同步改本侧的镜像期望值 ⇒ 两侧照样全绿，而两边的脱敏口径已经漂了。
//! 现在两边读**同一份**夹具（`tests/fixtures/crash_logger_cases.json`）⇒ 改夹具必然两侧一起红。

mod common;

use tangsnow_core::crash_logger::redact_urls;

/// 共享夹具（编译期读入；路径相对本文件）。
const CASES: &str = include_str!("fixtures/crash_logger_cases.json");

#[test]
fn url_被剥到_origin_加省略号() {
    let c = common::pick(common::load(CASES), "完整 URL 被收敛成 scheme 与 host");
    assert_eq!(redact_urls(&c.input), c.expect());
}

#[test]
fn 端口保留而路径查询片段丢弃() {
    let c = common::pick(common::load(CASES), "端口保留而路径查询片段丢弃");
    assert_eq!(redact_urls(&c.input), c.expect());
}

#[test]
fn userinfo_里的凭据不得残留() {
    let c = common::pick(common::load(CASES), "userinfo 里的凭据不得残留");
    let out = redact_urls(&c.input);
    assert!(!out.contains("pa55"), "脱敏后不得含凭据");
    assert!(!out.contains("user:"), "脱敏后不得含用户名");
    assert_eq!(out, c.expect());
}

#[test]
fn 路径里含_at_符号时_host_不得被误剥() {
    // userinfo 的 `@` 只在 authority 段合法；先截 authority 再剥 `@`，
    // 否则 /docs/@user 里的 `@` 会把 host 连同路径前缀整个吃掉
    let c = common::pick(common::load(CASES), "路径里含 at 符号时 host 不得被误剥");
    assert_eq!(redact_urls(&c.input), c.expect());
}

#[test]
fn userinfo_与路径含_at_符号同时存在时各归其位() {
    let c = common::pick(common::load(CASES), "userinfo 与路径含 at 符号同时存在时各归其位");
    let out = redact_urls(&c.input);
    assert!(!out.contains("alice"));
    assert_eq!(out, c.expect());
}

#[test]
fn 大小写_scheme_同样处理() {
    let c = common::pick(common::load(CASES), "大小写 scheme 同样处理");
    assert_eq!(redact_urls(&c.input), c.expect());
}

#[test]
fn 同一行的多个_url_都被处理() {
    let c = common::pick(common::load(CASES), "同一行的多个 URL 都被处理");
    assert_eq!(redact_urls(&c.input), c.expect());
}

#[test]
fn 非_url_文本一字不动() {
    let c = common::pick(common::load(CASES), "非 URL 文本一字不动（脱敏不得破坏排障价值）");
    assert_eq!(redact_urls(&c.input), c.expect());
}

#[test]
fn 只有_scheme_没有域名时不会被误改成半截() {
    // `https://` 后面直接是空白：`\S+` 一个字符都没吃到 ⇒ 不匹配，原样保留
    let c = common::pick(common::load(CASES), "只有 scheme 没有域名时不会被误改成半截");
    assert_eq!(redact_urls(&c.input), c.expect());
}
