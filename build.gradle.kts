// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        // AGP 9.x 内置 Kotlin 运行时依赖 KGP；
        // 官方机制：把 KGP 显式升到更高版本即可让内置 Kotlin 编译器跟随升级。
        // 版本与插件坐标单一来源于 gradle/libs.versions.toml（CR-009），此处只引用别名。
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
}