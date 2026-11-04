import { apiFetch, buildQuery } from "@/lib/api";
import type { PageResult, UserRole } from "@/lib/types";

export type AccessStatus = "PENDING" | "ACTIVE" | "REJECTED" | "SUSPENDED";

export const DECIDABLE_STATUSES: AccessStatus[] = ["ACTIVE", "REJECTED", "SUSPENDED"];

export type AccessRosterEntry = {
  id: string;
  email: string;
  status: AccessStatus;
  grantedRole: UserRole;
  fullName: string | null;
  notes: string | null;
  joinedAt: string | null;
  firstSeenAt: string | null;
  requestCount: number;
  firstRequestedAt: string | null;
  lastRequestedAt: string | null;
  decidedAt: string | null;
  decidedById: string | null;
  createdAt: string | null;
  updatedAt: string | null;
  userId: string | null;
  accountRole: UserRole | null;
  accountName: string | null;
  accountCreated?: boolean;
};

export type AccessRosterQuery = {
  page?: number;
  pageSize?: number;
  search?: string;
  status?: AccessStatus;
};

export async function listAccessRoster(query: AccessRosterQuery = {}) {
  return apiFetch<PageResult<AccessRosterEntry>>(
    `/access-roster${buildQuery({
      page: query.page,
      pageSize: query.pageSize,
      search: query.search,
      status: query.status
    })}`
  );
}

export async function accessRosterPendingCount(): Promise<number> {
  const result = await apiFetch<{ pending: number }>("/access-roster/pending-count");
  return result.pending;
}

export type AccessRosterCreateBody = {
  email: string;
  fullName?: string | null;
  notes?: string | null;
  grantedRole?: UserRole | null;
  isActive?: boolean;
  sendInvite?: boolean;
  password?: string;
  mustChangePassword?: boolean;
};

export type RosterSignIn = "PROVIDER" | "PASSWORD";

export function rosterCreateBody(fields: {
  email: string;
  fullName: string;
  notes: string;
  grantedRole: string;
  sendInvite: boolean;
  signIn: RosterSignIn;
  password: string;
  mustChangePassword: boolean;
}): AccessRosterCreateBody {
  return {
    email: fields.email.trim(),
    fullName: fields.fullName.trim() || null,
    notes: fields.notes.trim() || null,
    grantedRole: (fields.grantedRole.trim() || null) as UserRole | null,
    ...(fields.sendInvite ? { sendInvite: true } : {}),
    ...(fields.signIn === "PASSWORD" ? { password: fields.password, mustChangePassword: fields.mustChangePassword } : {})
  };
}

export async function addToAccessRoster(body: AccessRosterCreateBody) {
  return apiFetch<AccessDecisionAnswer>("/access-roster", {
    method: "POST",
    body: JSON.stringify(body)
  });
}

export type AccessRosterUpdateBody = {
  email?: string;
  status?: AccessStatus;
  grantedRole?: UserRole;
  fullName?: string | null;
  notes?: string | null;
  sendInvite?: boolean;
};

export type AccessDecisionAnswer = AccessRosterEntry & { accountPromotionHeld?: string | null };

export function promotionHeldSentence(answer: Pick<AccessDecisionAnswer, "accountPromotionHeld"> | null | undefined): string | null {
  const sentence = answer?.accountPromotionHeld;
  return typeof sentence === "string" && sentence.trim() ? sentence.trim() : null;
}

export async function updateAccessRosterEntry(id: string, body: AccessRosterUpdateBody) {
  return apiFetch<AccessDecisionAnswer>(`/access-roster/${id}`, {
    method: "PATCH",
    body: JSON.stringify(body)
  });
}

export async function suspendAccessRosterEntry(id: string) {
  return apiFetch<AccessRosterEntry>(`/access-roster/${id}`, { method: "DELETE" });
}

export const ACCESS_STATUS_LABELS: Record<AccessStatus, string> = {
  PENDING: "Awaiting approval",
  ACTIVE: "May sign in",
  REJECTED: "Not approved",
  SUSPENDED: "Suspended"
};

export const ACCESS_STATUS_BLURBS: Record<AccessStatus, string> = {
  PENDING: "Waiting for a decision.",
  ACTIVE: "Can sign in.",
  REJECTED: "Refused.",
  SUSPENDED: "Access ended."
};

export function accessStatusLabel(status: AccessStatus | string): string {
  return ACCESS_STATUS_LABELS[status as AccessStatus] ?? String(status);
}

export function accessStatusChip(status: AccessStatus): string {
  switch (status) {
    case "ACTIVE":
      return "border-success-600/25 bg-success-100 text-success-600";
    case "PENDING":
      return "border-amber-500/30 bg-amber-100 text-amber-800";
    case "SUSPENDED":
    case "REJECTED":
    default:
      return "border-error-600/25 bg-error-100 text-error-600";
  }
}

export function accessRefusalCode(error: unknown): string | null {
  if (!error || typeof error !== "object") return null;
  const payload = (error as { payload?: unknown }).payload;
  if (!payload || typeof payload !== "object") return null;
  const detail = (payload as { detail?: unknown }).detail;
  if (!detail || typeof detail !== "object") return null;
  const code = (detail as { code?: unknown }).code;
  return typeof code === "string" && code.startsWith("ACCESS_") ? code : null;
}

export function isAccessPending(error: unknown): boolean {
  return accessRefusalCode(error) === "ACCESS_PENDING";
}

export function invitationLabel(entry: AccessRosterEntry): string {
  if (entry.firstSeenAt) return "Signed in";
  if (entry.status !== "ACTIVE") return "No access to take up";
  return "Not signed in yet";
}

export function requestLabel(entry: AccessRosterEntry): string {
  if (!entry.requestCount) return "Added by an admin";
  if (entry.requestCount === 1) return "Asked once";
  return `Asked ${entry.requestCount} times`;
}
