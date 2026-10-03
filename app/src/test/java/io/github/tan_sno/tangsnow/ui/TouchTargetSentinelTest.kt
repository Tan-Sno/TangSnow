package io.github.tan_sno.tangsnow.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * **触摸目标门禁：任何"可点击且有一边 < 48dp"的控件都必须显式豁免，否则测试变红。**
 *
 * ## 为什么需要它
 *
 * 48dp 是 Android 无障碍的最小触摸目标。低于它，手指命中面积不足 —— 本机实测最小的一处只有
 * 32×32dp，而且恰好是「删除标签」这种高频**破坏性**操作。这类缺陷**不崩、不打日志**，
 * 只能靠人逐屏点出来，而人只会点自己注意到的地方。
 *
 * 曾经的教训：真机 `uiautomator dump` 抓到 46 条疑似项，其中底栏那 6 条与布局里写死的 78dp
 * **互相矛盾**，查了两小时才发现是 dump 侧的问题；同一份数据还整屏漏掉了没走过的界面
 * （扩展已安装项的「管理 / 卸载」、资料库的「删除」、主页图的两个按钮）。
 * ⇒ 结论：**验收靠真机（抽样），门禁靠静态（全量）**。
 *
 * ## 判定为什么能"静态算"
 *
 * 1. 可点击集合来自**代码**：五种绑定写法 + 布局里的声明式 `clickable` / `onClick`；
 * 2. 尺寸来自**布局 XML**，沿父容器链解析 `match_parent`，把 `minWidth/minHeight` 当可靠下界；
 * 3. **只有两侧都静态可确定**且最小边 < 48dp 才算违规 —— `0dp`(权重) / `wrap_content`
 *    这类算不出的**不判违规**（宁可漏判不误判；误判会污染施工面）。
 *
 * ## 为什么是"豁免表"而不是"直接断言 0"
 *
 * 直接断言 0 会让门禁一落地就把仓库打红，那种门禁活不过一天。因此：**每条已知的小目标都必须
 * 写进 `touch_target_min.xml` 并附理由**，改完一处删一条 —— 门禁只负责"不许新增"，
 * 存量靠豁免表当**棘轮**推进。表由 `notes-tangsnow/tools/static-touch-target-audit.py`
 * 生成与校对（`--check` 能发现"布局改了但表没更新"）。
 */
class TouchTargetSentinelTest {

    private data class Hit(val layout: String, val node: String, val w: Double, val h: Double) {
        val minSide: Double get() = minOf(w, h)
        val key: String get() = "$layout|$node"
        fun describe(): String = "${fmt(w)}×${fmt(h)}dp"
    }

    private data class Exempt(val kind: String, val reason: String)

    private companion object {
        const val MIN_DP = 48.0
        const val ROOT_KEY = "(item 布局根)"
        const val EXEMPT_FILE = "touch_target_min.xml"

        val CLICK_NAMED = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*\.\s*set(?:On|Long)ClickListener""")
        val CLICK_FIND_ID =
            Regex("""findViewById<[^>]*>\(\s*R\.id\.(\w+)\s*\)\s*\??\s*\.?\s*set(?:On|Long)ClickListener""")
        val CLICK_BARE = Regex("""(?<![.\w])set(?:On|Long)ClickListener""")
        val R_ID = Regex("""R\.id\.(\w+)""")
        val ROOT_CLICK = Regex(
            """(?:holder\.binding|binding|itemView)\.root\s*\.\s*set(?:On|Long)ClickListener"""
        )
        val INFLATE_ADAPTER =
            Regex("""([A-Za-z0-9_]+)Binding\.inflate\(.*?,\s*parent\s*,""", RegexOption.DOT_MATCHES_ALL)
        val CAMEL = Regex("""(?<!^)(?=[A-Z])""")

        fun fmt(v: Double): String =
            if (v == Math.rint(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
    }

    // ---------------------------------------------------------------- 定位

    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("定位不到仓库根（没找到 settings.gradle.kts）")
    }

    private fun layoutDir(): File = File(File(repoRoot(), "app"), "src/main/res/layout")
    private fun sourceDir(): File = File(File(repoRoot(), "app"), "src/main/java")

    // ---------------------------------------------------------------- 豁免表

    private fun exemptions(): Map<String, Exempt> {
        val stream = javaClass.classLoader?.getResourceAsStream(EXEMPT_FILE)
            ?: throw AssertionError("找不到 $EXEMPT_FILE（应在 app/src/test/resources/ 下）")
        val doc = stream.use {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
        }
        val nodes = doc.getElementsByTagName("target")
        val out = LinkedHashMap<String, Exempt>(nodes.length)
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            val key = "${el.getAttribute("layout")}|${el.getAttribute("key")}"
            val prev = out.put(key, Exempt(el.getAttribute("kind"), el.getAttribute("exempt")))
            check(prev == null) { "豁免表里 $key 重复" }
        }
        return out
    }

    // ---------------------------------------------------------------- 主断言

    @Test
    fun `任何可点击控件的触摸目标都不得小于 48dp 除非显式豁免`() {
        val violations = scan()
        val exempt = exemptions()
        val unguarded = violations.filter { exempt[it.key]?.reason?.isNotBlank() != true }
        assertTrue(
            "发现 ${unguarded.size} 处未豁免的触摸目标 < ${MIN_DP.toInt()}dp：\n" +
                unguarded.joinToString("\n") { "  · ${it.key} = ${it.describe()}" } +
                "\n修法二选一：① 把热区扩到 ${MIN_DP.toInt()}dp（父容器有富余空间时优先 TouchDelegate，" +
                "视觉零变化）；② 确实改不动就写进 $EXEMPT_FILE 的 exempt 属性并说明理由。",
            unguarded.isEmpty(),
        )
    }

    @Test
    fun `豁免表不得留已修好的条目`() {
        val exempt = exemptions()
        assertTrue("豁免表读不到条目", exempt.isNotEmpty())
        val stillBad = scan().map { it.key }.toSet()
        val stale = exempt.filter { (k, v) -> v.kind == "violation" && k !in stillBad }.keys
        assertTrue(
            "豁免表里有 ${stale.size} 条已不是违规（对应布局改好了，请删条目）：${stale.joinToString()}",
            stale.isEmpty(),
        )
    }

    @Test
    fun `豁免表每条违规都必须写理由`() {
        val noReason = exemptions().filter { (k, v) ->
            v.kind == "violation" && v.reason.isBlank()
        }.keys
        assertTrue("豁免表里有 ${noReason.size} 条违规没写理由：${noReason.joinToString()}", noReason.isEmpty())
    }

    // ---------------------------------------------------------------- 扫描

    private fun scan(): List<Hit> {
        val clickable = collectClickableIds()
        val adapterRoots = adapterItemLayouts()
        val hits = ArrayList<Hit>()
        val files = layoutDir().listFiles { f: File -> f.name.endsWith(".xml") } ?: emptyArray()
        for (f in files.sortedBy { it.name }) {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
            walk(doc.documentElement, null, depth = 0) { el, parent, depth ->
                val id = el.getAttribute("android:id").ifBlank { null }?.substringAfterLast('/')
                val declared = el.getAttribute("android:clickable") == "true" ||
                    el.getAttribute("android:onClick").isNotBlank()
                // 适配器把点击绑在 holder.binding.root 上 ⇒ **只有布局根**因此可点，
                // 不是整棵子树（否则子控件会被误报）。
                val isRoot = depth == 0 && f.name in adapterRoots
                if (id == null && !declared && !isRoot) return@walk
                if (id != null && id !in clickable && !declared && !isRoot) return@walk
                val w = resolveWidth(el, parent) ?: return@walk
                val h = resolveHeight(el, parent) ?: return@walk
                if (minOf(w, h) < MIN_DP) {
                    hits += Hit(f.name, id ?: ROOT_KEY, w, h)
                }
            }
        }
        return hits
    }

    private fun walk(el: Element, parent: Element?, depth: Int, onEach: (Element, Element?, Int) -> Unit) {
        onEach(el, parent, depth)
        val kids = el.childNodes
        for (i in 0 until kids.length) {
            val c = kids.item(i)
            if (c is Element) walk(c, el, depth + 1, onEach)
        }
    }

    /**
     * 解析宽度；null = 静态不可确定。
     * `0dp` 是权重（分给父容器）而不是 0dp 宽，因此不可确定；`minWidth` 是可靠下界。
     */
    private fun resolveWidth(el: Element, parent: Element?): Double? {
        fixed(el.getAttribute("android:layout_width"))?.let { return it }
        if (el.getAttribute("android:layout_width").startsWith("match_parent") && parent != null) {
            resolveWidth(parent, null)?.let { return it }
        }
        return minBound(el.getAttribute("android:minWidth"))
    }

    private fun resolveHeight(el: Element, parent: Element?): Double? {
        fixed(el.getAttribute("android:layout_height"))?.let { return it }
        if (el.getAttribute("android:layout_height").startsWith("match_parent") && parent != null) {
            resolveHeight(parent, null)?.let { return it }
        }
        return minBound(el.getAttribute("android:minHeight"))
    }

    /** 固定 dp 尺寸；`0dp` / wrap / match / 其它 → null（不可确定）。 */
    private fun fixed(raw: String): Double? {
        val v = raw.trim()
        if (!v.endsWith("dp")) return null
        if (v == "0dp") return null
        return v.dropLast(2).toDoubleOrNull()
    }

    private fun minBound(raw: String): Double? {
        val v = raw.trim()
        if (!v.endsWith("dp")) return null
        return v.dropLast(2).toDoubleOrNull()
    }

    // ---------------------------------------------------------------- 可点击集合

    private fun collectClickableIds(): Set<String> {
        val ids = HashSet<String>()
        sourceDir().walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .sortedBy { it.path }
            .forEach { f ->
                val text = f.readText()
                val lines = text.lines()
                val hasDialogBtn = text.contains("getButton(")
                lines.forEachIndexed { i, line ->
                    if (line.contains("getButton(")) return@forEachIndexed
                    CLICK_NAMED.findAll(line).forEach { m ->
                        val recv = m.groupValues[1]
                        if (recv == "root" || recv == "itemView") return@forEachIndexed
                        if (hasDialogBtn && recv == "btn") return@forEachIndexed
                        ids += recv
                    }
                    CLICK_FIND_ID.findAll(line).forEach { ids += it.groupValues[1] }
                    if (CLICK_BARE.containsMatchIn(line)) {
                        for (back in 0..3) {
                            val j = i - back
                            if (j < 0) break
                            val m = R_ID.find(lines[j])
                            if (m != null) {
                                ids += m.groupValues[1]
                                break
                            }
                        }
                    }
                }
            }
        return ids
    }

    /** 适配器里绑在 `holder.binding.root` 上的 ⇒ 整个 item 布局根可点。 */
    private fun adapterItemLayouts(): Set<String> {
        val out = HashSet<String>()
        sourceDir().walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .forEach { f ->
                val text = f.readText()
                if (!ROOT_CLICK.containsMatchIn(text)) return@forEach
                INFLATE_ADAPTER.findAll(text).forEach { m ->
                    out += CAMEL.replace(m.groupValues[1], "_").lowercase() + ".xml"
                }
            }
        return out
    }
}
