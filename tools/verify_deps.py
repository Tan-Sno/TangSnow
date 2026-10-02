#!/usr/bin/env python3
"""依赖完整性校验（供应链加固，**只读**）：核对本地 Gradle 缓存里的构件与官方公布的校验和。

## 为什么要有它
仓库此前**没有任何依赖校验**：`gradle/wrapper/gradle-wrapper.properties` 钉了 Gradle 发行包的
SHA256，但**依赖产物本身**（尤其来自 maven.mozilla.org 的 GeckoView AAR）没有校验和/签名约束。
`settings.gradle.kts` 的 `includeGroupByRegex` 只做了"内容过滤"（限制哪些组能从 google 拉），
不等于完整性校验。2026-09-30 外部审查把这条列为供应链加固项。

## 它怎么校验（两条互补的路径）
1. **离线优先**：Gradle 下载构件时会校验其 `.module`（Gradle Module Metadata）里声明的
   `sha512`/`sha256`/`sha1`。本脚本读同一份 `.module`，把**当前缓存文件**重算一遍比对 ——
   能发现"下载后被改动/损坏/替换"的构件，且不需要联网。
2. **在线兜底**（`--online`）：对没有 `.module` 的构件，从仓库取 `.sha256`/`.sha1` 比对。
   注意：Maven Central 对部分构件（如 OkHttp 的 `okhttp.aar`）**不提供**伴生校验和文件，
   那种情况会如实报到"无公布校验和"，而不是伪装成通过。

## 用法
    python tools/verify_deps.py            # 离线（默认）
    python tools/verify_deps.py --online   # 额外联官网校验（慢，受网络影响）
    python tools/verify_deps.py --cache /path/to/gradle-home   # 指定 GRADLE_USER_HOME

退出码：0 = 全部一致（或无公布校验和项已如实列出）；1 = 发现不一致（值得当事故查），
或**一项都没能校验**（缓存缺失/元数据全无 ⇒ 闸门形同虚设，按失败处理，见 main 末尾）。
"""

from __future__ import annotations

import argparse
import hashlib
import ipaddress
import json
import os
import re
import socket
import sys
import urllib.parse
import urllib.request


def _use_utf8_output():
    """把 stdout / stderr 重配为 UTF-8 —— 与 verify_release.py 同款。

    Windows 中文环境默认 GBK/CP936：本脚本打的 ✓/✗/⚠️ 会抛 UnicodeEncodeError。
    作为子进程被 verify_release.py 调用时后果更糟：主脚本会把「编码崩溃」误报成
    「散列不一致（构件可能被改动）」—— 一次假供应链事故（2026-10-01 外部审查实证）。
    与 verify_release 同样刻意不做模块级调用（谁 import 谁被波及）。
    """
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, OSError):
            pass

# 依赖坐标 → (仓库基址, 构件文件名)。版本从 gradle/libs.versions.toml 读取，避免两处写版本。
REPOS = {
    "default": "https://repo1.maven.org/maven2",
    "google": "https://dl.google.com/dl/android/maven2",
    "mozilla": "https://maven.mozilla.org/maven2",
}

# (gradle 版本键, group:artifact, 仓库, 文件后缀)
# 只列**运行时真正进包**的关键构件；其余 AndroidX 走同一机制，收益递减故不逐一列。
TARGETS = [
    ("geckoView", "org.mozilla.geckoview:geckoview", "mozilla", ".aar"),
    ("okhttp", "com.squareup.okhttp3:okhttp-android", "default", ".aar"),
    ("zxingCore", "com.google.zxing:core", "default", ".jar"),
    ("zxingEmbed", "com.journeyapps:zxing-android-embedded", "default", ".aar"),
    ("coreKtx", "androidx.core:core-ktx", "google", ".aar"),
    ("appcompat", "androidx.appcompat:appcompat", "google", ".aar"),
    ("material", "com.google.android.material:material", "google", ".aar"),
    ("coroutinesAndroid", "org.jetbrains.kotlinx:kotlinx-coroutines-android", "default", ".jar"),
]

HASH_ALGOS = ("sha512", "sha256", "sha1")


def repo_root() -> str:
    d = os.path.abspath(os.getcwd())
    while True:
        if os.path.isfile(os.path.join(d, "settings.gradle.kts")):
            return d
        parent = os.path.dirname(d)
        if parent == d:
            raise SystemExit("定位不到仓库根（没找到 settings.gradle.kts）")
        d = parent


def versions(root: str) -> dict:
    """从 gradle/libs.versions.toml 的 [versions] 段读版本号（只认简单 key = "value" 形式）。"""
    path = os.path.join(root, "gradle", "libs.versions.toml")
    out, in_versions = {}, False
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            s = line.strip()
            if s.startswith("["):
                in_versions = s == "[versions]"
                continue
            if in_versions and "=" in s and not s.startswith("#"):
                k, v = s.split("=", 1)
                out[k.strip()] = v.strip().strip('"')
    return out


def cache_files(cache: str, group: str, artifact: str, version: str):
    """返回 (构件文件列表, .module 文件路径或 None)。Gradle 的缓存布局是
    <cache>/caches/modules-2/files-2.1/<group>/<artifact>/<version>/<sha1>/<file>。"""
    base = os.path.join(cache, "caches", "modules-2", "files-2.1", group, artifact, version)
    if not os.path.isdir(base):
        return [], None
    files, module = [], None
    for sub in os.listdir(base):
        d = os.path.join(base, sub)
        if not os.path.isdir(d):
            continue
        for name in os.listdir(d):
            p = os.path.join(d, name)
            if name.endswith(".module"):
                module = p
            elif not name.endswith((".pom", ".module")):
                files.append(p)
    return files, module


def declared_hashes(module_path: str, file_name: str) -> dict:
    """从 .module 里取该文件声明的散列（取第一个匹配的变体）。"""
    try:
        meta = json.load(open(module_path, encoding="utf-8"))
    except Exception:
        return {}
    for variant in meta.get("variants", []):
        for f in variant.get("files", []):
            if f.get("name") == file_name:
                return {a: f[a] for a in HASH_ALGOS if a in f}
    return {}


def digest(path: str, algo: str) -> str:
    h = hashlib.new(algo)
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


# fetch 允许访问的主机白名单：与上方 REPOS 的三个官方仓库一一对应。
# URL 虽由 REPOS 常量拼装、不含任何用户输入，仍在出口做完整校验（协议 / 主机白名单 /
# 解析后 IP 必须公网 / 限定重定向）——静态扫描把「变量进 urlopen」一律按 SSRF 处理
# （2026-10-02 被 L3 扫描拦下），且将来改 REPOS 时这里同步兜底。
ALLOWED_FETCH_HOSTS = frozenset({
    "repo1.maven.org",
    "dl.google.com",
    "maven.mozilla.org",
})


def _is_public_https_url(url: str) -> bool:
    """https + 白名单主机 + 解析出的**全部** IP 都是公网（拒内网/环回/链路本地/保留段）。"""
    try:
        parts = urllib.parse.urlsplit(url)
        if parts.scheme != "https":
            return False
        host = (parts.hostname or "").lower()
        if host not in ALLOWED_FETCH_HOSTS:
            return False
        for info in socket.getaddrinfo(host, 443, proto=socket.IPPROTO_TCP):
            if not ipaddress.ip_address(info[4][0]).is_global:
                return False
        return True
    except Exception:
        return False


class _StrictRedirectHandler(urllib.request.HTTPRedirectHandler):
    """重定向的每一跳都重新过一遍白名单 + 公网校验；不允许则中止（返回 None = 不跟随）。"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not _is_public_https_url(newurl):
            return None
        return super().redirect_request(req, fp, code, msg, headers, newurl)


_OPENER = urllib.request.build_opener(_StrictRedirectHandler())


def fetch(url: str, timeout: int = 25) -> str | None:
    if not _is_public_https_url(url):
        print("  ! 跳过非 https / 非白名单 / 非公网地址的请求：%s" % url)
        return None
    try:
        with _OPENER.open(url, timeout=timeout) as r:
            return r.read().decode().strip().split()[0]
    except Exception:
        return None


def main() -> int:
    _use_utf8_output()
    ap = argparse.ArgumentParser()
    ap.add_argument("--online", action="store_true", help="对无 .module 的构件联官网校验")
    ap.add_argument("--cache", default=os.environ.get("GRADLE_USER_HOME", os.path.expanduser("~/.gradle")))
    args = ap.parse_args()

    root = repo_root()
    vs = versions(root)
    modules_2 = os.path.join(args.cache, "caches", "modules-2")
    print("依赖完整性校验（只读）")
    print("  仓库根 : %s" % root)
    print("  Gradle : %s" % args.cache)
    if not os.path.isdir(modules_2):
        # 本机 GRADLE_USER_HOME 未必是 ~/.gradle（本项目在开发机上就另设了目录），
        # 故这里必须明说"没找对缓存"，而不是把它显示成"构件缺失"。
        print("  ⚠️  该目录下没有 caches/modules-2 —— 请用 --cache 指定真正的 GRADLE_USER_HOME")
    print()

    failures, unverifiable, ok = [], [], 0
    for key, coord, repo, suffix in TARGETS:
        version = vs.get(key)
        group, artifact = coord.split(":")
        if not version:
            unverifiable.append("%s（libs.versions.toml 里没有 %s）" % (coord, key))
            continue
        files, module = cache_files(args.cache, group, artifact, version)
        target = [f for f in files if f.endswith(suffix)]
        name = "%s:%s:%s" % (group, artifact, version)
        if not target:
            unverifiable.append("%s（缓存里找不到 %s 构件）" % (name, suffix))
            continue
        local = target[0]
        got = {a: digest(local, a) for a in HASH_ALGOS}

        want = declared_hashes(module, os.path.basename(local)) if module else {}
        if not want and args.online:
            url = "%s/%s/%s/%s/%s%s" % (
                REPOS[repo], group.replace(".", "/"), artifact, version,
                os.path.basename(local), "",
            )
            for a in ("sha256", "sha1"):
                v = fetch(url + "." + a)
                if v:
                    want = {a: v}
                    break
        if not want:
            why = "元数据里没写散列，且未启用 --online" if not args.online else "元数据与仓库均未提供散列"
            unverifiable.append("%s（%s）" % (name, why))
            print("  ?  %-52s %s" % (name, why))
            continue
        algo = next(a for a in HASH_ALGOS if a in want)
        if got[algo] == want[algo]:
            ok += 1
            print("  ✓  %-52s %s=%s…" % (name, algo, got[algo][:16]))
        else:
            failures.append(name)
            print("  ✗  %-52s %s 不一致！本地=%s… 公布=%s…" % (name, algo, got[algo][:16], want[algo][:16]))

    print()
    print("结果：一致 %d 项，不一致 %d 项，无法校验 %d 项" % (ok, len(failures), len(unverifiable)))
    for x in unverifiable:
        print("  · 无法校验：%s" % x)
    if failures:
        print("\n❗ 不一致项（当事故查：构件可能在下载后被改动）：")
        for x in failures:
            print("  · %s" % x)
        return 1
    # 「无法校验」单条不拦（缓存缺/仓库未提供散列在干净机器上确实会发生），
    # 但**一项都没能校验**就是另一回事：那意味着这道闸门这次什么都没验，
    # 亮绿灯等于装模作样 —— 按失败处理，逼人去查缓存路径或加 --online。
    if ok == 0 and TARGETS:
        print("\n❗ 一项都没能校验（ok=0）—— 校验链本次形同虚设，按失败处理。")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
