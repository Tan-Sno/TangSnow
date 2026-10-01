package io.github.tan_sno.tangsnow.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「清除完成后待提示的结果」的进程级传递（CR-004 的配套哨兵）。
 *
 * 为什么值得单独测：这条链路是**界面被重建时的唯一反馈出口** ——
 *  · 若 `takeOutcome` 不是"取即清"，用户每次进入设置页都会看到一条**过期**的「已清除」；
 *  · 若是"取后不清"，同一条结果会**重复**提示；
 *  · 若保留的不是最后一次，会提示**过时**的失败态。
 * 三种都属"看起来在工作、实际在误导用户"，且只靠读代码不容易发现 ⇒ 用断言钉住语义。
 */
class ClearDataOutcomeTest {

    @Test
    fun `取走一次后即清空（只提示一次）`() {
        ClearDataUseCase.rememberOutcome(ClearDataUseCase.Result(kernelOk = true, localFailedCount = 0))
        assertEquals(0, ClearDataUseCase.takeOutcome()?.localFailedCount)
        assertNull("取走之后必须为空，否则每次进设置页都会重复提示", ClearDataUseCase.takeOutcome())
    }

    @Test
    fun `只保留最后一次结果（清除是幂等操作）`() {
        ClearDataUseCase.rememberOutcome(ClearDataUseCase.Result(kernelOk = false, localFailedCount = 2))
        ClearDataUseCase.rememberOutcome(ClearDataUseCase.Result(kernelOk = true, localFailedCount = 0))
        val taken = ClearDataUseCase.takeOutcome()
        assertEquals("应保留最后一次（allOk）；过期结果没有提示价值", true, taken?.allOk)
        assertNull("取走后同样必须清空", ClearDataUseCase.takeOutcome())
    }
}
