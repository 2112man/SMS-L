plugins {
    id("com.android.application") version "9.2.1" apply false
    // 显式指定 Kotlin：AGP 9 内置的 2.2.10 读不了 Miuix（Kotlin 2.4.x 编译）的元数据，
    // 因此关掉内置 Kotlin（见 gradle.properties），改用与依赖匹配的版本。
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    // 版本必须与上面的 Kotlin 一致，否则编译器版本不匹配。
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}

