package io.github.tan_sno.tangsnow.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [CrashLogger.displayStamp] —— 崩溃日志文件名 → 展示时间戳。
 *
 * 背景：文件名自 2212d96 起精确到毫秒（`crash-yyyyMMdd-HHmmss-SSS.txt`），展示解析曾滞后
 * 失配（新日志显示成裸 `20261001-121500-123`）。写名与读名同源由本测试钉住两代格式。
 */
class CrashLoggerDisplayTest {

    @Test
    fun `旧格式（无毫秒）被格式化`() {
        assertEquals("2026-09-08 10:15:30", CrashLogger.displayStamp("crash-20260908-101530.txt"))
    }

    @Test
    fun `新格式（含毫秒）被格式化且毫秒丢弃`() {
        assertEquals("2026-10-01 12:15:00", CrashLogger.displayStamp("crash-20261001-121500-123.txt"))
    }

    @Test
    fun `无法解析的名单原样呈现（只去前缀后缀）`() {
        assertEquals("weird-name", CrashLogger.displayStamp("crash-weird-name.txt"))
    }
}
