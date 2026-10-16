//! Android JNI 桥接（feature = "jni" 时编译）。
//!
//! Kotlin 侧声明见 `app/src/coreRust/java/io/github/tan_sno/tangsnow/core/CoreNative.kt`
//! （coreRust flavor 源集，包 `io.github.tan_sno.tangsnow.core`，类 `CoreNative`）。
//! ⚠️ 包名含下划线：JNI 名称转义 `tan_sno` → `tan_1sno`。
//! 两侧符号与形参个数由 `app/src/test/.../BridgeSignatureParityTest` 钉住（gradle 门禁）。
//!
//! 边界约定：
//!  - 入向按 Java 的 **modified UTF-8（CESU-8）** 解、出向按它编（两侧同源，见
//!    [`crate::decode_java_modified_utf8`]）；Rust 内部一律 UTF-8；
//!  - Rust 返回「可空」用 null string 表示（Kotlin 侧 `guard` 就地兜底，见其 KDoc）；
//!  - 结构化数据（书签条目）在边界上走 JSON（serde_json），核心 API 保持类型化；
//!  - 本层不做任何 panic 路径：release 构建为 `panic = "abort"`，
//!    FFI 侧崩溃即进程死亡，因此只允许 infallible 操作出边界。

use crate::bookmark_html::{self, Bookmark, Entry};
use crate::search_engines::{self, SearchEngine};
use crate::url_utils;
use jni::objects::{JClass, JObject, JString};
use jni::sys::{jboolean, jint, jintArray, jstring, jlong};
use jni::JNIEnv;

/// 提取 Kotlin 传来的字符串；null → None。
fn opt_str(env: &mut JNIEnv, s: &JString) -> Option<String> {
    if s.is_null() {
        jlog("opt_str: input JString is null");
        return None;
    }
    match env.get_string(s) {
        // ⚠️ 必须按 modified UTF-8（CESU-8）解，见 [`crate::decode_java_modified_utf8`]。
        // 这里的 `to_bytes()` 拿到的是 JNI 原样给出的字节，`to_string_lossy()` 会按 UTF-8
        // 硬解，一颗 emoji 静默变成六个 U+FFFD（2026-10-15 外部审查 H3）。
        Ok(v) => Some(crate::decode_java_modified_utf8(v.to_bytes())),
        Err(e) => {
            let p = if env.exception_occurred().is_ok() { "yes" } else { "no" };
            jlog(&format!("opt_str: get_string failed err={e:?}, pending_ex={p}"));
            None
        }
    }
}

/// Android logcat 打点（零依赖：直连 liblog）。定位 JNI null 的真实来源用。
#[cfg(target_os = "android")]
fn jlog(msg: &str) {
    use std::ffi::CString;
    extern "C" {
        fn __android_log_print(prio: i32, tag: *const std::os::raw::c_char, text: *const std::os::raw::c_char) -> i32;
    }
    let tag = CString::new("CoreNativeRS").unwrap();
    let text = CString::new(msg).unwrap_or_else(|_| CString::new("(bad msg)").unwrap());
    unsafe { __android_log_print(6, tag.as_ptr(), text.as_ptr()) }; // 6 = ERROR
}
#[cfg(not(target_os = "android"))]
fn jlog(_msg: &str) {}

/// 返回字符串（None → null）。
fn ret_str(env: &mut JNIEnv, s: Option<String>) -> jstring {
    match s {
        Some(v) => match env.new_string(v) {
            Ok(js) => js.into_raw(),
            Err(_) => {
                let p = if env.exception_occurred().is_ok() { "yes" } else { "no" };
                jlog(&format!("ret_str: new_string failed, pending_ex={p}"));
                std::ptr::null_mut()
            }
        },
        None => std::ptr::null_mut(),
    }
}

fn ret_string(env: &mut JNIEnv, s: String) -> jstring {
    ret_str(env, Some(s))
}

// ---------------------------------------------------------------- URL 工具

#[no_mangle]
pub extern "system" fn Java_io_github_tan_1sno_tangsnow_core_CoreNative_percentEncodeImpl(
    mut env: JNIEnv,
    _class: &JClass,
    value: JString,
) -> jstring {
    match opt_str(&mut env, &value) {
        Some(v) => ret_string(&mut env, crate::percent_encode::percent_encode(&v)),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_tan_1sno_tangsnow_core_CoreNative_looksLikeUriImpl(
    mut env: JNIEnv,
    _class: &JClass,
    input: JString,
) -> jboolean {
    match opt_str(&mut env, &input) {
        Some(v) => url_utils::looks_like_uri(&v) as jboolean,
        None => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_tan_1sno_tangsnow_core_CoreNative_isLocalNetworkAddressImpl(
    mut env: JNIEnv,
    _class: &JClass,
    input: JString,
) -> jboolean {
    match opt_str(&mut env, &input) {
        Some(v) => url_utils::is_local_network_address(&v) as jboolean,
        None => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_tan_1sno_tangsnow_core_CoreNative_schemeOfImpl(
    mut env: JNIEnv,
    _class: &JClass,
    input: JString,
) -> jstring {
    match opt_str(&mut env, &input) {
        Some(v) => ret_string(&mut env, url_utils::scheme_of(&v)),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_tan_1sno_tangsnow_core_CoreNative_isWebNavigationSchemeImpl(
    mut env: JNIEnv,
    _class: &JClass,
    scheme: JString,
) -> jboolean {
    url_utils::is_web_navigation_scheme(opt_str(&mut env, &scheme).as_deref()) as jboolean
}

#[no_mangle]
pub extern "system" fn Java_io_github_tan_1sno_tangsnow_core_CoreNative_isNeverWebContentSchemeImpl(
    mut env: JNIEnv,
    _class: &JClass,
    scheme: JString,
) -> jboolean {
    url_utils::is_never_web_content_scheme(opt_str(&mut env, &scheme).as_deref()) as jboolean
}

#[no_mangle]
pub extern "system" fn Java_io_github_tan_1sno_tangsnow_core_CoreNative_isPortableUrlImpl(
    mut env: JNIEnv,
    _class: &JClass,
    url: JString,
) -> jboolean {
    url_utils::is_portable_url(opt_str(&mut env, &url).as_deref()) as jboolean
}

#[no_mangle]
pub extern "system" fn Java_io_github_tan_1sno_tangsnow_core_CoreNative_toAsciiUrlImpl(
    mut env: JNIEnv,
    _class: &JClass,
    input: JString,
) -> jstring {
    match opt_str(&mut env, &input) {
        Some(v) => ret_str(&mut env, url_utils::to_ascii_url(&v)),
        None => std::ptr::null_mut(),
    }
}

/// 地址栏解析：返回 `[url, isSearch]`（引擎只取 template；labelRes/label 与 id 都不参与判定）。
#[no_mangle]
pub extern "system" fn Java_io_github_tan_1sno_tangsnow_core_CoreNative_resolveInfoImpl(
    mut env: JNIEnv,
    _class: &JClass,
    input: JString,
    template: JString,
) -> jni::sys::jobjectArray {
    let (Some(input), Some(template)) = (opt_str(&mut env, &input), opt_str(&mut env, &template)) else {
        let p = if env.exception_occurred().is_ok() { "yes" } else { "no" };
        jlog(&format!("resolveInfo: args failed, pending_ex={p}"));
        return std::ptr::null_mut();
    };
    let engine = SearchEngine::template_only(&template);
    let Some(r) = url_utils::resolve_info(&input, &engine) else {
        jlog("resolveInfo: resolve_info returned None");
        return std::ptr::null_mut();
    };
    let arr = match env.new_object_array(2, "java/lang/String", JObject::null()) {
        Ok(a) => a,
        Err(_) => {
            let p = if env.exception_occurred().is_ok() { "yes" } else { "no" };
            jlog(&format!("resolveInfo: new_object_array failed, pending_ex={p}"));
            return std::ptr::null_mut();
        }
    };
    let url = env.new_string(&r.url).ok();
    let flag = env.new_string(if r.is_search { "1" } else { "0" }).ok();
    if let (Some(u), Some(f)) = (url, flag) {
        // set 失败（多半因线程上挂着 pending exception）绝不能留半填充数组：
        // Kotlin 侧 r[0] 穿进 Resolved 的非空主构造就是 NPE（真机闪退实锤路径）。
        // 任一元素写失败 → 整体回 null，让 Kotlin 走 searchUrl 降级链。
        let ok0 = env.set_object_array_element(&arr, 0, u).is_ok();
        let ok1 = env.set_object_array_element(&arr, 1, f).is_ok();
        if !(ok0 && ok1) {
            let p = if env.exception_occurred().is_ok() { "yes" } else { "no" };
            jlog(&format!("resolveInfo: set_array failed(ok0={ok0},ok1={ok1}), pending_ex={p}"));
            let _ = env.exception_clear();
            return std::ptr::null_mut();
        }
    } else {
        let p = if env.exception_occurred().is_ok() { "yes" } else { "no" };
        jlog(&format!("resolveInfo: new_string failed, pending_ex={p}"));
        let _ = env.exception_clear();
        return std::ptr::null_mut();
    }
    arr.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_io_github_tan_1sno_tangsnow_core_CoreNative_extractUrlFromTextImpl(
    mut env: JNIEnv,
    _class: &JClass,
    text: JString,
) -> jstring {
    match opt_str(&mut env, &text) {
        Some(v) => ret_str(&mut env, url_utils::extract_url_from_text(&v)),
        None => std::ptr::null_mut(),
    }
}

// ---------------------------------------------------------------- 搜索引擎

#[no_mangle]
pub extern "system" fn Java_io_github_tan_1sno_tangsnow_core_CoreNative_searchUrlFromTemplateImpl(
    mut env: JNIEnv,
    _class: &JClass,
    template: JString,
    query: JString,
) -> jstring {
    let (Some(t), Some(q)) = (opt_str(&mut env, &template), opt_str(&mut env, &query)) else {
        return std::ptr::null_mut();
    };
    let engine = SearchEngine::template_only(&t);
    ret_string(&mut env, engine.search_url(&q))
}

#[no_mangle]
pub extern "system" fn Java_io_github_tan_1sno_tangsnow_core_CoreNative_templateHostOfImpl(
    mut env: JNIEnv,
    _class: &JClass,
    template: JString,
) -> jstring {
    match opt_str(&mut env, &template) {
        Some(t) => ret_str(&mut env, search_engines::template_host_of(&t)),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_tan_1sno_tangsnow_core_CoreNative_hostMatchesImpl(
    mut env: JNIEnv,
    _class: &JClass,
    host: JString,
    engine_id: JString,
    template_host: JString,
) -> jboolean {
    match (
        opt_str(&mut env, &host),
        opt_str(&mut env, &engine_id),
        opt_str(&mut env, &template_host),
    ) {
        (Some(h), Some(id), Some(t)) => search_engines::host_matches(&h, &id, &t) as jboolean,
        _ => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_tan_1sno_tangsnow_core_CoreNative_displayForUrlImpl(
    mut env: JNIEnv,
    _class: &JClass,
    url: JString,
    template: JString,
    query_param: JString,
    engine_id: JString,
) -> jstring {
    let (Some(u), Some(t)) = (opt_str(&mut env, &url), opt_str(&mut env, &template)) else {
        return std::ptr::null_mut();
    };
    let qp = opt_str(&mut env, &query_param);
    // 引擎 id 由 Kotlin 传进来：`HOST_ALIASES` 以**真 id** 为键，本层无权也不该自造。
    // 此前写死 `"jni"` ⇒ 别名表永不命中，`bing.com` / `sm.cn` 这类「模板主机之外」的
    // 官方结果页上「搜完回显关键词」整条失效（2026-10-15 外部审查 M5）。
    // 空/缺失时退到 `custom_` 前缀：查不到别名 = **不配别名**，与 Kotlin 对自定义引擎的口径一致。
    let id = opt_str(&mut env, &engine_id)
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| search_engines::CUSTOM_ID_PREFIX.to_string());
    let engine = SearchEngine::new(&id, &t, qp.as_deref());
    ret_str(&mut env, search_engines::display_for_url(&u, &[engine]))
}

// ---------------------------------------------------------------- 书签（JSON 边界）

#[derive(serde::Serialize)]
struct ParseOut {
    entries: Vec<JsonEntry>,
    dropped: u64,
}

#[derive(serde::Serialize, serde::Deserialize)]
struct JsonEntry {
    url: String,
    title: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    created_at_ms: Option<i64>,
}

#[derive(serde::Serialize)]
struct SanitizeOut {
    entries: Vec<JsonEntry>,
    skipped: u64,
}

impl From<Entry> for JsonEntry {
    fn from(e: Entry) -> Self {
        JsonEntry {
            url: e.url,
            title: e.title,
            created_at_ms: e.created_at_ms,
        }
    }
}

impl From<JsonEntry> for Entry {
    fn from(j: JsonEntry) -> Self {
        Entry::new(j.url, j.title, j.created_at_ms)
    }
}

/// 解析书签 HTML：入参 (html, nowMs)，返回 JSON
/// `{"entries":[{url,title,created_at_ms?}...],"dropped":n}`。
#[no_mangle]
pub extern "system" fn Java_io_github_tan_1sno_tangsnow_core_CoreNative_bookmarkParseImpl(
    mut env: JNIEnv,
    _class: &JClass,
    html: JString,
    now_ms: jlong,
) -> jstring {
    let Some(html) = opt_str(&mut env, &html) else {
        return std::ptr::null_mut();
    };
    let (entries, dropped) = bookmark_html::parse_import_counted(&html, Some(now_ms));
    let out = ParseOut {
        entries: entries.into_iter().map(Into::into).collect(),
        dropped,
    };
    match serde_json::to_string(&out) {
        Ok(s) => ret_string(&mut env, s),
        Err(_) => std::ptr::null_mut(),
    }
}

/// 导入清洗：入参 (rawJson, existingUrlsJson)，返回 JSON
/// `{"entries":[...],"skipped":n}`。
#[no_mangle]
pub extern "system" fn Java_io_github_tan_1sno_tangsnow_core_CoreNative_bookmarkSanitizeImpl(
    mut env: JNIEnv,
    _class: &JClass,
    raw_json: JString,
    existing_json: JString,
) -> jstring {
    let (Some(raw), Some(existing)) = (opt_str(&mut env, &raw_json), opt_str(&mut env, &existing_json)) else {
        return std::ptr::null_mut();
    };
    let Ok(raw): Result<Vec<JsonEntry>, _> = serde_json::from_str(&raw) else {
        return std::ptr::null_mut();
    };
    let existing: Vec<String> = serde_json::from_str(&existing).unwrap_or_default();
    let raw: Vec<Entry> = raw.into_iter().map(Into::into).collect();
    let (entries, skipped) = bookmark_html::sanitize(&raw, &existing.into_iter().collect());
    let out = SanitizeOut {
        entries: entries.into_iter().map(Into::into).collect(),
        skipped,
    };
    match serde_json::to_string(&out) {
        Ok(s) => ret_string(&mut env, s),
        Err(_) => std::ptr::null_mut(),
    }
}

/// 导出：入参 JSON `[{url,title,created_at_ms?}...]`（created_at 缺省按 0 处理由调用方保证），
/// 返回 Netscape 书签 HTML。
#[no_mangle]
pub extern "system" fn Java_io_github_tan_1sno_tangsnow_core_CoreNative_bookmarkExportImpl(
    mut env: JNIEnv,
    _class: &JClass,
    bookmarks_json: JString,
) -> jstring {
    let Some(json) = opt_str(&mut env, &bookmarks_json) else {
        return std::ptr::null_mut();
    };
    let Ok(list) = serde_json::from_str::<Vec<BookmarkJson>>(&json) else {
        return std::ptr::null_mut();
    };
    let bookmarks: Vec<Bookmark> = list
        .into_iter()
        .map(|b| Bookmark {
            url: b.url,
            title: b.title,
            created_at: b.created_at_ms.unwrap_or(0),
        })
        .collect();
    ret_string(&mut env, bookmark_html::export(&bookmarks))
}

#[derive(serde::Deserialize)]
struct BookmarkJson {
    url: String,
    title: String,
    created_at_ms: Option<i64>,
}

// ---------------------------------------------------------------- 历史

#[no_mangle]
pub extern "system" fn Java_io_github_tan_1sno_tangsnow_core_CoreNative_shouldSkipHistoryUrlImpl(
    mut env: JNIEnv,
    _class: &JClass,
    url: JString,
) -> jboolean {
    match opt_str(&mut env, &url) {
        Some(u) => crate::history::should_skip_history_url(&u) as jboolean,
        None => 0,
    }
}

// ---------------------------------------------------------------- 崩溃日志

#[no_mangle]
pub extern "system" fn Java_io_github_tan_1sno_tangsnow_core_CoreNative_crashRedactUrlsImpl(
    mut env: JNIEnv,
    _class: &JClass,
    text: JString,
) -> jstring {
    match opt_str(&mut env, &text) {
        Some(t) => ret_string(&mut env, crate::crash_logger::redact_urls(&t)),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_tan_1sno_tangsnow_core_CoreNative_crashDisplayStampImpl(
    mut env: JNIEnv,
    _class: &JClass,
    file_name: JString,
) -> jstring {
    match opt_str(&mut env, &file_name) {
        Some(n) => ret_string(&mut env, crate::crash_logger::display_stamp(&n)),
        None => std::ptr::null_mut(),
    }
}

// ---------------------------------------------------------------- 位图计算

#[no_mangle]
pub extern "system" fn Java_io_github_tan_1sno_tangsnow_core_CoreNative_bitmapRequiredSampleSizeImpl(
    _env: &mut JNIEnv,
    _class: &JClass,
    out_w: jint,
    out_h: jint,
    req_w: jint,
    req_h: jint,
    max_pixels: jlong,
) -> jint {
    crate::bitmaps::required_sample_size(out_w, out_h, req_w, req_h, max_pixels)
}

#[no_mangle]
pub extern "system" fn Java_io_github_tan_1sno_tangsnow_core_CoreNative_bitmapCenterCropRectImpl(
    mut env: JNIEnv,
    _class: &JClass,
    src_w: jint,
    src_h: jint,
    target_w: jint,
    target_h: jint,
) -> jintArray {
    let r = crate::bitmaps::center_crop_rect(src_w, src_h, target_w, target_h);
    match env.new_int_array(4) {
        Ok(arr) => {
            let _ = env.set_int_array_region(&arr, 0, &r);
            arr.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

// ---------------------------------------------------------------- 下载文件名

#[no_mangle]
pub extern "system" fn Java_io_github_tan_1sno_tangsnow_core_CoreNative_dlParseFileNameImpl(
    mut env: JNIEnv,
    _class: &JClass,
    content_disposition: JString,
    url: JString,
) -> jstring {
    let cd = opt_str(&mut env, &content_disposition);
    let u = opt_str(&mut env, &url);
    ret_string(&mut env, crate::download_filename::parse_file_name(cd.as_deref(), u.as_deref()))
}

#[no_mangle]
pub extern "system" fn Java_io_github_tan_1sno_tangsnow_core_CoreNative_dlSanitizeFileNameImpl(
    mut env: JNIEnv,
    _class: &JClass,
    raw: JString,
) -> jstring {
    match opt_str(&mut env, &raw) {
        Some(r) => ret_string(&mut env, crate::download_filename::sanitize_file_name(&r)),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_tan_1sno_tangsnow_core_CoreNative_dlIsExecutableNameImpl(
    mut env: JNIEnv,
    _class: &JClass,
    file_name: JString,
) -> jboolean {
    match opt_str(&mut env, &file_name) {
        Some(n) => crate::download_filename::is_executable_name(&n) as jboolean,
        None => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_tan_1sno_tangsnow_core_CoreNative_dlBareMimeTypeImpl(
    mut env: JNIEnv,
    _class: &JClass,
    raw: JString,
) -> jstring {
    let r = opt_str(&mut env, &raw);
    match crate::download_filename::bare_mime_type(r.as_deref()) {
        Some(m) => ret_string(&mut env, m),
        None => std::ptr::null_mut(),
    }
}

// ---------------------------------------------------------------- 法律文本

#[no_mangle]
pub extern "system" fn Java_io_github_tan_1sno_tangsnow_core_CoreNative_legalParseBoldRangesImpl(
    mut env: JNIEnv,
    _class: &JClass,
    raw: JString,
) -> jintArray {
    let Some(t) = opt_str(&mut env, &raw) else { return std::ptr::null_mut() };
    let m = crate::legal_text::parse(&t);
    // 扁平化 [start0, end0, start1, end1, ...]（字符索引闭区间）
    let flat: Vec<jint> = m.bold_ranges.iter().flat_map(|(a, b)| [*a as jint, *b as jint]).collect();
    // 长度按 `i32::try_from` 收窄，不用 `as i32`：`as` 在超界时**静默**给出负数或错值，
    // Kotlin 侧拿到的是长度不对的数组。要触发需要 ≥2^31 个区间端点（本机不可达），
    // 但不可达不等于可以静默 —— 超长时按「算不出来」返回 null，由调用方回落。
    let Ok(len) = jni::sys::jsize::try_from(flat.len()) else {
        jlog("legalParseBoldRanges: 区间数超出 jsize 可表示范围，按加载失败处理");
        return std::ptr::null_mut();
    };
    match env.new_int_array(len) {
        Ok(arr) => {
            let _ = env.set_int_array_region(&arr, 0, &flat);
            arr.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

// ---------------------------------------------------------------- 更新链路（2026-10-08 批）

#[no_mangle]
pub extern "system" fn Java_io_github_tan_1sno_tangsnow_core_CoreNative_upNormalizeVersionImpl(
    mut env: JNIEnv,
    _class: &JClass,
    raw: JString,
) -> jstring {
    match opt_str(&mut env, &raw) {
        Some(r) => ret_string(&mut env, crate::update_logic::normalize_version(&r)),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_tan_1sno_tangsnow_core_CoreNative_upIsNewerImpl(
    mut env: JNIEnv,
    _class: &JClass,
    remote: JString,
    local: JString,
) -> jboolean {
    let (Some(r), Some(l)) = (opt_str(&mut env, &remote), opt_str(&mut env, &local)) else {
        return 0;
    };
    crate::update_logic::is_newer(&r, &l) as jboolean
}

#[no_mangle]
pub extern "system" fn Java_io_github_tan_1sno_tangsnow_core_CoreNative_upIsTrustedTargetImpl(
    mut env: JNIEnv,
    _class: &JClass,
    raw: JString,
    scheme: JString,
    host: JString,
    expected_host: JString,
) -> jboolean {
    let Some(r) = opt_str(&mut env, &raw) else { return 0 };
    let Some(e) = opt_str(&mut env, &expected_host) else { return 0 };
    // scheme/host 允许为 null（Kotlin 可空参数）：null 一律视为不可信
    let scheme = opt_str(&mut env, &scheme);
    let host = opt_str(&mut env, &host);
    crate::update_logic::is_trusted_target(
        &r,
        scheme.as_deref(),
        host.as_deref(),
        &e,
    ) as jboolean
}

#[no_mangle]
pub extern "system" fn Java_io_github_tan_1sno_tangsnow_core_CoreNative_upPickAssetImpl(
    mut env: JNIEnv,
    _class: &JClass,
    assets_joined: JString,
    abis_joined: JString,
) -> jstring {
    let (Some(a), Some(b)) = (opt_str(&mut env, &assets_joined), opt_str(&mut env, &abis_joined))
    else {
        return std::ptr::null_mut();
    };
    match crate::update_logic::pick_asset(&a, &b) {
        Some(s) => ret_string(&mut env, s),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_tan_1sno_tangsnow_core_CoreNative_prefsNormalizeUrlImpl(
    mut env: JNIEnv,
    _class: &JClass,
    input: JString,
) -> jstring {
    match opt_str(&mut env, &input) {
        Some(i) => match crate::update_logic::normalize_custom_url(&i) {
            Some(u) => ret_string(&mut env, u),
            None => std::ptr::null_mut(),
        },
        None => std::ptr::null_mut(),
    }
}

// ---------------------------------------------------------------- 网页表单时间（2026-10-08 批 C）

#[no_mangle]
pub extern "system" fn Java_io_github_tan_1sno_tangsnow_core_CoreNative_dtParseLooseImpl(
    mut env: JNIEnv,
    _class: &JClass,
    kind: JString,
    raw: JString,
) -> jstring {
    let (Some(k), Some(r)) = (opt_str(&mut env, &kind), opt_str(&mut env, &raw)) else {
        return std::ptr::null_mut();
    };
    match crate::dt_prompts::parse_loose(&k, &r) {
        Some((value, with_seconds)) => ret_string(&mut env, format!("{}|{}", value, with_seconds as i32)),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_tan_1sno_tangsnow_core_CoreNative_dtFormatChosenImpl(
    mut env: JNIEnv,
    _class: &JClass,
    kind: JString,
    with_seconds: jboolean,
    y: jint,
    m: jint,
    d: jint,
    hh: jint,
    mm: jint,
) -> jstring {
    let Some(k) = opt_str(&mut env, &kind) else { return std::ptr::null_mut() };
    match crate::dt_prompts::format_chosen(
        &k,
        with_seconds != 0,
        y as i32,
        m as i32,
        d as i32,
        hh as i32,
        mm as i32,
    ) {
        Some(s) => ret_string(&mut env, s),
        None => std::ptr::null_mut(),
    }
}
