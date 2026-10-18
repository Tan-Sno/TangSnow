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

/// Kotlin `Char.isWhitespace()` 的 Rust 对齐实现。
///
/// Kotlin stdlib（`kotlin/text/CharJVM.kt`）把它实现为
/// `Character.isWhitespace(c) || Character.isSpaceChar(c)` —— 是**两个** Java 判定的并集，
/// 而 `String.trim()` / `isBlank()` / `any { it.isWhitespace() }` 全走它。
///
/// Rust `char::is_whitespace` 用 Unicode `White_Space` 属性，与该并集的差异点：
///  - U+00A0 / U+2007 / U+202F（不换行空格系，属 `Zs`）：`isSpaceChar` 判 **true**，
///    而 `Character.isWhitespace` 特意排除 ⇒ 并集里**算空白**，Rust 也算 true（一致）；
///  - U+0085（NEL）：`White_Space` 判 true，但它既非 `Zs/Zl/Zp` 也不在
///    `Character.isWhitespace` 的显式表里 ⇒ Kotlin 判 **false**（Rust 多算了它）；
///  - U+001C..=U+001F（信息分隔符）：Java 判 **true**，Rust 判 false。
///
/// 所以「Rust 的 `White_Space` 去掉 U+0085，再补上 U+001C..=U+001F」即等于 Kotlin 的集合。
/// 旧实现恰好**反向**：多减了 U+00A0/U+2007/U+202F（`Zs` 一族，Kotlin 是算空白的），
/// 又漏了 U+0085 —— 两侧在 4 个码点上不等价（2026-10-17 修）。
pub fn java_is_whitespace(c: char) -> bool {
    matches!(
        c,
        '\t' | '\n' | '\u{0B}' | '\u{0C}' | '\r' | '\u{1C}'..='\u{1F}'
    ) || (c.is_whitespace() && c != '\u{0085}')
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

/// 解 JNI `GetStringUTFChars` 的字节 —— 它是 **modified UTF-8（CESU-8）**，不是 UTF-8。
///
/// 两处必须记住的编码差异：
///  - 星平面字符（emoji、生僻字）在 Java 侧写成**两个代理项、各 3 字节共 6 字节**，
///    按 UTF-8 解是非法序列；
///  - `U+0000` 写成 `0xC0 0x80`（不是单个 `0x00`，否则 C 字符串会在中途截断）。
///
/// 所以这一层的正确写法只有"按 CESU-8 解"。原先 `opt_str` 直接用
/// `CStr::to_string_lossy()`（走 `from_utf8_lossy`），一颗 emoji 被静默换成**六个** `U+FFFD`
/// （实测：校验器在每个非法字节处各换一个，2026-10-15 复核 H3 时更正了台账里「两个」的说法）；
/// 而出向 `JNIEnv::new_string` 用的是 `cesu8::to_java_cesu8` —— 一进一出不同源，
/// 于是「导出再导入」不可逆（书签标题、搜索词、URL 都会被污染）。2026-10-15 外部审查 H3。
///
/// 把解码从 `JNIEnv` 上摘下来还有一个原因：JNI 层在本机没有 JVM 参与不了测试
/// （`cargo test --features jni` 只保证能编能链），纯函数才能被 host 用例钉住。
///
/// 坏输入（残缺代理对等）回落 `from_utf8_lossy`，与 jni 0.21.1
/// `From<&JNIStr> for Cow<str>` 的口径一致 —— 边界上不抛、不 panic。
pub fn decode_java_modified_utf8(bytes: &[u8]) -> String {
    match cesu8::from_java_cesu8(bytes) {
        Ok(cow) => cow.into_owned(),
        Err(_) => String::from_utf8_lossy(bytes).into_owned(),
    }
}
