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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/*
 * ─────────────────────────────────────────────────────────────────────────────────────────────────
 * THE WALKTHROUGH'S WINDOW, THE FLAG THAT OPENS IT ONCE, AND NOTHING ELSE.
 *
 * Three files, one feature. `WalkthroughSteps.kt` holds the words. `WalkthroughJourney.kt` holds the
 * experience — the scroll, the spine, the cards. This one holds the WINDOW they are drawn in, the
 * exits, and the device-local "you have seen this" flag. The split is deliberate and it is the
 * reason the step list can be read by a JVM unit test that never stands up a composition.
 *
 * ── IT IS A DIALOG AND NOT A `Screen`, AND THAT IS THE WHOLE ARGUMENT ────────────────────────────
 *
 * `MainActivity.kt` routes through a sealed `Screen` hierarchy with several exhaustive `when` tables
 * over it — the title bar, the back behaviour, the island's selected chip. A walkthrough added there
 * would owe every one of those an arm, and — the part that actually matters — it would sit BEHIND
 * `navigate`'s unsaved-changes guard (MainActivity.kt:1202-1215), which exists so that a menu tap
 * cannot silently throw away a half-filled artisan form. The walkthrough takes nothing away: it
 * draws OVER whatever was there and closing it reveals that screen again, which is why
 * `MainActivity.navigate` already exempts `NavDestination.WALKTHROUGH` by name and why that
 * exemption stays correct only for as long as this stays a dialog.
 *
 * The back gesture is the other half of the same property, and it is not obvious from reading this
 * file, so it is written down. A `BackHandler` registers against `LocalOnBackPressedDispatcherOwner`,
 * and inside a dialog that owner is NOT the Activity: Compose's `DialogWrapper` extends
 * `androidx.activity.ComponentDialog`, which carries an `OnBackPressedDispatcher` of its own and
 * publishes itself on the dialog window's decor view in `initializeViewTreeOwners`; the composition
 * local's fallback is exactly that view-tree lookup. So the handler in [WalkthroughJourney] binds to
 * THIS window's dispatcher. Two consequences, and between them they are the whole answer: a back
 * press inside the walkthrough cannot reach `MainActivity`'s dispatcher, so it cannot finish the
 * activity and drop somebody onto the launcher; and opening the walkthrough pushed nothing onto the
 * app's own back stack, so there is nothing to pop and "where the reader came from" is simply the
 * screen that was never unmounted. (Versions: activity-compose 1.9.3 and the compose-bom 2024.10.01
 * ui artifact — app/build.gradle.kts:73-82.)
 * ─────────────────────────────────────────────────────────────────────────────────────────────────
 */

// ---------------------------------------------------------------------------------------------
// The first-run flag
// ---------------------------------------------------------------------------------------------

/*
 * THE SAME SHARED-PREFERENCES FILE AND THE SAME KEY `MainActivity` ALREADY USES, NAMED AGAIN RATHER
 * THAN IMPORTED.
 *
 * `MainActivity.kt:7728` declares `private const val APP_PREFS_NAME = "fieldrepo_prefs"` and
 * `:7730` declares `private const val PREF_WALKTHROUGH_SEEN = "walkthrough_seen"`. Both are
 * file-private inside a 13,751-line file, so they cannot be imported; moving them here would be an
 * edit to a file this workstream does not own. The literals are therefore repeated — with this
 * comment, which is the whole price of repeating them.
 *
 * ⚠ AND REPEATING THEM EXACTLY IS THE POINT, NOT AN ACCIDENT. Every installed user of this app
 * already has `walkthrough_seen` sitting in `fieldrepo_prefs` from the deck this replaces
 * (MainActivity.kt:7740-7745). `getSharedPreferences` with a name no file has yet does not fail: it
 * hands back an empty document. So a "tidied" name here would not migrate one stored flag — it
 * would silently re-show the walkthrough to every installed user on the update that renamed it, and
 * the symptom would not appear until the morning after the release. Reusing the pair is what makes
 * the replacement invisible to somebody who has already dismissed the old deck.
 */
private const val WALKTHROUGH_PREFS = "fieldrepo_prefs"
private const val PREF_WALKTHROUGH_SEEN = "walkthrough_seen"

/**
 * True once this device's user has finished, skipped or backed out of the walkthrough at least once.
 *
 * DEVICE-LOCAL, AND THAT IS THE INTENDED SCOPE RATHER THAN A SHORTCUT. Making it per-account would
 * mean another value on the `/preferences/me` contract — the Pydantic schema, `frontend/lib/types.ts`
 * and `data/ApiModels.kt`, which in this repository is a three-file hand edit with no codegen — to
 * remember something about a tour of THIS handset's menu. The consequence is written down so nobody
 * reports it as a bug: a researcher who signs in on a second phone is shown it again there, and
 * signing out and back in on the same phone is not.
 *
 * Reading it is a synchronous parse of a small XML file, and that is what makes it safe to read while
 * deciding the first screen rather than a frame afterwards. That distinction is not theoretical here:
 * `MainActivity.kt:1025-1026` currently reads it in a `LaunchedEffect(Unit)`, which runs AFTER the
 * first composition — so the dashboard is drawn, and then the walkthrough appears over it on the next
 * frame. The fix belongs in `MainActivity` and is written up as a handoff; this function is
 * synchronous so that the fix is available to take.
 */
internal fun walkthroughSeen(context: Context): Boolean =
    context.getSharedPreferences(WALKTHROUGH_PREFS, Context.MODE_PRIVATE)
        .getBoolean(PREF_WALKTHROUGH_SEEN, false)

/**
 * Remember that the walkthrough has been shown, for good, on this device.
 *
 * `apply()` and not `commit()`: the write is handed to a background thread and the in-memory value is
 * updated at once, so a [walkthroughSeen] call on the very next frame already reads true. A
 * `commit()` here would block the UI thread on a disk write at the exact moment the reader is trying
 * to leave a dialog.
 *
 * EVERY EXIT CALLS THIS, WITHOUT EXCEPTION. A Skip that closes the dialog without writing the flag is
 * the single most common defect this kind of feature has: it looks completely correct until tomorrow
 * morning, when the walkthrough opens again over the dashboard of somebody who has already said no to
 * it. [WalkthroughDialog] is built so that there is exactly one exit lambda and no second path that
 * could forget.
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
 * Whether the journey should move, reading BOTH switches that get a vote.
 *
 * ── THE APP'S OWN SWITCH, WHICH IS THE ESTABLISHED CONVENTION ────────────────────────────────────
 *
 * `LocalAppPreferences.current.reducedMotion` (AppearanceScreen.kt:115/242) is the app's own
 * preference and this file follows it rather than inventing a second one.
 *
 * ── THE DEVICE'S OWN SWITCH, WHICH THIS FILE IS THE FIRST TO READ ────────────────────────────────
 *
 * `Settings.Global.ANIMATOR_DURATION_SCALE` — Developer options › "Animator duration scale", and the
 * setting an accessibility service or a battery saver turns to zero on the user's behalf — is read by
 * NO other Kotlin in this application. Compose's own recomposer installs a `MotionDurationScale` from
 * it, so `AnimatedContent` does collapse when the platform says zero, and in practice the framework
 * has been carrying this for the whole app. But the Accessibility card on `AppearanceScreen.kt:309`
 * promises in as many words that "Your device's own reduce-motion setting is always honoured too",
 * and a promise that rests entirely on a framework default is a promise nothing in this repository
 * asserts. So this one composable performs the OR explicitly, and I am writing down that it is a NEW
 * convention here: it currently has exactly one caller, and if a second screen wants it the right
 * move is to lift this function to a shared place rather than to copy the `Settings.Global` read.
 * Copying it is how the two would drift. (No permission is needed to read `Settings.Global`.)
 *
 * ── WHAT EACH SWITCH ACTUALLY PRODUCES, BECAUSE THE TWO ARE NOT THE SAME REQUEST ─────────────────
 *
 * True from either source sends [WalkthroughJourney] down its reduced-motion branch, which is not one
 * decision but several: the travelling node stops being drawn, the reveal stops rising, the bubble
 * stops growing, the chevron and the detail panel `snap()`, and a programmatic scroll becomes a jump
 * because SMOOTH SCROLLING IS MOTION TOO. The one thing that does not become instant is the header's
 * active-step swap, which stays a 90 ms cross-fade and NOT a `snap()`: reduced motion asks for no
 * MOVEMENT, not for no change at all, and an instant substitution of a line of text reads as a
 * rendering glitch rather than as a change. When the DEVICE is the one asking, that fade collapses to
 * nothing anyway without a line of code here, because Compose installs a `MotionDurationScale` from
 * this very setting and `tween(90)` then resolves to zero duration.
 *
 * Read once per [Context] rather than on every recomposition: this is a `ContentResolver` round trip
 * to the settings provider, and a reveal animation is not worth an IPC per frame. The cost of that
 * choice is that flipping the developer-options slider while this dialog is open does not take effect
 * until it is reopened, which is the correct trade for a surface a researcher sees once.
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
 * DERIVED FROM [FIELD_NAV_ITEMS] AND NEVER TYPED OUT HERE. `NavEntry.label` is the app's one
 * spelling of a screen's name and the drawer, the island and the dashboard all read it from there; a
 * button in this dialog reading anything else would be a second spelling, sitting on the one surface
 * whose entire job is to teach a newcomer what things are called. Deriving it also means this button
 * and the drawer row a researcher then goes looking for say the same words by construction.
 *
 * Null only if a destination is ever dropped from the menu while a step still points at it — the
 * caller falls back to a generic label rather than rendering a button with no words on it.
 * `WalkthroughStepsTest` asserts that null cannot happen for any step shipped today.
 */
private fun walkthroughOpenLabel(destination: NavDestination): String? =
    FIELD_NAV_ITEMS.firstOrNull { it.destination == destination }?.label

// ---------------------------------------------------------------------------------------------
// The dialog
// ---------------------------------------------------------------------------------------------

/**
 * The walkthrough's window: a full-bleed dialog with [WalkthroughJourney] scrolling inside it.
 *
 * ── THE SIGNATURE IS BUILT TO BE TRIVIALLY CALLABLE, ON PURPOSE ──────────────────────────────────
 *
 * Two lambdas and nothing else. `MainActivity.kt` is 13,751 lines long and holds every wire this
 * dialog needs — the first-run gate, the router arm at :1158, the `navigate` exemption at :1207, the
 * dashboard button at :1552 and the `pendingUpdate == null` ordering guard at :1897 that keeps this
 * from burying a required-update prompt. Keeping the surface behind a two-lambda signature is what
 * lets all of them stay exactly as they are, and that ordering guard becomes MORE load-bearing now
 * that this fills the screen rather than floating over the middle of it. Do not give this composable
 * a parameter that would make somebody have to touch them.
 *
 * (The currently-shipped call site at MainActivity.kt:1898 passes a single `onDismiss`. Replacing it
 * is a one-line edit, written up as a handoff because that file belongs to another workstream.)
 *
 * ── ONE EXIT LAMBDA, ON PURPOSE ──────────────────────────────────────────────────────────────────
 *
 * [onFinish] is called by Skip, by Done, by the system back gesture with nothing open, and by a
 * dismissal from outside. There is no second way out and no second lambda, because the requirement
 * that "Skip and Done must both clear the flag and land in the same place" is only enforceable if the
 * two are literally the same call. Two lambdas doing the same two things is exactly the shape in
 * which one of them later loses the `markWalkthroughSeen`, and the symptom — the walkthrough
 * reappearing tomorrow for somebody who dismissed it — does not show up until tomorrow.
 *
 * Where they land is not a routing decision at all: this dialog draws over whatever screen was
 * already there, so closing it reveals that screen.
 *
 * ── WHY THE DESTINATION BUTTON IS NEVER HIDDEN BY ROLE ───────────────────────────────────────────
 *
 * `visibleNavItems` (AppNavigation.kt:402-403) FILTERS a menu row the account cannot use — hidden,
 * never greyed — and this surface deliberately does the opposite. The walkthrough's own nav entry is
 * ungated for the stated reason that it must reach people who have earned no capability yet
 * (AppNavigation.kt:326-329), and three of these steps END by saying what happens when the capability
 * is missing: `view-data` says the browser shows a restricted notice, `craft` and `workshop` open
 * rows that `canManageCrafts` / `canManageWorkshops` gate. Hiding the button would make those
 * sentences unreachable and would hide the existence of a capability from the one person who most
 * needs to know it exists in order to go and ask for it.
 *
 * @param onFinish close the walkthrough and mark it seen. The caller owns both halves.
 * @param onOpen leave for [WalkStep.destination]. The caller must close and mark seen too, and must
 *   route through `MainActivity.navigate` rather than `openDestination`: the walkthrough itself is
 *   exempt from the unsaved-changes guard because it draws over the page you were on, but a screen it
 *   launches is a real departure from a possibly half-filled form and must still be asked about.
 */
@Composable
internal fun WalkthroughDialog(
    onFinish: () -> Unit,
    onOpen: (NavDestination) -> Unit,
) {
    /*
     * READ ONCE, HERE, AND THREADED DOWN AS A BOOLEAN.
     *
     * [walkthroughReduceMotion] costs a `ContentResolver` round trip to the settings provider the
     * first time it is asked, and every card in the journey below it has an animation that branches
     * on the answer. Asking per card would be an IPC and a CompositionLocal read per card for one
     * Boolean that cannot change while this window is open. Asking once at the top is also what makes
     * the answer auditable: there is exactly one call site, so there is exactly one place to look when
     * somebody asks whether this screen honours the preference.
     */
    val reduceMotion = walkthroughReduceMotion()

    Dialog(
        onDismissRequest = onFinish,
        properties = DialogProperties(
            // Full-bleed rather than the platform's ~280dp alert width, for two reasons. The first:
            // several of these bodies run past four hundred characters and the app multiplies the
            // reader's own Android font scale by another 1.125 when "Larger text" is on
            // (AppearanceScreen.kt:102), which the shipped AlertDialog deck put into a fixed-width box.
            // The second: a journey with a spine down its left-hand side needs the height of the
            // handset to be a journey at all.
            usePlatformDefaultWidth = false,
            // BACK IS OWNED BY THE BACK HANDLER IN [WalkthroughJourney], NOWHERE ELSE, and this flag
            // must stay false for a sharper reason than "two listeners". Setting it true would not
            // replace the journey's handler: `DialogWrapper` registers its own callback on that same
            // dispatcher in its constructor, so there would be two ENABLED callbacks on one dispatcher
            // and which of them runs is a question about registration order that nothing in this file
            // controls. With the flag false that first callback is inert and the journey's, added
            // later, is the one the dispatcher runs.
            dismissOnBackPress = false,
        ),
    ) {
        /*
         * THE WHOLE HANDSET, AND A REAL SURFACE COLOUR ON IT.
         *
         * The colour is named rather than left to the Dialog's default because a Dialog's own
         * background is not the app's surface: unset, the journey would scroll against whatever the
         * platform decides a dialog window is, which is not a colour `FieldRepositoryTheme` chose and
         * does not invert with the reader's theme.
         */
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            WalkthroughJourney(
                steps = walkthroughSteps,
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
 * step tells a researcher the name of a screen and leaves them to go and find it, which on a handset
 * means opening a drawer and reading past twenty rows. Its own composable so the label derivation and
 * the semantics live next to each other rather than four levels deep inside a card.
 *
 * `internal` RATHER THAN PRIVATE, FOR ONE CALLER AND ONE REASON. The card that draws it lives in
 * `WalkthroughJourney.kt`, and file-private would mean either copying this button next to it or
 * copying [walkthroughOpenLabel] — and the label derivation is the whole point of the thing, since a
 * second spelling of a screen's name on the one surface whose job is to teach a newcomer what things
 * are called is the defect it exists to prevent.
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
