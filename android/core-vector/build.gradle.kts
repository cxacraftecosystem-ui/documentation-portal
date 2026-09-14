/*
 * VENDORED FROM `F:/Offline-Tracer/android/core-vector/build.gradle.kts`, WITH TWO DELIBERATE CHANGES.
 *
 * The source under `src/` is byte-for-byte upstream and must stay that way — `android/UPSTREAM-
 * MANIFEST-KOTLIN.txt` records a SHA-256 for every file and this build script's own digest, both
 * as vendored here and as it stands upstream. THIS FILE IS THE ONLY ONE OF THE FIVE IN THIS MODULE
 * THAT DIFFERS, and it differs in exactly two places, each with its own note below:
 *
 *     1. upstream       kotlin { jvmToolchain(17) }
 *        here          kotlin { compilerOptions { jvmTarget = JVM_17 } } + java { ...17 }
 *     2. here only     tasks.withType<KotlinCompile> { incremental = false }
 *
 * The first one, and why:
 *
 * WHY. `jvmToolchain(17)` tells Gradle to find and RUN a JDK 17. This machine has exactly one JDK,
 * Adoptium 21.0.12, so on 2026-08-27 the vendored form failed configuration outright:
 *
 *     Cannot find a Java installation on your machine matching this tasks requirements:
 *     {languageVersion=17, vendor=any, implementation=vendor-specific} for WINDOWS on x86_64.
 *       > No locally installed toolchains match and toolchain download repositories have not
 *         been configured.
 *
 * The two ways out of that are to let Gradle DOWNLOAD a JDK 17 (a toolchain resolver plugin in
 * `settings.gradle.kts`, i.e. a network fetch at configuration time, in the repository whose whole
 * premise is a handset that has been offline for a fortnight), or to compile ON the JDK that is
 * running the build and EMIT 17 bytecode. The second is what `:app` has always done —
 * `kotlinOptions.jvmTarget = "17"` with `compileOptions` at `JavaVersion.VERSION_17` — so this is
 * the module falling in line with the build it now belongs to, not a target change. The class-file
 * version produced is 61 either way; only the compiler that produces it moves, from 17 to 21.
 *
 * THE `java { }` BLOCK IS NOT DECORATION. Without it `targetCompatibility` defaults to the JDK
 * running the build (21) while Kotlin emits 17, and the Kotlin plugin fails the build with
 * "Inconsistent JVM-target compatibility detected". It is here to keep javac and kotlinc agreeing,
 * even though this module contains no `.java` sources at all.
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

/*
 * KOTLIN INCREMENTAL COMPILATION IS OFF FOR THIS MODULE, AND IT IS OFF BECAUSE THE BUILD FAILS
 * WITHOUT THAT — not as a precaution.
 *
 * On 2026-08-27, on Windows 11 with Gradle 8.9 / Kotlin 2.0.21 / Adoptium 21.0.12, the first
 * compile of these modules failed three times out of four from a deleted `build/` directory, each
 * time like this:
 *
 *     e: Daemon compilation failed: null
 *     Caused by: java.lang.AssertionError: java.lang.Exception: Could not close incremental caches
 *       in <module>\build\kotlin\compileKotlin\cacheable\caches-jvm\jvm\kotlin:
 *       class-fq-name-to-source.tab, proto.tab, internal-name-to-source.tab
 *     Using fallback strategy: Compile without Kotlin daemon
 *     > Could not delete '<module>\build\kotlin\compileKotlin\cacheable\caches-jvm'
 *
 * The Kotlin daemon cannot close its own memory-mapped `.tab` caches, the fallback compiler then
 * cannot delete them, and the task fails. It is FLAKY rather than certain — one run in four got
 * through — which is the worst possible shape for a build failure, because a retry sometimes
 * "fixes" it and the next person inherits it anyway. With `incremental = false` the same command
 * from the same clean state went green.
 *
 * WHY THIS IS AN ACCEPTABLE PLACE TO GIVE UP INCREMENTAL COMPILATION. These sources are VENDORED
 * and are required to stay byte-for-byte identical to upstream, so the case incremental
 * compilation exists to serve — edit one file, rebuild only that file — is the case that must not
 * happen here. What it would buy is a faster rebuild of code nobody is allowed to edit. Full
 * compilation of all four modules from cold takes about a minute and a half on this machine.
 *
 * This is scoped to this module deliberately. `kotlin.incremental=false` in `gradle.properties`
 * would have been one line instead of four, and it would have taken incremental compilation away
 * from `:app` as well — where sources DO change every day and the setting would be paid for on
 * every build.
 */
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    incremental = false
}

dependencies {
    implementation(project(":core-imaging"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}
