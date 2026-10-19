#!/usr/bin/env bash
# AI生成
# 构建 tangsnow-core 的 Android .so（三个 ABI），输出到 dist/jniLibs/。
# （2026-10-15：dist/jniLibs 已 gitignore，这里只是**重编落点**；唯一进仓、进构建的副本
#   在 app/src/coreRust/jniLibs/ —— 末尾会把拷过去的命令打出来。）
#
# 方案：直连 NDK clang 包装器（不依赖 cargo-ndk）。
# 依赖：
#   1. Android NDK r25+，export ANDROID_NDK_HOME=<NDK 根目录>
#   2. Rust Android 目标：
#      rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
#
# 用法：./tools/build-android.sh            # release
#       ./tools/build-android.sh --debug    # debug（快，供开发）
#
# 注：cargo target 名是 armv7-linux-androideabi，而 NDK 包装器名带 a
#     （armv7a-linux-androideabi26-clang）——别混淆，r27 实测踩过。
set -euo pipefail
cd "$(dirname "$0")/../tangsnow-core"

PROFILE="--release"
PROFILEDIR="release"
if [[ "${1:-}" == "--debug" ]]; then PROFILE=""; PROFILEDIR="debug"; fi

: "${ANDROID_NDK_HOME:?请先 export ANDROID_NDK_HOME=<NDK 根目录>}"

# NDK 的 llvm prebuilt 目录名随**宿主**平台而变（linux-x86_64 / darwin-x86_64 / windows-x86_64）。
# 这里原先写死 `linux-x86_64`（本机就是那台 Linux 交叉编译环境，所以一直没暴露）：
# 换宿主时报错不是"链接失败"而是**找不到链接器**，且症状落到 cargo 里，指不回这一行。
# 现在按 `uname` 选、选不出来再看目录里实际有什么（2026-10-15 外部审查 L1）。
# 要手工指定：NDK_HOST_BIN=<prebuilt 平台目录>/bin。
PREBUILT_ROOT="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt"
if [[ -z "${NDK_HOST_BIN:-}" ]]; then
  case "$(uname -s)" in
    Linux*) WANT_HOST="linux-" ;;
    Darwin*) WANT_HOST="darwin-" ;;
    MINGW*|MSYS*|CYGWIN*) WANT_HOST="windows-" ;;
    *) WANT_HOST="" ;;
  esac
  matches=()
  for d in "$PREBUILT_ROOT"/*/; do
    [[ -d "$d" ]] || continue
    name="$(basename "$d")"
    if [[ -z "$WANT_HOST" ]] || [[ "$name" == "$WANT_HOST"* ]]; then matches+=("$name"); fi
  done
  if [[ ${#matches[@]} -eq 1 ]]; then
    NDK_HOST_BIN="$PREBUILT_ROOT/${matches[0]}/bin"
  elif [[ ${#matches[@]} -eq 0 ]]; then
    echo "在 $PREBUILT_ROOT 下找不到匹配宿主（uname=$(uname -s)）的 prebuilt 目录。" >&2
    echo "实际存在的目录：$(ls -1 "$PREBUILT_ROOT" 2>/dev/null | tr '\n' ' ')" >&2
    echo "（ANDROID_NDK_HOME 指对了吗？或直接用 NDK_HOST_BIN=<平台目录>/bin 指定）" >&2
    exit 1
  else
    echo "$PREBUILT_ROOT 下有多个候选 prebuilt 目录：${matches[*]} —— 用 NDK_HOST_BIN 明确指定哪一个" >&2
    exit 1
  fi
fi
T="$NDK_HOST_BIN"
[[ -d "$T" ]] || { echo "链接器目录不存在：$T" >&2; exit 1; }
echo "NDK 宿主 bin 目录：$T"

# 链接器：Linux/macOS 用 NDK 的 `*-clang`；**Windows 必须换成 `*.cmd`** ——
# Windows 包里这三组入口各有两份：无扩展名的那份是 **POSIX shell 脚本**，
# cargo 直接 exec 会得到 `os error 193`（"不是有效的 Win32 应用程序"），
# 且症状落在 cargo 的 linker 阶段、指不回这一行（2026-10-15 外部审查已记录，
# 2026-10-19 在此修）。`.cmd` 才是 NDK 给 Windows 的入口。
#
# 弯路记录（2026-10-19 实测，别再走）：
#   ① 直接选无扩展名脚本 → `os error 193`；
#   ② 改用 llvm 的 `clang.exe` + `CARGO_TARGET_<TRIPLE>_RUSTFLAGS="-C link-arg=--target=…"`
#      → **该变量未被 cargo 采用**（实测命令行里根本没出现 `--target=`，只剩全局
#      `RUSTFLAGS`），clang 于是按 host target 调 `lld`，报一串
#      `lld: error: unknown argument: -z / --version-script=…`；
#   ③ `.cmd` 包装器 → 一次通过（arm64 实测 17.35s Finished）。
# 教训：cross 链接的 target 信息应由**包装器**注入，不要指望 target 级 RUSTFLAGS。
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) CLANG_EXT=".cmd" ;;
  *) CLANG_EXT="" ;;
esac
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$T/aarch64-linux-android26-clang$CLANG_EXT"
export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="$T/armv7a-linux-androideabi26-clang$CLANG_EXT"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$T/x86_64-linux-android26-clang$CLANG_EXT"

# 16KB 页对齐（Android 15+ 新设备的硬要求）：
# NDK r27 链接器默认 max-page-size=4096，产出的 so 在 16KB 页内核的设备上
# 会被 PackageManager 在解析阶段直接拒绝（症状=「无法解析包」），r28 才改默认 16K。
# 显式链接参数让 p_align=16384，4KB 老设备向后兼容不受影响。三 ABI 统一加，无害。
export RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384"

for target in aarch64-linux-android armv7-linux-androideabi x86_64-linux-android; do
  cargo build $PROFILE --target "$target" --features jni
done

OUT=../dist/jniLibs
mkdir -p "$OUT"/{arm64-v8a,armeabi-v7a,x86_64}
# 注意：Cargo workspace 的 target 在仓库根（tangsnow-rs/target/），不在成员目录
# tangsnow-core/target/——脚本已 cd 进 tangsnow-core，故 cp 用 ../target/ 前缀。
# 目录名跟着 PROFILE 走：此前三行写死 `release/`，于是文档里的 `--debug` 用法
# （"快，供开发"）会在这一段直接 cp 失败 —— 编出来了却拷不出。
cp "../target/aarch64-linux-android/$PROFILEDIR/libtangsnow_core.so" "$OUT/arm64-v8a/"
cp "../target/armv7-linux-androideabi/$PROFILEDIR/libtangsnow_core.so" "$OUT/armeabi-v7a/"
cp "../target/x86_64-linux-android/$PROFILEDIR/libtangsnow_core.so" "$OUT/x86_64/"

echo
echo "产物（$PROFILEDIR）："
ls -lh "$OUT"/*/libtangsnow_core.so
echo
echo "下一步：把这三颗 .so 拷进 coreRust flavor 的 jniLibs（构建读的是那里，不是 dist/）："
echo "  for a in arm64-v8a armeabi-v7a x86_64; do"
echo "    cp ../dist/jniLibs/\$a/libtangsnow_core.so ../../app/src/coreRust/jniLibs/\$a/; done"
echo "Kotlin 侧的桥（CoreNative.kt / CoreBridge.kt）**已在** app/src/coreRust/java/io/github/"
echo "tan_sno/tangsnow/core/ 里，不需要再拷 dist/ 下的任何副本。签名是否两侧仍一致由"
echo "app/src/test 的 BridgeSignatureParityTest 钉（gradle 门禁跑它）；本机没有 android"
echo "target，所以这一步只在**能重建 .so 的环境**里有意义。"
