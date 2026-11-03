"use client";

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
  promotionHeldSentence,
  requestLabel,
  suspendAccessRosterEntry,
  updateAccessRosterEntry,
  type AccessRosterEntry,
  type AccessStatus,
  rosterCreateBody,
  type RosterSignIn
} from "@/lib/accessRoster";
import { MAX_PASSWORD_LENGTH, MIN_PASSWORD_LENGTH, passwordRuleLine } from "@/lib/signIn";
import { formatDate, formatDateTime } from "@/lib/format";
import { fetchNotificationPreferences } from "@/lib/notifications";
import { assignableRoles, canManageAccessRoster, roleLabel } from "@/lib/permissions";
import type { PageResult, UserRole } from "@/lib/types";
import { Toggle } from "@/components/ui/Toggle";
import { ButtonRow } from "@/components/ui/ButtonRow";

const NEW_ACCOUNTS_ONLY = "New accounts only. To change an existing account, use Manage users.";

const PAGE_SIZE = 20;

const PENDING_PAGE_SIZE = 25;

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
  const [mailAvailable, setMailAvailable] = useState(false);
  const [inviteOnApprove, setInviteOnApprove] = useState(true);
  const [signIn, setSignIn] = useState<RosterSignIn>("PROVIDER");
  const [addRequireChange, setAddRequireChange] = useState(true);

  useEffect(() => {
    let live = true;
    void fetchNotificationPreferences().then((answer) => {
      if (live) setMailAvailable(answer.available);
    });
    return () => {
      live = false;
    };
  }, []);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [heldRole, setHeldRole] = useState<string | null>(null);
  const skipFirstDebounce = useRef(true);

  const listGeneration = useRef(0);
  const queueGeneration = useRef(0);

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
    }
  }, [permitted]);

  useEffect(() => {
    loadList();
  }, [loadList]);

  useEffect(() => {
    loadQueue();
  }, [loadQueue]);

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

  const reloadEverything = useCallback(async () => {
    await Promise.all([loadList(), loadQueue()]);
    refreshPendingCount();
  }, [loadList, loadQueue, refreshPendingCount]);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    const email = String(form.get("email") ?? "").trim();
    const fullName = String(form.get("fullName") ?? "").trim();
    const notes = String(form.get("notes") ?? "").trim();
    const grantedRole = String(form.get("grantedRole") ?? "").trim();
    const sendInvite = mailAvailable && form.get("sendInvite") === "on";
    const password = typeof form.get("password") === "string" ? String(form.get("password")) : "";
    if (!email) return;
    if (signIn === "PASSWORD" && !fullName) {
      setError("Enter their full name.");
      return;
    }

    setSaving(true);
    setError(null);
    setNotice(null);
    try {
      const created = await addToAccessRoster(
        rosterCreateBody({
          email,
          fullName,
          notes,
          grantedRole,
          sendInvite,
          signIn,
          password,
          mustChangePassword: addRequireChange
        })
      );
      const held = signIn === "PASSWORD" ? null : promotionHeldSentence(created);
      const sent = sendInvite ? " Invitation sent." : "";
      const message =
        signIn === "PASSWORD"
          ? `${created.email} can sign in as ${roleLabel(created.grantedRole)}${
              addRequireChange ? " and will choose a new password" : ""
            }. Send them the password.`
          : held
            ? `${held}${sent}`
            : `${created.email} added as ${roleLabel(created.grantedRole)}.${sent}`;
      setHeldRole(held ? message : null);
      setNotice(message);
      formElement.reset();
      setSignIn("PROVIDER");
      setAddRequireChange(true);
      await reloadEverything();
    } catch (err) {
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

  async function approve(entry: AccessRosterEntry, grantedRole: UserRole) {
    setBusyId(entry.id);
    setError(null);
    try {
      const invite = mailAvailable && inviteOnApprove;
      const updated = await updateAccessRosterEntry(entry.id, {
        status: "ACTIVE",
        grantedRole,
        ...(invite ? { sendInvite: true } : {})
      });
      const held = promotionHeldSentence(updated);
      const sent = invite ? " Invitation sent." : "";
      const message = held ? `${held}${sent}` : `${updated.email} approved as ${roleLabel(updated.grantedRole)}.${sent}`;
      setHeldRole(held ? message : null);
      setNotice(message);
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
      body: "They’ll be told to contact an administrator.",
      note:
        "They won’t return to this queue by signing in again.",
      confirmLabel: "Refuse access",
      tone: "danger"
    });
    if (!ok) return;
    setBusyId(entry.id);
    setError(null);
    try {
      const updated = await updateAccessRosterEntry(entry.id, { status: "REJECTED" });
      setNotice(`${updated.email} refused.`);
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
      body: "They’re signed out and can’t sign in until restored.",
      note:
        "Their records are kept. Restore any time.",
      confirmLabel: "Suspend",
      tone: "danger"
    });
    if (!ok) return;
    setBusyId(entry.id);
    setError(null);
    try {
      const updated = await suspendAccessRosterEntry(entry.id);
      setNotice(
        `${updated.email} suspended${updated.decidedAt ? ` on ${formatDate(updated.decidedAt)}` : ""}.`
      );
      await reloadEverything();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to suspend this entry");
    } finally {
      setBusyId(null);
    }
  }

  async function restore(entry: AccessRosterEntry) {
    const ok = await confirm({
      title: `Restore access for ${entry.fullName || entry.accountName || entry.email}?`,
      body: "They can sign in again straight away.",
      note: entry.joinedAt
        ? `Joined ${formatDate(entry.joinedAt)}.`
        : "Today becomes their joining date.",
      confirmLabel: "Restore",
      tone: "warning"
    });
    if (!ok) return;
    setBusyId(entry.id);
    setError(null);
    try {
      const updated = await updateAccessRosterEntry(entry.id, { status: "ACTIVE" });
      const held = promotionHeldSentence(updated);
      setHeldRole(held);
      setNotice(held ?? `${updated.email} can sign in again.`);
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
      description="Who may sign in."
      icon={<ShieldCheck className="h-5 w-5" aria-hidden />}
    />
  );

  if (!permitted) {
    return (
      <>
        {header}
        <RestrictedPanel
          title="Admin access required"
          body={
            "Only admins can manage the access roster."
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
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-800">{error}</div>
      ) : null}
      {notice ? (
        <div
          className={
            heldRole !== null && heldRole === notice
              ? "mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm text-amber-800"
              : "mb-4 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700"
          }
        >
          {notice}
        </div>
      ) : null}

      <section className="panel mb-6 overflow-hidden">
        <div className="flex flex-wrap items-center justify-between gap-2 border-b border-line-200 px-4 py-3">
          <div>
            <h2 className="flex items-center gap-2 font-display font-bold text-ink-900">
              <Clock className="h-4 w-4 text-amber-800 dark:text-amber-200" aria-hidden />
              Waiting for a decision
              {queue ? (
                <span className="rounded-full border border-amber-500/30 bg-amber-100 px-2 py-0.5 text-xs font-semibold text-amber-800">
                  {queueTotal}
                </span>
              ) : null}
            </h2>
            <p className="mt-1 text-sm leading-6 text-ink-500">
              People turned away at sign-in. The role is for new accounts only.
            </p>
          </div>
          {mailAvailable ? (
            <Toggle className="w-auto" label="E-mail each person I approve" checked={inviteOnApprove} onChange={setInviteOnApprove} />
          ) : null}
        </div>

        {queue === null ? (
          <div className="p-4 text-sm text-ink-700">Loading the queue…</div>
        ) : queueRows.length === 0 ? (
          <div className="p-4">
            <EmptyState
              title="Nobody is waiting"
              body="New requests appear here."
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
          <p className="border-t border-line-200 bg-amber-100 px-4 py-2 text-xs leading-5 text-amber-800">
            Showing {queueRows.length} of {queueTotal}. Filter by “Awaiting approval” to see all.
          </p>
        ) : null}
      </section>

      <form onSubmit={submit} className="panel mb-5 grid gap-4 p-4">
        <div>
          <h2 className="font-display text-lg font-bold text-ink-900">Add an address to the roster</h2>
          <p className="mt-1 text-sm leading-6 text-ink-muted">Their account is created when they first sign in.</p>
        </div>
        <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-3">
          <Field label="Email address" required>
            <TextInput
              name="email"
              type="email"
              required
              maxLength={254}
              placeholder="person@institution.ac.in"
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
            hint={`Defaults to the lowest role. ${NEW_ACCOUNTS_ONLY}`}
          />
        </div>
        <fieldset className="grid gap-2">
          <legend className="field-label">How will they sign in?</legend>
          <label className="flex items-start gap-2 text-sm text-ink-700">
            <input
              type="radio"
              name="signIn"
              value="PROVIDER"
              checked={signIn === "PROVIDER"}
              onChange={() => setSignIn("PROVIDER")}
              className="mt-1"
            />
            <span>Google, Microsoft or Yahoo</span>
          </label>
          <label className="flex items-start gap-2 text-sm text-ink-700">
            <input
              type="radio"
              name="signIn"
              value="PASSWORD"
              checked={signIn === "PASSWORD"}
              onChange={() => setSignIn("PASSWORD")}
              className="mt-1"
            />
            <span>A password you set now</span>
          </label>
          {signIn === "PASSWORD" ? (
            <div className="grid gap-2 rounded-md border border-line-200 bg-surface-50 p-3 md:max-w-md">
              <Field label="Password" required>
                <TextInput
                  name="password"
                  type="password"
                  required
                  minLength={MIN_PASSWORD_LENGTH}
                  maxLength={MAX_PASSWORD_LENGTH}
                  autoComplete="new-password"
                />
              </Field>
              <p className="text-xs leading-5 text-ink-500">
                {passwordRuleLine("It won’t be shown again.")}
              </p>
              <Toggle
                label="Ask them to choose a new password at first sign-in"
                checked={addRequireChange}
                onChange={setAddRequireChange}
              />
            </div>
          ) : null}
        </fieldset>
        <Field label="Note">
          <TextArea
            name="notes"
            maxLength={2000}
            rows={2}
            placeholder="Who they are and why"
          />
        </Field>
        {mailAvailable ? (
          <Toggle name="sendInvite" defaultChecked label="E-mail them that they can sign in" />
        ) : null}
        <ButtonRow>
          <button className="field-button" disabled={saving}>
            <MailPlus className="h-4 w-4" aria-hidden />
            {saving ? "Saving…" : "Add to roster"}
          </button>
        </ButtonRow>
      </form>

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
                  ? "No matches."
                  : "No one on the roster yet."
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

      <p className="mt-4 text-xs leading-5 text-ink-500">
        The master admin can always sign in and can’t be removed from this list.
        {pendingCount !== null && pendingCount > 0 ? ` ${pendingCount} request${pendingCount === 1 ? "" : "s"} waiting.` : ""}
      </p>
    </>
  );
}

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
