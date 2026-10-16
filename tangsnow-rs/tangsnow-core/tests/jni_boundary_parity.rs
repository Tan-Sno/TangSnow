//! `src/jni.rs` 的**源码级**边界不变量。
//!
//! 为什么用「读自己的源码再断言」这种写法：`lib.rs` 把 `pub mod jni` 关在
//! `#[cfg(feature = "jni")]` 后面，而 JNI 调用得有真的 JVM 才跑得到 —— host 测试一行也
//! 覆盖不到这一层（STATUS 里明记着这条限制，`cargo test --features jni --no-run` 只保证
//! 能编能链）。于是把「边界必须走 CESU-8 解码」这类**手抄口径**钉成红灯，与本仓
//! `TestCountCommentTest` / `ProguardLogRuleConsistencyTest` 同一套纪律：
//! 靠人记着的同步就是没同步。
//!
//! 措辞变了就同步改正则 —— 它们查不到语义，只保证「这一行还在、还在用这个函数」。

use std::path::Path;

fn jni_src() -> String {
    let p = Path::new(env!("CARGO_MANIFEST_DIR")).join("src/jni.rs");
    std::fs::read_to_string(&p).unwrap_or_else(|e| panic!("读不到 {}：{e}", p.display()))
}

/// 取某个导出函数的源码片段（从函数名到下一个 `#[no_mangle]`）。
fn fn_src(src: &str, name: &str) -> String {
    let start = src
        .find(format!("CoreNative_{name}(").as_str())
        .unwrap_or_else(|| panic!("jni.rs 里找不到 CoreNative_{name}（改名/删符号请同步本测试）"));
    let rest = &src[start..];
    match rest.find("#[no_mangle]") {
        Some(end) => rest[..end].to_string(),
        None => rest.to_string(),
    }
}

#[test]
fn 字符串入参一律走_cesu8_解码而不是按_utf8_硬解() {
    let src = jni_src();
    // 唯一的取值入口是 opt_str；它一旦改回硬解，emoji 就静默变两个 U+FFFD（H3）。
    assert!(
        !src.contains(".to_string_lossy()"),
        "jni.rs 里又出现了 `.to_string_lossy()`：JNI 给的是 modified UTF-8（CESU-8），\
         按 UTF-8 硬解会把星平面字符换成 U+FFFD，且出向编码是对的 ⇒ 导出→导入不可逆。\
         改用 crate::decode_java_modified_utf8（纯函数，tests/modified_utf8.rs 钉着）。"
    );
    assert!(
        src.contains("crate::decode_java_modified_utf8("),
        "opt_str 不再调用 decode_java_modified_utf8 —— 边界解码口径飘了"
    );
    // 入向只有 opt_str 这一个口子：出现第二处 get_string 就等于绕过了那道闸。
    assert_eq!(
        src.matches("env.get_string(").count(),
        1,
        "jni.rs 里出现了第二个 `env.get_string(` 调用点：它没走 opt_str 的 CESU-8 解码"
    );
}

#[test]
fn 引擎_id_由_kotlin_传进来而不是桥层自造() {
    let src = jni_src();
    let f = fn_src(&src, "displayForUrlImpl");
    assert!(
        !src.contains("SearchEngine::new(\"jni\""),
        "桥层又写死了占位 id：`HOST_ALIASES` 以真 id 为键，写死就等于别名表永不命中（M5）"
    );
    assert!(
        f.contains("engine_id: JString"),
        "displayForUrlImpl 的参数里没有 engine_id —— Kotlin 侧 `CoreNative.displayForUrlImpl` \
         已带这个形参，两边少一个就是 UnsatisfiedLinkError（由 guard 兜成降级，但口径就错了）"
    );
    // 缺失/空 id 走 custom_ 前缀 = 不配别名，与 Kotlin 对自定义引擎的口径一致
    assert!(
        f.contains("CUSTOM_ID_PREFIX"),
        "displayForUrlImpl 里没有空 id 的回落口径（应为 custom_ 前缀，不配别名）"
    );
}

#[test]
fn 数组长度按_jsize_收窄超长时按加载失败回落() {
    let src = jni_src();
    let f = fn_src(&src, "legalParseBoldRangesImpl");
    assert!(
        !f.contains("flat.len() as i32"),
        "legalParseBoldRangesImpl 又用 `as i32` 转数组长度：超界是**静默**给出负数或错值，\
         Kotlin 侧拿到的是长度不对的数组。按不可达处理不等于可以静默 —— 用 try_from 并回落 null。"
    );
    assert!(
        f.contains("jsize::try_from(flat.len())") && f.contains("env.new_int_array(len)"),
        "长度必须经 jsize::try_from 收窄、超界返回 null（本测试钉这个形状，L8）"
    );
}
