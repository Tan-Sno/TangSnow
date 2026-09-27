package io.github.tan_sno.tangsnow.data

import io.github.tan_sno.tangsnow.data.repo.DownloadRepo
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * 「清空下载记录」的漏键哨兵（N1 回归）。
 *
 * 曾经的缺陷：[DownloadRepo.clearRecords] 只 remove 了 `KEY_IDS` / `KEY_MANAGED`，
 * 漏了后加的 `KEY_STALE_PROMOTIONS` ⇒ 「清空」之后转正待重试队列仍带着旧条目复活。
 * 修法是 `ALL_RECORD_KEYS` 枚举；本测试用反射钉住「**所有** `KEY_` 静态常量都必须
 * 被它覆盖」—— 将来谁加了键却忘了登记，这条直接红，不依赖人记得复查。
 *
 * 反射可达性依据：`const val` 在 object 内编译为 `private static final` 字段，
 * `ALL_RECORD_KEYS` 是 object 实例字段；既有测试（DownloadFileNameTest）已证明
 * DownloadRepo 可在纯 JVM 测试里加载。
 */
class DownloadRecordKeysTest {

    @Test
    fun `ALL_RECORD_KEYS 覆盖 DownloadRepo 全部 KEY_ 常量`() {
        val keyFields = DownloadRepo::class.java.declaredFields
            .filter { it.name.startsWith("KEY_") && Modifier.isStatic(it.modifiers) }
        assertTrue("没找到任何 KEY_* 静态常量，测试本身已失效", keyFields.isNotEmpty())
        val cleaned = allRecordKeys()
        keyFields.forEach { f ->
            f.isAccessible = true
            val key = f.get(null) as? String
            assertTrue("记录键 $key 未被 ALL_RECORD_KEYS 覆盖", key in cleaned)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun allRecordKeys(): Set<String> {
        val instance = DownloadRepo::class.java.getField("INSTANCE").get(null)
        val f = DownloadRepo::class.java.getDeclaredField("ALL_RECORD_KEYS")
        f.isAccessible = true
        return f.get(instance) as Set<String>
    }
}
