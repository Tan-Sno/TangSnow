package io.github.tan_sno.tangsnow.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [HomeImageFile.ownCopyPath] —— 副本删除的**归属守卫**单测。
 *
 * 这个判定错了的后果不对称：
 *  - 判宽（把别人的路径当自有副本）⇒ 可能删掉不该删的文件；
 *  - 判严（自有副本被拒）⇒ 漏删 ⇒ 无主副本悄悄累积 —— 那正是这个出口存在的理由。
 *
 * 用真实临时目录把「目录内 / 目录外 / 同前缀兄弟目录 / 目录本身 / `..` 穿越 / 非 file 协议」
 * 全钉一遍。纯函数、不碰 Android 运行时，故可在普通 JVM 单测里直接跑。
 */
class HomeImageFileTest {

    private lateinit var root: File
    private lateinit var dir: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("home-image-test").toFile()
        dir = File(root, HomeImageFile.DIR_NAME).apply { mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun uriOf(f: File) = "file://${f.absolutePath}"

    @Test
    fun `目录内的副本可删`() {
        val copy = File(dir, "bg-1.jpg").apply { writeText("x") }
        val got = HomeImageFile.ownCopyPath(dir, uriOf(copy))
        assertEquals("目录内的副本必须可删，否则副本会累积", copy.canonicalFile, got?.canonicalFile)
    }

    @Test
    fun `目录外的文件一律拒绝`() {
        val sibling = File(root, "unrelated.jpg").apply { writeText("x") }
        assertNull("上级目录里的无关文件不得被删", HomeImageFile.ownCopyPath(dir, uriOf(sibling)))
    }

    @Test
    fun `同前缀的兄弟目录不被误命中`() {
        // home_bg_extra 以 home_bg 开头 —— 只比字符串前缀（不补分隔符）就会误判
        val siblingDir = File(root, "${HomeImageFile.DIR_NAME}_extra").apply { mkdirs() }
        val f = File(siblingDir, "x.jpg").apply { writeText("x") }
        assertNull("同前缀不同目录不得命中", HomeImageFile.ownCopyPath(dir, uriOf(f)))
    }

    @Test
    fun `目录本身不算副本`() {
        assertNull("要删的是目录里的文件，不是目录", HomeImageFile.ownCopyPath(dir, uriOf(dir)))
    }

    @Test
    fun `路径穿越被归一化后拒绝`() {
        val outside = File(root, "secret.jpg").apply { writeText("x") }
        val traversal = File(dir, "../secret.jpg")
        assertTrue(
            "前提：该字符串确实指向目录外（否则本用例没有意义）",
            traversal.canonicalFile == outside.canonicalFile,
        )
        assertNull("`..` 穿越必须被判出界", HomeImageFile.ownCopyPath(dir, uriOf(traversal)))
    }

    @Test
    fun `非 file 协议与空值一律拒绝`() {
        assertNull(HomeImageFile.ownCopyPath(dir, null))
        assertNull(HomeImageFile.ownCopyPath(dir, ""))
        assertNull(HomeImageFile.ownCopyPath(dir, "file://"))
        assertNull(HomeImageFile.ownCopyPath(dir, "content://media/external/images/1"))
        assertNull(HomeImageFile.ownCopyPath(dir, "https://example.com/bg.jpg"))
        // 裸路径（无 scheme）也不算 —— 入口只认 Uri.fromFile 生成的那种
        assertNull(HomeImageFile.ownCopyPath(dir, File(dir, "bg.jpg").absolutePath))
    }
}
