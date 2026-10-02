package io.github.tan_sno.tangsnow.data.repo

import io.github.tan_sno.tangsnow.data.Bookmark
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
