import type { User, UserRole } from "@/lib/types";

/**
 * The six-tier role ladder, mirroring the backend exactly (app/core/deps.py).
 * Higher rank inherits every power of the ranks below it; the grantable can*
 * booleans additionally lift a single capability for a lower tier.
 */
export const ROLE_RANK: Record<UserRole, number> = {
  CROWDSOURCE_VOLUNTEER: 10,
  FIELD_CONTRIBUTOR: 20,
  RESEARCHER: 30,
  PROFESSOR: 40,
  ADMIN: 50,
  MASTER_ADMIN: 60
};

export const ROLE_LABELS: Record<UserRole, string> = {
  CROWDSOURCE_VOLUNTEER: "Crowdsource Volunteer",
  FIELD_CONTRIBUTOR: "Field Contributor",
  RESEARCHER: "Researcher",
  PROFESSOR: "Professor",
  ADMIN: "Admin",
  MASTER_ADMIN: "Master Admin"
};

/** All roles, highest tier first — the display order for pickers. */
export const ROLES_BY_RANK: UserRole[] = (Object.keys(ROLE_RANK) as UserRole[]).sort(
  (a, b) => ROLE_RANK[b] - ROLE_RANK[a]
);

export function roleRank(userOrRole: User | UserRole | null | undefined): number {
  if (!userOrRole) return 0;
  const role = typeof userOrRole === "string" ? userOrRole : userOrRole.role;
  return ROLE_RANK[role] ?? 0;
}

export function roleLabel(role: string | null | undefined): string {
  return ROLE_LABELS[role as UserRole] ?? String(role ?? "");
}

export function hasRank(user: User | null | undefined, role: UserRole): boolean {
  return roleRank(user) >= ROLE_RANK[role];
}

/** Roles the current user may assign: at or below their own tier (master mints anything). */
export function assignableRoles(user: User | null | undefined): UserRole[] {
  return ROLES_BY_RANK.filter((role) => ROLE_RANK[role] <= roleRank(user));
}

/**
 * May the current user manage (promote/demote, and for admins edit/delete) this target?
 * Master admin manages anyone except OTHER master-admin rows; everyone else manages only
 * users ranked strictly below them.
 */
export function canManageUser(user: User | null | undefined, target: User): boolean {
  if (isMasterAdmin(user)) return target.role !== "MASTER_ADMIN" || target.id === user?.id;
  return roleRank(target) < roleRank(user);
}

/** Professors and above run the user table (promotion rights; admins add create/delete/grants). */
export function canManageUsers(user: User | null | undefined) {
  return hasRank(user, "PROFESSOR");
}

/** Only admins and the master admin may assign tasks to other users. */
export function canAssignTasks(user: User | null | undefined) {
  // The content admins since 2026-10-20 (`deps.require_content_admin` on the task routes).
  return isContentAdmin(user);
}

export function isAdmin(user: User | null | undefined) {
  return user?.role === "MASTER_ADMIN" || user?.role === "ADMIN";
}

export function isMasterAdmin(user: User | null | undefined) {
  return user?.role === "MASTER_ADMIN";
}

/**
 * The CONTENT admins (owner's ruling, 2026-10-20): they add, edit and delete every record, media
 * file and other managed content type. Every other role adds and edits and never deletes.
 *
 * A SET, and deliberately not {@link isAdmin}. This portal has no MINISTRY_ADMIN tier yet; the name
 * is listed so the rule holds the day the ladders merge. `backend/app/core/roles.py::
 * CONTENT_ADMIN_ROLES` is the twin (the designer-portal spells it identically), and
 * `backend/tests/test_content_admin_parity.py` holds them together.
 */
export const CONTENT_ADMIN_ROLES: readonly string[] = ["MINISTRY_ADMIN", "ADMIN", "MASTER_ADMIN"];

export function isContentAdmin(user: User | null | undefined) {
  return !!user && CONTENT_ADMIN_ROLES.includes(user.role);
}

export function canManageQuestionnaire(user: User | null | undefined) {
  return hasRank(user, "PROFESSOR") || !!user?.canManageQuestionnaire;
}

/**
 * The access roster — who may sign in at all, and the queue of people who were turned away.
 * `require_admin` on every route in `backend/app/api/routes/access_roster.py`.
 *
 * ADMIN AND MASTER ADMIN, matching the requirement's "the admins and master admins should get a
 * notification to approve or reject the user". Professors are deliberately outside it even though
 * they manage USERS: the pending queue holds the email addresses of strangers who tried to get into
 * this institution's repository, which is not information the tiers below administration have any
 * reason to hold. Managing an account that already exists and deciding whether an account may exist
 * at all are two different powers, and this is the second one.
 */
export function canManageAccessRoster(user: User | null | undefined) {
  return isAdmin(user);
}

/**
 * Add or edit a craft — `can_manage_crafts` / `require_craft_manager`. Professor and above, RANK
 * ALONE: the `canManageCrafts` column is no longer read on either side, because a per-user grant
 * that lifted a researcher over the taxonomy was invisible in the role column. Deleting a craft is
 * stricter still (admin), so a delete control needs `isAdmin`, not this.
 */
export function canManageCrafts(user: User | null | undefined) {
  return hasRank(user, "PROFESSOR");
}

/** Add or edit a workshop — `require_workshop_manager`. Professor+; deleting one is admin-only. */
export function canManageWorkshops(user: User | null | undefined) {
  return hasRank(user, "PROFESSOR");
}

/** Anyone with somebody ranked below them may peer-review (plus grantees of canReview). */
export function canReview(user: User | null | undefined) {
  return hasRank(user, "FIELD_CONTRIBUTOR") || !!user?.canReview;
}

export function canDownloadDataset(user: User | null | undefined) {
  return hasRank(user, "PROFESSOR") || !!user?.canDownloadDataset;
}

/**
 * Opening a NEW artisan, product, tool, process or interview — `require_record_creator`. Researcher
 * and above. The two tiers below populate records instead of opening them, and none of what they do
 * is gated by this: uploading media, answering an existing interview and commenting all stay open,
 * so hiding a "New …" control from them never hides a contribution path.
 */
export function canCreateRecords(user: User | null | undefined) {
  return hasRank(user, "RESEARCHER");
}

export function canEditOwnOrAdmin(user: User | null | undefined, ownerId?: string | null) {
  // Content admins edit every record, whoever made it (2026-10-20).
  return isContentAdmin(user) || (!!user?.id && !!ownerId && user.id === ownerId);
}

/**
 * EDITING an existing questionnaire is its AUTHOR's (`Questionnaire.createdById`) or a content
 * admin's (owner, 22 Oct 2026): the server's `assert_may_edit_form`. That covers renaming the form,
 * adding, editing, reordering or moving its sections and questions, downloading its own workbook for
 * editing (`GET /questionnaires/{id}/xlsx`) and re-uploading one over it
 * (`POST /questionnaires/{id}/upload`). A form with no recorded author is a content admin's alone.
 * Other questionnaire managers (`canManageQuestionnaire`) see it read-only. Creating a NEW form stays
 * `canManageQuestionnaire`; creating one from a workbook stays `isAdmin`.
 */
export function mayEditQuestionnaire(user: User | null | undefined, createdById?: string | null) {
  return canEditOwnOrAdmin(user, createdById);
}

/**
 * Deleting from a questionnaire — the instrument itself (a soft delete, restorable), a section, a
 * question, or switching one off — is its AUTHOR's (`Questionnaire.createdById`) or a content admin's
 * (owner, 22 Oct 2026): the server's `assert_may_delete_from_form`, the same predicate as
 * {@link mayEditQuestionnaire}. Deleting sessions, takes and interviews stays `isContentAdmin` alone
 * (`canDeleteRecords`), and so do the default form and workshop binding.
 */
export function mayDeleteQuestionnaire(user: User | null | undefined, createdById?: string | null) {
  return mayEditQuestionnaire(user, createdById);
}

/**
 * Provenance — created-by plus the per-field edit history. Android parity
 * (MainActivity `canViewProvenance = isAdmin || user.canViewProvenance`): admins always, plus
 * anyone the master admin granted the capability. Admin view may hide it from an admin, but a
 * grantee keeps it permanently.
 */
export function canViewProvenance(user: User | null | undefined) {
  return isAdmin(user) || !!user?.canViewProvenance;
}

/**
 * Managed API keys (GET/PUT/DELETE /secrets, /secrets/{key}/reveal, /secrets/{key}/test) and the
 * repository's global app settings. Master admin ONLY — an ordinary admin never sees a key value.
 */
export function canManageSecrets(user: User | null | undefined) {
  return isMasterAdmin(user);
}

/* ────────────────────────────────────────────────────────────────────────────
 * Route guards — the page-level half of gating.
 *
 * A hidden nav entry is not a guard. /users, /review, /data and the create forms are all reachable
 * by typing the URL, so every wholly-gated route is declared ONCE here and AppShell enforces the
 * list for the entire (protected) tree. A new page therefore cannot ship without its guard being a
 * deliberate decision, and a page that also guards itself is simply defended twice.
 *
 * Matching is by path segment: a rule for "/users" also covers "/users/anything", and the LONGEST
 * matching rule wins, so "/artisans/new" can be stricter than "/artisans". Anything unlisted is
 * open to any signed-in user — that is the correct default for the read surfaces (lists, search,
 * activity, tasks, sharing, feedback, the walkthrough and a user's own settings).
 *
 * Admin view is deliberately NOT consulted. The toggle hides admin chrome from an admin who wants
 * to browse as an ordinary user; it is not a permission, and it must never lock an admin out of a
 * URL the API would happily serve.
 * ──────────────────────────────────────────────────────────────────────────── */

export type RouteGuard = {
  /** The path this rule covers, together with everything nested beneath it. */
  path: string;
  can: (user: User | null | undefined) => boolean;
  /** The backend dependency this mirrors (backend/app/core/deps.py) — keep the two in step. */
  gate: string;
  title: string;
  message: string;
};

/** Shared copy for the four create routes, mirroring `require_record_creator`'s 403 detail. */
const RECORD_CREATOR_GUARD = {
  can: canCreateRecords,
  gate: "require_record_creator",
  title: "Researcher access required",
  message:
    "Creating records needs Researcher access. You can still answer interviews, upload media and comment."
} as const;

export const ROUTE_GUARDS: RouteGuard[] = [
  {
    path: "/users",
    can: canManageUsers,
    gate: "require_professor",
    title: "Professor access required",
    message:
      "For professors and admins."
  },
  {
    path: "/admin",
    can: isAdmin,
    gate: "require_admin",
    title: "Admin access required",
    message: "Admins only."
  },
  {
    // Nested under /admin, which is already admin-guarded — this row exists for its WORDING, not for
    // its predicate. Longest-match wins, so somebody who follows a link to the roster and is not
    // entitled to it is told what the roster is rather than being told about a settings hub they
    // were not trying to open. If the tiers ever diverge (a roster-only capability, say), this is
    // the line that has to change, and it is already here to change.
    path: "/admin/access-roster",
    can: canManageAccessRoster,
    gate: "require_admin",
    title: "Admin access required",
    message:
      "The access roster is for admins only."
  },
  {
    // The page now holds two things with two different owners, so the ROUTE is admin and the halves
    // gate themselves. Key VALUES stay master-admin (every /secrets route is require_master_admin,
    // and the page renders ApiKeysPanel only for them); RANKING the transcription providers is
    // require_admin on the server, and this guard used to slam the door on the admins entitled to
    // it — the ranking was unreachable for the exact people who asked for it.
    path: "/settings/api-keys",
    can: isAdmin,
    gate: "require_admin",
    title: "Admin access required",
    message:
      "Admins only."
  },
  {
    // Batch task assignment (POST /tasks/batch, /tasks/batches, /tasks/progress) is admin-only;
    // /tasks itself stays open, because every user can be an assignee.
    path: "/settings/tasks",
    can: canAssignTasks,
    gate: "require_admin",
    title: "Admin access required",
    message:
      "Admins only. Your own tasks are on the Tasks page."
  },
  {
    path: "/review",
    can: canReview,
    gate: "require_reviewer",
    title: "Review access required",
    message:
      "Needs review access."
  },
  {
    path: "/data",
    can: canDownloadDataset,
    gate: "require_dataset_downloader",
    title: "Dataset access required",
    message:
      "Needs dataset access. Use Search instead."
  },
  { path: "/artisans/new", ...RECORD_CREATOR_GUARD },
  { path: "/products/new", ...RECORD_CREATOR_GUARD },
  { path: "/tools/new", ...RECORD_CREATOR_GUARD }
];

function routeMatches(rulePath: string, pathname: string): boolean {
  return pathname === rulePath || pathname.startsWith(`${rulePath}/`);
}

/** The most specific guard covering `pathname`, or null when the route is open to any signed-in user. */
export function routeGuardFor(pathname: string): RouteGuard | null {
  let best: RouteGuard | null = null;
  for (const guard of ROUTE_GUARDS) {
    if (!routeMatches(guard.path, pathname)) continue;
    if (!best || guard.path.length > best.path.length) best = guard;
  }
  return best;
}

export function canAccessRoute(user: User | null | undefined, pathname: string): boolean {
  const guard = routeGuardFor(pathname);
  return !guard || guard.can(user);
}

/* ────────────────────────────────────────────────────────────────────────────
 * Route redirects — the other half of gating, for pages that HAVE an ordinary-user twin.
 *
 * A ROUTE_GUARDS entry answers "no" with a lock panel, which is the honest answer when the page has
 * no counterpart: there is no ordinary-user version of /users or of the managed API keys, so naming
 * the tier and offering the dashboard is all that can truthfully be said.
 *
 * It is the WRONG answer when the same job also exists for the person asking. Workshop access is the
 * case in point: the admin console and the request page are two views of one WorkshopAssignment row,
 * so a researcher who opens "Workshop access" wants the half that belongs to them, and stopping them
 * at a padlock hides a page they are fully entitled to. These rules therefore send them to it —
 * `to` must always be a route the user can genuinely use, so this can never dead-end.
 *
 * Not listed, deliberately, because nothing executes the rule for them yet (declaring a redirect
 * that no one performs would read as enforcement that is not there):
 *  - /data → /search. The guard copy already says "browse records instead" but gives no way there.
 *  - /settings/tasks → /tasks. Same shape: the assignment board's twin is your own task list.
 * Both become one-liners here the moment AppShell consults this table — see routeRedirectFor.
 * ──────────────────────────────────────────────────────────────────────────── */

export type RouteRedirect = {
  /** The path this rule covers, together with everything nested beneath it. */
  path: string;
  /** Who sees the page as built. Everyone else is sent to `to` — this never widens `can`. */
  can: (user: User | null | undefined) => boolean;
  /** The ordinary-user route that does the same job for the person being turned away. */
  to: string;
};

export const ROUTE_REDIRECTS: RouteRedirect[] = [
  {
    path: "/workshop-access/manage",
    can: isAdmin,
    to: "/workshop-access/request"
  }
];

/**
 * Where `pathname` should send this user instead, or null when they may stay.
 *
 * Enforced today by the /workshop-access pages themselves. AppShell is the right place for it —
 * above every page, the way ROUTE_GUARDS is — and adopting it there is a `router.replace` on the
 * result of this call, checked BEFORE the ROUTE_GUARDS lock so a route with a twin redirects rather
 * than locks. Until then the table stays limited to routes that enforce it locally.
 */
export function routeRedirectFor(user: User | null | undefined, pathname: string): string | null {
  let best: RouteRedirect | null = null;
  for (const rule of ROUTE_REDIRECTS) {
    if (!routeMatches(rule.path, pathname)) continue;
    if (!best || rule.path.length > best.path.length) best = rule;
  }
  return best && !best.can(user) ? best.to : null;
}
