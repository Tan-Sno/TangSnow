package io.github.tan_sno.tangsnow.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import java.io.File

/**
 * **跨语言共享夹具的读取入口**（2026-10-20 审查报告 C-14）。
 *
 * ## 为什么要它
 * `tangsnow-rs/tangsnow-core/tests/` 下的各个差分文件（`_parity.rs`）与 JVM 侧的同名测试
 * **各写一份期望值** ——
 * 那是**镜像**：改实现 + 同步改本侧的镜像期望值 ⇒ 两侧照样全绿，而两边的行为已经漂了
 * （B-1 那次「Rust 按码点、Kotlin 按 UTF-16」就是这么活下来的）。
 *
 * 现在两侧读**同一份** JSON 夹具（`tangsnow-rs/tangsnow-core/tests/fixtures/` 下的 json）⇒
 * 改夹具必然两侧一起红；只想改一侧的期望值，必须显式改夹具 —— 那一刻两侧就一起变了。
 *
 * ## 夹具格式
 * ```json
 * { "cases": [ { "name": "用例名", "input": "…", "expected": "…" } ] }
 * ```
 *  - `name`：失败时指认是哪一条（两侧共用同一串，便于对照）；
 *  - `expected` **缺省 = 输出必须与 `input` 逐字相同**（脱敏类用例常用）；
 *  - 期望为「无结果」时写 `null`；
 *  - 多个入参的用例用 `args` 数组（见各夹具的 `_说明`）。
 */
object SharedFixtures {

    /** 夹具所在目录（Rust 侧用 `include_str!` 读同一批文件） */
    const val DIR = "tangsnow-rs/tangsnow-core/tests/fixtures"

    /**
     * 读一个夹具的 `cases` 数组。
     *
     * @param fileName 夹具文件名（如 `crash_logger_cases.json`）
     */
    fun cases(fileName: String): JSONArray {
        val f = File(repoRoot(), "$DIR/$fileName")
        assertTrue("找不到共享夹具：${f.absolutePath}", f.isFile)
        val root = JSONObject(f.readText())
        val cases = root.getJSONArray("cases")
        assertTrue("夹具 $fileName 里一条用例都没有 —— 门禁会空转", cases.length() > 0)
        return cases
    }

    /**
     * 按 id 取一条用例（找不到 ⇒ **红**，绝不静默跳过）。
     *
     * Rust 侧同名物：`tangsnow-rs/tangsnow-core/tests/common/mod.rs` 的 `pick` ✔。
     */
    fun case(fileName: String, id: String): JSONObject {
        val cases = cases(fileName)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            if (c.optString("id") == id) return c
        }
        throw AssertionError("夹具 $fileName 里没有 id 为「$id」的用例 —— 改夹具时两侧要同步（这正是共享夹具的意义）")
    }

    /** 一条共享用例（输入 + 期望输出）。 */
    data class Case(val id: String, val input: String, val expected: String?)

    /**
     * 按 id 取一条**带类型**的用例。
     *
     * `expected` **缺省 = 输出必须与输入逐字相同**（脱敏类用例常用）；
     * 显式写 `null` = 「无结果」⇒ 本函数的 `expected` 返回 `null` ✔。
     */
    fun pick(fileName: String, id: String): Case {
        val c = case(fileName, id)
        val input = c.getString("input")
        val expected = when {
            !c.has("expected") -> input
            c.isNull("expected") -> null
            else -> c.getString("expected")
        }
        return Case(id, input, expected)
    }

    /** 仓库根（工作目录可能是模块目录也可能是仓库根，故向上找 `settings.gradle.kts`） */
    fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("定位不到仓库根（没找到 settings.gradle.kts）")
    }
}
