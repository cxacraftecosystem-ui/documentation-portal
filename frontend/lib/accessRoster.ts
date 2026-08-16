/**
 * The access roster — the client half of the sign-in gate.
 *
 * WHAT THE ROSTER IS. Until the gate shipped, this application refused nobody: any verified Google
 * address on earth got a `User` row and a bearer token, automatically, at the lowest tier. The
 * roster is the institution's answer to "who may sign in at all", and it is deliberately NOT the
 * `User` table — a row usually exists BEFORE the account does (an admin admits an address; the
 * account provisions itself the first time that person signs in) and it outlives the access
 * (suspending keeps the row, so an audit two years from now can still answer "who was admitted, and
 * when"). That is why every endpoint here is keyed by EMAIL and not by user id.
 *
 * THE PENDING HALF IS ALSO THE NOTIFICATION. There is no email and no push anywhere in this
 * codebase, so "the admins and master admins should get a notification to approve or reject the
 * user" is served by a count on surfaces admins already open (`GET /access-roster/pending-count`)
 * plus the queue itself (`GET /access-roster?status=PENDING`). Everything in this module exists to
 * feed one of those two.
 *
 * EVERY FUNCTION HERE IS `require_admin` ON THE SERVER (backend/app/api/routes/access_roster.py).
 * Mirror that with `canManageAccessRoster` before rendering a control — never after.
 */

import { apiFetch, buildQuery } from "@/lib/api";
import type { PageResult, UserRole } from "@/lib/types";

/**
 * The four statuses, mirroring the `AccessStatus` enum in `backend/prisma/schema.prisma`.
 *
 * Only ACTIVE admits. That is the whole security model and it is worth stating on the client too:
 * a PENDING row is created BY THE REFUSED CALLER, so "a row exists" and "this person may sign in"
 * are one clause apart, and any UI that treats the presence of a row as admission is describing an
 * authentication bypass to the admin reading it.
 */
export type AccessStatus = "PENDING" | "ACTIVE" | "REJECTED" | "SUSPENDED";

/** The statuses an admin may SET, matching `access_roster.DECIDABLE_STATUSES`. PENDING is absent. */
export const DECIDABLE_STATUSES: AccessStatus[] = ["ACTIVE", "REJECTED", "SUSPENDED"];

/** One roster row, exactly as `access_roster.roster_payload` serialises it. */
export type AccessRosterEntry = {
  id: string;
  email: string;
  status: AccessStatus;
  /** The tier this address gets when it is admitted. Not the live account role — see `accountRole`. */
  grantedRole: UserRole;
  /** ADMIN-WRITTEN ONLY. The sign-in path never stores a name a stranger supplied. */
  fullName: string | null;
  notes: string | null;
  /** The date of joining the platform. Stamped the FIRST time an address is admitted, never moved. */
  joinedAt: string | null;
  /** First successful sign-in. Null = admitted but never taken up — the invitation nobody opened. */
  firstSeenAt: string | null;
  /** How many times a refused sign-in bumped this row. Zero = an admin added it; nobody asked. */
  requestCount: number;
  firstRequestedAt: string | null;
  lastRequestedAt: string | null;
  decidedAt: string | null;
  decidedById: string | null;
  createdAt: string | null;
  updatedAt: string | null;
  /**
   * The account behind the address, when one exists. Resolved server-side in ONE query for the
   * whole page, which is why it arrives on the row instead of being looked up here.
   */
  userId: string | null;
  /**
   * The account's LIVE role, which disagrees with `grantedRole` the moment somebody is promoted
   * through the users screen. Both are shown on the roster on purpose: an admin reading only
   * `grantedRole` would be looking at a stale tier and believing it current.
   */
  accountRole: UserRole | null;
  accountName: string | null;
};

export type AccessRosterQuery = {
  page?: number;
  pageSize?: number;
  search?: string;
  /** Absent means EVERY status, which is the roster screen's default — see the page for why. */
  status?: AccessStatus;
};

/**
 * A page of the roster.
 *
 * REFUSED ROWS COME BACK UNLESS FILTERED OUT, and that default is the point of the screen: an admin
 * looking for somebody who says they cannot sign in needs to SEE the row that is refusing them. A
 * list that hid it would leave them re-adding an address the unique index then rejects with a 409,
 * with nothing on screen ever explaining why.
 */
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

/**
 * How many people are waiting on an administrator. THE NOTIFICATION, in its entirety.
 *
 * A bare count and not a list, deliberately: this is meant to be read by every admin surface, and
 * the server answers it out of an index without reading a row. Do not "improve" it into a call that
 * also returns the queue — the badge would become the most expensive query on the dashboard.
 */
export async function accessRosterPendingCount(): Promise<number> {
  const result = await apiFetch<{ pending: number }>("/access-roster/pending-count");
  return result.pending;
}

export type AccessRosterCreateBody = {
  email: string;
  fullName?: string | null;
  notes?: string | null;
  /** Absent means the bottom of the ladder — "all the users by default join as the lowest rung". */
  grantedRole?: UserRole | null;
  /** Defaults true on the server: "add somebody to the allow list" is what the endpoint is for. */
  isActive?: boolean;
};

/**
 * Put an address on the allow list. No account is required and none is created.
 *
 * A duplicate answers 409 with a sentence that NAMES the existing row and says restoring it is an
 * update rather than a second add. Show that sentence verbatim; it is the only thing that explains
 * why the add "did nothing".
 */
export async function addToAccessRoster(body: AccessRosterCreateBody) {
  return apiFetch<AccessRosterEntry>("/access-roster", {
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
};

/**
 * Approve, reject, restore or correct one entry.
 *
 * ABSENCE MEANS "LEAVE IT ALONE" — the server reads `exclude_unset`, so only send what changed. A
 * body that carried every field would let an admin fixing a typo in a note silently re-approve
 * somebody another admin had rejected.
 */
export async function updateAccessRosterEntry(id: string, body: AccessRosterUpdateBody) {
  return apiFetch<AccessRosterEntry>(`/access-roster/${id}`, {
    method: "PATCH",
    body: JSON.stringify(body)
  });
}

/**
 * SUSPEND. Never a delete, whatever the HTTP verb says.
 *
 * `DELETE /access-roster/{id}` sets `status = SUSPENDED` and answers 200 with the suspended row, so
 * the entry stays on screen — dated, and one click from being restored. A real delete would put the
 * person straight back into the pending queue at their next sign-in, which is precisely the loop
 * REJECTED and SUSPENDED exist to break.
 */
export async function suspendAccessRosterEntry(id: string) {
  return apiFetch<AccessRosterEntry>(`/access-roster/${id}`, { method: "DELETE" });
}

/* ────────────────────────────────────────────────────────────────────────────
 * Shared wording.
 *
 * These strings are read by the roster page, the admin hub tile and the nav badge, and the Android
 * client says the same things in `ui/AccessRosterScreen.kt`. A status that reads "Pending" on one
 * surface and "Awaiting approval" on another is two features to the admin using both.
 * ──────────────────────────────────────────────────────────────────────────── */

export const ACCESS_STATUS_LABELS: Record<AccessStatus, string> = {
  PENDING: "Awaiting approval",
  ACTIVE: "May sign in",
  REJECTED: "Not approved",
  SUSPENDED: "Suspended"
};

/**
 * What each status MEANS for the person behind the address, in the admin's terms.
 *
 * Worded rather than coloured, because colour never carries meaning alone in this app — the
 * judgement has to survive colour-blindness, a greyscale print-out and forced-colours mode.
 */
export const ACCESS_STATUS_BLURBS: Record<AccessStatus, string> = {
  PENDING: "They tried to sign in and were turned away. Nothing happens until an admin decides.",
  ACTIVE: "They can sign in. Their account is created on first sign-in if it does not exist yet.",
  REJECTED: "They are refused, and retrying does not put them back in the queue.",
  SUSPENDED: "They had access and it was ended. Their sessions stop at the next request."
};

export function accessStatusLabel(status: AccessStatus | string): string {
  return ACCESS_STATUS_LABELS[status as AccessStatus] ?? String(status);
}

/**
 * The chip classes for a status.
 *
 * PENDING is amber rather than red: it is not a refusal an admin made, it is work waiting for one.
 * `amber-100`/`amber-800` and not `amber-50`/`amber-200` — only 100/500/800 are brand rungs in this
 * config, the rest deep-merge with stock Tailwind amber and do not pair.
 */
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

/* ────────────────────────────────────────────────────────────────────────────
 * The refusal, as the sign-in page reads it.
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The machine-readable half of a gated refusal: `ACCESS_PENDING`, `ACCESS_REJECTED` or
 * `ACCESS_SUSPENDED`, or null when the failure was anything else (a wrong password, a 500, no
 * network).
 *
 * WHY A CODE AND NOT A STRING MATCH. The server sends
 * `{"detail": {"code": "ACCESS_PENDING", "message": "…"}}` precisely so a client can branch on
 * something stable. Matching the English prose instead would break the first time somebody improves
 * a sentence, and the thing that would break is the sign-in page's ability to tell a person waiting
 * for an administrator apart from a person who mistyped their password — which is the entire ruling
 * this feature turns on.
 *
 * The MESSAGE is never composed here. `apiFetch` has already pulled `detail.message` out and put it
 * on `error.message`, and that sentence — written once, on the server, so that both clients and
 * both applications say the same thing — is the one shown to the person.
 */
export function accessRefusalCode(error: unknown): string | null {
  if (!error || typeof error !== "object") return null;
  const payload = (error as { payload?: unknown }).payload;
  if (!payload || typeof payload !== "object") return null;
  const detail = (payload as { detail?: unknown }).detail;
  if (!detail || typeof detail !== "object") return null;
  const code = (detail as { code?: unknown }).code;
  return typeof code === "string" && code.startsWith("ACCESS_") ? code : null;
}

/**
 * Is this refusal the "your request is with an administrator" one?
 *
 * Kept apart from the other two because it is the only one where the right thing for the person to
 * do is NOTHING — no retry, no password reset, no second attempt with another address. The sign-in
 * page renders it as a waiting state rather than as an error for exactly that reason.
 */
export function isAccessPending(error: unknown): boolean {
  return accessRefusalCode(error) === "ACCESS_PENDING";
}

/**
 * "Has this admitted address ever actually been used?" — the one question the roster exists to
 * answer that no other screen can.
 *
 * Never the word "never": a null `firstSeenAt` is a statement about an INVITATION, not about a
 * person, and an admin chasing five addresses added in March needs to be able to tell "they have
 * not opened it yet" from "we never let them in". An entry that is not admitted has no invitation
 * outstanding at all, so it says so instead.
 */
export function invitationLabel(entry: AccessRosterEntry): string {
  if (entry.firstSeenAt) return "Signed in";
  if (entry.status !== "ACTIVE") return "No access to take up";
  return "Not signed in yet";
}

/**
 * How the person behind a row got here — the difference between somebody an admin added and
 * somebody who asked.
 *
 * `requestCount` is 0 for a row an administrator created (nobody asked to be there) and rises by
 * one on every refused attempt. A rising count on a REJECTED row is how an admin notices somebody
 * who keeps trying and goes to talk to them, which is the whole reason a rejection stays a
 * rejection instead of re-opening.
 */
export function requestLabel(entry: AccessRosterEntry): string {
  if (!entry.requestCount) return "Added by an admin";
  if (entry.requestCount === 1) return "Asked once";
  return `Asked ${entry.requestCount} times`;
}
