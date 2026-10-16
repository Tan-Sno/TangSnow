//! `HistoryRepo` 纯函数部分差分测试：入库前缀过滤（Kotlin `shouldSkipHistoryUrl`
//! 的 Kotlin 实现同口径）与 SQL 绑定变量分批。锁定「什么 URL 不许进用户历史」
//! 的边界 —— 漏一个 `javascript:` 前缀就是隐私泄露。

use tangsnow_core::history::{should_skip_history_url, url_batches, SKIP_PREFIXES};

#[test]
fn 内部页与脚本协议不进历史() {
    for u in [
        "about:blank",
        "about:config",
        "chrome://settings",
        "data:text/html,hello",
        "blob:https://a.com/uuid",
        "javascript:void(0)",
        "moz-extension://uuid/page.html",
    ] {
        assert!(should_skip_history_url(u), "{u} 应被跳过");
    }
}

#[test]
fn 大小写不敏感() {
    assert!(should_skip_history_url("ABOUT:BLANK"));
    assert!(should_skip_history_url("JavaScript:void(0)"));
    assert!(should_skip_history_url("Data:"));
}

#[test]
fn 普通网页进历史() {
    for u in [
        "https://example.com",
        "http://a.com/b?c=d",
        "file:///sdcard/x.pdf",
        "example.com",
    ] {
        assert!(!should_skip_history_url(u), "{u} 不应被跳过");
    }
}

#[test]
fn 前缀清单与_kotlin_侧一致() {
    // ⚠️ 这是**镜像锚点**，不是跨语言比对：清单硬编码在本用例里，只防「Rust 侧无声改动」。
    // Kotlin 侧 `HistoryRepo.SKIP_PREFIXES` 改了这里**照样绿**（2026-10-16 同类扩散扫描轮发现
    // 整个 `tests/*_parity.rs` 系列都是这个形状，而别名表漏行就是这么漏掉的）。
    // 真正的两表并排比对在 `app/src/test/java/.../util/CrossLanguageLiteralParityTest`。
    assert_eq!(
        SKIP_PREFIXES,
        ["about:", "chrome:", "data:", "blob:", "javascript:", "moz-extension:"]
    );
}

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
