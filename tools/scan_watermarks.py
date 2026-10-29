#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""水印/署名扫描门禁（2026-10-30 新增）——「全链路无署名」纪律的机械判据。

## 为什么要有它

2026-10-29 的署名清理轮覆盖了主仓（TangSnow），但仓外的壳仓（gecko-build/winembed-spike）
漏网三处：提交正文尾签、3 个源文件首行生成标记、活账本词面提及。人工轮次必然存在覆盖面
缺口——十月的「外部写入者」插标记事件也证明同一件事：**防线不能依赖任何人自觉，只能依赖
会红的机器**。本工具把清理轮的判据固化下来，CI 每次 push 都跑一遍。

## 扫描面

1. `git ls-files` 列出的全部跟踪文件（按行扫内容；二进制按空字节嗅探跳过）
2. git 元数据：HEAD 全部提交的 主题行/正文/作者/提交者（发布面就是 main 的历史）

## 模式（四类，与 2026-10-29 清理轮同一口径）

A 作者/模型标识 —— 小通 / xiaotong / jiuwen / 九文 / GLM-<n> / deepseek / zhipu / 智谱 /
                    claude / anthropic / chatgpt / openai / copilot / codeium / codebuddy…
B 生成标记     —— `AI生成` / `人工智能生成` / `由 AI …生成`（含 `# AI生成` 这族）
C git 尾签     —— Co-Authored-By / Signed-off-by
D 尾签括号     —— `[…]` 字面量与 `[小通…]` / `[GLM…]` / `[AI…]` / `[模型…]` 形态
E 隐形字符     —— ZWSP / ZWNJ / ZWJ / WORD JOINER / 软连字符 / 文件头 BOM /
                    同一个词内混排拉丁与希腊/西里尔字母（同形字伪装）

## 豁免（只有三类，其余一律拦）

- `apk` 词尾紧跟 U+2060：安全公告在演示 WORD JOINER 攻击样例（`evil.apk⁠` 绕过强确认），
  这类行是**安全内容本身**，拦了它门禁就没法留在仓库里。
- `tests/fixtures/` 路径下的隐形字符：共享 JSON 夹具（download_filename/url_idna 两族）
  里驻着 ZWNJ/ZWJ/ZWSP/软连字符——**净化器被测的就是这些字符**，属功能测试数据
  （与夹具里的 😀 同理）。A–D 四类模式在该路径下**仍然全拦**。
- 独立出现的希腊字母（如数学符号 Δ）：同形字检测按「同一个词内混排」判定，
  `Δ` 单独成词、`ΣΊΣΥΦΟΣ`/`ЖУК` 这类纯外文夹具词天然不命中，无需逐一豁免。

## 自身豁免

本文件自己**不入扫描面**：模式表源码里必然驻着全部字面量（自指悖论——2026-10-30
首跑实抓 41 处全是自己的模式表）。它没坏由 `--selftest` 与 verify_release ⑬ 保证；
改这张表时走 review，不走门禁。

## git 历史的口径

- 作者/提交者/主题行：**全部历史都扫**——署名进了作者字段无论多久都必须清。
- 正文：仅扫 2026-10-29（纪律定调日）之后的提交；此前的正文里有 5 处
  **描述「外部写入者插标记事件」的历史记录**（引用 `# AI生成` 字面值的修复说明），
  那是安全事件的时间线本身，不是标记——拦截它们等于逼人删历史。

## 用法

    python tools/scan_watermarks.py              # 扫当前仓；发现违例 exit 2
    python tools/scan_watermarks.py --selftest   # 判定逻辑自证（双向用例，不碰真仓）
    python tools/scan_watermarks.py <path>...    # 只扫指定文件（清污轮复查用）

exit code：0 = 干净；1 = 工具自身出错；2 = 发现违例。
"""

import os
import re
import subprocess
import sys

# ---------------------------------------------------------------- 模式表

# A/B/C/D 四类内容模式。顺序即报告顺序；新增模式时同步 --selftest 的正反用例。
PATTERNS = [
    (r'小通', 'A 作者/模型标识'),
    (r'xiaotong', 'A 作者/模型标识'),
    (r'jiuwen|九文', 'A 作者/模型标识'),
    (r'\bGLM[-_ ]?\d', 'A 作者/模型标识'),
    (r'\bglm-\d', 'A 作者/模型标识'),
    (r'deepseek', 'A 作者/模型标识'),
    (r'zhipu|智谱|bigmodel', 'A 作者/模型标识'),
    (r'claude|anthropic', 'A 作者/模型标识'),
    (r'chatgpt|openai', 'A 作者/模型标识'),
    (r'copilot|codeium|codebuddy|comate|marscode', 'A 作者/模型标识'),
    (r'qwen|通义|kimi|moonshot|doubao|豆包|ernie|文心|盘古', 'A 作者/模型标识'),
    (r'AI生成|人工智能生成|由\s*AI\s*(?:生成|辅助|创建)', 'B 生成标记'),
    (r'co-authored-by', 'C git 尾签'),
    (r'signed-off-by', 'C git 尾签'),
    (r'\[…\]', 'D 尾签括号'),
    (r'\[(?:小通|GLM|AI|模型)[^\]]{0,40}\]', 'D 尾签括号'),
]
COMPILED = [(re.compile(p, re.IGNORECASE), label) for p, label in PATTERNS]

# E 隐形字符。U+2060 的 `apk` 豁免见 _scan_line。
INVISIBLE = {'\u200b': 'ZWSP', '\u200c': 'ZWNJ', '\u200d': 'ZWJ',
             '\u2060': 'WORD JOINER', '\u00ad': '软连字符'}
MIXED_WORD = re.compile(
    r'\b(?=\w*[a-zA-Z])(?=\w*[\u0370-\u03ff\u0400-\u04ff])\w+\b')

# 纪律定调日（含当日）。此前的 git 正文豁免——见模块 docstring「git 历史的口径」。
DISCIPLINE_DATE = '2026-10-29'

MAX_BYTES = 3 * 1024 * 1024
TEXT_EXT = {'.kt', '.java', '.kts', '.xml', '.md', '.txt', '.py', '.sh', '.cmd',
            '.bat', '.ps1', '.json', '.toml', '.yaml', '.yml', '.properties',
            '.pro', '.cfg', '.gradle', '.html', '.css', '.js', '.mjs', '.c',
            '.h', '.cpp', '.rs', '.sql', '.svg', '.csv'}


class Violation(Exception):
    """发现违例。detail 面向「改哪里」，照现有门禁的口吻写。"""


# ---------------------------------------------------------------- 检测器

def scan_line(line):
    """单行检测。返回 [(label, 说明)]；豁免逻辑只在这一层生效。"""
    hits = []
    for rx, label in COMPILED:
        if rx.search(line):
            hits.append((label, '模式命中'))
    for ch, name in INVISIBLE.items():
        if ch in line:
            # U+2060 豁免：安全公告演示 .apk<WORD JOINER> 攻击样例的行。
            if ch == '\u2060' and re.search(r'apk\u2060', line):
                continue
            hits.append(('E 隐形字符', name))
    for m in MIXED_WORD.finditer(line):
        hits.append(('E 同形字混排', '词内混排拉丁与希腊/西里尔字母: %s' % m.group()))
    return hits


def scan_text(text, source):
    """整段文本检测，返回违例行列表 [(source, lineno, label, 说明)]。"""
    out = []
    if text.startswith('\ufeff'):
        out.append((source, 0, 'E 隐形字符', '文件头 BOM'))
    for i, line in enumerate(text.splitlines(), 1):
        for label, why in scan_line(line):
            out.append((source, i, label, why))
    return out


def _is_text(path):
    ext = os.path.splitext(path)[1].lower()
    if ext and ext not in TEXT_EXT:
        return False
    try:
        with open(path, 'rb') as f:
            head = f.read(8192)
    except OSError:
        return False
    return b'\x00' not in head


FIXTURE_DIR = re.compile(r'[\\/]tests[\\/]fixtures[\\/]')


def _is_self(path):
    """扫描器自身的模式表文件不入扫描面（自指豁免，见 docstring）。"""
    try:
        return os.path.samefile(path, os.path.abspath(__file__))
    except OSError:
        return False


def scan_paths(paths):
    """扫描显式给出的文件列表。tests/fixtures/ 下的隐形字符按功能测试数据豁免
    （A–D 四类模式在该路径仍全拦），见模块 docstring「豁免」。"""
    violations = []
    for p in paths:
        if _is_self(p):
            continue
        try:
            if os.path.getsize(p) == 0 or os.path.getsize(p) > MAX_BYTES:
                continue
            if not _is_text(p):
                continue
            with open(p, 'rb') as f:
                data = f.read()
            try:
                text = data.decode('utf-8')
            except UnicodeDecodeError:
                continue  # 非 UTF-8 文本不在本仓跟踪范围（GBK 产物不进库）
            if FIXTURE_DIR.search(p):
                text = text.replace('\u200b', '').replace('\u200c', '') \
                           .replace('\u200d', '').replace('\u2060', '') \
                           .replace('\u00ad', '').replace('\ufeff', '')
            violations.extend(scan_text(text, p))
        except OSError as e:
            raise Violation('无法读取 %s：%s' % (p, e))
    return violations


def _git(root, args):
    r = subprocess.run(['git'] + args, cwd=root, capture_output=True)
    if r.returncode != 0:
        raise Violation('git %s 失败：%s' % (' '.join(args), r.stderr.decode('utf-8', 'replace')[:300]))
    return r.stdout.decode('utf-8', 'replace')


def scan_repo(root):
    """扫描 root 仓库：跟踪文件 + HEAD 历史的 git 元数据。"""
    violations = []

    tracked = _git(root, ['ls-files', '-z']).split('\0')
    violations.extend(scan_paths([
        os.path.join(root, t) for t in tracked if t and not _is_self(os.path.join(root, t))]))

    # git 元数据：作者/提交者/主题行全历史都扫；正文只扫纪律日之后的。
    raw = _git(root, ['log', '--all', '--date=short',
                      '--format=%H%x1f%ad%x1f%an%x1f%ae%x1f%cn%x1f%ce%x1f%s%x1f%b%x1e'])
    for entry in raw.split('\x1e'):
        entry = entry.strip('\n')
        if not entry:
            continue
        fields = entry.split('\x1f')
        if len(fields) < 7:
            continue
        sha, adate, aname, aemail, cname, cemail, subject = fields[:7]
        body = fields[7] if len(fields) > 7 else ''
        ident = '%s <%s> / %s <%s>' % (aname, aemail, cname, cemail)
        for label, why in scan_line('%s %s' % (aname, aemail)) + \
                scan_line('%s %s' % (cname, cemail)):
            violations.append((sha[:10] + ' (身份)', 0, label, ident))
        for label, why in scan_line(subject):
            violations.append((sha[:10] + ' (主题)', 0, label, subject[:120]))
        if adate >= DISCIPLINE_DATE:
            for source, lineno, label, why in scan_text(body, sha[:10] + ' (正文)'):
                violations.append((source, lineno, label, why))
    return violations


# ---------------------------------------------------------------- 自证

def selftest():
    """判定逻辑双向自证：该拦的拦、该放的放。直接测检测器，不碰真仓。"""
    bad = []

    def expect_violation(name, text, label_part):
        hits = scan_text(text, 'selftest')
        if not any(label_part in label for _, _, label, _ in hits):
            bad.append('%s：应拦未拦' % name)

    def expect_clean(name, text):
        hits = scan_text(text, 'selftest')
        if hits:
            bad.append('%s：应放行却拦（%s）' % (name, hits[:2]))

    # —— 该拦 ——
    expect_violation('尾签正文', 'some change\n\n[小通/GLM-5.2]', 'D 尾签括号')
    expect_violation('生成标记', '# AI生成', 'B 生成标记')
    expect_violation('生成标记(中文)', '由 AI 生成', 'B 生成标记')
    expect_violation('Co-Authored', 'Co-Authored-By: someone <a@b.c>', 'C git 尾签')
    expect_violation('Signed-off', 'Signed-off-by: x', 'C git 尾签')
    expect_violation('模型名', 'powered by DeepSeek', 'A 作者/模型标识')
    expect_violation('模型名(中文)', '智谱清言', 'A 作者/模型标识')
    expect_violation('尾签括号(字面)', 'change […]', 'D 尾签括号')
    expect_violation('ZWSP', 'hidden\u200bhere', 'E 隐形字符')
    expect_violation('软连字符', 'id\u00adentity', 'E 隐形字符')
    expect_violation('同形字混排', 'Cliеnt root', 'E 同形字混排')  # е 是西里尔
    expect_violation('文件头 BOM', '\ufeffhello', 'E 隐形字符')

    # —— 该放行（豁免表逐条对应模块 docstring）——
    expect_clean('U+2060 攻击样例豁免', 'evil.apk\u2060 挂不可见尾巴绕过强确认')
    expect_clean('独立希腊字母 Δ', 'displayForUrlImpl 在三个 ABI 上都是最大 Δ')
    expect_clean('纯外文夹具词', 'ΣΊΣΥΦΟΣ 与 ЖУК 是书签搜索夹具')
    expect_clean('正常文本', 'fix(app): 内核兜底防二次崩溃')
    expect_clean('正常注释', '升级 AGP 后门禁全绿，见 verify_release.py ⑦')

    # —— 路径级豁免（tests/fixtures/ 的隐形字符 = 功能测试数据；A–D 模式仍拦）——
    import tempfile
    with tempfile.TemporaryDirectory() as td:
        fx = os.path.join(td, 'tests', 'fixtures')
        os.makedirs(fx)
        probe = '{"case": "a\u200bb"}'
        with open(os.path.join(fx, 'x.json'), 'w', encoding='utf-8') as f:
            f.write(probe)
        if scan_paths([os.path.join(fx, 'x.json')]):
            bad.append('夹具路径豁免：应放行却拦')
        with open(os.path.join(fx, 'y.json'), 'w', encoding='utf-8') as f:
            f.write(probe + '\nxiaotong')
        if not scan_paths([os.path.join(fx, 'y.json')]):
            bad.append('夹具路径下 A 类模式：应拦未拦')
        plain = os.path.join(td, 'x.json')
        with open(plain, 'w', encoding='utf-8') as f:
            f.write(probe)
        if not scan_paths([plain]):
            bad.append('非夹具路径隐形字符：应拦未拦')

    if bad:
        for b in bad:
            print('  FAIL  %s' % b)
        raise SystemExit('自证失败 %d 项' % len(bad))
    print('水印扫描自证：全部通过（拦截 %d 例 / 放行 %d 例 / 路径豁免 3 例）'
          % (12, 5))


# ---------------------------------------------------------------- 入口

def main(argv):
    if '--selftest' in argv:
        selftest()
        return 0
    try:
        root = os.path.abspath(
            subprocess.run(['git', 'rev-parse', '--show-toplevel'], capture_output=True)
            .stdout.decode().strip())
    except Exception:
        root = os.getcwd()
    try:
        if argv:
            violations = scan_paths(argv)
        else:
            if not os.path.isdir(os.path.join(root, '.git')):
                print('错误：当前目录不在 git 仓内，且未给出文件参数。', file=sys.stderr)
                return 1
            violations = scan_repo(root)
    except Violation as e:
        print('❌ %s' % e)
        return 1

    if violations:
        print('❌ 发现 %d 处署名/水印违例 —— 这是对外仓库，纪律见 RELEASING-internal.md §5：' % len(violations))
        for source, lineno, label, why in violations[:30]:
            loc = '%s:%s' % (source, lineno) if lineno else source
            print('   %s  [%s] %s' % (loc, label, why))
        if len(violations) > 30:
            print('   …另有 %d 处' % (len(violations) - 30))
        return 2
    print('✅ 水印/署名扫描：干净（跟踪文件 + git 元数据，四类模式 + 隐形字符，豁免 3 类）')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
