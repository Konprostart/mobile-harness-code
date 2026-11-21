plugins {
    id("com.android.application") version "8.13.2" apply false
    // Kotlin is pinned to 2.0.21 to match Gradle 8.14's embedded Kotlin DSL (2.0.21);
    // a newer Kotlin Gradle Plugin makes the build script fail to compile with a
    // DefaultErrorMessages NoClassDefFoundError on the on-device toolchain.
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
