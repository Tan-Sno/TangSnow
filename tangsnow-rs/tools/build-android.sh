#!/usr/bin/env bash
# 构建 tangsnow-core 的 Android .so（三个 ABI），输出到 bridge/jniLibs/。
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
if [[ "${1:-}" == "--debug" ]]; then PROFILE=""; fi

: "${ANDROID_NDK_HOME:?请先 export ANDROID_NDK_HOME=<NDK 根目录>}"
T="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"

export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$T/aarch64-linux-android26-clang"
export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="$T/armv7a-linux-androideabi26-clang"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$T/x86_64-linux-android26-clang"

# 16KB 页对齐（Android 15+ 新设备的硬要求）：
# NDK r27 链接器默认 max-page-size=4096，产出的 so 在 16KB 页内核的设备上
# 会被 PackageManager 在解析阶段直接拒绝（症状=「无法解析包」），r28 才改默认 16K。
# 显式链接参数让 p_align=16384，4KB 老设备向后兼容不受影响。三 ABI 统一加，无害。
export RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384"

for target in aarch64-linux-android armv7-linux-androideabi x86_64-linux-android; do
  cargo build $PROFILE --target "$target" --features jni
done

OUT=../bridge/jniLibs
mkdir -p "$OUT"/{arm64-v8a,armeabi-v7a,x86_64}
# 注意：Cargo workspace 的 target 在仓库根（tangsnow-rs/target/），不在成员目录
# tangsnow-core/target/——脚本已 cd 进 tangsnow-core，故 cp 用 ../target/ 前缀。
cp ../target/aarch64-linux-android/release/libtangsnow_core.so "$OUT/arm64-v8a/"
cp ../target/armv7-linux-androideabi/release/libtangsnow_core.so "$OUT/armeabi-v7a/"
cp ../target/x86_64-linux-android/release/libtangsnow_core.so "$OUT/x86_64/"

echo
echo "产物："
ls -lh "$OUT"/*/libtangsnow_core.so
echo
echo "下一步：把 bridge/jniLibs 挂进 Gradle（见 bridge/gradle-integration.gradle.kts），"
echo "并把 bridge/CoreNative.kt 拷入 app 源码树（包 io.github.tan_sno.tangsnow.core）。"
