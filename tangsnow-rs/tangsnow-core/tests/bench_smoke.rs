//! 效率基准冒烟（std::time 计时；非严格 criterion，但足以验证量级与无病态回退）。
//!
//! 记录口径：host 编译（TCG 加速下无），关注**相对量级**——单次微秒级 = 无 JNI 瓶颈
//! 压力；若出现毫秒级即异常（回查 regex 重编译/分配风暴）。

use std::time::Instant;
use tangsnow_core::crash_logger;
use tangsnow_core::download_filename;
use tangsnow_core::dt_prompts;
use tangsnow_core::percent_encode;
use tangsnow_core::update_logic;
use tangsnow_core::url_utils;

fn bench<F: FnMut()>(name: &str, mut f: F) {
    let n = 10_000u32;
    let start = Instant::now();
    for _ in 0..n {
        f();
    }
    let elapsed = start.elapsed();
    println!(
        "{:<34} {:>8.2} µs/op  ({} iters in {:?})",
        name,
        elapsed.as_secs_f64() * 1e6 / n as f64,
        n,
        elapsed
    );
}

#[test]
fn bench_smoke() {
    let addr = "https://例子.测试/路径?q=棠雪";
    let share = "看看这个 https://example.com/a.pdf?q=x。转疯了";
    let cd = "attachment; filename*=UTF-8''%E6%A3%A0%E9%9B%AA.apk";
    let url = "https://dl.example.com/path/%E6%A3%A0%E9%9B%AA.apk?token=x";

    bench("resolveInfo-like resolve", || {
        let _ = url_utils::looks_like_uri(addr);
    });
    bench("extract_url_from_text", || {
        let _ = url_utils::extract_url_from_text(share);
    });
    bench("percent_encode(cjk)", || {
        let _ = percent_encode::percent_encode(addr);
    });
    bench("dl parse_file_name", || {
        let _ = download_filename::parse_file_name(Some(cd), Some(url));
    });
    bench("dl sanitize_file_name", || {
        let _ = download_filename::sanitize_file_name("evil\u{200B}nam<>e?.apk");
    });
    bench("update normalize_version", || {
        let _ = update_logic::normalize_version("v2.1.0-beta1");
    });
    bench("update is_newer", || {
        let _ = update_logic::is_newer("v2.10.0", "2.9.0");
    });
    bench("dt parse time", || {
        let _ = dt_prompts::parse_loose("time", "10:30:45");
    });
    bench("crash redact_urls", || {
        let _ = crash_logger::redact_urls(
            "GET https://user:pw@example.com/a/b?q=1 200 https://x.example/y",
        );
    });
}
