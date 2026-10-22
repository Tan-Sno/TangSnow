# AI生成
#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# AI生成（标记必须在 shebang 之下；coding 声明占第 2 行是 PEP 263 认的窗口）
"""发布前校验：把「人工核对的那几件事」变成可重复、会失败的断言。

    python tools/verify_release.py                 # 校验 app/release 下的三个包
    python tools/verify_release.py --apk-dir DIR   # 指定目录
    python tools/verify_release.py --quiet         # 只报结论与校验和

**纯本地**：不联网、不改任何文件、不上传。全部只读。

## 为什么需要它
这两件事都真实发生过，且都靠人的记性：

1. 签名时工作区有未提交的改动（一次 build 依赖升级没提交）⇒ 发布出去的 APK
   **对应不到任何 git 提交**，日后无从追溯，且事后无法补救。
2. `apksigner` 在缺 `JAVA_HOME` 时**报错却返回退出码 0** —— 「退出码 0」看起来
   就是「验证通过」。本脚本因此不只看退出码，还要求输出里出现签名方案为真的行。

## 检查项
1. git 工作区干净 —— 保证**此刻没有未提交改动**（它**不**等于「产物是从 HEAD 构建的」，
   与 HEAD 的对应关系另见第 6 项）
2. 包名、versionName、versionCode、ABI 与 `app/build.gradle.kts` 一致
3. 签名证书 SHA-256 == 期望指纹；且至少使用 v2 签名方案
4. 权限集合 == 已披露基准（新增权限必须同时更新本文件与文档）
5. 打印校验和与体积，供发布说明直接引用
6. **提示级**：签名之后是否又改过 `app/src` / 构建脚本 ——「工作区干净」只说明
   *此刻*没有未提交改动，并不保证 APK 是从 HEAD 构建的。只改文档不影响产物，
   故这里只提示不拦；按提交时间判断，可能多报，宁可多提醒。
7. 依赖完整性（调 `tools/verify_deps.py`）：构件与公布散列不符**就拦**；
   「一项都没校验成」也拦（闸门不能形同虚设）。
8. **APK 里实际装的内核**与 `gradle/libs.versions.toml` 声明的 GeckoView 版本一致 ——
   从 `lib/*/libxul.so` 里找内核构建号，与声明逐字符比对。为什么需要它：依赖声明、
   内核自报、APK 内实物是**三个**不同来源，只前两者一致并不说明打出来的包是对的
   （过期的 AAR / 构建缓存都可能让包里的内核与声明不符，而门禁与单测都看不出来）。
9. `exported=true` 的组件集合 == 基准：依赖升级会悄悄带进**或带走**新的导出面（GeckoView 157 带进
   剪贴板 provider、158 又移除 —— 同一道门两次都拦到了），而只比权限集合的检查看不见它。
10. **包内原生库形态**：`lib/*.so` 必须走 deflate，且条目的 local header extra 不得超阈。
    为什么需要它（2026-10-15 从 Rust 线 10-07 的安装攻坚搬来）：AGP 为 16KB 页设备做 zip 层
    对齐时，会把 ~14KB 的 padding 写进 `.so` 条目的 **local header extra field**，安装器的
    libziparchive 在提取阶段拒收（`res=-113`，用户端只看到「解析包出现问题」）；而
    aapt2 / apksigner / Java `ZipFile` 全读得动 —— ①–⑨ 因此可以**全绿却装不上**。
    修复项是 `useLegacyPackaging = true`（.so 走 deflate、安装时解压），所以这里两道都查：
    「是不是 deflate」= 修复有没有生效，「padding 多长」= 就算没对齐也别漏。

依赖：Python 3 标准库 + 本机 Android SDK 的 `apksigner` / `aapt2`。
"""

import argparse
import hashlib
import os
import re
import shutil
import struct
import subprocess
import sys
import zipfile

def _use_utf8_output():
    """把 stdout / stderr 重配为 UTF-8（无法解码的字符用 replace 兜底）。

    Windows 控制台默认 GBK/CP936 时，本脚本要打的 ✅/❌/⚠️ 会抛 UnicodeEncodeError，
    让发布校验在打印第一行时就崩掉（部分机器实测）；极老 Python 无 reconfigure 时静默降级。

    ⚠️ 刻意**不**做模块级调用：模块级副作用意味着「谁 import 本模块，谁的 stdout 就被改掉」
    （被别的脚本 import 取值时会波及对方）。放进 main() 后，只有真正跑校验时才动输出流。
    """
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, OSError):
            pass

# ---------------------------------------------------------------------------
# 期望值 —— 这些是本项目「对外已披露」的事实，改动必须是有意的
# ---------------------------------------------------------------------------

# 签名证书指纹：与 v2.0.1 / v2.0.2 同一把密钥（CN=Tan_Sno）。
# 换过密钥意味着老用户无法覆盖安装，必须是有意识的决定，故写死在这里。
EXPECTED_CERT_SHA256 = "af9542c452c164036d9dce01d6ce2a6d6b1bd20c7f2900e9f0bcfec777e18041"

# 应用身份：A1（2026-10-19）起两 flavor 是**两个独立 applicationId** ——
#  · coreKotlin → io.github.tan_sno.tangsnow（既有的已发布身份）
#  · coreRust   → io.github.tan_sno.tangsnow.rust（新增身份，可与 Kotlin 包同机并存）
# ⚠️ namespace 两 flavor 相同 ⇒ 组件全名（EXPECTED_EXPORTED）**不分叉**，只有 applicationId
#    及其派生的动态接收器权限（见 expected_permissions）不同。校验时按 --flavor 取值。
EXPECTED_PACKAGE_BY_FLAVOR = {
    "coreKotlin": "io.github.tan_sno.tangsnow",
    "coreRust": "io.github.tan_sno.tangsnow.rust",
}

# ABI 分包：ABI → versionCode 的 ABI 序号。versionCode = 基准 * 10 + 序号
# （见 app/build.gradle.kts 的 splits 配置）。
ABI_ORDER = {"arm64-v8a": 1, "armeabi-v7a": 2, "x86_64": 3}

# 2026-10-15 起 `app` 有了 `core` flavor 维度，产物名随之变成
# `app-<flavor>-<abi>-release.apk`。**一次发布只允许一个 flavor** —— 两个 flavor 共用
# applicationId 与同一套 versionCode，混发等于让用户在两份实现之间无声升降。所以名单按
# --flavor 生成，而目录里出现的**另一个 flavor** 的包会落进「预期之外的 APK」被拦下。
DEFAULT_FLAVOR = "coreKotlin"


def abi_splits(flavor: str) -> dict:
    """文件名 → (ABI, versionCode 的 ABI 序号)，按 flavor 生成。"""
    return {
        "app-%s-%s-release.apk" % (flavor, abi): (abi, order)
        for abi, order in ABI_ORDER.items()
    }

# 权限基准：与 README「隐私」一节、隐私政策第 2 条披露的一致。
# ⚠️ 这里**不含**随 applicationId 派生的动态接收器权限（AndroidX 生成、前缀 = 包名）——
#    它在 expected_permissions() 里按 flavor 的包名拼接，故两 flavor 各自正确。
EXPECTED_PERMISSIONS = {
    "android.permission.INTERNET",
    "android.permission.CAMERA",
    # Android 17 起访问局域网必需（否则连接被内核拦掉）；仅在打开局域网地址时按需申请
    "android.permission.ACCESS_LOCAL_NETWORK",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.WAKE_LOCK",
    "android.permission.MODIFY_AUDIO_SETTINGS",
}


def expected_permissions(package: str) -> set:
    """按 applicationId 拼接期望权限集。

    动态接收器权限 `"<applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"` 由 AndroidX
    依包名生成 —— A1 之后 coreKotlin / coreRust 包名不同，该条必须随 flavor 变化。
    """
    return set(EXPECTED_PERMISSIONS) | {
        package + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
    }

# 导出组件基准（`exported=true` 的组件全清单）。为什么必须写死：依赖升级会**悄悄**带进新的导出组件，
# 而「权限基准」（第 4 项）看不见它。2026-10-03 实证：GeckoView 155→157 带进了
# `org.mozilla.gecko.GeckoClipboardContentProvider`（exported=true、**无权限保护**，任何应用可查询），
# 同时 `androidx.profileinstaller` 的 `ProfileInstallReceiver` 也在（受 `android.permission.DUMP` 保护，
# 只有 shell/系统能触发 ⇒ 无害）。两个 activity 是应用自己的入口，另两个来自依赖 —— 增删都必须是有意的。
# 每项是 (类型, 组件名, android:permission 或 None)。**把"保护"也写进来**是有意的：
# 只记名字的话，「有人悄悄把 provider 的权限收口去掉」不会红。
# 2026-10-22：GeckoView 158 **移除了**上面那个剪贴板 provider（导出面 4 → 3 —— 同一道门的第二次生效）。
# `AndroidManifest.xml` 里那条 `tools:node="merge"` + DUMP 的规则**保留**作防御：内核哪天真把它放回来，
# 规则会自动重新收口，而这里会当场红、提醒把这行加回基准。
EXPECTED_EXPORTED = {
    ("activity", "io.github.tan_sno.tangsnow.ConsentActivity", None),       # LAUNCHER（同意页）
    ("activity", "io.github.tan_sno.tangsnow.MainActivity", None),          # http/https 的 ACTION_VIEW 入口
    # androidx 基线配置安装，自带 DUMP 保护（仅 shell/系统可触发）
    ("receiver", "androidx.profileinstaller.ProfileInstallReceiver", "android.permission.DUMP"),
}

# SDK 级别：与 app/build.gradle.kts 的 defaultConfig 一致。
# 此前没有任何检查项校验它们 —— 误改 targetSdk 会一路绿灯发布出去。
EXPECTED_MIN_SDK = 26
EXPECTED_TARGET_SDK = 37


class Fail(Exception):
    """校验失败。只带一句人话，不打印堆栈 —— 使用者要的是「哪里不对、怎么办」。"""


# ---------------------------------------------------------------------------
# 环境探测
# ---------------------------------------------------------------------------

def repo_root(override=None):
    """仓库根 = 本脚本上溯一级（`tools/` 的父目录）。

    `override` 只为 [selftest] 与「在别的 checkout 上试跑」存在：脚本自身定位在默认路径时
    行为与从前完全一致。**自检必须能指向一个临时仓库**，否则就没法喂它坏输入。
    """
    if override:
        return os.path.abspath(override)
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True,
                          encoding="utf-8", errors="replace", **kw)


def _java_exe(java_home):
    return os.path.join(java_home, "bin", "java.exe" if os.name == "nt" else "java")


def _is_jdk(path):
    """该目录看起来是不是一个 JDK / JRE（`bin/java[.exe]` 在位）。"""
    return bool(path) and os.path.isfile(_java_exe(path))


def _studio_jbr_candidates():
    """Android Studio 自带运行时（JBR）的**约定位置**（各平台通用，不含任何本机绝对路径）。"""
    if os.name == "nt":
        yield os.path.join(os.environ.get("ProgramFiles", r"C:\Program Files"),
                           "Android", "Android Studio", "jbr")
        yield os.path.join(os.environ.get("LOCALAPPDATA", ""),
                           "Programs", "Android Studio", "jbr")
    elif sys.platform == "darwin":
        yield "/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    else:
        yield "/opt/android-studio/jbr"
        yield "/usr/lib/android-studio/jbr"
        yield os.path.expanduser("~/android-studio/jbr")


def find_java(sdk=None):
    """找 java。优先 `JAVA_HOME`；其次 Android Studio 自带的 JBR；最后由 PATH 兜底。

    单独抽出来是因为 apksigner 缺 java 时**退出码仍为 0**，必须提前拦住。

    ⚠️ 这里**不写死任何本机绝对路径**：此前候选列表里留着开发机的 Android Studio JBR
    路径（报错示例也沿用了它），那属于「构建环境绝对路径」，不该出现在对外仓库里。
    该路径自 v2.1.1 起已进入公开历史（只改 tip 清不掉），故此前一直留着待随版本处理；
    现已改为推导式。**不要再把具体机器的路径加回候选列表或错误提示里。**
    开发机那种「Android Studio 与 SDK 解包在同一个根目录下」的布局由 [sdk] 推出来：
    取出 SDK 目录的**兄弟目录**中带 `jbr/bin/java` 的那些 —— 推导过程与盘符、
    目录名都无关，因此对任何同布局的机器都成立。
    """
    jh = os.environ.get("JAVA_HOME")
    if _is_jdk(jh):
        return jh
    # ⚠️ 这里**没有**「JAVA_HOME 是目录就接受」的分支：指向一个存在但没有
    # bin/java 的目录（卸载残留）时，必须继续走下方兜底链（Studio JBR / SDK 兄弟
    # 目录 / PATH），否则会在 apksigner 处以难以定位的方式失败。
    # _is_jdk 已覆盖 JRE / JDK 两种布局，不存在第三种合法形态。

    candidates = list(_studio_jbr_candidates())
    if sdk:
        parent = os.path.dirname(os.path.abspath(sdk.rstrip("/\\")))
        try:
            for name in sorted(os.listdir(parent)):
                candidates.append(os.path.join(parent, name, "jbr"))
        except OSError:
            pass
    for cand in candidates:
        if _is_jdk(cand):
            return cand

    if shutil.which("java"):
        return None  # 直接可用，无需设置
    raise Fail(
        "找不到 Java。apksigner 需要它，且**缺 Java 时它会报错却返回退出码 0**。\n"
        "      请设置 JAVA_HOME 指向一个 JDK（或 Android Studio 安装目录下自带的 jbr）后重试：\n"
        "        Windows:     set JAVA_HOME=<JDK 目录>\n"
        "        macOS/Linux: export JAVA_HOME=<JDK 目录>"
    )


def find_sdk(root):
    """定位 Android SDK：先读 local.properties 的 sdk.dir，再退到 ANDROID_HOME / ANDROID_SDK_ROOT。

    ⚠️ 顺序不能反：开发机上 ANDROID_HOME 可能指向另一份 SDK，而 local.properties 才是本工程
    实际构建所用的那一份（该文件已 gitignore、不进仓库）。CI 上没有它，故必须有环境变量兜底 ——
    否则本脚本一上 runner 就必 Fail（这正是「加 CI」这条待办的前置之一）。
    """
    lp = os.path.join(root, "local.properties")
    if os.path.isfile(lp):
        with open(lp, encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if line.startswith("sdk.dir="):
                    # 值形如 D\:\\some\\sdk（Windows 上盘符冒号与反斜杠都会被转义），需反转义
                    return line.split("=", 1)[1].replace("\\\\", "\\").replace("\\:", ":")
    for env_var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(env_var)
        if value and os.path.isdir(value):
            return value
    raise Fail(
        "无法定位 Android SDK：既没有 local.properties 的 sdk.dir，"
        "也没有可用的 ANDROID_HOME / ANDROID_SDK_ROOT。"
    )


def find_build_tool(sdk, name):
    """在 build-tools 下取**版本号最高**的那份工具。"""
    bt = os.path.join(sdk, "build-tools")
    if not os.path.isdir(bt):
        raise Fail("Android SDK 下没有 build-tools 目录：%s" % bt)

    def key(d):
        # 预发布版本排在**稳定版之后**：纯数字比较会把 "36.0.0-rc1"（→[36,0,0,1]）排到
        # "36.0.0"（→[36,0,0]）之上，从而选中一个预览版 build-tools（2026-10-01 外部审查指出的候选）。
        stable = 0 if re.search(r"(rc|beta|alpha|preview|canary|dev)", d, re.IGNORECASE) else 1
        return (stable, [int(x) for x in re.findall(r"\d+", d)] or [0])

    for ver in sorted(os.listdir(bt), key=key, reverse=True):
        for cand in ("%s.bat" % name, name, "%s.exe" % name) if os.name == "nt" else (name,):
            p = os.path.join(bt, ver, cand)
            if os.path.isfile(p):
                return p
    raise Fail("build-tools 下找不到 %s。" % name)


def tool_env(java_home):
    env = dict(os.environ)
    if java_home:
        env["JAVA_HOME"] = java_home
    return env


# ---------------------------------------------------------------------------
# 期望值的来源（从构建脚本解析，避免两处各写一遍版本号）
# ---------------------------------------------------------------------------

def read_gradle_expectations(root):
    path = os.path.join(root, "app", "build.gradle.kts")
    if not os.path.isfile(path):
        raise Fail("找不到 app/build.gradle.kts。")
    with open(path, encoding="utf-8") as fh:
        text = fh.read()

    def grab(pattern, what, flags=0):
        m = re.search(pattern, text, flags)
        if not m:
            raise Fail("无法从 app/build.gradle.kts 解析出%s。" % what)
        return m.group(1)

    return {
        # 两者都做行锚定（^\s*）：re.search 取的是全文首个匹配 —— 不锚定的话，将来谁在
        # 注释里写一句 `// versionCode = 38`（该文件的版本历史注释正是这种风格），
        # 这里就会静默取错值。只锚一个等于留一半隐患。
        "versionName": grab(r'^\s*versionName\s*=\s*"([^"]+)"', "versionName", re.M),
        "versionCode": int(grab(r"^\s*versionCode\s*=\s*(\d+)", "versionCode", re.M)),
    }


# ---------------------------------------------------------------------------
# 各项检查
# ---------------------------------------------------------------------------

def check_git_clean(root):
    """① 工作区干净 —— 这一条是「产物能对应到提交」的前提，最重要。"""
    # --no-optional-locks：git status 会刷新 .git/index（后台可选锁）——
    # 本脚本自称「全部只读」，这里必须做到字面意义上的只读。
    r = run(["git", "--no-optional-locks", "status", "--porcelain"], cwd=root)
    if r.returncode != 0:
        raise Fail("git status 执行失败：%s" % (r.stderr or "").strip())
    dirty = [l for l in r.stdout.splitlines() if l.strip()]
    if dirty:
        shown = "\n      ".join(dirty[:10])
        more = "" if len(dirty) <= 10 else "\n      …另有 %d 项" % (len(dirty) - 10)
        raise Fail(
            "工作区不干净，共 %d 项未提交：\n      %s%s\n"
            "      ⇒ 此时构建出的 APK 对应不到任何 git 提交，日后无从追溯。\n"
            "      请先提交（或 stash）后重新构建再签名。" % (len(dirty), shown, more)
        )


def apk_info(aapt2, env, apk):
    r = run([aapt2, "dump", "badging", apk], env=env)
    if r.returncode != 0:
        raise Fail("aapt2 读取失败：%s" % (r.stderr or r.stdout or "").strip())
    out = r.stdout
    info = {}
    m = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", out)
    if not m:
        raise Fail("无法从 APK 解析包名与版本：%s" % apk)
    info["package"], info["versionCode"], info["versionName"] = m.group(1), int(m.group(2)), m.group(3)
    m = re.search(r"native-code: '([^']*)'", out)
    info["abi"] = m.group(1) if m else ""
    # minSdk / targetSdk：此前没有任何检查项盯着它们 —— 误改 targetSdk 会一路绿灯。
    # 字段名 minSdkVersion（大写 S，aapt2 36.0.0 实测，整行位于行首）；旧版 build-tools
    # 的 badging 用 sdkVersion 表示 minSdk，兜底再认一次。两个都没有 = None → 调用方
    # 判失败（fail-closed）。
    # 三者都做行锚定（^，re.M）：badging 是「一行一个字段」的产物，锚定后只认字段行
    # 行首，不会在别处误取。（此处曾有过一句「不锚定会咬到 targetSdkVersion」的说法，
    # **实测为误**：字段名里的 S 是大写，而本正则大小写敏感 —— 顺便留作提醒：
    # 结论要跑出来，别由形态推断。）
    m = re.search(r"^minSdkVersion:'([^']+)'", out, re.M)
    if not m:
        m = re.search(r"^sdkVersion:'([^']+)'", out, re.M)
    info["minSdk"] = int(m.group(1)) if m else None
    m = re.search(r"^targetSdkVersion:'([^']+)'", out, re.M)
    info["targetSdk"] = int(m.group(1)) if m else None
    return info


def apk_permissions(aapt2, env, apk):
    r = run([aapt2, "dump", "permissions", apk], env=env)
    if r.returncode != 0:
        raise Fail("aapt2 读取权限失败：%s" % apk)
    # aapt2 的 `dump permissions` 会把带 maxSdkVersion 的权限打成
    # `uses-permission-sdk-23: name='...'`——旧正则只认 `uses-permission:`，那类声明会被整条漏掉
    # （既不进集合，也就永远无法与 EXPECTED_PERMISSIONS 比对）。
    # ⚠️ **当前无触发面**（2026-10-17 实测现役 4 个真包：只出现 `uses-permission:` 一种前缀，
    # 新旧正则解出的集合逐字节相同）⇒ 这条是**防未来**的加固，不是修了某个现役漏检；
    # 真出现 `-sdk-NN` 声明时第 ④ 项会当场红，那正是想要的。
    return set(re.findall(r"uses-permission(?:-sdk-\d+)?: name='([^']+)'", r.stdout))


def apk_signature(apksigner, env, apk):
    """返回 (证书 SHA-256, {签名方案: 是否启用})。

    注意：**不能只看退出码** —— apksigner 在缺 Java 时会报错但返回 0，
    故同时要求输出里出现明文结论行。
    """
    r = run([apksigner, "verify", "--verbose", "--print-certs", apk], env=env)
    out = (r.stdout or "") + (r.stderr or "")
    if "JAVA_HOME" in out or "no 'java' command" in out:
        raise Fail("apksigner 未能运行（缺 Java），且它的退出码不可信：\n      %s" % out.strip()[:200])
    if "DOES NOT VERIFY" in out or "ERROR" in out:
        raise Fail("签名校验未通过：\n      %s" % out.strip()[:300])
    # 不依赖 apksigner 输出的整句措辞（build-tools 版本更替可能微调）——用宽松正则
    # 解析出的 schemes 字典判定；没有「v2 为真」= 无法判定，仍然 fail-closed。
    schemes = {m.group(1): m.group(2) == "true"
               for m in re.finditer(r"Verified using ([^:]+): (true|false)", out)}
    if not any(enabled and key.startswith("v2") for key, enabled in schemes.items()):
        raise Fail("输出里没有「v2 签名方案为真」的结论，不能判定通过：\n      %s"
                   % out.strip()[:300])
    m = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)", out)
    if not m:
        raise Fail("无法从 apksigner 输出中取到证书指纹。")
    return m.group(1).lower(), schemes


def check_apk_freshness(root, apks):
    """⑥ 签名之后是否又改过「会进产物」的代码。

    工作区干净只保证「此刻没有未提交改动」，**不能**保证「APK 是从 HEAD 构建的」：
    完全可以先签名、再提交若干改动，于是要发布的包其实落后于 HEAD。
    这里取 APK 文件时间之后触及产物相关路径的提交 —— 有就提示。

    路径清单必须覆盖**一切会影响产物的输入**：源码、应用构建脚本、R8 规则、
    根构建脚本与设置、仓库级构建属性、wrapper/目录。漏掉 proguard-rules.pro
    这类文件时，改一行 keep 规则 100% 改变产物却不会被提示。

    ⚠️ 时序说明：本仓库的发布流程是「构建 → 签名 → 提交」，因此**本次发布的
    提交本身**必然晚于 APK 文件时间，下面的列表总会列到它 —— 这是预期现象，
    不是告警。真正要人工确认的是列表里是否出现了**超出本次发布内容**的文件
    （那才说明 APK 落后于 HEAD）。**返回的是清单，不是错误**：升级为拦截会把
    既定发布流程卡死，交给人判断更合适。
    """
    newest = max(os.path.getmtime(p) for p in apks)
    r = run(
        ["git", "log", "--since=%d" % int(newest), "--name-only",
         "--pretty=format:", "--",
         "app/src", "app/build.gradle.kts", "app/proguard-rules.pro",
         "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradle/"],
        cwd=root,
    )
    return sorted({l.strip() for l in (r.stdout or "").splitlines() if l.strip()})


def check_apk_gecko_build(root, apks):
    """⑧ 每个 APK 内 `libxul.so` 的内核构建号是否与 `gradle/libs.versions.toml` 声明一致。

    三个来源（依赖声明 / 内核自报 BuildConfig / 包内实物）里，只有这一条能证明**打出去的包**
    用的是声明的内核 —— 过期的 AAR 或构建缓存会让前两者一致而实物不符，而单测和 lint 都看不见。
    逐包检查（三个 ABI 分包各自带一份内核，任何一份不符都说明打包链出了问题）。
    返回 (ok, 说明)。
    """
    toml = os.path.join(root, "gradle", "libs.versions.toml")
    m = re.search(r'^geckoView\s*=\s*"([^"]+)"', open(toml, encoding="utf-8").read(), re.M)
    if not m:
        return False, "在 gradle/libs.versions.toml 里找不到 geckoView 声明"
    declared = m.group(1)                      # 形如 157.0.20260924084938
    major = declared.split(".")[0]
    build_id = declared.split(".")[-1]
    total = 0
    for apk in apks:
        with zipfile.ZipFile(apk) as z:
            targets = [n for n in z.namelist()
                       if n.startswith("lib/") and n.endswith("/libxul.so")]
            if not targets:
                return False, "%s 里没有 lib/*/libxul.so" % os.path.basename(apk)
            for name in sorted(targets):
                found_id = found_rv = False
                with z.open(name) as f:
                    tail = b""
                    while True:
                        chunk = f.read(1 << 20)
                        if not chunk:
                            break
                        buf = tail + chunk
                        if build_id.encode() in buf:
                            found_id = True
                        if ("rv:" + major).encode() in buf:
                            found_rv = True
                        if found_id and found_rv:
                            break
                        tail = buf[-64:]
                if not (found_id and found_rv):
                    return False, (
                        "%s 的 %s 里没找到内核 %s（构建号 %s / rv:%s）：包里的内核与声明不符 "
                        "⇒ 可能是过期的 AAR 或构建缓存"
                        % (os.path.basename(apk), name, declared, build_id, major)
                    )
                total += 1
    return True, "%s（共 %d 份 libxul.so，均为声明版本）" % (declared, total)


def native_libs_not_deflated(apks):
    """⑩ 的第一道断言：包内 `lib/*.so` 必须是 deflate。返回不符的条目清单（空 = 通过）。

    为什么值得抽成函数：这段判断坏掉的表现同样是**静默放行**而不是报错，而它管的是
    「装得上装不上」——`useLegacyPackaging = true` 一被删掉，stored 条目就会让 16K 对齐的
    padding 落进 local header，安装器 libziparchive 拒收（`res=-113`），而 ①–⑨ 全绿。
    抽出来之后 `--selftest` 能用一个假包把它双向自证（2026-10-15）。

    读的是中央目录里的 `compress_type`（不是自己解析 local header）⇒ 与 Java/aapt2 同源。
    """
    bad = []
    for apk in apks:
        with zipfile.ZipFile(apk) as z:
            bad += [
                "%s：%s" % (os.path.basename(apk), i.filename)
                for i in z.infolist()
                if i.filename.startswith("lib/") and i.filename.endswith(".so")
                and i.compress_type != zipfile.ZIP_DEFLATED
            ]
    return bad


def check_exported_components(aapt2, env, apks, dump=None):
    """⑨ 清单里 `exported=true` 的组件集合（含 `android:permission`）是否等于 [EXPECTED_EXPORTED]。

    为什么单列一项：第 4 项只比权限集合，**看不见组件导出面**；而"多出一个能被别的应用调用的
    provider/activity"往往来自依赖升级，且不会有任何编译或测试信号。把 `android:permission`
    一起比也有意为之：不然"有人悄悄把收口去掉"不会红。

    **遍历所有分包**（2026-10-15 修 M6）。原先只查 `apks[0]`，依据是「ABI 分包共用同一份清单
    （差异只在 `lib/`）」—— 这句**今天**成立，但它是一句关于构建配置的断言，不是一个保证：
    flavor 维度进来之后，清单差异完全可能只落在某个分包上（不同 flavor 的 manifest 合并、
    某个分包带了额外的依赖组件），那时这道门就只对三分之一产物有效，而它自己不会说。
    现在三份都验、并且**先互相比对**：分包之间不一致时报「同一份清单的假设已不成立」，
    比甩一句「与基准不符」更能指到根因。代价是多两次 `aapt2 dump`（本地、毫秒级）。

    @param dump 「apk → `aapt2 dump xmltree` 的 stdout」注入点，**只给 `--selftest` 用**：
        本项的判定逻辑（三份互比、与基准比、形状不符要红）不需要真 APK 就能自证，
        而"能不能从真清单里解出这些行"仍然只有产物能证明。生产路径不传，走真 aapt2。
    """

    def parse(apk):
        """把一个分包的清单解析成 {(kind, name, permission)}；返回 (集合, 错误说明)。"""
        if dump is None:
            r = run([aapt2, "dump", "xmltree", apk, "--file", "AndroidManifest.xml"], env=env)
            if r.returncode != 0:
                return None, "aapt2 dump xmltree 失败"
            text = r.stdout
        else:
            text = dump(apk)
            if text is None:
                return None, "aapt2 dump xmltree 失败"
        rows, kind, name, exported, permission = [], None, None, None, None

        def flush():
            if kind and name and exported == "true":
                rows.append((kind, name, permission))

        for ln in text.splitlines():
            t = ln.strip()
            m = re.match(r"^E: (activity|activity-alias|provider|service|receiver)\b", t)
            if m:
                flush()
                kind, name, exported, permission = m.group(1), None, None, None
                continue
            m = re.search(r'android:name\(0x[0-9a-f]+\)="([^"]+)"', t)
            if m and name is None:
                name = m.group(1)
                continue
            m = re.search(r"android:exported\(0x[0-9a-f]+\)=(\w+)", t)
            if m:
                exported = m.group(1).lower()
                continue
            m = re.search(r'android:permission\(0x[0-9a-f]+\)="([^"]+)"', t)
            if m and permission is None:
                permission = m.group(1)
        flush()
        # 一个导出组件都没解析出来 = 形状不符（清单结构或 aapt2 输出格式变了），不是「恰好没有」
        if not rows:
            return None, ("没解析出任何 exported=true 的组件 —— 解析形状已飘，"
                          "此时返回空集等于静默放行")
        return set(rows), None

    def show(items):
        return "；".join("%s %s%s" % (k, n, "" if p is None else "（权限 " + p + "）")
                         for k, n, p in items)

    got_by_apk = []
    for apk in apks:
        got, err = parse(apk)
        if err:
            return False, "%s：%s" % (os.path.basename(apk), err)
        got_by_apk.append((os.path.basename(apk), got))

    base_name, base = got_by_apk[0]
    for name, got in got_by_apk[1:]:
        if got != base:
            only_here = sorted(got - base)
            only_there = sorted(base - got)
            bits = []
            if only_here:
                bits.append("只在 %s 有：%s" % (name, show(only_here)))
            if only_there:
                bits.append("只在 %s 有：%s" % (base_name, show(only_there)))
            return False, ("%s 与 %s 的导出组件不一致 —— 「ABI 分包共用同一份清单」这个假设"
                           "已经不再成立：%s" % (base_name, name, " ｜ ".join(bits)))

    got = base
    if got == EXPECTED_EXPORTED:
        return True, ("%d 个导出组件（含权限保护）在 %d 个分包里完全一致，且与基准一致"
                      % (len(got), len(got_by_apk)))
    parts = []
    extra = sorted(got - EXPECTED_EXPORTED)
    missing = sorted(EXPECTED_EXPORTED - got)
    if extra:
        parts.append("多出：" + show(extra))
    if missing:
        parts.append("少了：" + show(missing))
    return False, "导出组件与基准不符（%s，%d 个分包同此结论）："% (base_name, len(got_by_apk)) + " ｜ ".join(parts)


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------



# ---- ⑪ 原生库的 ELF 页对齐（2026-10-18 本机对 7 个真 APK 实测后定下的形状）----
ALIGN_16K = 16384
# 16KB 页只存在于 arm64 / x86_64 设备；32 位 armeabi-v7a 不要求（且上游 GeckoView 的 13 个库
# 实测全是 0x1000 ⇒ 把它们纳进断言只会让门禁因别人的产物而永久红）。
ALIGNED_ABIS = {"arm64-v8a", "x86_64"}
OWN_LIBS = ("libtangsnow_core.so",)


def elf_min_load_align(data):
    """返回 ELF 里 PT_LOAD 段的最小 p_align；读不出结构就返回 None（调用方按 fail-closed 处理）。"""
    import struct
    if len(data) < 64 or data[:4] != b"\x7fELF":
        return None
    is64, le = (data[4] == 2), ("<" if data[5] == 1 else ">")
    if is64:
        phoff, = struct.unpack_from(le + "Q", data, 0x20)
        phes, = struct.unpack_from(le + "H", data, 0x36)
        phnum, = struct.unpack_from(le + "H", data, 0x38)
        af, asz = 48, "Q"
    else:
        phoff, = struct.unpack_from(le + "I", data, 0x1C)
        phes, = struct.unpack_from(le + "H", data, 0x2A)
        phnum, = struct.unpack_from(le + "H", data, 0x2C)
        af, asz = 28, "I"
    if not phes or not phnum or phnum > 64 or phoff + phes * phnum > len(data):
        return None
    best = None
    for i in range(phnum):
        o = phoff + i * phes
        p_type, = struct.unpack_from(le + "I", data, o)
        p_align, = struct.unpack_from(le + asz, data, o + af)
        if p_type == 1:  # PT_LOAD
            best = p_align if best is None else min(best, p_align)
    return best


def so_page_alignment_problems(apks):
    """⑪：我们产的 `lib/<abi>/*.so` 在 arm64/x86_64 上必须 p_align >= 16384。

    为什么要加这道：第 ⑩ 项查的是 **APK 层**（zip compress_type 与 local header padding），
    而"16KB 页内核设备上被 PackageManager 拒收"取决于 **ELF 层**的段对齐 —— 两层是两回事，
    此前只有一句 `RUSTFLAGS=-Wl,-z,max-page-size=16384` 的注释守着，没有任何回归保护。
    非 ELF / 读不出程序头一律按**问题**处理，不许"看不懂就跳过"。
    返回 (拦下的条目, 仅报告的条目)。

    实测边界（2026-10-18 在仓外副本对 15 个真包跑过一遍）：拦下 0 条 ⇒ 现网产物本来就合规；
    「仅报告」那一档在真包上**当前无触发面**（arm64/x86_64 里上游库已经是 0x4000，
    只有 armeabi-v7a 是 0x1000 而它按 ABI 规则不参评）⇒ 它只由 selftest 第 5 档夹具自证，
    别把它当成"真包上一直在报的东西"。
    """
    import os
    import zipfile
    bad, note = [], []
    for apk in apks:
        with zipfile.ZipFile(apk) as z:
            for n in z.namelist():
                if not (n.startswith("lib/") and n.endswith(".so")):
                    continue
                abi, name = n.split("/")[1], os.path.basename(n)
                if abi not in ALIGNED_ABIS:
                    continue
                al = elf_min_load_align(z.read(n))
                where = "%s!%s" % (os.path.basename(apk), n)
                if al is None:
                    bad.append("%s：读不出 ELF 程序头（非 ELF 或结构异常）" % where)
                elif al < ALIGN_16K:
                    (bad if name in OWN_LIBS else note).append("%s：p_align=0x%X < 0x4000" % (where, al))
    return bad, note


# ⑫ 我们自己产出的字节（要逐条目扫的那些）。
#
# ⚠️ 刻意**只查自家产物**：GeckoView 的 .so 里带的是 **Mozilla 自己**的构建路径
# （如 /Users/<Mozilla 开发者>/.mozbuild/…）—— 那是上游的事，不归我们管，
# 也不该让这条门禁天天红。真正要拦的是**我们的**编译机路径与身份。
OWN_ARTIFACT = re.compile(
    r"^(classes\d*\.dex|resources\.arsc|lib/[^/]+/libtangsnow_core\.so)$"
)


def apk_build_path_leaks(apks, root):
    """返回产物里出现的「构建机路径 / 身份」清单（空 = 干净）。

    为什么要有这条：APK 是**对外发布**的字节，里面若留着编译机的绝对路径与用户名，
    等于把构建环境的目录结构公开出去（Rust 线历史上就踩过：产物里嵌了
    `/home/<user>/.cargo/registry/src/…`，靠 `--remap-path-prefix` 才消掉）。
    这类东西**不崩、不报错**，只有把包发出去才看得见 ⇒ 放进发布门禁。

    判定口径（fail-closed）：
      · 仓库根与其上级目录的**绝对路径**（正/反斜杠两种写法都算）；
      · 任意 Linux/macOS 家目录前缀（`/home/`、`/Users/`）—— 这是**别的**构建机
        （Linux 交叉编译机）留下的用户名所在处，我们不认得那台机器的用户名，
        所以按前缀判定；
      · 家目录的**绝对路径**（Windows 构建机：`C:\\Users\\<name>`）。

    ⚠️ 刻意**不**判「裸用户名」与「`.cargo/registry` 片段」：
      前者会误伤（仓库若放在 `<workspace>/Android/…`，末段 `Android` 不是用户名，
      却会命中 dex 里的 `/Android/` 资源路径）；后者已被 `--remap-path-prefix`
      换成 `~/…`，剩下的是 crate 名与版本（`Cargo.lock` 里本来就有），不是泄漏。
    """
    root_abs = os.path.abspath(root)
    home = os.path.dirname(root_abs)
    needles = []
    for base in (root_abs, home):
        for variant in {base, base.replace("\\", "/"), base.replace("/", "\\")}:
            if variant:
                needles.append((variant.encode("utf-8", "replace"), "路径 %s" % variant))
    for frag in ("/home/", "/Users/"):
        needles.append((frag.encode("utf-8"), "家目录前缀 %s" % frag))

    leaks = []
    for apk in apks:
        try:
            z = zipfile.ZipFile(apk)
        except Exception as e:  # 读不了包 ⇒ fail-closed
            leaks.append("%s：包打不开（%s）" % (os.path.basename(apk), e))
            continue
        with z:
            for name in z.namelist():
                if not OWN_ARTIFACT.match(name):
                    continue
                try:
                    data = z.read(name)
                except Exception as e:
                    leaks.append("%s!%s：读不出内容（%s）" % (os.path.basename(apk), name, e))
                    continue
                for needle, label in needles:
                    if needle in data:
                        leaks.append(
                            "%s!%s：出现 %s" % (os.path.basename(apk), name, label)
                        )
    return leaks


def selftest():
    """发布门禁的自检：**证明它真的会拦，而不是一路绿灯。**

    ## 为什么需要它

    这个脚本是发版前最后一道闸，而它**失败时的表现是"抛异常"**、成功时的表现是"打印 ✅"——
    也就是说：**它自身的解析/判定逻辑一旦坏了，最可能的表现不是误报，而是静默放行**。
    本轮就吃过两次亏（`verify_release.py` 第 ⑧ 项只看 `apks[0]`、第 ⑨ 项的 permission 解析
    静默失效导致假红），两次都不是靠门禁自己发现的，是靠人肉复核发现的。

    ## 覆盖什么、为什么不覆盖什么

    ✅ 覆盖（2026-10-15 起，**四项都是"该放的放 + 该拦的拦"双向**）：
       ① [check_git_clean]：干净 ⇒ 不拦；有一处未提交 / 有未跟踪文件 ⇒ **必须拦**。
       ② 第 ⑦ 项（依赖完整性）：用**假 Gradle home + 假 `.module`** 跑「一致 ⇒ 放行且真的核对上了 /
          构件被改动 ⇒ 拦并说清是散列不一致 / 一项都没能校验 ⇒ 拦（ok=0 那条收紧）」。`verify_deps.py`
          本来就接受 `--cache`，仓库根由 CWD 向上找 `settings.gradle.kts` ⇒ 全程不联网。
       ③ 第 ⑧ 项（包内实物内核）：用**假 APK**（单个 `lib/*/libxul.so` 条目）跑「两串都对上 ⇒ 放行 /
          旧内核 ⇒ 拦 / 只对上构建号而 `rv:` 不符 ⇒ 拦 / 没有 libxul.so ⇒ 拦」。
       ④ 第 ⑨ 项（导出组件面）：用**假清单**跑「三份一致放行 / 只有一个分包多导出 ⇒ 拦 /
          解不出组件 ⇒ 拦 / 与基准不符 ⇒ 拦」四档（随 M6 一起加）。
       ⑤ 第 ⑩ 项（原生库形态）：`native_libs_not_deflated` 抽成了函数 ⇒ 能跑「deflate + 小 padding ⇒ 放行 /
          stored ⇒ 列出 / 14000B 的 local extra ⇒ `check_zip_padding.py` 拦」。
       ⑥ 第 ⑪ 项（原生库 **ELF 段对齐**，和 ⑩ 的 APK 层是两回事）：用手造的假 ELF（只写到 `p_align`）跑
          「16KB/64KB 放行 / arm64 只有 4KB ⇒ 拦 / armeabi-v7a 的 4KB ⇒ 不参评 / 非 ELF ⇒ fail-closed /
          上游库不足 ⇒ 只报告不拦」五档（2026-10-18 加，形状由对 7 个真 APK 的实测定）。

       只有"该放的放、该拦的拦"双向都过，才说明这道门既不放过问题、也不是"永远失败"。

    ❌ 不覆盖（**边界要说准**）：上面自证的是**判定逻辑**——给定这个形状就能得出这个结论。
       它**不**等于"真 APK / 真 Gradle 缓存 / 真 aapt2 输出一定能读对"：真产物还要 aapt2 与 build-tools
       （SDK 相关）、真 zip 的对齐与签名块、真 `.module` 的完整字段。那一段仍是已知缺口，
       写在这里是为了别假装它是全覆盖的 —— 发版前那一次**真包全流程跑通**不可用本自检替代。

    用法：`python tools/verify_release.py --selftest`（不需要任何 APK）。
    """
    import shutil
    import tempfile

    results = []

    def case(name, fn):
        try:
            fn()
            results.append((True, name, ""))
        except AssertionError as e:
            results.append((False, name, str(e)))
        except Exception as e:  # 脚本自身抛异常也算失败
            results.append((False, name, "意外异常：%s: %s" % (type(e).__name__, e)))

    tmp = tempfile.mkdtemp(prefix="tangsnow-verify-selftest-")
    try:
        root = os.path.join(tmp, "repo")
        os.makedirs(os.path.join(root, "app"), exist_ok=True)
        env = {
            "GIT_AUTHOR_NAME": "selftest", "GIT_AUTHOR_EMAIL": "selftest@example.invalid",
            "GIT_COMMITTER_NAME": "selftest", "GIT_COMMITTER_EMAIL": "selftest@example.invalid",
        }
        saved = {k: os.environ.get(k) for k in env}
        os.environ.update(env)
        try:
            r = run(["git", "init", "-q"], cwd=root)
            if r.returncode != 0:
                raise AssertionError("git init 失败：%s" % (r.stderr or "").strip())
            tracked = os.path.join(root, "app", "tracked.txt")
            with open(tracked, "w", encoding="utf-8") as f:
                f.write("clean\n")
            run(["git", "add", "-A"], cwd=root)
            r = run(["git", "commit", "-q", "-m", "selftest base"], cwd=root)
            if r.returncode != 0:
                raise AssertionError("git commit 失败：%s" % (r.stderr or "").strip())

            def clean_passes():
                check_git_clean(root)  # 不抛 = 干净时不拦，正确

            def dirty_blocks():
                with open(tracked, "a", encoding="utf-8") as f:
                    f.write("dirty\n")
                try:
                    check_git_clean(root)
                except Fail:
                    return
                finally:
                    # 复位，保证另一个用例跑在干净树上
                    with open(tracked, "w", encoding="utf-8") as f:
                        f.write("clean\n")
                raise AssertionError("工作区已弄脏，但 check_git_clean 没有拦 —— 门禁形同虚设")

            def untracked_blocks():
                stray = os.path.join(root, "app", "stray.txt")
                with open(stray, "w", encoding="utf-8") as f:
                    f.write("untracked\n")
                try:
                    check_git_clean(root)
                except Fail:
                    return
                finally:
                    os.remove(stray)
                raise AssertionError("出现未跟踪文件，但 check_git_clean 没有拦")

            case("干净工作区不拦（正向）", clean_passes)
            case("已修改未提交会拦（反向·必须红）", dirty_blocks)
            case("未跟踪文件也会拦（反向）", untracked_blocks)

            # ---- 第 ⑨ 项（导出组件面）的判定逻辑：用**假清单**自证
            # 2026-10-15 随 M6（改成遍历所有分包）一起加。判的是「三份互比 + 与基准比 +
            # 形状不符必须红」，这一段本来就不需要真 APK —— 需要产物的只是「能不能从真清单里
            # 解出这些行」，那仍然留在缺口里（见函数注释与下面的收尾提示）。
            three = ["app-arm64-v8a-release.apk", "app-armeabi-v7a-release.apk",
                     "app-x86_64-release.apk"]

            def xmltree(rows):
                """造 `aapt2 dump xmltree` 的文本形状（每个组件：E: <kind> + name/exported/permission）。"""
                out = ["E: manifest (Raw: \"manifest\")", "  E: application (Raw: \"application\")"]
                for kind, name, perm in rows:
                    out.append("  E: %s (Raw: \"%s\")" % (kind, kind))
                    out.append("    A: android:name(0x01010003)=\"%s\" (Raw: \"%s\")" % (name, name))
                    out.append("    A: android:exported(0x01010004)=true (Raw: \"true\")")
                    if perm:
                        out.append("    A: android:permission(0x01010006)=\"%s\" (Raw: \"%s\")" % (perm, perm))
                return "\n".join(out)

            base_rows = sorted(EXPECTED_EXPORTED)

            def check_nine(dumps, want_ok, want_in=""):
                got_ok, got_detail = check_exported_components(
                    "(stub, 未调用)", {}, three, dump=lambda apk: dumps[apk])
                if got_ok != want_ok:
                    raise AssertionError("期望%s，实际判成%s：%s" % (
                        "放行" if want_ok else "拦截", "放行" if got_ok else "拦截", got_detail))
                if want_in and want_in not in got_detail:
                    raise AssertionError("结论里应当出现「%s」，实际：%s" % (want_in, got_detail))

            def nine_all_equal_passes():
                check_nine({a: xmltree(base_rows) for a in three}, True)

            def nine_one_abi_diverges_blocks():
                # M6 的靶心：只看 apks[0] 时，另外两个分包多导出一个组件**根本看不见**。
                dumps = {a: xmltree(base_rows) for a in three}
                dumps[three[1]] = xmltree(base_rows + [("provider", "com.example.LeakedProvider", None)])
                check_nine(dumps, False, "不一致")

            def nine_shape_drift_blocks():
                # 一个组件都没解出来 = 清单结构或 aapt2 输出格式变了。返回空集就是静默放行，
                # 所以这里必须红（本项与第 4 项的权限集合不同，它看不见"多了谁"）。
                dumps = {a: xmltree(base_rows) for a in three}
                dumps[three[2]] = "E: manifest (Raw: \"manifest\")\n"
                check_nine(dumps, False, "形状")

            def nine_baseline_mismatch_blocks():
                # 三份一致但仍与基准不符（少一个）—— 方向别搞反：基准是**期望集合**，不是"第一个分包"。
                check_nine({a: xmltree(base_rows[:-1]) for a in three}, False, "少了")

            case("⑨ 三个分包一致且等于基准 ⇒ 放行（正向）", nine_all_equal_passes)
            case("⑨ 只有一个分包多导出组件 ⇒ 必须拦（M6 的靶心）", nine_one_abi_diverges_blocks)
            case("⑨ 解不出任何导出组件 ⇒ 必须拦，不能当空集放行", nine_shape_drift_blocks)
            case("⑨ 三份一致但少了基准组件 ⇒ 必须拦", nine_baseline_mismatch_blocks)

            # ====================================================================
            # ⑦⑧⑩：2026-10-15 起也用**离线夹具**自证（原先这三项写在"已知未覆盖"里）。
            # 共同做法：不碰真 APK / 真 Gradle 缓存 / 真网络，只在临时目录里造出判定函数
            # 需要读的那些字节，然后要求「该放行的放行、该拦的拦」两个方向都过。
            # 诚实的边界：自证的是**判定逻辑**，不是「真产物一定能读对」——后者仍然只有
            # 一次真 APK 跑通才算数（见本函数末尾的收尾提示）。
            # ====================================================================
            tools_dir = os.path.dirname(os.path.abspath(__file__))

            def run_tool(script, args_, cwd, cache=None):
                """跑 tools/ 下的兄弟脚本；统一按 UTF-8 解码（脚本自己会把输出重配成 UTF-8，
                而 Windows 的 locale 是 cp936 —— 用默认编码会把中文解崩，把"编码问题"误报成"判定失败"）。"""
                return subprocess.run(
                    [sys.executable, os.path.join(tools_dir, script)] + list(args_),
                    capture_output=True, text=True, encoding="utf-8", errors="replace", cwd=cwd,
                )

            def out_tail(r):
                return "\n".join(((r.stdout or "") + "\n[stderr]\n" + (r.stderr or "")).strip().splitlines()[-10:])

            def fake_apk(path, body=b"kernel", deflate=True, local_extra=b""):
                """造一个**只含一个** `lib/arm64-v8a/libxul.so` 条目的最小 zip，可注入 local extra。

                为什么刻意只放一个条目：往 local header 与数据之间插 extra 字节，会把后面所有
                条目的数据整体后移，而中央目录里记的 `header_offset` 不会跟着变 ⇒ 多条目时造出来
                的是**坏包**，测的就不是判据而是夹具了。单条目时该偏移恒为 0、不受影响。

                ⚠️ 插完还必须**同步 EOCD 里的「中央目录起始偏移」**（它也跟着后移了）。这条不是
                讲究出来的：本函数第一版就是漏了它，`zipfile` 于是从错位置读中央目录、把
                `header_offset` 读成 100，padding 检查报的是「local header 签名异常」而不是
                「padding 超阈」—— 用例的红/绿**看着符合预期，却都不是判据给的**。所以造完立刻
                自检两件事：包仍能打开、条目内容读回来等于原字节。
                """
                with zipfile.ZipFile(path, "w") as z:
                    z.writestr(
                        "lib/arm64-v8a/libxul.so", body,
                        compress_type=zipfile.ZIP_DEFLATED if deflate else zipfile.ZIP_STORED,
                    )
                if local_extra:
                    with open(path, "rb") as f:
                        raw = bytearray(f.read())
                    if raw[:4] != b"PK\x03\x04":
                        raise AssertionError("假包的 local header 签名不对 —— 夹具本身坏了")
                    fnlen = struct.unpack("<H", raw[26:28])[0]
                    extralen = struct.unpack("<H", raw[28:30])[0]
                    data_at = 30 + fnlen + extralen
                    raw[28:30] = struct.pack("<H", extralen + len(local_extra))
                    raw[data_at:data_at] = bytearray(local_extra)
                    eocd = raw.rfind(b"PK\x05\x06")
                    if eocd < 0:
                        raise AssertionError("假包没有 EOCD 记录 —— 夹具本身坏了")
                    cd_at = struct.unpack("<I", raw[eocd + 16:eocd + 20])[0]
                    raw[eocd + 16:eocd + 20] = struct.pack("<I", cd_at + len(local_extra))
                    with open(path, "wb") as f:
                        f.write(bytes(raw))
                # 夹具自检：插完字节之后必须仍是一个读得懂、内容不变的 zip
                with zipfile.ZipFile(path) as z:
                    names = z.namelist()
                    if names != ["lib/arm64-v8a/libxul.so"] or z.read(names[0]) != body:
                        raise AssertionError("假包自检失败（条目或内容与预期不符）：%s" % names)
                return path

            # ---- ⑧ 包内实物内核 ────────────────────────────────────────────
            # 用一个**不可能与真实内核撞上**的号：major=999、构建号=20990101000000。
            nine_nine = "999.0.20990101000000"
            fx_root = os.path.join(tmp, "fx-root")
            os.makedirs(os.path.join(fx_root, "gradle"), exist_ok=True)
            with open(os.path.join(fx_root, "gradle", "libs.versions.toml"), "w", encoding="utf-8") as f:
                f.write('[versions]\ngeckoView = "%s"\n' % nine_nine)
            apk_dir = os.path.join(tmp, "fx-apks")
            os.makedirs(apk_dir, exist_ok=True)
            # 判据要同时看到**构建号**与 **`rv:<major>`** 两个串（内核里两者都有），
            # 缺一个就会被判"不符" —— 所以正夹具两个都写上（本轮第一版漏了 rv:999，
            # 于是"正向"用例当场红：这条红是夹具的错，不是判据的错，正好说明用例是真的在跑判据）。
            body_ok = ("Mozilla Firefox %s\nbuildid %s\nrv:%s\n"
                       % (nine_nine, nine_nine.split(".")[-1], nine_nine.split(".")[0])).encode()
            # 一个"过期内核"的形状：读得到字符串，但既不是声明的构建号也不是 rv:999
            body_stale = b"Mozilla Firefox 155.0\nbuildid 20250101000000\nrv:155\n"

            def eight_matching_kernel_passes():
                p = fake_apk(os.path.join(apk_dir, "ok.apk"), body_ok)
                ok, detail = check_apk_gecko_build(fx_root, [p])
                if not ok:
                    raise AssertionError("声明与包内一致却被判不符（判定逻辑坏了会往「误报」方向骗人）：%s" % detail)

            def eight_stale_kernel_blocks():
                p = fake_apk(os.path.join(apk_dir, "stale.apk"), body_stale)
                ok, detail = check_apk_gecko_build(fx_root, [p])
                if ok:
                    raise AssertionError("包里是 155 的内核、声明是 999，⑧ 却放行 —— 这条门的全部意义没了")
                if "不符" not in detail:
                    raise AssertionError("拦下了但没指到根因（结论里应有「不符」）：%s" % detail)

            def eight_missing_libxul_blocks():
                p = os.path.join(apk_dir, "nolib.apk")
                with zipfile.ZipFile(p, "w") as z:
                    z.writestr("lib/arm64-v8a/libother.so", b"x", compress_type=zipfile.ZIP_DEFLATED)
                ok, detail = check_apk_gecko_build(fx_root, [p])
                if ok or "没有 lib" not in detail:
                    raise AssertionError("包里没有 lib/*/libxul.so 时必须红并说清（否则空包也能过关）：%s" % detail)

            def eight_only_one_marker_blocks():
                # 判据要**两个串都在**（构建号 + `rv:<major>`）才算对上。只对上构建号的那一档
                # 正是"半新半旧"的形状（例如换了 AAR 但缓存里还留着旧 selfreport），
                # 若哪天有人把 `and` 写成 `or`，这条就是唯一会红的地方。
                body = ("Mozilla Firefox 999.0\nbuildid %s\nrv:155\n"
                        % nine_nine.split(".")[-1]).encode()
                p = fake_apk(os.path.join(apk_dir, "half.apk"), body)
                ok, detail = check_apk_gecko_build(fx_root, [p])
                if ok:
                    raise AssertionError("只对上构建号、`rv:` 仍是 155，⑧ 却放行 ⇒ and/or 写错了")
                if "不符" not in detail:
                    raise AssertionError("拦下了但没指到根因：%s" % detail)

            case("⑧ 包内构建号与声明一致 ⇒ 放行（正向）", eight_matching_kernel_passes)
            case("⑧ 包里是过期内核 ⇒ 必须拦（⑧ 的本职）", eight_stale_kernel_blocks)
            case("⑧ 只对上构建号、`rv:` 不符 ⇒ 必须拦（and 不是 or）", eight_only_one_marker_blocks)
            case("⑧ 包里根本没有 libxul.so ⇒ 必须拦", eight_missing_libxul_blocks)

            # ---- ⑦ 依赖完整性 ─────────────────────────────────────────────
            # verify_deps.py 自带 --cache，且仓库根由 CWD 向上找 settings.gradle.kts 决定
            # ⇒ 一个假 Gradle home + 一份假 .module 就能完整自证，全程不联网。
            dep_root = os.path.join(tmp, "dep-root")
            os.makedirs(os.path.join(dep_root, "gradle"), exist_ok=True)
            open(os.path.join(dep_root, "settings.gradle.kts"), "w").close()
            with open(os.path.join(dep_root, "gradle", "libs.versions.toml"), "w", encoding="utf-8") as f:
                f.write('[versions]\nzxingCore = "0.0.1-selftest"\n')
            pkg_dir = os.path.join(tmp, "dep-cache", "caches", "modules-2",
                                   "files-2.1", "com.google.zxing", "core", "0.0.1-selftest", "abc")
            os.makedirs(pkg_dir, exist_ok=True)
            jar_path = os.path.join(pkg_dir, "core-0.0.1-selftest.jar")
            module_path = os.path.join(pkg_dir, "core-0.0.1-selftest.module")

            def write_artifact(on_disk, published_from):
                """写构件字节 + 一份 .module（公布散列按 `published_from` 算）。
                两者给不同字节，就是「下载之后构件被改动」的形状。"""
                with open(jar_path, "wb") as f:
                    f.write(on_disk)
                sha512 = hashlib.sha512(published_from).hexdigest()
                with open(module_path, "w", encoding="utf-8") as f:
                    f.write('{"formatVersion":"1.1","variants":[{"files":['
                            '{"name":"core-0.0.1-selftest.jar","sha512":"%s"}]}]}' % sha512)

            def seven_consistent_passes():
                write_artifact(b"selftest artifact bytes", b"selftest artifact bytes")
                r = run_tool("verify_deps.py", ["--cache", os.path.join(tmp, "dep-cache")], dep_root)
                if r.returncode != 0:
                    raise AssertionError("构件与公布散列一致却被拦：\n%s" % out_tail(r))
                if "一致 1 项" not in (r.stdout or ""):
                    raise AssertionError("放行是真放行了，但**一项都没核对**（应打「一致 1 项」）：\n%s" % out_tail(r))

            def seven_tampered_artifact_blocks():
                write_artifact(b"selftest artifact bytes TAMPERED", b"selftest artifact bytes")
                r = run_tool("verify_deps.py", ["--cache", os.path.join(tmp, "dep-cache")], dep_root)
                if r.returncode == 0:
                    raise AssertionError("下载后构件被改动，⑦ 却放行 —— 供应链闸门形同虚设")
                if "不一致" not in (r.stdout or ""):
                    raise AssertionError("拦下了但没说清是散列不一致（会被人当成脚本崩）：\n%s" % out_tail(r))

            def seven_nothing_verifiable_blocks():
                write_artifact(b"selftest artifact bytes", b"selftest artifact bytes")
                empty = os.path.join(tmp, "dep-cache-empty")
                os.makedirs(empty, exist_ok=True)
                r = run_tool("verify_deps.py", ["--cache", empty], dep_root)
                if r.returncode == 0:
                    raise AssertionError("缓存里一个构件都没有，⑦ 却返回 0 —— "
                                         "「ok=0 也拦」那条收紧（2026-10-01）失效了")

            case("⑦ 构件与公布散列一致 ⇒ 放行，且确实核对上了（正向）", seven_consistent_passes)
            case("⑦ 构件被改动过 ⇒ 必须拦并说清是散列不一致", seven_tampered_artifact_blocks)
            case("⑦ 一项都没能校验 ⇒ 必须拦，不能亮绿灯", seven_nothing_verifiable_blocks)

            # ---- ⑩ 原生库形态 ─────────────────────────────────────────────
            def ten_deflate_and_small_padding_passes():
                p = fake_apk(os.path.join(apk_dir, "good.apk"), body_ok, local_extra=b"p" * 100)
                bad = native_libs_not_deflated([p])
                if bad:
                    raise AssertionError("deflate 条目被误报成 stored（误报会让人白跑一次重打包）：%s" % bad)
                r = run_tool("check_zip_padding.py", [p], tmp)
                if r.returncode != 0:
                    raise AssertionError("100B 的 local extra 远小于阈值却被拦下（夹具或判据有问题）：\n%s"
                                         % out_tail(r))

            def ten_stored_blocks():
                p = fake_apk(os.path.join(apk_dir, "stored.apk"), body_ok, deflate=False)
                bad = native_libs_not_deflated([p])
                if len(bad) != 1:
                    raise AssertionError("stored 的 `.so` 必须被列出来（这是 `useLegacyPackaging` 被删的指纹），"
                                         "实际得到：%s" % bad)

            def ten_16k_padding_blocks():
                p = fake_apk(os.path.join(apk_dir, "pad16k.apk"), body_ok, local_extra=b"\x00" * 14000)
                if native_libs_not_deflated([p]):
                    raise AssertionError("夹具自己不该被 ⑩ 的第一道判据拦下")
                r = run_tool("check_zip_padding.py", [p], tmp)
                if r.returncode == 0 or "PAD-FAIL" not in (r.stdout or ""):
                    raise AssertionError("14000B 的 local extra（16K 对齐 padding 特征，阈 8192）没被拦 —— "
                                         "这就是装不上却全绿的那一类（res=-113）：\n%s" % out_tail(r))

            case("⑩ deflate + 小 padding ⇒ 放行（正向）", ten_deflate_and_small_padding_passes)
            case("⑩ stored 的 .so ⇒ 必须列出（useLegacyPackaging 被删的指纹）", ten_stored_blocks)
            case("⑩ 16K padding 特征 ⇒ check_zip_padding 必须拦", ten_16k_padding_blocks)
            # ---- ⑪ ELF 段对齐（假 ELF：只造到 p_align 那几个字节即可；5 档双向）----
            def fake_elf_so(align):
                import struct
                phdr = bytearray(56)
                struct.pack_into("<I", phdr, 0, 1)                 # p_type = PT_LOAD
                struct.pack_into("<Q", phdr, 48, align)            # p_align
                hdr = bytearray(64)
                hdr[0:4] = b"\x7fELF"
                hdr[4], hdr[5] = 2, 1                             # 64-bit, little endian
                struct.pack_into("<Q", hdr, 0x20, 64)              # e_phoff
                struct.pack_into("<H", hdr, 0x36, 56)              # e_phentsize
                struct.pack_into("<H", hdr, 0x38, 1)               # e_phnum
                return bytes(hdr) + bytes(phdr)

            def apk_with_so(path, abi_to_align):
                import zipfile as zf
                with zf.ZipFile(path, "w", zf.ZIP_DEFLATED) as z:
                    for abi, al in abi_to_align.items():
                        z.writestr("lib/%s/libtangsnow_core.so" % abi, fake_elf_so(al))

            def p(tag, abi_to_align):
                """每条用例一个独立文件名：共用 tmp 目录，但绝不共用产物（防夹具之间互相污染出假绿）。"""
                fp = os.path.join(tmp, "app-%s.apk" % tag)
                apk_with_so(fp, abi_to_align)
                return fp

            def ten_elf_align_passes():
                fp = p("align-ok", {"arm64-v8a": 0x4000, "x86_64": 0x10000, "armeabi-v7a": 0x1000})
                bad, _ = so_page_alignment_problems([fp])
                if bad:
                    raise AssertionError("合格产物被拦了：%s" % bad)

            def ten_elf_align_blocks_4k():
                fp = p("align-4k", {"arm64-v8a": 0x1000})
                bad, _ = so_page_alignment_problems([fp])
                if not bad:
                    raise AssertionError("4KB 对齐的 arm64 产物必须拦（16KB 页设备会拒收）")

            def ten_elf_align_skips_v7a():
                fp = p("align-v7a", {"armeabi-v7a": 0x1000})
                bad, _ = so_page_alignment_problems([fp])
                if bad:
                    raise AssertionError("armeabi-v7a 不该进这条断言（实测上游 13 个库都是 0x1000）：%s" % bad)

            def ten_elf_align_blocks_unreadable():
                fp = p("align-junk", {"x86_64": 0x4000})
                import zipfile as zf
                with zf.ZipFile(fp, "a") as z:
                    z.writestr("lib/x86_64/libjunk.so", b"not an elf at all")
                bad, _ = so_page_alignment_problems([fp])
                if not bad:
                    raise AssertionError("读不出 ELF 结构时必须 fail-closed，不许静默跳过")

            def ten_elf_align_reports_upstream():
                """别人的产物不足 16KB ⇒ **只报告不拦**，但报告必须非空（否则这条分支永远没人走）。"""
                fp = p("align-upstream", {"arm64-v8a": 0x4000})
                import zipfile as zf
                with zf.ZipFile(fp, "a") as z:
                    z.writestr("lib/arm64-v8a/libxul.so", fake_elf_so(0x1000))
                bad, note = so_page_alignment_problems([fp])
                if bad:
                    raise AssertionError("上游库的 4KB 不该变成我们的红灯：%s" % bad)
                if not note:
                    raise AssertionError("上游库不足 16KB 必须进报告，否则「仅报告」这一档等于没实现")

            case("⑪ ELF 段对齐：16KB/64KB 放行、v7a 不参评", ten_elf_align_passes)
            case("⑪ ELF 段对齐：arm64 只有 4KB ⇒ 必须拦", ten_elf_align_blocks_4k)
            case("⑪ ELF 段对齐：armeabi-v7a 的 4KB ⇒ 不得拦", ten_elf_align_skips_v7a)
            case("⑪ ELF 段对齐：非 ELF ⇒ 必须拦（fail-closed）", ten_elf_align_blocks_unreadable)
            case("⑪ ELF 段对齐：上游库不足 ⇒ 只报告不拦", ten_elf_align_reports_upstream)

            # ---- ⑫ 产物里的构建机路径/身份 ---------------------------------
            # 两个方向都要过：干净的 dex 放行、带本机路径的 dex 必须拦。
            # 顺带钉住「只查自家产物」这条边界：Mozilla 自己的构建路径不得变成我们的红灯。
            def twelve_leak_passes():
                fp = p("leak-clean", {})
                with zipfile.ZipFile(fp, "a") as z:
                    z.writestr("classes.dex", b"nothing to see here")
                if apk_build_path_leaks([fp], tmp):
                    raise AssertionError("干净的产物不该被判为含路径")

            def twelve_leak_blocks():
                fp = p("leak-dirty", {})
                with zipfile.ZipFile(fp, "a") as z:
                    z.writestr("classes.dex", b"built at " + tmp.encode("utf-8") + b"/app/src")
                if not apk_build_path_leaks([fp], tmp):
                    raise AssertionError("带本机路径的产物必须被拦下")

            def twelve_ignores_upstream_paths():
                fp = p("leak-upstream", {})
                with zipfile.ZipFile(fp, "a") as z:
                    # 上游 Mozilla 的构建路径：原样嵌在 GeckoView 的 .so 里，不归我们管
                    z.writestr("lib/arm64-v8a/libxul.so", b"/Users/someone/.mozbuild/x")
                    z.writestr("classes.dex", b"clean")
                leaks = apk_build_path_leaks([fp], tmp)
                if leaks:
                    raise AssertionError("上游库的构建路径不该变成我们的红灯：%s" % leaks)

            case("⑫ 产物路径扫描：干净产物放行", twelve_leak_passes)
            case("⑫ 产物路径扫描：含本机路径必须拦", twelve_leak_blocks)
            case("⑫ 产物路径扫描：上游库的路径不拦", twelve_ignores_upstream_paths)

        finally:
            for k, v in saved.items():
                if v is None:
                    os.environ.pop(k, None)
                else:
                    os.environ[k] = v
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    width = max(len(n) for _, n, _ in results)
    for ok, name, detail in results:
        print("  %s  %-*s %s" % ("PASS" if ok else "FAIL", width, name, detail))
    bad = [r for r in results if not r[0]]
    print("自检结果：%s（%d 项）" % ("全部通过" if not bad else "有失败项", len(results)))
    if not bad:
        print("⚠️ 自证的是**判定逻辑**（⑦⑧⑨⑩⑪ 双向），不是「真产物一定能读对」：真 APK / 真 Gradle 缓存 /\n"
              "   真 aapt2 输出仍只有跑一次真包才算数 —— 发版前那次真包全流程**不能**用本自检替代。")
    return 1 if bad else 0


def main():
    _use_utf8_output()
    ap = argparse.ArgumentParser(description="发布前校验（纯本地，只读）")
    ap.add_argument("--apk-dir", default=None, help="APK 所在目录，默认 app/release")
    ap.add_argument("--root", default=None,
                    help="仓库根（默认由脚本自身位置推断；自检与「在别处试跑」才需要传）")
    ap.add_argument("--selftest", action="store_true",
                    help="跑发布门禁的自检并退出（不需要 APK / SDK）")
    ap.add_argument("--flavor", default=DEFAULT_FLAVOR,
                    help="本次发布的内核实现 flavor（`core` 维度下的产品名），默认 %s；"
                         "APK 名单按 app-<flavor>-<abi>-release.apk 生成" % DEFAULT_FLAVOR)
    ap.add_argument("--quiet", action="store_true", help="只输出结论与校验和")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    # `--flavor` 白名单（2026-10-20 审查报告 C-5）。
    #
    # 此前 flavor 完全没校验，两个后果：
    #  ① 未知取值会在查 EXPECTED_PACKAGE_BY_FLAVOR 时抛**未捕获的 KeyError**
    #     —— 给使用者看的是一段栈回溯，不是「你把 flavor 拼错了」；
    #  ② flavor 会**拼进 APK 文件名**（`app-<flavor>-<abi>-release.apk`），
    #     形如 `../../x` 的取值能让拼出的路径**越出 --apk-dir**
    #     （只读、CLI 本地可控，影响有限，但没必要留着这个口子）。
    if args.flavor not in EXPECTED_PACKAGE_BY_FLAVOR:
        ap.error("未知的 --flavor：%r；可选值：%s"
                 % (args.flavor, "、".join(sorted(EXPECTED_PACKAGE_BY_FLAVOR))))

    root = repo_root(args.root)
    apk_dir = args.apk_dir or os.path.join(root, "app", "release")
    say = (lambda *a: None) if args.quiet else print

    try:
        say("棠雪 · 发布前校验（本地，不上传）")
        say("  仓库：%s" % root)
        say("  APK ：%s" % apk_dir)
        say("")

        # ① 工作区
        check_git_clean(root)
        say("  ✅ ① 工作区干净（此刻无未提交改动；与 HEAD 的对应关系见下方 ⑥）")

        # 可追溯性（只打印，不拦截）：本仓库流程是「提交 → 构建 → verify → push →
        # 打 tag」，verify 时刻 HEAD 本来就领先远端 —— 检查「是否已推送」只会误伤
        # 流程。这里把 SHA 与领先数打印出来供发布说明记录，事后可追溯性同样达到。
        head = run(["git", "rev-parse", "--short", "HEAD"], cwd=root)
        # 领先 upstream 数：对 origin/<当前分支> 用 rev-list 计数。
        # 不用 `git status -b` 的 `[ahead N]` —— 那要求分支**已绑定** upstream，
        # 而本仓库历次以 `git push origin main`（未加 -u）推送、分支并未绑定，
        # 该命令只会输出 `## main`，正则匹配不到 ⇒ 恒显示 `?`（2026-09-26 实测）。
        # rev-parse / rev-list 本身是只读命令，不需要 --no-optional-locks。
        branch_name = (run(["git", "rev-parse", "--abbrev-ref", "HEAD"],
                           cwd=root).stdout or "").strip()
        ahead = "?"
        if branch_name and branch_name != "HEAD":  # detached HEAD 时无从比对
            rev = run(["git", "rev-list", "--count",
                       "origin/%s..HEAD" % branch_name], cwd=root)
            if rev.returncode == 0 and (rev.stdout or "").strip().isdigit():
                ahead = rev.stdout.strip()
        say("      HEAD: %s（领先 upstream %s 个提交 —— push 后归零）"
            % ((head.stdout or "").strip(), ahead))

        exp = read_gradle_expectations(root)
        say("  ✅ ② 构建脚本声明：%s (versionCode %d)" % (exp["versionName"], exp["versionCode"]))

        splits = abi_splits(args.flavor)
        say("      flavor: %s ⇒ %s" % (args.flavor, "、".join(sorted(splits))))
        apks = [os.path.join(apk_dir, n) for n in splits]
        missing = [p for p in apks if not os.path.isfile(p)]
        if missing:
            raise Fail("缺少 APK：\n      " + "\n".join(os.path.basename(p) for p in missing))
        # 反向也要查：目录里任何**预期之外**的 .apk 都不进校验链，可能搭车发布
        # （旧包/第四个分包）。宁可在发布前拦下清点，不让它悄悄混出去（2026-10-01 外部审查）。
        extra = sorted(
            n for n in os.listdir(apk_dir)
            if n.lower().endswith(".apk") and n not in splits
        )
        if extra:
            raise Fail("发布目录里出现预期之外的 APK（不在校验链内，清点后再发布）：\n      "
                       + "\n      ".join(extra))

        # SDK 先解析：它既用于定位构建工具，也是 find_java 推导「兄弟目录里的 Android Studio JBR」的依据
        sdk = find_sdk(root)
        java_home = find_java(sdk)
        env = tool_env(java_home)
        apksigner = find_build_tool(sdk, "apksigner")
        aapt2 = find_build_tool(sdk, "aapt2")
        say("      SDK: %s" % sdk)
        say("      Java: %s" % (java_home or "沿用 PATH 中的 java"))

        problems = []
        checksums = []

        for name, (abi, order) in splits.items():
            apk = os.path.join(apk_dir, name)
            want_code = exp["versionCode"] * 10 + order

            info = apk_info(aapt2, env, apk)
            if info["package"] != EXPECTED_PACKAGE_BY_FLAVOR[args.flavor]:
                problems.append("%s：包名是 %s，期望 %s"
                                % (name, info["package"], EXPECTED_PACKAGE_BY_FLAVOR[args.flavor]))
            if info["versionName"] != exp["versionName"]:
                problems.append("%s：versionName 是 %s，构建脚本声明 %s"
                                % (name, info["versionName"], exp["versionName"]))
            if info["versionCode"] != want_code:
                problems.append("%s：versionCode 是 %d，按「基准×10+ABI序号」应为 %d"
                                % (name, info["versionCode"], want_code))
            if info["abi"] != abi:
                problems.append("%s：native-code 是 %s，期望 %s" % (name, info["abi"], abi))
            if info["minSdk"] != EXPECTED_MIN_SDK:
                problems.append("%s：minSdk 是 %s，期望 %s"
                                % (name, info["minSdk"], EXPECTED_MIN_SDK))
            if info["targetSdk"] != EXPECTED_TARGET_SDK:
                problems.append("%s：targetSdk 是 %s，期望 %s"
                                % (name, info["targetSdk"], EXPECTED_TARGET_SDK))

            perms = apk_permissions(aapt2, env, apk)
            want_perms = expected_permissions(info["package"])
            extra, lack = perms - want_perms, want_perms - perms
            if extra:
                problems.append("%s：多出未披露的权限 %s —— 需同步更新本文件与 README/政策"
                                % (name, sorted(extra)))
            if lack:
                problems.append("%s：缺少已披露的权限 %s" % (name, sorted(lack)))

            digest, schemes = apk_signature(apksigner, env, apk)
            if digest != EXPECTED_CERT_SHA256:
                problems.append("%s：签名证书指纹是 %s，期望 %s（换过密钥？）"
                                % (name, digest, EXPECTED_CERT_SHA256))

            on = [k for k, v in schemes.items() if v]
            checksums.append((name, sha256_of(apk), os.path.getsize(apk), on))

        if problems:
            print()
            print("❌ 校验未通过：")
            for p in problems:
                print("   · %s" % p)
            print()
            print("   以上任何一条成立，都说明当前产物**不该发布**。")
            return 1

        say("  ✅ ③ 包名 / 版本 / versionCode / ABI 三项全部匹配")
        say("  ✅ ④ 权限集合与已披露基准一致")
        say("  ✅ ⑤ 签名证书为预期密钥，且 v2 签名方案已启用")

        # ⑥ 提示（不是错误）：签名之后是否又改过会进产物的代码
        stale = check_apk_freshness(root, apks)

        print()
        if stale:
            say("⚠️  签名之后，这些「会影响产物」的文件又有改动：")
            for f in stale[:8]:
                say("      %s" % f)
            if len(stale) > 8:
                say("      …另有 %d 个" % (len(stale) - 8))
            say("")
            say("    注：本仓库流程是「构建 → 签名 → 提交」，本次发布的提交自身必然出现在上表。")
            say("    需要人工确认的是列表里是否出现了**超出本次发布内容**的文件 ——")
            say("    有则说明 APK 落后于 HEAD，请重新构建并签名后再发布。")
            say("")

        # ⑦ 依赖完整性（供应链加固，2026-09-30 新增）
        # 为什么排在 ⑥ 之后：⑥ 只是「签名后是否又改过产物」的**提示**，不是检查项，
        # 保持 ①–⑤ 的编号连续更清楚。
        # 语义：**不一致就拦**（构件与公布散列不符 = 供应链层面的红灯）；
        # 「一项都没能校验」也拦（ok=0 ⇒ 闸门形同虚设，2026-10-01 收紧）；
        # 个别条目「缓存里找不到 / 仓库未提供散列」只算"无法校验"，不拦（本机/CI 都可能发生）。
        dep = subprocess.run(
            [sys.executable, os.path.join(root, "tools", "verify_deps.py")],
            capture_output=True, text=True, cwd=root,
            # 子脚本会打印中文：Windows 上以 cp936 解码 stdout 会抛 UnicodeDecodeError，
            # 崩在这行就看不到真正的结论（本文件 run_tool 早为同一原因固定了 utf-8）。
            encoding="utf-8", errors="replace",
        )
        if dep.returncode != 0:
            # stdout 与 stderr 都要带上：子脚本自身崩溃（如编码问题）时真凶在 stderr，
            # 只看 stdout 会把「脚本崩了」误报成「散列不一致」（2026-10-01 外部审查实证）。
            tail = (dep.stdout + "\n[stderr]\n" + dep.stderr).strip().splitlines()[-8:]
            detail = "\n      ".join(tail)
            raise Fail("依赖完整性校验未通过（散列不一致或校验链自身失败），发布前必须查清：\n      %s" % detail)
        dep_summary = next(
            (l.strip() for l in dep.stdout.splitlines() if l.startswith("结果：")), "已核对"
        )
        say("  ✅ ⑦ 依赖完整性：%s" % dep_summary)

        # ⑧ APK 内实物内核与声明一致（2026-10-03 新增）。
        # 为什么加：有人问过「内核版本到底准不准」—— 依赖声明、内核自报 BuildConfig、
        # 包内 libxul.so 是三个来源；前两者一致**不**说明打出来的包是对的。
        gk_ok, gk_detail = check_apk_gecko_build(root, apks)
        if not gk_ok:
            raise Fail("APK 内内核版本与声明不一致：\n      %s" % gk_detail)
        say("  ✅ ⑧ 内核版本：%s" % gk_detail)

        # ⑨ 导出组件基准（2026-10-03 新增）。依赖升级带进新导出面时，这里会红。
        ex_ok, ex_detail = check_exported_components(aapt2, env, apks)
        if not ex_ok:
            raise Fail("清单的导出组件与基准不一致：\n      %s\n"
                       "      若这是有意的（如依赖带进的新组件并已评估/披露），请同步 "
                       "tools/verify_release.py 的 EXPECTED_EXPORTED 并写进改动说明。" % ex_detail)
        say("  ✅ ⑨ 导出组件：%s" % ex_detail)

        # ⑩ 包内原生库形态（2026-10-15 自 Rust 线的 10-07 安装攻坚搬来，两道断言缺一不可）。
        # 只查 padding 不够：stored 且 padding 小的包同样走安装器提取路径；只查 deflate 也不够：
        # 修复回退时会立刻反映在这里。
        not_deflated = native_libs_not_deflated(apks)
        if not_deflated:
            raise Fail(
                "%d 个 `.so` 不是 deflate ⇒ `useLegacyPackaging` 未生效，16K 对齐的 padding 会被"
                "写进 local header，安装器 libziparchive 可能拒收（res=-113，"
                "「解析包出现问题」）：\n      %s\n"
                "      修法见 app/build.gradle.kts 的 packaging/jniLibs 配置与 Rust 线攻坚报告。"
                % (len(not_deflated), "\n      ".join(not_deflated[:6]))
            )
        pad = subprocess.run(
            [sys.executable, os.path.join(root, "tools", "check_zip_padding.py")] + list(apks),
            capture_output=True, text=True, cwd=root,
            encoding="utf-8", errors="replace",
        )
        if pad.returncode != 0:
            tail = (pad.stdout + "\n[stderr]\n" + pad.stderr).strip().splitlines()[-8:]
            raise Fail("APK 本地头 padding 超阈（安装器拒收风险），发布前必须查清：\n      %s"
                       % "\n      ".join(tail))
        say("  ✅ ⑩ 原生库形态：三 ABI 的 .so 全部 deflate，本地头 padding 在阈内")
        bad_align, note_align = so_page_alignment_problems(apks)
        if bad_align:
            raise Fail("⑪ 原生库页对齐不足 16KB（这类库在 16KB 页设备上会在解析阶段被拒，" +
                       "症状与 ⑩ 的 res=-113 同族，而 ①–⑩ 全绿看不出来）：\n  " + "\n  ".join(bad_align))
        say("  ✅ ⑪ 原生库 ELF 段对齐：我们产的 .so 在 arm64/x86_64 全部 >=16KB" +
            ("（⚠️ 另有非本仓产物不足：\n     " + "\n     ".join(note_align) + "）" if note_align else ""))
        # ⑫ 产物里不得含构建机路径/身份（2026-10-21 新增）。
        leaks = apk_build_path_leaks(apks, root)
        if leaks:
            raise Fail(
                "产物里出现了构建机的路径/身份信息 —— APK 是对外发布的字节，等于公开编译机目录结构"
                "与用户名（这类东西不崩不报错，只有发出去才看得见）：\n      %s\n"
                "  修法：Kotlin 侧确认 release 的 R8 裁剪生效（产物不带调试信息）；"
                "Rust 侧确认 build-android.sh 的 --remap-path-prefix 重映射生效（路径被换成 ~）。"
                % "\n      ".join(leaks[:8])
            )
        say("  ✅ ⑫ 产物不含构建机路径/身份信息（dex、资源与自家 .so 逐条目扫过）")


        print("✅ 校验通过，可以发布。校验和（可直接贴进发布说明）：")
        print()
        for name, digest, size, on in checksums:
            print("   %s  %s  (%.1f MB)" % (digest, name, size / 1048576))
        print()
        print("   签名方案：%s" % "、".join(sorted({s for _, _, _, v in checksums for s in v})))
        return 0

    except Fail as e:
        print()
        print("❌ %s" % e)
        return 1
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main())
