package io.github.tan_sno.tangsnow.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Bookmark(val id: Long, val url: String, val title: String, val createdAt: Long)

data class HistoryItem(val id: Long, val url: String, val title: String, val visitedAt: Long)

/**
 * 轻量本地库：书签 + 历史。
 * 采用原生 SQLite（无第三方依赖、无注解处理器），
 * 读写统一走内部 IO 线程池，避免阻塞主线程。
 */
class BrowserDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE bookmarks (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                url TEXT NOT NULL UNIQUE,
                title TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE history (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                url TEXT NOT NULL,
                title TEXT NOT NULL DEFAULT '',
                visited_at INTEGER NOT NULL
            )"""
        )
        createIndexes(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createIndexes(db)
            // 老库一次裁剪：只保留最近 200 条
            db.execSQL(
                "DELETE FROM history WHERE _id NOT IN " +
                    "(SELECT _id FROM history ORDER BY visited_at DESC LIMIT $HISTORY_KEEP)"
            )
        }
    }

    /** 历史检索索引：地址栏联想与历史页排序走 visited_at / url 查询时显著提速 */
    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_visited ON history(visited_at DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_url ON history(url)")
    }

    // ---------- 书签 ----------

    fun isBookmarked(url: String): Boolean {
        db().rawQuery("SELECT 1 FROM bookmarks WHERE url = ?", arrayOf(url)).use { c ->
            return c.moveToFirst()
        }
    }

    /**
     * 收藏：URL 已存在时只刷新标题并保留原收藏时间（重复收藏不重置 created_at，
     * 也不会因 CONFLICT_REPLACE 重建 _id 而跳到列表最前）；不存在才新建。
     * 更新/插入间采用先 UPDATE 后 INSERT，即使并发也只会落一行。
     */
    fun insertBookmark(url: String, title: String) {
        val db = db()
        val cvTitle = ContentValues().apply { put("title", title) }
        val updated = db.update("bookmarks", cvTitle, "url = ?", arrayOf(url))
        if (updated == 0) {
            val cv = ContentValues().apply {
                put("url", url)
                put("title", title)
                put("created_at", System.currentTimeMillis())
            }
            // IGNORE：与其它并发写入竞争时宁可跳过，也不覆盖已存在的原收藏
            db.insertWithOnConflict("bookmarks", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    /**
     * 批量插入书签（书签导入路径）：整批包在一个写事务里。此前导入逐条调
     * [insertBookmark]，每条各自落盘 —— 一次 ≤1000 条的导入就是 ≤1000 次独立
     * 事务（每次都带 fsync 语义），事务化后一次提交。
     * 复用 [insertBookmark] 的「已存在只刷新标题」upsert 语义，导入天然幂等。
     */
    fun insertBookmarks(items: List<Pair<String, String>>) {
        if (items.isEmpty()) return
        val db = db()
        db.beginTransaction()
        try {
            for ((url, title) in items) insertBookmark(url, title)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteBookmark(url: String) {
        db().delete("bookmarks", "url = ?", arrayOf(url))
    }

    fun allBookmarks(): List<Bookmark> {
        db().query("bookmarks", null, null, null, null, null, "created_at DESC").use { c ->
            val out = ArrayList<Bookmark>(c.count)
            while (c.moveToNext()) {
                out += Bookmark(
                    id = c.getLong(0), url = c.getString(1),
                    title = c.getString(2), createdAt = c.getLong(3),
                )
            }
            return out
        }
    }

    // ---------- 历史 ----------

    /**
     * 同一 URL 已存在时刷新 visited_at（并在给了标题时刷新 title），避免历史列表被同一地址刷屏。
     * 返回受影响行数。
     *
     * [title] 为 **null** 表示「本次访问没有可用标题，只更新时间、不要动标题」。
     * 这一分支是必需的：历史有**两条写入路径**——界面层 `onLocationChanged`（拿得到真实标题）
     * 与内核 `onVisited`（GeckoView 只给 url，不给标题）。两者并发时，若内核那条把标题写成
     * 空串，就会把界面层刚写入的真实标题覆盖掉，历史列表标题在“标题/URL”之间抖动。
     * 因此约定：**没有标题就不要写标题**，而不是写一个空值。
     *
     * 整体包在一个写事务里：UPDATE 与 INSERT 之间不会被其它协程插入同一 URL，
     * 杜绝“两个都 UPDATE 0 行 → 双双 INSERT”造成的重复历史行。
     */
    fun touchHistory(url: String, title: String?): Int {
        val db = db()
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            val updated = if (title == null) {
                db.compileStatement("UPDATE history SET visited_at = ? WHERE url = ?").use { st ->
                    st.bindLong(1, now)
                    st.bindString(2, url)
                    st.executeUpdateDelete()
                }
            } else {
                db.compileStatement("UPDATE history SET visited_at = ?, title = ? WHERE url = ?")
                    .use { st ->
                        st.bindLong(1, now)
                        st.bindString(2, title)
                        st.bindString(3, url)
                        st.executeUpdateDelete()
                    }
            }
            val affected = if (updated > 0) {
                updated
            } else {
                // title 列 NOT NULL：无标题时退回用 URL 占位（与原行为一致）
                val cv = ContentValues().apply {
                    put("url", url)
                    put("title", title ?: url)
                    put("visited_at", now)
                }
                val id = db.insert("history", null, cv)
                // 仅真正插入新行时才触发一次裁剪（保持最近 keep 条）
                if (id >= 0) {
                    trimHistoryTo(HISTORY_KEEP)
                    1
                } else 0
            }
            db.setTransactionSuccessful()
            return affected
        } finally {
            db.endTransaction()
        }
    }

    /** 只保留最近 [keep] 条历史，更早的自动裁掉（走 visited_at 索引，开销极小） */
    fun trimHistoryTo(keep: Int) {
        db().execSQL(
            "DELETE FROM history WHERE _id NOT IN " +
                "(SELECT _id FROM history ORDER BY visited_at DESC LIMIT $keep)"
        )
    }

    fun deleteHistoryRow(id: Long) {
        db().delete("history", "_id = ?", arrayOf(id.toString()))
    }

    fun clearHistory() {
        db().delete("history", null, null)
    }

    fun clearBookmarks() {
        db().delete("bookmarks", null, null)
    }

    fun allHistory(): List<HistoryItem> {
        db().query("history", null, null, null, null, null, "visited_at DESC").use { c ->
            val out = ArrayList<HistoryItem>(c.count)
            while (c.moveToNext()) {
                out += HistoryItem(
                    id = c.getLong(0), url = c.getString(1),
                    title = c.getString(2), visitedAt = c.getLong(3),
                )
            }
            return out
        }
    }

    /** 最近 [limit] 条历史（地址栏联想用，避免每次全表扫描） */
    fun recentHistory(limit: Int): List<HistoryItem> {
        db().query("history", null, null, null, null, null, "visited_at DESC", "$limit").use { c ->
            val out = ArrayList<HistoryItem>(c.count)
            while (c.moveToNext()) {
                out += HistoryItem(
                    id = c.getLong(0), url = c.getString(1),
                    title = c.getString(2), visitedAt = c.getLong(3),
                )
            }
            return out
        }
    }

    private fun db(): SQLiteDatabase = writableDatabase

    companion object {
        private const val DB_NAME = "tangsnow.db"
        private const val DB_VERSION = 2
        /** 专门历史页可见范围：超出后按最近访问自动裁剪 */
        const val HISTORY_KEEP = 200

        @Volatile
        private var instance: BrowserDb? = null

        /**
         * 单例获取。**`instance` 必须是 `@Volatile`**：这里是双检锁，
         * 若不加 `@Volatile`，另一个线程可能在 `instance` 已可见但对象字段尚未完成
         * 发布（未安全发布）时读到半初始化对象 —— 表现是极难复现的随机异常。
         * （同类问题在 `BrowserSessionManager.instance` 已按此修正，此处此前漏了。）
         */
        fun get(context: Context): BrowserDb =
            instance ?: synchronized(this) {
                instance ?: BrowserDb(context.applicationContext).also { instance = it }
            }
    }
}