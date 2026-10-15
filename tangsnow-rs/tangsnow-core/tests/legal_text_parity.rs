//! `LegalText`（法律文本加粗标记）差分测试：用例与 JVM 侧 `LegalTextTest.kt`
//! 同源（同输入 ⇒ 同输出）。这段解析直接决定隐私政策三个渲染点的视觉呈现；
//! 落单标记/空标记的边界一旦漂移，正文里的真实星号会静默消失。

use tangsnow_core::legal_text::parse;

#[test]
fn 成对标记被去掉且区间正确() {
    let m = parse("a**bc**d");
    assert_eq!(m.plain, "abcd");
    assert_eq!(m.bold_ranges, vec![(1, 2)]);
}

#[test]
fn 多处标记各自成段() {
    let m = parse("**甲**在中间**乙**尾");
    assert_eq!(m.plain, "甲在中间乙尾");
    assert_eq!(m.bold_ranges, vec![(0, 0), (4, 4)]);
}

#[test]
fn 无标记时原样返回且不加粗() {
    let m = parse("没有任何标记");
    assert_eq!(m.plain, "没有任何标记");
    assert!(m.bold_ranges.is_empty());
}

#[test]
fn 落单的标记原样保留() {
    let m = parse("前面**后面没有配对");
    assert_eq!(m.plain, "前面**后面没有配对");
    assert!(m.bold_ranges.is_empty());
}

#[test]
fn 空标记被丢弃且不产生加粗区间() {
    let m = parse("a****b");
    assert_eq!(m.plain, "ab");
    assert!(m.bold_ranges.is_empty());
}

#[test]
fn 标记在结尾时同样成对处理() {
    let m = parse("结尾**加粗**");
    assert_eq!(m.plain, "结尾加粗");
    assert_eq!(m.bold_ranges, vec![(2, 3)]);
}
