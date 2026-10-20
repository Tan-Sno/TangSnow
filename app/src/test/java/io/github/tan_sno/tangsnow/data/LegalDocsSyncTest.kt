package io.github.tan_sno.tangsnow.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **对外法律文档（`docs/PRIVACY.md` / `docs/TERMS.md`）与应用内文案的一致性**。
 *
 * ## 为什么值得一条哨兵
 * 那两份是**应用内法律文本的导出物**（`tools/export_legal_docs.py` 从
 * `app/src/main/res/values/strings.xml` 与 `values-en/strings.xml` 生成），
 * 而 GitHub 首页、商店页、README 都链到它们。
 *
 * 漂移的后果是**对外说的与应用内做的不是一回事**：应用内那份永远是对的（用户看到的是它），
 * 于是只有对外那份静默过期 —— 改了政策版本或端点清单却忘了重导出，
 * **没有任何门禁会报**（本测试之前就是这种情况：全仓检索 `export_legal_docs|PRIVACY\.md`
 * 只命中 PR 模板与 CONTRIBUTING 散文，没有一处比对）。
 *
 * ## 与 `tools/export_legal_docs.py --check` 的分工（**别当成重复**）
 *  · `--check`（CI 步骤）：**逐字节**比对，口径 = 导出脚本自己 ⇒ 连「改了 banner 排版却忘了
 *    重导出」也能抓到；
 *  · 本测试（本地 `./gradlew test`，两个 flavor 都跑）：**按内容**比对 ——
 *    只认「正文在不在 / 版本对不对 / 日期对不对」，对 banner 的排版改动不敏感，
 *    因此换排版不会把它变成红海。
 *
 * ## 边界（别高估它）
 *  · ❌ 查不了：译文是否译得对、语气是否得当 —— 只能靠人审。
 *  · ❌ 查不了：应用内**实际渲染**的文本是不是这份 `strings.xml`（那要真机；这里读的是源码）。
 *  · ⚠️ `extract` / `unescape` / `firstLineDate` 是导出脚本同名函数的**同口径复刻**：
 *    改了 `tools/export_legal_docs.py` 里的转义或取日期规则，这里要同步改。
 */
class LegalDocsSyncTest {

    /** (strings.xml 里的键, 导出物路径) */
    private val docs = listOf(
        "privacy_policy_text" to "docs/PRIVACY.md",
        "user_agreement_text" to "docs/TERMS.md",
    )

    private val zhStrings: String by lazy { read("app/src/main/res/values/strings.xml") }
    private val enStrings: String by lazy { read("app/src/main/res/values-en/strings.xml") }
    private val legalDocs: String by lazy {
        read("app/src/main/java/io/github/tan_sno/tangsnow/data/LegalDocs.kt")
    }

    @Test
    fun `导出文档包含应用内的中英文原文`() {
        for ((key, rel) in docs) {
            val doc = read(rel)
            assertTrue(
                "$rel 里没有应用内的**中文**原文（改了 strings.xml 忘了重导出？）",
                doc.contains(extract(zhStrings, key)),
            )
            assertTrue(
                "$rel 里没有应用内的**英文**原文（改了 strings.xml 忘了重导出？）",
                doc.contains(extract(enStrings, key)),
            )
        }
    }

    @Test
    fun `文档标注的政策版本与应用内一致`() {
        val version = Regex("""POLICY_VERSION\s*=\s*(\d+)""").find(legalDocs)?.groupValues?.get(1)
            ?: throw AssertionError("LegalDocs.kt 里找不到 POLICY_VERSION")
        for ((_, rel) in docs) {
            assertTrue(
                "$rel 里没有当前政策版本 POLICY_VERSION = $version（改了版本号忘了重导出？）",
                read(rel).contains("POLICY_VERSION = $version"),
            )
        }
    }

    @Test
    fun `文档标注的更新日期与文案首行一致`() {
        for ((key, rel) in docs) {
            val date = firstLineDate(extract(zhStrings, key))
            assertTrue(
                "$rel 的「最后更新」与文案首行的日期不一致（期望「$date」）",
                read(rel).contains("最后更新 / Last updated：$date"),
            )
        }
    }

    // ------------------------------------------------------------------ 取值

    /** 读文件并**归一化换行**：仓库存 LF，但 Windows 上跑导出脚本会落 CRLF（`.gitattributes` 的 eol=lf 只管仓库） */
    private fun read(name: String): String {
        val f = File(repoRoot(), name)
        assertTrue("找不到文件：$name", f.isFile)
        return f.readText().replace("\r\n", "\n")
    }

    /** 与 `tools/export_legal_docs.py` 的 `extract` 同一口径 */
    private fun extract(text: String, key: String): String {
        val m = Regex("(?s)<string name=\"${Regex.escape(key)}\">(.*?)</string>").find(text)
            ?: throw AssertionError("strings.xml 里找不到 $key")
        return unescape(m.groupValues[1]).trim()
    }

    /** 与 `tools/export_legal_docs.py` 的 `unescape` 同一口径（Android 转义先解、XML 实体后解、`&amp;` 最后） */
    private fun unescape(raw: String): String {
        var out = raw
        for ((a, b) in listOf(
            "\\n" to "\n", "\\t" to "\t", "\\'" to "'", "\\\"" to "\"", "\\@" to "@",
        )) {
            out = out.replace(a, b)
        }
        for ((a, b) in listOf(
            "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&apos;" to "'", "&#39;" to "'",
        )) {
            out = out.replace(a, b)
        }
        return out.replace("&amp;", "&")
    }

    /** 与 `tools/export_legal_docs.py` 的 `first_line_date` 同一口径 */
    private fun firstLineDate(text: String): String {
        val line = text.substringBefore('\n').trim()
        if (line.contains("更新日期")) return line.substringAfter('：').trim()
        if (line.contains("Last updated", ignoreCase = true)) return line.substringAfter(':').trim()
        return "（见正文）"
    }

    /** 工作目录可能是模块目录也可能是仓库根，故向上找 `settings.gradle.kts` */
    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("定位不到仓库根（没找到 settings.gradle.kts）")
    }
}
