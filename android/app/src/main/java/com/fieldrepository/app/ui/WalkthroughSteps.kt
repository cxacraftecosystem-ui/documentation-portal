package com.fieldrepository.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.ui.graphics.vector.ImageVector

/*
 * ─────────────────────────────────────────────────────────────────────────────────────────────────
 * THE WALKTHROUGH'S CONTENT, AND NOTHING ELSE.
 *
 * This file holds the steps. It draws nothing, it navigates nowhere and it reads no preference — the
 * window that hosts it and the flag that opens it on first run live in `WalkthroughScreen.kt`, and
 * the scrolling journey lives in `WalkthroughJourney.kt`. The split is what lets a JVM unit test read
 * the journey without standing up a composition, and it is why everything here is `internal` rather
 * than `private`: the codebase's own precedent is `internal fun navBadge` in AppNavigation.kt,
 * hoisted out of the drawer purely so a test could assert it.
 *
 * ── WHAT THIS REPLACES, AND THE DEFECT THAT MADE IT NECESSARY ────────────────────────────────────
 *
 * `MainActivity.kt:7748-7873` holds a private twelve-card deck: `private data class WalkStep(val
 * title: String, val body: String)` at :7748, `private val walkthroughSteps` at :7763, and an
 * `AlertDialog` with Back/Next/Done/Skip at :7875. Its titles carry HAND-TYPED NUMBERS —
 * `"1. Workshop · Record workshop"` at MainActivity.kt:7769 — which is the exact defect the designer
 * portal paid for and then wrote down: insert a step and every title after it is a lie that only a
 * human re-reading the whole file can catch. It is also `private` inside a 13,751-line file, so
 * nothing in the test source set can see it, and a step list nothing can see is a step list that
 * drifts. The numbers here are DERIVED — see [walkthroughStepNumber] — and the list is readable.
 *
 * ⚠ THE OLD DECK IS STILL IN `MainActivity.kt` AS THIS FILE LANDS. Deleting it is a `MainActivity.kt`
 * edit, and that file belongs to another agent in this run, so the removal is written up as a handoff
 * rather than performed here. Until it is done the app has two walkthroughs; only this one is wired
 * to anything once the handoff lands. The two cannot collide at compile time — the old declarations
 * are file-private in package `com.fieldrepository.app` and these are `internal` in
 * `com.fieldrepository.app.ui`, and MainActivity imports the `ui` package by explicit name only
 * (62 `import com.fieldrepository.app.ui.…` lines, no star import), so nothing resolves differently
 * until somebody adds the import.
 *
 * ── WHY THE COPY IS A KOTLIN LITERAL AND NOT A STRING RESOURCE ───────────────────────────────────
 *
 * `res/values/strings.xml` in this app holds `app_name` and nothing else. There is no `values-hi/`,
 * no plurals and no `stringResource` call anywhere in the copy. Every user-facing sentence in this
 * application is an inline Kotlin literal, and the pattern for copy that has to be TESTED is a
 * top-level `val` in a Kotlin file — see `ui/Countries.kt` and `ui/TranscriptionProviders.kt`, both
 * plain data pinned by nothing more exotic than being visible. Moving twelve paragraphs into
 * `strings.xml` would make this the only screen in the app whose words live somewhere else, and the
 * parity test below would then have to parse XML to compare the handset against the web. So:
 * literals, here, beside the list that orders them.
 *
 * ── PARITY IS THE JOURNEY, THE ORDER, AND — HERE — THE PROSE TOO ─────────────────────────────────
 *
 * The subjects and their ORDER mirror `frontend/components/guide/steps.ts`, which is the field
 * repository's authoritative walkthrough content and was itself written off the real forms (its own
 * header says so: "`fields` mirrors the real form labels one-for-one"). Unlike the designer portal's
 * port, which rewrote every sentence from its own Kotlin, THIS file carries the web's prose as
 * written. That is not laziness: the web file is the register, it is checked against the forms, and
 * a second independent wording of the same caution is a second thing to get wrong. `summary`, `why`,
 * `fields[]` and `watch[]` all come across, and `WalkthroughStepsTest` reads `steps.ts` OFF DISK and
 * fails when any of them drifts.
 *
 * ── AND IT STILL HAS TO BE TRUE OF *THIS* APP ────────────────────────────────────────────────────
 *
 * A step describing a screen the handset does not have is worse than a missing step: it sends a
 * researcher hunting through a menu for a row that was never built, and what they conclude is that
 * they cannot find it rather than that it is not there. TWO sentences therefore diverge from the web
 * on purpose, both of them naming a surface this handset routes differently, and both are declared
 * in `WalkthroughStepsTest.WATCH_REWRITTEN` / called out in the step's own comment so the divergence
 * is a registered decision rather than a drift:
 *
 *   1. `review` — the web has a standalone `/review` page (steps.ts:286). This handset does not:
 *      `MainActivity.kt:1180` routes `NavDestination.REVIEW -> screen = screenFor(EntryMode.VIEW_DATA)`
 *      under its own comment "Android has no standalone review queue: reviewing happens inside the
 *      record browser". The step says so rather than promising a queue.
 *   2. `view-data` — the web's last caution ends "use Search to find records instead" (steps.ts:321).
 *      There is no row called "Search" in this menu; `AppNavigation.kt:349` calls it "Browse records"
 *      (`NavDestination.BROWSE_RECORDS`, ungated). Naming the web's control would send a researcher
 *      who has just been refused the dataset looking for a row that does not exist.
 *
 * ⚠ AND NO SENTENCE IN THIS FILE MAY CARRY A COUNT OF THE WEB'S STEPS. The deck this replaces opens
 * with "Ten steps, in this order" typed as a literal (MainActivity.kt:7765-7767). That literal is
 * correct TODAY — the web really does teach ten — and being correct today is precisely what makes it
 * a defect rather than a bug: nothing connects it to the list underneath it, so the number, the
 * titles and the list are three separate registers that drift independently the day the web teaches
 * eleven, and neither client can tell. The designer portal is the worked example: the identical
 * sentence sat there reading "Ten steps, in this order" over a list of twelve while its own web guide
 * had grown to nineteen, and nothing went red. A prose count is not merely wrong when it rots — it is
 * an instruction to the next reader to stop looking. Say "the web's steps" and let
 * `WalkthroughStepsTest` do the counting, because it reads the web's file and this comment cannot.
 * ─────────────────────────────────────────────────────────────────────────────────────────────────
 */

/**
 * One step of the walkthrough.
 *
 * Seven fields, and every one of them is load-bearing — see each property for what breaks without it.
 */
internal data class WalkStep(
    /**
     * A stable, permanent handle for this step, equal to the web's `GuideStep.id`.
     *
     * IT IS NOT SHOWN TO ANYBODY. Its whole job is to be the thing the parity test joins on. The ids
     * are the only part of this file stable enough to assert against while prose on both clients is
     * still being edited: pin a test to a `title` and it fails the next time somebody improves a
     * sentence, which trains everybody to ignore the test. Rename one and the comparison silently
     * stops covering that step — `indexOfFirst { it.id == … }` finds nothing and reports a MISSING
     * step, not a renamed one, and the honest reading of that report is "the web does not teach this
     * yet", which is how a register written down twice stays wrong for months.
     *
     * Keep them kebab-case and keep them equal to the web's.
     */
    val id: String,
    /**
     * The step's heading — the feature name, then the control a researcher actually taps, separated
     * by a middle dot (U+00B7, with a space each side; [walkthroughTitleParts] cuts on exactly that).
     *
     * NO STEP NUMBER IN HERE, EVER. The deck this replaces carried "1. Workshop" … "10. View Data" in
     * the literals (MainActivity.kt:7769-7824). The position is derived instead — see
     * [walkthroughStepNumber] — so inserting a step renumbers the rest for free.
     *
     * The FEATURE NAME half is the web's `GuideStep.label` VERBATIM and the test asserts it. The
     * ACTION half is the web's `GuideStep.action`, which for eight of the ten is also the exact
     * `NavEntry.label` of the menu row this step opens (AppNavigation.kt:334-355) — one name for one
     * thing, on the one surface whose job is to teach a newcomer what things are called.
     */
    val title: String,
    /**
     * The step as prose, in one string: what you are doing at this point in the field, then why the
     * dataset needs it.
     *
     * IT IS THE WEB'S `summary` AND `why`, CONCATENATED, IN THAT ORDER, AND NOTHING ELSE. The card
     * cuts it back apart at the first sentence boundary — [walkthroughFacets] — so the summary is the
     * collapsed line and the why is the opened panel, which is exactly the web card's own shape. The
     * ORDER is the durable half of the contract between the two clients.
     *
     * ⚠ THE CAUTION IS NOT IN HERE. It is [watch], a list. The designer portal's version of this file
     * buried the caution in this string behind the literal words "Watch out:" and cut it back out
     * with a regex, and that cost it two silently undrawn cautions when two steps wrote
     * "WATCH OUT, BECAUSE…" with a comma instead of a colon (`WalkthroughJourney.kt:286-315` in
     * designer-portal is thirty lines of post-mortem). A comma is not a colon; a separate field is
     * neither. The web has always modelled `watch` as its own array and this file follows the web
     * rather than the port, which also means the ten steps keep their two-to-four SEPARATE bullets
     * instead of being collapsed into one paragraph by the seam cutter.
     *
     * Long is fine. A handset body is scrollable and a researcher reads this once.
     */
    val body: String,
    /**
     * The form labels this screen asks for, in screen order, one entry per chip — the web's
     * `GuideStep.fields` VERBATIM.
     *
     * ON THE STEP AND NOT IN A SIDE MAP, WHICH IS A DELIBERATE DIVERGENCE FROM THE DESIGNER PORT.
     * There the same register lives in a `WALKTHROUGH_FIELDS` map in the drawing file, held to the
     * web by `backend/tests/test_walkthrough_fields_parity.py` — 674 lines of Python that parses
     * TypeScript on one side and Kotlin on the other. An unguarded duplicate map is exactly the
     * failure that Python file exists to prevent. Putting the strings on the step instead means
     * `WalkthroughStepsTest` can IMPORT them — they are `internal`, the test source set is the same
     * module — and compare them to `steps.ts` with no Kotlin parser at all. One register, one guard,
     * in one language.
     *
     * THE SECOND HALF OF THIS PARAGRAPH USED TO READ "this repository has no such test and this
     * workstream cannot add one (the backend is another agent's tree)". That was true when it was
     * written and stopped being true on 2026-09-16: `backend/tests/test_questionnaire_form_contract.py`
     * is here, it parses TypeScript and Kotlin exactly as the sibling's does, and it reads THIS list
     * among its three registers. Rewritten rather than deleted because the sentence was load-bearing
     * — it is what the design choice below was argued FROM, and a reader who found it gone would not
     * know whether the choice had been re-examined or merely tidied.
     *
     * THE CHOICE IS UNCHANGED, AND NOW RESTS ON THE BETTER ARGUMENT OF THE TWO. Availability was
     * never the real reason; a Kotlin parser is a thing you maintain forever to avoid a refactor you
     * do twice. An imported `internal` list cannot fall out of step with the declaration it IS, and
     * the two guards now answer different questions: `WalkthroughStepsTest` holds this register and
     * `steps.ts` equal to each other, and the Python file holds both of them — and the printed guide
     * — to the form. Two registers agreeing was never evidence, which is the gap that file closed.
     *
     * Empty for the two ends of the deck, which teach no screen. Empty means the card draws no
     * heading, on the same terms as [watch].
     */
    val fields: List<String> = emptyList(),
    /**
     * The cautions, one entry per bullet — the web's `GuideStep.watch`, verbatim except where a
     * sentence names a control this handset spells differently (see the file header; every such
     * rewrite is declared in `WalkthroughStepsTest.WATCH_REWRITTEN` with its reason).
     *
     * The list comes back empty rather than holding one blank string when a step has no caution,
     * because the card decides whether to draw the "Watch out for" heading by asking whether the list
     * is empty, and a heading over an empty bullet is a latent defect rather than a layout choice.
     */
    val watch: List<String> = emptyList(),
    /**
     * The glyph this step wears, or null where a heading alone reads better.
     *
     * DRAWN ONLY FROM ICONS THIS APPLICATION ALREADY USES — specifically the ten that
     * `FIELD_NAV_ITEMS` gives the very rows these steps open (AppNavigation.kt:334-355), so the icon
     * on a step is the icon on the menu row it sends you to. `material-icons-extended` is on the
     * classpath (app/build.gradle.kts:80) so nearly any name would resolve, but "it compiles" is not
     * the bar: a researcher looking for the row they just read about is looking for the picture.
     *
     * Nullable rather than defaulted to a placeholder, because the two ends of this list are not
     * features and have no row to match.
     */
    val icon: ImageVector? = null,
    /**
     * The screen this step teaches, or null where no single destination opens it.
     *
     * A REAL [NavDestination] AND NEVER A WEB PATH. The web's `GuideStep.href` is a URL because the
     * web has URLs; this app has a hand-rolled router whose one routing table is
     * `MainActivity.openDestination` (MainActivity.kt:1150-1200), and a step carrying "/products/new"
     * as a string would be a step that can never open anything. Pointing at the enum means the
     * compiler is what notices when a destination is renamed.
     *
     * Null means exactly one thing: there is no screen to open. Only the opening and closing cards
     * carry it.
     *
     * WHOEVER WIRES THE BUTTON: go through `MainActivity.navigate`, not `openDestination`. The
     * walkthrough itself is exempt from the unsaved-changes guard because it draws OVER the page you
     * were on and takes nothing away (MainActivity.kt:1207-1214); a screen it LAUNCHES is a real
     * departure from a possibly half-filled form and must still be asked about.
     */
    val destination: NavDestination? = null,
)

/**
 * The numbered ground the walkthrough covers, in the order the work happens in.
 *
 * ── THE ORDER, AND WHY IT IS NOT NEGOTIABLE ──────────────────────────────────────────────────────
 *
 * It is `frontend/components/guide/steps.ts`'s order, position for position, because that is the
 * order the work happens in and because a researcher who read the walkthrough on a laptop must
 * recognise this one, step for step, when they open it in a courtyard. The workshop comes first
 * because every other record is scoped to one; the craft before the artisan because the artisan form
 * will not save without a craft; the product before the process because a process is documented
 * against a product that already exists. Run it the other way round and the pickers inside the later
 * screens are empty and the researcher concludes the feature is broken.
 *
 * ⚠ THE IDS ARE THE JOIN AND THEY MUST STAY EQUAL TO THE WEB'S. A step whose id this file invents is
 * a step the parity test reports as MISSING FROM THE WEB rather than as renamed. If the web adds a
 * counterpart, take its id exactly, however awkward it reads next to its neighbours.
 *
 * ⚠ AND THE ORDER OF THE SHARED SUBJECTS IS THE WEB'S. Two clients teaching the same subjects in
 * different orders is worse than a missing step, because both look complete and only one of them is
 * the order the work happens in — which is why `the web's steps appear here in the web's own order`
 * compares whole lists rather than sets.
 *
 * Nothing designer-specific is here and nothing designer-specific may be added: this application has
 * no design workshops, no sketches, no prototypes, no printed code cards and no designer roster, so
 * the twelve steps the designer portal's journey carries beyond these ten would every one of them be
 * a step describing a screen that was never built.
 */
internal val walkthroughJourney: List<WalkStep> = listOf(
    WalkStep(
        id = "workshop",
        title = "Workshop · Record workshop",
        icon = Icons.Filled.Groups,
        destination = NavDestination.RECORD_WORKSHOP,
        body = "Open or create the workshop first. Every record belongs to a workshop, and new forms preselect your latest one.",
        // One string literal per line, and never a `+` concatenation inside an entry: these are
        // compared one-for-one against `steps.ts`, and a label split across two literals reads as one
        // entry to Kotlin and as two to anybody skimming the diff.
        fields = listOf(
            "Workshop title (required)",
            "Place (required)",
            "Start and end date",
            "Description",
            "Notes",
            "Linked artisans",
            "Crafts covered",
            "Workshop media",
            "Location (GPS fix or map pin)",
        ),
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
        body = "Add the craft being documented. Artisans, products and tools link to it, keeping spellings consistent.",
        fields = listOf(
            "Craft name (required)",
            "Local name",
            "Category",
            "Place",
            "Description",
            "Craft media",
        ),
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
        body = "Who they are, where they work and what they’ve learnt. Everything links back to the artisan. Their Do’s and Don’ts can’t be recovered later.",
        fields = listOf(
            "Name (required)",
            "Local name",
            "Workshop",
            "Craft (required)",
            "Or new craft name",
            "Place (required)",
            "Gender",
            "Phone",
            "Email",
            "Address",
            "Notes",
            "Do's (required)",
            "Don'ts (required)",
            "Artisan media",
            "Location (GPS fix or map pin)",
        ),
        watch = listOf(
            "Do’s and Don’ts are required — one point per line.",
            "Choose a craft or type a new one.",
            "Photo details are added to the notes automatically.",
        ),
    ),
    WalkStep(
        id = "product",
        title = "Product · Record product",
        icon = Icons.Filled.Inventory2,
        destination = NavDestination.RECORD_PRODUCT,
        body = "One thing they make — size, cost and photos. Dimensions, cost and price are what researchers compare across regions.",
        fields = listOf(
            "Product name (required)",
            "Local name",
            "Workshop",
            "Product type",
            "Linked craft (fills craft name)",
            "Craft name (required)",
            "Linked artisan (fills artisan + place)",
            "Artisan name (required)",
            "Place (required)",
            "Time taken to complete",
            "Size",
            "Length (inches)",
            "Breadth (inches)",
            "Height (inches)",
            "Cost of making",
            "Selling price",
            "Market demand",
            "Raw materials used",
            "Main tools used",
            "Function or use",
            "Remarks",
            "Product media",
            "Location (GPS fix or map pin)",
        ),
        watch = listOf(
            "Pick the craft first; it filters the artisans.",
            "\"Document using grid\" measures the piece from a photo.",
            "Linked craft and artisan fill their names for you.",
        ),
    ),
    WalkStep(
        id = "process",
        title = "Process · Document process",
        icon = Icons.Filled.AccountTree,
        destination = NavDestination.DOCUMENT_PROCESS,
        body = "How the product is made, step by step, on video. The steps on video capture knowledge a photo or description loses.",
        fields = listOf(
            "Name of the process (required)",
            "Artisan (required)",
            "Product (required)",
            "Per step: Name of the step (required)",
            "Per step: additional context notes (optional)",
            "Per step: attached media",
        ),
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
        body = "Material, size, maker and replacement cost. Tool makers often vanish before the craft does — record who makes them.",
        fields = listOf(
            "Toolkit name (required)",
            "Local name",
            "English name",
            "Workshop",
            "Linked crafts (fills craft name)",
            "Craft name (required)",
            "Linked artisans (fills artisan + place)",
            "Artisan name (required)",
            "Place (required)",
            "Process used in",
            "Material",
            "Years in use",
            "Height (cm)",
            "Width (cm)",
            "Length (inches)",
            "Breadth (inches)",
            "Height (inches)",
            "Thickness",
            "Weight",
            "Radius",
            "Maker",
            "Tradition type",
            "Replacement cost",
            "Suggestions for improvement",
            "Remarks",
            "Process stages",
            "Tool media",
            "Location (GPS fix or map pin)",
        ),
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
        body = "Work through the sections, recording each answer. Audio is transcribed, so you keep their voice and searchable text.",
        // REGENERATED AGAINST THE FORM ON 2026-09-16, and now held to it rather than to the other
        // register: `shared/questionnaire-form-contract.json` declares this form's boxes once and
        // `backend/tests/test_questionnaire_form_contract.py` measures this list, `steps.ts` and
        // `docs/WALKTHROUGH.md` against that declaration. The register-to-register edge in
        // `WalkthroughStepsTest` stays and still matters — it is what keeps the two clients' guides
        // word for word — but it cannot tell you WHICH side is right, and it passed for months over
        // two lists that were equally wrong.
        //
        // WHAT WAS WRONG, since a list that changes by four entries deserves the account: "Date" was
        // named by all three guides and the form has not had one since the server started deriving
        // `interviewDate` from `recordedAt` (page.tsx:997, and `absent` in the contract) — a guide
        // naming a removed field sends the reader looking for it, and the printed copy does that to
        // somebody with no signal to check against. Workshop, Questionnaire, Status and Interview
        // notes were all missing: four boxes a researcher had not been told to fill in, two of which
        // decide which questionnaire the sitting is filed against and whether it is approved.
        //
        // THE ORDER IS THE WEB'S SCREEN ORDER, which is the contract's, and the handset's form was
        // re-ordered to match it in the same commit.
        fields = listOf(
            "Interview title (required)",
            "Place",
            "Language",
            "Workshop",
            "Questionnaire",
            "Status",
            "Artisans interviewed",
            "Per question: \"Record this question\" audio, or typed answer",
            "Interview notes",
        ),
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
        body = "Files that don’t belong to one record. Keeps field context with your records, not on a phone.",
        fields = listOf(
            "Capture media — images, video, audio and documents",
            "Media title / object name",
            "Linked record type (required)",
            "Linked entry (optional)",
            "Caption",
            "Location (GPS fix or map pin)",
        ),
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
        /*
         * ⚠ ONE SENTENCE HERE IS NOT THE WEB'S, AND THIS IS THE WHOLE REASON.
         *
         * The web's `review.href` is `/review` (steps.ts:286) — a page of its own. This handset has
         * no such page: `MainActivity.kt:1180` is
         *     NavDestination.REVIEW -> screen = screenFor(EntryMode.VIEW_DATA)
         * under its own comment, "Android has no standalone review queue: reviewing happens inside
         * the record browser, which is the surface EntryMode.VIEW_DATA opens and where `canReview`
         * is honoured". The menu row is still called "Review" (AppNavigation.kt:365) and still opens
         * the right capability, so the step is NOT dropped — only the address is corrected. Carrying
         * the web's "the review queue" across unchanged would have a researcher hunting a drawer for
         * a queue that was never built, and concluding they cannot find it rather than that it is
         * not there. The added sentence is marked in `WalkthroughStepsTest.WATCH_REWRITTEN`'s sibling
         * register for the body, `BODY_EXTENDED`, so it cannot be mistaken for drift.
         */
        body = "Submissions come back Approved, Rejected or Sent for revision. A reviewer above you checks every record before it counts. On the phone, \"Review\" opens the record browser.",
        fields = listOf(
            "Pending — submitted, waiting for a reviewer",
            "Approved — final, counted in the dataset",
            "Needs revision — comments explain what to change",
            "Rejected — not going into the dataset",
        ),
        watch = listOf(
            "Below Professor, everything is submitted as Pending.",
            "Fix what the comments say; saving resubmits it.",
            "Reviewers see work from those below them.",
        ),
    ),
    WalkStep(
        id = "view-data",
        title = "View Data · Browse records",
        icon = Icons.Filled.Storage,
        destination = NavDestination.VIEW_DATA,
        body = "Browse records as folders and export any of them. The same records, filed three ways, ready to preview and download.",
        fields = listOf(
            "By workshop — every record filed under the workshop it was made in (the view it opens on)",
            "By uploader — a workshop's records filed under the researcher who uploaded them",
            "By media type — every file filed by what kind of file it is",
            "Download report (.xlsx)",
            "Download any folder as a zip, with content-type filters",
        ),
        watch = listOf(
            "Use the breadcrumb to go back up.",
            "Transcripts preview as formatted text.",
            "Without dataset access, use Browse records instead.",
        ),
    ),
)

/**
 * The opening card.
 *
 * THE COUNT IS DERIVED AND MUST STAY DERIVED. The deck this replaces types it: "Ten steps, in this
 * order" (MainActivity.kt:7765-7767), on the very first thing a new researcher reads. It is right
 * today and nothing keeps it right — it is not connected to the list it describes, so the day the web
 * teaches an eleventh step the opening card is the one place that goes on saying ten, and no test in
 * either client can see it. Interpolating [walkthroughJourney] means the sentence cannot be wrong,
 * and `steps.ts`'s own web page renders its count the same way.
 *
 * Declared AFTER the journey it counts, because top-level properties in one Kotlin file initialise in
 * declaration order and a reference upward would read an empty list and print "0 steps".
 */
private val walkthroughIntro = WalkStep(
    id = "intro",
    title = "The order the work happens in",
    icon = Icons.Filled.Explore,
    body = "${walkthroughJourney.size} steps, in order: the workshop first, then its records, then review. Reopen it any time from the menu.",
)

/**
 * The closing card.
 *
 * A CHECKLIST AND NOT A SUMMARY, because the moment it is read for is the one where somebody is
 * about to get into a vehicle. Every line is a thing that is a phone call if it is missing and
 * another trip to the district if it is missing in the wrong way, and they are phrased as things to
 * look at rather than as things that were said.
 */
private val walkthroughOutro = WalkStep(
    id = "before-you-leave",
    title = "Before you leave the field",
    icon = Icons.Filled.CheckCircle,
    body = "Check every artisan has Do's and Don'ts, products have dimensions and costs, processes have ordered steps with video, tools have a material, maker and cost, and stray media is in Miscellaneous Media. Then make sure everything has uploaded.",
)

/**
 * The whole walkthrough, in the order it is scrolled through: the opening card, the journey, the
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
 * Where [step] sits in the journey, counting from one, or null if it is the opening or closing card.
 *
 * THE ONE PLACE A STEP NUMBER IS ALLOWED TO COME FROM. Hand-written numbers in the titles are what
 * made "10. View Data" the tenth of TWELVE entries in the deck this replaces (MainActivity.kt:7763);
 * deriving the position means inserting a step renumbers everything after it and no human has to
 * notice.
 *
 * Matched on [WalkStep.id] rather than on the whole value, so a screen holding a step it copied or
 * rebuilt still gets the right answer.
 */
internal fun walkthroughStepNumber(step: WalkStep): Int? =
    walkthroughJourney.indexOfFirst { it.id == step.id }.takeIf { it >= 0 }?.plus(1)
