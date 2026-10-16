package com.fieldrepository.app.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fieldrepository.app.data.UserDto

/*
 * ─────────────────────────────────────────────────────────────────────────────────────────────────
 * THE WALKTHROUGH'S WINDOW, THE FLAG THAT OPENS IT ONCE, AND NOTHING ELSE.
 *
 * Three files, one feature. `WalkthroughSteps.kt` holds the words. `WalkthroughJourney.kt` holds the
 * experience — the scroll, the spine, the cards. This one holds the WINDOW they are drawn in, the
 * exits, and the device-local "you have seen this" flag. The split is deliberate and it is the
 * reason the step list can be read by a JVM unit test that never stands up a composition — a step
 * list nothing can see is a step list that drifts, and the twelve steps these files replace drifted
 * seven behind the web without one thing in either client noticing.
 *
 * ── WHAT CHANGED HERE, AND WHY THE WINDOW DID NOT ────────────────────────────────────────────────
 *
 * This used to be a deck: an `ElevatedCard` with one step on it and a Next button. Every fact on it
 * was right and it still was not the web's walkthrough, because the web's is a journey you scroll
 * down with a spine filling beside you, and a paged card cannot say "these are in an order and you
 * are somewhere in it" — see the argument at the top of `WalkthroughJourney.kt`, which is where that
 * whole surface now lives.
 *
 * What was fighting the journey was the CARD, not the Dialog. The window here was already
 * `usePlatformDefaultWidth = false`, and a Dialog is its own window: its content is the root of a
 * fresh composition subtree with no scrolling parent, so a full-height scroller inside it is bounded
 * by the window and is perfectly legal — the same reason `MediaPlayers` puts a `fillMaxSize` Box in
 * one and `SearchableSelect` puts a `LazyColumn` in a modal sheet. So the `ElevatedCard` became a
 * `Surface(fillMaxSize())` and everything below this comment stayed exactly as it was.
 *
 * ⚠ AND THE WINDOW IS DELIBERATELY LEFT FITTING THE SYSTEM DECOR. `decorFitsSystemWindows = false`
 * exists on the classpath and `DwQrLiveScanner` uses it; the activity window behind this dialog has
 * paid its system-bar insets once, at its root, since 2026-10-09 (`SystemBarsInsetsRoot` in
 * `Theme.kt`, because targetSdk 37 leaves it no opt-out from edge to edge). A Dialog is a window of
 * its own and that root does not reach it, so going edge-to-edge HERE would draw the pinned header
 * under the status bar and Skip under the gesture pill on every tall handset unless this file paid
 * the insets itself — and fitting the decor already gets that right for nothing.
 *
 * ── WHY THIS IS A DIALOG AND NOT A `Screen` ──────────────────────────────────────────────────────
 *
 * It is tempting to give onboarding a screen of its own, and it would be wrong. `MainActivity`'s
 * `navigate` puts an unsaved-changes guard in front of every destination, and it EXEMPTS the
 * walkthrough by name, because the walkthrough draws OVER the page you were already on and takes
 * nothing away from it. Turn this into a `Screen` and re-opening the walkthrough from the menu — the
 * thing a designer does when they are halfway through an artisan form and cannot remember whether
 * Do's and Don'ts is one lesson per line — starts asking whether to throw the form away. It would
 * also cost three more exhaustive `when` tables (`headerTitle`, `currentDestination`, `goBack`) for
 * a surface that has no back stack of its own.
 *
 * So: `NavDestination.WALKTHROUGH` sets a boolean, this dialog reads it, and the page underneath is
 * untouched the whole time. That is also what makes the "where do Skip and Done land?" question have
 * one answer instead of two — see [WalkthroughDialog].
 *
 * ── NOTHING HERE AWAITS A REQUEST ────────────────────────────────────────────────────────────────
 *
 * No repository, no coroutine scope, no suspend call, no `LaunchedEffect` that fetches. The steps are
 * a compiled-in `listOf`, the icons ship inside the APK, the flag is an XML file in this app's own
 * `shared_prefs/`, and the destination buttons hand a `NavDestination` to a router that is already in
 * memory. This has to be true rather than merely likely: the fortnight this walkthrough describes
 * happens in courtyards with no bar of signal, and an onboarding screen that spins on first launch is
 * an onboarding screen a new designer force-quits.
 * ─────────────────────────────────────────────────────────────────────────────────────────────────
 */

// ---------------------------------------------------------------------------------------------
// The "you have seen this" flag
// ---------------------------------------------------------------------------------------------

/*
 * THE SAME SHARED-PREFERENCES FILE `MainActivity` ALREADY OWNS, NAMED AGAIN RATHER THAN IMPORTED.
 *
 * `MainActivity.kt` declares `APP_PREFS_NAME = "fieldrepo_prefs"` private to itself and keeps the
 * AI-cost acknowledgement in it. Moving that constant here to share it would drag two unrelated
 * helpers across a file boundary for no gain, so the literal is repeated — with this comment, which
 * is the whole price of repeating it.
 *
 * DO NOT "TIDY" THIS NAME. It is pre-rebrand and it stays pre-rebrand. `getSharedPreferences` with a
 * name no file has yet does not fail: it hands back an empty document. So renaming this string would
 * not migrate one stored flag, it would silently re-show the walkthrough to every installed user on
 * the update that renamed it. `AppPreferencesStore` and `TokenStore` carry the same warning over
 * their own file names, where the cost is a lost theme and a forced sign-out respectively.
 */
private const val WALKTHROUGH_PREFS = "fieldrepo_prefs"
private const val PREF_WALKTHROUGH_SEEN = "walkthrough_seen"

/**
 * True once this device's user has finished, skipped or backed out of the walkthrough at least once.
 *
 * DEVICE-LOCAL, AND THAT IS THE INTENDED SCOPE RATHER THAN A SHORTCUT. Making it per-account would
 * mean a fifth value on the `/preferences/me` contract — `ApiModels`, `WorkshopRepository`,
 * `AppearanceScreen` and the backend — to remember something about a tour of THIS handset's menu.
 * The consequence is written down so nobody reports it as a bug: a designer who signs in on a second
 * phone is shown it again there, and signing out and back in on the same phone is not.
 *
 * Reading it is a synchronous parse of a two-key XML file, and that is what makes it safe to read
 * while deciding the first screen rather than a frame afterwards — see the call site in
 * `MainActivity.HomeScreen`.
 *
 * IT IS A FIRST TOUCH OF THAT FILE, NOT A FREE LOOKUP AT ONE ALREADY IN MEMORY, and an earlier draft
 * of this paragraph claimed the opposite. `fieldrepo_prefs` has exactly one other reader —
 * `aiCostReminderSuppressed`, which nothing calls until somebody presses "Refine transcript" — and
 * `AppPreferencesStore` is a DIFFERENT file (`field_repository_preferences`). So this call is what
 * opens `fieldrepo_prefs`, and `getBoolean` waits on the load. What makes that acceptable is not that
 * somebody else paid for it: it is that the file holds two booleans, and that the app already makes
 * exactly this trade one layer up, where `AppPreferencesStore.read()` is deliberately synchronous in
 * `onCreate` because deciding the theme asynchronously flashes a light app at somebody who chose
 * Dark. Do not "fix" this by moving it to a coroutine — that reintroduces the frame of dashboard the
 * `remember` initialiser was moved here to remove.
 */
internal fun walkthroughSeen(context: Context): Boolean =
    context.getSharedPreferences(WALKTHROUGH_PREFS, Context.MODE_PRIVATE)
        .getBoolean(PREF_WALKTHROUGH_SEEN, false)

/**
 * Remember that the walkthrough has been shown, for good, on this device.
 *
 * `apply()` and not `commit()`: the write is handed to the background thread and the in-memory value
 * is updated at once, so a [walkthroughSeen] call on the very next frame already reads true. A
 * `commit()` here would block the UI thread on a disk write at the exact moment the designer is
 * trying to leave a dialog.
 *
 * EVERY EXIT CALLS THIS, WITHOUT EXCEPTION. A Skip that closes the dialog without writing the flag is
 * the single most common defect this feature has, on every product that has ever shipped one: it
 * looks completely correct until tomorrow morning, when the walkthrough opens again over the
 * dashboard of somebody who has already said no to it. [WalkthroughDialog] is built so that there is
 * exactly one exit lambda and no second path that could forget.
 */
internal fun markWalkthroughSeen(context: Context) {
    context.getSharedPreferences(WALKTHROUGH_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(PREF_WALKTHROUGH_SEEN, true)
        .apply()
}

// ---------------------------------------------------------------------------------------------
// Motion
// ---------------------------------------------------------------------------------------------

/**
 * Whether the page turn should move, reading BOTH switches that get a vote.
 *
 * ── THE APP'S OWN SWITCH, WHICH IS THE ESTABLISHED CONVENTION ────────────────────────────────────
 *
 * `LocalAppPreferences.current.reducedMotion` is read at ten sites already — the island bar, the
 * media carousel, the gallery floor, the sketch panels, the stage screen, the scanner. It is the
 * convention and this file follows it rather than inventing a second one. `AppNavigation` states the
 * rule it enforces: "Stops animations and transitions" is a promise the user made the app.
 *
 * ── THE DEVICE'S OWN SWITCH, WHICH THIS FILE IS THE FIRST TO READ ────────────────────────────────
 *
 * `Settings.Global.ANIMATOR_DURATION_SCALE` — Developer options › "Animator duration scale", and the
 * setting an accessibility service or a battery saver turns to zero on the user's behalf — is read by
 * NO other Kotlin in this application. Compose's own recomposer installs a `MotionDurationScale` from
 * it, so `AnimatedContent` does collapse when the platform says zero, and in practice the framework
 * has been carrying this for the whole app. But the Accessibility card on `AppearanceScreen` promises
 * in as many words that "Your device's own reduce-motion setting is always honoured too", and a
 * promise that rests entirely on a framework default is a promise nothing in this repository asserts.
 * So this one composable performs the OR explicitly, and I am writing down that I introduced it here:
 * it is a NEW convention in this codebase, it currently has exactly one caller, and if a second
 * screen wants it the right move is to lift this function to a shared place rather than to copy the
 * `Settings.Global` read. Copying it is how the two would drift.
 *
 * ── WHAT EACH SWITCH ACTUALLY PRODUCES, BECAUSE THE TWO ARE NOT THE SAME REQUEST ─────────────────
 *
 * True from either source sends [WalkthroughJourney] down its reduced-motion branch, which is not one
 * decision but nine: the travelling node stops being drawn, the reveal stops rising, the bubble stops
 * growing, the chevron and the detail panel `snap()`, and a programmatic scroll becomes a jump
 * because SMOOTH SCROLLING IS MOTION TOO. The one thing that does not become instant is the header's
 * active-step swap, which stays a 90 ms cross-fade and NOT a `snap()` — the app's settled position,
 * argued in `DwMediaCarousel`: reduced motion asks for no MOVEMENT, not for no change at all, and an
 * instant substitution of a line of text reads as a rendering glitch rather than as a change. When
 * the DEVICE is the one asking, that fade then collapses to nothing anyway without a line of code
 * here: Compose installs a `MotionDurationScale` from this very setting, so `tween(90)` resolves to
 * zero duration and the words swap instantly. So "the app's own switch" means no movement, and "the
 * platform says zero" means no transition at all, which is what each of them asked for.
 *
 * Read once per [Context] rather than on every recomposition: this is a `ContentResolver` round trip
 * to the settings provider, and a page-turn animation is not worth an IPC per frame. The cost of that
 * choice is that flipping the developer-options slider while this dialog is open does not take effect
 * until it is reopened, which is the correct trade for a surface a designer sees once.
 */
@Composable
private fun walkthroughReduceMotion(): Boolean {
    val context = LocalContext.current
    val osScale = remember(context) {
        // Defaults to 1f — motion allowed — on any device that cannot answer. A phone whose settings
        // provider throws must not silently lose its animations; the app's own switch still applies.
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        }.getOrDefault(1f)
    }
    return LocalAppPreferences.current.reducedMotion || osScale == 0f
}

// ---------------------------------------------------------------------------------------------
// The screen this step teaches
// ---------------------------------------------------------------------------------------------

/**
 * The menu row a step's [WalkStep.destination] opens, by its own name.
 *
 * DERIVED FROM [FIELD_NAV_ITEMS] AND NEVER TYPED OUT HERE. `NavEntry.label` carries the contract that
 * it is the exact web label and that neither client may reword its side alone; a button in this
 * dialog reading anything else would be a third spelling of a screen's name, sitting on the one
 * surface whose entire job is to teach a newcomer what things are called. Deriving it also means the
 * button and the drawer row a designer then goes looking for say the same words by construction.
 *
 * Null only if a destination is ever dropped from the menu while a step still points at it — the
 * caller falls back to a generic label rather than rendering a button with no words on it.
 */
private fun walkthroughOpenLabel(destination: NavDestination): String? =
    FIELD_NAV_ITEMS.firstOrNull { it.destination == destination }?.label

// ---------------------------------------------------------------------------------------------
// The dialog
// ---------------------------------------------------------------------------------------------

/**
 * The walkthrough's window: a full-bleed dialog with [WalkthroughJourney] scrolling inside it.
 *
 * ── WHICH DECK IT OPENS ON, AND WHY THE CHOICE LIVES HERE ────────────────────────────────────────
 *
 * There are two walkthroughs in this APK and `walkthroughDeckFor` picks which one OPENS from the
 * signed-in account's role. IT IS A DEFAULT AND NOT A GATE, exactly as `tracks.ts` rules for the web
 * — every deck is in the build for everybody, whichever one opens offers the others on its first
 * card, and a wrong answer costs the reader one tap. The walkthrough's own menu row stays ungated
 * for all eleven tiers, which is what `WalkthroughSurfaceTest` asserts role by role and what this
 * must not quietly become a second opinion about.
 *
 * The CHOICE is state and the state is here, one level above the journey, for the same reason the
 * seen flag is: the window is the thing that outlives a scroll position. Held in a `remember` keyed
 * on the account's role rather than recomputed per composition, so that a reader who switched to the
 * other deck stays on it while they read — recomputing would snap them back to the role's default on
 * the next recomposition, which on a scrolling surface is every few frames.
 *
 * THE SIGNATURE GREW EXACTLY ONE PARAMETER, AND THE SIX THINGS IT WAS GUARDING STAYED PUT.
 * `MainActivity` is seventeen thousand lines long and holds six things that have to keep agreeing
 * about this dialog — the first-run gate inside a `remember` initialiser, the router arm, the
 * `navigate` exemption, the Settings row, the dashboard button and the `pendingUpdate == null`
 * ordering guard that keeps this from burying a required-update prompt. Replacing the entire surface
 * behind an identical two-lambda signature is what let all six of them stay exactly as they were
 * when this became a journey, and that ordering guard is MORE load-bearing now that this fills the
 * screen rather than floating over the middle of it: do not touch it.
 *
 * ⚠ THE PARAGRAPH HERE USED TO END "do not give this composable a parameter that would make somebody
 * have to", AND [user] IS THAT PARAMETER. It is added anyway, and the test the rule was protecting is
 * the reason it is safe rather than an exception to it: NONE of those six is touched by it. All six
 * are statements about WHEN this dialog is shown and what happens when it closes; [user] is about
 * WHICH deck it opens on, which is a question none of them asks. The one line that changes is the
 * call site's own argument list, and `WalkthroughSurfaceTest` still reads both lambdas out of the
 * same slice. What the rule was actually refusing is a parameter that forces the first-run gate, the
 * exemption or the ordering guard to be rewritten to supply it; a value that is already in scope at
 * the call site is not that.
 *
 * ── ONE EXIT LAMBDA, ON PURPOSE ──────────────────────────────────────────────────────────────────
 *
 * [onFinish] is called by Skip, by Done, by the system back gesture with nothing open, and by a
 * dismissal from outside. There is no second way out and no second lambda, because the requirement that
 * "Skip and Done must both clear the flag and land in the same place" is only enforceable if the two
 * are literally the same call. Two lambdas doing the same two things is exactly the shape in which
 * one of them later loses the `markWalkthroughSeen`, and the symptom — the walkthrough reappearing
 * tomorrow for somebody who dismissed it — does not show up until tomorrow.
 *
 * Where they land is not a routing decision at all: this dialog draws over whatever screen was
 * already there, so closing it reveals that screen. The dashboard on first run, the half-filled form
 * a designer re-opened it from otherwise. There is no destination to get wrong and no stack to
 * unwind, which is the same property that makes the system-back handling below safe.
 *
 * ── WHERE THE BACK GESTURE ACTUALLY GOES, ESTABLISHED RATHER THAN ASSUMED ────────────────────────
 *
 * Back has to return the reader where they came from and must never strand them on an empty stack.
 * Both halves hold here for structural reasons, and the second one is not obvious from reading this
 * file, so it is written down together with how it was checked.
 *
 * A `BackHandler` registers against `LocalOnBackPressedDispatcherOwner`, and inside a dialog that
 * owner is NOT the Activity. Compose's `DialogWrapper` extends `androidx.activity.ComponentDialog`,
 * which carries an `OnBackPressedDispatcher` of its own and, in `initializeViewTreeOwners`,
 * publishes itself on the dialog window's decor view; the composition local's fallback is exactly
 * that view-tree lookup. So the handler in [WalkthroughJourney] binds to THIS WINDOW's dispatcher.
 * That was read out of `compose-ui 1.7.8` and `activity 1.9.3` in the Gradle cache rather than
 * remembered — the two classes are `androidx.compose.ui.window.DialogWrapper` and
 * `androidx.activity.ComponentDialog`, and the line that matters is the
 * `ViewTreeOnBackPressedDispatcherOwner.set` on the decor view. Re-read in `compose-ui 1.12.1` and
 * `activity 1.13.0` on 2026-10-09, when the app moved to them: unchanged on both counts.
 *
 * Two consequences, and between them they are the whole answer. A back press inside the walkthrough
 * cannot reach `MainActivity`'s dispatcher, so it cannot run `goBack()` and cannot finish the
 * activity: there is no path from this surface to the launcher, whatever the journey's handler does.
 * And opening the walkthrough pushed nothing onto the app's own back stack, so there is nothing to
 * pop and "where the reader came from" is simply the screen that was never unmounted.
 *
 * ⚠ THAT IS A PROPERTY OF BEING A DIALOG, NOT OF THE HANDLER. Make this a `Screen` and the identical
 * `BackHandler` binds to the Activity instead, where it is one voice among several and where leaving
 * has to name a destination to leave to. `WalkthroughSurfaceTest` asserts that this stays a dialog,
 * for this reason as much as for the unsaved-changes one.
 *
 * ⚠ AND `dismissOnBackPress` MUST STAY FALSE, for a sharper reason than "two listeners". Setting it
 * true would not replace the journey's handler; `DialogWrapper` registers its callback on that same
 * dispatcher in its constructor, so there would be two enabled callbacks on one dispatcher and which
 * of them runs is a question about registration order that nothing in this file controls. With the
 * flag false that first callback is inert — its body is a test of this very property — and the
 * journey's, added later, is the one the dispatcher runs.
 *
 * ── WHY THE DESTINATION BUTTON IS NEVER HIDDEN ───────────────────────────────────────────────────
 *
 * `visibleNavItems` hides a menu row the account cannot use — hidden, never disabled — and this
 * surface deliberately does the opposite. The walkthrough's own nav entry is ungated for the stated
 * reason that it must reach people who have earned no capability yet, several steps describe screens
 * that need Designer access, and each of those steps ENDS by saying that without it the screen will
 * tell you designer access is required. Hiding the button would make that sentence false and would
 * hide the existence of a capability from the one person who most needs to know it exists in order to
 * go and ask for it.
 *
 * WHAT IS ON THE FAR SIDE OF THAT BUTTON IS NOT UNIFORM, AND THE STEPS HAVE TO SAY SO RATHER THAN
 * THIS COMMENT ASSUMING IT. An earlier draft of this paragraph claimed "the screens themselves
 * re-derive their own predicate before they issue a request, so the refusal is drawn by the screen
 * that owns it", and that is true of exactly one of them. [DesignReviewScreen] opens with
 * `if (user == null || !FieldPermissions.canRunDesignWorkshops(user))` and draws "Designer access
 * required" before it asks the repository anything. [SketchesAndPrototypesScreen] takes no `UserDto`
 * at all — `repository`, `onOpenStage`, `onError` and nothing else — so it structurally cannot
 * refuse anybody: it asks for the workshop list and renders the failure, which means a 403 reads as
 * "The repository could not list your design workshops" and, with no signal, as the network's fault.
 * A walkthrough that promised a refusal there would be teaching a sentence the app cannot say, so
 * that step says what actually happens instead. Whoever gives that screen a tier refusal should go
 * and shorten the step; until then the step is the only place a non-designer is told.
 *
 * @param onFinish close the walkthrough and mark it seen. The caller owns both halves.
 * @param onOpen leave for [WalkStep.destination]. The caller must close and mark seen too, and must
 *   route through `MainActivity.navigate` rather than `openDestination`: the walkthrough itself is
 *   exempt from the unsaved-changes guard because it draws over the page you were on, but a screen it
 *   launches is a real departure from a possibly half-filled form and must still be asked about.
 */
@Composable
internal fun WalkthroughDialog(
    user: UserDto?,
    onFinish: () -> Unit,
    onOpen: (NavDestination) -> Unit,
) {
    /*
     * THE DECK THIS ACCOUNT OPENS ON, AND THE READER'S OWN CHOICE AFTER THAT.
     *
     * Keyed on the ROLE and not on the whole account, because the role is the only thing
     * `walkthroughDeckFor` reads: keying on `user` would reset a reader's chosen deck the next time
     * any unrelated field on their profile changed underneath a live session.
     */
    var deck by remember(user?.role) { mutableStateOf(walkthroughDeckFor(user)) }

    /*
     * READ ONCE, HERE, AND THREADED DOWN AS A BOOLEAN.
     *
     * [walkthroughReduceMotion] costs a `ContentResolver` round trip to the settings provider the
     * first time it is asked, and the journey below it has twenty-five cards, every one of which has
     * an animation that branches on the answer. Asking per card would be twenty-four IPCs and
     * twenty-five CompositionLocal reads for one Boolean that cannot change while this window is
     * open. Asking once at the top is also what makes the answer auditable: there is exactly one call
     * site, so there is exactly one place to look when somebody asks whether this screen honours the
     * preference.
     */
    val reduceMotion = walkthroughReduceMotion()

    Dialog(
        onDismissRequest = onFinish,
        properties = DialogProperties(
            // Full-bleed rather than the platform's ~280dp alert width, and now for a second reason
            // as well as the first. The first: several of these bodies run past four hundred
            // characters and `ProvideAppPreferences` multiplies the reader's own Android font scale
            // by another 1.125 when "Larger text" is on, which the shipped AlertDialog form put into
            // a fixed-width box with no scroll container. The second: a journey with a spine down its
            // left-hand side needs the height of the handset to be a journey at all.
            usePlatformDefaultWidth = false,
            // BACK IS OWNED BY THE BACK HANDLER IN [WalkthroughJourney], NOWHERE ELSE. Leaving this
            // true would give the gesture two listeners with different opinions — the platform's,
            // which closes the dialog, and ours, which closes an open step first — and which one wins
            // would be an ordering detail of whichever Compose version happened to be on the
            // classpath.
            dismissOnBackPress = false,
        ),
    ) {
        /*
         * THE WHOLE HANDSET, AND A REAL SURFACE COLOUR ON IT.
         *
         * `fillMaxSize` inside a `usePlatformDefaultWidth = false` window is the shape `MediaPlayers`
         * already uses for its full-screen viewer. The colour is named rather than left to the
         * Dialog's default because a Dialog's own background is not the app's surface: unset, the
         * journey would scroll against whatever the platform decides a dialog window is, which is not
         * a colour `DesignWorkshopTheme` chose and does not invert with the reader's theme.
         */
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            WalkthroughJourney(
                deck = deck,
                decks = WALKTHROUGH_DECKS,
                onChooseDeck = { chosen -> deck = chosen },
                reduceMotion = reduceMotion,
                onOpen = onOpen,
                onFinish = onFinish,
            )
        }
    }
}

/**
 * "Open Record artisan" — the step's own launch button.
 *
 * The web's `GuideStep.href` is what makes its guide a launcher as well as a lesson; without it a
 * step tells a designer the name of a screen and leaves them to go and find it, which on a handset
 * means opening a drawer and reading thirty-one rows. Its own composable so the label derivation and
 * the semantics live next to each other rather than four levels deep inside a card.
 *
 * `internal` RATHER THAN PRIVATE, FOR ONE CALLER AND ONE REASON. The card that draws it now lives in
 * `WalkthroughJourney.kt`, and file-private would mean either copying this button next to it or
 * copying `walkthroughOpenLabel` — and the label derivation is the whole point of the thing, since a
 * second spelling of a screen's name on the one surface whose job is to teach a newcomer what things
 * are called is the defect it exists to prevent. This is the same widening `WalkthroughSteps.kt`
 * argues for over its own list, and the codebase's own precedent is `internal fun navBadge`.
 */
@Composable
internal fun WalkthroughOpenButton(
    destination: NavDestination,
    onOpen: (NavDestination) -> Unit,
) {
    val label = walkthroughOpenLabel(destination)
    TextButton(
        onClick = { onOpen(destination) },
        modifier = Modifier.padding(top = 2.dp),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            // Decorative: the button's own words say where it goes, and a description here would have
            // TalkBack read "arrow forward" before every one of them.
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            // The fallback is for the day a destination is dropped from the menu while a step still
            // points at it. The button still works — `openDestination` is exhaustive over the enum
            // and always has an arm — it just cannot borrow a name, so it says what it does instead
            // of rendering with no words on it.
            text = if (label != null) "Open $label" else "Open the screen this step teaches",
            style = FieldTextStyles.Link,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
