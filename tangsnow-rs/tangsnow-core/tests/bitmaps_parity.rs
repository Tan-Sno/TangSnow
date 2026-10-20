//! `Bitmaps`（位图采样/裁剪数学）差分测试。
//!
//! ## 采样那一半的期望值来自**两侧共享的 JSON 夹具**（2026-10-20 审查报告 C-14）
//!
//! 此前这里与 JVM 侧 `BitmapsSampleSizeTest.kt` **各写一份期望值** —— 那是**镜像**：
//! 改实现 + 同步改本侧的镜像期望值 ⇒ 两侧照样全绿，而两边的采样口径已经漂了
//! （采样不足 → 解码出上百 MB 位图直接崩；采样过头 → 背景图糊）。
//! 现在两边读**同一份**夹具（`tests/fixtures/bitmaps_sample_size_cases.json`）。
//!
//! 居中裁剪（`center_crop_rect`）两侧函数名与语义各写各的，留在本文件里。

mod common;

use tangsnow_core::bitmaps::{center_crop_rect, required_sample_size};

const FOUR_MP: i64 = 4_000_000;

/// 共享夹具（编译期读入；路径相对本文件）。
const CASES: &str = include_str!("fixtures/bitmaps_sample_size_cases.json");

// ------------------------------------------------------------- 采样

#[test]
fn 采样倍数与Kotlin侧同口径() {
    let v = common::load(CASES);
    for c in v["cases"].as_array().unwrap() {
        let id = c["id"].as_str().expect("id 必须是字符串");
        let a = c["args"].as_array().expect("args 必须是数组");
        let w = a[0].as_i64().expect("args[0] 必须是整数") as i32;
        let h = a[1].as_i64().expect("args[1] 必须是整数") as i32;
        let rw = a[2].as_i64().expect("args[2] 必须是整数") as i32;
        let rh = a[3].as_i64().expect("args[3] 必须是整数") as i32;
        let max = a[4].as_i64().expect("args[4] 必须是整数");
        let sample = required_sample_size(w, h, rw, rh, max);
        assert_eq!(
            sample as i64,
            c["expected"].as_i64().expect("expected 必须是整数"),
            "夹具用例「{id}」"
        );
        // ⚠️ 这里**不**断言「像素上限」：退化输入（目标尺寸非正 ⇒ 直接返回 1）本来就不满足它，
        // 像素上限是**性质**（见下一条用例），不是逐点的期望值。
    }
}

#[test]
fn 任何输入下结果都满足像素上限且必然收敛() {
    let cases: [(i32, i32, i32, i32); 4] = [
        (12000, 8000, 1080, 2400),
        (30000, 20000, 4000, 4000),
        (1, 1, 10, 10),
        (100000, 100000, 1, 1),
    ];
    for (w, h, rw, rh) in cases {
        let sample = required_sample_size(w, h, rw, rh, FOUR_MP);
        assert!(sample >= 1, "sample 必须为正");
        assert!(
            (w / sample) as i64 * (h / sample) as i64 <= FOUR_MP,
            "w={w} h={h} sample={sample} 超出像素上限",
        );
    }
}

#[test]
fn 非法尺寸返回_1_而不是死循环() {
    assert_eq!(required_sample_size(0, 0, 100, 100, FOUR_MP), 1);
    assert_eq!(required_sample_size(-5, 100, 100, 100, FOUR_MP), 1);
}

#[test]
fn 目标尺寸非正时返回_1_而不是除零崩溃() {
    assert_eq!(required_sample_size(4000, 3000, 0, 0, FOUR_MP), 1);
    assert_eq!(required_sample_size(4000, 3000, -1, 100, FOUR_MP), 1);
    assert_eq!(required_sample_size(4000, 3000, 100, -1, FOUR_MP), 1);
}

#[test]
fn 上限极大时退化为纯按尺寸采样() {
    assert_eq!(required_sample_size(4096, 4096, 1024, 1024, i64::MAX), 4);
}

// ------------------------------------------------------------- 居中裁剪

fn assert_consistent(r: [i32; 4], sw: i32, sh: i32, tw: i32, th: i32) {
    let [x, y, w, h] = r;
    assert!(x >= 0, "x 越界: {x}");
    assert!(y >= 0, "y 越界: {y}");
    assert!(w > 0 && x + w <= sw, "w 越界: w={w} x={x} sw={sw}");
    assert!(h > 0 && y + h <= sh, "h 越界: h={h} y={y} sh={sh}");
    // 宽高比一致：交叉相乘判据，容忍 1px 取整
    assert!(
        (w as i64 * th as i64 - h as i64 * tw as i64).abs() <= tw.max(th) as i64,
        "宽高比偏离目标 ({w}×{h} vs {tw}×{th})",
    );
}

#[test]
fn 宽幅源图配竖向目标_裁掉左右不放大() {
    let r = center_crop_rect(8000, 500, 1080, 2400);
    assert_consistent(r, 8000, 500, 1080, 2400);
    assert_eq!(r[2], 225);
    assert_eq!(r[3], 500);
    assert!(r[2] as i64 * r[3] as i64 <= 8000i64 * 500);
}

#[test]
fn 竖幅源图配横向目标_裁掉上下() {
    let r = center_crop_rect(500, 8000, 2400, 1080);
    assert_consistent(r, 500, 8000, 2400, 1080);
    assert_eq!(r[2], 500);
    assert_eq!(r[3], 225);
}

#[test]
fn 方形源配竖向目标_左右各裁一半() {
    let r = center_crop_rect(1000, 1000, 1080, 2400);
    assert_consistent(r, 1000, 1000, 1080, 2400);
    assert_eq!(r[2], 450);
    assert_eq!(r[3], 1000);
    assert_eq!(r[0], 275, "居中");
    assert_eq!(r[1], 0);
}

#[test]
fn 宽高比恰好相等时原样返回整张() {
    let r = center_crop_rect(1080, 2400, 1080, 2400);
    assert_consistent(r, 1080, 2400, 1080, 2400);
    assert_eq!([r[0], r[1], r[2], r[3]], [0, 0, 1080, 2400]);
}

#[test]
fn 极端_1px_源图与长宽比不越界也不除零() {
    let cases = [
        center_crop_rect(1, 1, 1080, 2400),
        center_crop_rect(1, 4000, 1080, 2400),
        center_crop_rect(4000, 1, 1080, 2400),
    ];
    for r in cases {
        assert!(r[2] > 0 && r[3] > 0);
    }
}
