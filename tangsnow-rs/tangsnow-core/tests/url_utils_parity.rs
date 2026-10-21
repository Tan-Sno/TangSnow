//! `UrlUtils` 差分测试：两侧读**同一份** JSON 夹具（2026-10-20 审查报告 C-14）。
//!
//! ## 为什么改成共享夹具
//! 此前这里与 JVM 侧 `UrlUtilsTest.kt` / `SchemeGateTest.kt` / `PercentEncodeTest.kt`
//! **各写一份期望值** —— 那是**镜像**：改实现 + 同步改本侧的镜像期望值 ⇒ 两侧照样全绿，
//! 而两边的口径已经漂了（B-1 那次「Rust 按码点、Kotlin 按 UTF-16」就是这么活下来的）。
//! 现在两侧读**同一份**夹具（`tests/fixtures/url_utils_cases.json`）⇒ 改夹具必然两侧一起红。
//!
//! ## 一侧独有的语料留在本文件
//! 两侧口径本就不同的输入不进夹具：IDNA 的映射差异（ß / 行尾 sigma / 全角拉丁 / 零宽连接符 /
//! 未分配码位）由 `tests/url_idna_parity.rs` 单独钉住；罕见 Unicode 数字的 scheme 判定见本文件末尾。

mod common;

use serde_json::Value;
use std::collections::{BTreeMap, BTreeSet};
use tangsnow_core::percent_encode::percent_encode;
use tangsnow_core::search_engines::SearchEngine;
use tangsnow_core::url_utils::*;

/// 共享夹具（编译期读入；路径相对本文件）。
const CASES: &str = include_str!("fixtures/url_utils_cases.json");

/// 两侧派发支持的 kind 全集（Kotlin 侧三个文件负责的 kind 并集与它一致）。
///
/// 为什么要有这份清单：夹具里多出一个 kind 而两侧都没人认领时，**两边都会静默跳过它**
/// ⇒ 那条用例等于没测。故两边都断言「夹具里的 kind 集合 == 本清单」，且每种至少一条用例。
const KINDS: [&str; 8] = [
    "extractUrlFromText",
    "isLocalNetworkAddress",
    "isPortableUrl",
    "looksLikeUri",
    "percentEncode",
    "resolveInfo",
    "schemeClass",
    "schemeOf",
];

/// 夹具里的引擎口径（Kotlin 侧同用 id=test、模板 https://search.test/?q={q}、查询参数名 q）。
fn engine() -> SearchEngine {
    SearchEngine::new("test", "https://search.test/?q={q}", Some("q"))
}

// ------------------------------------------------------------- 共享夹具

#[test]
fn 夹具里的每条用例两侧同口径() {
    let v = common::load(CASES);
    for c in v["cases"].as_array().unwrap() {
        let id = c["id"].as_str().expect("id 必须是字符串");
        let kind = c["kind"].as_str().expect("kind 必须是字符串");
        let a = c["args"].as_array().expect("args 必须是数组");
        match kind {
            "looksLikeUri" => {
                assert_eq!(
                    looks_like_uri(&arg_str(a, 0)),
                    c["expected"].as_bool().expect("expected 必须是布尔"),
                    "夹具用例「{id}」"
                );
            }
            "resolveInfo" => {
                let got = resolve_info(&arg_str(a, 0), &engine());
                let exp = &c["expected"];
                if exp.is_null() {
                    assert!(
                        got.is_none(),
                        "夹具用例「{id}」：期望无结果，却拿到了 {:?}",
                        got.map(|r| r.url)
                    );
                } else {
                    let r = got
                        .unwrap_or_else(|| panic!("夹具用例「{id}」：期望有结果，Rust 侧却返回 None"));
                    assert_eq!(
                        r.url,
                        exp["url"].as_str().expect("expected.url 必须是字符串"),
                        "夹具用例「{id}」"
                    );
                    assert_eq!(
                        r.is_search,
                        exp["isSearch"]
                            .as_bool()
                            .expect("expected.isSearch 必须是布尔"),
                        "夹具用例「{id}」"
                    );
                }
            }
            "isLocalNetworkAddress" => {
                assert_eq!(
                    is_local_network_address(&arg_str(a, 0)),
                    c["expected"].as_bool().expect("expected 必须是布尔"),
                    "夹具用例「{id}」"
                );
            }
            "schemeOf" => {
                assert_eq!(
                    scheme_of(&arg_str(a, 0)),
                    c["expected"].as_str().expect("expected 必须是字符串"),
                    "夹具用例「{id}」"
                );
            }
            "schemeClass" => {
                // 这是**性质**不是逐点期望：三档互不重叠且覆盖全部输入（none = 压根没有 scheme）。
                let s = arg_opt(a, 0);
                let got = match s.as_deref() {
                    None => "none",
                    Some("") => "none",
                    Some(x) if is_web_navigation_scheme(Some(x)) => "web",
                    Some(x) if is_never_web_content_scheme(Some(x)) => "never",
                    Some(_) => "external",
                };
                assert_eq!(
                    got,
                    c["expected"].as_str().expect("expected 必须是字符串"),
                    "夹具用例「{id}」"
                );
            }
            "isPortableUrl" => {
                let u = arg_opt(a, 0);
                assert_eq!(
                    is_portable_url(u.as_deref()),
                    c["expected"].as_bool().expect("expected 必须是布尔"),
                    "夹具用例「{id}」"
                );
            }
            "extractUrlFromText" => {
                assert_eq!(
                    extract_url_from_text(&arg_str(a, 0)).as_deref(),
                    c["expected"].as_str(),
                    "夹具用例「{id}」"
                );
            }
            "percentEncode" => {
                assert_eq!(
                    percent_encode(&arg_str(a, 0)),
                    c["expected"].as_str().expect("expected 必须是字符串"),
                    "夹具用例「{id}」"
                );
            }
            other => panic!("夹具里的 kind「{other}」本文件不认（改夹具要同步两侧的派发）"),
        }
    }
}

#[test]
fn 夹具的kind集合与两侧派发一致且每种都有用例() {
    let v = common::load(CASES);
    let mut counts: BTreeMap<&str, usize> = BTreeMap::new();
    for c in v["cases"].as_array().unwrap() {
        let kind = c["kind"].as_str().expect("kind 必须是字符串");
        *counts.entry(kind).or_insert(0) += 1;
    }
    let got: BTreeSet<&str> = counts.keys().copied().collect();
    let want: BTreeSet<&str> = KINDS.iter().copied().collect();
    assert_eq!(
        got, want,
        "夹具里的 kind 集合与两侧派发支持的集合不一致 —— 新增 kind 必须两侧同时认领，\
         否则那条用例会两边都静默跳过（等于没测）"
    );
    for k in KINDS.iter() {
        assert!(
            counts.get(k).copied().unwrap_or(0) > 0,
            "kind「{k}」在夹具里一条用例都没有 ⇒ 门禁空转"
        );
    }
}

// ------------------------------------------------------------- 本侧独有的语义

#[test]
fn resolve_就是_resolve_info_的_url() {
    // 这是**同一侧两个函数之间的关系**，不是跨语言的期望值 ⇒ 不进共享夹具（进了就得两侧各写一遍）。
    // 夹具里的 `resolveInfo` 已经钉住两者的输入 ⇒ 关系变了这里就会红。
    for input in [
        "https://example.com/x",
        "example.com",
        "localhost:8080",
        "棠雪 浏览器",
        "",
        "   ",
    ] {
        assert_eq!(
            resolve(input, &engine()),
            resolve_info(input, &engine()).map(|r| r.url),
            "resolve 必须就是 resolveInfo 的 url（输入：{input}）"
        );
    }
}

#[test]
fn 有意分歧_罕见_unicode_数字的_scheme_两侧判定不同() {
    // Kotlin 的 isLetterOrDigit 只认 L*+Nl+Nd（**不含** No），Rust 的 is_numeric 含 No
    // ⇒ 「a²://x」在 Kotlin 侧判「没有 scheme」、在 Rust 侧判「有 scheme」。
    // 这是 README「已知口径差异」表里「字母判定」那一行记的**有意保留**的差异
    // （只影响 scheme 合法性判定中的罕见 Unicode 数字），故不进共享夹具。
    assert_eq!(scheme_of("a²://x"), "a²");
}

// ------------------------------------------------------------- 夹具读取的小工具

/// 取 args[i]；JSON null ⇒ None（「无 scheme」「无 URL」两种语义都要用）。
fn arg_opt(a: &[Value], i: usize) -> Option<String> {
    match a.get(i) {
        None | Some(Value::Null) => None,
        Some(v) => Some(
            v.as_str()
                .expect("args 里的非 null 项必须是字符串")
                .to_string(),
        ),
    }
}

/// 取 args[i]（必须是非 null 字符串）。
fn arg_str(a: &[Value], i: usize) -> String {
    arg_opt(a, i).unwrap_or_else(|| panic!("args[{i}] 必须是字符串"))
}
