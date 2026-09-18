// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        // AGP 9.x 内置 Kotlin 运行时依赖 KGP；
        // 官方机制：把 KGP 显式升到更高版本即可让内置 Kotlin 编译器跟随升级（此处 2.4.10）
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
}