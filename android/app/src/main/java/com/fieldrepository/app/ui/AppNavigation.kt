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
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics

import com.fieldrepository.app.data.UserDto

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

    fun isAdmin(user: UserDto): Boolean = rank(user.role) >= RANK_ADMIN

    fun isMasterAdmin(user: UserDto): Boolean = user.role == "MASTER_ADMIN"

    val CONTENT_ADMIN_ROLES: Set<String> = setOf("MINISTRY_ADMIN", "ADMIN", "MASTER_ADMIN")

    fun isContentAdmin(user: UserDto?): Boolean = user?.role?.let { it in CONTENT_ADMIN_ROLES } == true

    fun canCreateRecords(user: UserDto): Boolean = rank(user.role) >= RANK_RESEARCHER

    fun canManageCrafts(user: UserDto): Boolean = rank(user.role) >= RANK_PROFESSOR

    fun canManageWorkshops(user: UserDto): Boolean = rank(user.role) >= RANK_PROFESSOR

    fun canManageUsers(user: UserDto): Boolean = rank(user.role) >= RANK_PROFESSOR

    fun canReview(user: UserDto): Boolean =
        rank(user.role) >= RANK_FIELD_CONTRIBUTOR || user.canReview

    fun canDownloadDataset(user: UserDto): Boolean =
        rank(user.role) >= RANK_PROFESSOR || user.canDownloadDataset
}

enum class NavGroup(val label: String) {
    RECORD("Record"),
    BROWSE("Browse"),
    ADMIN("Admin"),
    ACCOUNT("Account")
}

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
    SCAN_CODE,
    MAP,
    VIEW_DATA,
    CONSOLIDATED_QUESTIONNAIRE,
    SHARE_DATA_ACCESS,
    ASSIGN_TOOLS,
    REVIEW,
    SETTINGS_HUB,
    ACCESS_ROSTER,
    MANAGE_USERS,
    NOTIFICATIONS,
    SETTINGS,
    GIVE_FEEDBACK
}

data class NavEntry(
    val destination: NavDestination,
    val label: String,
    val icon: ImageVector,
    val group: NavGroup?,
    val can: (UserDto) -> Boolean,
    val gate: String,
    val adminSurface: Boolean = false
)

private val everyone: (UserDto) -> Boolean = { true }

val FIELD_NAV_ITEMS: List<NavEntry> = listOf(
    NavEntry(NavDestination.DASHBOARD, "Dashboard", Icons.Filled.Dashboard, null, everyone, "get_current_user"),
    NavEntry(NavDestination.WALKTHROUGH, "Walkthrough", Icons.Filled.Explore, null, everyone, "none (static page)"),

    NavEntry(NavDestination.RECORD_ARTISAN, "Record artisan", Icons.Filled.Person, NavGroup.RECORD, FieldPermissions::canCreateRecords, "require_record_creator"),
    NavEntry(NavDestination.RECORD_PRODUCT, "Record product", Icons.Filled.Inventory2, NavGroup.RECORD, FieldPermissions::canCreateRecords, "require_record_creator"),
    NavEntry(NavDestination.DOCUMENT_PROCESS, "Document process", Icons.Filled.AccountTree, NavGroup.RECORD, FieldPermissions::canCreateRecords, "require_record_creator"),
    NavEntry(NavDestination.RECORD_TOOL, "Record tool", Icons.Filled.Build, NavGroup.RECORD, FieldPermissions::canCreateRecords, "require_record_creator"),
    NavEntry(NavDestination.TAKE_INTERVIEW, "Take interview", Icons.Filled.Quiz, NavGroup.RECORD, everyone, "get_current_user"),
    NavEntry(NavDestination.UPLOAD_MEDIA, "Upload media", Icons.Filled.PermMedia, NavGroup.RECORD, everyone, "get_current_user"),
    NavEntry(NavDestination.ADD_CRAFT, "Add craft", Icons.Filled.Brush, NavGroup.RECORD, FieldPermissions::canManageCrafts, "require_craft_manager"),
    NavEntry(NavDestination.RECORD_WORKSHOP, "Record workshop", Icons.Filled.Groups, NavGroup.RECORD, FieldPermissions::canManageWorkshops, "require_workshop_manager"),

    NavEntry(NavDestination.MY_ACTIVITY, "My Activity", Icons.Filled.Timeline, NavGroup.BROWSE, everyone, "get_current_user"),
    NavEntry(NavDestination.TASKS, "Tasks", Icons.AutoMirrored.Filled.Assignment, NavGroup.BROWSE, everyone, "get_current_user"),
    NavEntry(NavDestination.BROWSE_RECORDS, "Browse records", Icons.Filled.Search, NavGroup.BROWSE, everyone, "get_current_user"),
    NavEntry(NavDestination.SCAN_CODE, "Scan a code", Icons.Filled.QrCodeScanner, NavGroup.BROWSE, everyone, "get_current_user"),
    NavEntry(NavDestination.MAP, "Map", Icons.Filled.Map, NavGroup.BROWSE, everyone, "get_current_user"),
    NavEntry(NavDestination.VIEW_DATA, "Data", Icons.Filled.Storage, NavGroup.BROWSE, FieldPermissions::canDownloadDataset, "require_dataset_downloader"),
    NavEntry(NavDestination.CONSOLIDATED_QUESTIONNAIRE, "Consolidated questionnaire", Icons.Filled.Layers, NavGroup.BROWSE, everyone, "get_current_user"),
    NavEntry(NavDestination.SHARE_DATA_ACCESS, "Share data access", Icons.Filled.Share, NavGroup.BROWSE, everyone, "get_current_user"),
    NavEntry(NavDestination.ASSIGN_TOOLS, "Assign tools to artisans", Icons.Filled.Handyman, NavGroup.BROWSE, FieldPermissions::canCreateRecords, "get_current_user + owner/EDIT-grant/admin per artisan"),
    NavEntry(NavDestination.REVIEW, "Review", Icons.Filled.Visibility, NavGroup.BROWSE, FieldPermissions::canReview, "require_reviewer"),

    NavEntry(NavDestination.SETTINGS_HUB, "Settings hub", Icons.Filled.Tune, NavGroup.ADMIN, FieldPermissions::isAdmin, "require_admin", adminSurface = true),
    NavEntry(NavDestination.ACCESS_ROSTER, "Access roster", Icons.Filled.HowToReg, NavGroup.ADMIN, FieldPermissions::isAdmin, "require_admin", adminSurface = true),
    NavEntry(NavDestination.MANAGE_USERS, "Manage users", Icons.Filled.ManageAccounts, NavGroup.ADMIN, FieldPermissions::canManageUsers, "require_professor", adminSurface = true),

    NavEntry(NavDestination.NOTIFICATIONS, "Notifications", Icons.Filled.Notifications, NavGroup.ACCOUNT, everyone, "get_current_user (GET /notifications)"),
    NavEntry(NavDestination.SETTINGS, "Settings", Icons.Filled.Settings, NavGroup.ACCOUNT, everyone, "get_current_user (PUT /preferences/me)"),
    NavEntry(NavDestination.GIVE_FEEDBACK, "Give app feedback", Icons.Filled.RateReview, NavGroup.ACCOUNT, everyone, "get_current_user (PUT /feedback/me)")
)

fun isNavItemVisible(item: NavEntry, user: UserDto?, adminMode: Boolean): Boolean {
    if (user == null || !item.can(user)) return false
    if (item.adminSurface && FieldPermissions.isAdmin(user)) return adminMode
    return true
}

fun visibleNavItems(user: UserDto?, adminMode: Boolean): List<NavEntry> =
    FIELD_NAV_ITEMS.filter { isNavItemVisible(it, user, adminMode) }

@Composable
fun AppNavigationDrawerContent(
    user: UserDto,
    adminMode: Boolean,
    onNavigate: (NavDestination) -> Unit,
    onToggleAdminView: () -> Unit,
    onLogout: () -> Unit,
    currentDestination: NavDestination? = null,
    badges: Map<NavDestination, Int> = emptyMap(),
    pushingUpdate: Boolean = false,
    onPushUpdate: () -> Unit = {}
) {
    val items = visibleNavItems(user, adminMode)
    val rootItems = items.filter { it.group == null }
    val groups = NavGroup.entries
        .map { group -> group to items.filter { it.group == group } }
        .filter { (_, entries) -> entries.isNotEmpty() }
    val isAdmin = FieldPermissions.isAdmin(user)

    ModalDrawerSheet(
        modifier = Modifier
            .widthIn(max = appMenuDrawerMaxWidth())
            .semantics { paneTitle = "Menu" },
        drawerShape = APP_MENU_DRAWER_SHAPE
    ) {
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

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            rootItems.forEach { entry ->
                NavRow(entry, entry.destination == currentDestination, badges[entry.destination], onNavigate)
            }
            groups.forEach { (group, entries) ->
                NavGroupHeading(group.label)
                entries.forEach { entry ->
                    NavRow(entry, entry.destination == currentDestination, badges[entry.destination], onNavigate)
                }
            }
            Spacer(Modifier.padding(bottom = 8.dp))
        }

        if (isAdmin || FieldPermissions.isMasterAdmin(user)) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
        if (FieldPermissions.isContentAdmin(user)) {
            AppToggle(
                label = "Admin view",
                description = if (adminMode) "Admin pages and tools are shown." else "You see the app as a researcher does.",
                checked = adminMode,
                onCheckedChange = { onToggleAdminView() },
                modifier = Modifier.padding(horizontal = 28.dp)
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
private fun NavRow(
    entry: NavEntry,
    selected: Boolean,
    badge: Int?,
    onNavigate: (NavDestination) -> Unit
) {
    NavigationDrawerItem(
        label = { Text(entry.label, style = MaterialTheme.typography.labelLarge) },
        selected = selected,
        icon = { Icon(entry.icon, contentDescription = null) },
        badge = badge?.takeIf { it > 0 }?.let {
            {
                Text(
                    "$it waiting",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.field.warning
                )
            }
        },
        onClick = { onNavigate(entry.destination) },
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
    )
}

val LocalContentAdminChrome = staticCompositionLocalOf { false }

val LocalMayDelete = staticCompositionLocalOf { false }
