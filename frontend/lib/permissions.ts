import type { User, UserRole } from "@/lib/types";

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

export function assignableRoles(user: User | null | undefined): UserRole[] {
  return ROLES_BY_RANK.filter((role) => ROLE_RANK[role] <= roleRank(user));
}

export function canManageUser(user: User | null | undefined, target: User): boolean {
  if (isMasterAdmin(user)) return target.role !== "MASTER_ADMIN" || target.id === user?.id;
  return roleRank(target) < roleRank(user);
}

export function canManageUsers(user: User | null | undefined) {
  return hasRank(user, "PROFESSOR");
}

export function canAssignTasks(user: User | null | undefined) {
  return isContentAdmin(user);
}

export function isAdmin(user: User | null | undefined) {
  return user?.role === "MASTER_ADMIN" || user?.role === "ADMIN";
}

export function isMasterAdmin(user: User | null | undefined) {
  return user?.role === "MASTER_ADMIN";
}

export const CONTENT_ADMIN_ROLES: readonly string[] = ["MINISTRY_ADMIN", "ADMIN", "MASTER_ADMIN"];

export function isContentAdmin(user: User | null | undefined) {
  return !!user && CONTENT_ADMIN_ROLES.includes(user.role);
}

export function canManageQuestionnaire(user: User | null | undefined) {
  return hasRank(user, "PROFESSOR") || !!user?.canManageQuestionnaire;
}

export function canManageAccessRoster(user: User | null | undefined) {
  return isAdmin(user);
}

export function canManageCrafts(user: User | null | undefined) {
  return hasRank(user, "PROFESSOR");
}

export function canManageWorkshops(user: User | null | undefined) {
  return hasRank(user, "PROFESSOR");
}

export function canReview(user: User | null | undefined) {
  return hasRank(user, "FIELD_CONTRIBUTOR") || !!user?.canReview;
}

export function anySignedIn(user: User | null | undefined) {
  return !!user;
}

export function canDownloadDataset(user: User | null | undefined) {
  return hasRank(user, "PROFESSOR") || !!user?.canDownloadDataset;
}

export function canCreateRecords(user: User | null | undefined) {
  return hasRank(user, "RESEARCHER");
}

export function canEditOwnOrAdmin(user: User | null | undefined, ownerId?: string | null) {
  return isContentAdmin(user) || (!!user?.id && !!ownerId && user.id === ownerId);
}

export function mayEditQuestionnaire(user: User | null | undefined, createdById?: string | null) {
  return canEditOwnOrAdmin(user, createdById);
}

export function mayDeleteQuestionnaire(user: User | null | undefined, createdById?: string | null) {
  return mayEditQuestionnaire(user, createdById);
}

export function canViewProvenance(user: User | null | undefined) {
  return isAdmin(user) || !!user?.canViewProvenance;
}

export function canManageSecrets(user: User | null | undefined) {
  return isMasterAdmin(user);
}

export type RouteGuard = {
  path: string;
  can: (user: User | null | undefined) => boolean;
  gate: string;
  title: string;
  message: string;
};

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
    path: "/admin/access-roster",
    can: canManageAccessRoster,
    gate: "require_admin",
    title: "Admin access required",
    message:
      "The access roster is for admins only."
  },
  {
    path: "/settings/api-keys",
    can: isAdmin,
    gate: "require_admin",
    title: "Admin access required",
    message:
      "Admins only."
  },
  {
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
  {
    path: "/data/go",
    can: anySignedIn,
    gate: "get_current_user + the record's own visibility (data_report go routes)",
    title: "Access required",
    message:
      "You need access to this record. Ask its owner or an admin."
  },
  { path: "/artisans/new", ...RECORD_CREATOR_GUARD },
  { path: "/products/new", ...RECORD_CREATOR_GUARD },
  { path: "/tools/new", ...RECORD_CREATOR_GUARD }
];

function routeMatches(rulePath: string, pathname: string): boolean {
  return pathname === rulePath || pathname.startsWith(`${rulePath}/`);
}

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

export type RouteRedirect = {
  path: string;
  can: (user: User | null | undefined) => boolean;
  to: string;
};

export const ROUTE_REDIRECTS: RouteRedirect[] = [
  {
    path: "/workshop-access/manage",
    can: isAdmin,
    to: "/workshop-access/request"
  }
];

export function routeRedirectFor(user: User | null | undefined, pathname: string): string | null {
  let best: RouteRedirect | null = null;
  for (const rule of ROUTE_REDIRECTS) {
    if (!routeMatches(rule.path, pathname)) continue;
    if (!best || rule.path.length > best.path.length) best = rule;
  }
  return best && !best.can(user) ? best.to : null;
}
