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

    fun parseImportCounted(html: String, nowMs: Long): Pair<List<BookmarkHtml.Entry>, Int> {
        val out = CoreNative.bookmarkParse(html, nowMs)
            ?: return emptyList<BookmarkHtml.Entry>() to 0
        val obj = JSONObject(out)
        val arr = obj.getJSONArray("entries")
        val entries = ArrayList<BookmarkHtml.Entry>(arr.length())
        for (i in 0 until arr.length()) entries += entryFrom(arr.getJSONObject(i))
        return entries to obj.getInt("dropped")
    }

    fun sanitize(
        raw: List<BookmarkHtml.Entry>,
        existingUrls: Set<String>,
    ): Pair<List<BookmarkHtml.Entry>, Int> {
        val rawJson = JSONArray().apply { raw.forEach { put(entryJson(it)) } }.toString()
        val existingJson = JSONArray().apply { existingUrls.forEach { put(it) } }.toString()
        val out = CoreNative.bookmarkSanitize(rawJson, existingJson)
            ?: return emptyList<BookmarkHtml.Entry>() to 0
        val obj = JSONObject(out)
        val arr = obj.getJSONArray("entries")
        val entries = ArrayList<BookmarkHtml.Entry>(arr.length())
        for (i in 0 until arr.length()) entries += entryFrom(arr.getJSONObject(i))
        return entries to obj.getInt("skipped")
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
