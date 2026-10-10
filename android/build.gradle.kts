plugins {
    id("com.android.application") version "9.4.1" apply false
    // NO `org.jetbrains.kotlin.android`, AND IT MUST NOT COME BACK. AGP 9 compiles Kotlin itself
    // ("built-in Kotlin"), and the Kotlin Android plugin is incompatible with AGP 9's DSL, so the two
    // cannot both be applied to :app. The compiler AGP drives is the Kotlin Gradle plugin on this
    // build's classpath: AGP 9.4.1 needs 2.2.10 or later, and the three declarations below put 2.4.21
    // there, which is the one version that resolves.
    //
    // The vendored trace engine (:core-imaging, :core-vector, :core-pipeline, :core-export) is plain
    // Kotlin/JVM, not Android — see the block in settings.gradle.kts for why. Same 2.4.21 as every
    // other Kotlin plugin here, so the four modules and :app are compiled by one compiler rather than
    // two. Upstream (F:/Offline-Tracer) pinned 2.0.21 when it was vendored; that drift is deliberate
    // and is recorded in android/UPSTREAM-MANIFEST-KOTLIN.txt.
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
}
