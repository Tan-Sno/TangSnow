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
