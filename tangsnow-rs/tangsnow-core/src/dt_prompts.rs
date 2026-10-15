//! 网页表单时间提示的解析与回交格式化（2026-10-08 深度重构批 C）。
//!
//! 对齐 Kotlin：`ui/WebPrompts.kt` 的 parseTimeLoose / parseDateTimeLocal / DATE / MONTH /
//! WEEK 分支与 formatChosenTime / formatChosenDateTimeLocal / 各 format 调用。
//!
//! 移植口径（Java `DateTimeFormatter` 默认 **ResolverStyle.SMART** 的行为）：
//!  - 字段宽度：`HH`/`mm`/`dd` 等 SMART 模式接受 **1–2 位**数字（`9:5` → 09:05）；
//!    因此这里手写字段解析而不是 chrono 的 `%H`（严格两位），避免把 Java 能收的输入拒掉。
//!  - 有效性：SMART 拒绝 `2026-02-30`（日超月长）与 `24:00`（时超界）—— chrono 的
//!    `from_ymd_opt` / `from_hms_opt` 同拒，逐条对齐。
//!  - 解析顺序：先试**带秒**变体、再试不带（与 Kotlin 的 runCatching 顺序一致）——
//!    `10:30:00` 用 `HH:mm` 会因未消费文本失败，故顺序不可反。
//!  - WEEK：ISO 周日期 `yyyy-Www(-d)`，回交/默认值统一归一为**该周周一** `yyyy-MM-dd`
//!    （`LocalDate.parse(raw+"-1", ISO_WEEK_DATE)` 的语义），选择器选定后回交
//!    `weekBasedYear-Www`。ISO 周含「53 周年」判定，手写易错，走 chrono。
//!  - 返回值协议：`parse_loose` 返回 `(规范化值, withSeconds)`；格式化失败返回 None。
//!    `raw.trim()` 与空白判定走 [`java_trim`]/[`java_is_whitespace`]（JVM 口径）。

use chrono::Datelike;
use crate::java_trim;

/// 网页弹窗 datetime 的子类型（对齐 GeckoSession.DateTimePrompt.Type）。
pub const KIND_TIME: &str = "time";
pub const KIND_DATE: &str = "date";
pub const KIND_DATETIME: &str = "datetime";
pub const KIND_MONTH: &str = "month";
pub const KIND_WEEK: &str = "week";

/// 1–2 位十进制数字解析（Java SMART 字段口径）；无效返回 None。
fn small_num(s: &str, min: i32, max: i32) -> Option<i32> {
    if s.is_empty() || s.len() > 2 || !s.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    // "007" 长度 3 已被上面拦掉；前导零允许（"05" = 5）
    let v: i32 = s.parse().ok()?;
    (min..=max).contains(&v).then_some(v)
}

/// 按 SMART 口径解析 `HH:mm(:ss)`；返回 (时, 分, 秒, 是否显式带秒)。
fn parse_hms(t: &str) -> Option<(i32, i32, i32, bool)> {
    let parts: Vec<&str> = t.split(':').collect();
    match parts.as_slice() {
        [h, m] => Some((small_num(h, 0, 23)?, small_num(m, 0, 59)?, 0, false)),
        [h, m, s] => Some((
            small_num(h, 0, 23)?,
            small_num(m, 0, 59)?,
            small_num(s, 0, 59)?,
            true,
        )),
        _ => None,
    }
}

/// 按 SMART 口径解析 `yyyy-MM-dd`（chrono 校验月长/闰年）；返回 chrono 日期序号天数。
fn parse_ymd(s: &str) -> Option<chrono::NaiveDate> {
    let (ys, rest) = s.split_once('-')?;
    let (ms, ds) = rest.split_once('-')?;
    if ys.len() != 4 {
        return None; // Java `uuuu` SMART 对 4 位年约束：ISO_WEEK_DATE/公式都是 4 位
    }
    let y: i32 = ys.parse().ok()?;
    let m = small_num(ms, 1, 12)?;
    let d = small_num(ds, 1, 31)?;
    chrono::NaiveDate::from_ymd_opt(y, m as u32, d as u32)
}

/// 两位补零输出（月/日/时/分/秒）。
fn two(v: i32) -> String {
    if v < 10 {
        format!("0{}", v)
    } else {
        v.to_string()
    }
}

/// 宽松解析。返回 `(规范化值, withSeconds)`：
///  - time：`HH:mm[:ss]`（SMART 补零，与回交格式同形）
///  - datetime：`yyyy-MM-ddTHH:mm[:ss]`
///  - date：`yyyy-MM-dd`；month：`yyyy-MM`
///  - week：`yyyy-MM-dd`（该周**周一**，供日期选择器定初值；原始周串由调用方持有）
pub fn parse_loose(kind: &str, raw: &str) -> Option<(String, bool)> {
    let t = java_trim(raw);
    if t.is_empty() {
        return None;
    }
    match kind {
        KIND_TIME => {
            let (h, m, s, with_sec) = parse_hms(t)?;
            Some((
                if with_sec {
                    format!("{}:{}:{}", two(h), two(m), two(s))
                } else {
                    format!("{}:{}", two(h), two(m))
                },
                with_sec,
            ))
        }
        KIND_DATETIME => {
            let (ds, ts) = t.split_once('T')?;
            let date = parse_ymd(ds)?;
            let (h, m, s, with_sec) = parse_hms(ts)?;
            Some((
                if with_sec {
                    format!("{}T{}:{}:{}", date.format("%Y-%m-%d"), two(h), two(m), two(s))
                } else {
                    format!("{}T{}:{}", date.format("%Y-%m-%d"), two(h), two(m))
                },
                with_sec,
            ))
        }
        KIND_DATE => {
            let date = parse_ymd(t)?;
            Some((date.format("%Y-%m-%d").to_string(), false))
        }
        KIND_MONTH => {
            // `yyyy-MM`：SMART 下 MM 同样 1–2 位；无日字段（无有效性可校验）
            let (ys, ms) = t.split_once('-')?;
            if ys.len() != 4 {
                return None;
            }
            if ys.parse::<i32>().is_err() {
                return None;
            }
            let m = small_num(ms, 1, 12)?;
            Some((format!("{}-{}", ys, two(m)), false))
        }
        KIND_WEEK => {
            // `yyyy-Www`（ISO_WEEK_DATE 的 `-d` 变体由调用点补 "-1" 取周一）：归一为周一 yyyy-MM-dd。
            // 周号 0 非法；「53 周年」判定交给 chrono（from_isoywd_opt 对无 53 周的年返回 None）。
            let base: Vec<&str> = t.split('-').collect();
            // 年份必须**恰好 4 位数字**：缺这道闸时 `26-W01` 会被 chrono 接受、选择器跳到公元 26 年
            // （主包用 ISO_WEEK_DATE 解析，那里天然拒收）。
            // 另一处易被误读成越界：`base[1][1..]` 的起点紧跟 1 字节的 `W`，恒在码点边界上；
            // 尾部若是非 ASCII（如 `2026-Wé`）只会让 parse::<u32> 失败返回 None，不会 panic。
            if base.len() != 2
                || base[0].len() != 4
                || !base[0].bytes().all(|b| b.is_ascii_digit())
                || base[1].len() != 3
                || !base[1].starts_with('W')
            {
                return None;
            }
            let y: i32 = base[0].parse().ok()?;
            let w: u32 = base[1][1..].parse().ok()?;
            if w == 0 {
                return None;
            }
            let monday = chrono::NaiveDate::from_isoywd_opt(y, w, chrono::Weekday::Mon)?;
            Some((monday.format("%Y-%m-%d").to_string(), false))
        }
        _ => None,
    }
}

/// 把用户选定值按**解析时记录的形态**格式化回交（与页面 `step` 约定一致）。
/// 参数即选择器的产出：日期（date/month/week/datetime 用；time 忽略）+ 时:分。
/// week：`y/m/d` 给**该周周一**所在日期，回交其 weekBasedYear-Www。
pub fn format_chosen(
    kind: &str,
    with_seconds: bool,
    y: i32,
    m: i32,
    d: i32,
    hh: i32,
    mm: i32,
) -> Option<String> {
    // time 类没有日期字段，跳过日期有效性校验
    if kind != KIND_TIME {
        chrono::NaiveDate::from_ymd_opt(y, m as u32, d as u32)?; // 有效性
    }
    if !(0..=23).contains(&hh) || !(0..=59).contains(&mm) {
        return None;
    }
    match kind {
        KIND_TIME => Some(if with_seconds {
            format!("{}:{}:00", two(hh), two(mm))
        } else {
            format!("{}:{}", two(hh), two(mm))
        }),
        KIND_DATE => Some(format!("{}-{}-{}", y, two(m), two(d))),
        KIND_MONTH => Some(format!("{}-{}", y, two(m))),
        KIND_DATETIME => Some(if with_seconds {
            format!("{}-{}-{}T{}:{}:00", y, two(m), two(d), two(hh), two(mm))
        } else {
            format!("{}-{}-{}T{}:{}", y, two(m), two(d), two(hh), two(mm))
        }),
        KIND_WEEK => {
            let date = chrono::NaiveDate::from_ymd_opt(y, m as u32, d as u32)?;
            let iso = date.iso_week();
            // weekBasedYear 用 ISO 周年（与 %G 一致）；chrono 的 year() 是日历年，边界周会差一年
            Some(format!(
                "{}-W{}",
                iso.year(),
                two(iso.week() as i32)
            ))
        }
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_time_edges() {
        // 带秒优先；SMART 接受 1–2 位字段
        assert_eq!(parse_loose("time", "10:30:00"), Some(("10:30:00".into(), true)));
        assert_eq!(parse_loose("time", "10:30"), Some(("10:30".into(), false)));
        assert_eq!(parse_loose("time", "9:5"), Some(("09:05".into(), false)));
        assert_eq!(parse_loose("time", " 23:59:59 "), Some(("23:59:59".into(), true)));
        // 越界/缺字段/垃圾输入
        assert_eq!(parse_loose("time", "24:00"), None);
        assert_eq!(parse_loose("time", "10:60"), None);
        assert_eq!(parse_loose("time", "10:30:61"), None);
        assert_eq!(parse_loose("time", "10"), None);
        assert_eq!(parse_loose("time", ""), None);
    }

    #[test]
    fn parse_datetime_edges() {
        assert_eq!(
            parse_loose("datetime", "2026-09-30T10:30:00"),
            Some(("2026-09-30T10:30:00".into(), true))
        );
        assert_eq!(
            parse_loose("datetime", "2026-09-30T10:30"),
            Some(("2026-09-30T10:30".into(), false))
        );
        // SMART 拒 2 月 30 日（chrono 同口径）
        assert_eq!(parse_loose("datetime", "2026-02-30T10:30"), None);
        // 闰日合法年
        assert!(parse_loose("datetime", "2024-02-29T10:30").is_some());
        assert_eq!(parse_loose("datetime", "2026-02-29T10:30"), None);
    }

    #[test]
    fn parse_date_month_week_edges() {
        assert_eq!(parse_loose("date", "2026-9-5"), Some(("2026-09-05".into(), false)));
        assert_eq!(parse_loose("date", "2026-02-30"), None);
        assert_eq!(parse_loose("month", "2026-9"), Some(("2026-09".into(), false)));
        assert_eq!(parse_loose("month", "2026-13"), None);
        // ISO 周：归一为该周周一；53 周年判定交给 chrono
        let w1 = parse_loose("week", "2026-W01").unwrap().0;
        assert_eq!(w1, "2025-12-29"); // 2026-W01 的周一是 2025-12-29（ISO 周年跨年）
        assert!(parse_loose("week", "2025-W53").is_none()); // 2025 非 53 周年（1/1 是周三、非闰），chrono 判
        assert_eq!(parse_loose("week", "2026-W0"), None);
        assert_eq!(parse_loose("week", "2026-W1"), None); // 缺 W+两位
    }

    #[test]
    fn format_chosen_edges() {
        assert_eq!(
            format_chosen("time", true, 0, 0, 0, 10, 30),
            Some("10:30:00".into())
        );
        assert_eq!(
            format_chosen("time", false, 0, 0, 0, 9, 5),
            Some("09:05".into())
        );
        assert_eq!(
            format_chosen("datetime", false, 2026, 9, 30, 10, 30),
            Some("2026-09-30T10:30".into())
        );
        // week：给该周周一，回交 ISO 周串
        assert_eq!(
            format_chosen("week", false, 2025, 12, 29, 0, 0),
            Some("2026-W01".into())
        );
        // 越界时:分
        assert_eq!(format_chosen("time", false, 0, 0, 0, 24, 0), None);
    }

    #[test]
    fn week_年份必须四位否则拒收() {
        // 缺这道闸时 `26-W01` 会被 chrono 接受、选择器跳到公元 26 年（主包走 ISO_WEEK_DATE 天然拒收）
        assert_eq!(parse_loose("week", "26-W01"), None);
        assert_eq!(parse_loose("week", "+26-W01"), None);
        assert_eq!(
            parse_loose("week", "2026-W01").map(|(v, _)| v),
            Some("2025-12-29".into())
        );
        // `W` 只占 1 字节 ⇒ base[1][1..] 的起点恒在码点边界；非 ASCII 尾巴只是解析失败，不是 panic
        assert_eq!(parse_loose("week", "2026-W\u{00e9}"), None);
        assert_eq!(parse_loose("week", "2026-W00"), None);
        assert_eq!(parse_loose("week", "2025-W53"), None); // 2025 年没有第 53 周
    }

    #[test]
    fn month_产物只到月不带日() {
        // 这条记录的是**契约**：month 的产物没有日字段，消费方必须补日或用 YearMonth。
        // Kotlin 侧 WebPrompts.parseDateByType 曾直接 LocalDate.parse ⇒ DateTimeParseException
        // 主线程闪退（2026-10-15 已修：month 补 -01 并恢复 runCatching）。
        assert_eq!(
            parse_loose("month", "2026-5").map(|(v, _)| v),
            Some("2026-05".into())
        );
        assert_eq!(parse_loose("month", "26-05"), None);
        assert_eq!(parse_loose("month", "abcd-05"), None);
        assert_eq!(parse_loose("month", "2026-13"), None);
    }
}
