//! `Bitmaps`（位图采样/裁剪数学）差分测试。
//!
//! ## 两半的期望值都来自**两侧共享的 JSON 夹具**（2026-10-20 审查报告 C-14）
//!
//! 此前这里与 JVM 侧 `BitmapsSampleSizeTest.kt` / `BitmapsCoverRectTest.kt` **各写一份期望值**
//! —— 那是**镜像**：改实现 + 同步改本侧的镜像期望值 ⇒ 两侧照样全绿，而两边的口径已经漂了
//! （采样不足 → 解码出上百 MB 位图直接崩；采样过头 → 背景图糊；
//! 裁剪矩形偏几个像素 → 缩放后宽高比与目标不符，出现拉伸）。
//! 现在两边读**同一份**夹具：
//! - 采样：`tests/fixtures/bitmaps_sample_size_cases.json`
//! - 裁剪：`tests/fixtures/bitmaps_cover_rect_cases.json`
//!
//! ⚠️ 期望值**只写一份**：任何一侧想改口径都必须改夹具 —— 那一刻两侧一起变。
//! 留在本文件里的只有**性质**断言（矩形落在源图内、宽高比与目标一致）：
//! 它们与逐点的期望值是两类东西，混进夹具会让「这条期望值错」和「这个性质不成立」分不清。

mod common;

use tangsnow_core::bitmaps::{center_crop_rect, required_sample_size};

const FOUR_MP: i64 = 4_000_000;

/// 采样那一半的共享夹具（编译期读入；路径相对本文件）。
const CASES: &str = include_str!("fixtures/bitmaps_sample_size_cases.json");

/// 居中裁剪那一半的共享夹具（编译期读入；路径相对本文件）。
const COVER_CASES: &str = include_str!("fixtures/bitmaps_cover_rect_cases.json");

// ------------------------------------------------------------- 采样

#[test]
fn 采样倍数与_kotlin_侧同口径() {
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

/// 夹具里的一条裁剪用例。
struct CoverCase {
    /// 与 Kotlin 侧共用的稳定 id（失败时指认是哪一条）
    id: String,
    /// `[srcW, srcH, targetW, targetH]`
    dims: [i32; 4],
    /// `[x, y, w, h]`
    expected: [i32; 4],
}

/// 把共享夹具读成结构化用例。
///
/// 为什么不用 `common::pick`：那份读取器是给「单个字符串入参/出参」的用例设计的，
/// 而裁剪矩形是四个整数入参、四个整数出参 ⇒ 形状不同，只能另写。
/// 格式不对 ⇒ 直接 panic（绝不静默跳过，那会让门禁空转）。
fn cover_cases() -> Vec<CoverCase> {
    let v = common::load(COVER_CASES);
    v["cases"]
        .as_array()
        .expect("cases 必须是数组")
        .iter()
        .map(|c| {
            let id = c["id"].as_str().expect("id 必须是字符串").to_string();
            let dims = ints(c, "args", &id);
            let expected = ints(c, "expected", &id);
            CoverCase {
                id,
                dims,
                expected,
            }
        })
        .collect()
}

/// 取一个「四个整数」的数组字段。
fn ints(c: &serde_json::Value, key: &str, id: &str) -> [i32; 4] {
    let a = c[key]
        .as_array()
        .unwrap_or_else(|| panic!("夹具用例「{id}」缺 {key} 数组"));
    assert_eq!(a.len(), 4, "夹具用例「{id}」的 {key} 必须是四个整数");
    let mut out = [0i32; 4];
    for (i, v) in a.iter().enumerate() {
        out[i] = v
            .as_i64()
            .unwrap_or_else(|| panic!("夹具用例「{id}」的 {key}[{i}] 必须是整数")) as i32;
    }
    out
}

#[test]
fn 裁剪矩形与_kotlin_侧同口径() {
    for c in cover_cases() {
        let [sw, sh, tw, th] = c.dims;
        let r = center_crop_rect(sw, sh, tw, th);
        assert_eq!(
            [r[0], r[1], r[2], r[3]],
            c.expected,
            "夹具用例「{}」",
            c.id
        );
    }
}

#[test]
fn 合法输入下矩形落在源图内且宽高比与目标一致() {
    let mut checked = 0;
    for c in cover_cases() {
        let [sw, sh, tw, th] = c.dims;
        // 退化输入走的是守卫分支（压根不裁），不满足「宽高比与目标一致」这个性质
        if sw <= 0 || sh <= 0 || tw <= 0 || th <= 0 {
            continue;
        }
        let r = center_crop_rect(sw, sh, tw, th);
        assert_consistent(r, sw, sh, tw, th);
        checked += 1;
    }
    assert!(checked > 0, "一条合法输入都没有 ⇒ 性质断言空转");
}

#[test]
fn 退化输入返回可用的矩形() {
    let mut checked = 0;
    for c in cover_cases() {
        let [sw, sh, tw, th] = c.dims;
        if sw > 0 && sh > 0 && tw > 0 && th > 0 {
            continue;
        }
        let r = center_crop_rect(sw, sh, tw, th);
        assert!(
            r[2] >= 1 && r[3] >= 1,
            "夹具用例「{}」：退化输入也要给出可用的矩形",
            c.id
        );
        checked += 1;
    }
    assert!(checked > 0, "一条退化输入都没有 ⇒ 守卫分支没人盯");
}

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
    // 中间图不得大于源图：先裁后缩的前提 —— 越界就意味着这一步偷偷放大了（会爆内存）
    assert!(
        w as i64 * h as i64 <= sw as i64 * sh as i64,
        "中间图大于源图 ({w}×{h} vs {sw}×{sh})",
    );
}
