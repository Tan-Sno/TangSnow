//! 位图采样与 center-crop 的纯整数部分。
//!
//! Kotlin 源：`util/Bitmaps.kt` 的 `requiredSampleSize` / `centerCropRect`
//! （`decodeSampled` / `cover` 依赖 Bitmap/ContentResolver，不移植）。
//!
//! 口径要点：Kotlin `Int` 乘法是 32 位环绕——`sample * 2` 在守卫不可达的极端
//! 输入下溢出，行为依赖环绕后变 0/负数使循环终止。Rust 侧用 `wrapping_mul`
//! 保持同一环绕语义（i32 除法同为向零截断）；中间乘积按 Kotlin `toLong()`
//! 升位为 i64。

/// `requiredSampleSize`：既满足「不小于目标尺寸」，又满足总像素 ≤ max_pixels；
/// 冲突时优先保内存。非法输入一律返回 1（对齐 Kotlin 三道守卫）。
pub fn required_sample_size(
    out_width: i32,
    out_height: i32,
    req_w: i32,
    req_h: i32,
    max_pixels: i64,
) -> i32 {
    if out_width <= 0 || out_height <= 0 {
        return 1;
    }
    // CR-014：非正预算守卫（Kotlin 侧注释：纯函数可直接被测试调用，守卫成本为零）
    if max_pixels <= 0 {
        return 1;
    }
    // 下界守卫：req 非正时「不小于目标」恒真，采样会翻倍到溢出
    if req_w <= 0 || req_h <= 0 {
        return 1;
    }
    let mut sample: i32 = 1;
    while out_width / (sample.wrapping_mul(2)) >= req_w
        && out_height / (sample.wrapping_mul(2)) >= req_h
    {
        sample = sample.wrapping_mul(2);
    }
    // 内存闸门：整数除法下 sample 超过边长时结果为 0，循环必然收敛
    while (out_width / sample) as i64 * (out_height / sample) as i64 > max_pixels {
        sample = sample.wrapping_mul(2);
    }
    sample
}

/// `centerCropRect`：目标宽高比下、源图里居中的最大内接矩形 `[x, y, w, h]`。
/// 比较一律整数乘法（i64 中间量），避免浮点误差把边界判反。
pub fn center_crop_rect(src_w: i32, src_h: i32, target_w: i32, target_h: i32) -> [i32; 4] {
    if src_w <= 0 || src_h <= 0 || target_w <= 0 || target_h <= 0 {
        return [0, 0, src_w.max(1), src_h.max(1)];
    }
    // 目标更「宽」（含相等）⇒ 源相对更高 ⇒ 裁掉上下；否则裁掉左右
    if (target_w as i64) * (src_h as i64) >= (target_h as i64) * (src_w as i64) {
        let h = (((src_w as i64) * (target_h as i64) / target_w as i64) as i32).clamp(1, src_h);
        [0, (src_h - h) / 2, src_w, h]
    } else {
        let w = (((src_h as i64) * (target_w as i64) / target_h as i64) as i32).clamp(1, src_w);
        [(src_w - w) / 2, 0, w, src_h]
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const FOUR_MP: i64 = 4_000_000;

    // ---- required_sample_size：与 JVM 单测 BitmapsSampleSizeTest 逐条对齐 ----

    #[test]
    fn downsample_by_powers_of_two() {
        assert_eq!(2, required_sample_size(4000, 3000, 1000, 1500, FOUR_MP));
    }

    #[test]
    fn small_image_not_sampled() {
        assert_eq!(1, required_sample_size(800, 600, 1080, 1920, FOUR_MP));
    }

    #[test]
    fn memory_gate_wins_over_clarity() {
        // 12000×8000 = 96MP。按尺寸只需 sample=2（高度 4000 ≥ 2400），
        // 但 6000×4000 = 24MP 超上限，必须提到 8（1500×1000 = 1.5MP）
        let sample = required_sample_size(12000, 8000, 1080, 2400, FOUR_MP);
        assert_eq!(8, sample);
        assert!((12000 / sample) as i64 * (8000 / sample) as i64 <= FOUR_MP);
    }

    #[test]
    fn always_satisfies_pixel_cap_and_converges() {
        for (w, h, (rw, rh)) in [
            (12000, 8000, (1080, 2400)),
            (30000, 20000, (4000, 4000)),
            (1, 1, (10, 10)),
            (100000, 100000, (1, 1)),
        ] {
            let sample = required_sample_size(w, h, rw, rh, FOUR_MP);
            assert!(sample >= 1, "w={w} h={h} sample={sample} 非正");
            assert!(
                (w / sample) as i64 * (h / sample) as i64 <= FOUR_MP,
                "w={w} h={h} sample={sample} 超出像素上限"
            );
        }
    }

    #[test]
    fn invalid_dimensions_return_1() {
        assert_eq!(1, required_sample_size(0, 0, 100, 100, FOUR_MP));
        assert_eq!(1, required_sample_size(-5, 100, 100, 100, FOUR_MP));
    }

    #[test]
    fn non_positive_target_returns_1() {
        assert_eq!(1, required_sample_size(4000, 3000, 0, 0, FOUR_MP));
        assert_eq!(1, required_sample_size(4000, 3000, -1, 100, FOUR_MP));
        assert_eq!(1, required_sample_size(4000, 3000, 100, -1, FOUR_MP));
    }

    #[test]
    fn huge_budget_degrades_to_size_only() {
        assert_eq!(
            4,
            required_sample_size(4096, 4096, 1024, 1024, i64::MAX)
        );
    }

    // ---- center_crop_rect：与 JVM 单测 BitmapsCoverRectTest 逐条对齐 ----

    #[test]
    fn wide_source_vertical_target_crops_sides() {
        // 旧实现会把 8000×500 放大到 9600×2400（≈23MP / 92MB）—— 这是本批要消灭的形态
        let r = center_crop_rect(8000, 500, 1080, 2400);
        assert_eq!([3887, 0, 225, 500], r);
        assert!((r[2] as i64) * (r[3] as i64) <= 8000i64 * 500);
    }

    #[test]
    fn tall_source_horizontal_target_crops_top_bottom() {
        let r = center_crop_rect(500, 8000, 2400, 1080);
        assert_eq!([0, 3887, 500, 225], r);
    }

    #[test]
    fn square_source_vertical_target_crops_sides_half() {
        let r = center_crop_rect(1000, 1000, 1080, 2400);
        assert_eq!([275, 0, 450, 1000], r);
    }

    #[test]
    fn equal_aspect_returns_full() {
        let r = center_crop_rect(1080, 2400, 1080, 2400);
        assert_eq!([0, 0, 1080, 2400], r);
    }

    #[test]
    fn one_px_source_and_extreme_ratio_stay_in_bounds() {
        for r in [
            center_crop_rect(1, 1, 1080, 2400),
            center_crop_rect(1, 4000, 1080, 2400),
            center_crop_rect(4000, 1, 1080, 2400),
            center_crop_rect(3, 2, 4000, 4000),
        ] {
            assert!(r[2] > 0 && r[3] > 0);
        }
    }

    #[test]
    fn invalid_dimensions_return_usable_rect() {
        for r in [
            center_crop_rect(0, 100, 1080, 2400),
            center_crop_rect(100, 0, 1080, 2400),
            center_crop_rect(100, 100, 0, 2400),
            center_crop_rect(100, 100, 1080, 0),
        ] {
            assert!(r[2] >= 1 && r[3] >= 1);
        }
    }
}
