//! `LegalText`（法律文本加粗标记）差分测试。
//!
//! ## 期望值来自**两侧共享的 JSON 夹具**（2026-10-20 审查报告 C-14）
//!
//! 此前这里与 JVM 侧 `LegalTextTest.kt` **各写一份期望值** —— 那是**镜像**：
//! 改实现 + 同步改本侧的镜像期望值 ⇒ 两侧照样全绿，而两边的行为已经漂了
//! （B-1 那次「Rust 按码点、Kotlin 按 UTF-16」就是这么活下来的）。
//! 现在两边读**同一份**夹具（`tests/fixtures/legal_text_cases.json`）⇒ 改夹具必然两侧一起红。
//!
//! 区间口径：**UTF-16 码元**闭区间（与 Kotlin `String.length` / `Spannable` 下标同口径）。

use serde_json::Value;
use tangsnow_core::legal_text::parse;

/// 共享夹具（编译期读入；路径相对本文件）。
const CASES_JSON: &str = include_str!("fixtures/legal_text_cases.json");

#[test]
fn 夹具里的每条用例都按同一口径解析() {
    let v: Value = serde_json::from_str(CASES_JSON).expect("夹具不是合法 JSON");
    let cases = v["cases"].as_array().expect("夹具缺少 cases 数组");
    assert!(!cases.is_empty(), "夹具里一条用例都没有 —— 门禁会空转");
    for c in cases {
        let raw = c["raw"].as_str().expect("raw 必须是字符串");
        let plain = c["plain"].as_str().expect("plain 必须是字符串");
        let ranges: Vec<(usize, usize)> = c["boldRanges"]
            .as_array()
            .expect("boldRanges 必须是数组")
            .iter()
            .map(|r| {
                let a = r[0].as_u64().expect("区间端点必须是非负整数") as usize;
                let b = r[1].as_u64().expect("区间端点必须是非负整数") as usize;
                (a, b)
            })
            .collect();
        let m = parse(raw);
        assert_eq!(m.plain, plain, "夹具用例：{raw:?}");
        assert_eq!(m.bold_ranges, ranges, "夹具用例：{raw:?}");
    }
}

#[test]
fn 夹具本身是自洽的() {
    // 夹具写错时（比如区间端点写反、正文与区间对不上）应当**当场**红，
    // 而不是等到某一侧跑起来才暴露 —— 两侧都读它，坏夹具会同时毒到两边。
    let v: Value = serde_json::from_str(CASES_JSON).expect("夹具不是合法 JSON");
    let cases = v["cases"].as_array().expect("夹具缺少 cases 数组");
    for c in cases {
        let raw = c["raw"].as_str().expect("raw 必须是字符串");
        let plain = c["plain"].as_str().expect("plain 必须是字符串");
        for r in c["boldRanges"].as_array().expect("boldRanges 必须是数组") {
            let a = r[0].as_u64().expect("区间端点必须是非负整数") as usize;
            let b = r[1].as_u64().expect("区间端点必须是非负整数") as usize;
            assert!(a <= b, "夹具里的闭区间端点写反了：{a}..{b}（用例 {raw:?}）");
            let width = plain.chars().map(|c| c.len_utf16()).sum::<usize>();
            assert!(
                b < width,
                "夹具里的区间超出正文长度：{a}..{b} vs {width}（用例 {raw:?}）"
            );
        }
    }
}
