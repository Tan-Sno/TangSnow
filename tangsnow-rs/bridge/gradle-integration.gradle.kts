// =====================================================================
// tangsnow-core 接入 Gradle：把下面片段并入 app/build.gradle.kts
// =====================================================================

android {
    // 1) .so 来源：tools/build-android.sh 产出 bridge/jniLibs/<abi>/libtangsnow_core.so
    sourceSets["main"].jniLibs.srcDirs("${layout.projectDirectory}/../tangsnow-rs/bridge/jniLibs")
    // 若 tangsnow-rs 不在 app 同级，改成绝对路径或把 jniLibs 拷进 app/src/main/
}

// 2) 可选：让 Gradle 在每次构建前自动重编 Rust（需本机装好 NDK + cargo-ndk）。
//    CI 或无 NDK 环境可去掉该任务，只提交预构建的 .so。
//    ⚠️ GeckoView 纯 Kotlin/JVM 单测不需要 .so；CoreNative.available 已做降级。
val buildRust by tasks.registering(Exec::class) {
    workingDir("${layout.projectDirectory}/../tangsnow-rs")
    commandLine("bash", "tools/build-android.sh")
    // 仅在 Rust 源变更时重跑
    inputs.dir("tangsnow-core/src")
    inputs.file("tangsnow-core/Cargo.toml")
    outputs.dir("bridge/jniLibs")
}
tasks.named("preBuild") { dependsOn(buildRust) }

// 3) ABI 过滤（与现有发布口径一致）：
//    ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
