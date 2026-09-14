plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // The vendored trace engine (:core-imaging, :core-vector, :core-pipeline, :core-export) is
    // plain Kotlin/JVM, not Android — see the block in settings.gradle.kts for why. Same 2.0.21 as
    // every other Kotlin plugin here, and the same version upstream pins, so the four modules and
    // :app are compiled by one compiler rather than two.
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
}
