//! `to_ascii_url` 的**主机标签口径**回归钉。
//!
//! 期望值不是想出来的：2026-10-15 用主线那份还没被替换的 Kotlin 实现
//! （`app/src/main/java/io/github/tan_sno/tangsnow/util/UrlUtils.kt:74-92`，
//! 走 `java.net.IDN.toASCII(host, ALLOW_UNASSIGNED)`）在 JBR 25.0.3 上逐条量出来，
//! 再与 Rust 侧（`idna` 1.x / UTS46）对撞 40 条语料得到 9 条分歧，分两组钉在这里：
//!
//! 1. `与主线_逐条相同` —— 两侧**必须**永远一致，改任何一条都要同时改另一侧与 README 差异表；
//!    其中空标签三条（`a..b.com` 等）是本轮修掉的真实缺陷：修前 Rust 把它们**放过**，
//!    于是"当网址导航"，而主线与浏览器都把它当搜索词。
//! 2. `有意分歧_冻结现值` —— 已知且刻意不一致（多为 Rust 更严/更新），把当前值钉住，
//!    将来谁改谁负责；完整口径见 `tangsnow-rs/README.md` 的 IDNA 差异表。

use tangsnow_core::url_utils::to_ascii_url;

#[test]
fn 与主线_空白标签一律拒而尾点保留() {
    // 主线：IDN.toASCII 对**开头/中间**的空标签抛 IllegalArgumentException → runCatching → null；
    // 但容忍**尾点**并原样保留。Rust 同口径。
    assert_eq!(to_ascii_url("a..b.com"), None);
    assert_eq!(to_ascii_url(".example.com"), None);
    assert_eq!(to_ascii_url("a...b"), None);
    assert_eq!(to_ascii_url(".."), None);
    assert_eq!(to_ascii_url("a..b.co.uk"), None);
    assert_eq!(to_ascii_url("example.com."), Some("example.com.".into()));
    assert_eq!(
        to_ascii_url("example.com.:8080/x"),
        Some("example.com.:8080/x".into())
    );
    assert_eq!(to_ascii_url("xn--tda.com."), Some("xn--tda.com.".into()));
}

#[test]
fn 与主线_合法与非法输入的分类一致() {
    assert_eq!(to_ascii_url("例子.测试"), Some("xn--fsqu00a.xn--0zwm56d".into()));
    assert_eq!(to_ascii_url("münchen.de"), Some("xn--mnchen-3ya.de".into()));
    assert_eq!(to_ascii_url("xn--mnchen-3ya.de"), Some("xn--mnchen-3ya.de".into()));
    assert_eq!(to_ascii_url("0.0.0.0"), Some("0.0.0.0".into()));
    assert_eq!(to_ascii_url("1.2.3.4:80/x"), Some("1.2.3.4:80/x".into()));
    // 连字符在标签首尾：两侧都不管（LDH 规则不在这一层），必须保持"不额外收紧"
    assert_eq!(to_ascii_url("-a.com"), Some("-a.com".into()));
    assert_eq!(to_ascii_url("a-.com"), Some("a-.com".into()));
    // 分类面：空白、userinfo、无点、全角句号 —— 都按搜索处理
    assert_eq!(to_ascii_url("a b.com"), None);
    assert_eq!(to_ascii_url("a@b.com"), None);
    assert_eq!(to_ascii_url("例子"), None);
    assert_eq!(to_ascii_url("中文。com"), None);
}

#[test]
fn 有意分歧_冻结现值_rust更严或映射规则更新() {
    // ① ACE 回环校验：Java 完全不验，Rust 拒非法 punycode。
    assert_eq!(to_ascii_url("xn--.com"), None);
    assert_eq!(to_ascii_url("xn--xn--a.com"), None);
    // ② 未分配码位：Java 因 ALLOW_UNASSIGNED 放行，Rust(UTS46) 拒。
    assert_eq!(to_ascii_url("\u{0378}.com"), None);
    // ③ ß：Java 走过渡映射（ß→ss，正是 IDNA2008 废弃它的原因：同形字），Rust 保留。
    assert_eq!(to_ascii_url("ß.de"), Some("xn--zca.de".into()));
    assert_eq!(to_ascii_url("FAß.de"), Some("xn--fa-hia.de".into()));
    // ④ 行尾 sigma：Java 不折叠（xn--4xa），UTS46 折叠成 σ（xn--3xa）。
    assert_eq!(to_ascii_url("\u{03c2}.com"), Some("xn--3xa.com".into()));
    // ⑤ 全角拉丁：Java 转小写却保留 `CD` 原大小写，Rust 统一小写。
    assert_eq!(to_ascii_url("\u{ff21}\u{ff22}.CD"), Some("ab.cd".into()));
    // ⑥ 退化输入 `.`：Java 返回 "."，Rust 拒。
    assert_eq!(to_ascii_url("."), None);
    // ⑦ 零宽连接符在主机中：Java 抹掉继续转，Rust 拒。
    assert_eq!(to_ascii_url("a\u{200d}b.com"), None);
}
