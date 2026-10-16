//! `SearchEngines` 差分测试：用例与 JVM 侧 `SearchEnginesTest.kt` / `PercentEncodeTest.kt` 同源。

use tangsnow_core::search_engines::*;

// ------------------------------------------------------------- find

#[test]
fn 按_id_命中内置引擎() {
    let all = builtins();
    assert_eq!(find(all, Some("baidu")).id, "baidu");
    assert_eq!(find(all, Some("bing_cn")).id, "bing_cn");
}

#[test]
fn 未知_id_回退默认引擎而不是抛异常() {
    let all = builtins();
    assert_eq!(find(all, Some("nope")).id, DEFAULT_ID);
    assert_eq!(find(all, None).id, DEFAULT_ID);
    assert_eq!(find(&[], Some("baidu")).id, DEFAULT_ID);
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

#[test]
fn 占位符_q_替换为编码后的关键词() {
    let e = SearchEngine::new("x", "https://s.test/?q={q}", None);
    assert_eq!(e.search_url("abc"), "https://s.test/?q=abc");
    assert_eq!(
        e.search_url("棠雪 浏览器"),
        "https://s.test/?q=%E6%A3%A0%E9%9B%AA%20%E6%B5%8F%E8%A7%88%E5%99%A8"
    );
}

#[test]
fn 模板也支持百分号_s_占位符() {
    let e = SearchEngine::new("x", "https://s.test/?wd=%s", None);
    assert_eq!(e.search_url("abc"), "https://s.test/?wd=abc");
}

#[test]
fn 模板不含占位符时追加_q_参数() {
    let e = SearchEngine::new("x", "https://s.test/search", None);
    assert_eq!(e.search_url("abc"), "https://s.test/search?q=abc");
    let with_query = SearchEngine::new("y", "https://s.test/search?lang=cn", None);
    assert_eq!(with_query.search_url("abc"), "https://s.test/search?lang=cn&q=abc");
}

#[test]
fn 空模板回退到内置兜底地址() {
    let e = SearchEngine::new("x", "", None);
    assert_eq!(e.search_url("abc"), "https://www.bing.com/search?q=abc");
}

#[test]
fn 关键词前后空白被裁剪() {
    let e = SearchEngine::new("x", "https://s.test/?q={q}", None);
    assert_eq!(e.search_url("  abc  "), "https://s.test/?q=abc");
}

// ------------------------------------------------------------- templateHostOf

#[test]
fn 从模板取出主机() {
    assert_eq!(template_host_of("https://a.com").as_deref(), Some("a.com"));
    assert_eq!(template_host_of("https://a.com/").as_deref(), Some("a.com"));
    assert_eq!(template_host_of("https://a.com#x").as_deref(), Some("a.com"));
    assert_eq!(template_host_of("https://a.com:8443/search?q={q}").as_deref(), Some("a.com"));
    // 省略协议（自定义引擎可能这么写）
    assert_eq!(template_host_of("a.com/search?q={q}").as_deref(), Some("a.com"));
    // userinfo 要被去掉，只留主机
    assert_eq!(template_host_of("https://u:p@a.com/x").as_deref(), Some("a.com"));
    // 大小写归一
    assert_eq!(template_host_of("HTTPS://CN.Bing.COM/search?q={q}").as_deref(), Some("cn.bing.com"));
}

#[test]
fn 取不到主机时返回_none() {
    assert_eq!(template_host_of(""), None);
    assert_eq!(template_host_of("https:///search?q={q}"), None);
    assert_eq!(template_host_of("https://:8080/x"), None);
}

// ------------------------------------------------------------- hostMatches

#[test]
fn 模板主机及其子域命中() {
    assert!(host_matches("cn.bing.com", "bing_cn", "cn.bing.com"));
    assert!(host_matches("x.cn.bing.com", "bing_cn", "cn.bing.com"));
    assert!(host_matches("www.baidu.com", "baidu", "www.baidu.com"));
    assert!(host_matches("evil.com", "bing_cn", "cn.bing.com") == false);
    assert!(host_matches("bing_cnevil.com", "bing_cn", "cn.bing.com") == false);
}

#[test]
fn 主机别名命中_www_bing() {
    // 模板写 cn.bing.com，用户实际在 www.bing.com 上搜索 —— 地址栏要显示关键词
    assert!(host_matches("www.bing.com", "bing_cn", "cn.bing.com"));
    assert!(host_matches("m.baidu.com", "baidu", "www.baidu.com"));
    assert!(host_matches("html.duckduckgo.com", "ddg", "duckduckgo.com"));
}

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
