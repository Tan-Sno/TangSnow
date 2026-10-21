//! `BookmarkHtml` 差分测试：用例与 JVM 侧 `BookmarkHtmlTest.kt` 同源，
//! 另含复杂度哨兵（对抗性输入必须保持线性）与「线性实现 vs 参考正则实现」
//! 的随机语料差分（固定种子，可复现）。

use std::collections::HashSet;
use tangsnow_core::bookmark_html::*;

/// 包装：显式传入「导入时刻」（Kotlin 默认取 System.currentTimeMillis()，Rust 侧由测试/JNI 显式给值）
fn parse(html: &str, now: i64) -> (Vec<Entry>, u64) {
    parse_import_counted(html, Some(now))
}

// ------------------------------------------------------------- parseImport

#[test]
fn 解析标准_netscape_导出格式() {
    let html = concat!(
        "<!DOCTYPE NETSCAPE-Bookmark-file-1>\n",
        "<DL><p>\n",
        "<DT><A HREF=\"https://example.com/\" ADD_DATE=\"1700000000\">示例站点</A>\n",
        "<DT><A HREF=\"https://x.cn/a\" ADD_DATE=\"1700000001\">另一个</A>\n",
        "</DL><p>\n",
    );
    let (entries, dropped) = parse(html, 1_700_000_100_000);
    assert_eq!(dropped, 0);
    assert_eq!(entries.len(), 2);
    assert_eq!(entries[0].url, "https://example.com/");
    assert_eq!(entries[0].title, "示例站点");
    assert_eq!(entries[0].created_at_ms, Some(1_700_000_000_000));
    assert_eq!(entries[1].created_at_ms, Some(1_700_000_001_000));
}

#[test]
fn 属性顺序与大小写不保证() {
    let html = "<DT><a add_date='1699999999' href='https://mixed.cn/'>倒序属性</A>";
    let (entries, _) = parse(html, 1_700_000_100_000);
    assert_eq!(entries.len(), 1);
    assert_eq!(entries[0].url, "https://mixed.cn/");
    assert_eq!(entries[0].created_at_ms, Some(1_699_999_999_000));
}

#[test]
fn data_href_不抢匹配() {
    let html =
        "<DT><A data-href=\"https://evil.example/x\" HREF=\"https://good.example/\">测试</A>";
    let (entries, _) = parse(html, 1_700_000_100_000);
    assert_eq!(entries.len(), 1);
    assert_eq!(entries[0].url, "https://good.example/");
}

#[test]
fn 无引号_href() {
    let html = "<DT><A HREF=https://bare.example/x>无引号</A>";
    let (entries, _) = parse(html, 1_700_000_100_000);
    assert_eq!(entries[0].url, "https://bare.example/x");
}

#[test]
fn 无收尾标签_标题为空但条目保留() {
    let html = "<DT><A HREF=\"https://nc.example/\">没有收尾";
    let (entries, _) = parse(html, 1_700_000_100_000);
    assert_eq!(entries.len(), 1);
    assert_eq!(entries[0].title, "");
    assert_eq!(entries[0].url, "https://nc.example/");
}

#[test]
fn 收尾标签容忍空白与大小写() {
    let html = "<DT><A HREF=\"https://a.example/\">标题一</A >\n<DT><A HREF=\"https://b.example/\">标题二</a>";
    let (entries, _) = parse(html, 1_700_000_100_000);
    assert_eq!(entries.len(), 2);
    assert_eq!(entries[0].title, "标题一");
    assert_eq!(entries[1].title, "标题二");
}

#[test]
fn 单趟实体解码_保往返性() {
    // &amp;lt; 只解成字面量 &lt;，不得二次解码成 <（导出→导入往返一致）
    let html = "<DT><A HREF=\"https://e.example/\">Use &amp;lt;div&amp;gt;</A>";
    let (entries, _) = parse(html, 1_700_000_100_000);
    assert_eq!(entries[0].title, "Use &lt;div&gt;");
}

#[test]
fn href_里的实体被解码() {
    let html = "<DT><A HREF=\"https://e.example/?a=1&amp;b=2\">链接</A>";
    let (entries, _) = parse(html, 1_700_000_100_000);
    assert_eq!(entries[0].url, "https://e.example/?a=1&b=2");
}

#[test]
fn 未知实体与_amp_大小写原样保留() {
    let html = "<DT><A HREF=\"https://e.example/\">&AMP; &unknown; &nbsp;x</A>";
    let (entries, _) = parse(html, 1_700_000_100_000);
    // &AMP; 大小写敏感不解码；&unknown; 原样；&nbsp; 解成空格
    assert_eq!(entries[0].title, "&AMP; &unknown;  x");
}

#[test]
fn href_为空或缺失跳过_不中断整体() {
    let html = concat!(
        "<DT><A HREF=\"\">空href</A>\n",
        "<DT><A>无href</A>\n",
        "<DT><A HREF=\"https://ok.example/\">好条目</A>\n",
    );
    let (entries, _) = parse(html, 1_700_000_100_000);
    assert_eq!(entries.len(), 1);
    assert_eq!(entries[0].url, "https://ok.example/");
}

// ------------------------------------------------------------- parseAddDate

#[test]
fn add_date_秒与毫秒自适应() {
    let now = 1_700_000_000_000i64;
    // 秒（Netscape 约定）
    assert_eq!(parse_add_date("1700000000", now), Some(1_700_000_000_000));
    // ≥1e11 按毫秒
    assert_eq!(parse_add_date("1700000000000", now), Some(1_700_000_000_000));
    // 边界：1e11 秒 = 公元 5138 年，必被上界拒收；1e11 毫秒 = 1973 年，合法
    assert_eq!(parse_add_date("100000000000", now), Some(100_000_000_000));
}

#[test]
fn add_date_非法值回落_none() {
    let now = 1_700_000_000_000i64;
    assert_eq!(parse_add_date("", now), None);
    assert_eq!(parse_add_date("abc", now), None);
    assert_eq!(parse_add_date("0", now), None);
    assert_eq!(parse_add_date("-5", now), None);
    assert_eq!(parse_add_date("12a4", now), None);
    assert_eq!(parse_add_date("99999999999999999", now), None); // >16 位
    // 未来超过一天（钟表错乱/单位猜错兜底）
    assert_eq!(parse_add_date("1700086401", now), None); // now+1d+1s（秒）之后
}

// ------------------------------------------------------------- 上限与清洗

#[test]
fn 原始条目上限_超出计数为_dropped() {
    let now = 1_700_000_100_000i64;
    let mut html = String::new();
    for i in 0..20_005 {
        html.push_str(&format!(
            "<DT><A HREF=\"https://cap.example/{i}\">t</A>\n"
        ));
    }
    let (entries, dropped) = parse(&html, now);
    assert_eq!(entries.len(), RAW_PARSE_ENTRY_CAP);
    assert_eq!(dropped, 5);
}

#[test]
fn 文件体积超限直接空结果() {
    let big = "x".repeat(MAX_IMPORT_CHARS + 1);
    let (entries, dropped) = parse(&big, 1_700_000_100_000);
    assert!(entries.is_empty());
    assert_eq!(dropped, 0);
}

#[test]
fn sanitize_scheme_白名单_去重_上限() {
    let now = 1_700_000_100_000i64;
    let html = concat!(
        "<DT><A HREF=\"https://a.example/\">a</A>\n",
        "<DT><A HREF=\"javascript:alert(1)\">js</A>\n",
        "<DT><A HREF=\"about:blank\">about</A>\n",
        "<DT><A HREF=\"file:///tmp/x\">file</A>\n",
        "<DT><A HREF=\"https://a.example/\">批内重复</A>\n",
        "<DT><A HREF=\"  https://a.example/  \">批内重复带空白</A>\n",
    );
    let (raw, _) = parse(html, now);
    let mut existing = HashSet::new();
    existing.insert("https://a.example/".to_string()); // 与库内已有重复
    let (out, skipped) = sanitize(&raw, &existing);
    assert!(out.is_empty());
    assert_eq!(skipped, raw.len() as u64);
}

#[test]
fn sanitize_标题截断到上限() {
    let long_title = "标".repeat(300);
    let html = format!("<DT><A HREF=\"https://t.example/\">{long_title}</A>");
    let (raw, _) = parse(&html, 1_700_000_100_000);
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
    let (raw, _) = parse(&html, 1_700_000_100_000);
    let (out, skipped) = sanitize(&raw, &HashSet::new());
    assert_eq!(skipped, 0);
    assert_eq!(out[0].title.chars().count(), MAX_TITLE_LENGTH);
    // 末尾必须是完整的 emoji（不是它的半个代理）
    assert!(out[0].title.ends_with('🎈'));
}

// ------------------------------------------------------------- isHttpUrl

#[test]
fn 仅放行_http_https() {
    assert!(is_http_url("http://a.com"));
    assert!(is_http_url("HTTPS://a.com"));
    assert!(!is_http_url("about:blank"));
    assert!(!is_http_url("javascript:alert(1)"));
    assert!(!is_http_url("file:///x"));
    assert!(!is_http_url("ftp://x"));
    assert!(!is_http_url("//x")); // 无 scheme
    assert!(!is_http_url(""));
}

// ------------------------------------------------------------- export

#[test]
fn 导出为标准_netscape_格式() {
    let bookmarks = vec![
        Bookmark {
            url: "https://example.com/?a=1&b=2".to_string(),
            title: "示例 & <站点>".to_string(),
            created_at: 1_700_000_000_123,
        },
        Bookmark {
            url: "https://blank.example/".to_string(),
            title: "  ".to_string(), // 空白标题 → 回落 URL
            created_at: 1_700_000_001_000,
        },
    ];
    let expected = concat!(
        "<!DOCTYPE NETSCAPE-Bookmark-file-1>\n",
        "<!-- This is an automatically generated file. It will be read and overwritten. DO NOT EDIT! -->\n",
        "<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n",
        "<TITLE>Bookmarks</TITLE>\n",
        "<H1>Bookmarks</H1>\n",
        "<DL><p>\n",
        "<DT><A HREF=\"https://example.com/?a=1&amp;b=2\" ADD_DATE=\"1700000000\">示例 &amp; &lt;站点&gt;</A>\n",
        "<DT><A HREF=\"https://blank.example/\" ADD_DATE=\"1700000001\">https://blank.example/</A>\n",
        "</DL><p>\n",
    );
    assert_eq!(export(&bookmarks), expected);
}

#[test]
fn 导出导入往返() {
    let bookmarks = vec![Bookmark {
        url: "https://r.example/".to_string(),
        title: "Use &lt;div&gt;".to_string(),
        created_at: 1_700_000_000_000,
    }];
    let html = export(&bookmarks);
    let (entries, _) = parse(&html, 1_700_000_100_000);
    assert_eq!(entries.len(), 1);
    assert_eq!(entries[0].url, "https://r.example/");
    // 往返稳定：标题含实体样文字时，导出把 & 转义为 &amp;lt;，导入单趟解码还原为字面量 &lt;——不改写
    assert_eq!(entries[0].title, "Use &lt;div&gt;");
    assert_eq!(entries[0].created_at_ms, Some(1_700_000_000_000));
}

// ------------------------------------------------------------- 复杂度哨兵

#[test]
fn 对抗性输入_整篇小于号加a_保持线性() {
    // 修复前的正则实现在这类输入上是 O(n²)（实测 7.1s）；线性实现必须毫秒级
    let big = "<a ".repeat(500_000); // 2MB
    let t0 = std::time::Instant::now();
    let (entries, _) = parse(&big, 1_700_000_100_000);
    assert!(entries.is_empty());
    assert!(t0.elapsed().as_secs() < 2, "必须线性，实测 {:?}", t0.elapsed());
}

#[test]
fn 对抗性输入_有大于号但永无收尾_保持线性() {
    // 每轮从缓存位置继续而非重扫文末
    let big = "<a >".repeat(500_000); // 2MB
    let t0 = std::time::Instant::now();
    let (entries, _) = parse(&big, 1_700_000_100_000);
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
        let (entries, _) = parse(&s, 1_700_000_100_000);
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
