//! `HistoryRepo` 纯函数部分差分测试：入库前缀过滤与 SQL 绑定变量分批。锁定「什么 URL 不许进用户历史」
//! 的边界 —— 漏一个 `javascript:` 前缀就是隐私泄露。
//!
//! ## 前缀过滤的期望值来自**两侧共享的 JSON 夹具**（2026-10-20 审查报告 C-14）
//!
//! 此前这里与 JVM 侧各写一份期望值 —— 那是**镜像**：改实现 + 同步改本侧的镜像期望值 ⇒
//! 两侧照样全绿，而两边的口径已经漂了。现在两边读**同一份**夹具
//! （`tests/fixtures/history_skip_prefix_cases.json`）⇒ 改夹具必然两侧一起红。
//!
//! 分批那几组不是跨语言镜像（Kotlin 侧的分批是 `HistoryRepo.urlBatches`，语义同构但不是同一份契约），
//! 各自留在本侧。

mod common;

use std::collections::BTreeSet;

use tangsnow_core::history::{should_skip_history_url, url_batches, SKIP_PREFIXES};

/// 共享夹具（编译期读入；路径相对本文件）。
const CASES: &str = include_str!("fixtures/history_skip_prefix_cases.json");

// ------------------------------------------------------------- 入库前缀过滤

#[test]
fn 夹具里的每条用例与_kotlin_侧同口径() {
    let v = common::load(CASES);
    for c in v["cases"].as_array().unwrap() {
        let id = c["id"].as_str().expect("id 必须是字符串");
        let a = c["args"].as_array().expect("args 必须是数组");
        let url = a[0].as_str().expect("args[0] 必须是字符串");
        let want = c["expected"].as_bool().expect("expected 必须是布尔");
        assert_eq!(should_skip_history_url(url), want, "夹具用例「{id}」");
    }
}

#[test]
fn 前缀清单与共享夹具隐含的前缀集合一致() {
    // 这条**不是**镜像：它把「前缀清单」与「共享夹具」互相钉住，两个方向都堵：
    //  · 夹具里 skip=true 的用例，其 scheme 必须真在前缀清单里 —— 漏一个前缀就是漏一条隐私豁免
    //    （`javascript:` 漏掉 ⇒ 脚本页被写进用户历史）；
    //  · 清单里的每个前缀都必须有夹具用例覆盖 —— 只改清单不改夹具 ⇒ 行为漂移没有任何用例能发现
    //    （这正是「镜像」的老毛病：两侧各写一份期望值，改了一侧照绿）。
    // 两侧清单本身是否一致，由 `CrossLanguageLiteralParityTest` 并排比对两侧源码负责。
    let v = common::load(CASES);
    let mut from_fixture: BTreeSet<String> = BTreeSet::new();
    for c in v["cases"].as_array().unwrap() {
        let url = c["args"][0].as_str().expect("args[0] 必须是字符串");
        if !c["expected"].as_bool().expect("expected 必须是布尔") {
            continue;
        }
        let scheme = url.split(':').next().unwrap_or("").to_lowercase();
        from_fixture.insert(format!("{scheme}:"));
    }
    let listed: BTreeSet<String> = SKIP_PREFIXES.iter().map(|s| s.to_string()).collect();
    assert_eq!(
        listed, from_fixture,
        "Rust 前缀清单与共享夹具隐含的前缀集合不一致（改前缀必须同时改夹具与 Kotlin 侧）"
    );
}

// ------------------------------------------------------------- SQL 绑定变量分批

#[test]
fn 分批_整除与余数() {
    let b = url_batches(10, 3);
    assert_eq!(b, vec![0..3, 3..6, 6..9, 9..10]);
    assert_eq!(url_batches(9, 3), vec![0..3, 3..6, 6..9]);
}

#[test]
fn 分批_零输入返回空() {
    assert!(url_batches(0, 10).is_empty());
    assert!(url_batches(10, 0).is_empty());
}

#[test]
fn 分批_单条与上限对齐() {
    assert_eq!(url_batches(1, 900), vec![0..1]);
    // 与 SQL_VARIABLE_LIMIT 一致的一批
    assert_eq!(url_batches(900, 900), vec![0..900]);
    assert_eq!(url_batches(901, 900), vec![0..900, 900..901]);
}
