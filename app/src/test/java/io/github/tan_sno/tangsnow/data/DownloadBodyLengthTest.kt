package io.github.tan_sno.tangsnow.data

import io.github.tan_sno.tangsnow.data.repo.DownloadRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载长度收尾校验的纯逻辑哨兵（外部审查 P4-4 / 待办 #31）。
 *
 * 曾经的缺陷：内核流式下载的复制循环读到 EOF 就收工、不比对长度 ⇒ 服务端传输中途干净断流时
 * `read` 返回 `-1`，**半截文件被登记成「下载成功」**（违背本仓「禁假反馈」）。
 *
 * 本测试钉住两件事：
 *  1. [DownloadRepo.expectedBodyLength] 只在**可信**时才给出期望值，且在三条必然误伤的情形
 *     （`Content-Encoding` 非 identity、`Transfer-Encoding`、头缺失/非法/负）下**必须**返回
 *     `null` —— 否则 gzip 响应（内核解码后字节数与压缩前 `Content-Length` 不等）会把正常
 *     下载判成失败；
 *  2. [DownloadRepo.bodyLengthMismatch] 的比对语义：期望为 `null` 恒不判不等（不校验）。
 */
class DownloadBodyLengthTest {

    private fun headers(vararg pairs: Pair<String, String>): List<Pair<String, String>> =
        pairs.toList()

    // ---- expectedBodyLength ----

    @Test
    fun `无 content-length 头时不做校验`() {
        assertNull(DownloadRepo.expectedBodyLength(headers("content-type" to "application/pdf")))
    }

    @Test
    fun `正常 content-length 解析为期望值`() {
        assertEquals(100L, DownloadRepo.expectedBodyLength(headers("Content-Length" to "100")))
    }

    @Test
    fun `头名大小写不敏感且容忍首尾空白`() {
        assertEquals(2048L, DownloadRepo.expectedBodyLength(headers("content-length" to "  2048 ")))
    }

    @Test
    fun `非数字 content-length 不校验`() {
        assertNull(DownloadRepo.expectedBodyLength(headers("content-length" to "abc")))
    }

    @Test
    fun `负 content-length 不校验`() {
        assertNull(DownloadRepo.expectedBodyLength(headers("content-length" to "-5")))
    }

    @Test
    fun `零 content-length 视为可信的期望 0`() {
        assertEquals(0L, DownloadRepo.expectedBodyLength(headers("content-length" to "0")))
    }

    @Test
    fun `有 transfer-encoding 时跳过校验`() {
        assertNull(
            DownloadRepo.expectedBodyLength(
                headers("content-length" to "100", "transfer-encoding" to "chunked"),
            ),
        )
    }

    @Test
    fun `有 content-encoding gzip 时跳过校验`() {
        assertNull(
            DownloadRepo.expectedBodyLength(
                headers("content-length" to "100", "content-encoding" to "gzip"),
            ),
        )
    }

    @Test
    fun `content-encoding 为 identity 时仍然校验`() {
        assertEquals(
            100L,
            DownloadRepo.expectedBodyLength(
                headers("content-length" to "100", "content-encoding" to "identity"),
            ),
        )
    }

    // ---- bodyLengthMismatch ----

    @Test
    fun `期望为空时永不判不等`() {
        assertFalse(DownloadRepo.bodyLengthMismatch(null, 999))
    }

    @Test
    fun `字节数相符不判不等`() {
        assertFalse(DownloadRepo.bodyLengthMismatch(100, 100))
    }

    @Test
    fun `字节数不足判不等（干净断流的典型）`() {
        assertTrue(DownloadRepo.bodyLengthMismatch(100, 60))
    }

    @Test
    fun `字节数超出同样判不等`() {
        assertTrue(DownloadRepo.bodyLengthMismatch(100, 120))
    }

    @Test
    fun `零长度期望与实际 0 相符`() {
        assertFalse(DownloadRepo.bodyLengthMismatch(0, 0))
    }
}