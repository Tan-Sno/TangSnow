package io.github.tan_sno.tangsnow.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **FileProvider 白名单与代码落点的一致性单测**。
 *
 * ## 为什么需要它
 *
 * 本应用只申请极少量权限（其中**不含任何存储权限**），因此把下载文件交给外部应用只能走
 * `content://` 按次授权，而授权范围完全来自 `res/xml/file_paths.xml`。
 * 若代码把文件写到白名单**之外**的目录，`FileProvider.getUriForFile` 会抛
 * `IllegalArgumentException` —— 这一路既不崩溃、也不弹错，最终表现为
 * 「文件明明在本机，却打不开也分享不了」，只能靠人碰巧发现。
 *
 * 2026-09-26 这份白名单刚被**系统性收紧**（删掉了 `external-files` / `files` / `cache`
 * 三个整目录根，只保留实际交付落点）。收紧之后，「新增落点却忘了补白名单」就是最容易
 * 踩的一脚，故把这条不变式写成断言。
 *
 * ## 覆盖范围与边界（别高估它）
 *
 * ✅ 能查：`getExternalFilesDir(Environment.DIRECTORY_X)` 这一种写法（含跨行调用）。
 * ❌ 查不了：手工拼接路径、`getExternalFilesDir(null)`、`filesDir` / `cacheDir` 等其它
 *    根目录下的落点 —— 需要交付时再扩展本测试。
 * 遇到**无法映射的常量名直接失败**（fail-closed），不允许「看不懂就跳过」演变成漏检。
 */
class FileProviderPathConsistencyTest {

    @Test
    fun `代码里的外部私有目录落点都被 file_paths_xml 覆盖`() {
        val xml = File(repoRoot(), "app/src/main/res/xml/file_paths.xml").readText()
        val allowed = Regex("""path="([^"]*)"""")
            .findAll(xml)
            .map { it.groupValues[1].trim().trimEnd('/') }
            .toSet()
        assertTrue("file_paths.xml 里没解析出任何 path，正则可能失效了", allowed.isNotEmpty())

        // 目录名 → 出处（文件:行）
        val used = linkedMapOf<String, String>()
        val call = Regex(
            """getExternalFilesDir\(\s*(?:android\.os\.)?Environment\.DIRECTORY_([A-Z_]+)\s*\)"""
        )
        val javaDir = File(repoRoot(), "app/src/main/java")
        for (f in javaDir.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            // 按整篇匹配而非逐行：真实调用有跨行写法（参数另起一行）
            val text = f.readText()
            for (m in call.findAll(text)) {
                val where = "${f.name}:${text.take(m.range.first).count { it == '\n' } + 1}"
                used[dirNameOf(m.groupValues[1], where)] = where
            }
        }
        assertTrue("没在源码里找到任何 getExternalFilesDir 落点，正则可能失效了", used.isNotEmpty())

        for ((dir, where) in used) {
            assertTrue(
                "$where 把文件写到外部私有目录的「$dir/」，但 file_paths.xml 没有覆盖它" +
                    "（当前白名单：${allowed.sorted()}）。\n" +
                    "⇒ 该目录下的文件将无法经 FileProvider 交给外部应用打开或分享，" +
                    "且失败是静默的。请同步 app/src/main/res/xml/file_paths.xml。",
                dir in allowed,
            )
        }
    }

    /**
     * `Environment.DIRECTORY_*` 的常量名 → 实际目录名。
     *
     * 未登记的常量名**直接失败**而不是跳过：漏检比误报危险 ——
     * 它会让新落点在无人察觉的情况下失去交付能力。
     */
    private fun dirNameOf(const: String, where: String): String = when (const) {
        // 这里写死字面量，而不是引用 android.os.Environment.DIRECTORY_DOWNLOADS：
        // 普通 JVM 单测跑在 Android 的 stub android.jar 上，该字段取不到值
        // （实测为 null，直接引用会 NPE）。常量自 API 1 起即 "Download"，值稳定。
        "DOWNLOADS" -> "Download"
        else -> throw AssertionError(
            "$where 用了目录常量 Environment.DIRECTORY_$const，本测试尚未登记它。\n" +
                "请把它的实际目录名补进 dirNameOf()，并确认 file_paths.xml 已覆盖该目录。"
        )
    }

    /** 工作目录在本地与 CI 上可能不同（模块目录 / 仓库根），以 `settings.gradle.kts` 为标记向上找 */
    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("定位不到仓库根（没找到 settings.gradle.kts）")
    }
}
