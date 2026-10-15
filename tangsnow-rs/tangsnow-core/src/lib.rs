//! # tangsnow-core
//!
//! 棠雪 TangSnow（<https://github.com/Tan-Sno/TangSnow>）的 Rust 核心逻辑库。
//!
//! 从 Kotlin 仓库中**零 Android 依赖**的纯逻辑模块逐行移植：
//!
//! | Rust 模块 | Kotlin 源 | 内容 |
//! |---|---|---|
//! | [`percent_encode`] | `util/UrlUtils.kt` 顶层函数 | 与 Android `Uri.encode` 逐字节一致的百分号编码 |
//! | [`url_utils`] | `util/UrlUtils.kt` | 地址栏解析（网址 vs 搜索）、局域网判定、scheme 闸门、分享文本提链接 |
//! | [`search_engines`] | `data/SearchEngines.kt` | 引擎表、搜索 URL 生成、主机别名、结果页关键词回显 |
//! | [`bookmark_html`] | `data/BookmarkHtml.kt` | Netscape 书签 HTML 解析/清洗/导出 |
//! | [`history`] | `data/repo/HistoryRepo.kt` | 历史入库过滤与 SQL 分批的纯函数部分 |
//!
//! ## 移植原则
//!
//! 1. **行为逐字节对齐**：JVM 单测的用例全部搬进 `tests/` 做差分验证（同输入同输出）。
//! 2. **复杂度不变量保留**：Kotlin 侧「单趟线性扫描防 O(n²)」「单趟实体解码保往返性」
//!    的设计在 Rust 侧原样保留，并有对抗语料测试钉住。
//! 3. **明确记录口径差异**：正则 `\s`/`\w`（Java 默认 ASCII，Rust 默认 Unicode）、
//!    `Character.isWhitespace` 与 Rust `char::is_whitespace` 等差异处，用显式 ASCII
//!    类 / 自实现 `java_is_whitespace` 抹平，无法抹平的在 README「已知口径差异」表中列明。

pub mod bookmark_html;
pub mod bitmaps;
pub mod crash_logger;
pub mod download_filename;
pub mod dt_prompts;
pub mod history;
pub mod legal_text;
pub mod percent_encode;
pub mod search_engines;
pub mod update_logic;
pub mod url_utils;

#[cfg(feature = "jni")]
pub mod jni;

/// Kotlin `Char.isWhitespace()`（= Java `Character.isWhitespace`）的 Rust 对齐实现。
///
/// Rust `char::is_whitespace` 用 Unicode `White_Space` 属性，与 Java 的差异点：
///  - U+00A0 / U+2007 / U+202F（不换行空格系）：Java 判 **false**，Rust 判 true；
///  - U+001C..=U+001F（信息分隔符）：Java 判 **true**，Rust 判 false。
///
/// Kotlin 源码里的 `trim()` / `isWhitespace()` 一律经此函数走到与 JVM 相同的判定。
pub fn java_is_whitespace(c: char) -> bool {
    matches!(
        c,
        '\t' | '\n' | '\u{0B}' | '\u{0C}' | '\r' | '\u{1C}'..='\u{1F}'
    ) || (c.is_whitespace() && !matches!(c, '\u{A0}' | '\u{2007}' | '\u{202F}'))
}

/// Kotlin `String.trim()` 的对齐实现（两端剥掉 [`java_is_whitespace`] 命中的字符）。
pub fn java_trim(s: &str) -> &str {
    s.trim_matches(java_is_whitespace)
}

/// Java 默认（未开 `UNICODE_CHARACTER_CLASS`）正则里 `\s` 的等价 ASCII 类。
/// 所有从 Kotlin 搬来的正则，`\s` 必须替换成此串（或其取反形式）。
pub const JAVA_WS_CLASS: &str = r"[ \t\n\x0B\x0C\r]";

/// Java 默认正则里 `\S` 的等价类（ASCII 空白的补集，**包含**全部非 ASCII 字符）。
pub const JAVA_NON_WS_CLASS: &str = r"[^ \t\n\x0B\x0C\r]";
