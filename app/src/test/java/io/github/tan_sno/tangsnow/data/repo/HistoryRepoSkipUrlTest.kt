package io.github.tan_sno.tangsnow.data.repo

import io.github.tan_sno.tangsnow.data.SharedFixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 历史入库前缀过滤的行为用例：内部页 / 临时文档 / 脚本协议 / 扩展页**不进历史**。
 *
 * ## 为什么需要它
 * 这段判定原先内联在 [HistoryRepo.add] 里，而 [add] 要写库 ⇒ JVM 单测碰不到 ⇒
 * Rust 侧改前缀过滤时，Kotlin 这边**没有任何行为用例**能发现漂移。
 * 现在 [HistoryRepo.shouldSkipHistoryUrl] 是纯函数，两侧读**同一份夹具**
 * （`tangsnow-rs/tangsnow-core/tests/fixtures/history_skip_prefix_cases.json`）
 * ⇒ 改一边必然另一边一起红。
 *
 * ## 为什么是「共享夹具」而不是两侧各写一份
 * 各写一份就是**镜像**：改实现 + 同步改本侧的镜像期望值 ⇒ 两侧照样全绿，而两边的口径已经漂了
 * （2026-10-20 审查报告 C-14；B-1 那次「Rust 按码点、Kotlin 按 UTF-16」就是这么活下来的）。
 *
 * 前缀清单本身（`SKIP_PREFIXES`）两侧各存一份，由 `CrossLanguageLiteralParityTest`
 * 并排比对两侧源码负责；Rust 侧另有一条用例把「清单」与本夹具互相钉住。
 */
class HistoryRepoSkipUrlTest {

    /** 夹具里的每条用例与 Rust 侧同口径（skip=true = 不进历史）。 */
    @Test
    fun `夹具里的每条用例与Rust侧同口径`() {
        val cases = SharedFixtures.cases(FIXTURE)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val id = c.getString("id")
            val url = c.getJSONArray("args").getString(0)
            assertEquals(
                "夹具用例「$id」",
                c.getBoolean("expected"),
                HistoryRepo.shouldSkipHistoryUrl(url),
            )
        }
    }

    private companion object {
        /** 两侧共享的夹具（Rust 侧读同一个文件） */
        const val FIXTURE = "history_skip_prefix_cases.json"
    }
}
