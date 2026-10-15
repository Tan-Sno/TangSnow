//! `CrashLogger` 的 URL 脱敏差分测试：用例与 JVM 侧 `CrashLoggerRedactTest.kt`
//! 同源（同输入 ⇒ 同输出）。崩溃日志直接落盘、会被用户复制分享 —— 脱敏
//! 漏一个凭据就是隐私事故，多剥一个字符就毁掉排障价值，双侧锁定口径。

use tangsnow_core::crash_logger::redact_urls;

#[test]
fn url_被剥到_origin_加省略号() {
    assert_eq!(
        redact_urls("java.io.FileNotFoundException: https://example.com/a/b?q=1#f"),
        "java.io.FileNotFoundException: https://example.com/…",
    );
}

#[test]
fn 端口保留而路径查询片段丢弃() {
    assert_eq!(
        redact_urls("java.net.ConnectException: http://10.0.0.2:8080/download?token=secret"),
        "java.net.ConnectException: http://10.0.0.2:8080/…",
    );
}

#[test]
fn userinfo_里的凭据不得残留() {
    let out = redact_urls("boom https://user:pa55@example.com/private/x.pdf");
    assert!(!out.contains("pa55"), "脱敏后不得含凭据");
    assert!(!out.contains("user:"), "脱敏后不得含用户名");
    assert_eq!(out, "boom https://example.com/…");
}

#[test]
fn 路径里含_at_符号时_host_不得被误剥() {
    // userinfo 的 `@` 只在 authority 段合法；先截 authority 再剥 `@`，
    // 否则 /docs/@user 里的 `@` 会把 host 连同路径前缀整个吃掉
    assert_eq!(
        redact_urls("fail https://example.com/docs/@user?page=2"),
        "fail https://example.com/…",
    );
}

#[test]
fn userinfo_与路径含_at_符号同时存在时各归其位() {
    let out = redact_urls("fetch https://alice@s3.example.com/blobs/@me/x");
    assert!(!out.contains("alice"));
    assert_eq!(out, "fetch https://s3.example.com/…");
}

#[test]
fn 大小写_scheme_同样处理() {
    assert_eq!(redact_urls("HTTPS://example.com/x"), "HTTPS://example.com/…");
}

#[test]
fn 同一行的多个_url_都被处理() {
    assert_eq!(
        redact_urls("a https://a.com/1 b http://b.com/2"),
        "a https://a.com/… b http://b.com/…",
    );
}

#[test]
fn 非_url_文本一字不动() {
    let stack = "java.lang.IllegalStateException: session is not open\n    at io.github.tan_sno.tangsnow.MainActivity.onDestroy(MainActivity.kt:666)\n    at android.app.Activity.performDestroy(Activity.java:8305)";
    assert_eq!(redact_urls(stack), stack);
}

#[test]
fn 只有_scheme_没有域名时不会被误改成半截() {
    // `https://` 后面直接是空白：`\S+` 一个字符都没吃到 ⇒ 不匹配，原样保留
    assert_eq!(redact_urls("no url here: https://"), "no url here: https://");
}
