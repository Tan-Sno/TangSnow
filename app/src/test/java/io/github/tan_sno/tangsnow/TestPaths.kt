package io.github.tan_sno.tangsnow

import org.junit.Assert.assertTrue
import java.io.File

/**
 * **测试侧的仓库路径 helper**（单一来源）。
 *
 * ## 为什么抽出来
 *
 * 「仓库根在哪」这件事（工作目录在本地与 CI 上可能不同：可能是模块目录，也可能是仓库根
 * ⇒ 向上找 `settings.gradle.kts`）此前在 **16 个测试文件**里各抄了一份；读文件的小helper
 * （`read(path)`）也有两份拷贝。
 *
 * 代价不是"多写了 100 行"，而是**改一处漏一处**：剩下的那些会去找错目录，
 * 然后报一个与真相无关的错（"找不到 docs/PRIVACY.md" —— 实际是仓库根没找对），
 * 排查的人得先怀疑自己写的断言，再怀疑路径。
 *
 * 放在**根包**（`io.github.tan_sno.tangsnow`）而不是 `data`/`util` 某个子包里：
 * 用它的是横跨 `data` / `util` / `ui` / `browser` 四个包的测试。
 */
internal object TestPaths {

    /** 仓库根（工作目录可能是模块目录也可能是仓库根，故向上找 `settings.gradle.kts`） */
    fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("定位不到仓库根（没找到 settings.gradle.kts）")
    }

    /** 仓库里的某个文件（不存在 ⇒ 直接红，报错文案指名道姓） */
    fun file(path: String): File {
        val f = File(repoRoot(), path)
        assertTrue("找不到 $path（仓库根=${repoRoot()}）", f.isFile)
        return f
    }

    /**
     * 仓库里某个文件的全文。
     *
     * ⚠️ **换行归一为 LF**：工作区的文件可能是 CRLF（`.gitattributes` 只约束仓库里的副本，
     * 而 `core.autocrlf=true` 会让检出成 CRLF），而 `docs/` 下那些**被比对**的产物是 LF
     * ⇒ 不归一就会「明明有这段文字却找不到」（行尾差一个 `\r`）。
     */
    fun read(path: String): String = file(path).readText().replace("\r\n", "\n")
}
