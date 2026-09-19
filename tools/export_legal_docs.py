#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把应用内法律文本导出为仓库内可外部阅读的 Markdown。

**单一事实来源仍是 `app/src/main/res/values*/strings.xml`** —— 本脚本只是导出，
不承担任何编辑职能。改文案请改 strings.xml，然后重跑：

    python tools/export_legal_docs.py

产出：
    docs/PRIVACY.md   隐私政策（中文 + English）
    docs/TERMS.md     用户协议（中文 + English）

依赖：Python 3 标准库，无第三方依赖。
"""

from __future__ import annotations

import io
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ZH = ROOT / "app/src/main/res/values/strings.xml"
EN = ROOT / "app/src/main/res/values-en/strings.xml"
LEGAL_KT = ROOT / "app/src/main/java/io/github/tan_sno/tangsnow/data/LegalDocs.kt"

DOCS = {
    "privacy_policy_text": ("docs/PRIVACY.md", "隐私政策", "Privacy Policy"),
    "user_agreement_text": ("docs/TERMS.md", "用户协议", "User Agreement"),
}


def unescape(raw: str) -> str:
    """Android strings.xml 的转义 → 纯文本。"""
    out = raw
    for a, b in (("\\n", "\n"), ("\\t", "\t"), ("\\'", "'"), ('\\"', '"'), ("\\@", "@")):
        out = out.replace(a, b)
    # XML 实体；&amp; 必须最后处理，否则会二次解码
    for a, b in (("&lt;", "<"), ("&gt;", ">"), ("&quot;", '"'), ("&apos;", "'"), ("&#39;", "'")):
        out = out.replace(a, b)
    return out.replace("&amp;", "&")


def extract(path: Path, key: str) -> str:
    text = io.open(path, encoding="utf-8").read()
    m = re.search(r'<string name="%s">(.*?)</string>' % re.escape(key), text, re.S)
    if not m:
        raise SystemExit("在 %s 中找不到 %s" % (path, key))
    return unescape(m.group(1)).strip()


def policy_version() -> str:
    m = re.search(r"POLICY_VERSION\s*=\s*(\d+)", io.open(LEGAL_KT, encoding="utf-8").read())
    return m.group(1) if m else "?"


def banner(zh_title: str, en_title: str, version: str, updated: str) -> str:
    return (
        "<!-- ⚠️ 本文件是自动导出产物，请勿直接编辑。\n"
        "     单一事实来源：app/src/main/res/values*/strings.xml\n"
        "     重新生成：python tools/export_legal_docs.py -->\n\n"
        "# %s / %s\n\n"
        "> 本文件与应用内「设置 → 关于棠雪」展示的法律文本一致，对应应用内 `POLICY_VERSION = %s`。\n"
        "> 最后更新：%s\n"
        "> **请勿直接编辑**；请修改 `strings.xml` 后运行 `python tools/export_legal_docs.py` 重新导出。\n\n"
        "**目录 / Contents**　[中文](#%s) · [English](#english)\n\n"
        "---\n\n" % (zh_title, en_title, version, updated, zh_title)
    )


def first_line_date(text: str) -> str:
    """文本首行的日期值（去掉「更新日期：」/「Last updated:」这类标签本身）。"""
    line = text.split("\n", 1)[0].strip()
    if "更新日期" in line:
        return line.split("：", 1)[-1].strip()
    if "Last updated" in line.lower():
        return line.split(":", 1)[-1].strip()
    return "（见正文）"


def main() -> int:
    ver = policy_version()
    for key, (rel, zh_title, en_title) in DOCS.items():
        zh_body = extract(ZH, key)
        en_body = extract(EN, key)
        # 英文首行形如 "Last updated: 2026-09-16"，中文首行形如 "更新日期：…"
        date_line = first_line_date(zh_body) or first_line_date(en_body)
        content = banner(zh_title, en_title, ver, date_line)
        # 正文首行已是更新日期，重复一次也无妨；保留全文以便逐字对照
        content += "## %s\n\n%s\n\n---\n\n## English\n\n%s\n" % (zh_title, zh_body, en_body)
        out = ROOT / rel
        out.write_text(content, encoding="utf-8")
        print("✓ %s  (%d 字符, POLICY_VERSION=%s)" % (rel, len(content), ver))
    return 0


if __name__ == "__main__":
    sys.exit(main())
