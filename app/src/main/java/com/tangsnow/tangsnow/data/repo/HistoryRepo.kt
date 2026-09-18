package com.tangsnow.tangsnow.data.repo

import com.tangsnow.tangsnow.data.BrowserDb
import com.tangsnow.tangsnow.data.HistoryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 历史仓库 */
object HistoryRepo {
    private val SKIP_PREFIXES = listOf(
        "about:", "chrome:", "data:", "blob:", "javascript:", "moz-extension:",
    )

    /**
     * 同一 URL 重复访问不再新增历史行；改为刷新 visited_at，
     * 既避免列表膨胀，也保证该地址始终浮到顶部（主流浏览器行为）。
     *
     * [title] 为空白时传 **null** 给库层：表示「本次没有可用标题」，
     * 库层只更新时间、不覆盖已有标题。历史有两条写入路径（界面层带真实标题、
     * 内核 onVisited 只给 URL），若这里把空标题落库就会互相覆盖（标题抖动）。
     */
    suspend fun add(url: String, title: String): Unit = withContext(Dispatchers.IO) {
        val lower = url.lowercase()
        if (SKIP_PREFIXES.any { lower.startsWith(it) }) return@withContext
        BrowserDb.get(ApplicationScope.context)
            .touchHistory(url, title.trim().ifBlank { null })
    }

    suspend fun list(): List<HistoryItem> = withContext(Dispatchers.IO) {
        BrowserDb.get(ApplicationScope.context).allHistory()
    }

    /** 最近 [limit] 条历史：地址栏联想专用，避免每次全表扫描拖慢输入 */
    suspend fun recent(limit: Int): List<HistoryItem> = withContext(Dispatchers.IO) {
        BrowserDb.get(ApplicationScope.context).recentHistory(limit)
    }

    /**
     * 只读查询：批量判定给定 URL 是否已写入历史表。
     * 用途：内核 [GeckoSession.HistoryDelegate.getVisited] 需要这批 URL 的“已访问”布尔数组，
     * 以便给网页内链接着色。一条 `WHERE url IN (...)` 一次查回命中集合，避免逐条查询；
     * 纯查询、不改写任何数据（不回灌内核数据）。返回与 [urls] 等长的布尔数组。
     *
     * **必须调用在 IO 线程**：GeckoView 把 HistoryDelegate 的回调标注为 `@UiThread`，
     * 而这里是磁盘 I/O；调用方（BrowserSessionManager.getVisited）已在 ioScope 上调用。
     *
     * **分批执行**：`IN (?,?,…)` 的绑定变量数受 SQLite 的 `SQLITE_MAX_VARIABLE_NUMBER`
     * 限制（旧版 Android 自带库默认 **999**）。链接密集的页面（新闻/导航站上千条链接）
     * 会一次性超出，导致整条 SQL 抛 `too many SQL variables`；而上层是 `runCatching`，
     * 结果是**静默返回“全部未访问”**——整页链接都不着色，且没有任何日志。
     * 分批后每批都很小，并集语义与单条查询完全一致。
     */
    fun areVisited(urls: Array<out String>): BooleanArray {
        if (urls.isEmpty()) return BooleanArray(0)
        val db = BrowserDb.get(ApplicationScope.context)
        val hit = HashSet<String>(urls.size)
        for (range in urlBatches(urls.size)) {
            // out 投影数组不能直接喂进 selectionArgs，逐批复制成可读写数组
            val args = Array(range.count()) { i -> urls[range.first + i] }
            val placeholders = args.joinToString(",") { "?" }
            // readableDatabase 是 SQLiteOpenHelper 的公开方法，无需改动 BrowserDb 即可只读
            db.readableDatabase.rawQuery(
                "SELECT url FROM history WHERE url IN ($placeholders)", args
            ).use { c ->
                while (c.moveToNext()) hit.add(c.getString(0))
            }
        }
        return BooleanArray(urls.size) { i -> hit.contains(urls[i]) }
    }

    /**
     * 单条 SQL 允许的绑定变量上限，留出余量（SQLite 旧版实测上限 999）。
     */
    internal const val SQL_VARIABLE_LIMIT = 900

    /**
     * 把 [total] 个待查下标切成每批不超过 [size] 的区间（纯函数，便于单测覆盖边界）。
     * total <= 0 或 size <= 0 时返回空列表。
     */
    internal fun urlBatches(total: Int, size: Int = SQL_VARIABLE_LIMIT): List<IntRange> {
        if (total <= 0 || size <= 0) return emptyList()
        return (0 until total step size).map { it until minOf(it + size, total) }
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        BrowserDb.get(ApplicationScope.context).deleteHistoryRow(id)
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        BrowserDb.get(ApplicationScope.context).clearHistory()
    }
}
