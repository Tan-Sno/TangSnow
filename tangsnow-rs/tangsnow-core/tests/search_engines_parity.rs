//! `SearchEngines` 差分测试。
//!
//! ## 引擎查找 / 主机判定 / 取主机三组的期望值来自**两侧共享的 JSON 夹具**
//! （2026-10-20 审查报告 C-14）
//!
//! 此前这里与 JVM 侧 `SearchEnginesTest.kt` **各写一份期望值** —— 那是**镜像**：
//! 改实现 + 同步改本侧的镜像期望值 ⇒ 两侧照样全绿，而两边的口径已经漂了
//! （2026-10-16 的别名表漏行"bing"就是这么漏掉的）。
//! 现在两边读**同一份**夹具（`tests/fixtures/search_engines_cases.json`）⇒ 改夹具必然两侧一起红。
//!
//! 内置引擎**模板表**不在夹具内 —— 它是一张表，由 `SearchEngineTableParityTest` 并排比对两侧源码。

mod common;

use std::collections::BTreeSet;

use tangsnow_core::search_engines::*;

/// 共享夹具（编译期读入；路径相对本文件）。
const CASES: &str = include_str!("fixtures/search_engines_cases.json");

// ------------------------------------------------------------- find / hostMatches / templateHostOf

#[test]
fn 夹具里的每条用例两侧同口径() {
    let v = common::load(CASES);
    let mut seen: BTreeSet<&str> = BTreeSet::new();
    for c in v["cases"].as_array().unwrap() {
        let id = c["id"].as_str().expect("id 必须是字符串");
        let kind = c["kind"].as_str().expect("kind 必须是字符串");
        let a = c["args"].as_array().expect("args 必须是数组");
        seen.insert(kind);
        match kind {
            "find" => {
                // args[0] = 引擎 id（null = 空 id）
                let want: Option<&str> = a[0].as_str();
                assert_eq!(
                    find(builtins(), want).id,
                    c["expected"].as_str().expect("expected 必须是字符串"),
                    "夹具用例「{id}」"
                );
            }
            "findEmptyList" => {
                // 用**空列表**找：兜底必须落到第一个内置引擎（而不是抛异常）
                let want = a[0].as_str().expect("args[0] 必须是字符串");
                assert_eq!(
                    find(&[], Some(want)).id,
                    c["expected"].as_str().expect("expected 必须是字符串"),
                    "夹具用例「{id}」"
                );
            }
            "hostMatches" => {
                let host = a[0].as_str().expect("args[0] 必须是字符串");
                let engine = a[1].as_str().expect("args[1] 必须是字符串");
                let tpl = a[2].as_str().expect("args[2] 必须是字符串");
                assert_eq!(
                    host_matches(host, engine, tpl),
                    c["expected"].as_bool().expect("expected 必须是布尔"),
                    "夹具用例「{id}」"
                );
            }
            "templateHostOf" => {
                let tpl = a[0].as_str().expect("args[0] 必须是字符串");
                assert_eq!(
                    template_host_of(tpl).as_deref(),
                    c["expected"].as_str(),
                    "夹具用例「{id}」"
                );
            }
            "searchUrl" => {
                // args = [模板, 关键词]；引擎的 id 与查询参数名都不参与生成 ⇒ 只需模板
                let tpl = a[0].as_str().expect("args[0] 必须是字符串");
                let q = a[1].as_str().expect("args[1] 必须是字符串");
                assert_eq!(
                    SearchEngine::new("test", tpl, None).search_url(q),
                    c["expected"].as_str().expect("expected 必须是字符串"),
                    "夹具用例「{id}」"
                );
            }
            other => panic!("夹具里的 kind「{other}」本文件不认（改夹具要同步两侧的派发）"),
        }
    }
    // 夹具里出现但本文件没派发的 kind ⇒ 有人加了语料却没人读 ⇒ 静默空转。
    // 断言「夹具里的 kind 集合 == 本文件认的 kind 集合」，两侧同时改。
    for k in ["find", "findEmptyList", "hostMatches", "templateHostOf", "searchUrl"] {
        assert!(seen.contains(k), "夹具里没有 kind「{k}」的用例 ⇒ 这一支永远跑不到");
    }
}

#[test]
fn 内置引擎表_条目与模板完备() {
    let all = builtins();
    // 与 Kotlin builtins 同序同模板（关键条目抽查）
    let expect = [
        ("bing_cn", "https://cn.bing.com/search?q={q}", Some("q")),
        ("bing", "https://global.bing.com/search?q={q}&mkt=en-US&setlang=zh-Hans", Some("q")),
        ("baidu", "https://www.baidu.com/s?wd={q}", Some("wd")),
        ("sogou", "https://www.sogou.com/web?query={q}", Some("query")),
        ("so360", "https://www.so.com/s?q={q}", Some("q")),
        ("sm", "https://m.sm.cn/s?q={q}", Some("q")),
        ("google", "https://www.google.com/search?q={q}", Some("q")),
        ("ddg", "https://duckduckgo.com/?q={q}", Some("q")),
    ];
    assert_eq!(all.len(), expect.len());
    for (i, (id, tpl, qp)) in expect.iter().enumerate() {
        assert_eq!(all[i].id, *id);
        assert_eq!(all[i].template, *tpl);
        assert_eq!(all[i].query_param.as_deref(), *qp);
        assert!(!all[i].is_custom);
    }
}

// ------------------------------------------------------------- searchUrl
//
// 模板替换那组原先是本文件与 JVM 侧 `PercentEncodeTest.kt` **各写一份**的镜像期望值
// （两侧实现逐行等价，却各自钉一份）⇒ 现并入 `search_engines_cases.json` 的 `searchUrl` 一组，
// 改期望值必然两侧一起红。

// ------------------------------------------------------------- displayForUrl

#[test]
fn 结果页还原关键词() {
    let all = builtins();
    assert_eq!(display_for_url("https://www.baidu.com/s?wd=%E6%A3%A0%E9%9B%AA", all), Some("棠雪".to_string()));
    assert_eq!(display_for_url("https://cn.bing.com/search?q=abc&form=QBLH", all), Some("abc".to_string()));
    // 别名主机也认
    assert_eq!(display_for_url("https://www.bing.com/search?q=1", all), Some("1".to_string()));
    // + 解码为空格（与 Android getQueryParameter 一致）
    assert_eq!(display_for_url("https://www.baidu.com/s?wd=a+b", all), Some("a b".to_string()));
}

#[test]
fn 非结果页返回_none() {
    let all = builtins();
    assert_eq!(display_for_url("https://example.com/page", all), None);
    assert_eq!(display_for_url("https://www.baidu.com/s?ie=utf-8", all), None);
    assert_eq!(display_for_url("https://www.baidu.com/", all), None);
    assert_eq!(display_for_url("not a url", all), None);
}

// ------------------------------------------------------------- 自定义引擎 id

#[test]
fn 自定义引擎_id_前缀() {
    assert!(CUSTOM_ID_PREFIX == "custom_");
    let custom = SearchEngine {
        id: format!("{CUSTOM_ID_PREFIX}0"),
        template: "https://c.example/?s={q}".into(),
        label: "我的引擎".into(),
        query_param: None,
        is_custom: true,
    };
    assert_eq!(custom.search_url("词"), "https://c.example/?s=%E8%AF%8D");
}

// ------------------------------------------------- 边界占位 id 与别名表（M5，2026-10-15）

#[test]
fn 别名表按真_id_命中而占位_id_命不中() {
    // 把 M5 的成因钉在纯函数层：`display_for_url` → `host_matches(host, engine.id, 模板主机)`，
    // 而 `HOST_ALIASES` 的键是引擎**真 id**。`bing_cn` 的模板主机是 cn.bing.com，
    // 别名里另有 bing.com —— 桥层写死占位 id 时，用户在 www.bing.com 的结果页上
    // 「回显关键词」整条失效（别名这一支永远查不到）。
    let tpl = "https://cn.bing.com/search?q={q}";
    let url = "https://www.bing.com/search?q=%E6%A3%A0%E9%9B%AA";
    let real = SearchEngine::new("bing_cn", tpl, Some("q"));
    let placeholder = SearchEngine::new("jni", tpl, Some("q"));
    assert_eq!(display_for_url(url, &[real]).as_deref(), Some("棠雪"));
    assert_eq!(
        display_for_url(url, &[placeholder]),
        None,
        "占位 id 命不中别名表 —— JNI 的 displayForUrlImpl 因此必须由 Kotlin 把 id 传进来"
    );
}

#[test]
fn template_only_与_new_在只读模板的场合完全等价() {
    // JNI 的 resolveInfo / searchUrlFromTemplate 两处以前也写 `new("jni", …)`，
    // 现在改用 template_only：这条断言证明换构造没有改动行为（只读 template 的场合）。
    let tpl = "https://cn.bing.com/search?q={q}";
    assert_eq!(
        SearchEngine::template_only(tpl).search_url("a b"),
        SearchEngine::new("bing_cn", tpl, Some("q")).search_url("a b")
    );
    assert!(
        SearchEngine::template_only(tpl).id.is_empty(),
        "template_only 不带 id：免得后来人以为这个 id 也能在别名表里查着什么"
    );
}

#[test]
fn 国际版引擎的结果页落在无区域前缀主机上也回显() {
    // 2026-10-16：Rust 的别名表曾漏登记 "bing" 这一行（由 Kotlin↔Rust 的表比对测试抓到）。
    // 模板主机是 global.bing.com，而用户实际打开的结果页常是 www.bing.com：
    // "bing.com" 比模板主机**短** ⇒ `host_matches` 的子域那一支覆盖不到，只能靠别名登记。
    let e = SearchEngine::new(
        "bing",
        "https://global.bing.com/search?q={q}&mkt=en-US&setlang=zh-Hans",
        Some("q"),
    );
    assert_eq!(
        display_for_url("https://www.bing.com/search?q=%E6%A3%A0%E9%9B%AA", &[e]).as_deref(),
        Some("棠雪"),
        "别名行缺了就是静默返回 None —— 不崩、不报错，只是关键词再也不回显",
    );
}
