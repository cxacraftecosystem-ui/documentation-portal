package com.fieldrepository.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.ui.graphics.vector.ImageVector
import com.fieldrepository.app.data.UserDto

/*
 * ─────────────────────────────────────────────────────────────────────────────────────────────────
 * THE WALKTHROUGH'S CONTENT, AND NOTHING ELSE.
 *
 * This file holds the steps. It draws nothing, it navigates nowhere and it reads no preference — the
 * dialog that pages through it and the flag that decides whether it opens on first run live with the
 * screen. The split is what lets a unit test read the journey without standing up a composition, and
 * it is why everything here is `internal` rather than `private`. A step list nothing can see is a
 * step list that drifts, which is exactly what happened to the deck this replaces: it was private to
 * `MainActivity.kt`, its opening card said "Ten steps" while its counter read 1/12, and nothing in
 * either client could tell.
 *
 * ── THE SAME MACHINERY AS THE DESIGNER PORTAL, AND THIS APP'S OWN WORDS ──────────────────────────
 *
 * `WalkthroughJourney.kt` and `WalkthroughScreen.kt` are the designer portal's files, byte for byte
 * apart from the package line and the field register — same structure, interaction, motion, reduced
 * motion, persistence and accessibility. THIS file is where the two apps differ, because it is the
 * content: every step below describes a screen this handset really has, under the menu row's own
 * label.
 *
 * ── CONCISE, ON PURPOSE ──────────────────────────────────────────────────────────────────────────
 *
 * Every step is one short title and one short line. The owner's rule is "concise, premium, no AI
 * slop": a reader scrolling past thirteen cards reads the collapsed line and nothing else, so that
 * line has to carry the step alone. The form labels a screen asks for are not prose and are not
 * here — they are the web's register, carried in `WALKTHROUGH_FIELDS` and shown when a card opens.
 * `WalkthroughStepsTest` holds every body to one sentence and a ceiling of characters, so a step
 * cannot grow back into a paragraph quietly.
 *
 * ── WHY AND WATCH, THE OPEN CARD'S OTHER TWO SECTIONS ────────────────────────────────────────────
 *
 * The open card also says why the step exists and what to watch out for, as the designer portal's
 * card and the web's `GuideStepCard` do (owner, 2026-10-20: the handset's cards were missing both).
 * They are fields of their own, [WalkStep.why] and [WalkStep.watch], rather than more sentences in
 * the body, so the collapsed line stays one line. Where the web teaches the step they are the web's
 * `why` and `watch[]` from `steps.ts`, verbatim — `WalkthroughStepsTest` reads that file and holds
 * them equal. A card in another deck that opens a researcher screen carries that screen's words by
 * reference ([researcherStep]). Every numbered step has both; the two ends of a deck have neither.
 *
 * ── PARITY IS THE JOURNEY, NOT THE SENTENCES ─────────────────────────────────────────────────────
 *
 * The researcher's deck teaches every subject `frontend/components/guide/steps.ts` teaches, in the
 * web's order and under the web's ids, and `WalkthroughStepsTest` reads that file to hold it there.
 * It then adds the three subjects only a handset needs: finding a record by search, what works with
 * no signal, and the account's own settings.
 *
 * ⚠ NO SENTENCE IN THIS FILE MAY CARRY A COUNT OF STEPS. Every count a reader sees is interpolated
 * from the list it describes — see [walkthroughIntro].
 * ─────────────────────────────────────────────────────────────────────────────────────────────────
 */

/**
 * One step of the walkthrough.
 *
 * Seven fields, and every one of them is load-bearing — see each property for what breaks without it.
 */
internal data class WalkStep(
    /**
     * A stable, permanent handle for this step, matching the web's `GuideStep.id` wherever the two
     * teach the same subject.
     *
     * IT IS NOT SHOWN TO ANYBODY. Its whole job is to be the thing a parity test compares and the key
     * the card's field register is looked up by. Unique across every deck, not merely within one.
     *
     * Keep them kebab-case and keep them equal to the web's where a counterpart exists.
     */
    val id: String,
    /**
     * The step's heading — the feature name, then what you do there, separated by a middle dot.
     *
     * NO STEP NUMBER IN HERE, EVER. The position is derived instead — see [walkthroughStepNumber] — so
     * inserting a step renumbers the rest for free.
     */
    val title: String,
    /**
     * The step itself: one short sentence, read in the collapsed card.
     *
     * One line and no more — see the file header. The card cuts nothing out of it: a one-sentence
     * body is the summary whole, and the panel that opens under it carries the form labels and the
     * door.
     */
    val body: String,
    /**
     * The glyph this step wears.
     *
     * DRAWN ONLY FROM ICONS THIS APPLICATION ALREADY USES — the same set `FIELD_NAV_ITEMS` gives the
     * very rows these steps open, so the icon on a step is the icon on the menu row it sends you to.
     */
    val icon: ImageVector? = null,
    /**
     * The screen this step teaches, or null where no single destination opens it.
     *
     * A REAL [NavDestination] AND NEVER A WEB PATH, so the compiler notices a renamed destination.
     * Null means exactly one thing: there is no screen to open. Only the two ends of a deck and the
     * offline step carry it.
     *
     * WHOEVER WIRES THE BUTTON: go through `MainActivity`'s `navigate`, not `openDestination`. The
     * walkthrough itself is exempt from the unsaved-changes guard because it draws OVER the page you
     * were on; a screen it launches is a real departure from a possibly half-filled form.
     */
    val destination: NavDestination? = null,
    /**
     * "Why this step exists", as a field of its own. Where it is blank, [walkthroughFacets] takes the
     * reason from [body] instead, after its first sentence; a step with neither draws no heading.
     */
    val why: String = "",
    /**
     * "Watch out for": the cautions, one bullet each, in the order a reader meets them. Where it is
     * empty, [walkthroughFacets] takes the caution from [body] instead, after the words "Watch out";
     * a step with neither draws no heading.
     */
    val watch: List<String> = emptyList(),
)

/**
 * The researcher's step with [id], for a card in another deck that opens the same screen and must
 * describe it the same way. Fails at class load, loudly, if the id stops existing.
 */
private fun researcherStep(id: String): WalkStep = walkthroughJourney.first { it.id == id }

// ---------------------------------------------------------------------------------------------
// The researcher's deck — the documentation journey, in the order the work happens in
// ---------------------------------------------------------------------------------------------

/**
 * The numbered ground the walkthrough covers, in the order the work happens in.
 *
 * The web's ten subjects first, in the web's order: the workshop every record files under, the craft
 * every artisan links to, the records themselves, the interview and the loose media, then review and
 * the dataset. A picker in a later form offers what an earlier one recorded, so following the order
 * saves a return trip. Then the three this handset adds — search, no signal, the account.
 */
internal val walkthroughJourney: List<WalkStep> = listOf(
    WalkStep(
        id = "workshop",
        title = "Workshop · Record workshop",
        icon = Icons.Filled.Groups,
        destination = NavDestination.RECORD_WORKSHOP,
        body = "Open or create the workshop first.",
        why = "Every record belongs to a workshop, and new forms preselect your latest one.",
        watch = listOf(
            "Create it before you leave for the field.",
            "Records outside its dates need approval.",
        ),
    ),
    WalkStep(
        id = "craft",
        title = "Craft · Add craft",
        icon = Icons.Filled.Brush,
        destination = NavDestination.ADD_CRAFT,
        body = "Add the craft being documented.",
        why = "Artisans, products and tools link to it, keeping spellings consistent.",
        watch = listOf(
            "Reuse an existing craft rather than adding a near-duplicate.",
            "Record the local name the community uses.",
        ),
    ),
    WalkStep(
        id = "artisan",
        title = "Artisan · Record artisan",
        icon = Icons.Filled.Person,
        destination = NavDestination.RECORD_ARTISAN,
        body = "Who they are, where they work and what they’ve learnt.",
        why = "Everything links back to the artisan. Their Do’s and Don’ts can’t be recovered later.",
        watch = listOf(
            "Do’s and Don’ts are required — one point per line.",
            "Pick the craft from the list.",
            "Photo details are added to the notes automatically.",
            "The ring on each record shows what’s still missing; tap a field to fill it in.",
        ),
    ),
    WalkStep(
        id = "product",
        title = "Product · Record product",
        icon = Icons.Filled.Inventory2,
        destination = NavDestination.RECORD_PRODUCT,
        body = "One thing they make — size, cost and photos.",
        why = "Dimensions, cost and price are what researchers compare across regions.",
        watch = listOf(
            "Pick the artisan first; it fills the craft, name and place.",
            "\"Document using grid\" measures the piece from a photo.",
            "Artisan not on record? Switch it on and type the names.",
        ),
    ),
    WalkStep(
        id = "process",
        title = "Process · Document process",
        icon = Icons.Filled.AccountTree,
        destination = NavDestination.DOCUMENT_PROCESS,
        body = "How the product is made, step by step, on video.",
        why = "The steps on video capture knowledge a photo or description loses.",
        watch = listOf(
            "Use Sequential for ordered steps, Group for parallel ones.",
            "Film the action, not the result.",
            "Link it to the product you recorded.",
        ),
    ),
    WalkStep(
        id = "tool",
        title = "Tool · Record tool",
        icon = Icons.Filled.Build,
        destination = NavDestination.RECORD_TOOL,
        body = "Material, size, maker and replacement cost.",
        why = "Tool makers often vanish before the craft does — record who makes them.",
        watch = listOf(
            "Fill only the dimensions that apply.",
            "cm and inch boxes fill each other.",
            "Link several crafts and artisans at once.",
            "Shoot process stages in order.",
            "Assign tools to artisans later from \"Assign tools to artisans\".",
        ),
    ),
    WalkStep(
        id = "questionnaire",
        title = "Questionnaire · Take interview",
        icon = Icons.Filled.Quiz,
        destination = NavDestination.TAKE_INTERVIEW,
        body = "Work through the sections, recording each answer.",
        why = "Audio is transcribed, so you keep their voice and searchable text.",
        watch = listOf(
            "One interview per set of artisans; saving adds to an existing one.",
            "Answer only what was asked.",
            "Others’ answers can only be changed by them or an admin.",
            "\"Check completion\" shows the gaps.",
        ),
    ),
    WalkStep(
        id = "media",
        title = "Miscellaneous Media · Upload media",
        icon = Icons.Filled.PermMedia,
        destination = NavDestination.UPLOAD_MEDIA,
        body = "Files that don’t belong to one record.",
        why = "Keeps field context with your records, not on a phone.",
        watch = listOf(
            "Pick a record type first, or \"Miscellaneous Media\".",
            "Audio is transcribed.",
            "You can link it to a record later.",
        ),
    ),
    WalkStep(
        id = "review",
        title = "Review · Track your submissions",
        icon = Icons.Filled.Visibility,
        destination = NavDestination.REVIEW,
        body = "Submissions come back Approved, Rejected or Sent for revision.",
        why = "A reviewer above you checks every record before it counts.",
        watch = listOf(
            "Below Professor, everything is submitted as Pending.",
            "Fix what the comments say; saving resubmits it.",
            "Reviewers see work from those below them.",
        ),
    ),
    WalkStep(
        id = "view-data",
        title = "View Data · Export a folder",
        icon = Icons.Filled.Storage,
        destination = NavDestination.VIEW_DATA,
        body = "Browse records as folders and export any of them.",
        why = "The same records, filed three ways, ready to preview and download.",
        watch = listOf(
            "Use the breadcrumb to go back up.",
            "Transcripts preview as formatted text.",
            "Without dataset access, use Search instead.",
        ),
    ),
    WalkStep(
        id = "browse-records",
        title = "Browse records · Search everything",
        icon = Icons.Filled.Search,
        destination = NavDestination.BROWSE_RECORDS,
        body = "Search every record, then narrow by type, craft or place.",
        why = "Search first, so nobody records the same artisan or product twice.",
        watch = listOf(
            "A near-duplicate splits one artisan’s work across two records.",
            "Open a result to see the whole record and its files.",
        ),
    ),
    WalkStep(
        id = "offline",
        title = "No signal · What still works",
        icon = Icons.Filled.CloudOff,
        body = "Entries saved offline wait on this phone and upload when you reconnect.",
        why = "Villages often have no signal; your work waits safely on this phone.",
        watch = listOf(
            "Signing out keeps queued entries; they send when you sign back in.",
            "A save the server refuses waits in the tray to fix and send again.",
        ),
    ),
    WalkStep(
        id = "account",
        title = "Account · Settings",
        icon = Icons.Filled.Settings,
        destination = NavDestination.SETTINGS,
        body = "Turn on two-step verification, and set the theme and text size.",
        why = "Two-step verification keeps the dataset safe if this phone is lost.",
        watch = listOf(
            "Save your recovery codes before you finish setting it up.",
            "Larger text, high contrast and reduced motion are on the same screen.",
        ),
    ),
)

/**
 * The opening card.
 *
 * THE COUNT IS DERIVED AND MUST STAY DERIVED. The deck this replaces typed "Ten steps" over a counter
 * reading 1/12. Interpolating [walkthroughJourney] means the sentence cannot be wrong.
 *
 * Declared after the journey it counts, because top-level properties in one Kotlin file initialise in
 * declaration order and a reference upward would read an empty list.
 */
private val walkthroughIntro = WalkStep(
    id = "intro",
    title = "The order the work happens in",
    icon = Icons.Filled.Explore,
    body = "${walkthroughJourney.size} steps, in the order you do them. Leave any time, and " +
        "reopen it from Walkthrough.",
)

/** The closing card: a checklist for the moment before the signal goes. */
private val walkthroughOutro = WalkStep(
    id = "before-you-leave",
    title = "Before you leave the field",
    icon = Icons.Filled.CheckCircle,
    body = "Every record has its workshop, craft and photos, and nothing is waiting to upload.",
)

/**
 * The whole walkthrough, in the order it is paged through: the opening card, the journey, the
 * closing card.
 *
 * THIS IS THE LIST THE SCREEN RENDERS. It is deliberately a different list from [walkthroughJourney]
 * — the two ends are not features, have no destination and must not be numbered as though they were
 * — and keeping them apart is what lets the opening card state a count that is about the journey
 * rather than about itself.
 */
internal val walkthroughSteps: List<WalkStep> =
    listOf(walkthroughIntro) + walkthroughJourney + walkthroughOutro

/**
 * Where [step] sits in [deck]'s journey, counting from one, or null if it is an opening or closing
 * card.
 *
 * THE ONE PLACE A STEP NUMBER IS ALLOWED TO COME FROM. The deck is a parameter so that every deck
 * numbers its own steps against its own length — a step of one deck asked about in another answers
 * null rather than borrowing somebody else's arithmetic.
 *
 * Matched on [WalkStep.id] rather than on the whole value, so a screen holding a step it copied or
 * rebuilt still gets the right answer.
 */
internal fun walkthroughStepNumber(deck: WalkthroughDeck, step: WalkStep): Int? =
    deck.journey.indexOfFirst { it.id == step.id }.takeIf { it >= 0 }?.plus(1)

// ---------------------------------------------------------------------------------------------
// The decks, and the rule that decides which one opens
// ---------------------------------------------------------------------------------------------

/**
 * One walkthrough: an opening card, a numbered journey, a closing card, and who it is written for.
 */
internal data class WalkthroughDeck(
    /** Stable id. Never shown, and never a URL — this app has no deep links into the walkthrough. */
    val id: String,
    /** The switcher's button label. */
    val name: String,
    /** One line saying who the deck is for. */
    val audience: String,
    /** The numbered steps, in the order the work happens in. Never includes the two ends. */
    val journey: List<WalkStep>,
    /**
     * The whole deck as it is paged through: the opening card, the journey, the closing card. The
     * screen renders this; everything that counts, numbers or recaps reads [journey].
     */
    val steps: List<WalkStep>,
)

/**
 * The researcher's deck — the whole documentation journey.
 *
 * It wraps the lists above rather than restating them, which keeps `WalkthroughStepsTest`'s join
 * against `steps.ts` pointed at the very objects the screen renders.
 */
internal val WALKTHROUGH_RESEARCHER_DECK = WalkthroughDeck(
    id = "researcher",
    name = "Documenting a craft",
    audience = "Researchers and professors recording in the field",
    journey = walkthroughJourney,
    steps = walkthroughSteps,
)

// ---------------------------------------------------------------------------------------------
// The contributor's deck — Crowdsource Volunteers and Field Contributors
// ---------------------------------------------------------------------------------------------

/**
 * What an account below Researcher can actually do on this handset.
 *
 * Recording an artisan, product, process or tool needs Researcher (`canCreateRecords`), and adding a
 * craft or a workshop needs Professor, so the researcher's deck would hand these readers seven doors
 * their menu does not show. This deck is the four screens their menu does show — the interview, loose
 * media, review and search — plus no signal and the account. Review is a Field Contributor's own
 * capability (`canReview`), and the card says so rather than hiding it from a volunteer, who can then
 * see what the next tier opens.
 */
internal val walkthroughContributorJourney: List<WalkStep> = listOf(
    WalkStep(
        id = "contributor-interview",
        title = "Questionnaire · Take interview",
        icon = Icons.Filled.Quiz,
        destination = NavDestination.TAKE_INTERVIEW,
        body = "Record each answer as audio; one interview holds each set of artisans.",
        why = researcherStep("questionnaire").why,
        watch = researcherStep("questionnaire").watch,
    ),
    WalkStep(
        id = "contributor-media",
        title = "Miscellaneous Media · Upload media",
        icon = Icons.Filled.PermMedia,
        destination = NavDestination.UPLOAD_MEDIA,
        body = "Photos, video and audio from the field, linked to a record where one fits.",
        why = researcherStep("media").why,
        watch = researcherStep("media").watch,
    ),
    WalkStep(
        id = "contributor-review",
        title = "Review · Check submissions",
        icon = Icons.Filled.Visibility,
        destination = NavDestination.REVIEW,
        body = "Field Contributors approve or send back what volunteers submit.",
        why = "A second reader checks volunteers’ work before it counts.",
        watch = listOf(
            "A fixable reason sends the record back instead of rejecting it.",
            "Approving a record also approves its files.",
        ),
    ),
    WalkStep(
        id = "contributor-browse",
        title = "Browse records · Search everything",
        icon = Icons.Filled.Search,
        destination = NavDestination.BROWSE_RECORDS,
        body = "Read what the team recorded, narrowed by type, craft or place.",
        why = "See what the team already has before you record more.",
        watch = listOf(
            "A near-duplicate splits one artisan’s work across two records.",
            "Open a result to see the whole record and its files.",
        ),
    ),
    WalkStep(
        id = "contributor-offline",
        title = "No signal · What still works",
        icon = Icons.Filled.CloudOff,
        body = "Entries saved offline wait on this phone and upload when you reconnect.",
        why = researcherStep("offline").why,
        watch = researcherStep("offline").watch,
    ),
    WalkStep(
        id = "contributor-account",
        title = "Account · Settings",
        icon = Icons.Filled.Settings,
        destination = NavDestination.SETTINGS,
        body = "Turn on two-step verification, and set the theme and text size.",
        why = researcherStep("account").why,
        watch = researcherStep("account").watch,
    ),
)

/** The contributor deck's opening card. Its count is its OWN journey's, never the researcher's. */
private val walkthroughContributorIntro = WalkStep(
    id = "contributor-intro",
    title = "What you can do from the field",
    icon = Icons.Filled.Explore,
    body = "${walkthroughContributorJourney.size} steps for interviews, media and finding records. " +
        "Recording artisans opens at Researcher.",
)

/** The contributor deck's closing checklist. */
private val walkthroughContributorOutro = WalkStep(
    id = "contributor-before-you-leave",
    title = "Before you leave the field",
    icon = Icons.Filled.CheckCircle,
    body = "Every answer is recorded, every file is linked, and nothing is waiting to upload.",
)

/** The contributor's deck: the opening card, six screens, and the closing checklist. */
internal val WALKTHROUGH_CONTRIBUTOR_DECK = WalkthroughDeck(
    id = "contributor",
    name = "Contributing from the field",
    audience = "Crowdsource Volunteers and Field Contributors",
    journey = walkthroughContributorJourney,
    steps = listOf(walkthroughContributorIntro) + walkthroughContributorJourney +
        walkthroughContributorOutro,
)

// ---------------------------------------------------------------------------------------------
// The admin's deck — running the repository
// ---------------------------------------------------------------------------------------------

/**
 * The screens that keep the repository running: who may sign in, what each account may do, the two
 * shared lists every record hangs off, the decisions waiting on review, the dataset, and the hub.
 *
 * Every door here is a real row in `FIELD_NAV_ITEMS`. The admin rows among them are `adminSurface`,
 * so an admin sees them in the menu only while admin view is on — the opening card says so, because
 * a door the menu is not currently showing is otherwise a door the reader concludes is missing.
 */
internal val walkthroughAdminJourney: List<WalkStep> = listOf(
    WalkStep(
        id = "admin-access-roster",
        title = "Access roster · Who may sign in",
        icon = Icons.Filled.HowToReg,
        destination = NavDestination.ACCESS_ROSTER,
        body = "Approve or refuse sign-in requests, and add people before they ask.",
        why = "Only people on the roster can sign in, so access stays deliberate.",
        watch = listOf(
            "Search by email or name before adding someone twice.",
            "Workshop access is separate; grant it from the Settings hub.",
        ),
    ),
    WalkStep(
        id = "admin-manage-users",
        title = "Manage users · Roles and access",
        icon = Icons.Filled.ManageAccounts,
        destination = NavDestination.MANAGE_USERS,
        body = "Set each person's role; it decides what their menu offers.",
        why = "A role decides what each person can record and review.",
        watch = listOf(
            "Recording artisans, products, processes and tools needs Researcher.",
            "Adding a craft or a workshop needs Professor.",
        ),
    ),
    WalkStep(
        id = "admin-workshop",
        title = "Workshop · Record workshop",
        icon = Icons.Filled.Groups,
        destination = NavDestination.RECORD_WORKSHOP,
        body = "Create the workshop before the team leaves; every record files under one.",
        why = researcherStep("workshop").why,
        watch = researcherStep("workshop").watch,
    ),
    WalkStep(
        id = "admin-craft",
        title = "Craft · Add craft",
        icon = Icons.Filled.Brush,
        destination = NavDestination.ADD_CRAFT,
        body = "Search before adding a craft, so one spelling holds every record.",
        why = researcherStep("craft").why,
        watch = researcherStep("craft").watch,
    ),
    WalkStep(
        id = "admin-review",
        title = "Review · Decide submissions",
        icon = Icons.Filled.Visibility,
        destination = NavDestination.REVIEW,
        body = "Approve, reject, or send back with a comment saying what to fix.",
        why = "Only approved records count in the dataset.",
        watch = listOf(
            "A fixable reason sends the record back instead of rejecting it.",
            "Approving a record also approves its files.",
            "Offline, your decision waits on this phone until it can send.",
        ),
    ),
    WalkStep(
        id = "admin-view-data",
        title = "View Data · Export a folder",
        icon = Icons.Filled.Storage,
        destination = NavDestination.VIEW_DATA,
        body = "Every record as folders; download any folder as a spreadsheet.",
        why = researcherStep("view-data").why,
        watch = researcherStep("view-data").watch,
    ),
    WalkStep(
        id = "admin-settings-hub",
        title = "Settings hub · Assignments and requests",
        icon = Icons.Filled.Tune,
        destination = NavDestination.SETTINGS_HUB,
        body = "Workshop assignments, access requests, tasks and recovered recordings.",
        why = "The admin jobs that span workshops and people, in one place.",
        watch = listOf(
            "Workshop assignments decide who may submit entries to each workshop.",
            "Re-link recovered recordings, or their audio stays unfiled.",
        ),
    ),
)

/** The admin deck's opening card. Its count is its OWN journey's, never the researcher's. */
private val walkthroughAdminIntro = WalkStep(
    id = "admin-intro",
    title = "Running the repository",
    icon = Icons.Filled.Explore,
    body = "${walkthroughAdminJourney.size} steps for access, roles and shared lists. Admin rows " +
        "show while admin view is on.",
)

/** The admin deck's closing checklist. */
private val walkthroughAdminOutro = WalkStep(
    id = "admin-before-you-sign-off",
    title = "Before you sign off",
    icon = Icons.Filled.CheckCircle,
    body = "Requests are answered, roles are right, and nothing waits on your review.",
)

/** The admin's deck: the opening card, seven screens, and the closing checklist. */
internal val WALKTHROUGH_ADMIN_DECK = WalkthroughDeck(
    id = "admin",
    name = "Running the repository",
    audience = "Admins and the master admin",
    journey = walkthroughAdminJourney,
    steps = listOf(walkthroughAdminIntro) + walkthroughAdminJourney + walkthroughAdminOutro,
)

/**
 * Every deck, in the order the switcher prints them.
 *
 * THE ORDER IS FIXED WHOEVER IS READING: the order of a list of choices is not the same question as
 * which of them is selected, and a switcher that reordered itself per account would mean two
 * colleagues describing "the second one" and meaning different decks.
 */
internal val WALKTHROUGH_DECKS: List<WalkthroughDeck> =
    listOf(WALKTHROUGH_RESEARCHER_DECK, WALKTHROUGH_CONTRIBUTOR_DECK, WALKTHROUGH_ADMIN_DECK)

/**
 * WHICH DECK OPENS FOR THIS ACCOUNT. A DEFAULT, NOT A GATE.
 *
 * The walkthrough's nav row is never gated, every deck stays in the APK for everybody, and the
 * opening card of whichever deck opens offers every other one. A wrong answer here costs the reader
 * one tap. If this ever becomes a filter over which decks EXIST for an account, it has stopped being
 * this function.
 *
 * The predicates are the menu's own, so the deck that opens and the rows it teaches cannot disagree:
 *
 *  - [FieldPermissions.isAdmin] — Admin and Master Admin — opens the admin's deck.
 *  - Below [FieldPermissions.canCreateRecords] — Crowdsource Volunteer and Field Contributor, and any
 *    role this build does not know — opens the contributor's deck: the rows their menu actually shows.
 *  - Everybody else — Researcher and Professor — opens the researcher's deck.
 *
 * A NULL USER GETS THE RESEARCHER'S DECK. The app renders the sign-in screen until there is a user,
 * so this arm is not reachable from the dialog — but the function is pure and testable, and answering
 * null for an unknown account would push the null check onto every caller.
 */
internal fun walkthroughDeckFor(user: UserDto?): WalkthroughDeck =
    when {
        user == null -> WALKTHROUGH_RESEARCHER_DECK
        FieldPermissions.isAdmin(user) -> WALKTHROUGH_ADMIN_DECK
        !FieldPermissions.canCreateRecords(user) -> WALKTHROUGH_CONTRIBUTOR_DECK
        else -> WALKTHROUGH_RESEARCHER_DECK
    }
