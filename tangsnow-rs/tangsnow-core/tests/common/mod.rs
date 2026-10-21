//! 跨语言**共享夹具**的读取（2026-10-20 审查报告 C-14）。
//!
//! ## 为什么要有它
//! `tests/` 下各差分文件与 JVM 侧的同名测试**各写一份期望值** —— 那是**镜像**：
//! 改实现 + 同步改本侧的镜像期望值 ⇒ 两侧照样全绿，而两边的行为已经漂了
//! （B-1 那次「Rust 按码点、Kotlin 按 UTF-16」就是这么活下来的）。
//! 现在两侧读**同一份** JSON 夹具（`tests/fixtures/*.json`）⇒ 改夹具必然两侧一起红。
//!
//! Kotlin 侧的对应物：`app/src/test/java/.../data/SharedFixtures.kt`。
//!
//! ## 用法
//! ```ignore
//! const CASES: &str = include_str!("fixtures/xxx_cases.json");
//! #[test]
//! fn 某条用例() {
//!     let c = common::pick(common::load(CASES), "用例 id");
//!     assert_eq!(some_fn(&c.input), c.expected);
//! }
//! ```
//! 找不到 id ⇒ 直接 panic：**绝不允许「找不到就跳过」**（那会让门禁静默失效）。
//!
//! ⚠️ 本模块被**多个**测试二进制共用（每个 `tests/*.rs` 各是一个二进制），而每个二进制
//! 只用其中一部分 ⇒ 未被用到的那部分会报死码告警（`Case` / `pick` / `expect`）。
//! 那是**预期**的（模块本来就是共享的），故在此统一放行，而不是逐个二进制加 `#[allow]`。

#![allow(dead_code)]

use serde_json::Value;

/// 共享夹具里的一条用例。
#[derive(Debug, Clone)]
pub struct Case {
    /// 与 Kotlin 侧共用的稳定 id（两侧用同一串，便于对照）
    pub id: String,
    /// 输入
    pub input: String,
    /// 期望输出；`None` = 「无结果」（例如函数返回 null）
    pub expected: Option<String>,
}

impl Case {
    /// 期望输出（`None` = 无结果 ⇒ 直接 panic：调用方若要断言「无结果」请自己判 `expected.is_none()`）。
    pub fn expect(&self) -> &str {
        self.expected
            .as_deref()
            .expect("该用例的 expected 是 null（无结果），请改用 expected.is_none() 断言")
    }
}

/// 解析夹具文本。
pub fn load(raw: &str) -> Value {
    let v: Value = serde_json::from_str(raw).expect("夹具不是合法 JSON");
    assert!(
        v["cases"].is_array(),
        "夹具缺少 cases 数组（格式：{{\"cases\":[...]}}）"
    );
    assert!(
        !v["cases"].as_array().unwrap().is_empty(),
        "夹具里一条用例都没有 —— 门禁会空转"
    );
    v
}

/// 按 id 取一条用例；找不到 ⇒ panic（不静默跳过）。
pub fn pick(v: Value, id: &str) -> Case {
    for c in v["cases"].as_array().unwrap() {
        if c["id"].as_str() == Some(id) {
            let input = c["input"]
                .as_str()
                .unwrap_or_else(|| panic!("夹具用例「{id}」缺 input"))
                .to_string();
            // expected 缺省 = 「输出必须与输入逐字相同」；显式 null = 「无结果」
            let expected = match c.get("expected") {
                Some(e) => match e.as_str() {
                    Some(s) => Some(s.to_string()),
                    None => None,
                },
                None => Some(input.clone()),
            };
            return Case {
                id: id.to_string(),
                input,
                expected,
            };
        }
    }
    panic!(
        "夹具里没有 id 为「{id}」的用例 —— 改夹具时两侧要同步（这正是共享夹具的意义）"
    );
}
