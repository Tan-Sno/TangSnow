package io.github.tan_sno.tangsnow.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * **`app/build.gradle.kts` 版本史注释里的测试数，必须与实际 `@Test` 数一致**。
 *
 * ## 为什么要钉
 * 那句注释是那个文件里**唯一靠手维护的数字**（同文件的日志调用点数、ABI 派生表、SDK 级别都由
 * 别的测试钉住）。2026-09-30 外部审查正是靠人工数出"注释写 204、实测 205" —— 那就把它变成红灯，
 * 而不是每轮靠人记着改。
 *
 * ## 判据的边界
 * 只认 `测试 181 → **N**` 这种形式：**改措辞会让它报"找不到"而不是静默放过** —— 这是刻意的，
 * 说明符变体太多，宽松匹配反而会匹配到别的数字。
 */
class TestCountCommentTest {

    @Test
    fun `版本史注释里的测试数与实际注解数一致`() {
        val root = repoRoot()
        val actual = File(root, "app/src/test").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            // 只数**行首**的 @Test（带缩进），文档/注释里提到的不会误计
            .sumOf { file ->
                Regex("""^\s*@Test\b""", RegexOption.MULTILINE).findAll(file.readText()).count()
            }

        val build = File(root, "app/build.gradle.kts").readText()
        val found = Regex("""测试\s*181\s*→\s*\*\*(\d+)\*\*""").find(build)
        assertNotNull(
            "app/build.gradle.kts 里找不到「测试 181 → **N**」形式的计数注释（改了措辞就同步改本测试）",
            found,
        )
        assertEquals(
            "版本史注释里的测试数 ≠ app/src/test 下实际的 @Test 数 —— 加了/删了测试就同步那句注释",
            actual,
            found!!.groupValues[1].toInt(),
        )
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
