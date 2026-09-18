package com.tangsnow.tangsnow.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * 标签会话快照的本地持久化（配合 GeckoView 的 SessionState 实现进程回收后的恢复）。
 *
 * 只保存普通（非无痕）标签的 URL / 标题 / 会话状态；无痕标签绝不落盘，
 * 与「无痕不留下任何痕迹」的承诺一致。会话状态本身以 GeckoView SessionState
 * 的 JSON 字符串形式存放（[TabSnapshot.sessionState]），恢复时用
 * SessionState.fromString 反序列化并 restoreState。
 *
 * 落盘可靠性：
 *  - 所有写/删操作**串行化到单一后台线程**，消除"两个保存任务同时截断写同一文件
 *    → 落半截 JSON → 下次启动解析失败、会话静默丢失"；
 *  - 写入采用**写临时文件 + 原子重命名**，磁盘上任何时刻都不会存在半截文件；
 *  - 同一串行队列也保证 [write] 与 [clear] 的先后顺序——否则"清除"之后仍可能被
 *    在途的写任务把快照复活，与隐私承诺直接冲突。
 */
object SessionStore {

    private const val FILE_NAME = "session_store.json"
    private const val TMP_SUFFIX = ".tmp"

    /** [preload] 的预读结果与就绪标记（仅主线程读、io 线程写，故用 @Volatile） */
    @Volatile
    private var cached: Snapshot? = null

    @Volatile
    private var cachedReady = false

    /**
     * 清空会话快照后的"暂停落盘"标志（供清除浏览数据用例设置，见
     * [ClearDataUseCase]）。
     *
     * 场景：用户勾选「标签页会话快照」并清除后，标签仍在打开；若不做标记，
     * 紧接着的 onPause → [com.tangsnow.tangsnow.browser.BrowserSessionManager.saveState]
     * 会立刻把仍然打开的标签又写回快照，"清除"形同虚设。置位期间 saveState 跳过，
     * 直到产生新的浏览活动（新标签 / 新导航）才复位。
     */
    @Volatile
    private var purgePending = false

    /**
     * 标记「快照已清除」：在下一次真实浏览活动之前，[BrowserSessionManager.saveState] 跳过落盘。
     * 由清除浏览数据用例调用。
     */
    fun markPurged() {
        purgePending = true
    }

    /** 出现新的浏览活动（新标签 / 真实页面导航）：恢复正常的会话落盘，抵消 [markPurged]。 */
    fun clearPurged() {
        purgePending = false
    }

    /** [BrowserSessionManager.saveState] 前查询：true 表示本次应跳过落盘。 */
    fun shouldSkipSave(): Boolean = purgePending

    /** 单个标签的快照 */
    data class TabSnapshot(
        val url: String?,
        val title: String,
        /** GeckoSession.SessionState.toString() 的 JSON；null = 无会话状态（如空白标签） */
        val sessionState: String?,
    )

    /** 整个会话的快照 */
    data class Snapshot(
        val activeIndex: Int,
        val tabs: List<TabSnapshot>,
    )

    /**
     * 串行落盘队列（守护线程，不阻止进程退出）。
     * 不用一次性 `Thread { }`：并发写同一文件的竞态正是旧实现丢会话的成因。
     */
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tangsnow-session-store").apply { isDaemon = true }
    }

    private fun file(context: Context): File =
        File(context.applicationContext.filesDir, FILE_NAME)

    /**
     * 把快照写盘。JSON 序列化在调用线程（轻量），落盘在串行后台线程，
     * 避免阻塞主线程；写失败静默（会话持久化是尽力而为的增强，不能影响浏览）。
     */
    fun write(context: Context, snapshot: Snapshot) {
        val json = buildJson(snapshot)
        val dir = context.applicationContext.filesDir
        io.execute {
            runCatching {
                val target = File(dir, FILE_NAME)
                val tmp = File(dir, FILE_NAME + TMP_SUFFIX)
                tmp.writeText(json)
                // 原子替换：读方要么看到旧文件，要么看到完整的新文件
                if (!tmp.renameTo(target)) {
                    target.writeText(json)
                    tmp.delete()
                }
            }
            // 落盘内容已变，作废预读缓存，避免下次冷启动拿到过期快照
            invalidatePreload()
        }
    }

    /**
     * 读取上次保存的会话快照；无文件 / 解析失败返回 null。
     *
     * 冷启动路径请优先使用 [consume]（启动时已后台预读，纯内存返回）。
     * 本同步版保留给「预读尚未完成」的兜底与确有同步需要的场景。
     */
    fun read(context: Context): Snapshot? {
        val f = file(context)
        if (!f.exists()) return null
        return runCatching { parse(f.readText()) }.getOrNull()
    }

    /**
     * 冷启动预读：在**本对象的串行 IO 线程**上先把快照读进内存。
     *
     * 为什么需要它：`MainActivity.onCreate` 必须在创建标签之前拿到快照，
     * 而那里是主线程 —— 直接 `read()` 就是主线程读盘 + `JSONObject` 解析，
     * 快照越大冷启动首帧越慢，正好抵消 `ConsentActivity` 预热内核所做的优化。
     * 由 `TangSnowApplication.onCreate` 在**用户已同意**的前提下调用，
     * 到主界面 onCreate 时结果通常已就绪，`consume()` 只是一次内存读。
     */
    fun preload(context: Context) {
        val dir = context.applicationContext.filesDir
        io.execute {
            // 与 write/clear 共用同一条串行队列：不会读到写了一半的文件
            cached = runCatching {
                val f = File(dir, FILE_NAME)
                if (f.exists()) parse(f.readText()) else null
            }.getOrNull()
            cachedReady = true
        }
    }

    /**
     * 取用预读结果；返回后即失效（快照只用于冷启动这一次）。
     *
     * 「取用即失效」是刻意的：若长期留存在内存里，用户在设置里执行
     * 「清除浏览数据 → 标签页会话快照」之后，一旦 Activity 重建就可能把
     * **已清除的标签**又恢复出来 —— 那与隐私承诺直接冲突。
     * 预读尚未完成时退回同步 [read]（与改造前行为一致，不会更差）。
     */
    fun consume(context: Context): Snapshot? {
        if (cachedReady) {
            val snapshot = cached
            cached = null
            cachedReady = false
            return snapshot
        }
        return read(context)
    }

    private fun invalidatePreload() {
        cached = null
        cachedReady = false
    }

    /**
     * 清除已保存的会话快照（关闭全部标签 / 关闭「恢复上次的标签页」/ 清除浏览数据）。
     * 与 [write] 共用同一条串行队列，保证"清除"不会被在途写任务覆盖。
     */
    fun clear(context: Context) {
        val dir = context.applicationContext.filesDir
        io.execute {
            runCatching {
                File(dir, FILE_NAME).delete()
                File(dir, FILE_NAME + TMP_SUFFIX).delete()
            }
            // 同时作废预读缓存：否则「清除」之后内存里那份旧快照仍可能被 consume 取走
            invalidatePreload()
        }
    }

    private fun buildJson(snapshot: Snapshot): String {
        val arr = JSONArray()
        snapshot.tabs.forEach { tab ->
            arr.put(
                JSONObject()
                    .put("url", tab.url.orEmpty())
                    .put("title", tab.title)
                    .put("state", tab.sessionState.orEmpty())
            )
        }
        return JSONObject()
            .put("active", snapshot.activeIndex)
            .put("tabs", arr)
            .toString()
    }

    private fun parse(text: String): Snapshot? {
        val root = JSONObject(text)
        val active = root.optInt("active", 0)
        val arr = root.optJSONArray("tabs") ?: return null
        val tabs = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url", "").ifBlank { null }
            val title = o.optString("title", "")
            val state = o.optString("state", "").ifBlank { null }
            TabSnapshot(url, title, state)
        }
        return Snapshot(active, tabs)
    }
}
