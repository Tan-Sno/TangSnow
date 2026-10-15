//! Netscape 书签 HTML 的解析、导入清洗与导出。
//!
//! Kotlin 源：`data/BookmarkHtml.kt`。设计不变量原样保留：
//!  - **单趟线性扫描**（三条硬约束：查找游标只增不减；`closeExhausted` 一旦得出
//!    即缓存；属性正则只作用于单个开始标签字符串）——防对抗性输入 O(n²)；
//!  - **单趟实体解码**（`&amp;` 与其它实体同趟互斥处理，保导出→导入往返性）；
//!  - `ADD_DATE` 秒/毫秒以 1e11 为界自适应，拒收非法与「未来超过一天」的值。

use regex::Regex;
use std::sync::LazyLock;

/// 待插入的书签条目。`created_at_ms` = 原始 `ADD_DATE`（epoch **毫秒**）；
/// None = 文件没给或值非法（回落「导入时刻」）。
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Entry {
    pub url: String,
    pub title: String,
    pub created_at_ms: Option<i64>,
}

impl Entry {
    pub fn new(url: impl Into<String>, title: impl Into<String>, created_at_ms: Option<i64>) -> Self {
        Entry {
            url: url.into(),
            title: title.into(),
            created_at_ms,
        }
    }
}

/// 导出用的已入库书签（对应 Kotlin `BrowserDb.Bookmark` 的 url/title/createdAt 三元组）。
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Bookmark {
    pub url: String,
    pub title: String,
    pub created_at: i64,
}

/// 收尾标签前缀。刻意不用正则：查找游标单调推进即可线性。
const CLOSE_PREFIX: &[u8; 3] = b"</a";

/// 导入清洗时单条标题的上限（超出截断，不拒收）。
pub const MAX_TITLE_LENGTH: usize = 200;

/// 单次导入的条目上限：防御超大/恶意文件，>1000 条的书签属于异常体量。
pub const MAX_IMPORT_COUNT: usize = 1000;

/// 导入文件体积上限（字符数）：5MB 的纯文本书签文件已远超任何正常导出。
pub const MAX_IMPORT_CHARS: usize = 5_000_000;

/// 解析阶段的**原始**条目收集上限（入库上限的 20 倍）。
/// 超出上限的条目**不再静默丢弃**：计数并由调用方如实并入「跳过」。
pub const RAW_PARSE_ENTRY_CAP: usize = 20_000;

/// `href` 属性：单/双引号或无引号；lookbehind 防止 `data-href=` 里的 href= 被抢匹配。
/// 作用域是**单个开始标签字符串**（标签之间互不重叠 ⇒ 全文总扫描量与原文等长）。
/// Kotlin `\w`/`\s` 均为 ASCII 语义，此处显式写 ASCII 类。
static HREF_ATTR: LazyLock<fancy_regex::Regex> = LazyLock::new(|| {
    fancy_regex::Regex::new(
        r#"(?i)(?<![A-Za-z0-9_-])href[ \t\n\x0B\x0C\r]*=[ \t\n\x0B\x0C\r]*("([^"]*)"|'([^']*)'|([^ \t\n\x0B\x0C\r>]+))"#,
    )
    .unwrap()
});

/// `ADD_DATE` 属性：与 [`HREF_ATTR`] 同一套引号/lookbehind 口径（属性名大小写不敏感）。
static ADD_DATE_ATTR: LazyLock<fancy_regex::Regex> = LazyLock::new(|| {
    fancy_regex::Regex::new(
        r#"(?i)(?<![A-Za-z0-9_-])add_date[ \t\n\x0B\x0C\r]*=[ \t\n\x0B\x0C\r]*("([^"]*)"|'([^']*)'|([^ \t\n\x0B\x0C\r>]+))"#,
    )
    .unwrap()
});

/// 基本实体解码（书签导出文件里最常见的几个）；未知实体原样保留。
///
/// **单趟**正则替换——`&amp;` 与其它实体同趟互斥处理：链式先后替换会让
/// `&amp;lt;` 被二次解码成 `<`，破坏导出→导入往返性。单趟下 `&amp;lt;`
/// 只解成字面量 `&lt;`。
static ENTITY: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"&(amp|lt|gt|quot|#39|apos|nbsp);").unwrap());

/// 单趟实体解码。`&amp;lt;` → `&lt;`（不二次解码），未知实体（`&AMP;` 等）原样保留。
pub fn decode_entities(s: &str) -> String {
    ENTITY
        .replace_all(s, |caps: &regex::Captures| {
            match caps.get(1).map(|m| m.as_str()) {
                Some("amp") => "&".to_string(),
                Some("lt") => "<".to_string(),
                Some("gt") => ">".to_string(),
                Some("quot") => "\"".to_string(),
                Some("#39") | Some("apos") => "'".to_string(),
                Some("nbsp") => " ".to_string(),
                _ => caps.get(0).unwrap().as_str().to_string(),
            }
        })
        .into_owned()
}

/// 解析 `ADD_DATE` 原始值为 epoch **毫秒**；非法一律返回 None（回落「导入时刻」）。
///
/// Netscape 约定是**秒**，但个别工具导出**毫秒**——以 1e11 为界区分：
/// 1e11 秒 = 公元 5138 年（必被上界拒收），1e11 毫秒 = 1973 年。
/// 拒收非纯数字、≤0、以及「未来超过一天」的值（钟表错乱/单位猜错的兜底）。
pub fn parse_add_date(raw: &str, now_ms: i64) -> Option<i64> {
    let s = crate::java_trim(raw);
    if s.is_empty() || s.len() > 16 || !s.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    let v: i64 = s.parse().ok()?;
    if v <= 0 {
        return None;
    }
    let seconds = if v >= 100_000_000_000 { v / 1000 } else { v };
    let ms = seconds * 1000;
    if ms >= 1 && ms <= now_ms + 86_400_000 {
        Some(ms)
    } else {
        None
    }
}

/// 解析阶段的字符数口径（对齐 Kotlin `String.length` = UTF-16 code unit 数）。
fn utf16_len(s: &str) -> usize {
    s.chars().map(char::len_utf16).sum()
}

/// `lt` 处是不是开始标签的开头（`<a` 紧跟一个空白字符，大小写不敏感）。
///
/// 刻意不用 Unicode 空白：那会比既有 `Regex("<a\\s…")` 的口径更宽，凭空放宽语义。
fn is_anchor_start(text: &str, lt: usize) -> bool {
    let b = text.as_bytes();
    lt + 2 < b.len() && (b[lt + 1] == b'a' || b[lt + 1] == b'A') && is_tag_space(b[lt + 2])
}

/// 与正则 `\s` 的默认（非 `UNICODE_CHARACTER_CLASS`）ASCII 空白集合一致。
fn is_tag_space(b: u8) -> bool {
    matches!(b, b' ' | b'\t' | b'\n' | b'\x0B' | b'\x0C' | b'\r')
}

/// 大小写不敏感地查找 `</a`，返回首个命中的字节下标（`from` 起，含）。
fn find_close_prefix(text: &str, from: usize) -> Option<usize> {
    let b = text.as_bytes();
    let n = b.len();
    let mut i = from;
    while i + 3 <= n {
        if b[i].eq_ignore_ascii_case(&b'<')
            && b[i + 1].eq_ignore_ascii_case(&b'/')
            && b[i + 2].eq_ignore_ascii_case(&b'a')
        {
            return Some(i);
        }
        i += 1;
    }
    None
}

/// 从书签 HTML 文本中解析全部条目（不做清洗，见 [`sanitize`]）。
/// 解析失败的条目（无 href / href 为空）直接跳过，不让单个坏行中断整体。
pub fn parse_import(text: &str) -> Vec<Entry> {
    parse_import_counted(text, None).0
}

/// [`parse_import`] 的可计数版本：除条目外，还回报「因 [`RAW_PARSE_ENTRY_CAP`]
/// 未收集的条数」。`now_ms` 供 `ADD_DATE` 合法性判定（None = 不判未来，全收）。
///
/// 计数与收集共用**同一趟**扫描（不额外扫文本）：达到上限后不再构造 Entry
///（内存仍有界），只累加被丢弃的条数，解码也一并省掉。
pub fn parse_import_counted(text: &str, now_ms: Option<i64>) -> (Vec<Entry>, u64) {
    if utf16_len(text) > MAX_IMPORT_CHARS {
        return (Vec::new(), 0);
    }
    let mut out: Vec<Entry> = Vec::new();
    let mut dropped: u64 = 0;
    let n = text.len();
    let bytes = text.as_bytes();
    let mut i = 0usize;
    // 收尾标签的查找游标，与「已确认此后无收尾标签」标志：二者共同消除重复扫尾
    let mut close_from = 0usize;
    let mut close_exhausted = false;
    loop {
        let lt = match text[i..].find('<') {
            Some(off) => i + off,
            None => break,
        };
        if !is_anchor_start(text, lt) {
            i = lt + 1;
            continue;
        }
        let gt = match text[lt..].find('>') {
            Some(off) => lt + off,
            None => break, // 此后连 '>' 都没有了，不可能再有完整的开始标签
        };
        let resume_at = gt + 1;
        let mut close_start: i64 = -1;
        // 默认从开始标签之后继续：与「收尾标签缺失」时的既有语义一致
        let mut next = resume_at;
        if !close_exhausted {
            let mut search_from = close_from.max(resume_at);
            loop {
                match find_close_prefix(text, search_from) {
                    None => {
                        // 从该位置起已无收尾标签 ⇒ 更靠后的位置同样没有，
                        // 后续条目一概按空标题收，且不必再扫尾
                        close_exhausted = true;
                        break;
                    }
                    Some(c) => {
                        let mut j = c + CLOSE_PREFIX.len();
                        while j < n && is_tag_space(bytes[j]) {
                            j += 1;
                        }
                        if j < n && bytes[j] == b'>' {
                            close_start = c as i64;
                            close_from = j + 1;
                            next = close_from;
                            break;
                        }
                        // `</a` 之后不是 '>'（如 `</abbr>`）：从下一位继续找
                        search_from = c + 1;
                    }
                }
            }
        }
        let title = if close_start >= 0 {
            &text[resume_at..close_start as usize]
        } else {
            ""
        };
        i = next;
        let tag = &text[lt..gt + 1];
        let href = match attr_value(&HREF_ATTR, tag, "href") {
            Some(h) => h,
            None => continue,
        };
        // Kotlin `href.isBlank()`：空串或全 Java 空白（trim 口径对齐 java_trim）
        if href.is_empty() || href.chars().all(crate::java_is_whitespace) {
            continue;
        }
        // ADD_DATE 可选：缺失/非法回落 None（导入时刻），不因时间坏而丢条目
        let add_date = attr_value(&ADD_DATE_ATTR, tag, "add_date")
            .and_then(|raw| match now_ms {
                Some(now) => parse_add_date(&raw, now),
                None => parse_add_date_unbounded(&raw),
            });
        // 原始条目收集上限在这里生效：达到上限后不再构造 Entry（内存有界化；
        // 入库数仍由 sanitize 裁到 MAX_IMPORT_COUNT），但**照样计数**，
        // 由调用方并入「跳过」如实提示，不再静默丢弃。
        if out.len() >= RAW_PARSE_ENTRY_CAP {
            dropped += 1;
        } else {
            out.push(Entry {
                url: crate::java_trim(&decode_entities(&href)).to_string(),
                title: crate::java_trim(&decode_entities(title)).to_string(),
                created_at_ms: add_date,
            });
        }
    }
    (out, dropped)
}

/// ASCII 大小写不敏感包含（无分配）。needle 必须全 ASCII。
fn contains_ci(haystack: &str, needle: &str) -> bool {
    let h = haystack.as_bytes();
    let n = needle.as_bytes();
    n.len() <= h.len() && h.windows(n.len()).any(|w| w.eq_ignore_ascii_case(n))
}

/// 属性值提取：对齐 Kotlin `(g[2].ifEmpty { g[3] }).ifEmpty { g[4] }`——
/// 双引号组 → 单引号组 → 无引号组，逐级回退；整个属性不命中返回 None。
///
/// `attr_ascii`：属性名（小写）。标签里连属性名都没有时直接短路——对抗性输入
/// （如整篇 `<a >`）会产生大量无属性标签，fancy-regex 回溯 VM 的启动开销
/// 实测决定复杂度哨兵能否毫秒级（预检后 2MB 从 4.6s 降到毫秒级）。
fn attr_value(re: &fancy_regex::Regex, tag: &str, attr_ascii: &str) -> Option<String> {
    if !contains_ci(tag, attr_ascii) {
        return None;
    }
    let caps = re.captures(tag).ok()??; // 首个命中
    let g = |i: usize| caps.get(i).map(|mm| mm.as_str()).unwrap_or("");
    let v = g(2);
    let v = if v.is_empty() { g(3) } else { v };
    let v = if v.is_empty() { g(4) } else { v };
    Some(v.to_string())
}

/// `parse_add_date` 的无上界变体（JNI 未传 now 时使用）：只拒收非正与非法格式。
fn parse_add_date_unbounded(raw: &str) -> Option<i64> {
    parse_add_date(raw, i64::MAX / 2 - 86_400_000)
}

/// 仅放行 http/https——about:/javascript:/file:/intent: 等一律不得经导入进入书签。
pub fn is_http_url(url: &str) -> bool {
    let colon = match url.find(':') {
        Some(c) => c,
        None => return false,
    };
    if colon == 0 {
        return false;
    }
    let scheme = url[..colon].to_lowercase();
    scheme == "http" || scheme == "https"
}

/// 导入清洗：scheme 必须 http/https，与 `existing_urls` 及批内自身去重，
/// 标题截断到 [`MAX_TITLE_LENGTH`]，总量截断到 [`MAX_IMPORT_COUNT`]。
/// 返回待插入条目与被跳过的条数（无效/重复/超上限），供界面如实提示。
pub fn sanitize(raw: &[Entry], existing_urls: &std::collections::HashSet<String>) -> (Vec<Entry>, u64) {
    let mut out: Vec<Entry> = Vec::with_capacity(raw.len().min(MAX_IMPORT_COUNT));
    let mut seen = existing_urls.clone();
    let mut skipped: u64 = 0;
    for e in raw {
        let url = crate::java_trim(&e.url);
        // 超上限 / 非 http(s) / 重复（含批内重复）一律计入「跳过」，界面如实提示
        if out.len() >= MAX_IMPORT_COUNT || !is_http_url(url) || !seen.insert(url.to_string()) {
            skipped += 1;
            continue;
        }
        out.push(Entry {
            url: url.to_string(),
            title: e.title.chars().take(MAX_TITLE_LENGTH).collect(),
            created_at_ms: e.created_at_ms,
        });
    }
    (out, skipped)
}

/// 最小 HTML 转义（& < > "），与主流浏览器导出格式兼容。
fn escape(s: &str) -> String {
    s.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
}

/// Kotlin `String.isBlank()` 口径（Java 空白）。
fn is_blank(s: &str) -> bool {
    s.is_empty() || s.chars().all(crate::java_is_whitespace)
}

/// 导出：把全部书签序列化为 Netscape 书签 HTML。
pub fn export(bookmarks: &[Bookmark]) -> String {
    let mut sb = String::with_capacity(256 + bookmarks.len() * 96);
    sb.push_str("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n");
    sb.push_str("<!-- This is an automatically generated file. It will be read and overwritten. DO NOT EDIT! -->\n");
    sb.push_str("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n");
    sb.push_str("<TITLE>Bookmarks</TITLE>\n");
    sb.push_str("<H1>Bookmarks</H1>\n");
    sb.push_str("<DL><p>\n");
    for b in bookmarks {
        sb.push_str("<DT><A HREF=\"");
        sb.push_str(&escape(&b.url));
        sb.push_str("\" ADD_DATE=\"");
        sb.push_str(&(b.created_at / 1000).to_string());
        sb.push_str("\">");
        sb.push_str(&escape(if is_blank(&b.title) { &b.url } else { &b.title }));
        sb.push_str("</A>\n");
    }
    sb.push_str("</DL><p>\n");
    sb
}
