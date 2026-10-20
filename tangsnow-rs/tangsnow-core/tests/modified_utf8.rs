//! JNI 字符串边界的编码不变量（2026-10-15 外部审查 H3）。
//!
//! 这些用例全是**纯函数**级的，不需要 JVM —— 把解码从 `JNIEnv` 上摘下来正是为了这个。
//! 「`src/jni.rs` 里是否真的走了这个函数」由 `tests/jni_boundary_parity.rs` 钉。

use cesu8::to_java_cesu8;
use tangsnow_core::decode_java_modified_utf8;

/// 把字符串按 Java 的 modified UTF-8（CESU-8）编出来，模拟 JNI 给到的字节。
fn java_bytes(s: &str) -> Vec<u8> {
    to_java_cesu8(s).into_owned()
}

#[test]
fn 星平面字符往返不被替换字符污染() {
    // emoji 与三路生僻字都在星平面：JNI 给的是「两个 3 字节代理项」共 6 字节。
    for s in ["棠雪🍁", "🍁", "a🎉b𐍈c", "𝄞 高澄清键"] {
        assert_eq!(decode_java_modified_utf8(&java_bytes(s)), s, "输入：{s:?}");
    }
}

#[test]
fn 同一字符的_cesu8_与_utf8_字节不同因此不能硬解() {
    // 这条把「为什么必须解 CESU-8」本身钉住：🍁(U+1F341) 的 UTF-8 是 4 字节，
    // modified UTF-8 是 6 字节（代理项各 3 字节）。按 UTF-8 硬解那 6 字节 ⇒ 两个 U+FFFD，
    // 这就是 H3 在真机上的症状（搜索词与书签标题被静默改写，且导出再导入不可逆）。
    let emoji = "🍁";
    let cesu = java_bytes(emoji);
    assert_eq!(cesu.len(), 6, "代理项对：每个代理项 3 字节");
    assert_eq!(emoji.as_bytes().len(), 4, "同一字符的标准 UTF-8 是 4 字节");
    assert_eq!(decode_java_modified_utf8(&cesu), emoji);
    // 反面（证明上面那条断言不是恒真）：按 UTF-8 硬解会得到替换字符。
    let lossy = String::from_utf8_lossy(&cesu);
    assert_ne!(lossy, emoji, "按 UTF-8 硬解必须**解错**，否则本测试没测到东西");
    assert!(
        lossy.chars().all(|c| c == '\u{FFFD}'),
        "硬解结果应当整串都是替换字符，实测：{lossy:?}"
    );
    // 实测 6 个，不是「一个字符换一个」：Rust 的校验器判定 ED A0.. 这三字节本身是非法序列，
    // 并在**每个**非法字节处各换一个 U+FFFD。⇒ journal 里「emoji 静默变两个 U+FFFD」
    // 这句是估的，实际污染是三倍长（2026-10-15 复核 H3 时实测更正）。
    assert_eq!(lossy.chars().count(), 6, "每个非法字节各换一个 U+FFFD");
}

#[test]
fn nul_字符编成_c0_80_而解码侧认得它() {
    // modified UTF-8 刻意不用裸 0x00（否则 C 字符串在中途就断了），U+0000 写成 0xC0 0x80。
    let s = "a\u{0000}b";
    let bytes = java_bytes(s);
    assert_eq!(bytes, vec![b'a', 0xC0, 0x80, b'b']);
    assert!(!bytes.contains(&0u8), "不得出现裸 0x00");
    assert_eq!(decode_java_modified_utf8(&bytes), s, "NUL 必须原样解回，不能丢字符");
}

#[test]
fn bmp_非代理字符与_utf8_同字节因此直通() {
    // CESU-8 与 UTF-8 只在代理项与 U+0000 上不同；常用中日韩与拉丁文完全一致。
    for s in ["", "plain ascii", "https://cn.bing.com/search?q=棠雪", "\u{00A0}不换行空格"] {
        assert_eq!(java_bytes(s), s.as_bytes(), "BMP 字符两种编码应当逐字节相同：{s:?}");
        assert_eq!(decode_java_modified_utf8(s.as_bytes()), s);
    }
}

#[test]
fn 坏输入只换替换字符绝不panic() {
    // 真机上这来自被截断的字节流或第三方拼出来的串：边界上**只允许**降级，不允许崩。
    for bad in [
        vec![0xEDu8, 0xA0, 0xBC, b'a'], // 高代理项没有后继低代理项
        vec![0xC0],                     // 半个 NUL 编码
        vec![0xF0, 0x9F],               // 截断的 4 字节序列
        vec![0xFF, 0xFE],               // 根本不可能的首字节
    ] {
        let out = decode_java_modified_utf8(&bad); // 不 panic 就是本用例的断言主体
        let _ = out.chars().count();
        // 「只换替换字符」这句也得有断言兜着：坏字节**不得**解出任何有效字符，
        // 只允许剩下替换字符（U+FFFD）与本来就合法的 ASCII（上面那条里的 'a'）。
        assert!(
            out.chars().all(|c| c == '\u{FFFD}' || c.is_ascii()),
            "坏输入解出了不该有的字符：{bad:?} → {out:?}"
        );
    }
}
