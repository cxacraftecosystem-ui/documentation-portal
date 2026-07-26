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
import androidx.compose.material.icons.filled.ManageAccounts
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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

    /** `require_record_creator` — artisans, products, processes, tools. */
    fun canCreateRecords(user: UserDto): Boolean = rank(user.role) >= RANK_FIELD_CONTRIBUTOR

    /** `require_craft_manager` — Professor and above, or an explicit grant. */
    fun canManageCrafts(user: UserDto): Boolean =
        rank(user.role) >= RANK_PROFESSOR || user.canManageCrafts

    /** `require_workshop_manager` — Professor and above, or an explicit grant. */
    fun canManageWorkshops(user: UserDto): Boolean =
        rank(user.role) >= RANK_PROFESSOR || user.canManageWorkshops

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
    VIEW_DATA,
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
    NavEntry(NavDestination.VIEW_DATA, "View Data", Icons.Filled.Storage, NavGroup.BROWSE, FieldPermissions::canDownloadDataset, "require_dataset_downloader"),
    NavEntry(NavDestination.SHARE_DATA_ACCESS, "Share data access", Icons.Filled.Share, NavGroup.BROWSE, everyone, "get_current_user"),
    // Linking a tool to an artisan needs a tool or an artisan of your own — both need record creation.
    // The endpoint only requires a login and then checks ownership per artisan, so this is the closest
    // STATIC mirror of a dynamic rule: nobody below Field Contributor owns either side.
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
 *  - horizontally scrollable. Six chips plus the brand do not fit across a 360dp phone, and
 *    shrinking the labels to fit would make them unreadable. Scrolling keeps them full-size, and the
 *    two a newcomer needs (Dashboard, Walkthrough) are the ones already on screen at rest.
 *  - the drawer stays. It is still the full list in one thumb-reachable place, and the hamburger at
 *    the right of the pill is how you get to it — same as the web's sheet.
 *
 * Renders exactly [visibleNavItems], so it can no more offer an unauthorised destination than the
 * drawer can. The admin-view chip mirrors the web's, including reading "Admin view: OFF" while off
 * rather than disappearing — an admin needs to see that the toggle is why their menu got shorter.
 */
/** One destination in the island: what it says, what it looks like, what it does. */
class IslandEntry(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/** One dropdown chip and the destinations behind it — the web's Record / Browse / Admin / Account. */
class IslandGroup(val label: String, val entries: List<IslandEntry>)

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
    currentLabel: String? = null
) {
    val shown = groups.filter { it.entries.isNotEmpty() }
    var openGroup by remember { mutableStateOf<String?>(null) }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, MaterialTheme.field.hairline)
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Brand — tapping it goes home, the same as the web's wordmark.
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(onClick = onBrandClick)
                    .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FieldRepoLogo(modifier = Modifier.size(24.dp), cornerRadius = 7.dp)
                Spacer(Modifier.width(7.dp))
                Text(
                    "Field Repository",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
            }

            roots.forEach { entry ->
                IslandChip(
                    label = entry.label,
                    selected = currentLabel == entry.label,
                    onClick = entry.onClick
                )
            }

            shown.forEach { group ->
                Box {
                    IslandChip(
                        label = group.label,
                        selected = group.entries.any { it.label == currentLabel },
                        trailing = Icons.Filled.ExpandMore,
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

            if (adminMode != null) {
                IslandChip(
                    label = if (adminMode) "Admin view: ON" else "Admin view: OFF",
                    selected = adminMode,
                    leading = if (adminMode) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    onClick = onToggleAdminView
                )
            }

            IconButton(onClick = onOpenDrawer, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.Menu,
                    contentDescription = "Open menu",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** One pill inside the island: the web's `rounded-full px-3 py-1.5 text-sm` nav link. */
@Composable
private fun IslandChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    leading: ImageVector? = null,
    trailing: ImageVector? = null
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) MaterialTheme.field.surface100 else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.field.body
        if (leading != null) {
            Icon(leading, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = tint,
            maxLines = 1
        )
        if (trailing != null) {
            Icon(trailing, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
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
