package io.github.tan_sno.tangsnow.data

import io.github.tan_sno.tangsnow.data.repo.DownloadRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载文件名解析与清洗的单元测试。
 *
 * 这是「下载」链路上最容易出安全问题的一环（文件名直接落盘）：
 * 既要正确解出服务端给的名字（含 RFC 5987 的中文名），
 * 又绝不能把路径分隔符、控制字符或结尾点带进文件名。
 */
class DownloadFileNameTest {

    // ------------------------------------------------------------- 来源优先级

    @Test
    fun `Content-Disposition 优先于 URL`() {
        assertEquals(
            "report.pdf",
            DownloadRepo.parseFileName("attachment; filename=\"report.pdf\"", "https://a.com/other.zip"),
        )
    }

    @Test
    fun `Content-Disposition 无引号形式`() {
        assertEquals("a.pdf", DownloadRepo.parseFileName("attachment; filename=a.pdf", null))
    }

    @Test
    fun `RFC5987 中文文件名被正确解码`() {
        // filename*=UTF-8''%E6%8A%A5%E5%91%8A.pdf → 报告.pdf
        assertEquals(
            "报告.pdf",
            DownloadRepo.parseFileName("attachment; filename*=UTF-8''%E6%8A%A5%E5%91%8A.pdf", null),
        )
    }

    @Test
    fun `无头部时取 URL 末段`() {
        assertEquals("file.zip", DownloadRepo.parseFileName(null, "https://a.com/dir/file.zip"))
    }

    @Test
    fun `URL 末段的查询串被剥离`() {
        assertEquals(
            "report.pdf",
            DownloadRepo.parseFileName(null, "https://a.com/report.pdf?token=secret&x=1"),
        )
    }

    @Test
    fun `URL 末段里的百分号编码被解码`() {
        assertEquals("报告.pdf", DownloadRepo.parseFileName(null, "https://a.com/%E6%8A%A5%E5%91%8A.pdf"))
    }

    @Test
    fun `片段标识符被剥离`() {
        assertEquals("a.pdf", DownloadRepo.parseFileName(null, "https://a.com/a.pdf#page=2"))
    }

    @Test
    fun `完全拿不到名字时用兜底名`() {
        assertEquals("download", DownloadRepo.parseFileName(null, null))
        assertEquals("download", DownloadRepo.parseFileName(null, ""))
        assertEquals("download", DownloadRepo.parseFileName("attachment", "https://a.com"))
        assertEquals("download", DownloadRepo.parseFileName("attachment", "https://a.com/"))
    }

    // ------------------------------------------------------------- 清洗（安全边界）

    @Test
    fun `路径分隔符被替换，不能穿越目录`() {
        val name = DownloadRepo.parseFileName("attachment; filename=\"../../etc/passwd\"", null)
        assertFalse("不得含路径分隔符", name.contains('/'))
        assertFalse("不得含反斜杠", name.contains('\\'))
        // 关键判据：该名字不得再含任何目录成分（即 File 解析出的文件名就是它本身）
        assertEquals(name, java.io.File(name).name)
    }

    @Test
    fun `Windows 非法字符被替换`() {
        val name = DownloadRepo.parseFileName("attachment; filename=\"a:b*c?d\"", null)
        listOf(':', '*', '?').forEach { assertFalse("不得含 $it", name.contains(it)) }
    }

    @Test
    fun `控制字符被替换`() {
        val name = DownloadRepo.parseFileName("attachment; filename=\"a\u0001b.txt\"", null)
        assertFalse(name.contains('\u0001'))
    }

    @Test
    fun `结尾的点与空格被去掉（exFAT 等文件系统不允许）`() {
        assertEquals("a", DownloadRepo.parseFileName("attachment; filename=\"a... \"", null))
    }

    @Test
    fun `超长文件名被截断`() {
        val long = "x".repeat(500) + ".pdf"
        val name = DownloadRepo.parseFileName("attachment; filename=\"$long\"", null)
        assertTrue("长度必须受限，实际 ${name.length}", name.length <= 150)
    }

    @Test
    fun `清洗后为空则回到兜底名`() {
        assertEquals("download", DownloadRepo.parseFileName("attachment; filename=\"...\"", null))
    }

    // ------------------------------------------------------------- 解码与末段（纯函数）

    @Test
    fun `percentDecode 只解百分号不把加号当空格`() {
        assertEquals("a b", DownloadRepo.percentDecode("a%20b"))
        assertEquals("a+b", DownloadRepo.percentDecode("a+b"))
        assertEquals("中", DownloadRepo.percentDecode("%E4%B8%AD"))
        // 非法序列原样保留，不抛异常
        assertEquals("100%zz", DownloadRepo.percentDecode("100%zz"))
        assertEquals("50%", DownloadRepo.percentDecode("50%"))
    }

    @Test
    fun `lastPathSegmentOf 与 URL 结构一致`() {
        assertEquals("c.pdf", DownloadRepo.lastPathSegmentOf("https://a.com/b/c.pdf"))
        assertEquals("c.pdf", DownloadRepo.lastPathSegmentOf("https://a.com/b/c.pdf?q=1#f"))
        assertEquals("", DownloadRepo.lastPathSegmentOf("https://a.com/"))
        assertNull(DownloadRepo.lastPathSegmentOf("https://a.com"))
        assertNull(DownloadRepo.lastPathSegmentOf(""))
    }

    // ------------------------------------------------- 可执行 / 安装类判定（S3 强警示的依据）

    @Test
    fun `安装包与可执行文件被识别`() {
        // 大小写不敏感；这类文件必须无条件确认，不能被「不再询问」永久跳过
        listOf(
            "app.apk", "APP.APK", "bundle.apks", "mod.xapk", "pack.apkm",
            "setup.exe", "installer.msi", "run.bat", "run.cmd", "script.ps1",
            "tool.jar", "macro.vbs", "pkg.deb", "pkg.rpm", "dmg.dmg", "lin.appimage",
        ).forEach { assertTrue("应判为可执行: $it", DownloadRepo.isExecutableName(it)) }
    }

    @Test
    fun `普通文档与无扩展名不被误判为可执行`() {
        listOf(
            "report.pdf", "photo.jpeg", "data.csv", "note.txt", "book.epub",
            "archive.zip", "video.mp4", "page.html", "font.ttf",
            "noext", "", "trailing.", "中文名.文档",
            // 只有最后一段扩展名算数：伪装成 .pdf 的 .apk 仍然要被抓住
        ).forEach { assertFalse("不应判为可执行: $it", DownloadRepo.isExecutableName(it)) }

        // 关键正向用例：双扩展名伪装 —— 取最后一段，故仍判为可执行
        assertTrue(DownloadRepo.isExecutableName("invoice.pdf.apk"))
    }
}
