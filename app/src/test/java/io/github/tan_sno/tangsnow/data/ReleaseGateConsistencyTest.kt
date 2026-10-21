package io.github.tan_sno.tangsnow.data

import io.github.tan_sno.tangsnow.TestPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **发布链路口径的一致性单测**（构建脚本 ↔ 门禁脚本 ↔ 实际工具文件）。
 *
 * ## 为什么需要它
 * 第 ⑩ 项门禁（2026-10-15）和 `core` flavor 维度把三样东西**抄成了两份**：
 * ABI 集合与它的 versionCode 序号同时存在于 `app/build.gradle.kts` 的 `abiCodes` 与
 * `tools/verify_release.py` 的 `ABI_ORDER`；flavor 名同时存在于 `productFlavors` 与
 * 门禁的 `DEFAULT_FLAVOR`；而 `verify_release.py` 要调用的 `tools/check_zip_padding.py`
 * 是个独立文件。原来那处代码只能留一句「请同步本表与 tools/verify_release.py」——
 * **靠人记着的同步就是没同步**，本仓已经吃过两次这类亏（`ProguardLogRuleConsistencyTest`
 * 与 `TestCountCommentTest` 都是为此而生的）。
 *
 * ## 口径
 * 一律 fail-closed：解析不出期望形状就**红**，不允许「看不懂就跳过」演变成漏检。
 * ❌ 查不了：两处的**语义**是否都对（例如序号 1/2/3 是否与真机 ABI 优先级一致）——
 * 那要产物级验证；本测试只保证「改了一处忘了改另一处」当场可见。
 */
class ReleaseGateConsistencyTest {

    private val buildText: String by lazy { File(TestPaths.repoRoot(), "app/build.gradle.kts").readText() }
    private val gateText: String by lazy { File(TestPaths.repoRoot(), "tools/verify_release.py").readText() }

    /**
     * 「这个用例真的会判定」的粗判标记（Rust 侧）。
     *
     * `prop_assert!` / `prop_assert_eq!` 是 proptest 宏，**同样是断言**（属性测试用）——
     * 漏掉它们会把 `properties_fuzz.rs` 整份误判成零断言。
     */
    private val ASSERT_MARKERS = listOf(
        "assert!", "assert_eq!", "assert_ne!", "panic!",
        "prop_assert!", "prop_assert_eq!", "prop_assert_ne!",
    )

    @Test
    fun `ABI 集合与 versionCode 序号在构建脚本与门禁脚本里完全一致`() {
        // 构建侧：abiCodes = mapOf("arm64-v8a" to 1, "armeabi-v7a" to 2, "x86_64" to 3)
        val gradleAbi = Regex(
            """abiCodes\s*=\s*mapOf\((.*?)\)""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(buildText)?.groupValues?.get(1)
            ?: error("app/build.gradle.kts 里找不到 abiCodes = mapOf(...) 这张表（改了写法就同步改本测试）")
        val gradlePairs = pairsOf(gradleAbi)
        // 门禁侧：ABI_ORDER = {"arm64-v8a": 1, "armeabi-v7a": 2, "x86_64": 3}
        val pyBlock = Regex("""ABI_ORDER\s*=\s*\{([^}]*)\}""").find(
            gateText
        )?.groupValues?.get(1)
            ?: error("tools/verify_release.py 里找不到 ABI_ORDER = {...}（改了写法就同步改本测试）")
        val pyPairs = pairsOf(pyBlock)

        assertEquals(
            "ABI → versionCode 序号这两张表已经飘了（顺序不算差异，成员或序号差了才是）：" +
                "构建侧 $gradlePairs，门禁侧 $pyPairs",
            gradlePairs.toSet(),
            pyPairs.toSet(),
        )
        // splits 里 include 的 ABI 必须是同一集合（否则某个 flavor 打出来的包不在校验链里）
        val included = Regex("""include\(([^)]*)\)""").find(
            buildText
        )?.groupValues?.get(1)?.split(",")?.map { it.trim().trim('"') }?.filter { it.isNotEmpty() }
            ?: error("app/build.gradle.kts 里找不到 splits.abi.include(...)")
        assertEquals(
            "splits.include 与 abiCodes 的 ABI 集合不一致（新 ABI 忘了登记偏移，或登记了不打的 ABI）",
            gradlePairs.map { it.first }.toSet(),
            included.toSet(),
        )
    }

    @Test
    fun `flavor 名与门禁的产物文件名口径一致`() {
        val flavors = Regex("""create\("([^"]+)"\)""").findAll(buildText)
            .map { it.groupValues[1] }.toSet()
        assertTrue("app/build.gradle.kts 里没解析出任何 productFlavor", flavors.isNotEmpty())
        assertTrue("构建脚本里应有 coreKotlin flavor（默认发布通道）", "coreKotlin" in flavors)

        val defaultFlavor = Regex("""DEFAULT_FLAVOR\s*=\s*"([^"]+)"""").find(
            gateText
        )?.groupValues?.get(1)
            ?: error("tools/verify_release.py 里找不到 DEFAULT_FLAVOR = \"...\"")
        assertTrue(
            "门禁默认校验的 flavor「$defaultFlavor」不在构建脚本声明的 flavor 集合 $flavors 里 —— " +
                "改名只改一边就会让发布目录永远对不上",
            defaultFlavor in flavors,
        )
        // 文件名形状：app-<flavor>-<abi>-release.apk（与 AGP 的 flavor+ABI 产物命名同构）
        assertTrue(
            "门禁的产物名形状必须是 app-<flavor>-<abi>-release.apk",
            "app-%s-%s-release.apk" in gateText,
        )
    }

    @Test
    fun `第 ⑩ 项依赖的 padding 检查脚本存在且真被门禁调用`() {
        val script = File(TestPaths.repoRoot(), "tools/check_zip_padding.py")
        assertTrue(
            "tools/check_zip_padding.py 不见了，但 verify_release.py 第 ⑩ 项依赖它（缺文件必须红，" +
                "不能让 -113 那道门静默消失）",
            script.isFile,
        )
        assertTrue(
            "verify_release.py 不再调用 check_zip_padding.py —— 第 ⑩ 项已形同虚设",
            "check_zip_padding.py" in gateText,
        )
        // 反向也要成立：脚本自己声明的阈值不能悄悄改动（8192 是 4K/16K padding 的分界）
        val limit = Regex("""PADDING_LIMIT\s*=\s*(\d+)""").find(script.readText())
            ?.groupValues?.get(1)?.toInt()
            ?: error("tools/check_zip_padding.py 里找不到 PADDING_LIMIT = <整数>")
        assertEquals(
            "padding 阈值被改动：4K 对齐 padding ≤4095、官方包实测 1878、16K padding 约 14000，" +
                "8192 这个分界改动前请连同本注释与 STATUS 一起改",
            8192,
            limit,
        )
    }

    @Test
    fun `包形态修复项仍在位且带着它的说明`() {
        val m = Regex("""useLegacyPackaging\s*=\s*true""").find(buildText)
        if (m == null) {
            // 少了修复项本身：给出可执行的结论，而不是让人去猜是哪一轮掉的
            error(
                "app/build.gradle.kts 里没有 useLegacyPackaging = true —— AGP 会把 16K 对齐 padding 写进 " +
                    ".so 的 local header extra，安装器 libziparchive 拒收（res=-113，「解析包出现问题」），" +
                    "而 ①–⑨ 全绿看不出来。移除它必须连着改 tools/verify_release.py 第 ⑩ 项与 STATUS 血泪条目。"
            )
        }
        assertTrue(
            "useLegacyPackaging 旁边那段「删前必读」说明不见了 —— 这行是被删过又加回来时最容易丢的上下文",
            buildText.contains("删前必读"),
        )
    }

    /**
     * 2026-10-15 整理：`libtangsnow_core.so` 在仓库里**只许有一份**（flavor 那份）。
     * 此前 `tangsnow-rs/dist/jniLibs/` 与 `app/src/coreRust/jniLibs/` 各存一份，实测三对文件
     * SHA-256 两两相同 —— 两份跟踪副本就是"重编了拷一边、忘另一边"的温床，而构建只读 flavor。
     */
    @Test
    fun `随包 so 只有一份真相`() {
        val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        val flavor = abis.map { File(TestPaths.repoRoot(), "app/src/coreRust/jniLibs/$it/libtangsnow_core.so") }
        val missing = flavor.filter { !it.isFile || it.length() == 0L }
        assertTrue(
            "coreRust flavor 的 `.so` 缺失或为空 ⇒ 这个 flavor 打出来的包里没有 Rust 内核" +
                "（AGP 按约定目录读 app/src/coreRust/jniLibs/<abi>/，**不**读 tangsnow-rs/dist/）：\n" +
                missing.joinToString("\n") { "  ${it.path}" },
            missing.isEmpty(),
        )

        val ignore = File(TestPaths.repoRoot(), "tangsnow-rs/.gitignore").readText()
        assertTrue(
            "tangsnow-rs/.gitignore 里缺 `/dist/jniLibs/` 这条 —— 那样 `tools/build-android.sh` 的产物" +
                "会再次被提交，仓里就又有了第二份 `.so`（本测试与 gitignore 是同一件事的两道闸）",
            "/dist/jniLibs/" in ignore,
        )

        // 落点目录里若还有文件（本机重编过），必须与 flavor 那份逐字节相同：
        // 不一致只有两种正解 —— 把新的拷进 flavor，或把落点清掉。两种都得人做一次决定，别让它并存。
        val drift = abis.mapNotNull { abi ->
            val out = File(TestPaths.repoRoot(), "tangsnow-rs/dist/jniLibs/$abi/libtangsnow_core.so")
            val ship = File(TestPaths.repoRoot(), "app/src/coreRust/jniLibs/$abi/libtangsnow_core.so")
            when {
                !out.isFile -> null
                sha256(out) == sha256(ship) -> null
                else -> "  $abi：落点=${sha256(out).take(16)}… flavor=${sha256(ship).take(16)}…"
            }
        }
        assertTrue(
            "dist/jniLibs 里的产物与 flavor 那份不一致 —— 随包出去的只有 flavor 那一份，" +
                "要么把新产物拷过去、要么把落点删掉：\n" + drift.joinToString("\n"),
            drift.isEmpty(),
        )
    }

    /** flavor 之前的预览通道残件：**不得回到仓库**（已移到仓外归档，理由逐条写在消息里）。 */
    @Test
    fun `flavor 之前的预览通道残件不得回到仓库`() {
        val retired = listOf(
            "tangsnow-rs/rust-core-integration.patch" to
                "它改 app/src/main 的调用点，而 CoreNative 只存在于 coreRust 源集 ⇒ 打上去 coreKotlin " +
                "当场编译不过；实测它仍能 `git apply` 干净应用，所以留在公开仓里就是一个能点的陷阱",
            "tangsnow-rs/bridge/gradle-integration.gradle.kts" to
                "它把 sourceSets[main].jniLibs 指向 tangsnow-rs/bridge/jniLibs ⇒ 第二份 jniLibs 来源，" +
                "flavor 的约定目录已经取代它",
            "tangsnow-rs/bridge/CoreNative.kt" to
                "那份 101 行设计稿与在用的防护网桥不是一份东西，照它拷会把正式实现覆盖掉（外部审查 L4）",
        ).map { (path, why) -> File(TestPaths.repoRoot(), path) to why }.filter { (f, _) -> f.exists() }
        assertTrue(
            "这些 pre-flavor 残件又出现在仓库里了。要查它们改过什么，去 " +
                "git 历史，不要放回构建可见的路径：\n" +
                retired.joinToString("\n") { (f, why) -> "  ${f.path} —— $why" },
            retired.isEmpty(),
        )
    }

    /**
     * **`tangsnow-rs/tools/build-android.sh` 里已修好的三处口径不得再漂回去**（2026-10-16）。
     *
     * 为什么这条只能钉源码：这台机器上没有 Rust 的 android target，三颗随包 `.so` 是在**另一台
     * Linux** 上交叉编译出来的（STATUS 记着这条环境限制）⇒ 脚本坏了本地一声不响，只有下次重编
     * 或装机才看得见。它与 `第 ⑩ 项依赖的 padding 检查脚本存在且真被门禁调用` 属于同一类：
     * 构建链路里的**工具文件**一旦被改，门禁与 flavor 的产物就跟着错，而 gradle 看不见。
     *
     * 钉的三处（都是 2026-10-15 外部审查 L1 与同日实测修掉的）：
     *  ① NDK 的 llvm prebuilt 目录按 `uname` 探测、`NDK_HOST_BIN` 可手工覆盖，且**零候选与多候选
     *     都 exit 1** —— 原先写死 `linux-x86_64`，换宿主时症状是"找不到链接器"，指不回这一行；
     *     静默猜一个则可能拿错工具链产出装不上的包。
     *  ② `RUSTFLAGS` 里的 `-Wl,-z,max-page-size=16384`：少了它，产出的 `.so` 在 16KB 页内核的
     *     设备上被 PackageManager 在解析阶段拒收（症状与第 ⑩ 项那个 res=-113 同族），
     *     而 ①–⑨ 全绿看不出来。
     *  ③ 三行 `cp` 跟着 `$PROFILEDIR` 走：曾经写死 `release/`，于是文档里的 `--debug`
     *     用法是"编得出、拷不出"。
     */
    @Test
    fun `重编 so 的脚本仍带着宿主探测 16K 对齐与 profile 拷贝这三处修复`() {
        val script = File(TestPaths.repoRoot(), "tangsnow-rs/tools/build-android.sh")
        assertTrue(
            "tangsnow-rs/tools/build-android.sh 不见了 ⇒ 仓库里再也没有能重建随包 `.so` 的入口" +
                "（三 ABI 的链接器与 16K 对齐参数只写在这一个脚本里）",
            script.isFile,
        )
        val sh = script.readText()

        assertTrue(
            "脚本不再支持 NDK_HOST_BIN 手工覆盖 —— 非 Linux 宿主就没有出路了（L1 留的口子）",
            "\${NDK_HOST_BIN:-}" in sh,
        )
        assertTrue(
            "脚本不再按 uname 探测宿主目录：写死 linux-x86_64 时，darwin/windows 上的报错落在 cargo 里，" +
                "指不回这一行（L1）",
            "case \"\$(uname -s)\" in" in sh,
        )
        for (prefix in listOf("linux-", "darwin-", "windows-")) {
            assertTrue("宿主探测缺少 $prefix 这一支（NDK 的 prebuilt 目录名随宿主而变）", "\"$prefix\"" in sh)
        }
        assertTrue(
            "prebuilt 候选为**零**时必须报错退出：静默继续就是把「ANDROID_NDK_HOME 指错了」说成链接错误",
            "matches[@]} -eq 0" in sh,
        )
        assertTrue(
            "prebuilt 候选为**多个**时必须报错退出并让人用 NDK_HOST_BIN 指定：静默挑第一个就是拿错工具链",
            "matches[@]} -eq 1" in sh,
        )
        assertTrue(
            "RUSTFLAGS 里丢了 max-page-size=16384 ⇒ 产出的 .so 在 16KB 页设备上被拒收（「解析包出现问题」），" +
                "而发布门禁 ①–⑨ 全绿看不出来；改这行请连同 build.gradle.kts 的 useLegacyPackaging 说明一起看",
            "max-page-size=16384" in sh,
        )
        assertEquals(
            "三 ABI 的 cp 必须都跟着 \$PROFILEDIR（曾写死 release/ ⇒ 文档里的 --debug 编得出拷不出）",
            3,
            sh.split("\$PROFILEDIR/libtangsnow_core.so").size - 1,
        )
        assertTrue(
            "cp 路径里又出现写死的 /release/ ⇒ --debug 那次会 cp 失败（产物明明编出来了）",
            "/release/libtangsnow_core.so" !in sh,
        )
        assertTrue(
            "脚本末尾不再指向 app/src/coreRust/jniLibs —— 那里是 `.so` 的唯一跟踪副本，" +
                "拷错地方就是造第二份真相（见 随包 so 只有一份真相）",
            "app/src/coreRust/jniLibs" in sh,
        )
    }

    @Test
    fun `第 ⑪ 项的 ELF 页对齐门禁在位且范围没被悄悄改掉`() {
        // 这道门禁的**形状是实测出来的**（2026-10-18 对 7 个真 APK）：arm64-v8a / x86_64 里连上游
        // GeckoView 的库都已 >=0x4000，而 armeabi-v7a 的 13 颗全是 0x1000 ⇒ 把 32 位纳进断言，
        // 门禁会因**别人的产物**永久红。"只查这两个 ABI、只拦自家那颗"不是随手写的，所以逐条钉住。
        // 全部用**行首前缀 + substring**解析而不是正则：这条断言要跨 Python/Kotlin 两层字符串，
        // 能用普通字符串匹配住的，就别为它多背一层转义。
        assertTrue(
            "⑪ 的判定函数不见了 —— 删它要连着改 selftest 的夹具与状态说明，不许静默消失",
            "def so_page_alignment_problems(" in gateText,
        )
        assertTrue(
            "⑪ 的 ELF 解析函数不见了（读不出程序头必须 fail-closed，不许「看不懂就跳过」）",
            "def elf_min_load_align(data)" in gateText,
        )
        val abisLine = gateText.lines().firstOrNull { it.trimStart().startsWith("ALIGNED_ABIS = {") }
            ?: error("tools/verify_release.py 里找不到 ALIGNED_ABIS = {...}（改了写法就同步改本测试）")
        val abis = abisLine.substringAfter("{").substringBefore("}").split(",")
            .map { it.trim().trim('"') }.filter { it.isNotEmpty() }.toSet()
        assertEquals(
            "参评 ABI 变了：加进 armeabi-v7a 就会因上游 0x1000 永久红；减到空集则这条门禁形同虚设",
            setOf("arm64-v8a", "x86_64"), abis,
        )
        val ownLine = gateText.lines().firstOrNull { it.trimStart().startsWith("OWN_LIBS = (") }
            ?: error("tools/verify_release.py 里找不到 OWN_LIBS = (...)")
        val own = ownLine.substringAfter("(").substringBefore(")").split(",")
            .map { it.trim().trim('"') }.filter { it.isNotEmpty() }.toSet()
        assertEquals(
            "⑪ 只该拦我们自己产的 .so：libxul.so 的对齐是 Mozilla 的事，不该变成我们的红灯",
            setOf("libtangsnow_core.so"), own,
        )
        assertTrue(
            "阈值不再是 16384 ⇒ 和 16KB 页设备的要求脱钩了（改阈值必须写清依据哪台设备）",
            "ALIGN_16K = 16384" in gateText,
        )
        // 函数在 ≠ 门禁在：第 ⑨ 项 M6 就是「函数抽出来了，但只查 apks[0]」那一类形状。
        val callAt = gateText.indexOf("bad_align, note_align = so_page_alignment_problems(apks)")
        assertTrue(
            "⑪ 的判定函数没人调用 —— 留着函数不等于门禁还在（调用点找不到了）",
            callAt >= 0,
        )
        val after = gateText.substring(callAt, (callAt + 400).coerceAtMost(gateText.length))
        assertTrue(
            "⑪ 调用之后没有 raise Fail ⇒ 不足 16KB 只会多打印一行字，等于没拦",
            "raise Fail" in after,
        )
        val cases = gateText.split("case(\"⑪").size - 1
        assertTrue(
            "⑪ 的 --selftest 夹具少于 5 档 ⇒ 双向自证被削（放行 / 拦 / 不参评 / fail-closed / 只报告）",
            cases >= 5,
        )
    }

    @Test
    fun `tangsnow-rs 的测试数与 README 声明一致，且 README 点名的差分文件都在`() {
        // README 自己写着「Rust 侧计数没有像 Kotlin 那样被 `TestCountCommentTest` 钉住」⇒ 这里把它钉上。
        // 顺带拦住一类具体事故：新增 `tests/*.rs` 却忘了 `git add` ⇒ 检出树里少一批用例而 README 仍写旧数
        // （`regression_2026_10_17.rs` 此刻正是**未跟踪**状态，靠人提醒不算数）。
        val rsRoot = File(TestPaths.repoRoot(), "tangsnow-rs")
        val readme = File(rsRoot, "README.md").readText()
        val m = Regex("`cargo test` \\*\\*(\\d+)/(\\d+) 通过\\*\\*").find(readme)
            ?: error("tangsnow-rs/README.md 里找不到「`cargo test` **N/N 通过**」那句（改了写法就同步改本测试）")
        assertEquals(
            "那句写成 N/N，两个数本来就该相同；不同说明其中一个是手抄错的",
            m.groupValues[2], m.groupValues[1],
        )
        val declared = m.groupValues[1].toInt()
        val actual = rsRoot.walkTopDown()
            .onEnter { dir -> dir.name != "target" } // 构建目录里的 .rs 不是源码（本仓有 tangsnow-rs/target）
            .filter { it.isFile && it.extension == "rs" }
            .sumOf { f -> f.readLines().count { it.trimStart().startsWith("#[test]") } }
        assertEquals(
            "README 声明的 `cargo test` 数 ≠ 仓库里实际的 `#[test]` 数 —— 要么改了测试没同步文档，" +
                "要么测试文件没进版本库（后者最危险：换台机器检出就少一批）",
            declared, actual,
        )
        val named = Regex("tests/[a-z0-9_]+\\.rs").findAll(readme).map { it.value }.toSet()
        assertTrue(
            "README 里一个差分文件都没点名 ⇒ 说明它的写法变了，本测试要跟着改（不许静默空集放行）",
            named.isNotEmpty(),
        )
        val missing = named.filter { !File(File(rsRoot, "tangsnow-core"), it).exists() }
        assertTrue(
            "README 指着不存在的差分文件：$missing —— 补文件、把文件纳入版本库，或改文档，别留假指路",
            missing.isEmpty(),
        )
    }

    /**
     * **每个 `#[test]` 用例都必须真的有断言**（2026-10-20 审查报告 C-12）。
     *
     * `bench_smoke.rs` 曾经只有 `println!`、**零断言** —— 它**永远不会失败**，
     * 却照样计入 README 声明的「N/N 通过」，而上一条测试正是拿这个数当门禁依据
     * ⇒ 一个空转的用例稀释了门禁的分量（读起来像"N 个检查都过了"，实际少了一个）。
     *
     * 判据：函数体里出现 `assert!` / `assert_eq!` / `assert_ne!` / `panic!` 之一
     * （proptest 的 `prop_assert!` 同样是断言 ✔）。
     * 刻意只做「有没有断言」的**粗判**，不解析断言的强度 —— 粗判已经能挡住
     * 「只打印不判定」这一类；断言写得好不好是另一回事，由 review 把关。
     *
     * ⚠️ **已知盲区**：用例体靠「剥注释与字符串字面量后按花括号配平」抽出来，
     * 抽不出来的用例**不会被检查**（2026-10-20 实测：`download_filename.rs` 里有几个
     * 用例抽不出来）。故本条是**兜底**而非完备证明；真要完备得写个 Rust 词法分析器 ✔。
     */
    @Test
    fun `每个用例都真的有断言`() {
        val rsRoot = File(TestPaths.repoRoot(), "tangsnow-rs")
        val offenders = mutableListOf<String>()
        var total = 0
        rsRoot.walkTopDown()
            .onEnter { dir -> dir.name != "target" }
            .filter { it.isFile && it.extension == "rs" }
            .forEach { f ->
                val bodies = testBodies(f.readText())
                total += bodies.size
                bodies.forEachIndexed { i, body ->
                    if (ASSERT_MARKERS.none { body.contains(it) }) {
                        offenders += "${f.relativeTo(TestPaths.repoRoot()).path}：第 ${i + 1} 个 #[test] 只有打印、没有断言"
                    }
                }
            }
        assertTrue(
            "扫到的 $total 个 #[test] 用例里，有 ${offenders.size} 个**没有任何断言** —— " +
                "这类用例永远不会失败，却照样计入 README 声明的测试数，稀释门禁分量：\n  " +
                offenders.joinToString("\n  "),
            offenders.isEmpty(),
        )
    }

    /**
     * 取出每个 `#[test]` 对应的函数体（按花括号配平）。
     *
     * 三处必须先处理，否则会误判：
     *  ① **字符串字面量** —— proptest 的形如 `ext in "[a-zA-Z]{1,6}"` 里带着花括号，
     *     当成代码块会让配平跑偏、把函数体截成半截（`properties_fuzz.rs` 就踩过这个）；
     *  ② **注释** —— 注释里出现的 `#[test]`（例如「与 JVM 侧逐条对齐的 `#[test]`」）
     *     不是用例，被当成零断言就会误报；
     *  ③ **顺序** —— 必须**先剥字符串、再剥注释**：反过来的话，字符串里的 `//`
     *     （如 `https://x`）会先被当注释吃掉 ⇒ 引号变奇数 ⇒ 随后的字符串匹配跨行配对
     *     ⇒ 把大段代码（含 `#[test]`）当成字符串抹掉。
     */
    private fun testBodies(text: String): List<String> {
        val code = text
            // DOT_MATCHES_ALL：Rust 字符串可用 `\` + 换行续行，"\\." 得能跨行才匹配得上
            .replace(Regex("\"(\\\\.|[^\"\\\\])*\"", RegexOption.DOT_MATCHES_ALL), "\"\"")
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""//[^\n]*"""), " ")
        val out = ArrayList<String>()
        var i = 0
        while (true) {
            val at = code.indexOf("#[test]", i)
            if (at < 0) break
            i = at + 1
            val open = code.indexOf('{', at)
            if (open < 0) continue
            var depth = 0
            var j = open
            while (j < code.length) {
                when (code[j]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                j++
            }
            out += code.substring(open + 1, minOf(j, code.length))
        }
        return out
    }

    /** 只为比较"是不是同一颗产物"，手算 SHA-256，不额外引依赖。 */
    private fun sha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /**
     * **能照抄的命令行必须带 flavor**（2026-10-16）。
     *
     * 判据故意收窄到"含 `./gradlew` 的行"：文档正文里解释"旧名字已失效"的句子是**该留的**
     * （`README.md` 里就有一句 "Bare `:app:lintDebug` ... no longer resolve"），
     * 而真正坑人的是**可照抄的那一行** —— 加 `core` flavor 之后：
     *  · `:app:lintDebug` / `:app:testDebugUnitTest` 变成 ambiguous，直接报任务名不存在；
     *  · 不带 flavor 的 `:app:assembleRelease` 会把**两套**（6 个 APK）一起打进 `app/release/`，
     *    而发版按"一次一个 flavor"判，多出来的三颗会被当成"预期之外的 APK"。
     * 这两条都是 2026-10-16 实测（dry-run）确认的，不是推断。
     */
    @Test
    fun `文档与CI里的 gradlew 命令都带 flavor`() {
        val scanned = listOf(
            "README.md", "README.zh-CN.md", "CONTRIBUTING.md", "CONTRIBUTING.zh-CN.md",
            "docs", ".github/PULL_REQUEST_TEMPLATE.md", ".github/workflows/ci.yml",
            "tangsnow-rs/README.md", "tangsnow-rs/自建验证指南.md",
        )
        val bare = Regex(""":app:(?:lint|test|assemble|bundle|package)(?:Debug|Release)(?![A-Za-z])""")
        val offenders = mutableListOf<String>()
        var gradlewLines = 0
        for (rel in scanned) {
            val root = File(TestPaths.repoRoot(), rel)
            val files = when {
                root.isDirectory -> root.walkTopDown()
                    .filter { it.isFile && it.extension.lowercase() in setOf("md", "yml", "yaml") }.toList()
                root.isFile -> listOf(root)
                else -> {
                    offenders += "  $rel —— 扫描面里的文件/目录不见了（文档改名要同步本测试）"
                    continue
                }
            }
            files.forEach { file ->
                file.readText().lineSequence().forEachIndexed { index, line ->
                    if (!line.contains("./gradlew")) return@forEachIndexed
                    gradlewLines++
                    bare.find(line)?.let {
                        offenders += "  ${file.relativeTo(TestPaths.repoRoot())}:${index + 1} 「${it.value}」不带 flavor"
                    }
                }
            }
        }
        assertTrue(
            "一条 `./gradlew` 都没扫到 ⇒ 扫描面已经失效（文档被移走/改名），这道门等于没在跑",
            gradlewLines > 0,
        )
        assertTrue(
            "这些行是照抄就会失败的命令，应带 flavor（:app:lintCoreKotlinDebug / " +
                ":app:testCoreRustDebugUnitTest / :app:assembleCoreKotlinRelease）；实际命中 " +
                offenders.size + " 处：" + offenders.joinToString(" ｜ "),
            offenders.isEmpty(),
        )
    }

    /** 把 `"a" to 1`（Kotlin）与 `"a": 1`（Python 字典）都解析成有序对；形状不符直接失败（不静默跳过）。 */
    private fun pairsOf(text: String): List<Pair<String, Int>> =
        text.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { entry ->
            val m = Regex(""""([^"]+)"\s*(?:to|:)\s*(\d+)""").find(entry)
                ?: error("解析不出 ABI 序号对，条目原文：「$entry」（改了写法就同步改本测试）")
            m.groupValues[1] to m.groupValues[2].toInt()
        }.also { assertTrue("ABI 表解析结果为空（形状不符）", it.isNotEmpty()) }

}
