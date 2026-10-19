plugins {
    // AGP 9+ has built-in Kotlin support; the org.jetbrains.kotlin.android plugin
    // must NOT be applied alongside it.
    id("com.android.application") version "9.4.1" apply false
}
