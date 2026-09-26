#!/usr/bin/env python3
# -*- coding: utf-8 -*-
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
1. git 工作区干净 —— 保证产物能对应到确切提交
2. 包名、versionName、versionCode、ABI 与 `app/build.gradle.kts` 一致
3. 签名证书 SHA-256 == 期望指纹；且至少使用 v2 签名方案
4. 权限集合 == 已披露基准（新增权限必须同时更新本文件与文档）
5. 打印校验和与体积，供发布说明直接引用
6. **提示级**：签名之后是否又改过 `app/src` / 构建脚本 ——「工作区干净」只说明
   *此刻*没有未提交改动，并不保证 APK 是从 HEAD 构建的。只改文档不影响产物，
   故这里只提示不拦；按提交时间判断，可能多报，宁可多提醒。

依赖：Python 3 标准库 + 本机 Android SDK 的 `apksigner` / `aapt2`。
"""

import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys

# Windows 控制台默认 GBK/CP936 时，✅/❌/⚠️ 等字符会抛 UnicodeEncodeError，
# 让发布校验本身在打印第一行时就崩掉（部分机器实测）。统一把输出流重配为
# UTF-8（无法解码的字符用 replace 兜底）；极老 Python 无 reconfigure 时静默降级。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, OSError):
        pass

# ---------------------------------------------------------------------------
# 期望值 —— 这些是本项目「对外已披露」的事实，改动必须是有意的
# ---------------------------------------------------------------------------

# 签名证书指纹：与 v2.0.1 / v2.0.2 同一把密钥（CN=Tan_Sno）。
# 换过密钥意味着老用户无法覆盖安装，必须是有意识的决定，故写死在这里。
EXPECTED_CERT_SHA256 = "af9542c452c164036d9dce01d6ce2a6d6b1bd20c7f2900e9f0bcfec777e18041"

EXPECTED_PACKAGE = "io.github.tan_sno.tangsnow"

# ABI 分包：文件名 → (ABI, versionCode 的 ABI 序号)
# versionCode = 基准 * 10 + 序号（见 app/build.gradle.kts 的 splits 配置）
ABI_SPLITS = {
    "app-arm64-v8a-release.apk": ("arm64-v8a", 1),
    "app-armeabi-v7a-release.apk": ("armeabi-v7a", 2),
    "app-x86_64-release.apk": ("x86_64", 3),
}

# 权限基准：与 README「隐私」一节、隐私政策第 2 条披露的一致。
# 最后一项由 AndroidX 自动生成（应用自签、不涉及数据访问），README 亦有说明。
EXPECTED_PERMISSIONS = {
    "android.permission.INTERNET",
    "android.permission.CAMERA",
    # Android 17 起访问局域网必需（否则连接被内核拦掉）；仅在打开局域网地址时按需申请
    "android.permission.ACCESS_LOCAL_NETWORK",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.WAKE_LOCK",
    "android.permission.MODIFY_AUDIO_SETTINGS",
    "io.github.tan_sno.tangsnow.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
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

def repo_root():
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
    """从 local.properties 读 sdk.dir（Android SDK 的位置）。"""
    lp = os.path.join(root, "local.properties")
    if not os.path.isfile(lp):
        raise Fail("缺少 local.properties，无法定位 Android SDK。")
    with open(lp, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line.startswith("sdk.dir="):
                # 值形如 D\:\\some\\sdk（Windows 上盘符冒号与反斜杠都会被转义），需反转义
                return line.split("=", 1)[1].replace("\\\\", "\\").replace("\\:", ":")
    raise Fail("local.properties 中没有 sdk.dir。")


def find_build_tool(sdk, name):
    """在 build-tools 下取**版本号最高**的那份工具。"""
    bt = os.path.join(sdk, "build-tools")
    if not os.path.isdir(bt):
        raise Fail("Android SDK 下没有 build-tools 目录：%s" % bt)

    def key(d):
        return [int(x) for x in re.findall(r"\d+", d)] or [0]

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
        # 注释里写一句 `// versionCode = 37`（该文件的版本历史注释正是这种风格），
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
    return set(re.findall(r"uses-permission: name='([^']+)'", r.stdout))


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


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description="发布前校验（纯本地，只读）")
    ap.add_argument("--apk-dir", default=None, help="APK 所在目录，默认 app/release")
    ap.add_argument("--quiet", action="store_true", help="只输出结论与校验和")
    args = ap.parse_args()

    root = repo_root()
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

        apks = [os.path.join(apk_dir, n) for n in ABI_SPLITS]
        missing = [p for p in apks if not os.path.isfile(p)]
        if missing:
            raise Fail("缺少 APK：\n      " + "\n      ".join(os.path.basename(p) for p in missing))

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

        for name, (abi, order) in ABI_SPLITS.items():
            apk = os.path.join(apk_dir, name)
            want_code = exp["versionCode"] * 10 + order

            info = apk_info(aapt2, env, apk)
            if info["package"] != EXPECTED_PACKAGE:
                problems.append("%s：包名是 %s，期望 %s" % (name, info["package"], EXPECTED_PACKAGE))
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
            extra, lack = perms - EXPECTED_PERMISSIONS, EXPECTED_PERMISSIONS - perms
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
