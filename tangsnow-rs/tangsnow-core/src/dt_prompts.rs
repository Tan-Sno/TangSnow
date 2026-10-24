//! 网页表单时间提示的解析与回交格式化（2026-10-08 深度重构批 C）。
//!
//! 对齐 Kotlin：`ui/WebPrompts.kt` 的 parseTimeLoose / parseDateTimeLocal / DATE / MONTH /
//! WEEK 分支与 formatChosenTime / formatChosenDateTimeLocal / 各 format 调用。
//!
//! 移植口径（Java `DateTimeFormatter` 默认 **ResolverStyle.SMART** 的行为）。
//!
//! ⚠️ **2026-10-17 用真 JBR（JDK 25）逐条实证订正**：下面三条断言此前与实测**相反**，
//!    而错误注释比错误代码更贵 —— 它会让人照着错的口径继续写。订正记录：
//!  - 字段宽度：`HH`/`mm`/`ss`/`MM`/`dd` **必须恰好 2 位**（`yyyy` 恰好 4 位），宽度由
//!    **模式字母个数**硬定、与 `ResolverStyle` 无关。旧注释称「SMART 接受 1–2 位、`9:5` → 09:05」，
//!    实测 `LocalTime.parse("9:5", ofPattern("HH:mm"))` 抛 `DateTimeParseException: ... at index 0`；
//!    所以手写字段解析**不是为了放宽**，而是为了不落到 chrono `%H`/`%m` 的 Unicode 口径上。
//!  - 有效性：SMART 对**日**是**钳位**而非拒绝 —— `2026-02-30` → 02-28、`2026-04-31` → 04-30、
//!    闰年 `2024-02-30` → 02-29；钳位只在 `1..=31` 内发生，`2026-01-32` / `2026-01-00` 仍抛异常。
//!    月**不钳**（`2026-13-05` 抛异常）。旧注释称「SMART 拒绝 `2026-02-30`」，与实测相反。
//!  - `24:00`：SMART 当**午夜特例**收下 —— 纯时间 `24:00[:00]` → `00:00`；带日期
//!    `2026-01-05T24:00` → `2026-01-06T00:00`（**进位次日**，`2026-12-31T24:00` → 2027-01-01）。
//!    而 `24:30` / `24:00:01` / `25:00` 抛异常。旧注释称「SMART 拒绝 `24:00`」，与实测相反。
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

/// 恰好两位十进制数字（Java `HH`/`mm`/`ss`/`MM`/`dd` 的解析宽度）；位数/数字/范围不符返回 None。
///
/// 旧实现（名为 `small_num`）接受 1–2 位，理由写在模块注释里「SMART 接受 1–2 位」——**与实测相反**：
/// 宽度由模式字母个数硬定、与 `ResolverStyle` 无关，1 位输入（`9:5`）直接抛异常。
fn two_digit(s: &str, min: i32, max: i32) -> Option<i32> {
    if s.len() != 2 || !s.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    // 前导零允许（"05" = 5）；"007" 因位数不符已被上面拦掉
    let v: i32 = s.parse().ok()?;
    (min..=max).contains(&v).then_some(v)
}

/// 按 SMART 口径解析 `HH:mm(:ss)`；返回 (时, 分, 秒, 是否显式带秒)。
///
/// 返回的时字段可能是 **24**：那是 SMART 的午夜特例（`24:00[:00]`），调用方必须显式处理，
/// 不要当普通小时用 —— 见 [`parse_loose`] 的 `KIND_TIME` / `KIND_DATETIME` 两处。
fn parse_hms(t: &str) -> Option<(i32, i32, i32, bool)> {
    let parts: Vec<&str> = t.split(':').collect();
    match parts.as_slice() {
        [h, m] => {
            let h = two_digit(h, 0, 24)?;
            let m = two_digit(m, 0, 59)?;
            // `24:00` 合法、`24:30` 非法（真 JVM 实测：前者 → 00:00，后者抛异常）
            if h == 24 && m != 0 {
                return None;
            }
            Some((h, m, 0, false))
        }
        [h, m, s] => {
            let h = two_digit(h, 0, 24)?;
            let m = two_digit(m, 0, 59)?;
            let s = two_digit(s, 0, 59)?;
            if h == 24 && (m != 0 || s != 0) {
                return None; // `24:00:00` 合法、`24:00:01` 非法（实测）
            }
            Some((h, m, s, true))
        }
        _ => None,
    }
}

/// 该公历年的该月共有多少天（`m` 已确保落在 `1..=12`）。
fn days_in_month(y: i32, m: u32) -> Option<u32> {
    let (ny, nm) = if m == 12 { (y.checked_add(1)?, 1) } else { (y, m + 1) };
    Some(chrono::NaiveDate::from_ymd_opt(ny, nm, 1)?.pred_opt()?.day())
}

/// 按 Java `yyyy-MM-dd` 口径解析（`yyyy` = `YEAR_OF_ERA`）：年月日各按模式宽度取位，
/// 年**恰好 4 位数字且 ≥ 1**（真 JVM 实测 `0000-01-05` 抛「Invalid value for YearOfEra (valid values 1 - …)」，
/// 所以不能像 chrono 那样收公元 0 年）。
///
/// 日字段按 SMART **钳到当月最后一天**：`2026-02-30` → 02-28、`2026-04-31` → 04-30、
/// 闰年 `2024-02-30` → 02-29；但只在 `1..=31` 内才钳，`2026-01-32` / `2026-01-00` 仍拒收。
/// 旧实现走 `from_ymd_opt` 直接拒收 2 月 30 日 —— 与主包相反：Kotlin 侧会钳成月末并弹窗，
/// coreRust 侧却回落「今天」，同一份 `defaultValue` 两侧给出不同日期。
fn parse_ymd(s: &str) -> Option<chrono::NaiveDate> {
    let (ys, rest) = s.split_once('-')?;
    let (ms, ds) = rest.split_once('-')?;
    if ys.len() != 4 || !ys.bytes().all(|b| b.is_ascii_digit()) {
        return None; // `yyyy` 恰好 4 位数字（`026-…` / `20266-…` / `+260-…` 均抛异常）
    }
    let y: i32 = ys.parse().ok()?;
    if y < 1 {
        return None; // YEAR_OF_ERA 不含 0 年
    }
    let m = two_digit(ms, 1, 12)?;
    let d = two_digit(ds, 1, 31)? as u32;
    let last = days_in_month(y, m as u32)?;
    chrono::NaiveDate::from_ymd_opt(y, m as u32, d.min(last))
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
            // SMART 午夜特例：`24:00[:00]` → `00:00`（纯时间没有「次日」，归零即可，不进位）
            let h = if h == 24 { 0 } else { h };
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
            // 午夜特例在**带日期**时是「次日零点」：实测 2026-01-05T24:00 → 2026-01-06T00:00、
            // 2026-12-31T24:00 → 2027-01-01T00:00（跨年也要跟着进）
            let (date, h) = if h == 24 { (date.succ_opt()?, 0) } else { (date, h) };
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
            // `yyyy-MM`：年恰好 4 位、月恰好 2 位；无日字段故无钳位可做。
            // 走 Kotlin 的 `YearMonth.parse`（`YEAR` 字段）⇒ **年 0 合法**，与 `date` 用的 `yyyy` 不同。
            let (ys, ms) = t.split_once('-')?;
            if ys.len() != 4 || !ys.bytes().all(|b| b.is_ascii_digit()) {
                return None; // JVM 的 ISO 年字段：恰好 4 位数字，`+001`/`-001` 都拒（实测 THROW@1）
            }
            if ys.parse::<i32>().is_err() {
                return None;
            }
            let m = two_digit(ms, 1, 12)?;
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
        // 年份按 **4 位补零**（`{:04}`），对齐 Kotlin 侧 `yyyy` / weekBasedYear(minWidth=4)
        // 的口径（WebPrompts 的 DT_DATE_FMT / DT_MONTH_FMT / DT_DATETIME_FMT / DT_WEEK_FMT
        // 四处全部如此）：y < 1000 时裸输出会得到 `5-01-01`，页面会当成非法值丢掉
        // （2026-10-24 审查 P3，W3 接线前的预置缺陷）。
        KIND_DATE => Some(format!("{:04}-{}-{}", y, two(m), two(d))),
        KIND_MONTH => Some(format!("{:04}-{}", y, two(m))),
        KIND_DATETIME => Some(if with_seconds {
            format!("{:04}-{}-{}T{}:{}:00", y, two(m), two(d), two(hh), two(mm))
        } else {
            format!("{:04}-{}-{}T{}:{}", y, two(m), two(d), two(hh), two(mm))
        }),
        KIND_WEEK => {
            let date = chrono::NaiveDate::from_ymd_opt(y, m as u32, d as u32)?;
            let iso = date.iso_week();
            // weekBasedYear 用 ISO 周年（与 %G 一致）；chrono 的 year() 是日历年，边界周会差一年
            Some(format!(
                "{:04}-W{}",
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
        // 带秒优先（Kotlin 先试 HH:mm:ss 再试 HH:mm）；字段**恰好两位**
        assert_eq!(parse_loose("time", "10:30:00"), Some(("10:30:00".into(), true)));
        assert_eq!(parse_loose("time", "10:30"), Some(("10:30".into(), false)));
        assert_eq!(parse_loose("time", " 23:59:59 "), Some(("23:59:59".into(), true)));
        // 一位字段：真 JVM 抛 `DateTimeParseException ... at index 0`。
        // 旧实现按「SMART 接受 1–2 位」补零输出，与本文件注释里的错误断言同源，已一并订正。
        assert_eq!(parse_loose("time", "9:5"), None);
        assert_eq!(parse_loose("time", "9:05"), None);
        assert_eq!(parse_loose("time", "09:5"), None);
        assert_eq!(parse_loose("time", "9:5:3"), None);
        // SMART 午夜特例：`24:00[:00]` 收（→ 00:00），`24:30` / `24:00:01` / `25:00` 拒
        assert_eq!(parse_loose("time", "24:00"), Some(("00:00".into(), false)));
        assert_eq!(parse_loose("time", "24:00:00"), Some(("00:00:00".into(), true)));
        assert_eq!(parse_loose("time", "24:30"), None);
        assert_eq!(parse_loose("time", "24:00:01"), None);
        assert_eq!(parse_loose("time", "25:00"), None);
        // 越界/缺字段/垃圾输入
        assert_eq!(parse_loose("time", "10:60"), None);
        assert_eq!(parse_loose("time", "10:30:61"), None);
        assert_eq!(parse_loose("time", "10"), None);
        assert_eq!(parse_loose("time", "007:05"), None); // 三位字段（实测 at index 2）
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
        // 超界日**钳到月末**（真 JVM 实测 2026-02-30 → 02-28）；旧实现走 from_ymd_opt 直接拒收
        assert_eq!(
            parse_loose("datetime", "2026-02-30T10:30"),
            Some(("2026-02-28T10:30".into(), false))
        );
        assert_eq!(
            parse_loose("datetime", "2026-02-29T10:30"),
            Some(("2026-02-28T10:30".into(), false))
        );
        assert_eq!(
            parse_loose("datetime", "2024-02-30T10:30"),
            Some(("2024-02-29T10:30".into(), false))
        );
        // 闰日合法年：本就是合法日期，不涉及钳位
        assert_eq!(
            parse_loose("datetime", "2024-02-29T10:30"),
            Some(("2024-02-29T10:30".into(), false))
        );
        // 午夜特例带日期 ⇒ 进位次日（含跨年）
        assert_eq!(
            parse_loose("datetime", "2026-01-05T24:00"),
            Some(("2026-01-06T00:00".into(), false))
        );
        assert_eq!(
            parse_loose("datetime", "2026-12-31T24:00:00"),
            Some(("2027-01-01T00:00:00".into(), true))
        );
        // 钳位只在 1..=31 内发生；0 与 ≥32 都拒
        assert_eq!(parse_loose("datetime", "2026-01-32T10:30"), None);
        assert_eq!(parse_loose("datetime", "2026-01-00T10:30"), None);
        // 年必须 4 位数字且 ≥ 1（`yyyy` = YEAR_OF_ERA）
        assert_eq!(parse_loose("datetime", "0000-01-05T10:30"), None);
    }

    #[test]
    fn parse_date_month_week_edges() {
        assert_eq!(parse_loose("date", "2026-09-05"), Some(("2026-09-05".into(), false)));
        // 一位月/日：真 JVM 抛异常（at index 5 / 8）—— 旧实现当合法并补零，已订正
        assert_eq!(parse_loose("date", "2026-9-5"), None);
        assert_eq!(parse_loose("date", "2026-1-05"), None);
        assert_eq!(parse_loose("date", "2026-01-5"), None);
        // 日钳到月末；月在 1..=12 外直接拒（**不钳**）
        assert_eq!(parse_loose("date", "2026-02-30"), Some(("2026-02-28".into(), false)));
        assert_eq!(parse_loose("date", "2026-04-31"), Some(("2026-04-30".into(), false)));
        assert_eq!(parse_loose("date", "2026-01-31"), Some(("2026-01-31".into(), false)));
        assert_eq!(parse_loose("date", "2026-01-32"), None);
        assert_eq!(parse_loose("date", "2026-01-00"), None);
        assert_eq!(parse_loose("date", "2026-13-05"), None);
        assert_eq!(parse_loose("date", "2026-00-05"), None);
        // 年：恰好 4 位数字、≥ 1
        assert_eq!(parse_loose("date", "0000-01-05"), None);
        assert_eq!(parse_loose("date", "0001-01-01"), Some(("0001-01-01".into(), false)));
        assert_eq!(parse_loose("date", "026-01-05"), None);
        // month：月恰好两位（`2026-9` 实测 at index 5）
        assert_eq!(parse_loose("month", "2026-09"), Some(("2026-09".into(), false)));
        assert_eq!(parse_loose("month", "2026-9"), None);
        assert_eq!(parse_loose("month", "2026-13"), None);
        // ISO 周：归一为该周周一；53 周年判定交给 chrono
        let w1 = parse_loose("week", "2026-W01").unwrap().0;
        assert_eq!(w1, "2025-12-29"); // 2026-W01 的周一是 2025-12-29（ISO 周年跨年）
        assert_eq!(
            parse_loose("week", "2026-W53").map(|(v, _)| v),
            Some("2026-12-28".into()) // 2026 有 53 个 ISO 周（1/1 是周四）；真 JVM 实测同值
        );
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
            parse_loose("month", "2026-05").map(|(v, _)| v),
            Some("2026-05".into())
        );
        assert_eq!(parse_loose("month", "2026-5"), None); // 月必须两位（实测 at index 5）
        assert_eq!(parse_loose("month", "26-05"), None);
        assert_eq!(parse_loose("month", "abcd-05"), None);
        assert_eq!(parse_loose("month", "2026-13"), None);
        assert_eq!(parse_loose("month", "2026-00"), None);
        assert_eq!(parse_loose("month", "+001-12"), None); // 前导 `+`：JVM 拒收（2026-10-17 复核）
        assert_eq!(parse_loose("month", "-001-12"), None); // 负号：JVM 也拒（这里靠 split_once 切空）
        // 与 date/datetime 的 `yyyy` 有意不同：month 走 Kotlin 的 `YearMonth.parse`（`YEAR` 字段），
        // 年 0 合法（真 JVM 实测 `YearMonth.parse("0000-01")` → 0000-01）。
        assert_eq!(
            parse_loose("month", "0000-01").map(|(v, _)| v),
            Some("0000-01".into())
        );
    }

    /// 2026-10-17 用真 JBR（JDK 25）跑出的矩阵，逐条钉在这里。
    ///
    /// 这不是产品路径的覆盖测试，而是**防口径凭印象漂移的锚**：本文件此前三处断言
    /// （宽度 1–2 位、拒 `24:00`、拒 `2026-02-30`）全部与实测相反，而它们各自都被
    /// 旧测试"证明"过一遍。期望值一律取自 `DtOracle*.java` 的真实输出，不是推导。
    #[test]
    fn smart_matrix_matches_real_jvm_2026_10_17() {
        let cases: &[(&str, &str, Option<&str>)] = &[
            // 宽度：一律恰好两位（`yyyy` 四位）
            ("time", "09:05", Some("09:05")),
            ("time", "9:5", None),
            ("time", "007:05", None),
            ("date", "2026-09-05", Some("2026-09-05")),
            ("date", "2026-9-05", None),
            ("date", "2026-01-5", None),
            ("month", "2026-09", Some("2026-09")),
            ("month", "2026-9", None),
            // 日钳位（只在 1..=31 内发生）
            ("date", "2026-02-30", Some("2026-02-28")),
            ("date", "2026-02-29", Some("2026-02-28")),
            ("date", "2024-02-30", Some("2024-02-29")),
            ("date", "2026-04-31", Some("2026-04-30")),
            ("date", "2026-06-31", Some("2026-06-30")),
            ("date", "2026-11-31", Some("2026-11-30")),
            ("date", "2026-12-31", Some("2026-12-31")),
            ("date", "2026-01-32", None),
            ("date", "2026-01-00", None),
            // 月不钳
            ("date", "2026-13-05", None),
            ("date", "2026-00-05", None),
            // 午夜特例
            ("time", "24:00", Some("00:00")),
            ("time", "24:00:00", Some("00:00:00")),
            ("time", "24:30", None),
            ("time", "24:00:01", None),
            ("time", "25:00", None),
            ("datetime", "2026-01-05T24:00", Some("2026-01-06T00:00")),
            ("datetime", "2026-12-31T24:00:00", Some("2027-01-01T00:00:00")),
            // 年
            ("date", "0000-01-05", None),
            ("date", "0001-01-01", Some("0001-01-01")),
            ("date", "9999-12-31", Some("9999-12-31")),
            ("date", "026-01-05", None),
            ("date", "20266-01-05", None),
        ];
        for (kind, input, want) in cases {
            let got = parse_loose(kind, input).map(|(v, _)| v);
            assert_eq!(
                got.as_deref(),
                *want,
                "kind={} input={:?} 与真 JVM 实测不一致（实测期望 {:?}）",
                kind,
                input,
                want
            );
        }
    }
}
