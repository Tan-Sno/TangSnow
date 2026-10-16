package io.github.tan_sno.tangsnow.core

import io.github.tan_sno.tangsnow.data.Bookmark
import io.github.tan_sno.tangsnow.data.BookmarkHtml
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rust 侧 JSON 边界 ↔ Kotlin 类型 的适配层。
 *
 * 仅在 [CoreNative.available] 为 true 时被调用（Android 运行时，org.json 可用）。
 * JSON 形状由 tangsnow-core 的 serde 结构定义：
 *  - 条目对象：`{"url": String, "title": String, "created_at_ms": Long?}`（缺失 = null）
 *  - 解析输出：`{"entries": [条目...], "dropped": n}`
 *  - 清洗输出：`{"entries": [条目...], "skipped": n}`
 *
 * **失败口径**：[parseImportCounted] / [sanitize] 返回 null = 本次不可用（native 缺失、返回 null，
 * 或返回的 JSON 不合形状）。调用方必须按「失败」处理 —— 报错、不写库、不清旧数据，
 * **不许**把它折叠成「0 条」。理由见 [decode]。
 */
internal object CoreBridge {

    private fun entryFrom(o: JSONObject): BookmarkHtml.Entry {
        val ms = if (o.has("created_at_ms") && !o.isNull("created_at_ms")) {
            o.getLong("created_at_ms")
        } else {
            null
        }
        return BookmarkHtml.Entry(url = o.getString("url"), title = o.getString("title"), createdAtMs = ms)
    }

    private fun entryJson(e: BookmarkHtml.Entry): JSONObject =
        JSONObject().apply {
            put("url", e.url)
            put("title", e.title)
            if (e.createdAtMs != null) put("created_at_ms", e.createdAtMs)
        }

    fun parseImportCounted(html: String, nowMs: Long): Pair<List<BookmarkHtml.Entry>, Int>? =
        CoreNative.bookmarkParse(html, nowMs)?.let { decode(it, "dropped") }

    fun sanitize(
        raw: List<BookmarkHtml.Entry>,
        existingUrls: Set<String>,
    ): Pair<List<BookmarkHtml.Entry>, Int>? {
        val rawJson = JSONArray().apply { raw.forEach { put(entryJson(it)) } }.toString()
        val existingJson = JSONArray().apply { existingUrls.forEach { put(it) } }.toString()
        return CoreNative.bookmarkSanitize(rawJson, existingJson)?.let { decode(it, "skipped") }
    }

    /**
     * 解 Rust 边界的 JSON 输出（`{"entries":[...], "<countKey>":n}`）。
     *
     * @return `entries to 计数`；**null = 本次不可用** —— 输出不是 JSON 对象、缺 `entries`、
     * 条目形状不对、计数键缺失或类型不符，全都算这一档。
     *
     * 为什么这道 try 必须存在：[CoreNative.guard] 只包住 **JNI 调用本身**（native 侧失败 →
     * 返回 null → 兜底），管不到「调用成功却吐回坏 JSON」这一档；原先 `JSONObject(out)`、
     * `getJSONArray("entries")`、`getInt(countKey)` 全在防护网之外，一颗坏字节就是一个
     * 未捕获的 `JSONException` 闪退（2026-10-15 外部审查 H2）。
     *
     * 为什么回落成 null 而不是 `emptyList to 0`：后者与「这个文件里确实一条书签都没有」
     * 在调用方眼里长得一模一样 —— 那是把失败报成成功，与导出链路「导出失败不许显示成功」
     * 同一条判据。调用方拿到 null 必须按「本次解析失败」处理（报错、别写库、别清旧数据）。
     */
    internal fun decode(out: String, countKey: String): Pair<List<BookmarkHtml.Entry>, Int>? =
        try {
            val obj = JSONObject(out)
            val arr = obj.getJSONArray("entries")
            val entries = ArrayList<BookmarkHtml.Entry>(arr.length())
            for (i in 0 until arr.length()) entries += entryFrom(arr.getJSONObject(i))
            entries to obj.getInt(countKey)
        } catch (t: Throwable) {
            // 兜 Throwable 而不是只兜 JSONException：org.json 在异常形状上并不只抛后者。
            // runCatching 包住日志：JVM 单测没有 android.util.Log 桩，"not mocked" 本身会抛。
            runCatching { android.util.Log.e("CoreBridge", "Rust 边界的 JSON 不合形状（$countKey）", t) }
            null
        }

    fun export(bookmarks: List<Bookmark>): String? {
        val arr = JSONArray().apply {
            bookmarks.forEach {
                put(
                    JSONObject().apply {
                        put("url", it.url)
                        put("title", it.title)
                        put("created_at_ms", it.createdAt)
                    },
                )
            }
        }
        return CoreNative.bookmarkExport(arr.toString())
    }
}
