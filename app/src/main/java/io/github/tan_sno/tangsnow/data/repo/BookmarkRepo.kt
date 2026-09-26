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

    suspend fun toggle(url: String, title: String): Boolean = withContext(Dispatchers.IO) {
        val db = BrowserDb.get(ApplicationScope.context)
        if (db.isBookmarked(url)) {
            db.deleteBookmark(url)
            false
        } else {
            db.insertBookmark(url, title)
            true
        }
    }

    /**
     * 直接新增书签（导入路径用）：insertBookmark 自带「已存在只刷新标题」的 upsert 语义，
     * 与并发导入的幂等性由库层保证。与 [toggle] 的区别是不做删除分支。
     */
    suspend fun add(url: String, title: String): Unit = withContext(Dispatchers.IO) {
        BrowserDb.get(ApplicationScope.context).insertBookmark(url, title)
    }

    /** 批量新增（书签导入）：整批一个写事务，语义见 [BrowserDb.insertBookmarks] */
    suspend fun addAll(items: List<Pair<String, String>>): Unit = withContext(Dispatchers.IO) {
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
