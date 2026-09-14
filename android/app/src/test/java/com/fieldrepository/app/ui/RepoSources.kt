package com.fieldrepository.app.ui

import java.io.File

/**
 * FINDING A FILE OF THIS REPOSITORY FROM A GRADLE TEST WORKER — the one copy of that walk.
 *
 * ── WHY THIS FILE EXISTS NOW AND NOT BEFORE ─────────────────────────────────────────────────────
 *
 * Four suites in this module read source off disk to hold two clients to one rule:
 * `WalkthroughStepsTest` (the web's `guide/steps.ts`), `WalkthroughSurfaceTest`
 * (`WalkthroughJourney.kt`), `RecordDictationParityTest` (`MainActivity.kt` against the web's forms)
 * and `RecordSwitcherTest` (`RecordSwitcher.kt` against `RecordSwitcher.tsx`). Every one of them
 * needs the same walk, and by the third the two existing copies had already drifted in signature —
 * one took a `vararg`, the other a single path, so only one of them could try both the bare and the
 * `../`-prefixed spelling at each level.
 *
 * THAT DRIFT WAS PREDICTED IN WRITING. `WalkthroughSurfaceTest` copied the helper deliberately and
 * left the condition for undoing it in its own comment — *"If a third suite wants it, that is the
 * moment to lift it"* — and `WalkthroughStepsTest` says the same thing harder: *"two copies of a
 * walk-up is how one of them later stops checking the candidate that mattered."* This is the fourth
 * suite. The copies are gone and the callers point here.
 *
 * ── MISSING IS A FAILURE, LOUDLY, AND NEVER A SKIP ──────────────────────────────────────────────
 *
 * The single most important line in this file. A source-reading test that quietly passed when it
 * could not find its subject would prove nothing on the day somebody MOVES that subject — and that
 * is precisely the day it is most needed, because its silence would be read as parity. The
 * `AssertionError` names every candidate and the directory the walk started from, so a red report
 * says what to fix rather than that something is missing.
 *
 * ── WHY EVERY CANDIDATE IS TRIED AT EVERY LEVEL ────────────────────────────────────────────────
 *
 * Not one candidate all the way up and then the next. The spellings differ by a `..`, so a walk that
 * exhausted the bare path first would find nothing until the repository root and then find it there
 * — the same answer, but only by luck of THIS repository's shape. A checkout with a `frontend/` one
 * level higher would resolve to the wrong tree and the test would go green against a file nobody is
 * shipping.
 *
 * The walk starts at `android/app` under a plain `./gradlew` run — this module declares no
 * `testOptions` and no `workingDir` — and climbs through `android/` to the repository root, which is
 * why bare `frontend/…` spellings resolve at all. The `../`-prefixed alternates are kept for the day
 * somebody adds a `workingDir`, and cost one `isFile` check each.
 */
internal fun repoFile(vararg relative: String): File {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
        for (path in relative) {
            val candidate = File(dir, path)
            if (candidate.isFile) return candidate
        }
        dir = dir.parentFile
    }
    throw AssertionError("none of ${relative.toList()} found from ${File(".").absolutePath}")
}

/**
 * [repoFile] read as UTF-8 text, WITH LINE ENDINGS NORMALISED.
 *
 * The normalisation is not cosmetic and this repository is the one where it bites: `.gitattributes`
 * and `core.autocrlf` mean the working tree on Windows can hold `\r\n` while the repository stores
 * `\n`. A Kotlin literal compared against a source line that differs only by a carriage return fails
 * with two strings that print IDENTICALLY in the report — an hour of staring at a diff that is not
 * there. The web's own `record-parity-fields-unit.spec.ts` answers it at the read for the same
 * reason. Nothing any of these suites assert is about line endings, so there is nothing to lose.
 */
internal fun repoSource(vararg relative: String): String =
    repoFile(*relative).readText(Charsets.UTF_8).replace("\r\n", "\n")
