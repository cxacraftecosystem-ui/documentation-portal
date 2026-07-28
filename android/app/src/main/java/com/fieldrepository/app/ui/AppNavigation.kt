package com.fieldrepository.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified

import com.fieldrepository.app.data.UserDto

/*
 * Field Repository — one navigation model for the phone, mirroring the web.
 *
 * WHAT THE WEB DOES (frontend/components/DynamicIslandNav.tsx): a floating "dynamic island" pill
 * pinned to the top of the viewport. Dashboard and Walkthrough sit loose in the bar; everything else
 * hangs off four hover dropdowns — Record, Browse, Admin, Account — and a full sheet repeats the
 * same filtered list for keyboard users. Each destination carries its OWN role predicate, and an
 * entry that fails it is NOT RENDERED (never rendered disabled), so the menu can only ever offer
 * what the API would actually serve.
 *
 * WHAT THIS DOES, AND WHAT WAS ADAPTED. The information architecture is copied exactly: the same
 * entries, the same four groups, the same labels, the same ordering, the same predicates, the same
 * "entitlement first, admin view second" rule. What is NOT copied is the floating pill itself.
 * It is a POINTER interface — its dropdowns open on hover and close on mouse-leave, and it costs a
 * permanent strip of vertical space. On a phone there is no hover, and the app already has a
 * right-anchored ModalNavigationDrawer that the whole app reaches through one thumb-friendly icon.
 * So the pill's CONTENT moves into that drawer, rendered as the same four labelled groups with the
 * two standalone roots pinned above them — which is precisely what the web's own full sheet is,
 * except grouped rather than a flat two-column grid. Nothing is added, removed, or renamed.
 *
 * Three entries deliberately differ from what the Android drawer used to show; see
 * [NavDestination] for the per-entry reasoning.
 */

// ---------------------------------------------------------------------------------------------
// Capability rules — the Kotlin mirror of frontend/lib/permissions.ts and backend/app/core/deps.py
// ---------------------------------------------------------------------------------------------

/**
 * The six-tier ladder and the capability predicates the nav gates on.
 *
 * Wrapped in an object rather than left as top-level functions on purpose: `MainActivity.kt` already
 * declares file-private `roleRank`/`isAdminUser`/`canCreateRecords` helpers, and top-level twins here
 * would read like a second, competing source of truth. These MUST agree with the server in both
 * directions — a rule stricter than the backend hides a screen from somebody entitled to it, a looser
 * one lets them fill in a form and eat a 403 on save.
 */
object FieldPermissions {
    const val RANK_CROWDSOURCE_VOLUNTEER = 10
    const val RANK_FIELD_CONTRIBUTOR = 20
    const val RANK_RESEARCHER = 30
    const val RANK_PROFESSOR = 40
    const val RANK_ADMIN = 50
    const val RANK_MASTER_ADMIN = 60

    private val RANKS = mapOf(
        "CROWDSOURCE_VOLUNTEER" to RANK_CROWDSOURCE_VOLUNTEER,
        "FIELD_CONTRIBUTOR" to RANK_FIELD_CONTRIBUTOR,
        "RESEARCHER" to RANK_RESEARCHER,
        "PROFESSOR" to RANK_PROFESSOR,
        "ADMIN" to RANK_ADMIN,
        "MASTER_ADMIN" to RANK_MASTER_ADMIN
    )

    /** Byte-for-byte the server's `ROLE_LABELS` (and the web's `ROLE_LABELS`). */
    private val LABELS = mapOf(
        "CROWDSOURCE_VOLUNTEER" to "Crowdsource Volunteer",
        "FIELD_CONTRIBUTOR" to "Field Contributor",
        "RESEARCHER" to "Researcher",
        "PROFESSOR" to "Professor",
        "ADMIN" to "Admin",
        "MASTER_ADMIN" to "Master Admin"
    )

    fun rank(role: String?): Int = RANKS[role] ?: 0
    fun label(role: String?): String = LABELS[role] ?: role.orEmpty()

    /** `is_admin` — admin and master admin. */
    fun isAdmin(user: UserDto): Boolean = rank(user.role) >= RANK_ADMIN

    /** `is_master_admin`. */
    fun isMasterAdmin(user: UserDto): Boolean = user.role == "MASTER_ADMIN"

    /**
     * `require_record_creator` — OPENING an artisan, product, process, tool or interview. Researcher
     * and above, matching `can_create_records` and the web's `canCreateRecords`.
     *
     * The two tiers below POPULATE records instead of opening them, and none of what they do is
     * gated by this: uploading media, answering an existing interview and commenting all stay open.
     * So hiding a "Record …" entry from them never hides a contribution path.
     */
    fun canCreateRecords(user: UserDto): Boolean = rank(user.role) >= RANK_RESEARCHER

    /**
     * `require_craft_manager` — Professor and above, RANK ALONE.
     *
     * The `canManageCrafts` column is deliberately not consulted. The server stopped reading it
     * (`can_manage_crafts`, deps.py) because a per-user grant that lifted a researcher over the
     * taxonomy was invisible in the role column; ORing it in here would put "Add craft" in the menu
     * of somebody the API refuses, which is the one thing this table exists not to do.
     */
    fun canManageCrafts(user: UserDto): Boolean = rank(user.role) >= RANK_PROFESSOR

    /** `require_workshop_manager` — Professor and above, rank alone; see [canManageCrafts]. */
    fun canManageWorkshops(user: UserDto): Boolean = rank(user.role) >= RANK_PROFESSOR

    /** `require_professor` on GET/PATCH /users. */
    fun canManageUsers(user: UserDto): Boolean = rank(user.role) >= RANK_PROFESSOR

    /** `require_reviewer` — anyone with somebody beneath them on the ladder, or a grant. */
    fun canReview(user: UserDto): Boolean =
        rank(user.role) >= RANK_FIELD_CONTRIBUTOR || user.canReview

    /** `require_dataset_downloader` — Professor and above, or an explicit grant. */
    fun canDownloadDataset(user: UserDto): Boolean =
        rank(user.role) >= RANK_PROFESSOR || user.canDownloadDataset
}

// ---------------------------------------------------------------------------------------------
// The model
// ---------------------------------------------------------------------------------------------

/**
 * The web's `NAV_GROUPS`, in render order. "Account" sits last because it holds what belongs to the
 * PERSON rather than to the repository — their own settings and their feedback.
 */
enum class NavGroup(val label: String) {
    RECORD("Record"),
    BROWSE("Browse"),
    ADMIN("Admin"),
    ACCOUNT("Account")
}

/**
 * Every destination the menu can reach. `MainActivity` owns the routing table (it holds the private
 * `EntryMode`/`Screen` types), so this enum is the contract between the two.
 *
 * Four entries read differently from the Android drawer that preceded them, all four to match the
 * web rather than to invent anything:
 *
 *  - [BROWSE_RECORDS] is the web's `/search`. The Android drawer called it "Search"; the web calls
 *    the same capability "Browse records", so that is the label now — it maps to `EntryMode.SEARCH`.
 *  - [VIEW_DATA] is the web's `/data`, the whole repository as a directory tree, which on Android is
 *    `EntryMode.DATA_BROWSER` (the drawer used to label that one "Data Browser").
 *  - [REVIEW] is new to the menu. The web has a dedicated `/review` queue; Android has no such
 *    screen — reviewing happens INSIDE the record browser, which is why `HomeScreen` hands
 *    `canReview` to `ViewDataScreen`. So this entry maps to `EntryMode.VIEW_DATA`, the one surface
 *    where a reviewer can actually act, and it is the reason that mode is absent from this list
 *    under its own name (its `actionTitle` is the string "Browse records", which would otherwise
 *    collide head-on with [BROWSE_RECORDS] above while opening a different screen).
 *  - "Request workshop access" is gone from the menu, because the web has no such nav entry — it
 *    lives inside the Workshops page and the admin hub. Its dashboard tile is untouched, so the
 *    screen is still one tap from the dashboard.
 */
enum class NavDestination {
    DASHBOARD,
    WALKTHROUGH,
    RECORD_ARTISAN,
    RECORD_PRODUCT,
    DOCUMENT_PROCESS,
    RECORD_TOOL,
    TAKE_INTERVIEW,
    UPLOAD_MEDIA,
    ADD_CRAFT,
    RECORD_WORKSHOP,
    MY_ACTIVITY,
    TASKS,
    BROWSE_RECORDS,
    /**
     * The web's `/map` — the repository read as geography. The third way of reading the whole corpus
     * and it sits next to the other two: `/search` reads it as a list, `/data` as a folder tree,
     * `/map` as a place. Ungated, because `GET /map/points` asks for nothing but a login: reading the
     * repository is open to every signed-in account (`records.viewable_where`), and what the pins may
     * NOT carry is bounded inside that route rather than at this entry.
     */
    MAP,
    VIEW_DATA,
    /**
     * The web's `/questionnaire/consolidated`. BROWSE and not RECORD: an interview is stored once per
     * exact set of artisans, so one artisan's answers are scattered over several entries and this is
     * the only surface that reads them back as one document. "Take interview" writes; this only reads.
     */
    CONSOLIDATED_QUESTIONNAIRE,
    SHARE_DATA_ACCESS,
    ASSIGN_TOOLS,
    REVIEW,
    SETTINGS_HUB,
    MANAGE_USERS,
    SETTINGS,
    GIVE_FEEDBACK
}

data class NavEntry(
    val destination: NavDestination,
    /** The EXACT web label. Both clients speak one language; never reword one side only. */
    val label: String,
    val icon: ImageVector,
    /** `null` = a standalone entry above the groups (the web renders these loose in the bar). */
    val group: NavGroup?,
    /**
     * The entitlement this destination needs. When it returns false the entry is NOT RENDERED —
     * never rendered disabled — so the menu only ever offers what the API would actually allow.
     */
    val can: (UserDto) -> Boolean,
    /** The backend dependency `can` mirrors (backend/app/core/deps.py); keep the two in step. */
    val gate: String,
    /** Admin-tier chrome: admins additionally need admin view ON. Never widens [can]. */
    val adminSurface: Boolean = false
)

private val everyone: (UserDto) -> Boolean = { true }

/**
 * The single source of truth for navigation, item-for-item and in the same order as the web's
 * `NAV_ITEMS`. One list drives the whole menu, so a hidden entry cannot reappear somewhere else.
 */
val FIELD_NAV_ITEMS: List<NavEntry> = listOf(
    NavEntry(NavDestination.DASHBOARD, "Dashboard", Icons.Filled.Dashboard, null, everyone, "get_current_user"),
    // Onboarding, deliberately ungated: the Walkthrough teaches the documentation process itself, so
    // it has to reach the people who have not earned any capability yet — a crowdsource volunteer on
    // their first day needs it MORE than an admin does.
    NavEntry(NavDestination.WALKTHROUGH, "Walkthrough", Icons.Filled.Explore, null, everyone, "none (static page)"),

    // Record — every entry here CREATES something, so it follows the create dependency, not the list
    // one. Hiding an entry therefore never hides the DATA behind it: "Browse records" and "View Data"
    // remain the read route to the same records.
    NavEntry(NavDestination.RECORD_ARTISAN, "Record artisan", Icons.Filled.Person, NavGroup.RECORD, FieldPermissions::canCreateRecords, "require_record_creator"),
    NavEntry(NavDestination.RECORD_PRODUCT, "Record product", Icons.Filled.Inventory2, NavGroup.RECORD, FieldPermissions::canCreateRecords, "require_record_creator"),
    NavEntry(NavDestination.DOCUMENT_PROCESS, "Document process", Icons.Filled.AccountTree, NavGroup.RECORD, FieldPermissions::canCreateRecords, "require_record_creator"),
    NavEntry(NavDestination.RECORD_TOOL, "Record tool", Icons.Filled.Build, NavGroup.RECORD, FieldPermissions::canCreateRecords, "require_record_creator"),
    // Answering an interview is open to everyone — volunteers contribute answers and media.
    NavEntry(NavDestination.TAKE_INTERVIEW, "Take interview", Icons.Filled.Quiz, NavGroup.RECORD, everyone, "get_current_user"),
    NavEntry(NavDestination.UPLOAD_MEDIA, "Upload media", Icons.Filled.PermMedia, NavGroup.RECORD, everyone, "get_current_user"),
    NavEntry(NavDestination.ADD_CRAFT, "Add craft", Icons.Filled.Brush, NavGroup.RECORD, FieldPermissions::canManageCrafts, "require_craft_manager"),
    NavEntry(NavDestination.RECORD_WORKSHOP, "Record workshop", Icons.Filled.Groups, NavGroup.RECORD, FieldPermissions::canManageWorkshops, "require_workshop_manager"),

    // Browse
    NavEntry(NavDestination.MY_ACTIVITY, "My Activity", Icons.Filled.Timeline, NavGroup.BROWSE, everyone, "get_current_user"),
    // Everyone can be a task assignee; the "assign" half is gated inside the screen.
    NavEntry(NavDestination.TASKS, "Tasks", Icons.AutoMirrored.Filled.Assignment, NavGroup.BROWSE, everyone, "get_current_user"),
    NavEntry(NavDestination.BROWSE_RECORDS, "Browse records", Icons.Filled.Search, NavGroup.BROWSE, everyone, "get_current_user"),
    // The third way of reading the whole corpus, in the web's own position in this group: /search reads
    // it as a list, /data as a folder tree, /map as a place. Ungated to match /search, because
    // GET /map/points asks for nothing but a login — reading the repository is open to every signed-in
    // account. A volunteer and a professor open this screen and see the SAME numbers, which is the point
    // of pooling the fieldwork and not a gap in this predicate. What stays earned is taking data out.
    NavEntry(NavDestination.MAP, "Map", Icons.Filled.Map, NavGroup.BROWSE, everyone, "get_current_user"),
    NavEntry(NavDestination.VIEW_DATA, "View Data", Icons.Filled.Storage, NavGroup.BROWSE, FieldPermissions::canDownloadDataset, "require_dataset_downloader"),
    // Browse rather than Record, for the reason on [NavDestination.CONSOLIDATED_QUESTIONNAIRE].
    // GET /questionnaire/artisans/{id}/consolidated asks for nothing but a login.
    NavEntry(NavDestination.CONSOLIDATED_QUESTIONNAIRE, "Consolidated questionnaire", Icons.Filled.Layers, NavGroup.BROWSE, everyone, "get_current_user"),
    NavEntry(NavDestination.SHARE_DATA_ACCESS, "Share data access", Icons.Filled.Share, NavGroup.BROWSE, everyone, "get_current_user"),
    // Linking a tool to an artisan needs a tool or an artisan of your own — both need record creation.
    // The endpoint only requires a login and then checks ownership per artisan, so this is the closest
    // STATIC mirror of a dynamic rule: nobody below Researcher can own either side. Same predicate as
    // the web's own "Assign tools to artisans" entry, which is why it moved with `canCreateRecords`.
    NavEntry(NavDestination.ASSIGN_TOOLS, "Assign tools to artisans", Icons.Filled.Handyman, NavGroup.BROWSE, FieldPermissions::canCreateRecords, "get_current_user + owner/EDIT-grant/admin per artisan"),
    // NOT adminSurface, matching the web: reviewing is a Field Contributor capability an admin merely
    // also holds, so "browse as an ordinary user" must not take the link away while the screen stays open.
    NavEntry(NavDestination.REVIEW, "Review", Icons.Filled.Visibility, NavGroup.BROWSE, FieldPermissions::canReview, "require_reviewer"),

    // Admin — capability holders below admin (professors, grantees) keep these permanently; admins,
    // who own the toggle, see them only while admin view is ON.
    NavEntry(NavDestination.SETTINGS_HUB, "Settings hub", Icons.Filled.Tune, NavGroup.ADMIN, FieldPermissions::isAdmin, "require_admin", adminSurface = true),
    NavEntry(NavDestination.MANAGE_USERS, "Manage users", Icons.Filled.ManageAccounts, NavGroup.ADMIN, FieldPermissions::canManageUsers, "require_professor", adminSurface = true),

    // Account — personal, so nothing here is role-gated. On Android "Settings" is the Appearance &
    // accessibility screen: the web's /settings is two columns in one page, and the master admin's
    // global column is the Android admin hub's own tool. Gating this would leave every non-admin with
    // NO route to their own accessibility switches, and PUT /preferences/me asks for nothing but a login.
    NavEntry(NavDestination.SETTINGS, "Settings", Icons.Filled.Settings, NavGroup.ACCOUNT, everyone, "get_current_user (PUT /preferences/me)"),
    NavEntry(NavDestination.GIVE_FEEDBACK, "Give app feedback", Icons.Filled.RateReview, NavGroup.ACCOUNT, everyone, "get_current_user (PUT /feedback/me)")
)

/**
 * Entitlement first, admin view second: [NavEntry.can] is consulted before the toggle is even looked
 * at, so switching admin view ON can never surface a destination the API would 403 — it only hides
 * admin chrome from an admin browsing as an ordinary user.
 */
fun isNavItemVisible(item: NavEntry, user: UserDto?, adminMode: Boolean): Boolean {
    if (user == null || !item.can(user)) return false
    if (item.adminSurface && FieldPermissions.isAdmin(user)) return adminMode
    return true
}

/** The visible entries for [user], in web order. */
fun visibleNavItems(user: UserDto?, adminMode: Boolean): List<NavEntry> =
    FIELD_NAV_ITEMS.filter { isNavItemVisible(it, user, adminMode) }

// ---------------------------------------------------------------------------------------------
// The floating island bar
// ---------------------------------------------------------------------------------------------

/**
 * The web's dynamic island, on the phone.
 *
 * A previous pass copied only the island's CONTENTS into the drawer and left the pill itself on the
 * web, on the reasoning that a hover bar is a pointer interface. That reasoning was about hover, not
 * about the bar: the value of the island is that Dashboard, the Walkthrough and the four groups are
 * VISIBLE — a newcomer can see the shape of the app without opening anything. Hiding all of it
 * behind one hamburger costs exactly that, which is why it is back, adapted rather than transplanted:
 *
 *  - taps, not hover. Each group chip opens a [DropdownMenu] anchored under it and closes on
 *    selection or on an outside tap.
 *  - it adapts, it does not scroll away. An earlier pass wrapped the whole bar in a horizontal
 *    scroller and called that the answer to a narrow screen. On a 360dp phone that showed the
 *    wordmark, Dashboard and part of Walkthrough — all four groups, the admin toggle and the
 *    hamburger sat off-screen behind a gesture nobody discovers, which reads to the person holding
 *    the phone as a navigation bar that was never built. A chip the user cannot see is a chip that
 *    does not exist. So the bar now sheds width in [IslandDensity] steps until it fits, and the
 *    order is what makes a landscape phone read like the web: first the wordmark, then the SIZE of
 *    the labels, and only then the labels themselves — the groups' first, the roots' last. Every
 *    step is priced by measuring the real strings (see [rememberIslandWidths]), never by guessing at
 *    them. The scroller survives only as an overflow net for the devices and font scales that defeat
 *    even the smallest step, and the hamburger is pinned OUTSIDE it so the route to the full drawer
 *    can never be the thing that scrolls away.
 *  - the drawer stays. It is still the full list in one thumb-reachable place, and the hamburger at
 *    the right of the pill is how you get to it — same as the web's sheet.
 *  - on a phone it gets out of the way. The web bar floats over a viewport that scrolls beneath it;
 *    a phone has no spare 60dp to float anything over, so once the page has moved (see
 *    [ISLAND_COLLAPSE_SCROLL]) the destinations fade upward and the bar becomes a header — mark,
 *    wordmark, hamburger — and comes back whole at the top of the page. A tablet is wide enough to
 *    be the web and keeps the labelled bar the whole way down.
 *
 * Renders exactly [visibleNavItems], so it can no more offer an unauthorised destination than the
 * drawer can. The admin-view chip mirrors the web's, including reading "Admin view: OFF" while off
 * rather than disappearing — an admin needs to see that the toggle is why their menu got shorter.
 */
/** One destination in the island: what it says, what it looks like, what it does. */
class IslandEntry(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/**
 * One dropdown chip and the destinations behind it — the web's Record / Browse / Admin / Account.
 *
 * [icon] defaults from the label because the web has no group icons to copy and the caller should not
 * have to invent one: below [IslandDensity.ICON_GROUPS] the glyph IS the chip, so every group needs
 * one whether or not whoever built the list thought about narrow screens.
 */
class IslandGroup(
    val label: String,
    val entries: List<IslandEntry>,
    val icon: ImageVector = islandGroupIcon(label)
)

/**
 * The glyph that stands in for a group label. Deliberately distinct from every icon a chip beside it
 * can carry — the Browse folder against the Dashboard grid, the Account bust against the Walkthrough
 * compass — because at the narrowest density the user is telling them apart by shape alone.
 */
private fun islandGroupIcon(label: String): ImageVector = when (label) {
    NavGroup.RECORD.label -> Icons.Filled.EditNote
    NavGroup.BROWSE.label -> Icons.Filled.FolderOpen
    NavGroup.ADMIN.label -> Icons.Filled.AdminPanelSettings
    NavGroup.ACCOUNT.label -> Icons.Filled.AccountCircle
    else -> Icons.Filled.Apps
}

/**
 * How much of itself the bar can afford to show. Each step gives up the widest thing that is not
 * load-bearing, in the order a reader would miss it least.
 */
private enum class IslandDensity {
    /** Everything, as the web shows it: wordmark, and every chip carrying its label. */
    FULL,

    /** The wordmark goes and the mark stays — decoration for width, and the logo is still home. */
    MARK,

    /**
     * Every chip still carries its word, but tightened — smaller type, less padding either side.
     * The step that stands between a landscape phone and a bar of anonymous glyphs: a label set two
     * points down is still a label, and a screen that can nearly afford the words should be made to
     * buy them before it is allowed to drop any.
     */
    MARK_COMPACT,

    /** Group chips shrink to their glyph, which is what buys all four a place beside the roots. */
    ICON_GROUPS,

    /** Nothing in the bar but glyphs. The only arrangement that fits a 360dp phone whole. */
    ICON_ONLY
}

/*
 * The bar's fixed costs, named once.
 *
 * Every one of these is spent TWICE: by the layout further down that draws the bar, and by
 * [rememberIslandWidths], which adds them up to decide which tier the layout is allowed to draw.
 * Two hand-kept copies of "a labelled chip pays 10dp either side" is precisely how a bar comes to
 * promise a tier it cannot fit, so both readings come from these.
 */
private val ISLAND_CHIP_PADDING = 10.dp
private val ISLAND_CHIP_PADDING_COMPACT = 6.dp
private val ISLAND_CHIP_PADDING_GLYPH = 7.dp
/** Between a chip's glyph and its word. */
private val ISLAND_CHIP_GAP = 4.dp
/** Between one chip and the next. */
private val ISLAND_CHIP_SPACING = 2.dp
private val ISLAND_CHIP_ICON = 15.dp
private val ISLAND_CHIP_ICON_COMPACT = 13.dp
/** A glyph standing in for a word is read at arm's length, so it is drawn larger. */
private val ISLAND_CHIP_ICON_GLYPH = 18.dp

private val ISLAND_MARK_SIZE = 24.dp
private val ISLAND_BRAND_START = 4.dp
private val ISLAND_BRAND_END = 8.dp
private val ISLAND_BRAND_START_TIGHT = 2.dp
private val ISLAND_BRAND_END_TIGHT = 4.dp
/** Between the mark and the wordmark it carries at FULL density. */
private val ISLAND_WORDMARK_GAP = 7.dp

private val ISLAND_BAR_PADDING = 8.dp
private val ISLAND_BAR_PADDING_TIGHT = 6.dp
private val ISLAND_BAR_SPACING = 2.dp

/** Material's minimum tap target. Also the height of the phone bar's icon row, for the same reason. */
private val ISLAND_TOUCH_TARGET = 48.dp
/** The hamburger on a tablet, where a pointer or a wider bar makes the extra 12dp cost more than it buys. */
private val ISLAND_MENU_TABLET = 36.dp

/** The admin chip's two captions, hoisted because the width ladder has to measure one of them. */
private const val ISLAND_ADMIN_ON = "Admin view: ON"
private const val ISLAND_ADMIN_OFF = "Admin view: OFF"

/**
 * Where a phone stops and a tablet starts — 600dp of the SHORTEST screen edge, which is the same
 * number and the same reading as Android's own `sw600dp` resource qualifier.
 *
 * It used to be read off the bar's current width against the web's 768dp `md` breakpoint, and that
 * made a phone held sideways a tablet. This handset's window is 384dp portrait and 777dp landscape,
 * so rotating it crossed the line: the bar stopped collapsing on scroll in the one orientation with
 * the least vertical room to spare — 359dp of it — and a landscape reader kept a permanent header
 * they never asked for. The shortest edge does not change when the device turns, so this cannot.
 */
private const val ISLAND_TABLET_SMALLEST_WIDTH = 600

@Composable
private fun isTabletDevice(): Boolean =
    LocalConfiguration.current.smallestScreenWidthDp >= ISLAND_TABLET_SMALLEST_WIDTH

/**
 * What each tier of the bar actually measures, for the content THIS user is being shown at THIS
 * font scale — the numbers the density ladder is chosen from.
 *
 * This replaces a block of arithmetic that priced text at "~7.5dp a character" and then padded every
 * threshold upwards to cover the guess, and the guess was wrong in both directions at once. On this
 * handset in landscape the bar gets 745.6dp. A master admin's labelled bar — two roots, four groups,
 * the admin chip — measures 814.6dp at labelLarge, not the 786dp the arithmetic claimed, so the words
 * genuinely do not fit at full size; but the same bar at labelMedium and 6dp of chip padding measures
 * 675.4dp, which fits with room to spare. The old ladder could express neither fact: it read 745.6
 * against a guessed 860dp threshold and dropped straight to glyph groups.
 *
 * A [androidx.compose.ui.text.TextMeasurer] knows the answer exactly — in the face, the weight and
 * the system font scale actually in force — so each threshold IS the requirement and there is nothing
 * left over to guess with. It also retires the old `* fontScale` multiplier, which existed only
 * because a dp guess could not see the font scale; a measurement can.
 */
private class IslandWidths(
    val full: Dp,
    val mark: Dp,
    val markCompact: Dp,
    val iconGroups: Dp,
    /** The wordmark on its own, which the collapsed header sizes itself against. */
    val wordmark: Dp
)

@Composable
private fun rememberIslandWidths(
    rootLabels: List<String>,
    groupLabels: List<String>,
    /** Null when this user has no admin chip; otherwise the WIDER of its two captions. */
    adminLabel: String?,
    menuWidth: Dp
): IslandWidths {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelStyle = MaterialTheme.typography.labelLarge
    val compactStyle = MaterialTheme.typography.labelMedium
    val wordmarkStyle = MaterialTheme.typography.titleSmall
    return remember(
        rootLabels, groupLabels, adminLabel, menuWidth,
        density, measurer, labelStyle, compactStyle, wordmarkStyle
    ) {
        fun textWidth(text: String, style: TextStyle): Dp =
            with(density) { measurer.measure(text, style).size.width.toDp() }

        fun total(widths: List<Dp>): Dp =
            widths.fold(0.dp) { sum, w -> sum + w } +
                ISLAND_CHIP_SPACING * (widths.size - 1).coerceAtLeast(0)

        // Everything outside the destination chips: the bar's own padding, the two gaps around the
        // strip, the brand block at its normal size and the hamburger.
        val fixed = ISLAND_BAR_PADDING * 2 + ISLAND_BAR_SPACING * 2 +
            ISLAND_BRAND_START + ISLAND_MARK_SIZE + ISLAND_BRAND_END + menuWidth

        // A labelled bar, at whichever type size and padding the tier is paying. Group chips add a
        // caret, the admin chip a leading eye; the roots are word and padding alone.
        fun labelled(style: TextStyle, pad: Dp, icon: Dp): Dp = fixed + total(
            rootLabels.map { pad * 2 + textWidth(it, style) } +
                groupLabels.map { pad * 2 + textWidth(it, style) + ISLAND_CHIP_GAP + icon } +
                listOfNotNull(adminLabel?.let { pad * 2 + icon + ISLAND_CHIP_GAP + textWidth(it, style) })
        )

        val glyphChip = ISLAND_CHIP_PADDING_GLYPH * 2 + ISLAND_CHIP_ICON_GLYPH
        val mark = labelled(labelStyle, ISLAND_CHIP_PADDING, ISLAND_CHIP_ICON)
        val wordmark = textWidth("Field Repository", wordmarkStyle)
        IslandWidths(
            full = mark + ISLAND_WORDMARK_GAP + wordmark,
            mark = mark,
            markCompact = labelled(compactStyle, ISLAND_CHIP_PADDING_COMPACT, ISLAND_CHIP_ICON_COMPACT),
            // Roots keep their words, everything else is one glyph — the step that buys all four
            // groups a place beside them.
            iconGroups = fixed + total(
                rootLabels.map { ISLAND_CHIP_PADDING * 2 + textWidth(it, labelStyle) } +
                    groupLabels.map { glyphChip } +
                    listOfNotNull(adminLabel?.let { glyphChip })
            ),
            wordmark = wordmark
        )
    }
}

/**
 * The densest tier [available] can pay for, each threshold being that tier's own measured width.
 */
private fun islandDensityFor(available: Dp, widths: IslandWidths, tablet: Boolean): IslandDensity = when {
    available >= widths.full -> IslandDensity.FULL
    available >= widths.mark -> IslandDensity.MARK
    available >= widths.markCompact -> IslandDensity.MARK_COMPACT
    available >= widths.iconGroups -> IslandDensity.ICON_GROUPS
    // The floor a tablet cannot fall through: the largest screen in the range must not be handed the
    // smallest possible bar because a 200% font scale priced its labels out. It keeps its labelled
    // roots and lets the overflow scroller take whatever genuinely will not fit.
    tablet -> IslandDensity.ICON_GROUPS
    else -> IslandDensity.ICON_ONLY
}

/*
 * The collapsed header's brand, and why it is drawn bigger than the expanded one.
 *
 * Collapsing takes the destinations out of the bar and leaves the mark, the wordmark and the
 * hamburger holding a strip built for eleven controls. Left at their expanded size those three read
 * as debris in an empty pill rather than as a header, so the brand grows into the space the chips
 * gave up — the mark by half again, the wordmark by as much of the room between the gutters as it
 * can use — and shrinks back on the same tween when the page returns to the top, so the whole thing
 * is one motion rather than a header that jumps.
 *
 * The cap is deliberate and it is the landscape case: portrait offers the wordmark 208dp between the
 * gutters and landscape offers it 602dp, and a word scaled to fill the second would be a banner
 * rather than a header. [ISLAND_WORDMARK_MAX_SCALE] tops it out at roughly titleLarge — 183.8dp of
 * ink, measured — so both orientations settle on the same size and turning the phone changes the
 * bar's width and nothing else about it.
 */
private val ISLAND_MARK_COLLAPSED = 34.dp
/** Kept proportional to the mark, so growing the tile does not square its corners off. */
private const val ISLAND_MARK_CORNER_RATIO = 7f / 24f
private const val ISLAND_WORDMARK_MAX_SCALE = 1.7f
/** How much of the room between the gutters the wordmark may fill; the rest is air either side. */
private const val ISLAND_WORDMARK_FILL = 0.95f
/** The collapsed wordmark's own tap padding — part of its width, so the fit has to know about it. */
private val ISLAND_WORDMARK_TAP_PADDING = 8.dp
/** Air between the centred wordmark and whichever of the mark or the hamburger is wider. */
private val ISLAND_WORDMARK_CLEARANCE = 8.dp

/*
 * Collapsing as the page scrolls, and why there are two thresholds rather than one.
 *
 * A phone gives up ~60dp of its 640dp to the bar, and the bar is worth that at the top of a page and
 * not worth it a screen and a half down, where the user is reading and every destination is one tap
 * away behind the hamburger anyway. So past [ISLAND_COLLAPSE_SCROLL] the destinations go and the
 * wordmark comes back: what is left says where you are and how to leave, which is what a header is.
 *
 * The two numbers are the whole point. With a single cutoff, a finger resting one pixel either side
 * of it — which is exactly where a finger rests, because the user stopped scrolling there — flips the
 * bar open and shut on every jitter of the scroll position. Collapsing at 72dp and expanding only
 * back at 24dp puts a 48dp dead band between the two decisions: once collapsed the page must travel
 * most of a bar's height back UP before the bar returns, and a resting thumb never covers that.
 * 24dp rather than 0 so that a page nudged slightly off the top still reads as "at the top".
 */
private val ISLAND_COLLAPSE_SCROLL = 72.dp
private val ISLAND_EXPAND_SCROLL = 24.dp

/**
 * Whether the page behind the bar has scrolled past the collapse threshold, with hysteresis.
 *
 * This is the part of the feature that decides whether the app still renders at 60fps, so it is worth
 * being explicit about what does NOT happen here. A scrolling page changes its offset on every frame.
 * If the bar's caller read `scrollState.value` in its own composition, every one of those frames would
 * invalidate the CALLER — on this app that is the whole screen. So [scrollOffset] is a lambda: the
 * read happens inside the [derivedStateOf] below, which makes the scroll state a dependency of this
 * one derived value and of nothing else in the tree.
 *
 * [derivedStateOf] then does the second half of the job. Its calculation reruns on every pixel, but
 * it PUBLISHES a Boolean, and Compose only invalidates readers when the published value actually
 * changes. Scrolling a thousand pixels therefore recomposes the bar exactly twice — once when it
 * collapses and once when it comes back — and recomposes nothing else, ever.
 *
 * The latch is captured in the [remember] block rather than kept in a [androidx.compose.runtime.MutableState]
 * because a derived calculation must not write snapshot state; it is safe to re-enter because the
 * result is a pure function of the offset and the latch, so evaluating it twice for the same offset
 * gives the same answer.
 */
@Composable
private fun rememberIslandScrolledPast(
    scrollOffset: (() -> Int)?,
    collapseAt: Dp,
    expandAt: Dp
): State<Boolean> {
    val collapsePx = with(LocalDensity.current) { collapseAt.toPx() }
    val expandPx = with(LocalDensity.current) { expandAt.toPx() }
    // Held in a State so a caller that rebuilds its lambda cannot reset the latch mid-gesture.
    val read = rememberUpdatedState(scrollOffset)
    return remember(collapsePx, expandPx) {
        var past = false
        derivedStateOf {
            val offset = read.value ?: return@derivedStateOf false
            val y = offset().toFloat()
            past = when {
                y >= collapsePx -> true
                y <= expandPx -> false
                // Inside the dead band nothing is decided; whatever the bar is doing, it keeps doing.
                else -> past
            }
            past
        }
    }
}

@Composable
fun FieldIslandNav(
    roots: List<IslandEntry>,
    groups: List<IslandGroup>,
    onBrandClick: () -> Unit,
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
    /** Null hides the chip entirely — a non-admin has no admin view to be in. */
    adminMode: Boolean? = null,
    onToggleAdminView: () -> Unit = {},
    /** Highlights the chip the user is currently inside, the web's `aria-current="page"` state. */
    currentLabel: String? = null,
    /**
     * How far the page behind the bar has scrolled, in pixels — a LAMBDA, deliberately, and not a
     * value. Pass `{ scrollState.value }`, never `scrollState.value`: handing over the number reads
     * the scroll state in the CALLER's composition and recomposes the caller on every frame of every
     * scroll, which on this app means the whole screen. Handing over the lambda moves that read
     * inside [rememberIslandScrolledPast], where it feeds one Boolean and nothing else.
     *
     * Null — the default — means the host does not scroll underneath the bar, so there is nothing to
     * collapse in response to and the bar behaves exactly as it did before this parameter existed.
     */
    scrollOffset: (() -> Int)? = null,
    /** Past this much scroll the phone bar collapses. See [ISLAND_COLLAPSE_SCROLL] for the pairing. */
    collapseAt: Dp = ISLAND_COLLAPSE_SCROLL,
    /** Back within this much of the top it expands again. Must be well below [collapseAt]. */
    expandAt: Dp = ISLAND_EXPAND_SCROLL
) {
    val shown = groups.filter { it.entries.isNotEmpty() }
    var openGroup by remember { mutableStateOf<String?>(null) }
    // Hoisted out of the branch below so that shedding a density tier — or collapsing and expanding
    // — does not silently reset how far the overflow strip was scrolled.
    val overflow = rememberScrollState()
    // "Stops animations and transitions" is a promise the user made the app; a bar that slides and
    // fades anyway would be the one place it is broken.
    val stillness = LocalAppPreferences.current.reducedMotion

    val tablet = isTabletDevice()
    val menuWidth = if (tablet) ISLAND_MENU_TABLET else ISLAND_TOUCH_TARGET
    val widths = rememberIslandWidths(
        rootLabels = roots.map { it.label },
        groupLabels = shown.map { it.label },
        // Always the OFF caption, which is the wider of the two: measuring whichever is showing
        // would let the bar shed or regain a whole tier on the toggle it was measuring.
        adminLabel = if (adminMode != null) ISLAND_ADMIN_OFF else null,
        menuWidth = menuWidth
    )

    // Read as a State, NOT with `by`: unwrapping it here would make the scroll position a dependency
    // of this function, and the whole point is that only the subcomposition below depends on it.
    val scrolledPast = rememberIslandScrolledPast(scrollOffset, collapseAt, expandAt)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, MaterialTheme.field.hairline)
    ) {
        BoxWithConstraints {
            val density = islandDensityFor(maxWidth, widths, tablet)
            // A tablet behaves like the web and never collapses: it has the vertical room, and the
            // bar it would collapse FROM is the labelled one the web shows all the way down a page.
            val collapsed = !tablet && scrolledPast.value
            val tightest = density == IslandDensity.ICON_ONLY
            // Labels survive on the groups only while there is room for the whole bar to wear them;
            // the roots keep theirs one step longer, being the two places a newcomer starts.
            val groupsLabelled = density == IslandDensity.FULL ||
                density == IslandDensity.MARK ||
                density == IslandDensity.MARK_COMPACT
            // The tier that buys its words by making them smaller rather than by dropping them.
            val compactChips = density == IslandDensity.MARK_COMPACT
            val rootsLabelled = !tightest
            // Even slots are only meaningful where every child is the same 18dp glyph. One tier up
            // the chips are different widths — a labelled "Dashboard" beside a bare folder — and
            // equal slots would space the GAPS evenly while leaving the glyphs visibly adrift.
            val evenlySpaced = tightest
            // Every tier above is CHOSEN by `islandDensityFor` because its measured width fits the
            // bar, so whenever one of them is in play there is slack and the chips cannot overflow.
            // Only the tablet floor and the phone fallback are handed a tier that may not fit, and
            // they are the two that still need the scroller.
            val densityFits = when (density) {
                IslandDensity.FULL -> maxWidth >= widths.full
                IslandDensity.MARK -> maxWidth >= widths.mark
                IslandDensity.MARK_COMPACT -> maxWidth >= widths.markCompact
                IslandDensity.ICON_GROUPS -> maxWidth >= widths.iconGroups
                IslandDensity.ICON_ONLY -> false
            }
            // Labelled chips that fit share the slack out between them instead of packing against the
            // brand and heaping it all in front of the hamburger — which is what landscape looked
            // like, the words crowded into the left two thirds of a bar with a hole after them.
            //
            // Equal GAPS, not the equal SLOTS the glyph tier uses: slots give equal centre-to-centre
            // spacing, which reads as even only while every chip is the same width. Here "Dashboard"
            // is nearly three times "Admin", so equal slots would leave visibly unequal air between
            // the words, which is the very thing being complained about.
            val distributeLabels = densityFits && !evenlySpaced
            // The collapsed header has no chips to name the app, so the wordmark comes back to do it —
            // but as a CENTRED title rather than as a word beside the mark, which is where it sat while
            // the destinations were still there to fill the rest of the bar. The two renderings cannot
            // be one: a child of the brand block is anchored to the bar's left end by construction.
            val inlineWordmark = density == IslandDensity.FULL && !collapsed

            // A dropdown anchored to a chip that is on its way out would be left pointing at nothing.
            LaunchedEffect(collapsed) { if (collapsed) openGroup = null }

            // Collapsing hands the bar back to the brand, so the squeeze the narrowest tier puts on
            // the brand block and the bar's own padding is lifted at the same moment the mark grows.
            // Both readings below come from these three values, so the gutters cannot fall out of
            // step with the layout they are measuring.
            val barPadding = if (tightest && !collapsed) ISLAND_BAR_PADDING_TIGHT else ISLAND_BAR_PADDING
            val brandStart = if (tightest && !collapsed) ISLAND_BRAND_START_TIGHT else ISLAND_BRAND_START
            val brandEnd = if (tightest && !collapsed) ISLAND_BRAND_END_TIGHT else ISLAND_BRAND_END
            // On the collapse tween rather than in a jump, so the mark and the wordmark arriving
            // over it read as one movement. `snap` honours the reduced-motion promise.
            val markSize by animateDpAsState(
                targetValue = if (collapsed) ISLAND_MARK_COLLAPSED else ISLAND_MARK_SIZE,
                animationSpec = if (stillness) snap() else tween(180),
                label = "islandMark"
            )
            // Priced against the mark's FINAL size, never the animating one: a gutter that grew with
            // the tween would walk the centred wordmark sideways for 180ms on every collapse. Equal
            // either side by construction, which is the whole of why the title lands on the bar's
            // centre and not on the centre of what is left over between the mark and the hamburger.
            val collapsedGutter = maxOf(
                barPadding + brandStart + ISLAND_MARK_COLLAPSED + brandEnd,
                barPadding + menuWidth
            ) + ISLAND_WORDMARK_CLEARANCE
            val wordmarkRoom = maxWidth - collapsedGutter * 2 - ISLAND_WORDMARK_TAP_PADDING * 2
            // Grow into the room, never past it, and never smaller than the expanded wordmark: at a
            // 200% system font scale there is no room to grow into, and the honest answer there is
            // the ordinary size plus the ellipsis the Text already carries.
            val wordmarkScale = if (widths.wordmark > 0.dp) {
                (wordmarkRoom.value * ISLAND_WORDMARK_FILL / widths.wordmark.value)
                    .coerceIn(1f, ISLAND_WORDMARK_MAX_SCALE)
            } else {
                1f
            }
            val collapsedWordmarkStyle = MaterialTheme.typography.titleSmall.let { base ->
                base.copy(
                    fontSize = base.fontSize * wordmarkScale,
                    // Scaled with it, or a 24sp word is laid out in a 20sp line and loses its tail.
                    lineHeight = if (base.lineHeight.isSpecified) base.lineHeight * wordmarkScale else base.lineHeight
                )
            }

            // One definition of the destination chips, invoked under three different parents below —
            // evenly weighted slots on a phone, an evenly spread row wherever the labels fit, and the
            // overflow scroller for the two tiers that may not. Written once so the layouts cannot
            // drift into offering different chips.
            val destinations: @Composable RowScope.() -> Unit = {
                // `weight` needs a RowScope, so the slot can only be built in here, where there is one.
                val slot = if (evenlySpaced) Modifier.weight(1f).heightIn(min = ISLAND_TOUCH_TARGET) else Modifier
                roots.forEach { entry ->
                    IslandChip(
                        modifier = slot,
                        label = entry.label,
                        selected = currentLabel == entry.label,
                        leading = if (rootsLabelled) null else entry.icon,
                        showLabel = rootsLabelled,
                        compact = compactChips,
                        onClick = entry.onClick
                    )
                }

                shown.forEach { group ->
                    // The dropdown anchors to its chip, so the chip needs a Box of its own; in the
                    // even layout that Box IS the slot and the chip fills it.
                    Box(modifier = if (evenlySpaced) Modifier.weight(1f) else Modifier) {
                        IslandChip(
                            modifier = if (evenlySpaced) {
                                Modifier.fillMaxWidth().heightIn(min = ISLAND_TOUCH_TARGET)
                            } else {
                                Modifier
                            },
                            label = group.label,
                            selected = group.entries.any { it.label == currentLabel },
                            leading = if (groupsLabelled) null else group.icon,
                            // The caret is an affordance, not information: it goes with the label
                            // rather than crowding a chip that is one glyph wide. The dropdown
                            // underneath is untouched at every density.
                            trailing = if (groupsLabelled) Icons.Filled.ExpandMore else null,
                            showLabel = groupsLabelled,
                            compact = compactChips,
                            onClick = { openGroup = group.label }
                        )
                        DropdownMenu(
                            expanded = openGroup == group.label,
                            onDismissRequest = { openGroup = null }
                        ) {
                            group.entries.forEach { entry ->
                                DropdownMenuItem(
                                    text = { Text(entry.label) },
                                    leadingIcon = {
                                        Icon(entry.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                                    },
                                    onClick = {
                                        openGroup = null
                                        entry.onClick()
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    // The one owner of the bar's height. Everything that comes and goes below is
                    // inside this Row, so whatever they add up to, the Surface follows it in a single
                    // spring instead of snapping — which is what would otherwise shunt the whole page
                    // the moment the icons left the layout.
                    .then(if (stillness) Modifier else Modifier.animateContentSize())
                    .padding(
                        horizontal = barPadding,
                        vertical = if (collapsed) 4.dp else 6.dp
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ISLAND_BAR_SPACING)
            ) {
                // Brand — tapping it goes home, the same as the web's wordmark. The wordmark is the
                // first thing the bar gives up to width: 111dp spent restating what the screen
                // already is, against a mark that stays exactly as clickable without it. It comes
                // back when the destinations go, for the opposite reason — a header of one anonymous
                // 24dp mark says nothing at all — but centred, below, rather than here, and with the
                // mark grown to match it.
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable(onClick = onBrandClick)
                        // Unlabelled the mark is a bare image, so it has to say what it is out loud.
                        .then(
                            if (inlineWordmark) Modifier
                            else Modifier.semantics {
                                contentDescription = "Field Repository, go to the dashboard"
                            }
                        )
                        .padding(
                            start = brandStart,
                            end = brandEnd,
                            top = 4.dp,
                            bottom = 4.dp
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FieldRepoLogo(
                        modifier = Modifier.size(markSize),
                        cornerRadius = markSize * ISLAND_MARK_CORNER_RATIO
                    )
                    AnimatedVisibility(
                        visible = inlineWordmark,
                        // Grown from the logo outwards rather than faded in place, so the two read as
                        // one widening brand block instead of a word materialising beside a mark.
                        enter = if (stillness) EnterTransition.None else {
                            fadeIn(tween(180)) + expandHorizontally(tween(180), Alignment.Start)
                        },
                        exit = if (stillness) ExitTransition.None else {
                            fadeOut(tween(110)) + shrinkHorizontally(tween(110), Alignment.Start)
                        }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.width(ISLAND_WORDMARK_GAP))
                            Text(
                                "Field Repository",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1
                            )
                        }
                    }
                }

                // The destinations, and the admin toggle that belongs with them. The weight lives on
                // this Box rather than on what is inside it, so that emptying it costs the bar no
                // width: collapsed, it is the gap between the wordmark and the hamburger, and the
                // hamburger therefore does not travel a single pixel between the two states.
                Box(modifier = Modifier.weight(1f)) {
                    IslandCollapsingStrip(visible = !collapsed, stillness = stillness) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = when {
                                // Nothing between the slots: an even layout puts ALL of the slack
                                // inside them, and a gap on top would be slack the icons never see.
                                evenlySpaced -> Arrangement.spacedBy(0.dp)
                                // SpaceEvenly rather than SpaceBetween so the run of chips keeps its
                                // air at both ends too. SpaceBetween would pin the first word to the
                                // logo and the last to the hamburger, which trades one lopsided bar
                                // for another.
                                distributeLabels -> Arrangement.SpaceEvenly
                                else -> Arrangement.spacedBy(ISLAND_CHIP_SPACING)
                            }
                        ) {
                            if (evenlySpaced || distributeLabels) {
                                // Laid out directly under this Row so its arrangement reaches every
                                // chip — the admin toggle below included, since a toggle left sitting
                                // apart from an evenly spread row is the same complaint one chip
                                // later. Both spreads replaced a layout that packed the chips against
                                // the logo and heaped every spare pixel on the right.
                                //
                                // No scroller in either case, and none wanted: the glyph tier divides
                                // whatever width there is, and `distributeLabels` is only ever true
                                // for a tier already measured to fit, so nothing can fall off the end.
                                destinations()
                            } else {
                                // The destinations are the only part allowed to overflow, and only
                                // after every density step has been spent. The brand, the admin
                                // toggle and the hamburger sit outside this scroller so that the
                                // escape hatch to the full drawer is always on screen — the old bar
                                // scrolled the hamburger away with everything else.
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .horizontalScroll(overflow),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(ISLAND_CHIP_SPACING),
                                    content = destinations
                                )
                            }

                            if (adminMode != null) {
                                // "Admin view: OFF" is the widest chip in the bar after the wordmark,
                                // and the eye already carries the state — open or shut. Below
                                // MARK_COMPACT the words go and the sentence moves into the
                                // contentDescription, so the toggle still announces which way it is
                                // set. Collapsed it goes entirely: an indicator nobody can act on is
                                // just a glyph, and the drawer still states the setting in words.
                                IslandChip(
                                    modifier = if (evenlySpaced) {
                                        Modifier.weight(1f).heightIn(min = ISLAND_TOUCH_TARGET)
                                    } else {
                                        Modifier
                                    },
                                    label = if (adminMode) ISLAND_ADMIN_ON else ISLAND_ADMIN_OFF,
                                    selected = adminMode,
                                    leading = if (adminMode) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                    showLabel = groupsLabelled,
                                    compact = compactChips,
                                    onClick = onToggleAdminView
                                )
                            }
                        }
                    }
                }

                // Never hidden and never moved — it is the whole of the collapsed header's right-hand
                // side, and the one control that must be findable without thinking. Full 48dp on a
                // phone, where it is thumbed; left at the tablet bar's existing 36dp, where the
                // pointer or the wider bar makes the extra 12dp cost more than it buys.
                IconButton(
                    onClick = onOpenDrawer,
                    modifier = Modifier.size(menuWidth)
                ) {
                    Icon(
                        Icons.Filled.Menu,
                        contentDescription = "Open menu",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // The collapsed header's title, centred against the BAR and not against the space left
            // over between the mark and the hamburger. Those two are ~46dp and 48dp, so every layout
            // that shares out the middle — a weighted slot, SpaceBetween, a Row that simply follows
            // the mark — puts the word a few pixels off centre, which is precisely close enough to
            // read as a mistake. Overlaid on the row instead: this Box IS the bar, so its centre is
            // the bar's centre whatever flanks it, and [collapsedGutter] is EQUAL either side and
            // sized off the wider of the two flanks, so an outsized font scale ellipsises the title
            // rather than running it underneath the controls.
            AnimatedVisibility(
                visible = collapsed,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = collapsedGutter),
                // Held back until the destinations have finished leaving, so the title is never drawn
                // across the chips it is standing in for; scaled up into place over the same window
                // the mark is growing in, so the two are one movement and not two.
                enter = if (stillness) {
                    EnterTransition.None
                } else {
                    fadeIn(tween(160, delayMillis = 120)) +
                        scaleIn(tween(200, delayMillis = 120), initialScale = 0.88f)
                },
                exit = if (stillness) ExitTransition.None else fadeOut(tween(90))
            ) {
                Text(
                    "Field Repository",
                    // Still the Dashboard link the wordmark is at FULL density. The mark leads to the
                    // same place, but the word is the larger target and the one under the thumb.
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClick = onBrandClick)
                        .padding(horizontal = ISLAND_WORDMARK_TAP_PADDING, vertical = 4.dp),
                    style = collapsedWordmarkStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * The half of the bar that comes and goes with the scroll position.
 *
 * A plain function rather than the [AnimatedVisibility] call written inline, for one reason that is
 * pure Kotlin: inline, the nearest implicit receiver would be the bar's own `Row`, and the compiler
 * would bind the `RowScope` overload — whose content is DISPOSED when hidden, taking the weight with
 * it and letting the hamburger jump left. Here there is no `RowScope` in scope, the plain overload
 * binds, and the caller's weighted Box holds the width open with nothing in it.
 *
 * Up and out, the direction the page itself is going. The slide is a quarter of the strip's own
 * height — enough to read as movement, too little to look like the icons are escaping the pill.
 */
@Composable
private fun IslandCollapsingStrip(
    visible: Boolean,
    stillness: Boolean,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = if (stillness) EnterTransition.None else {
            fadeIn(tween(180)) + slideInVertically(tween(180)) { -it / 4 }
        },
        exit = if (stillness) ExitTransition.None else {
            fadeOut(tween(120)) + slideOutVertically(tween(120)) { -it / 4 }
        }
    ) {
        content()
    }
}

/**
 * One pill inside the island: the web's `rounded-full px-3 py-1.5 text-sm` nav link.
 *
 * With [showLabel] false the chip is [leading] alone and [label] becomes the icon's
 * contentDescription — the chip loses its width, never its name.
 *
 * [modifier] is how the even phone layout hands the chip a slot to fill. Given a width the chip does
 * not need, it centres its contents in it and lets the pill — background, ripple and tap target
 * alike — grow to the slot, which is the point: the slack belongs to the finger, not to the gap.
 *
 * [compact] is the bar buying its words back on a screen that could nearly afford them: two points
 * smaller and 4dp tighter either side, which is what [IslandDensity.MARK_COMPACT] spends to keep
 * every chip named instead of turning four of them into glyphs.
 */
@Composable
private fun IslandChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
    trailing: ImageVector? = null,
    showLabel: Boolean = true,
    compact: Boolean = false
) {
    // A chip with neither a word nor a glyph would be an invisible tap target, so a caller that asks
    // for icon-only without supplying one gets the label back rather than a blank pill.
    val labelled = showLabel || leading == null
    val padding = when {
        !labelled -> ISLAND_CHIP_PADDING_GLYPH
        compact -> ISLAND_CHIP_PADDING_COMPACT
        else -> ISLAND_CHIP_PADDING
    }
    val glyph = when {
        !labelled -> ISLAND_CHIP_ICON_GLYPH
        compact -> ISLAND_CHIP_ICON_COMPACT
        else -> ISLAND_CHIP_ICON
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) MaterialTheme.field.surface100 else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = padding, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        // Centred rather than packed, which changes nothing for a chip measured by its contents and
        // everything for one given a slot to fill.
        horizontalArrangement = Arrangement.spacedBy(ISLAND_CHIP_GAP, Alignment.CenterHorizontally)
    ) {
        val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.field.body
        if (leading != null) {
            Icon(
                leading,
                // Beside a label the text is the accessible name and a second one would stutter.
                contentDescription = if (labelled) null else label,
                tint = tint,
                // A glyph standing in for a word is read at arm's length, so it is drawn larger than
                // the same glyph tucked in front of one.
                modifier = Modifier.size(glyph)
            )
        }
        if (labelled) {
            Text(
                label,
                style = if (compact) {
                    MaterialTheme.typography.labelMedium
                } else {
                    MaterialTheme.typography.labelLarge
                },
                color = tint,
                maxLines = 1
            )
            if (trailing != null) {
                Icon(trailing, contentDescription = null, tint = tint, modifier = Modifier.size(glyph))
            }
        }
    }
}


// ---------------------------------------------------------------------------------------------
// The drawer
// ---------------------------------------------------------------------------------------------

/**
 * Drop-in `drawerContent` for the app's `ModalNavigationDrawer`. Renders exactly
 * [visibleNavItems] — the standalone roots first, then each non-empty group behind its own heading —
 * followed by the admin-view toggle and the master admin's update action.
 *
 * @param currentDestination highlights the entry the user is on, the drawer's equivalent of the web's
 *   `aria-current="page"` purple state.
 */
@Composable
fun AppNavigationDrawerContent(
    user: UserDto,
    adminMode: Boolean,
    onNavigate: (NavDestination) -> Unit,
    onToggleAdminView: () -> Unit,
    onLogout: () -> Unit,
    currentDestination: NavDestination? = null,
    pushingUpdate: Boolean = false,
    onPushUpdate: () -> Unit = {}
) {
    val items = visibleNavItems(user, adminMode)
    val rootItems = items.filter { it.group == null }
    val groups = NavGroup.entries
        .map { group -> group to items.filter { it.group == group } }
        .filter { (_, entries) -> entries.isNotEmpty() }
    val isAdmin = FieldPermissions.isAdmin(user)

    ModalDrawerSheet {
        // Brand block — the island's left end: the mark in its cream tile, the wordmark in the
        // display face, and who you are signed in as.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            FieldRepoLogo(modifier = Modifier.size(36.dp), cornerRadius = 10.dp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Field Repository",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "${user.name} · ${FieldPermissions.label(user.role)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.field.muted
                )
            }
            OutlinedButton(
                onClick = onLogout,
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Logout", style = MaterialTheme.typography.labelLarge)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        // The menu is long for an admin, so the entries scroll while the brand block and the
        // footer actions stay pinned.
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            rootItems.forEach { entry ->
                NavRow(entry, entry.destination == currentDestination, onNavigate)
            }
            groups.forEach { (group, entries) ->
                NavGroupHeading(group.label)
                entries.forEach { entry ->
                    NavRow(entry, entry.destination == currentDestination, onNavigate)
                }
            }
            Spacer(Modifier.padding(bottom = 8.dp))
        }

        if (isAdmin || FieldPermissions.isMasterAdmin(user)) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
        // Offered to admins only — and it can merely hide admin chrome, never unlock it.
        if (isAdmin) {
            NavigationDrawerItem(
                label = {
                    Text(
                        if (adminMode) "Admin view: ON" else "Admin view: OFF",
                        style = MaterialTheme.typography.labelLarge
                    )
                },
                selected = adminMode,
                icon = {
                    Icon(
                        if (adminMode) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                        contentDescription = null
                    )
                },
                onClick = onToggleAdminView,
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
            )
        }
        if (FieldPermissions.isMasterAdmin(user)) {
            NavigationDrawerItem(
                label = {
                    Text(
                        if (pushingUpdate) "Publishing update…" else "Push update to all",
                        style = MaterialTheme.typography.labelLarge
                    )
                },
                selected = false,
                icon = { Icon(Icons.Filled.SystemUpdate, contentDescription = null) },
                onClick = { if (!pushingUpdate) onPushUpdate() },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
            )
        }
    }
}

/** Group heading — the web renders these as the dropdown TRIGGERS; on a sheet they are labels. */
@Composable
private fun NavGroupHeading(label: String) {
    Text(
        text = label.uppercase(),
        style = FieldTextStyles.FieldLabel,
        color = MaterialTheme.field.muted,
        modifier = Modifier.padding(start = 28.dp, end = 16.dp, top = 16.dp, bottom = 6.dp)
    )
}

@Composable
private fun NavRow(entry: NavEntry, selected: Boolean, onNavigate: (NavDestination) -> Unit) {
    NavigationDrawerItem(
        label = { Text(entry.label, style = MaterialTheme.typography.labelLarge) },
        selected = selected,
        icon = { Icon(entry.icon, contentDescription = null) },
        onClick = { onNavigate(entry.destination) },
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
    )
}
