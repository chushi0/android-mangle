plugins {
    id("com.android.application") apply false
    // AGP 9 compiles Kotlin by itself and rejects this plugin.
    id("org.jetbrains.kotlin.android") apply false
}
