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
     * TypeScript on one side and Kotlin on the other. This repository has no such test and this
     * workstream cannot add one (the backend is another agent's tree), so an unguarded duplicate map
     * is exactly the failure that Python file exists to prevent. Putting the strings on the step
     * instead means `WalkthroughStepsTest` can IMPORT them — they are `internal`, the test source set
     * is the same module — and compare them to `steps.ts` with no Kotlin parser at all. One register,
     * one guard, in one language.
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
        body = "Open the workshop you are documenting under — or create it — before you record " +
            "anything else. Every record you make is scoped to a workshop. Products, tools and " +
            "interviews all carry a linked workshop, and the Data Browser opens on \"By workshop\", " +
            "which files the whole repository under the workshop it was recorded in. On a create " +
            "form the most recent workshop you have access to is preselected, so getting this right " +
            "once saves you picking it on every screen afterwards.",
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
            "Create the workshop before you leave for the field — it is the container everything " +
                "else drops into.",
            "Records created outside a workshop's date window are flagged as out-of-window and need " +
                "a reviewer's approval.",
        ),
    ),
    WalkStep(
        id = "craft",
        title = "Craft · Add craft",
        icon = Icons.Filled.Brush,
        destination = NavDestination.ADD_CRAFT,
        body = "Add the craft being documented so artisans, products and tools have something to " +
            "hang off. Craft is the shared vocabulary of the repository: artisans link to a craft, " +
            "products and tools inherit the craft name from it, and the Data Browser groups every " +
            "workshop's contents by craft. Adding it once keeps spellings consistent across " +
            "everyone's records.",
        fields = listOf(
            "Craft name (required)",
            "Local name",
            "Category",
            "Place",
            "Description",
            "Craft media",
        ),
        watch = listOf(
            "Check the list first — if the craft already exists, reuse it instead of creating a " +
                "near-duplicate spelling.",
            "The local name matters as much as the English one; record what the community actually " +
                "calls it.",
        ),
    ),
    WalkStep(
        id = "artisan",
        title = "Artisan · Record artisan",
        icon = Icons.Filled.Person,
        destination = NavDestination.RECORD_ARTISAN,
        body = "Record the person: who they are, where they work, how to reach them, and what they " +
            "have learnt. The artisan is the anchor of the dataset. Products, processes, tools and " +
            "questionnaire interviews all link back to an artisan record, and the Do's and Don'ts " +
            "are the artisan's own hard-won craft knowledge — the part of the archive that cannot " +
            "be reconstructed later.",
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
            "Do's (positive prompt) (required)",
            "Don'ts (negative prompt) (required)",
            "Artisan media",
            "Location (GPS fix or map pin)",
        ),
        watch = listOf(
            "Do's and Don'ts are required. Press Enter for each new point — one lesson per line.",
            "You must either select an existing craft or type a new craft name; the form will not " +
                "save with neither.",
            "Photo EXIF is retained and summarised into the notes automatically — you do not need " +
                "to transcribe camera details by hand.",
        ),
    ),
    WalkStep(
        id = "product",
        title = "Product · Record product",
        icon = Icons.Filled.Inventory2,
        destination = NavDestination.RECORD_PRODUCT,
        body = "Record one thing this artisan makes, with its measurements, economics and " +
            "photographs. The product record is where the craft becomes measurable: dimensions, " +
            "cost of making, selling price and market demand are the fields researchers compare " +
            "across regions. Link it to the artisan and the craft and the whole chain stays " +
            "navigable.",
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
            "Pick the linked craft first — the artisan dropdown stays disabled until a craft is " +
                "chosen, then only lists that craft's artisans.",
            // "Document using grid" is this handset's own control, not only the web's:
            // MainActivity.kt:3589 draws the label and FieldRepository.kt:2405 is the auto-fill it
            // feeds. Verbatim from the web is also correct here, which is why it is not in
            // WATCH_REWRITTEN.
            "Use \"Document using grid\" to photograph the piece against the measuring grid: it " +
                "fills length, breadth and height for you and stores the photo as evidence.",
            "Choosing a linked artisan fills the artisan name and place; choosing a linked craft " +
                "fills the craft name.",
        ),
    ),
    WalkStep(
        id = "process",
        title = "Process · Document process",
        icon = Icons.Filled.AccountTree,
        destination = NavDestination.DOCUMENT_PROCESS,
        body = "Walk through how that product is made, one step at a time, filming each step as it " +
            "happens. The process is the craft itself. A product photograph shows the result; the " +
            "step-by-step record with per-step media shows the knowledge — the sequence, the hand " +
            "movements, the judgement calls that a text description always loses.",
        fields = listOf(
            "Name of the process (required)",
            "Artisan (required)",
            "Product (required)",
            "Per step: Name of the step (required)",
            "Per step: additional context notes (optional)",
            "Per step: attached media",
        ),
        watch = listOf(
            // "Add Another Step" is the handset's own button too — MainActivity.kt:7280 — so the
            // web's sentence is true here word for word.
            "Add a step with \"Add Another Step\" and pick Sequential for an ordered stage, or " +
                "Group of activities for things done together.",
            "Video is the preferred format for steps — capture the action as it happens rather " +
                "than posing the result.",
            "Document the process against the product you already recorded, so the two stay linked.",
        ),
    ),
    WalkStep(
        id = "tool",
        title = "Tool · Record tool",
        icon = Icons.Filled.Build,
        destination = NavDestination.RECORD_TOOL,
        body = "Record the toolkit the artisan uses: what it is made of, how big it is, who made " +
            "it, what it costs to replace. Tools are the most quietly endangered part of a craft — " +
            "the maker of a tool often disappears before the craft does. Replacement cost, maker " +
            "and tradition type are the fields that record whether the toolchain behind the craft " +
            "is still alive.",
        fields = listOf(
            "Toolkit name (required)",
            "Local name",
            "English name",
            "Workshop",
            "Linked craft (fills craft name)",
            "Craft name (required)",
            "Linked artisan (fills artisan + place)",
            "Artisan name (required)",
            "Place (required)",
            "Process used in",
            "Material",
            "Years in use",
            "Height",
            "Width",
            "Length (inches)",
            "Breadth (inches)",
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
            "Fill only the dimensions that make sense for the tool — a blade has a length and " +
                "thickness, a wheel has a radius.",
            "\"Process stages\" archives your captures in order as STAGE_STEP_1, STAGE_STEP_2, … " +
                "so shoot them in sequence.",
            // "Assign tools to artisans" is this handset's own menu row, at AppNavigation.kt:364,
            // with the identical label. Verbatim holds.
            "You can also hand tools to specific artisans later from \"Assign tools to artisans\" " +
                "— for your own artisans, ones shared with you for editing, or any artisan if you " +
                "are an admin.",
        ),
    ),
    WalkStep(
        id = "questionnaire",
        title = "Questionnaire · Take interview",
        icon = Icons.Filled.Quiz,
        destination = NavDestination.TAKE_INTERVIEW,
        body = "Sit down with the artisan and work through the interview sections, recording each " +
            "answer as audio. The questionnaire is the artisan speaking in their own voice and " +
            "their own language. Recorded audio is auto-transcribed on the server, so you get both " +
            "the original recording and searchable text without typing during the interview.",
        fields = listOf(
            "Interview title (required)",
            "Date",
            "Place",
            "Language",
            "Primary artisan",
            "Additional artisans",
            "Per question: \"Record this question\" audio, or typed answer",
        ),
        watch = listOf(
            "There is one interview per exact set of artisans. If an entry already exists for that " +
                "set, saving adds your answers to it — it never creates a duplicate.",
            "Answer only the questions actually asked; empty questions stay open for whoever picks " +
                "the interview up next.",
            "Questions already answered by someone else can only be changed by that contributor or " +
                "an admin.",
            // The completion matrix is on the handset form too — MainActivity.kt:1717 carries the
            // admin-view state into it by that name.
            "Use \"Check completion\" at the top of the screen to see the artisans × sections " +
                "matrix and find the gaps.",
        ),
    ),
    WalkStep(
        id = "media",
        title = "Miscellaneous Media · Upload media",
        icon = Icons.Filled.PermMedia,
        destination = NavDestination.UPLOAD_MEDIA,
        body = "Upload the photographs, video, audio and files that do not belong to any single " +
            "record. Field work produces context that no form has a slot for: the road into the " +
            "village, the market, an unplanned conversation. Miscellaneous Media keeps that " +
            "material inside the repository instead of on a phone that gets wiped.",
        fields = listOf(
            "Capture media — images, video, audio and documents",
            "Media title / object name",
            "Linked record type (required)",
            "Linked entry (optional)",
            "Caption",
            "Location (GPS fix or map pin)",
        ),
        watch = listOf(
            "Upload stays disabled until you pick a Linked record type. If the file belongs to " +
                "nothing in particular, pick \"Miscellaneous Media\" and leave the entry blank.",
            "Audio uploaded here is queued for transcription after upload, exactly like interview " +
                "audio.",
            "If the file does turn out to belong to a record, link it — misc media can be attached " +
                "to a record afterwards.",
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
        body = "Everything you submit goes into the review queue and comes back Approved, " +
            "Rejected, or Sent for revision. Review is what turns a pile of field notes into a " +
            "dataset anyone can cite. It also means you are never the last check on your own work " +
            "— a reviewer above your tier reads every record before it counts as final. This " +
            "handset has no separate review queue: the \"Review\" row opens the record browser, " +
            "which is the one surface where a reviewer can read a submission and act on it.",
        fields = listOf(
            "Pending — submitted, waiting for a reviewer",
            "Approved — final, counted in the dataset",
            "Needs revision — comments explain what to change",
            "Rejected — not going into the dataset",
        ),
        watch = listOf(
            "Below Professor the status chip is locked: whatever you create is submitted as " +
                "Pending. That is normal, not an error.",
            "\"Send for revision\" always carries mandatory comments — read them, fix the record, " +
                "and saving resubmits it as Pending.",
            "Reviewers only see submissions from contributors ranked strictly below them; the " +
                "master admin sees everyone.",
        ),
    ),
    WalkStep(
        id = "view-data",
        title = "View Data · Browse records",
        icon = Icons.Filled.Storage,
        destination = NavDestination.VIEW_DATA,
        body = "Browse the whole repository as a directory tree and export a report of any " +
            "subtree. This is where the documentation stops being data entry and starts being " +
            "research material: the same records, filed three different ways, previewable in place " +
            "and downloadable as a spreadsheet.",
        fields = listOf(
            "By workshop — every record filed under the workshop it was made in (the view it opens on)",
            "By uploader — a workshop's records filed under the researcher who uploaded them",
            "By media type — every file filed by what kind of file it is",
            "Download report (.xlsx)",
            "Download any folder as a zip, with content-type filters",
        ),
        watch = listOf(
            "Pick a folder, then use the breadcrumb to move back up — the tree loads lazily as you " +
                "expand it.",
            "Transcripts and AI text render as formatted Markdown in the preview pane, not raw text.",
            /*
             * ⚠ THE LAST FIVE WORDS ARE NOT THE WEB'S. The web ends "use Search to find records
             * instead" (steps.ts:321). There is no row called "Search" in this menu — the same
             * capability is `NavDestination.BROWSE_RECORDS`, labelled "Browse records"
             * (AppNavigation.kt:349) and ungated (`everyone`), which is precisely why it is the
             * right thing to point a refused researcher at. Sending them to a name the drawer does
             * not use would turn a working fallback into a dead end at the exact moment they have
             * just been told no. Declared in `WalkthroughStepsTest.WATCH_REWRITTEN`.
             */
            "Dataset download is a granted permission. If your role does not have it the browser " +
                "shows a restricted notice — use Browse records to find records instead.",
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
    body = "${walkthroughJourney.size} steps, in this order — the workshop first, then the " +
        "records it contains, then reading the whole thing back. It is the same journey the " +
        "Walkthrough teaches on the web, so a colleague working on a laptop is reading what you " +
        "are reading. The order is the thing worth learning: get it wrong and the pickers inside " +
        "the later screens have nothing to offer you, which costs a return trip rather than five " +
        "minutes. Work down it once and you should not need this again. You can leave at any " +
        "point, and you can reopen it from the menu without losing whatever form you are in the " +
        "middle of.",
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
    body = "A missing field is a phone call; a missing recording is another trip. Every artisan " +
        "you spoke to has a record, with their Do's and Don'ts in it. Every product you " +
        "photographed has its dimensions and its costs. Every process has its steps in order and " +
        "the steps have video. Every tool has a material, a maker and a replacement cost. The " +
        "interview has no unexplained gaps. Anything you shot that has no home is in " +
        "Miscellaneous Media. And before the signal goes for good, check that everything you " +
        "recorded has actually left this handset — a record still waiting here is a record the " +
        "repository has never seen.",
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
