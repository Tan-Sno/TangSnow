//! 搜索引擎表与关键词回显。
//!
//! Kotlin 源：`data/SearchEngines.kt`。`all(prefs)` / `current(prefs)` 依赖
//! `PreferenceStore`（Android 偏好层），不属纯逻辑，不移植——Rust 侧由调用方
//! 把自定义引擎追加进切片后交给 [`find`]。

use crate::percent_encode::percent_encode;
use std::sync::OnceLock;
use url::Url;

pub const DEFAULT_ID: &str = "bing_cn";
pub const CUSTOM_ID_PREFIX: &str = "custom_";

/// 一个搜索引擎 = id（偏好里的存储值）+ 搜索模板 + 结果页关键词参数名。
///
/// Kotlin 的 `labelRes`（@StringRes 本地化名）是 Android 资源层概念，不移植；
/// `label`（自定义引擎名）保留。模板里用 `{q}`（或 `%s`）代表关键词占位。
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SearchEngine {
    pub id: String,
    pub template: String,
    /// 自定义引擎的展示名；内置引擎为空
    pub label: String,
    /// 搜索结果页的关键词参数名（用于把结果页 URL 还原成关键词）
    pub query_param: Option<String>,
    pub is_custom: bool,
}

impl SearchEngine {
    pub fn new(id: &str, template: &str, query_param: Option<&str>) -> Self {
        SearchEngine {
            id: id.to_string(),
            template: template.to_string(),
            label: String::new(),
            query_param: query_param.map(str::to_string),
            is_custom: false,
        }
    }

    /// 生成搜索 URL（与 Kotlin `SearchEngine.searchUrl` 逐字节一致）。
    pub fn search_url(&self, query: &str) -> String {
        Self::build_search_url(&self.template, query)
    }

    /// 模板层面的搜索 URL 生成（JNI 桥接用，避免在桥层构造完整结构体）。
    pub fn build_search_url(template: &str, query: &str) -> String {
        let trimmed = crate::java_trim(query);
        let encoded = percent_encode(trimmed);
        let substituted = template.replace("{q}", &encoded).replace("%s", &encoded);
        if substituted != template {
            return substituted;
        }
        // 模板不含占位符：视为没有查询参数，补一个 q
        let base = if crate::java_trim(template).is_empty() {
            DEFAULT_FALLBACK
        } else {
            template
        };
        let glue = if base.contains('?') { "&q=" } else { "?q=" };
        format!("{base}{glue}{encoded}")
    }
}

const DEFAULT_FALLBACK: &str = "https://www.bing.com/search";

/// 内置搜索引擎（顺序与 Kotlin `builtins` 一致）。
pub fn builtins() -> &'static [SearchEngine] {
    static BUILTINS: OnceLock<Vec<SearchEngine>> = OnceLock::new();
    BUILTINS.get_or_init(|| {
        vec![
            SearchEngine::new("bing_cn", "https://cn.bing.com/search?q={q}", Some("q")),
            SearchEngine::new(
                "bing",
                "https://global.bing.com/search?q={q}&mkt=en-US&setlang=zh-Hans",
                Some("q"),
            ),
            SearchEngine::new("baidu", "https://www.baidu.com/s?wd={q}", Some("wd")),
            SearchEngine::new("sogou", "https://www.sogou.com/web?query={q}", Some("query")),
            SearchEngine::new("so360", "https://www.so.com/s?q={q}", Some("q")),
            SearchEngine::new("sm", "https://m.sm.cn/s?q={q}", Some("q")),
            SearchEngine::new("google", "https://www.google.com/search?q={q}", Some("q")),
            SearchEngine::new("ddg", "https://duckduckgo.com/?q={q}", Some("q")),
        ]
    })
}

/// 按 id 查找引擎，缺失/未知回退默认引擎（再退化到第一个内置，绝不 panic）。
pub fn find<'a>(all: &'a [SearchEngine], id: Option<&str>) -> &'a SearchEngine {
    all.iter()
        .find(|e| Some(e.id.as_str()) == id)
        .unwrap_or_else(|| {
            builtins()
                .iter()
                .find(|e| e.id == DEFAULT_ID)
                .unwrap_or(&builtins()[0])
        })
}

/// 同一引擎「模板主机之外」的其它官方主机（全部小写、可含子域）。
const HOST_ALIASES: &[(&str, &[&str])] = &[
    ("bing_cn", &["bing.com"]),
    ("baidu", &["m.baidu.com"]),
    ("sogou", &["m.sogou.com"]),
    ("so360", &["m.so.com"]),
    ("sm", &["sm.cn"]),
    ("ddg", &["html.duckduckgo.com"]),
];

/// 从模板里取出主机（纯字符串运算，与 Kotlin `templateHostOf` 一致）。
///
/// 输入如 `https://cn.bing.com/search?q={q}` → `cn.bing.com`（小写、无端口、去 userinfo）。
/// 取不到主机时返回 None。
pub fn template_host_of(template: &str) -> Option<String> {
    // 无 "://" 时按整串处理（自定义模板可能省略协议）
    let after_scheme = match template.find("://") {
        Some(i) => &template[i + 3..],
        None => template,
    };
    let host_part = after_scheme
        .split('/')
        .next()
        .unwrap_or(after_scheme)
        .split('?')
        .next()
        .unwrap_or(after_scheme)
        .split('#')
        .next()
        .unwrap_or(after_scheme);
    // 去 userinfo@ 与端口
    let host = host_part
        .rsplit('@')
        .next()
        .unwrap_or(host_part)
        .split(':')
        .next()
        .unwrap_or(host_part);
    if host.is_empty() {
        None
    } else {
        Some(host.to_lowercase())
    }
}

/// 该主机是否属于这个引擎（模板主机或其子域，或该引擎的官方别名主机）。
pub fn host_matches(host: &str, engine_id: &str, template_host: &str) -> bool {
    fn one_matches(host: &str, candidate: &str) -> bool {
        let bare = host.strip_prefix("www.").unwrap_or(host);
        let bare_candidate = candidate.strip_prefix("www.").unwrap_or(candidate);
        bare == bare_candidate
            // 子域判断：`bare.ends_with(".{candidate}")` 的零分配写法——
            // 结尾匹配 candidate 且其前一个字符恰为 `.`（原先每次循环 format! 分配）。
            || bare.len() > bare_candidate.len()
                && bare.ends_with(bare_candidate)
                && bare.as_bytes()[bare.len() - bare_candidate.len() - 1] == b'.'
    }
    // 先查模板主机、再查别名表：避免每次调用 Vec 分配 + extend。
    if one_matches(host, template_host) {
        return true;
    }
    match HOST_ALIASES.iter().find(|(id, _)| *id == engine_id) {
        Some((_, aliases)) => aliases.iter().any(|c| one_matches(host, c)),
        None => false,
    }
}

/// 把搜索结果页 URL 还原为用户输入的关键词；非搜索结果页返回 None。
///
/// Kotlin 源用 `android.net.Uri`（生产不可测的桩），Rust 用 WHATWG URL 标准实现
/// （`url` crate）——两者对 http/https 结果页的 host 与查询参数解码语义一致
/// （`getQueryParameter` 与 `Url::query_pairs` 都会把 `+` 解码成空格、取第一个同名参数）。
pub fn display_for_url(url: &str, engines: &[SearchEngine]) -> Option<String> {
    let parsed = Url::parse(url).ok()?;
    let host = parsed.host_str()?.trim_start_matches('[').trim_end_matches(']').to_lowercase();
    for engine in engines {
        // Kotlin 口径：queryParam / 模板主机缺失 ⇒ 跳过该引擎（continue 而非整体返回）
        let Some(param) = engine.query_param.as_deref() else {
            continue;
        };
        let Some(tpl_host) = template_host_of(&engine.template) else {
            continue;
        };
        if !host_matches(&host, &engine.id, &tpl_host) {
            continue;
        }
        for (k, v) in parsed.query_pairs() {
            // Kotlin `!query.isNullOrBlank()`：空串或全 Java 空白 ⇒ 不算关键词
            if k == param && !v.is_empty() && !v.chars().all(crate::java_is_whitespace) {
                return Some(v.into_owned());
            }
        }
    }
    None
}
