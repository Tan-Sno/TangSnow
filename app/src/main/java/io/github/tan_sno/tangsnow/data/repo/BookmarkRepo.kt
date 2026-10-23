package io.github.tan_sno.tangsnow.data.repo

import io.github.tan_sno.tangsnow.data.Bookmark
import io.github.tan_sno.tangsnow.data.BookmarkSearch
import io.github.tan_sno.tangsnow.data.BrowserDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 书签仓库（协程包装，内部走 IO 线程） */
object BookmarkRepo {
    suspend fun isBookmarked(url: String): Boolean = withContext(Dispatchers.IO) {
        BrowserDb.get(ApplicationScope.context).isBookmarked(url)
    }

    /**
     * 收藏 / 取消收藏（**原子**，见 [BrowserDb.toggleBookmark]）。
     *
     * @return 操作完成后是否处于「已收藏」状态
     */
    suspend fun toggle(url: String, title: String): Boolean = withContext(Dispatchers.IO) {
        BrowserDb.get(ApplicationScope.context).toggleBookmark(url, title)
    }

    /**
     * 批量新增（书签导入路径）：整批一个写事务（见 [BrowserDb.insertBookmarks]）。
     *
     * ⚠️ **幂等性不来自这一层**：库层的冲突策略是 `CONFLICT_IGNORE`（与其它并发写入竞争时
     * 宁可跳过、不覆盖用户原有收藏），导入路径的去重发生在更上游 ——
     * [io.github.tan_sno.tangsnow.data.BookmarkHtml.sanitize] 已用库中已有 URL 集合过滤。
     * 此前这里写着「与并发导入的幂等性由库层保证」，与 [BrowserDb.insertBookmarks]
     * 的「别把这条注释读成『导入靠 upsert 幂等』」直接矛盾；两处已按后者（本条）对齐。
     * 调用方仍应自行去重 —— 依赖库层去重会误判「已有条目被刷新标题」为成功路径。
     */
    suspend fun addAll(items: List<Triple<String, String, Long?>>): Unit = withContext(Dispatchers.IO) {
        BrowserDb.get(ApplicationScope.context).insertBookmarks(items)
    }

    /**
     * 搜书签（带上限）。给地址栏联想用 —— 它只要前几条匹配，不该把整表读进内存再过滤
     * （2026-10-02 外部审查报告 6 的 P4）。
     *
     * **两条路径**（N7 保留两段式；2026-10-20 审查报告 A-3 修正非 ASCII 那条）：
     *  - **纯 ASCII 关键词** → SQL 侧 `lower() + LIKE`（[BrowserDb.searchBookmarks]）：SQLite 的
     *    lower/LIKE 只折叠 ASCII，而关键词里没有会被漏折叠的字符 ⇒ 与内存态语义一致，
     *    且保住 1813e94「不全表进内存」的收益。
     *  - **含非 ASCII 关键词**（中文等本 app 的主语言）→ SQL 侧**预筛** + 应用侧**精确过滤**
     *    （[BrowserDb.searchBookmarksPrefiltered]）。为什么不直接把关键词下推给 SQL：SQLite 无 ICU，
     *    `lower()` 对非 ASCII **不折叠**（实测 `lower('CAFÉ')='CAFÉ'`），下推会把「CAFÉ Store」
     *    从 café 的联想里整个丢掉。为什么不退回「全表读进内存再过滤」：书签表**没有总量上限**
     *    （`MAX_IMPORT_COUNT` 只是*单次*导入上限，多次导入会累积），旧路径的代价随表大小线性增长。
     *    折中：预筛在原生侧粗筛，只对候选行做应用侧精确判定（与旧内存过滤**逐字一致**），
     *    攒够上限即停。
     *
     * ⚠️ 两条路径的边界差异（**刻意保留**，别当成 bug）：纯 ASCII 路径靠 SQLite 的 `lower()`，
     * 而它不折叠 `İ`(U+0130) / `K`(U+212A) 这类「会折叠成 ASCII 的非 ASCII 字符」，
     * 故原文含它们的条目在**纯 ASCII** 关键词下可能被漏掉；含非 ASCII 的路径是应用侧精确判定，
     * 没有这个遗漏。
     */
    suspend fun search(query: String, limit: Int): List<Bookmark> = withContext(Dispatchers.IO) {
        val db = BrowserDb.get(ApplicationScope.context)
        if (BookmarkSearch.isAsciiOnly(query)) {
            db.searchBookmarks(query, limit)
        } else {
            db.searchBookmarksPrefiltered(query.lowercase(), limit)
        }
    }

    suspend fun list(): List<Bookmark> = withContext(Dispatchers.IO) {
        BrowserDb.get(ApplicationScope.context).allBookmarks()
    }

    suspend fun remove(url: String) = withContext(Dispatchers.IO) {
        BrowserDb.get(ApplicationScope.context).deleteBookmark(url)
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        BrowserDb.get(ApplicationScope.context).clearBookmarks()
    }
}
