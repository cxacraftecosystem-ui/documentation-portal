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

internal data class WalkStep(
    val id: String,
    val title: String,
    val body: String,
    val icon: ImageVector? = null,
    val destination: NavDestination? = null,
    val why: String = "",
    val watch: List<String> = emptyList(),
)

private fun researcherStep(id: String): WalkStep = walkthroughJourney.first { it.id == id }

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
        why = "Tick Transcribe and translate to keep their words as searchable text.",
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
            "Tick Transcribe and translate on a recording or video to get its text.",
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

private val walkthroughIntro = WalkStep(
    id = "intro",
    title = "The order the work happens in",
    icon = Icons.Filled.Explore,
    body = "${walkthroughJourney.size} steps, in the order you do them. Leave any time, and " +
        "reopen it from Walkthrough.",
)

private val walkthroughOutro = WalkStep(
    id = "before-you-leave",
    title = "Before you leave the field",
    icon = Icons.Filled.CheckCircle,
    body = "Every record has its workshop, craft and photos, and nothing is waiting to upload.",
)

internal val walkthroughSteps: List<WalkStep> =
    listOf(walkthroughIntro) + walkthroughJourney + walkthroughOutro

internal fun walkthroughStepNumber(deck: WalkthroughDeck, step: WalkStep): Int? =
    deck.journey.indexOfFirst { it.id == step.id }.takeIf { it >= 0 }?.plus(1)

internal data class WalkthroughDeck(
    val id: String,
    val name: String,
    val audience: String,
    val journey: List<WalkStep>,
    val steps: List<WalkStep>,
)

internal val WALKTHROUGH_RESEARCHER_DECK = WalkthroughDeck(
    id = "researcher",
    name = "Documenting a craft",
    audience = "Researchers and professors recording in the field",
    journey = walkthroughJourney,
    steps = walkthroughSteps,
)

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

private val walkthroughContributorIntro = WalkStep(
    id = "contributor-intro",
    title = "What you can do from the field",
    icon = Icons.Filled.Explore,
    body = "${walkthroughContributorJourney.size} steps for interviews, media and finding records. " +
        "Recording artisans opens at Researcher.",
)

private val walkthroughContributorOutro = WalkStep(
    id = "contributor-before-you-leave",
    title = "Before you leave the field",
    icon = Icons.Filled.CheckCircle,
    body = "Every answer is recorded, every file is linked, and nothing is waiting to upload.",
)

internal val WALKTHROUGH_CONTRIBUTOR_DECK = WalkthroughDeck(
    id = "contributor",
    name = "Contributing from the field",
    audience = "Crowdsource Volunteers and Field Contributors",
    journey = walkthroughContributorJourney,
    steps = listOf(walkthroughContributorIntro) + walkthroughContributorJourney +
        walkthroughContributorOutro,
)

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

private val walkthroughAdminIntro = WalkStep(
    id = "admin-intro",
    title = "Running the repository",
    icon = Icons.Filled.Explore,
    body = "${walkthroughAdminJourney.size} steps for access, roles and shared lists. Admin rows " +
        "show while admin view is on.",
)

private val walkthroughAdminOutro = WalkStep(
    id = "admin-before-you-sign-off",
    title = "Before you sign off",
    icon = Icons.Filled.CheckCircle,
    body = "Requests are answered, roles are right, and nothing waits on your review.",
)

internal val WALKTHROUGH_ADMIN_DECK = WalkthroughDeck(
    id = "admin",
    name = "Running the repository",
    audience = "Admins and the master admin",
    journey = walkthroughAdminJourney,
    steps = listOf(walkthroughAdminIntro) + walkthroughAdminJourney + walkthroughAdminOutro,
)

internal val WALKTHROUGH_DECKS: List<WalkthroughDeck> =
    listOf(WALKTHROUGH_RESEARCHER_DECK, WALKTHROUGH_CONTRIBUTOR_DECK, WALKTHROUGH_ADMIN_DECK)

internal fun walkthroughDeckFor(user: UserDto?): WalkthroughDeck =
    when {
        user == null -> WALKTHROUGH_RESEARCHER_DECK
        FieldPermissions.isAdmin(user) -> WALKTHROUGH_ADMIN_DECK
        !FieldPermissions.canCreateRecords(user) -> WALKTHROUGH_CONTRIBUTOR_DECK
        else -> WALKTHROUGH_RESEARCHER_DECK
    }
