//! `UrlUtils` 差分测试：用例与 JVM 侧 `UrlUtilsTest.kt` / `PercentEncodeTest.kt` /
//! `SchemeGateTest.kt` 同源（同输入 ⇒ 同输出）。

use tangsnow_core::percent_encode::percent_encode;
use tangsnow_core::search_engines::SearchEngine;
use tangsnow_core::url_utils::*;

fn engine() -> SearchEngine {
    SearchEngine::new("test", "https://search.test/?q={q}", Some("q"))
}

// ------------------------------------------------------------- looksLikeUri

#[test]
fn 带协议与路径的完整地址识别为网址() {
    assert!(looks_like_uri("https://example.com/a/b?c=d"));
    assert!(looks_like_uri("http://example.com"));
}

#[test]
fn 裸域名识别为网址() {
    assert!(looks_like_uri("example.com"));
    assert!(looks_like_uri("sub.example.com/path"));
    assert!(looks_like_uri("example.com:8080"));
}

#[test]
fn localhost_与内网设备名识别为网址而非搜索词() {
    assert!(looks_like_uri("localhost"));
    assert!(looks_like_uri("localhost:8080"));
    assert!(looks_like_uri("nas:5000"));
}

#[test]
fn 中文域名识别为网址() {
    assert!(looks_like_uri("例子.测试"));
}

#[test]
fn 普通词句不是网址() {
    assert!(!looks_like_uri("hello"));
    assert!(!looks_like_uri("今天天气怎么样"));
    assert!(!looks_like_uri(""));
    assert!(!looks_like_uri("   "));
}

#[test]
fn 路径里的_at_是合法用法_不能误伤() {
    // 社交 handle 是合法路径，不能误判
    assert!(looks_like_uri("twitter.com/@jack"));
}

#[test]
fn authority_含_at_的裸输入按搜索处理_防钓鱼() {
    // trusted.com@evil.com：主流浏览器对这种裸输入按搜索处理
    assert!(!looks_like_uri("trusted.com@evil.com"));
}

#[test]
fn 含空白的输入一律不是网址() {
    assert!(!looks_like_uri("example.com 空格"));
    assert!(!looks_like_uri("aa bb.com"));
}

// ------------------------------------------------------------- resolveInfo

#[test]
fn http_与_https_直接透传且不标记为搜索() {
    let r = resolve_info("https://example.com/x", &engine()).unwrap();
    assert_eq!(r.url, "https://example.com/x");
    assert!(!r.is_search);
}

#[test]
fn 裸域名补_https() {
    let r = resolve_info("example.com", &engine()).unwrap();
    assert_eq!(r.url, "https://example.com");
    assert!(!r.is_search);
}

#[test]
fn localhost_与内网主机补_http() {
    assert_eq!(
        resolve_info("localhost:8080", &engine()).unwrap().url,
        "http://localhost:8080"
    );
    assert_eq!(
        resolve_info("nas:5000", &engine()).unwrap().url,
        "http://nas:5000"
    );
}

#[test]
fn 中文域名转_punycode_后按网址导航() {
    let r = resolve_info("例子.测试", &engine()).unwrap();
    assert!(r.url.starts_with("https://xn--"));
    assert!(!r.is_search);
}

#[test]
fn 普通词句走搜索引擎且标记为搜索() {
    let r = resolve_info("棠雪 浏览器", &engine()).unwrap();
    assert!(r.is_search);
    // 关键词必须被百分号编码后放进模板（空格按 Uri.encode 语义 = %20）
    assert_eq!(
        r.url,
        "https://search.test/?q=%E6%A3%A0%E9%9B%AA%20%E6%B5%8F%E8%A7%88%E5%99%A8"
    );
}

#[test]
fn 空输入返回_none() {
    assert!(resolve_info("", &engine()).is_none());
    assert!(resolve_info("   ", &engine()).is_none());
    assert!(resolve("", &engine()).is_none());
    assert_eq!(
        resolve("example.com", &engine()),
        resolve_info("example.com", &engine()).map(|r| r.url)
    );
}

// ------------------------------------------------------- isLocalNetworkAddress

#[test]
fn rfc1918_与链路本地为局域网() {
    for url in [
        "http://192.168.1.1/",
        "http://10.0.0.15",
        "http://172.16.0.1/",
        "http://172.31.255.255/",
        "http://169.254.10.20/",
    ] {
        assert!(is_local_network_address(url), "应判为局域网：{url}");
    }
}

#[test]
fn 回环与公网不判为局域网() {
    for url in [
        "http://127.0.0.1/",     // 回环明确排除
        "http://localhost/",     // 本机不算局域网
        "http://172.32.0.1/",    // 172 段出界
        "http://172.15.0.1/",    // 172 段出界
        "http://8.8.8.8/",       // 公网
        "http://192.169.1.1/",   // 相邻段
    ] {
        assert!(!is_local_network_address(url), "不应判为局域网：{url}");
    }
}

#[test]
fn local_后缀判为局域网() {
    assert!(is_local_network_address("http://nas.local/"));
    assert!(is_local_network_address("nas.local"));
}

#[test]
fn ipv4_必须严格点分十进制() {
    for url in [
        "http://010.0.0.1",     // 前导零
        "http://+10.0.0.1",     // 带符号
        "http://192.168.01.1",  // 段内前导零
        "http://1.2.3.999",     // 段超界
    ] {
        assert!(!is_local_network_address(url), "不应判为局域网：{url}");
    }
}

#[test]
fn ipv4_mapped_ipv6_的三种写法都按内嵌_ipv4_判局域网() {
    // 压缩 / 展开零 / 十六进制内嵌：内核实际按 192.168.1.1 连接，
    // 字面量是 IPv6 ⇒ 不认就会漏弹权限框
    for url in [
        "http://[::ffff:192.168.1.1]/",
        "http://[0:0:0:0:0:ffff:192.168.1.1]/",
        "http://[::ffff:c0a8:101]/", // c0a8:101 = 192.168.1.1
        "http://[0::ffff:10.0.0.2]/",
    ] {
        assert!(is_local_network_address(url), "应判为局域网（mapped）：{url}");
    }
}

#[test]
fn mapped_公网地址与非_mapped_形式不误判() {
    for url in [
        "http://[::ffff:8.8.8.8]/",       // mapped 但公网
        "http://[ffff::192.168.1.1]/",    // ffff 在前：非 mapped 结构
        "http://[::1:192.168.1.1]/",      // 前缀零组数不足
        "http://[::fffe:192.168.1.1]/",   // 第 6 组不是 ffff
        "http://[::ffff:192.168.1.1.9]/", // 内嵌段数不对（5 段）
    ] {
        assert!(!is_local_network_address(url), "不应判为局域网：{url}");
    }
    // 边界补钉：c0a8:808 = 192.168.8.8（属 192.168/16 → 真）；
    // fe80 前缀与 mapped 混合——按 fe80 链路本地已判真
    assert!(is_local_network_address("http://[::ffff:c0a8:808]/"));
    assert!(is_local_network_address("http://[fe80::ffff:192.168.1.1]/"));
}

#[test]
fn ipv6_ULA_判为局域网() {
    assert!(is_local_network_address("http://[fd00::1]/"));
    assert!(is_local_network_address("http://[fc00::abcd]/"));
    assert!(!is_local_network_address("http://[fe70::1]/")); // fe80::/10 之外
}

// ------------------------------------------------------------- scheme 闸门

#[test]
fn scheme_解析大小写不敏感且不含冒号() {
    assert_eq!(scheme_of("http://a.com"), "http");
    assert_eq!(scheme_of("HTTPS://a.com"), "https");
    assert_eq!(scheme_of("about:blank"), "about");
    assert_eq!(scheme_of("file:///data/data/x/files/a.json"), "file");
    assert_eq!(scheme_of("moz-extension://uuid/x"), "moz-extension");
    assert_eq!(scheme_of("mailto:a@b"), "mailto");
    // 非法：无冒号 / 冒号在前 / 首字符非字母 / 含非法字符
    assert_eq!(scheme_of("example.com"), "");
    assert_eq!(scheme_of(":blank"), "");
    assert_eq!(scheme_of("1http://x"), "");
    assert_eq!(scheme_of("ht tp://x"), "");
}

#[test]
fn 三分法互不重叠_网页档_禁用档_交系统档() {
    let web = ["http", "https", "about", "data", "blob"];
    let never = ["file", "moz-extension", "javascript"];
    let external = [
        "mailto", "tel", "market", "intent", "geo", "whatsapp", "package", "content",
    ];

    for s in web {
        assert!(is_web_navigation_scheme(Some(s)), "{s} 应属网页档");
        assert!(!is_never_web_content_scheme(Some(s)), "{s} 不得同时属禁用档");
    }
    for s in never {
        assert!(is_never_web_content_scheme(Some(s)), "{s} 应属禁用档");
        assert!(!is_web_navigation_scheme(Some(s)), "{s} 不得属网页档");
    }
    for s in external {
        assert!(!is_web_navigation_scheme(Some(s)), "{s} 不该属网页档");
        assert!(
            !is_never_web_content_scheme(Some(s)),
            "{s} 不该属禁用档（误挡即功能回归）"
        );
    }
    // null scheme：两档都不命中
    assert!(!is_web_navigation_scheme(None));
    assert!(!is_never_web_content_scheme(None));
}

#[test]
fn 可携带判据_排除内部页与临时文档() {
    assert!(!is_portable_url(Some("about:blank")));
    assert!(!is_portable_url(Some("data:text/html,hi")));
    assert!(!is_portable_url(Some("blob:https://a.com/x")));
    assert!(!is_portable_url(Some("file:///tmp/x")));
    assert!(!is_portable_url(Some("content://media/x")));
    assert!(is_portable_url(Some("https://example.com/")));
    assert!(is_portable_url(Some("mailto:a@b.com"))); // mailto 可分享
    assert!(!is_portable_url(None));
    assert!(!is_portable_url(Some("   "))); // 空白 ⇒ 不可携带
}

// ------------------------------------------------------- extractUrlFromText

#[test]
fn 提取_标题加链接_形态的第一个链接() {
    assert_eq!(
        extract_url_from_text("好文推荐 https://example.com/a?b=1 快看"),
        Some("https://example.com/a?b=1".to_string())
    );
}

#[test]
fn cjk_标点处硬截断() {
    // 分享文案把说明文字直接黏在链接后（无空格）
    assert_eq!(
        extract_url_from_text("https://x.com/1。转疯了"),
        Some("https://x.com/1".to_string())
    );
    assert_eq!(
        extract_url_from_text("https://x.com/a，后续文字"),
        Some("https://x.com/a".to_string())
    );
}

#[test]
fn 尾随标点连续剥离() {
    assert_eq!(
        extract_url_from_text("https://example.com/a."),
        Some("https://example.com/a".to_string())
    );
    // 成对右括号也被剥：刻意取舍（分享场景「括号结尾后接正文」更常见）
    assert_eq!(
        extract_url_from_text("见 https://en.wikipedia.org/wiki/Disambiguation_(d)）"),
        Some("https://en.wikipedia.org/wiki/Disambiguation_(d".to_string())
    );
    assert_eq!(
        extract_url_from_text("https://a.com/x”"),
        Some("https://a.com/x".to_string())
    );
}

#[test]
fn 截断的百分号编码残渣剥回完整边界() {
    assert_eq!(
        extract_url_from_text("https://e.com/a%"),
        Some("https://e.com/a".to_string())
    );
    assert_eq!(
        extract_url_from_text("https://e.com/a%2"),
        Some("https://e.com/a".to_string())
    );
    // 完整的 %XY 保留
    assert_eq!(
        extract_url_from_text("https://e.com/a%20b."),
        Some("https://e.com/a%20b".to_string())
    );
}

#[test]
fn 纯文本没有链接返回_none() {
    assert_eq!(extract_url_from_text("这是一段没有链接的文字"), None);
    assert_eq!(extract_url_from_text(""), None);
}

// ------------------------------------------------------- percentEncode 对齐

#[test]
fn 百分号编码与_uri_encode_逐字节一致() {
    let keep = "abcXYZ0189_-!.~'()*";
    assert_eq!(percent_encode(keep), keep);
    assert_eq!(percent_encode(" "), "%20");
    assert_eq!(percent_encode("/"), "%2F");
    assert_eq!(percent_encode("?"), "%3F");
    assert_eq!(percent_encode("&"), "%26");
    assert_eq!(percent_encode("="), "%3D");
    assert_eq!(percent_encode("+"), "%2B");
    assert_eq!(percent_encode("棠"), "%E6%A3%A0");
    assert_eq!(percent_encode("棠雪"), "%E6%A3%A0%E9%9B%AA");
}

#[test]
fn about_页地址栏直达而_data_与_blob_维持进搜索() {
    // 与主包 UrlUtils.resolveInfo（提交 37e0b0d）同口径。移植时这一分支整个漏了，
    // 敲 about:license 会被当搜索词丢给引擎 —— 本用例就是那条回归的钉子。
    let e = engine();
    for page in ["about:license", "about:buildinfo", "about:blank", "ABOUT:CONFIG"] {
        let r = resolve_info(page, &e).unwrap();
        assert_eq!(r.url, page, "{page} 应原样直达");
        assert!(!r.is_search, "{page} 不该被判成搜索");
    }
    // 刻意**只放行 about:**：data: 的顶层导航 Firefox 59 起已封禁（反钓鱼），
    // blob: 从地址栏新输入时会话里没有注册对象、打开必为空 —— 两者维持进搜索。
    assert!(resolve_info("data:text/html,hi", &e).unwrap().is_search);
    assert!(resolve_info("blob:https://a/b", &e).unwrap().is_search);
}
