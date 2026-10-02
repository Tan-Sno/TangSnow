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
    fun `双向文本控制符被删除，不能靠它骗过可执行文件判定`() {
        // U+202E(RLO) 让文件管理器把后面的字符**视觉反转**：`setup.apk<U+202E>txt.pdf`
        // 在用户眼里显示成 `pdf.txt`；而 isExecutableName 取**最后一段**扩展名 → 判成 .pdf，
        // 于是「可执行文件一律强确认、且不给『不再询问』」被绕过。这一族必须**删除**
        // （替换成 `_` 会留下带 `_` 的伪扩展名，绕过面仍在——见 INVISIBLE_FILE_CHARS）。
        val sneaky = "setup.apk\u202Etxt.pdf"
        assertFalse("双向控制符必须被清洗掉", DownloadRepo.sanitizeFileName(sneaky).contains('\u202E'))
        assertEquals(
            "清洗后必须仍被判为非可执行（扩展名已被暴露成 .pdf）",
            false,
            DownloadRepo.isExecutableName(DownloadRepo.sanitizeFileName(sneaky)),
        )
        // 正例：同族其余控制符一并删除
        for (cp in intArrayOf(0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069)) {
            val raw = "a${cp.toChar()}b.pdf"
            assertEquals("U+%04X 未被删除".format(cp), "ab.pdf", DownloadRepo.sanitizeFileName(raw))
        }
    }

    @Test
    fun `DEL 与 C0 之外的不可打印字符也被清掉`() {
        // DEL(U+007F) 不在 \u0000-\u001F 内，但同样是不可打印控制字符（删除，见 INVISIBLE_FILE_CHARS）
        assertEquals("ab.txt", DownloadRepo.sanitizeFileName("a\u007Fb.txt"))
    }

    @Test
    fun `零宽字符族被删除——可执行文件警示不被绕过`() {
        // U+200B–200F / FEFF / ALM 不在双向控制符与 C0 清单里：`evil.apk<U+200B>` 的
        // 扩展名按字符串是 "apk<U+200B>"，isExecutableName 判不中 ⇒ 「可执行文件一律强确认」
        // 被绕过，且文件管理器里用户肉眼不可见。必须**删除**还原真实可见名（外部审查 P4）。
        for (cp in intArrayOf(0x200B, 0x200C, 0x200D, 0x200E, 0x200F, 0xFEFF, 0x061C)) {
            val cleaned = DownloadRepo.sanitizeFileName("evil.apk${cp.toChar()}")
            assertEquals("U+%04X 未被删除".format(cp), "evil.apk", cleaned)
            assertTrue(
                "U+%04X 清洗后必须判为可执行".format(cp),
                DownloadRepo.isExecutableName(cleaned),
            )
        }
    }

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
    fun `RFC5987 的 charset 大小写不敏感`() {
        // HTTP 参数只规定 MIME charset 名大小写不敏感，服务端写 Utf-8 / UTF8 变体都属合法：
        // 见 RFC 5987 的 ext-value 语法（charset 是 mime-charset）
        assertEquals(
            "报告.pdf",
            DownloadRepo.parseFileName("attachment; filename*=Utf-8''%E6%8A%A5%E5%91%8A.pdf", null),
        )
    }

    @Test
    fun `Content-Disposition 的参数名大小写不敏感`() {
        // RFC 6266 §4.1 / RFC 2616 §2.2：参数名本身大小写不敏感。真实服务端确有大写的
        assertEquals("a.pdf", DownloadRepo.parseFileName("attachment; FILENAME=\"a.pdf\"", null))
        assertEquals("a.pdf", DownloadRepo.parseFileName("attachment; Filename=a.pdf", null))
    }

    @Test
    fun `RFC5987 的参数名大小写不敏感`() {
        assertEquals(
            "报告.pdf",
            DownloadRepo.parseFileName("attachment; Filename*=utf-8''%E6%8A%A5%E5%91%8A.pdf", null),
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
        assertTrue("字节数必须受限，实际 ${utf8(name)}", utf8(name) <= MAX_BYTES)
    }

    @Test
    fun `超长文件名截断后仍保留扩展名（否则可执行警示会被绕过）`() {
        // 「可执行 / 安装类文件」警示按**最终文件名**的扩展名判定（见 isExecutableName）。
        // 若限长时把扩展名连同主名一起截掉，一个 400 字符长的 .apk 就会被当成普通文件放行
        // —— 这正是 truncateKeepingExtension 要堵的绕过面。
        val name = DownloadRepo.parseFileName("attachment; filename=\"${"x".repeat(400)}.apk\"", null)
        assertTrue("字节数必须受限，实际 ${utf8(name)}", utf8(name) <= MAX_BYTES)
        assertTrue("扩展名必须保留，实际 $name", name.endsWith(".apk"))
        assertTrue("截断后必须仍判为可执行", DownloadRepo.isExecutableName(name))

        // 无扩展名时退化为整段截断（不得抛异常、不得超长）
        val noExt = DownloadRepo.parseFileName("attachment; filename=\"${"y".repeat(400)}\"", null)
        assertTrue("字节数必须受限，实际 ${utf8(noExt)}", utf8(noExt) <= MAX_BYTES)
    }

    // ---------------------------------------------- 限长的量纲是**字节**而不是字符（P3-5）

    /**
     * 旧实现按 `String.length`（UTF-16 码元）限长 150，而文件系统单段上限是 255 **字节**：
     * 150 个汉字 = 450 字节，会**原样穿过**限长、一路走到 `createNewFile` 抛
     * `File name too long`，整单下载失败（本可截到 60 余个汉字成功）。
     */
    @Test
    fun `超长中文名按字节截断，不会带着 450 字节去落盘`() {
        val name = DownloadRepo.parseFileName("attachment; filename=\"$CJK150.pdf\"", null)
        assertTrue("字节数必须受限，实际 ${utf8(name)}", utf8(name) <= MAX_BYTES)
        assertTrue("扩展名必须保留，实际 $name", name.endsWith(".pdf"))
        assertTrue("截断后仍应是可读的中文名", name.contains('中'))
    }

    @Test
    fun `按字节截断不得劈开多字节字符`() {
        // 汉字（3 字节）与 emoji（4 字节、且是代理对）都要整块收 —— 半个字符落盘就是乱码
        val cjk = DownloadRepo.parseFileName("attachment; filename=\"$CJK150.txt\"", null)
        assertFalse("不得出现替换字符（半个汉字被解码的结果）", cjk.contains('\uFFFD'))
        assertFalse("不得留下落单代理", hasLoneSurrogate(cjk))

        val emoji = DownloadRepo.parseFileName("attachment; filename=\"$EMOJI80.apk\"", null)
        assertTrue("字节数必须受限，实际 ${utf8(emoji)}", utf8(emoji) <= MAX_BYTES)
        assertTrue("扩展名必须保留，实际 $emoji", emoji.endsWith(".apk"))
        assertTrue("截断后必须仍判为可执行", DownloadRepo.isExecutableName(emoji))
        // ⚠️ 注意：成对 emoji 的**高代理本身** isHighSurrogate() 也为 true，所以不能拿它当「半个字符」的判据
        //（本用例第一版就是这么写错的 —— 它会对自己造出来的正确结果报假红）。这里只判「代理不落单」。
        assertFalse("不得留下落单代理", hasLoneSurrogate(emoji))
        assertFalse("不得出现替换字符", emoji.contains('\uFFFD'))
        // 49 个完整 emoji（49×4=196B）+ ".apk" = 200B
        assertEquals(49, emoji.codePointCount(0, emoji.length) - ".apk".length)
    }

    @Test
    fun `短名与边界名一字不动`() {
        assertEquals("报告.pdf", DownloadRepo.parseFileName("attachment; filename=\"报告.pdf\"", null))
        // 恰好 200 字节：不得被截
        val exact = "a".repeat(196) + ".pdf"
        assertEquals(exact, DownloadRepo.parseFileName("attachment; filename=\"$exact\"", null))
    }

    private fun utf8(s: String): Int = s.toByteArray(Charsets.UTF_8).size

    /**
     * 是否含**落单**代理（半个 emoji）。
     * 注意不能写成 `none { it.isHighSurrogate() }` —— 成对 emoji 的高代理也满足它，那样会对正确结果报假红。
     */
    private fun hasLoneSurrogate(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isHighSurrogate() -> {
                    if (i + 1 >= s.length || !s[i + 1].isLowSurrogate()) return true
                    i += 2
                }
                c.isLowSurrogate() -> return true
                else -> i++
            }
        }
        return false
    }

    private companion object {
        /** 与生产侧上限一致（见 DownloadRepo.MAX_FILE_NAME_BYTES 的说明） */
        const val MAX_BYTES = 200
        val CJK150 = "中".repeat(150)
        val EMOJI80 = "😀".repeat(80)
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

    // ------------------------------------------------------------- MIME 归一

    @Test
    fun `Content-Type 的参数被剥掉`() {
        // 带参数的整串拿去 setDataAndType 会匹配不到任何 Activity ⇒ 打开/分享时谎报「没有应用能打开」
        assertEquals("text/html", DownloadRepo.bareMimeType("text/html; charset=utf-8"))
        assertEquals("text/html", DownloadRepo.bareMimeType("text/html;charset=UTF-8"))
        assertEquals("application/pdf", DownloadRepo.bareMimeType("  application/pdf  "))
        assertEquals("image/jpeg", DownloadRepo.bareMimeType("image/jpeg"))

        // 空值 / 只有参数 ⇒ 交回 null，让调用方走扩展名兜底，而不是存进一个空 MIME
        assertNull(DownloadRepo.bareMimeType(null))
        assertNull(DownloadRepo.bareMimeType(""))
        assertNull(DownloadRepo.bareMimeType("; charset=utf-8"))
    }
}
