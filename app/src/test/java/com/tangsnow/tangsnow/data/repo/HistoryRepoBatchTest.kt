package com.tangsnow.tangsnow.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 历史「已访问」批量查询的分批逻辑单测。
 *
 * 背景：`HistoryRepo.areVisited` 用 `WHERE url IN (?,?,…)`，绑定变量数受 SQLite
 * `SQLITE_MAX_VARIABLE_NUMBER` 限制（旧版 Android 自带库为 **999**）。链接密集页面
 * （新闻/导航站上千条链接）会一次性超出，整条 SQL 抛 `too many SQL variables`，
 * 而上层是 `runCatching` → **静默返回“全部未访问”**，整页链接都不着色。
 * 分批后必须满足：覆盖完整、不重不漏、每批不超过上限。
 */
class HistoryRepoBatchTest {

    private fun assertCovers(total: Int, batches: List<IntRange>, limit: Int) {
        // 1) 每批不超过上限（这正是分批存在的理由）
        batches.forEach { assertTrue("批大小超标：$it (limit=$limit)", it.count() <= limit) }
        // 2) 不重不漏地覆盖 [0, total)
        val flat = batches.flatMap { it.toList() }
        assertEquals("下标总数应等于待查数量", total, flat.size)
        assertEquals("下标不得重复/错序", (0 until total).toList(), flat)
    }

    @Test
    fun `空输入不产生批次`() {
        assertEquals(emptyList<IntRange>(), HistoryRepo.urlBatches(0))
    }

    @Test
    fun `负数与非法批次大小都返回空`() {
        assertEquals(emptyList<IntRange>(), HistoryRepo.urlBatches(-1))
        assertEquals(emptyList<IntRange>(), HistoryRepo.urlBatches(10, size = 0))
        assertEquals(emptyList<IntRange>(), HistoryRepo.urlBatches(10, size = -5))
    }

    @Test
    fun `不足一批时只有一批且完整覆盖`() {
        val batches = HistoryRepo.urlBatches(1)
        assertEquals(listOf(0 until 1), batches)
        assertCovers(1, batches, HistoryRepo.SQL_VARIABLE_LIMIT)

        val small = HistoryRepo.urlBatches(7)
        assertEquals(listOf(0 until 7), small)
        assertCovers(7, small, HistoryRepo.SQL_VARIABLE_LIMIT)
    }

    @Test
    fun `恰好一批的边界不多切也不漏`() {
        val limit = HistoryRepo.SQL_VARIABLE_LIMIT
        val exact = HistoryRepo.urlBatches(limit)
        assertEquals(1, exact.size)
        assertCovers(limit, exact, limit)
    }

    @Test
    fun `超出上限一项就切成两批`() {
        val limit = HistoryRepo.SQL_VARIABLE_LIMIT
        val batches = HistoryRepo.urlBatches(limit + 1)
        assertEquals(2, batches.size)
        assertEquals(limit, batches[0].count())
        assertEquals(1, batches[1].count())
        assertCovers(limit + 1, batches, limit)
    }

    @Test
    fun `超过旧版 SQLite 上限的链接数被完整切分`() {
        // 1600 条链接：旧实现会整体失败，分批后必须全覆盖（1000 是旧版 999 上限的典型越界值）
        val limit = HistoryRepo.SQL_VARIABLE_LIMIT
        for (total in listOf(999, 1000, 1600, 3601)) {
            val batches = HistoryRepo.urlBatches(total)
            assertCovers(total, batches, limit)
            // 任何一批都不得越过 SQLite 旧版 999 的硬上限
            batches.forEach { assertTrue(it.count() < 999) }
        }
    }

    @Test
    fun `批次数为向上取整`() {
        val limit = HistoryRepo.SQL_VARIABLE_LIMIT
        assertEquals(1, HistoryRepo.urlBatches(limit).size)
        assertEquals(2, HistoryRepo.urlBatches(limit + 1).size)
        assertEquals(3, HistoryRepo.urlBatches(limit * 2 + 1).size)
        assertEquals(4, HistoryRepo.urlBatches(limit * 4).size)
    }
}
