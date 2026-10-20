//! 效率基准冒烟（std::time 计时；非严格 criterion，但足以验证量级与无病态回退）。
//!
//! 记录口径：host 编译（TCG 加速下无），关注**相对量级**——单次微秒级 = 无 JNI 瓶颈
//! 压力；若出现毫秒级即异常（回查 regex 重编译/分配风暴）。
//!
//! ⚠️ **本文件曾经零断言**（只有 `println!`）：它永远不会失败，却照样计入 README 声明的
//! 「N/N 通过」，而那道门禁正是拿这个数当依据 ⇒ 一个空转的用例稀释了门禁的分量
//! （2026-10-20 审查报告 C-12）。现在 [bench] 自带**耗时上界断言**，
//! 且「计入 README 测试数的每个用例都必须有断言」由 `ReleaseGateConsistencyTest` 钉住。

use std::time::Instant;
use tangsnow_core::crash_logger;
use tangsnow_core::download_filename;
use tangsnow_core::dt_prompts;
use tangsnow_core::percent_encode;
use tangsnow_core::update_logic;
use tangsnow_core::url_utils;

/// 单次操作的耗时上界（微秒）。
///
/// 取 500µs 是**极宽松**的闸：正常是 0.1~几 µs（差两个数量级），
/// 宽松是为了不让「CI 机器正忙」变成随机红灯；而真的出现**病态回退**
/// （regex 重编译 / 分配风暴）时是**毫秒级**（≥1000µs）⇒ 仍能抓住。
const MAX_US_PER_OP: f64 = 500.0;

fn bench<F: FnMut()>(name: &str, mut f: F) -> f64 {
    let n = 10_000u32;
    let start = Instant::now();
    for _ in 0..n {
        f();
    }
    let elapsed = start.elapsed();
    let per_op = elapsed.as_secs_f64() * 1e6 / n as f64;
    println!(
        "{:<34} {:>8.2} µs/op  ({} iters in {:?})",
        name,
        per_op,
        n,
        elapsed
    );
    per_op
}

#[test]
fn bench_smoke() {
    let addr = "https://例子.测试/路径?q=棠雪";
    let share = "看看这个 https://example.com/a.pdf?q=x。转疯了";
    let cd = "attachment; filename*=UTF-8''%E6%A3%A0%E9%9B%AA.apk";
    let url = "https://dl.example.com/path/%E6%A3%A0%E9%9B%AA.apk?token=x";

    let rows: Vec<(&str, f64)> = vec![
        (
            "resolveInfo-like resolve",
            bench("resolveInfo-like resolve", || {
                let _ = url_utils::looks_like_uri(addr);
            }),
        ),
        (
            "extract_url_from_text",
            bench("extract_url_from_text", || {
                let _ = url_utils::extract_url_from_text(share);
            }),
        ),
        (
            "percent_encode(cjk)",
            bench("percent_encode(cjk)", || {
                let _ = percent_encode::percent_encode(addr);
            }),
        ),
        (
            "dl parse_file_name",
            bench("dl parse_file_name", || {
                let _ = download_filename::parse_file_name(Some(cd), Some(url));
            }),
        ),
        (
            "dl sanitize_file_name",
            bench("dl sanitize_file_name", || {
                let _ = download_filename::sanitize_file_name("evil\u{200B}nam<>e?.apk");
            }),
        ),
        (
            "update normalize_version",
            bench("update normalize_version", || {
                let _ = update_logic::normalize_version("v2.1.0-beta1");
            }),
        ),
        (
            "update is_newer",
            bench("update is_newer", || {
                let _ = update_logic::is_newer("v2.10.0", "2.9.0");
            }),
        ),
        (
            "dt parse time",
            bench("dt parse time", || {
                let _ = dt_prompts::parse_loose("time", "10:30:45");
            }),
        ),
        (
            "crash redact_urls",
            bench("crash redact_urls", || {
                let _ = crash_logger::redact_urls(
                    "GET https://user:pw@example.com/a/b?q=1 200 https://x.example/y",
                );
            }),
        ),
    ];

    // ⚠️ 本文件**唯一的失败条件**：没有它，这个「基准」永远绿（见文件头），
    // 却照样计入 README 声明的测试数（2026-10-20 审查报告 C-12）。
    let (worst_name, worst) = rows
        .iter()
        .copied()
        .max_by(|a, b| a.1.total_cmp(&b.1))
        .expect("bench 行数不为 0");
    assert!(
        worst < MAX_US_PER_OP,
        "{worst_name}: 单次 {worst:.2}µs/op 超过上界 {MAX_US_PER_OP}µs —— 病态回退（regex 重编译 / 分配风暴）？"
    );
}
