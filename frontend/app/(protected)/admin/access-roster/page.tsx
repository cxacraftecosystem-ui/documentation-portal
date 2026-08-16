"use client";

/**
 * /admin/access-roster — who may sign in to the repository at all, and the queue of people waiting
 * for an answer.
 *
 * WHY THIS PAGE EXISTS. This application shipped for months with no sign-in refusal of any kind:
 * any address Google would vouch for got an account and a bearer token, automatically, at the
 * lowest tier. The institution had no say in who was in its own repository. This screen is that
 * say — and the PENDING section at the top of it is also the notification, because there is no
 * email and no push anywhere in this codebase and never has been.
 *
 * THREE THINGS ON THIS PAGE ARE NOT NEGOTIABLE, and each of them was argued for on the server side
 * as well (backend/app/services/access_roster.py — read it before changing behaviour here):
 *
 * 1. **A ROW IS NOT AN ADMISSION.** Only `status === "ACTIVE"` admits. A PENDING row was created BY
 *    THE REFUSED CALLER, so any UI that reads "there is a row for this address" as "this person is
 *    allowed in" is describing an authentication bypass to the admin looking at it. Every affordance
 *    here names the status it is acting on.
 * 2. **THERE IS NO DELETE.** `DELETE /access-roster/{id}` is a SUSPENSION that answers 200 with the
 *    suspended row, so the entry stays on screen — dated, and one click from being restored. The
 *    roster is the record that an address was recognised, and that record outlives the access. A
 *    real delete would also drop the person straight back into the pending queue at their next
 *    sign-in, which is the loop REJECTED and SUSPENDED exist to break.
 * 3. **REJECTED AND SUSPENDED ROWS ARE LISTED BY DEFAULT.** An admin arrives here because somebody
 *    says they cannot sign in; the row refusing them is the one they need to SEE. Hiding it leaves
 *    them re-adding an address the unique index rejects with a 409 and no explanation on screen.
 *
 * TRUNCATION IS STATED, EVERYWHERE. The pending section shows one page of the queue and says so
 * when there is more; the table prints its total; the server caps `pageSize` at 100 whatever is
 * asked for. A list that quietly stops is indistinguishable from a place with no records, and that
 * is the single most repeated bug class in this repository.
 */

import Link from "next/link";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { BadgeCheck, Clock, MailPlus, RotateCcw, ShieldCheck } from "lucide-react";

import { EmptyState } from "@/components/EmptyState";
import { Field, TextArea, TextInput } from "@/components/FormControls";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { ResizableTh } from "@/components/ResizableTh";
import { RestrictedPanel } from "@/components/settings/RestrictedPanel";
import { RowActions, rowAction } from "@/components/RowActions";
import { SearchInput } from "@/components/SearchInput";
import { useAuth } from "@/components/AuthProvider";
import { useConfirm } from "@/components/dialogs/ConfirmDialog";
import { usePendingAccessCount } from "@/components/hooks/usePendingAccessCount";
import { Dropdown } from "@/components/ui/Dropdown";
import { ApiError } from "@/lib/api";
import {
  accessStatusChip,
  accessStatusLabel,
  addToAccessRoster,
  invitationLabel,
  listAccessRoster,
  requestLabel,
  suspendAccessRosterEntry,
  updateAccessRosterEntry,
  type AccessRosterEntry,
  type AccessStatus
} from "@/lib/accessRoster";
import { formatDate, formatDateTime } from "@/lib/format";
import { assignableRoles, canManageAccessRoster, roleLabel } from "@/lib/permissions";
import type { PageResult, UserRole } from "@/lib/types";

const PAGE_SIZE = 20;

/**
 * How much of the pending queue the top section shows at once.
 *
 * Bigger than the table's page size because the queue is the thing an admin came to clear and
 * paging it twice over would be silly, and still bounded because this is one section of a page and
 * the server's own ceiling is 100. Anything beyond it is STATED and one click from being read in
 * the table below, filtered to PENDING — never silently dropped.
 */
const PENDING_PAGE_SIZE = 25;

/**
 * The status filter. Empty means EVERY status, by absence — the same rule every other filter in
 * this app follows, and the reason `buildQuery` drops "" exactly as it drops null. The default is
 * deliberately the wider of the two; see rule 3 in the file header.
 */
const STATUS_OPTIONS = [
  { value: "", label: "Every status" },
  { value: "PENDING", label: "Awaiting approval" },
  { value: "ACTIVE", label: "May sign in" },
  { value: "REJECTED", label: "Not approved" },
  { value: "SUSPENDED", label: "Suspended" }
];

export default function AccessRosterPage() {
  const { user } = useAuth();
  const confirm = useConfirm();
  const permitted = canManageAccessRoster(user);
  const { pending: pendingCount, refresh: refreshPendingCount } = usePendingAccessCount();

  const [data, setData] = useState<PageResult<AccessRosterEntry> | null>(null);
  const [queue, setQueue] = useState<PageResult<AccessRosterEntry> | null>(null);
  const [page, setPage] = useState(1);
  const [query, setQuery] = useState("");
  const [applied, setApplied] = useState("");
  const [statusFilter, setStatusFilter] = useState("");
  const [saving, setSaving] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const skipFirstDebounce = useRef(true);

  /**
   * Fetch generations rather than aborts: `listAccessRoster` takes no signal, and what matters is
   * IGNORING the late answer. Without this, a typed search whose first response arrives after the
   * second overwrites the newer list with older rows and the screen shows results for a query
   * nobody can see any more. Two counters, because the two lists race independently.
   */
  const listGeneration = useRef(0);
  const queueGeneration = useRef(0);

  /** The tiers this admin may hand out — bounded by their own, mirroring `users.assert_role`. */
  const roleOptions = useMemo(
    () => assignableRoles(user).map((role) => ({ value: role, label: roleLabel(role) })),
    [user]
  );

  const loadList = useCallback(async () => {
    if (!permitted) return;
    const mine = ++listGeneration.current;
    try {
      const result = await listAccessRoster({
        page,
        pageSize: PAGE_SIZE,
        search: applied || undefined,
        status: (statusFilter || undefined) as AccessStatus | undefined
      });
      if (mine !== listGeneration.current) return;
      setData(result);
      setError(null);
    } catch (err) {
      if (mine !== listGeneration.current) return;
      setError(err instanceof Error ? err.message : "Unable to load the access roster");
      // `data` is deliberately left standing on a failed refresh: replacing a roster the admin can
      // still read with "nobody is on the list" is indistinguishable from an empty institution, and
      // on THIS screen that reads as "the gate has locked everybody out".
    }
  }, [applied, page, permitted, statusFilter]);

  const loadQueue = useCallback(async () => {
    if (!permitted) return;
    const mine = ++queueGeneration.current;
    try {
      const result = await listAccessRoster({ page: 1, pageSize: PENDING_PAGE_SIZE, status: "PENDING" });
      if (mine !== queueGeneration.current) return;
      setQueue(result);
    } catch {
      // Silent, and the queue already on screen is left standing: its own failure must not put an
      // error banner over the roster below, which may have loaded perfectly well.
    }
  }, [permitted]);

  useEffect(() => {
    loadList();
  }, [loadList]);

  useEffect(() => {
    loadQueue();
  }, [loadQueue]);

  // Live search: 350ms after typing stops, Enter applies immediately. Both go through the same
  // state so the generation guard above stays the only race protection needed.
  useEffect(() => {
    if (skipFirstDebounce.current) {
      skipFirstDebounce.current = false;
      return;
    }
    const timer = window.setTimeout(() => {
      setApplied(query);
      setPage(1);
    }, 350);
    return () => window.clearTimeout(timer);
  }, [query]);

  /** Every write refreshes all three views, because one decision changes all three. */
  const reloadEverything = useCallback(async () => {
    await Promise.all([loadList(), loadQueue()]);
    refreshPendingCount();
  }, [loadList, loadQueue, refreshPendingCount]);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // React nulls `event.currentTarget` across an await, so the FormData is read before any async
    // work — after the first `await` it is null and every field arrives empty.
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    const email = String(form.get("email") ?? "").trim();
    const fullName = String(form.get("fullName") ?? "").trim();
    const notes = String(form.get("notes") ?? "").trim();
    const grantedRole = String(form.get("grantedRole") ?? "").trim();
    if (!email) return;

    setSaving(true);
    setError(null);
    setNotice(null);
    try {
      const created = await addToAccessRoster({
        email,
        fullName: fullName || null,
        notes: notes || null,
        grantedRole: (grantedRole || null) as UserRole | null
      });
      setNotice(
        `${created.email} is on the access roster as ${roleLabel(created.grantedRole)}. They can sign in with that address — the account is created the first time they do.`
      );
      formElement.reset();
      await reloadEverything();
    } catch (err) {
      // A 409 carries the sentence that matters — it names the existing row, gives its status and
      // says that changing it is an update rather than a second add — so it is shown verbatim, and
      // the search box is then pointed at the address so the admin is looking at the row rather
      // than reading about it. That is the whole fix for "an admin re-adds an email the unique
      // index rejects, with no explanation visible anywhere in the UI".
      setError(err instanceof Error ? err.message : "Unable to add this address to the roster");
      if (err instanceof ApiError && err.status === 409) {
        setQuery(email);
        setStatusFilter("");
        setPage(1);
      }
    } finally {
      setSaving(false);
    }
  }

  /** Approve: the ONE call that stamps the date of joining, and the only one that admits. */
  async function approve(entry: AccessRosterEntry, grantedRole: UserRole) {
    setBusyId(entry.id);
    setError(null);
    try {
      const updated = await updateAccessRosterEntry(entry.id, { status: "ACTIVE", grantedRole });
      setNotice(
        `${updated.email} can sign in, as ${roleLabel(updated.grantedRole)}. Their date of joining is ${formatDate(updated.joinedAt)}.`
      );
      await reloadEverything();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to approve this request");
    } finally {
      setBusyId(null);
    }
  }

  async function reject(entry: AccessRosterEntry) {
    const ok = await confirm({
      title: `Refuse access for ${entry.email}?`,
      body: "They will be told this address is not approved, and told to contact an administrator.",
      // The note is the part an admin actually needs, because the alternative design — a rejection
      // the applicant can undo by signing in again — is the one that makes this queue unworkable.
      note:
        "Signing in again will NOT put them back in this queue: the entry stays refused and only the attempt counter moves, so you will not be asked about this address again unless you change it here. The entry is kept either way.",
      confirmLabel: "Refuse access",
      tone: "danger"
    });
    if (!ok) return;
    setBusyId(entry.id);
    setError(null);
    try {
      const updated = await updateAccessRosterEntry(entry.id, { status: "REJECTED" });
      setNotice(`${updated.email} is refused. The entry stays on the roster and can be approved later.`);
      await reloadEverything();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to refuse this request");
    } finally {
      setBusyId(null);
    }
  }

  async function suspend(entry: AccessRosterEntry) {
    const ok = await confirm({
      title: `Suspend ${entry.fullName || entry.accountName || entry.email}?`,
      body: "They are signed out at their next request and refused at every sign-in after it, until the entry is restored.",
      // The tone is danger because access is being taken away, but the note has to correct the word
      // the tone implies: nothing is deleted here, and an admin who believes otherwise will hesitate
      // over something that is one click from being undone.
      note:
        "The roster entry is kept — it records that this address was admitted, and that record outlives the access. Nothing they have recorded is touched, and Restore gives the access back with their original date of joining intact.",
      confirmLabel: "Suspend",
      tone: "danger"
    });
    if (!ok) return;
    setBusyId(entry.id);
    setError(null);
    try {
      const updated = await suspendAccessRosterEntry(entry.id);
      setNotice(
        `${updated.email} is suspended${updated.decidedAt ? ` as of ${formatDate(updated.decidedAt)}` : ""}. The entry stays on the roster.`
      );
      await reloadEverything();
    } catch (err) {
      // The master admin's row is protected server-side (403) and the sentence it returns explains
      // why — that it is the break-glass and the gate never applies to it. Shown verbatim.
      setError(err instanceof Error ? err.message : "Unable to suspend this entry");
    } finally {
      setBusyId(null);
    }
  }

  async function restore(entry: AccessRosterEntry) {
    const ok = await confirm({
      title: `Restore access for ${entry.fullName || entry.accountName || entry.email}?`,
      body: "They will be able to sign in again immediately.",
      note: entry.joinedAt
        ? `Their date of joining stays ${formatDate(entry.joinedAt)} — it is stamped once, the first time an address is admitted, and restoring never moves it.`
        : "They have never been admitted before, so today becomes their date of joining.",
      confirmLabel: "Restore",
      tone: "warning"
    });
    if (!ok) return;
    setBusyId(entry.id);
    setError(null);
    try {
      const updated = await updateAccessRosterEntry(entry.id, { status: "ACTIVE" });
      setNotice(`${updated.email} can sign in again.`);
      await reloadEverything();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to restore this entry");
    } finally {
      setBusyId(null);
    }
  }

  const header = (
    <PageHeader
      title="Access roster"
      description="Who may sign in to the repository at all. An address on this list with the status “May sign in” is admitted; everybody else is turned away and lands in the queue below."
      icon={<ShieldCheck className="h-5 w-5" aria-hidden />}
    />
  );

  /**
   * The route is gated twice over — `ROUTE_GUARDS` refuses it above this page and `require_admin`
   * refuses every request it would make — but the panel is still rendered here, because a
   * client-side guard that only hides a nav item is not a guard, and somebody arriving on a stale
   * bookmark deserves a sentence rather than an empty screen.
   */
  if (!permitted) {
    return (
      <>
        {header}
        <RestrictedPanel
          title="Admin access required"
          body={
            `The access roster decides who may sign in at all and holds the addresses of people who tried and were turned away, ` +
            // `roleLabel` answers "" for an absent user. AppShell never renders a protected page
            // without one, but a sentence reading "  does not open it" is a worse way to find that
            // out than a fallback nobody will ever see.
            `so it is admin work: ${roleLabel(user?.role) || "your tier"} does not open it, and the API refuses the same request for the same reason. ` +
            `An admin or the master admin can approve, refuse, suspend and restore addresses here.`
          }
        />
      </>
    );
  }

  const rows = data?.items ?? [];
  const queueRows = queue?.items ?? [];
  const queueTotal = queue?.total ?? 0;
  const queueHidden = Math.max(0, queueTotal - queueRows.length);

  return (
    <>
      {header}

      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>
      ) : null}
      {notice ? (
        <div className="mb-4 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700">{notice}</div>
      ) : null}

      {/* ── The queue. The notification, and the reason an admin opens this page. ─────────── */}
      <section className="panel mb-6 overflow-hidden">
        <div className="flex flex-wrap items-center justify-between gap-2 border-b border-line-200 px-4 py-3">
          <div>
            <h2 className="flex items-center gap-2 font-display font-bold text-ink-900">
              <Clock className="h-4 w-4 text-amber-800" aria-hidden />
              Waiting for a decision
              {/* The count comes from the queue's own `total`, not from the badge hook: this heading
                  must agree with the rows underneath it, and the badge is on a one-minute poll. */}
              {queue ? (
                <span className="rounded-full border border-amber-500/30 bg-amber-100 px-2 py-0.5 text-xs font-semibold text-amber-800">
                  {queueTotal}
                </span>
              ) : null}
            </h2>
            <p className="mt-1 text-sm leading-6 text-ink-500">
              Somebody proved they own one of these addresses and was turned away because it is not on the roster. They
              have been told they are waiting for an administrator, and nothing else happens until you decide.
            </p>
          </div>
        </div>

        {queue === null ? (
          // null is "still loading" and [] is "genuinely none". On this section in particular,
          // saying "nobody is waiting" during a fetch is the one wrong answer it can give.
          <div className="p-4 text-sm text-ink-700">Loading the queue…</div>
        ) : queueRows.length === 0 ? (
          <div className="p-4">
            <EmptyState
              title="Nobody is waiting"
              body="Every address that has asked for access has been decided. New requests appear here the moment somebody is turned away."
            />
          </div>
        ) : (
          <ul className="divide-y divide-line-200">
            {queueRows.map((entry) => (
              <PendingRow
                key={entry.id}
                entry={entry}
                roleOptions={roleOptions}
                busy={busyId === entry.id}
                onApprove={approve}
                onReject={reject}
              />
            ))}
          </ul>
        )}

        {queueHidden > 0 ? (
          // Truncation is stated, never silent. The whole queue is reachable — the table below
          // filters to the same status — so the sentence also says how to reach it.
          <p className="border-t border-line-200 bg-amber-100 px-4 py-2 text-xs leading-5 text-amber-800">
            Showing the {queueRows.length} most recent of {queueTotal} waiting requests. Set the status filter below to
            “Awaiting approval” to page through the rest.
          </p>
        ) : null}
      </section>

      {/* ── Pre-admit an address. No account has to exist. ───────────────────────────────── */}
      <form onSubmit={submit} className="panel mb-5 grid gap-4 p-4">
        <div>
          <h2 className="font-display text-lg font-bold text-ink-900">Add an address to the roster</h2>
          <p className="mt-1 text-sm leading-6 text-ink-muted">
            The email address is the only thing needed, and no account has to exist yet — that is how somebody is
            admitted before they have ever opened the application. The account is created, at the tier chosen here, the
            first time they sign in with that address.
          </p>
        </div>
        <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-3">
          <Field label="Email address" required>
            <TextInput
              name="email"
              type="email"
              required
              maxLength={254}
              placeholder="person@institution.ac.in"
              // Lower-cased on the server before it is stored and before it is compared with the
              // address signing in, so capitals here are harmless — see `normalise_email`.
              autoComplete="off"
            />
          </Field>
          <Field label="Full name">
            <TextInput name="fullName" maxLength={200} placeholder="Your own note of who this is" />
          </Field>
          <RoleField
            name="grantedRole"
            options={roleOptions}
            label="Joins as"
            // "All the users by default join as the lowest rung unless promoted there itself" — the
            // promotion is this control, and it is bounded by the admin's own tier server-side.
            hint="Everybody joins at the lowest rung unless you promote them here."
          />
        </div>
        <Field label="Note">
          <TextArea
            name="notes"
            maxLength={2000}
            rows={2}
            placeholder="Who they are and why they were admitted — the record you will want in a year."
          />
        </Field>
        <div className="flex flex-wrap gap-2">
          <button className="field-button" disabled={saving}>
            <MailPlus className="h-4 w-4" aria-hidden />
            {saving ? "Saving…" : "Add to roster"}
          </button>
        </div>
      </form>

      {/* ── The roster itself. ───────────────────────────────────────────────────────────── */}
      <div className="mb-4 grid gap-2 sm:grid-cols-[1fr_18rem]">
        <SearchInput
          value={query}
          onChange={setQuery}
          onSubmit={() => {
            setApplied(query);
            setPage(1);
          }}
          placeholder="Search by email or name"
        />
        <Dropdown
          value={statusFilter}
          onChange={(next) => {
            setStatusFilter(next);
            setPage(1);
          }}
          options={STATUS_OPTIONS}
          ariaLabel="Filter by access status"
          // A dropdown that filters the screen it sits on must not advance focus on select: jumping
          // away from the control being adjusted is wrong when the control IS the adjustment.
          advanceOnSelect={false}
        />
      </div>

      <section className="panel overflow-hidden">
        {data === null ? (
          <div className="p-4 text-sm text-ink-700">Loading the roster…</div>
        ) : rows.length === 0 ? (
          <div className="p-4">
            <EmptyState
              title="No roster entries found"
              body={
                applied || statusFilter
                  ? "No entry matches this search. Clear it to see every address the roster knows about, refused and suspended ones included."
                  : "Nobody is on the roster yet. Add the first address above — every account that existed when the sign-in gate shipped was admitted automatically, so an empty list here is worth investigating."
              }
            />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[1040px] text-left text-sm">
              <thead className="bg-surface-50 text-xs uppercase text-ink-500">
                <tr>
                  <ResizableTh>Person</ResizableTh>
                  <ResizableTh>Access</ResizableTh>
                  <ResizableTh>Role</ResizableTh>
                  <ResizableTh>Joined</ResizableTh>
                  <ResizableTh>Requests</ResizableTh>
                  <ResizableTh className="text-right">Actions</ResizableTh>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-200">
                {rows.map((entry) => (
                  <tr key={entry.id}>
                    <td className="px-4 py-3">
                      <div className="font-medium text-ink-900">
                        {/* The name is whatever an admin typed, or the account's own if one exists —
                            there may be neither, and its absence is stated rather than left as an
                            empty cell. A name is NEVER taken from an unverified sign-in attempt. */}
                        {entry.fullName || entry.accountName || <span className="text-ink-500">Name not recorded</span>}
                      </div>
                      <div className="text-xs text-ink-500">{entry.email}</div>
                      {entry.notes ? (
                        <div className="mt-1 line-clamp-2 max-w-sm whitespace-pre-line text-xs text-ink-500">
                          {entry.notes}
                        </div>
                      ) : null}
                    </td>
                    <td className="px-4 py-3">
                      <StatusChip status={entry.status} />
                      <div className="mt-1 text-xs text-ink-500">
                        {invitationLabel(entry)}
                        {entry.firstSeenAt ? ` · ${formatDateTime(entry.firstSeenAt)}` : ""}
                      </div>
                    </td>
                    <td className="px-4 py-3">
                      <div className="text-ink-700">{roleLabel(entry.grantedRole)}</div>
                      {/* The two disagree the moment somebody is promoted through the users screen,
                          and an admin reading only the roster would be looking at a stale tier and
                          believing it current. So the live one is printed whenever it differs. */}
                      {entry.accountRole && entry.accountRole !== entry.grantedRole ? (
                        <div className="text-xs text-ink-500">Account is now {roleLabel(entry.accountRole)}</div>
                      ) : null}
                    </td>
                    <td className="px-4 py-3 text-ink-700">
                      {entry.joinedAt ? (
                        formatDate(entry.joinedAt)
                      ) : (
                        <span className="text-ink-500">Not admitted yet</span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-ink-700">
                      {requestLabel(entry)}
                      {entry.lastRequestedAt ? (
                        <span className="block text-xs text-ink-500">Last {formatDateTime(entry.lastRequestedAt)}</span>
                      ) : null}
                    </td>
                    <td className="px-4 py-3 text-right">
                      <RowActions>
                        {entry.userId ? (
                          <Link className={rowAction("neutral")} href="/users">
                            Manage account
                          </Link>
                        ) : null}
                        {entry.status === "ACTIVE" ? (
                          // Never labelled "Delete". The verb has to say what happens, and what
                          // happens is that the row stays.
                          <button
                            type="button"
                            className={rowAction("danger")}
                            disabled={busyId === entry.id}
                            onClick={() => suspend(entry)}
                          >
                            Suspend
                          </button>
                        ) : (
                          <button
                            type="button"
                            className={rowAction("positive")}
                            disabled={busyId === entry.id}
                            onClick={() => (entry.status === "PENDING" ? approve(entry, entry.grantedRole) : restore(entry))}
                          >
                            <RotateCcw className="h-3.5 w-3.5" aria-hidden />
                            {entry.status === "PENDING" ? "Approve" : "Restore"}
                          </button>
                        )}
                        {entry.status === "PENDING" ? (
                          <button
                            type="button"
                            className={rowAction("danger")}
                            disabled={busyId === entry.id}
                            onClick={() => reject(entry)}
                          >
                            Refuse
                          </button>
                        ) : null}
                      </RowActions>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {data ? <Pagination page={data.page} pages={data.pages} total={data.total} onPage={setPage} /> : null}
      </section>

      {/* The one fact about this screen that is not visible anywhere on it: the break-glass. */}
      <p className="mt-4 text-xs leading-5 text-ink-500">
        The master admin address is never gated and cannot be taken off this list. The roster is a table only an
        administrator can edit, so an administrator locked out by it would be an outage with no remedy inside the
        product — that one exemption is what makes the rest of this screen safe to use.
        {pendingCount !== null && pendingCount > 0 ? ` ${pendingCount} request${pendingCount === 1 ? "" : "s"} waiting.` : ""}
      </p>
    </>
  );
}

/**
 * One request in the queue, with the two decisions and the tier the approval hands out.
 *
 * The role picker sits on the ROW rather than in a dialog because the tier is part of the decision,
 * not a follow-up to it: "all the users by default join as the lowest rung unless promoted there
 * itself" means the promotion happens at the moment of approval, on this screen, or it means an
 * admin has to remember to go and do it on another one.
 */
function PendingRow({
  entry,
  roleOptions,
  busy,
  onApprove,
  onReject
}: {
  entry: AccessRosterEntry;
  roleOptions: { value: string; label: string }[];
  busy: boolean;
  onApprove: (entry: AccessRosterEntry, role: UserRole) => void;
  onReject: (entry: AccessRosterEntry) => void;
}) {
  const [role, setRole] = useState<string>(entry.grantedRole);

  return (
    <li className="flex flex-col gap-3 px-4 py-3 lg:flex-row lg:items-center lg:justify-between">
      <div className="min-w-0">
        {/* The ADDRESS is the heading, and it is the only thing about this person the roster
            stores. No display name, no picture, no "reason for joining" — the pending queue is the
            one screen in this product where a stranger can cause content to appear, and nothing
            they control is rendered here beyond the address they proved they own. */}
        <div className="truncate font-medium text-ink-900">{entry.email}</div>
        <div className="mt-0.5 text-xs text-ink-500">
          {requestLabel(entry)}
          {entry.firstRequestedAt ? ` · first asked ${formatDateTime(entry.firstRequestedAt)}` : ""}
          {entry.lastRequestedAt ? ` · last ${formatDateTime(entry.lastRequestedAt)}` : ""}
        </div>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <div className="w-full min-w-0 sm:w-52">
          <Dropdown
            value={role}
            onChange={setRole}
            options={roleOptions}
            ariaLabel={`Tier to admit ${entry.email} at`}
            advanceOnSelect={false}
          />
        </div>
        <button
          type="button"
          className={rowAction("positive")}
          disabled={busy}
          onClick={() => onApprove(entry, role as UserRole)}
        >
          <BadgeCheck className="h-3.5 w-3.5" aria-hidden />
          {busy ? "Working…" : "Approve"}
        </button>
        <button type="button" className={rowAction("danger")} disabled={busy} onClick={() => onReject(entry)}>
          Refuse
        </button>
      </div>
    </li>
  );
}

/**
 * The status, as a chip.
 *
 * Colour never carries the meaning on its own — each state is WORDED — so the judgement survives
 * colour-blindness, a greyscale print-out and forced-colours mode.
 */
function StatusChip({ status }: { status: AccessStatus }) {
  return (
    <span
      className={`inline-flex w-fit items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-medium ${accessStatusChip(status)}`}
    >
      {status === "ACTIVE" ? <BadgeCheck className="h-3.5 w-3.5" aria-hidden /> : null}
      {accessStatusLabel(status)}
    </span>
  );
}

/**
 * A tier picker that submits through the form.
 *
 * `FieldBlock` rather than `Field`, and a hidden mirror rather than a bound `<select>`: `Field` is a
 * `<label>`, and a stray click inside a `<label>` is forwarded to the first labelable control in
 * it — which slams a dropdown shut the moment it is opened. The mirror is how an uncontrolled
 * FormData submit sees a themed control at all.
 */
function RoleField({
  name,
  options,
  label,
  hint
}: {
  name: string;
  options: { value: string; label: string }[];
  label: string;
  hint: string;
}) {
  const [value, setValue] = useState("");
  return (
    <div className="grid min-w-0 gap-1">
      <span className="field-label">{label}</span>
      <input type="hidden" name={name} value={value} />
      <Dropdown
        value={value}
        onChange={setValue}
        options={options}
        placeholder="Lowest rung (default)"
        ariaLabel={label}
      />
      <span className="text-xs text-ink-500">{hint}</span>
    </div>
  );
}
