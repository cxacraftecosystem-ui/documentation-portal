package com.fieldrepository.app.data

/**
 * A [TokenStore] THAT WAS NEVER OPENED — the one copy of the awkward line every suite that drives a
 * real [FieldRepository] needs.
 *
 * ── WHY THIS FILE EXISTS NOW AND NOT BEFORE ─────────────────────────────────────────────────────
 *
 * The third suite. `QuestionnaireScopeTest` wrote it, `QuestionnaireArtisanOfferTest` copied it with
 * a pointer back ("See `QuestionnaireScopeTest.unopenedTokenStore` for why this exists"), and
 * `QuestionnaireMergeTest` is the one that makes it three — which is the exact condition
 * `ui/RepoSources.kt` records for lifting a duplicated test helper: *"If a third suite wants it,
 * that is the moment to lift it."* Unlike the comment-stripper that file also writes about, these
 * two copies have NOT drifted — they are the same five lines doing the same thing — so this is a
 * move and not a reconciliation, and nothing had to be re-verified against a winner.
 *
 * ── WHAT IT IS ─────────────────────────────────────────────────────────────────────────────────
 *
 * [FieldRepository] takes two collaborators and the second wraps `SharedPreferences`: its
 * constructor asks an Android `Context` for them. A plain JVM suite has no `Context` to give. This
 * module carries no Robolectric and no mocking framework (`app/build.gradle.kts` declares
 * `junit:junit` and nothing else for the JVM suite), so neither a fake nor a mock was on the table.
 *
 * NULL IS NOT REACHABLE, which is the first thing to try and the first thing that fails: Kotlin
 * emits `Intrinsics.checkNotNullParameter` on every public constructor, so a null second argument
 * throws inside `FieldRepository.<init>` however it is smuggled in — through a generic, through
 * reflection, through a cast. So the object is ALLOCATED WITHOUT RUNNING ITS CONSTRUCTOR instead: a
 * real `TokenStore` of the right type, with `preferences` never assigned. This is the same mechanism
 * every serialization library and every mocking framework uses to build an instance whose
 * constructor it cannot call.
 *
 * ── WHY IT IS SAFE, AND HOW THAT STAYS CHECKABLE ───────────────────────────────────────────────
 *
 * The bearer token is attached by an interceptor in `ApiClient`, not by this class;
 * [FieldRepository]'s constructor only stores the reference; and the methods these suites drive are
 * pass-throughs to Retrofit that never reach for the session. If an edit makes one of them read the
 * token store, the calling suite fails with an NPE naming the line. That is the correct outcome and
 * not a flake: a read that needs the session is not the read those files are describing.
 *
 * AND IF THE MECHANISM ITSELF EVER GOES AWAY — a JDK that removes `allocateInstance`, a module
 * system that closes `sun.misc` — this throws and every suite using it goes red. Loudly wrong is the
 * requirement (`ui/RepoSources.kt` argues it at length for the source-reading suites): a helper that
 * swallowed the failure and skipped would report parity on the one day nobody should believe it.
 */
internal fun unopenedTokenStore(): TokenStore {
    val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
    field.isAccessible = true
    val unsafe = field.get(null)
    val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
    return allocate.invoke(unsafe, TokenStore::class.java) as TokenStore
}
