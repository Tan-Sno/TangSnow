package io.github.tan_sno.tangsnow.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **主源码里不得出现两个连续的 KDoc 块**（一个块注释结束后只隔空行/注解、又跟一个 KDoc 起始标记）。
 *
 * ## 为什么值得一条哨兵
 * 两个连续 KDoc 里，前一个**不附着任何声明** ⇒ Dokka 直接丢掉它，而读代码的人以为它还在生效。
 * 它通常出现在两种时刻：① 有人把函数/字段挪走了、KDoc 留在原地；② 有人在某段 KDoc 与它的声明之间
 * 插了新注释或新成员。2026-10-01 一天内被这个坑到过多次（含**我自己两次编辑**造成的），
 * 故按本仓惯例把它变成红灯。
 *
 * ## 判定把「注解」也算作紧邻
 * 注解附着的是**声明**，所以「KDoc → `@Foo` → KDoc」与「KDoc → KDoc」是同一种病：
 * 前一个 KDoc 依然落单。2026-10-02 实测踩到过 —— 一条 `@Suppress` 被夹在两段 KDoc 之间，
 * 正好把 `saveFromStream` 的整段文档顶开（而本测试当时判它合法）。
 * 多行注解（括号跨行）按括号配平整段跳过，避免留下新的盲区。
 *
 * ## 规则刻意收窄
 * **不**检查「KDoc 之后跟行注释」这类形状 —— 本仓**有意**如此（例如 `MainActivity` 的类 KDoc 之后
 * 用行注释补权限说明，正是为了避免形成两个连续 KDoc）。哨兵宁可窄，也不天天喊狼来了。
 *
 * ## 写本文件时踩到的坑（留作同类问题的样本）
 * 这段说明**不能**直接写出那个起始标记的字面量：Kotlin 的块注释**可以嵌套**，一旦在说明里写出
 * 它，就等于开了一个新注释 ⇒ 直到文件末尾都"Unclosed comment"（本文件第一版正是这样编译不过）。
 * 所以下文一律用「KDoc 起始标记」这样的说法代替字面量。
 */
class KdocAdjacencyTest {

    @Test
    fun `主源码里不得出现两个连续的 KDoc 块`() {
        val root = repoRoot()
        val srcRoot = File(root, "app/src/main/java")
        assertTrue("找不到主源码目录：${srcRoot.absolutePath}", srcRoot.isDirectory)

        val violations = mutableListOf<String>()
        srcRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val lines = f.readLines()
            var i = 0
            while (i < lines.size) {
                if (!lines[i].trimStart().startsWith("/**")) {
                    i++
                    continue
                }
                // 跳到该块注释结束（KDoc 内不会出现嵌套块注释 —— 那本身就是被禁的写法）
                var end = i
                while (end < lines.size && !lines[end].contains("*/")) end++
                var next = end + 1
                while (next < lines.size && lines[next].isBlank()) next++
                // 注解附着声明 ⇒ 与 KDoc 一样「占住」紧随其后的那个位置，不能被它隔断判定
                while (next < lines.size && lines[next].trimStart().startsWith("@")) {
                    var depth = parenBalance(lines[next])
                    next++
                    while (depth > 0 && next < lines.size) {
                        depth += parenBalance(lines[next])
                        next++
                    }
                }
                if (next < lines.size && lines[next].trimStart().startsWith("/**")) {
                    violations += "${f.relativeTo(root).path}:${i + 1}" +
                        "（下一个 KDoc 在 :${next + 1}）"
                }
                i = end + 1
            }
        }

        assertTrue(
            "出现两个连续 KDoc：前一个不附着声明、Dokka 会静默丢弃它。" +
                "把该 KDoc 移到它真正说明的声明上方，或改成行注释：\n  " +
                violations.joinToString("\n  "),
            violations.isEmpty(),
        )
    }

    /** 一行里 `(` 与 `)` 的个数差（注解参数可能跨行，靠它配平） */
    private fun parenBalance(line: String): Int =
        line.count { it == '(' } - line.count { it == ')' }

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
