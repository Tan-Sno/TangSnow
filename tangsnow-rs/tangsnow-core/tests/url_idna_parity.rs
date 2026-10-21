//! `to_ascii_url` 的**主机标签口径**回归钉。
//!
//! ## 期望值分两摊（2026-10-20 审查报告 C-14）
//!
//! 期望值不是想出来的：2026-10-21 用主线那份 Kotlin 实现
//! （`app/src/main/java/io/github/tan_sno/tangsnow/util/UrlUtils.kt`，
//! 走 `java.net.IDN.toASCII(host, ALLOW_UNASSIGNED)`）在 JBR 25.0.3 上逐条量出来，
//! 再与 Rust 侧（`idna` 1.x / UTS46）对撞 90 余条语料（85 条主干 + 10 条补充），得到两摊：
//!
//! 1. **两侧同口径** —— 收进**共享夹具** `tests/fixtures/url_idna_cases.json`，
//!    Kotlin 侧 `app/src/test/.../util/UrlIdnaParityTest.kt` 读**同一个文件**。
//!    改夹具必然两侧一起红；只改一侧的实现也必然两侧一起红。
//!    —— 此前这一摊是**各写一份期望值**（镜像）：改实现 + 同步改本侧镜像期望值
//!    ⇒ 两侧照样全绿，而两边口径已经漂。
//! 2. **有意分歧** —— Java 的 IDNA2003 与 Rust 的 UTS46 本来就不同
//!    （映射表、大小写折叠、ACE 回环校验、码位是否已分配、长度校验）。
//!    这些**不进夹具**：进了就等于逼两侧互相迁就，把「谁更新」变成「谁迁就谁」。
//!    两侧各留一条**单侧**用例把当前值钉死 —— 谁改谁负责；
//!    完整口径见 `tangsnow-rs/README.md` 的 IDN 差异表。
//!
//! ## 空标签那条为什么单独说
//!
//! 开头或中间的**空标签**两侧一律拒（Java 侧 `IDN.toASCII` 抛 IllegalArgumentException
//! → `runCatching` → null；Rust 侧显式判空标签），而**尾点**两侧都保留 ——
//! 此前 Rust 把 `a..b.com` 放过，比浏览器和主线都宽松：那条主机会被「当网址导航」，
//! 而主线把它当搜索词。

mod common;

use tangsnow_core::url_utils::to_ascii_url;

/// 共享夹具（编译期读入；路径相对本文件）。
const CASES: &str = include_str!("fixtures/url_idna_cases.json");

/// 取夹具里的一条用例（找不到 id ⇒ panic，绝不静默跳过）。
fn case(id: &str) -> common::Case {
    common::pick(common::load(CASES), id)
}

/// 夹具里的每一条都要过 —— 否则「加了新语料却没人读」会静默发生，
/// 而这正是本文件要根治的那类漏检（镜像的另一种形态）。
#[test]
fn 夹具全覆盖_每条用例都与共享夹具一致() {
    let v = common::load(CASES);
    let ids: Vec<String> = v["cases"]
        .as_array()
        .expect("夹具缺少 cases 数组")
        .iter()
        .map(|c| {
            c["id"]
                .as_str()
                .expect("夹具用例缺 id")
                .to_string()
        })
        .collect();
    assert!(!ids.is_empty(), "夹具里一条用例都没有 —— 门禁会空转");
    for id in ids {
        let c = case(&id);
        assert_eq!(
            to_ascii_url(&c.input),
            c.expected,
            "用例「{id}」与共享夹具不一致"
        );
    }
}

// ---------------------------------------------------------------------------
// 第一摊：两侧同口径（期望值全部来自共享夹具）
// ---------------------------------------------------------------------------

#[test]
fn 与主线_空白标签一律拒而尾点保留() {
    for id in [
        "空标签在标签中间一律拒",
        "空标签在开头一律拒",
        "连续空标签一律拒",
        "退化输入两个点一律拒",
        "多段域名里的空标签同样拒",
        "尾点被保留",
        "尾点加端口与路径原样保留",
        "ACE 标签上的尾点同样保留",
    ] {
        let c = case(id);
        assert_eq!(
            to_ascii_url(&c.input),
            c.expected,
            "用例「{id}」与共享夹具不一致"
        );
    }
}

#[test]
fn 与主线_合法与非法输入的分类一致() {
    for id in [
        "普通域名原样保留",
        "子域原样保留",
        "域名带端口原样保留",
        "域名带路径原样保留",
        "查询串按原样拼回",
        "标签首的连字符不得收紧",
        "标签尾的连字符不得收紧",
        "含下划线的标签原样保留",
        "IPv4 字面量原样保留",
        "IPv4 加端口与路径原样保留",
        "含空格一律拒",
        "含制表符一律拒",
        "authority 段含 userinfo 一律拒",
        "路径里的 at 不得误伤",
        "子域路径里的 at 不得误伤",
        "不含点就不是域名",
        "全角句号不算域名分隔符",
        "全角句号在标签中间按分隔符处理",
        "全角句号（FF0E）在标签中间按分隔符处理",
        "IPv6 字面量不是域名",
        "端口不校验数字",
        "多个冒号原样保留",
    ] {
        let c = case(id);
        assert_eq!(
            to_ascii_url(&c.input),
            c.expected,
            "用例「{id}」与共享夹具不一致"
        );
    }
}

#[test]
fn 与主线_已分配码位的_各文种域名同口径() {
    for id in [
        "中文域名转 punycode",
        "中文域名带尾点",
        "德文变音域名转 punycode",
        "已是 ACE 的域名原样保留",
        "德文 ß 的 ACE 形式原样保留",
        "日文域名转 punycode",
        "韩文域名转 punycode",
        "西里尔域名转 punycode",
        "阿拉伯域名转 punycode",
        "希腊域名转 punycode",
        "emoji 域名转 punycode",
    ] {
        let c = case(id);
        assert_eq!(
            to_ascii_url(&c.input),
            c.expected,
            "用例「{id}」与共享夹具不一致"
        );
    }
}

#[test]
fn 与主线_大小写折叠与_映射规则同口径() {
    for id in [
        "带点的土耳其 I 转写一致",
        "无点小写 i 转写一致",
        "带点 o 转写一致",
        "带斜杠 o 转写一致",
        "连字 oe 转写一致",
        "微符号与希腊 Mu 同形",
        "希腊 Mu 与微符号同形",
        "预组合重音转写一致",
        "分解重音转写一致",
        "天城文短元音转写一致",
        "中点转写一致",
        "全角拉丁字母转半角",
        "零宽空格被抹掉后继续转",
        "软连字符被抹掉后继续转",
        "不换行空格按非法码位拒",
        "标签恰好 63 字符（上限内）原样保留",
    ] {
        let c = case(id);
        assert_eq!(
            to_ascii_url(&c.input),
            c.expected,
            "用例「{id}」与共享夹具不一致"
        );
    }
}

// ---------------------------------------------------------------------------
// 第二摊：有意分歧（两侧各钉各的；这些**不进**共享夹具）
// ---------------------------------------------------------------------------

#[test]
fn 有意分歧_冻结现值_ace回环校验() {
    // Java 的 `IDN.toASCII` 完全不验 punycode，Rust 侧 idna 要解码校验 ⇒ 非法 ACE 标签被拒。
    assert_eq!(to_ascii_url("xn--.com"), None);
    assert_eq!(to_ascii_url("xn--xn--a.com"), None);
    assert_eq!(to_ascii_url("xn--a.com"), None);
    assert_eq!(to_ascii_url("xn--0.com"), None);
}

#[test]
fn 有意分歧_冻结现值_未分配码位() {
    // Java 因 ALLOW_UNASSIGNED 放行（并 punycode 出去），Rust(UTS46) 拒未分配码位。
    assert_eq!(to_ascii_url("\u{0378}.com"), None);
    assert_eq!(to_ascii_url("\u{05ff}.com"), None);
}

#[test]
fn 有意分歧_冻结现值_sharp_s过渡映射() {
    // Java 走 IDNA2003 的过渡映射（ß→ss，正是 IDNA2008 废弃它的原因：同形字/抢注），
    // Rust 保留原字符并 punycode 出去。
    assert_eq!(to_ascii_url("ß.de"), Some("xn--zca.de".into()));
    assert_eq!(to_ascii_url("FAß.de"), Some("xn--fa-hia.de".into()));
    assert_eq!(to_ascii_url("aß.com"), Some("xn--a-qfa.com".into()));
    assert_eq!(to_ascii_url("xß.com"), Some("xn--x-qfa.com".into()));
    // 大写 ẞ（U+1E9E）：Java 的 IDNA2003 不认这个码位（Unicode 3.2 里还没有）⇒ 原样 punycode；
    // UTS46 把它折叠成 ß ⇒ 两侧转出的 ASCII 不同。
    assert_eq!(to_ascii_url("ẞ.de"), Some("xn--zca.de".into()));
}

#[test]
fn 有意分歧_冻结现值_行尾sigma() {
    // Java 不折叠（xn--4xa），UTS46 折叠成 σ（xn--3xa）⇒ 同一主机转出不同 ASCII。
    assert_eq!(to_ascii_url("ς.com"), Some("xn--3xa.com".into()));
}

#[test]
fn 有意分歧_冻结现值_全角拉丁() {
    // Java 转小写却保留 `CD` 原大小写，Rust 统一小写。
    assert_eq!(to_ascii_url("\u{ff21}\u{ff22}.CD"), Some("ab.cd".into()));
}

#[test]
fn 有意分歧_冻结现值_退化输入单点() {
    // Java 返回 "."，Rust 拒（退化输入不是域名）。
    assert_eq!(to_ascii_url("."), None);
}

#[test]
fn 有意分歧_冻结现值_零宽连接符与不连接符() {
    // Java 抹掉零宽字符继续转，Rust 拒（UTS46 里这两个是 deviation，Rust 侧按 IDNA2008 收）。
    assert_eq!(to_ascii_url("a\u{200d}b.com"), None);
    assert_eq!(to_ascii_url("a\u{200c}b.com"), None);
}

#[test]
fn 有意分歧_冻结现值_ascii大小写不折叠() {
    // Java 的 `IDN.toASCII` 对**纯 ASCII 标签**走快路径、不做大小写折叠 ⇒ 原样保留；
    // UTS46 折叠 ⇒ 两侧转出的主机大小写不同（键相等性受影响）。
    assert_eq!(to_ascii_url("EXAMPLE.COM"), Some("example.com".into()));
}

#[test]
fn 有意分歧_冻结现值_全角句点造成空标签() {
    // 全角/半角句点在 UTS46 里是**标签分隔符**；Java 侧映射后仍是合法标签（a.b.com），
    // Rust 侧的空标签预检跑在**映射之前** ⇒ 映射后才出现的空标签漏检，
    // 于是转出 "..com" 这种**非法主机**（首标签为空）。
    assert_eq!(to_ascii_url("\u{ff0e}.com"), Some("..com".into()));
    assert_eq!(to_ascii_url("\u{ff61}.com"), Some("..com".into()));
}

#[test]
fn 有意分歧_冻结现值_标签超长() {
    // Java 侧 `IDN.toASCII` 强制标签 ≤ 63 字符（超了抛异常 → null），
    // Rust 侧 idna 默认不校验 DNS 长度 ⇒ 原样放行。
    let long64 = "a".repeat(64);
    assert_eq!(
        to_ascii_url(&format!("{long64}.com")),
        Some(format!("{long64}.com"))
    );
    let long128 = "a".repeat(128);
    assert_eq!(
        to_ascii_url(&format!("{long128}.com")),
        Some(format!("{long128}.com"))
    );
}
