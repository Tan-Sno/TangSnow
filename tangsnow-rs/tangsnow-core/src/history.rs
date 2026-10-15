//! 历史仓库的纯函数部分。
//!
//! Kotlin 源：`data/repo/HistoryRepo.kt`。DB/协程侧（`add`/`search`/`delete`）
//! 属 Android I/O 层不移植，这里只收两条被 JVM 单测钉住的纯逻辑：
//! 入库前缀过滤与 SQL 绑定变量分批。

/// 这些前缀的 URL 不进历史：内部页、data/blob 临时文档、脚本、扩展页。
pub const SKIP_PREFIXES: [&str; 6] = [
    "about:",
    "chrome:",
    "data:",
    "blob:",
    "javascript:",
    "moz-extension:",
];

/// 历史入库过滤（大小写不敏感）。
pub fn should_skip_history_url(url: &str) -> bool {
    let lower = url.to_lowercase();
    SKIP_PREFIXES.iter().any(|p| lower.starts_with(p))
}

/// 单条 SQL 允许的绑定变量上限，留出余量（SQLite 旧版实测上限 999）。
pub const SQL_VARIABLE_LIMIT: usize = 900;

/// 把 `total` 个待查下标切成每批不超过 `size` 的区间（纯函数，便于单测覆盖边界）。
/// total == 0 或 size == 0 时返回空列表。
pub fn url_batches(total: usize, size: usize) -> Vec<std::ops::Range<usize>> {
    if total == 0 || size == 0 {
        return Vec::new();
    }
    (0..total)
        .step_by(size)
        .map(|start| start..std::cmp::min(start + size, total))
        .collect()
}
