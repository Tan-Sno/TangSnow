//! `BookmarkHtml` 差分测试。
//!
//! ## 期望值来自**两侧共享的 JSON 夹具**（2026-10-20 审查报告 C-14）
//!
//! 此前这里与 JVM 侧 `BookmarkHtmlTest.kt` **各写一份期望值** —— 那是**镜像**：
//! 改实现 + 同步改本侧的镜像期望值 ⇒ 两侧照样全绿，而两边的口径已经漂了
//! （「标题截断按码点 vs 按 UTF-16」就是这么活下来的）。
//! 现在两边读**同一份**夹具（`tests/fixtures/bookmark_html_cases.json`）⇒ 改夹具必然两侧一起红。
//!
//! ## 留在本文件里的是**性质**，不是期望值
//!
//! 复杂度哨兵（对抗性输入必须线性）、体积/条数上限、标题按**码点**截断（emoji 不劈半个代理）、
//! 导出→导入往返、线性实现 vs 参考正则实现的随机语料差分 —— 它们断言的是「性质」而非
//! 「某个具体值」，故不进夹具、两侧各留一条（与 `tests/fixtures/download_filename_cases.json`
//! 同一处置）。

mod common;

use std::collections::HashSet;
use tangsnow_core::bookmark_html::*;

/// 共享夹具（编译期读入；路径相对本文件）。
const CASES: &str = include_str!("fixtures/bookmark_html_cases.json");

/// 共享夹具里 `parse` / `sanitize` 用例的「导入时刻」。
///
/// 为什么取一个**遥远的未来**：Rust 侧的 `parse_add_date` 会拒收「晚于 now 超过一天」的
/// `ADD_DATE`，而 Kotlin 侧的 `parseImport` 用的是真实墙钟 ⇒ 夹具里的 `ADD_DATE` 只能是
/// 「早已过去」的绝对时间（对两侧都稳定，见夹具的 `_parse为什么与now无关`）。
/// 取 2100 年是为了让本侧**不会**因为「比 now 晚」而拒收任何一个「过去」的日期 ——
/// 否则日后往夹具里加一条 2024 年的 `ADD_DATE`，本侧会突然解析成 null 而 Kotlin 侧不是。
const FIXTURE_NOW: i64 = 4_102_444_800_000; // 2100-01-01T00:00:00Z（epoch 毫秒）

// ------------------------------------------------------------- 共享夹具

#[test]
fn 夹具里的每条用例两侧同口径() {
    let v = common::load(CASES);
    for c in v["cases"].as_array().unwrap() {
        let id = c["id"].as_str().expect("id 必须是字符串");
        let kind = c["kind"].as_str().expect("kind 必须是字符串");
        let a = c["args"].as_array().expect("args 必须是数组");
        match kind {
            // args=[html] → expected=[{url,title,createdAtMs}]（按导入顺序）
            "parse" => {
                let html = a[0].as_str().expect("args[0] 必须是字符串");
                let (entries, _dropped) = parse_import_counted(html, Some(FIXTURE_NOW));
                let want = c["expected"].as_array().expect("expected 必须是数组");
                assert_eq!(entries.len(), want.len(), "夹具用例「{id}」条目数");
                for (e, w) in entries.iter().zip(want.iter()) {
                    assert_eq!(
                        e.url,
                        w["url"].as_str().expect("expected.url 必须是字符串"),
                        "夹具用例「{id}」url"
                    );
                    assert_eq!(
                        e.title,
                        w["title"].as_str().expect("expected.title 必须是字符串"),
                        "夹具用例「{id}」title"
                    );
                    assert_eq!(
                        e.created_at_ms,
                        want_ms(w, id),
                        "夹具用例「{id}」createdAtMs"
                    );
                }
            }
            // args=[raw, now] → expected=int|null
            "parseAddDate" => {
                let raw = a[0].as_str().expect("args[0] 必须是字符串");
                let now = a[1].as_i64().expect("args[1] 必须是整数（epoch 毫秒）");
                let want = match c.get("expected") {
                    Some(serde_json::Value::Null) => None,
                    Some(v) => Some(v.as_i64().expect("expected 必须是整数或 null")),
                    None => panic!("夹具用例「{id}」缺 expected"),
                };
                assert_eq!(parse_add_date(raw, now), want, "夹具用例「{id}」");
            }
            // args=[html, existingUrls[]] → expected={kept:[{url,title}], skipped:int}
            "sanitize" => {
                let html = a[0].as_str().expect("args[0] 必须是字符串");
                let existing: HashSet<String> = a[1]
                    .as_array()
                    .expect("args[1] 必须是数组")
                    .iter()
                    .map(|v| {
                        v.as_str()
                            .expect("args[1] 的元素必须是字符串")
                            .to_string()
                    })
                    .collect();
                let (raw, _dropped) = parse_import_counted(html, Some(FIXTURE_NOW));
                let (kept, skipped) = sanitize(&raw, &existing);
                let want_kept = c["expected"]["kept"]
                    .as_array()
                    .expect("expected.kept 必须是数组");
                assert_eq!(kept.len(), want_kept.len(), "夹具用例「{id}」kept 条数");
                for (e, w) in kept.iter().zip(want_kept.iter()) {
                    assert_eq!(
                        e.url,
                        w["url"].as_str().expect("expected.kept.url 必须是字符串"),
                        "夹具用例「{id}」url"
                    );
                    assert_eq!(
                        e.title,
                        w["title"].as_str().expect("expected.kept.title 必须是字符串"),
                        "夹具用例「{id}」title"
                    );
                }
                assert_eq!(
                    skipped,
                    c["expected"]["skipped"]
                        .as_u64()
                        .expect("expected.skipped 必须是整数"),
                    "夹具用例「{id}」skipped"
                );
            }
            // args=[{url,title,createdAt}] → expected=str（完整导出文本）
            "export" => {
                let bookmarks: Vec<Bookmark> = a[0]
                    .as_array()
                    .expect("args[0] 必须是数组")
                    .iter()
                    .map(|o| Bookmark {
                        url: o["url"].as_str().expect("args[0].url 必须是字符串").to_string(),
                        title: o["title"]
                            .as_str()
                            .expect("args[0].title 必须是字符串")
                            .to_string(),
                        created_at: o["createdAt"]
                            .as_i64()
                            .expect("args[0].createdAt 必须是整数"),
                    })
                    .collect();
                assert_eq!(
                    export(&bookmarks),
                    c["expected"].as_str().expect("expected 必须是字符串"),
                    "夹具用例「{id}」"
                );
            }
            // args=[url] → expected=bool
            "isHttpUrl" => {
                let url = a[0].as_str().expect("args[0] 必须是字符串");
                assert_eq!(
                    is_http_url(url),
                    c["expected"].as_bool().expect("expected 必须是布尔"),
                    "夹具用例「{id}」"
                );
            }
            other => panic!("夹具里的 kind「{other}」本文件不认（改夹具要同步两侧的派发）"),
        }
    }
}

/// 夹具里 `expected.createdAtMs` 的取值：显式 null = 「文件没给或值非法」。
fn want_ms(w: &serde_json::Value, id: &str) -> Option<i64> {
    match w.get("createdAtMs") {
        Some(serde_json::Value::Null) => None,
        Some(v) => Some(v.as_i64().expect("expected.createdAtMs 必须是整数")),
        None => panic!("夹具用例「{id}」缺 expected.createdAtMs"),
    }
}

// ------------------------------------------------------------- 上限与清洗（性质）

#[test]
fn 原始条目上限_超出计数为_dropped() {
    let mut html = String::new();
    for i in 0..20_005 {
        html.push_str(&format!(
            "<DT><A HREF=\"https://cap.example/{i}\">t</A>\n"
        ));
    }
    let (entries, dropped) = parse_import_counted(&html, Some(FIXTURE_NOW));
    assert_eq!(entries.len(), RAW_PARSE_ENTRY_CAP);
    assert_eq!(dropped, 5);
}

#[test]
fn 文件体积超限直接空结果() {
    let big = "x".repeat(MAX_IMPORT_CHARS + 1);
    let (entries, dropped) = parse_import_counted(&big, Some(FIXTURE_NOW));
    assert!(entries.is_empty());
    assert_eq!(dropped, 0);
}

#[test]
fn sanitize_标题截断到上限() {
    let long_title = "标".repeat(300);
    let html = format!("<DT><A HREF=\"https://t.example/\">{long_title}</A>");
    let (raw, _) = parse_import_counted(&html, Some(FIXTURE_NOW));
    let (out, skipped) = sanitize(&raw, &HashSet::new());
    assert_eq!(skipped, 0);
    assert_eq!(out[0].title.chars().count(), MAX_TITLE_LENGTH);
}

/// 标题截断必须按**码点**、不得劈开代理对。
///
/// 回归的是什么：Kotlin 侧 `String.take(n)` 取的是前 n 个 **UTF-16 码元**，n 落在代理对
/// 中间时会把一个 emoji 劈成两半 ⇒ 落库一个**孤立代理**（渲染成 `�`）；而本侧
/// `chars().take(n)` 按码点 ⇒ 两侧原本对同一条含 emoji 的标题给出不同结果。
/// 现两侧统一按码点（Kotlin 见 `util/CodePoints.takeCodePoints`）。
#[test]
fn sanitize_标题含星平面字符时按码点截断不留半个代理() {
    // 199 个 BMP 字符 + emoji + 尾：截断点正好落在代理对中间
    let long_title = format!("{}🎈尾", "标".repeat(MAX_TITLE_LENGTH - 1));
    let html = format!("<DT><A HREF=\"https://t.example/\">{long_title}</A>");
    let (raw, _) = parse_import_counted(&html, Some(FIXTURE_NOW));
    let (out, skipped) = sanitize(&raw, &HashSet::new());
    assert_eq!(skipped, 0);
    assert_eq!(out[0].title.chars().count(), MAX_TITLE_LENGTH);
    // 末尾必须是完整的 emoji（不是它的半个代理）
    assert!(out[0].title.ends_with('🎈'));
}

/// 导出→导入必须**无损**（性质，不是某个具体期望值）。
#[test]
fn 导出导入往返_标题与时间逐字不变() {
    // 前提：标题**两端无空白** —— 导入侧会裁掉两端空白（`java_trim`），导出侧不裁，
    // 故带空白标题的往返不是无损的（那是既有口径，不在本性质的范围内）。
    for title in [
        "Use &lt;div&gt;",
        "示例 & <站点>",
        "say \"hi\"",
        "普通标题",
    ] {
        let html = export(&[Bookmark {
            url: "https://r.example/".to_string(),
            title: title.to_string(),
            created_at: 1_700_000_000_000,
        }]);
        let (entries, _) = parse_import_counted(&html, Some(FIXTURE_NOW));
        assert_eq!(entries.len(), 1, "标题 {title:?} 往返后条目数不对");
        assert_eq!(entries[0].url, "https://r.example/", "标题 {title:?}");
        assert_eq!(entries[0].title, title, "标题 {title:?} 往返后被改写");
        // 往返损失亚秒（导出格式的 ADD_DATE 是秒级）
        assert_eq!(entries[0].created_at_ms, Some(1_700_000_000_000), "标题 {title:?}");
    }
}

// ------------------------------------------------------------- 复杂度哨兵

#[test]
fn 对抗性输入_整篇小于号加a_保持线性() {
    // 修复前的正则实现在这类输入上是 O(n²)（实测 7.1s）；线性实现必须毫秒级
    let big = "<a ".repeat(500_000); // 2MB
    let t0 = std::time::Instant::now();
    let (entries, _) = parse_import_counted(&big, Some(FIXTURE_NOW));
    assert!(entries.is_empty());
    assert!(t0.elapsed().as_secs() < 2, "必须线性，实测 {:?}", t0.elapsed());
}

#[test]
fn 对抗性输入_有大于号但永无收尾_保持线性() {
    // 每轮从缓存位置继续而非重扫文末
    let big = "<a >".repeat(500_000); // 2MB
    let t0 = std::time::Instant::now();
    let (entries, _) = parse_import_counted(&big, Some(FIXTURE_NOW));
    assert!(entries.is_empty());
    assert!(t0.elapsed().as_secs() < 2, "必须线性，实测 {:?}", t0.elapsed());
}

// ------------------------------------------------------- 随机语料差分（固定种子）

/// 参考实现：直接正则（fancy-regex 提供 lookbehind），语义即「未做线性化前」的口径。
/// 与手写线性实现逐条对比，钉住「线性化不改变行为」。
mod reference {
    use fancy_regex::Regex;
    use std::sync::LazyLock;

    static REF_ANCHOR_TAG: LazyLock<Regex> =
        LazyLock::new(|| Regex::new(r"(?i)<a[ \t\n\x0B\x0C\r][^>]*>").unwrap());
    static REF_CLOSE_TAG: LazyLock<Regex> =
        LazyLock::new(|| Regex::new(r"(?i)</a[ \t\n\x0B\x0C\r]*>").unwrap());
    static REF_HREF_ATTR: LazyLock<Regex> = LazyLock::new(|| {
        Regex::new(concat!(
            r"(?i)(?<![A-Za-z0-9_-])href[ \t\n\x0B\x0C\r]*=[ \t\n\x0B\x0C\r]*",
            r#"("([^"]*)"|'([^']*)'|([^ \t\n\x0B\x0C\r>]+))"#,
        ))
        .unwrap()
    });

    fn decode(s: &str) -> String {
        // 参考实现复用核心的单趟解码（该函数正确性由上方专项用例独立锁定）
        tangsnow_core::bookmark_html::decode_entities(s)
    }

    pub fn parse(html: &str) -> Vec<(String, String)> {
        let mut out = Vec::new();
        let mut from = 0;
        loop {
            let tag = match REF_ANCHOR_TAG.find_from_pos(html, from) {
                Ok(Some(m)) => m,
                _ => break,
            };
            let body_start = tag.end();
            let close = REF_CLOSE_TAG.find_from_pos(html, body_start).ok().flatten();
            let title = match &close {
                Some(c) => html[body_start..c.start()].to_string(),
                None => String::new(),
            };
            from = match &close {
                Some(c) => c.end(),
                None => body_start,
            };
            let caps = match REF_HREF_ATTR.captures(tag.as_str()) {
                Ok(Some(c)) => c,
                _ => continue,
            };
            let g = |i: usize| caps.get(i).map(|x| x.as_str()).unwrap_or("");
            let v = g(2);
            let v = if v.is_empty() { g(3) } else { v };
            let v = if v.is_empty() { g(4) } else { v };
            // 参考实现：先对原始 href 判 blank，再解码、去空白（与 Kotlin 测试同序）
            if blank(v) {
                continue;
            }
            out.push((
                tangsnow_core::java_trim(&decode(v)).to_string(),
                tangsnow_core::java_trim(&decode(&title)).to_string(),
            ));
        }
        out
    }

    fn blank(s: &str) -> bool {
        s.is_empty() || s.chars().all(tangsnow_core::java_is_whitespace)
    }
}

/// 线性同余伪随机（固定种子，保证测试可复现——对齐 JVM 侧 `Random(20260926L)` 的思路）。
struct Lcg(u64);
impl Lcg {
    fn next(&mut self) -> u64 {
        self.0 = self.0.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(1_442_695_040_888_963_407);
        self.0
    }
    fn pick<'a>(&mut self, v: &'a [&'a str]) -> &'a str {
        v[(self.next() % v.len() as u64) as usize]
    }
}

#[test]
fn 随机语料差分_线性实现与参考正则实现同输出() {
    let tokens: &[&str] = &[
        "<a ", "<A ", "<a>", "<A>",
        "HREF=\"https://a.com/x\"", "href='https://b.cn/y'", "href=unq.example/z",
        "data-href=\"https://evil.example/\"", "data-href='x'",
        "ADD_DATE=\"1700000000\"", "add_date='999'", "ADD_DATE=abc",
        ">标题 &amp; 更多<", ">中文<", "> </A>", "</A>", "</a >", "</A  >", "</abbr>", "</a",
        ">", "<p>", "<br/>", " ", "\n", "&AMP;", "&nbsp;", "\"", "'", "…",
    ];
    let mut rng = Lcg(2026_10_02);
    for case in 0..500 {
        let n = 4 + (rng.next() % 12) as usize;
        let mut s = String::new();
        for _ in 0..n {
            s.push_str(rng.pick(tokens));
            s.push(' ');
        }
        let (entries, _) = parse_import_counted(&s, Some(FIXTURE_NOW));
        let mine: Vec<(String, String)> =
            entries.into_iter().map(|e| (e.url, e.title)).collect();
        let theirs = reference::parse(&s);
        assert_eq!(
            mine,
            theirs,
            "case {case} 不一致\n输入: {s:?}"
        );
    }
}
