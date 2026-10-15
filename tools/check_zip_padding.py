#!/usr/bin/env python3
"""check_zip_padding.py — APK 本地头 padding 门禁（2026-10-08 安装失败攻坚产物）。

背景：AGP 为 16KB 页设备做 zip 层 16K 对齐时，把对齐 padding（~14KB）写进 .so 条目的
local header extra field。Android 安装器的 C++ libziparchive 在提取阶段对此校验失败，
返回 -113，用户端 UI 表现为「解析包出现问题」。Java 层（ZipFile/aapt2/apksigner）
全部读得动 —— 这就是静态检查全绿却装不上的盲区。

门禁规则：lib/*.so 条目的 local header extra 长度 > PADDING_LIMIT 即失败。
  4K 对齐 padding ≤ 4095；官方包实测 1878；16K padding 实测 ~14000。
  阈值取 8192：正常 4K padding 不会触碰，16K padding 必然命中。

用法：check_zip_padding.py <apk路径> [<apk路径> ...]
退出码：0 = 通过；1 = 存在超限 padding 或读包失败。
"""
import struct
import sys
import zipfile

PADDING_LIMIT = 8192


def local_extra_len(path: str, header_offset: int) -> int:
    with open(path, "rb") as f:
        f.seek(header_offset)
        hdr = f.read(30)
    if len(hdr) < 30 or hdr[:4] != b"PK\x03\x04":
        raise ValueError(f"local header 签名异常 @ {header_offset}")
    return struct.unpack("<H", hdr[28:30])[0]


def check(apk: str) -> bool:
    ok = True
    try:
        zf = zipfile.ZipFile(apk)
    except Exception as e:  # noqa: BLE001
        print(f"[PAD-FAIL] {apk}: 无法打开 ({e})")
        return False
    so_items = [i for i in zf.infolist() if i.filename.startswith("lib/") and i.filename.endswith(".so")]
    if not so_items:
        print(f"[PAD-WARN] {apk}: 无 lib/*.so 条目（分包配置确认）")
    for i in so_items:
        try:
            elen = local_extra_len(apk, i.header_offset)
        except Exception as e:  # noqa: BLE001
            print(f"[PAD-FAIL] {apk}: {i.filename} — {e}")
            ok = False
            continue
        ctype = "stored" if i.compress_type == 0 else "deflate"
        status = "OK" if elen <= PADDING_LIMIT else "FAIL"
        if elen > PADDING_LIMIT:
            ok = False
            print(f"[PAD-FAIL] {apk}: {i.filename} local extra = {elen}B > {PADDING_LIMIT}B"
                  f"（16K 对齐 padding 特征，安装器 libziparchive 会拒收）[{ctype}]")
        else:
            print(f"[PAD-OK]   {apk}: {i.filename} local extra = {elen}B [{ctype}]")
    return ok


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    all_ok = all(check(p) for p in sys.argv[1:])
    print("结论：", "PASS" if all_ok else "FAIL —— 不得交付，参考 app/build.gradle.kts 的 useLegacyPackaging 注释")
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
