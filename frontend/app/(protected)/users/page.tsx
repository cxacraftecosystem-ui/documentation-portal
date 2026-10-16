"use client";

import { useEffect, useRef, useState } from "react";
import { ShieldCheck } from "lucide-react";

import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { ResizableTh } from "@/components/ResizableTh";
import { RowActions, rowAction } from "@/components/RowActions";
import { SearchInput } from "@/components/SearchInput";
import { adminChromeVisible, useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { apiFetch, listResource } from "@/lib/api";
import { resetTwoFactorForUser } from "@/lib/twoFactor";
import { fetchNotificationPreferences } from "@/lib/notifications";
import {
  issuePasswordLink,
  revokePasswordLink,
  signOutEverywhere,
  type IssuedPasswordLink,
  type PasswordLinkDelivery
} from "@/lib/signIn";
import {
  assignableRoles,
  canManageUser,
  hasRank,
  isAdmin,
  isMasterAdmin,
  roleLabel,
  roleRank
} from "@/lib/permissions";
import type { PageResult, User } from "@/lib/types";

import { RequirePasswordChangeDialog, TemporaryPasswordDialog } from "./AccountDialogs";
import { AddPersonForm } from "./AddPersonForm";
import { rowOffers, signInSummary } from "./accountAdmin";

function GrantCell({
  included,
  editable,
  label,
  onChange
}: {
  included: boolean;
  editable: boolean;
  label: string;
  onChange: (value: boolean) => void;
}) {
  return (
    <td className="px-4 py-3 text-ink-700">
      <label className="inline-flex items-center gap-2">
        <input type="checkbox" checked={included} disabled={!editable} aria-label={label} onChange={(event) => onChange(event.target.checked)} />
        <span>{included ? "Yes" : "No"}</span>
      </label>
    </td>
  );
}

export default function UsersPage() {
  const confirm = useConfirm();
  const { user: currentUser } = useAuth();
  const { adminMode } = useAdminView();
  /**
   * Professors reach this page too, with promotion rights only: the role column is editable for
   * users beneath them, while creation, deletion, capability grants and the bulk panel stay admin+.
   *
   * `adminChromeVisible` is the admin-view half. For an ADMIN with admin view off AppShell already
   * replaces the whole route (the nav link is hidden, so the page would otherwise be reachable only
   * by URL) — this is the second line, keeping the admin-only controls tied to the toggle wherever
   * the page renders. It cannot narrow a PROFESSOR: they are not `isAdmin`, so `adminChromeVisible`
   * is true for them and nothing here changes, which matters because they have no toggle to undo.
   */
  const chrome = adminChromeVisible(currentUser, adminMode);
  const admin = isAdmin(currentUser) && chrome;
  const masterControls = isMasterAdmin(currentUser) && chrome;
  const [data, setData] = useState<PageResult<User> | null>(null);
  const [query, setQuery] = useState("");
  const [applied, setApplied] = useState("");
  const [page, setPage] = useState(1);
  const [error, setError] = useState<string | null>(null);
  const skipFirstDebounce = useRef(true);
  // The master-admin bulk panel picks from EVERY user, not just the page of the table above it.
  const [allUsers, setAllUsers] = useState<User[]>([]);
  const [adminSelection, setAdminSelection] = useState<Set<string>>(new Set());
  const [grantAdmin, setGrantAdmin] = useState(true);
  const [grantQuestionnaire, setGrantQuestionnaire] = useState(false);
  const [grantDataset, setGrantDataset] = useState(false);
  const [applying, setApplying] = useState(false);
  const [adminMessage, setAdminMessage] = useState<string | null>(null);
  /** "Help them back in": whether e-mail can carry a link, the link just made, and the two dialogs. */
  const [mailAvailable, setMailAvailable] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [issuedLink, setIssuedLink] = useState<{ user: User; link: IssuedPasswordLink } | null>(null);
  const [linkCopied, setLinkCopied] = useState(false);
  const [linkBusy, setLinkBusy] = useState(false);
  const [temporary, setTemporary] = useState<{ open: boolean; user: User | null }>({ open: false, user: null });
  const [requireChange, setRequireChange] = useState<{ open: boolean; user: User | null }>({ open: false, user: null });

  useEffect(() => {
    let live = true;
    void fetchNotificationPreferences().then((answer) => {
      if (live) setMailAvailable(answer.available);
    });
    return () => {
      live = false;
    };
  }, []);

  /**
   * Make a password link for somebody — copied and handed over (by phone, WhatsApp, in person), or
   * e-mailed to their own address. The link exists only in this answer: no route reads one back.
   */
  async function issueLink(user: User, delivery: PasswordLinkDelivery) {
    setLinkBusy(true);
    setError(null);
    setNotice(null);
    setLinkCopied(false);
    try {
      const link = await issuePasswordLink(user.id, delivery);
      setIssuedLink({ user, link });
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to make a password link");
    } finally {
      setLinkBusy(false);
    }
  }

  async function withdrawLink() {
    if (!issuedLink) return;
    setLinkBusy(true);
    try {
      await revokePasswordLink(issuedLink.link.id);
      setIssuedLink(null);
      setNotice("The link was withdrawn. It no longer works.");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to withdraw the link");
    } finally {
      setLinkBusy(false);
    }
  }

  async function copyLink() {
    if (!issuedLink?.link.link) return;
    try {
      await navigator.clipboard.writeText(issuedLink.link.link);
      setLinkCopied(true);
    } catch {
      // Clipboard refused: say nothing rather than claim success — the link is on screen to select.
      setLinkCopied(false);
    }
  }

  async function endSessions(user: User) {
    const ok = await confirm({
      title: "Sign them out of every device?",
      body: (
        <>
          Every session <span className="font-medium text-ink-900">{user.name || user.email}</span> has open, on the
          web and on the phone, ends now. Their password does not change; they sign in again as usual.
        </>
      ),
      tone: "danger",
      confirmLabel: "Sign out everywhere"
    });
    if (!ok) return;
    try {
      await signOutEverywhere(user.id);
      setError(null);
      setNotice(`${user.name || user.email} is signed out of every device.`);
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to sign them out");
    }
  }

  function toggleAdminSelection(id: string) {
    setAdminSelection((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  async function applyAdminGrants() {
    if (adminSelection.size === 0) return;
    setApplying(true);
    setAdminMessage(null);
    setError(null);
    try {
      // Additive grants only: checked privileges are granted, unchecked ones are left untouched
      // (revoke individually via the per-user table above). This keeps the bulk action safe.
      const body: Record<string, unknown> = {};
      if (grantAdmin) body.role = "ADMIN";
      if (grantQuestionnaire) body.canManageQuestionnaire = true;
      if (grantDataset) body.canDownloadDataset = true;
      if (Object.keys(body).length === 0) {
        setAdminMessage("Pick at least one of: admin access, questionnaire or dataset download to grant.");
        setApplying(false);
        return;
      }
      const ids = Array.from(adminSelection);
      for (const id of ids) {
        await apiFetch(`/users/${id}`, { method: "PATCH", body: JSON.stringify(body) });
      }
      setAdminMessage(`Updated ${ids.length} user${ids.length === 1 ? "" : "s"}.`);
      setAdminSelection(new Set());
      load();
      loadAllUsers();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to update admin access");
    } finally {
      setApplying(false);
    }
  }

  async function load() {
    try {
      setData(await listResource<User>("/users", { search: applied || undefined, page, pageSize: 20 }));
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load users");
    }
  }

  /** Full roster for the bulk grants panel — the paged table alone would hide most accounts. */
  async function loadAllUsers() {
    if (!masterControls) return;
    try {
      const result = await listResource<User>("/users", { pageSize: 100 });
      setAllUsers(result.items);
    } catch {
      // The bulk panel degrades to whatever is already loaded; the table above still works.
    }
  }

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page, applied]);

  useEffect(() => {
    loadAllUsers();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentUser?.id, masterControls]);

  // Live search: debounce typing by 350ms; Enter applies immediately via onSubmit.
  useEffect(() => {
    if (skipFirstDebounce.current) {
      skipFirstDebounce.current = false;
      return;
    }
    const timer = setTimeout(() => {
      setApplied(query);
      setPage(1);
    }, 350);
    return () => clearTimeout(timer);
  }, [query]);

  /** "Open their account" after a duplicate: bring that row up in the table, where its role and access are. */
  function openExisting(_accountId: string, email: string) {
    setQuery(email);
    setApplied(email);
    setPage(1);
    window.requestAnimationFrame(() => document.getElementById("users-table")?.scrollIntoView({ block: "start" }));
  }

  async function updateRole(user: User, role: string) {
    // UX audit DC-05: the role picker writes the moment it changes, so a DEMOTION asks first — a
    // slip of the arrow key must not take somebody's access away. A promotion still writes at once.
    if (roleRank(role as User["role"]) < roleRank(user.role)) {
      const ok = await confirm({
        title: `Lower ${user.name}'s role to ${roleLabel(role)}?`,
        body: `They lose everything ${roleLabel(user.role)} could do that ${roleLabel(role)} cannot.`,
        confirmLabel: "Lower role",
        tone: "warning"
      });
      if (!ok) return;
    }
    try {
      await apiFetch(`/users/${user.id}`, { method: "PATCH", body: JSON.stringify({ role }) });
      setError(null);
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to change role");
    }
  }

  async function updateGrant(
    user: User,
    field: "canManageQuestionnaire" | "canDownloadDataset",
    value: boolean
  ) {
    try {
      await apiFetch(`/users/${user.id}`, { method: "PATCH", body: JSON.stringify({ [field]: value }) });
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to update access");
    }
  }

  async function remove(user: User) {
    const ok = await confirm(
      deleteConfirm(
        "Delete this account?",
        <>
          <span className="font-medium text-ink-900">{user.name || user.email}</span> loses access immediately and
          cannot sign in again. This action cannot be undone.
        </>,
        "Their records are kept and still credited to them."
      )
    );
    if (!ok) return;
    try {
      await apiFetch(`/users/${user.id}`, { method: "DELETE" });
      setError(null);
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to delete user");
    }
  }

  /**
   * For somebody who has lost the phone their authenticator was on. The server holds the same rule as
   * Delete (an admin, over a person ranked below them) and never hands the secret back — only takes
   * it away, with every recovery code beside it.
   */
  async function turnOffTwoStep(user: User) {
    const ok = await confirm({
      title: "Turn off two-step verification?",
      body: (
        <>
          <span className="font-medium text-ink-900">{user.name || user.email}</span> will sign in with only their
          password or Google, Microsoft or Yahoo until they turn it on again. Their recovery codes stop working.
        </>
      ),
      tone: "danger",
      confirmLabel: "Turn off"
    });
    if (!ok) return;
    try {
      await resetTwoFactorForUser(user.id);
      setError(null);
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to turn off two-step verification");
    }
  }

  return (
    <>
      <PageHeader
        title="Users"
        description="User management for professors and administrators — promotions for professors, full account control for admins."
        icon={<ShieldCheck className="h-5 w-5" aria-hidden />}
      />
      {error ? (
        <div role="alert" className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </div>
      ) : null}
      {/* Mounted from the first render, so a notice that appears later is announced. */}
      <div aria-live="polite">
        {notice ? (
          <div className="mb-4 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700">{notice}</div>
        ) : null}
      </div>
      {issuedLink ? (
        <IssuedLinkPanel
          issued={issuedLink}
          copied={linkCopied}
          busy={linkBusy}
          onCopy={copyLink}
          onWithdraw={withdrawLink}
          onDone={() => setIssuedLink(null)}
        />
      ) : null}
      {admin ? (
        <AddPersonForm
          viewer={currentUser}
          mailAvailable={mailAvailable}
          onAdded={() => {
            setError(null);
            load();
          }}
          onOpenExisting={openExisting}
        />
      ) : null}
      <p className="mb-4 text-xs text-ink-muted">
        Six tiers: Master Admin → Admin → Professor → Researcher → Field Contributor → Crowdsource
        Volunteer. You can promote users to your own tier and below, and manage only users beneath
        your tier. Professors hold promotion rights only: they may change the role of anyone ranked
        beneath them (up to Professor) but cannot create or delete accounts or grant capabilities —
        those remain admin actions. Professors and above hold every capability implicitly; the
        checkboxes lift a single capability for a lower tier.
      </p>
      <p className="mb-4 text-xs text-ink-muted">
        Two things are decided by RANK alone and have no checkbox: adding or editing crafts and
        workshops (Professor and above; deleting either is admin-only), and creating artisans,
        products, tools, processes and interviews (Researcher and above). Field contributors and
        volunteers keep answering existing interviews, uploading media and commenting. To give
        someone one of these, promote them.
      </p>
      <div className="mb-4">
        <SearchInput
          value={query}
          onChange={setQuery}
          onSubmit={() => {
            setApplied(query);
            setPage(1);
          }}
          placeholder="Search users by name or email"
        />
      </div>
      <section id="users-table" className="panel scroll-mt-28 overflow-hidden">
        {!data ? (
          <div className="p-4 text-sm text-ink-700">Loading...</div>
        ) : data.items.length === 0 ? (
          <div className="p-4">
            <EmptyState title="No users found" />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[1000px] text-left text-sm">
              <thead className="bg-surface-50 text-xs uppercase text-ink-500">
                <tr>
                  <ResizableTh>Name</ResizableTh>
                  <ResizableTh>Email</ResizableTh>
                  <ResizableTh>Role</ResizableTh>
                  <ResizableTh>Questionnaire</ResizableTh>
                  <ResizableTh>Dataset</ResizableTh>
                  <ResizableTh>Sign-in</ResizableTh>
                  <ResizableTh className="text-right">Actions</ResizableTh>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-200">
                {data.items.map((user) => (
                  <tr key={user.id}>
                    <td className="px-4 py-3 font-medium text-ink-900">
                      {user.name}
                      {user.twoFactorEnabled ? (
                        <span className="ml-2 inline-flex items-center rounded-full border border-line-200 bg-surface-50 px-2 py-0.5 text-[11px] font-semibold text-ink-700">
                          Two-step on
                        </span>
                      ) : null}
                    </td>
                    <td className="px-4 py-3 text-ink-700">{user.email}</td>
                    <td className="px-4 py-3">
                      {canManageUser(currentUser, user) && user.role !== "MASTER_ADMIN" ? (
                        <select
                          className="field-input max-w-44"
                          aria-label={`Role for ${user.name}`}
                          value={user.role}
                          onChange={(event) => updateRole(user, event.target.value)}>
                          {assignableRoles(currentUser)
                            .filter((role) => role !== "MASTER_ADMIN" || isMasterAdmin(currentUser))
                            .map((role) => (
                              <option key={role} value={role}>
                                {roleLabel(role)}
                              </option>
                            ))}
                        </select>
                      ) : (
                        <span className="text-sm text-ink-700">{roleLabel(user.role)}</span>
                      )}
                    </td>
                    <GrantCell
                      included={hasRank(user, "PROFESSOR") || !!user.canManageQuestionnaire}
                      editable={admin && canManageUser(currentUser, user) && !hasRank(user, "PROFESSOR")}
                      label={`Allow ${user.email} to manage questionnaire`}
                      onChange={(value) => updateGrant(user, "canManageQuestionnaire", value)}
                    />
                    {/* No Crafts or Workshops columns: both are rank-only now, so the cell could
                        only ever have restated the role beside it or offered a toggle that changed
                        nothing. Read the Role column for those two. */}
                    <GrantCell
                      included={hasRank(user, "PROFESSOR") || !!user.canDownloadDataset}
                      editable={admin && canManageUser(currentUser, user) && !hasRank(user, "PROFESSOR")}
                      label={`Allow ${user.email} to download the entire dataset`}
                      onChange={(value) => updateGrant(user, "canDownloadDataset", value)}
                    />
                    <td className="px-4 py-3 text-ink-700">{signInSummary(user)}</td>
                    <td className="px-4 py-3 text-right">
                      {user.role === "MASTER_ADMIN" ? (
                        <span className="text-xs font-semibold text-amber-700">Protected</span>
                      ) : admin && canManageUser(currentUser, user) ? (
                        <RowActions>
                          <HelpThemBackIn
                            offers={rowOffers(currentUser, user, { adminView: admin, mailAvailable })}
                            busy={linkBusy}
                            onEmailLink={() => void issueLink(user, "EMAIL")}
                            onCopyLink={() => void issueLink(user, "COPY_LINK")}
                            onTemporaryPassword={() => setTemporary({ open: true, user })}
                            onRequireChange={() => setRequireChange({ open: true, user })}
                            onSignOutEverywhere={() => void endSessions(user)}
                            onTurnOffTwoStep={() => void turnOffTwoStep(user)}
                          />
                          <button className={rowAction("danger")} onClick={() => remove(user)}>
                            Delete
                          </button>
                        </RowActions>
                      ) : canManageUser(currentUser, user) ? (
                        <span className="text-xs text-ink-500">Role only</span>
                      ) : (
                        <span className="text-xs text-ink-500">Peer tier</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {data ? <Pagination page={data.page} pages={data.pages} total={data.total} onPage={setPage} /> : null}
      </section>
      <TemporaryPasswordDialog
        open={temporary.open}
        target={temporary.user}
        onClose={() => setTemporary((state) => ({ ...state, open: false }))}
        onSaved={(updated, required) => {
          setTemporary((state) => ({ ...state, open: false }));
          setError(null);
          setNotice(
            `${updated.name || updated.email} has a new password and is signed out everywhere.${
              required ? " They choose their own at their next sign-in." : ""
            } Send it to them yourself.`
          );
          load();
        }}
      />
      <RequirePasswordChangeDialog
        open={requireChange.open}
        target={requireChange.user}
        onClose={() => setRequireChange((state) => ({ ...state, open: false }))}
        onSaved={(updated, withLink) => {
          setRequireChange((state) => ({ ...state, open: false }));
          setError(null);
          setNotice(`${updated.name || updated.email} must choose a new password at their next sign-in.`);
          if (withLink) void issueLink(updated, "COPY_LINK");
          load();
        }}
      />
      {masterControls ? (
        <section className="panel mt-6 p-4">
          <h2 className="font-display font-bold text-xl text-ink">Admin management</h2>
          <p className="mt-1 text-sm text-ink-muted">
            Select one or more existing users, then grant them administrator access and decide their individual privileges in one action. Privileges are additive here — revoke individually in the table above.
          </p>
          {adminMessage ? <div className="mt-3 rounded-md border border-emerald-200 bg-emerald-50 px-3 py-2 text-sm text-emerald-700">{adminMessage}</div> : null}
          <div className="mt-4 grid gap-4 lg:grid-cols-[1.4fr_1fr]">
            <div className="rounded-md border border-line-200 bg-field-50 p-3">
              <div className="mb-2 text-xs font-semibold uppercase tracking-wide text-ink-500">Select users ({adminSelection.size} selected)</div>
              <div className="grid max-h-72 gap-1 overflow-y-auto">
                {allUsers
                  .filter((user) => user.role !== "MASTER_ADMIN")
                  .map((user) => (
                    <label key={user.id} className="flex items-center gap-2 rounded px-2 py-1 hover:bg-field-100">
                      <input type="checkbox" checked={adminSelection.has(user.id)} onChange={() => toggleAdminSelection(user.id)} />
                      <span className="min-w-0 flex-1 truncate text-sm text-ink">
                        {user.name} <span className="text-ink-muted">· {user.email}</span>
                      </span>
                      <span className="rounded-full bg-field-200 px-2 py-0.5 text-xs text-ink-muted">{roleLabel(user.role)}</span>
                    </label>
                  ))}
                {allUsers.filter((user) => user.role !== "MASTER_ADMIN").length === 0 ? (
                  <p className="px-2 py-1 text-sm text-ink-muted">No eligible users.</p>
                ) : null}
              </div>
            </div>
            <div className="grid content-start gap-2 rounded-md border border-line-200 bg-field-50 p-3">
              <div className="mb-1 text-xs font-semibold uppercase tracking-wide text-ink-500">Grant</div>
              <label className="flex items-center gap-2 text-sm text-ink">
                <input type="checkbox" checked={grantAdmin} onChange={(event) => setGrantAdmin(event.target.checked)} />
                Administrator access (role = ADMIN)
              </label>
              <label className="flex items-center gap-2 text-sm text-ink">
                <input type="checkbox" checked={grantQuestionnaire} onChange={(event) => setGrantQuestionnaire(event.target.checked)} />
                Manage questionnaire
              </label>
              <label className="flex items-center gap-2 text-sm text-ink">
                <input type="checkbox" checked={grantDataset} onChange={(event) => setGrantDataset(event.target.checked)} />
                Download dataset
              </label>
              <button
                type="button"
                className="field-button mt-2"
                disabled={applying || adminSelection.size === 0}
                onClick={applyAdminGrants}
              >
                {applying ? "Applying..." : `Apply to ${adminSelection.size} user${adminSelection.size === 1 ? "" : "s"}`}
              </button>
            </div>
          </div>
        </section>
      ) : null}
    </>
  );
}

/**
 * "HELP THEM BACK IN" — every way an administrator can get somebody into their account again, on
 * the row of an account they manage. Only the offers `rowOffers` makes are drawn.
 */
function HelpThemBackIn({
  offers,
  busy,
  onEmailLink,
  onCopyLink,
  onTemporaryPassword,
  onRequireChange,
  onSignOutEverywhere,
  onTurnOffTwoStep
}: {
  offers: ReturnType<typeof rowOffers>;
  busy: boolean;
  onEmailLink: () => void;
  onCopyLink: () => void;
  onTemporaryPassword: () => void;
  onRequireChange: () => void;
  onSignOutEverywhere: () => void;
  onTurnOffTwoStep: () => void;
}) {
  return (
    <>
      {offers.emailLink ? (
        <button className={rowAction("neutral")} disabled={busy} onClick={onEmailLink}>
          Email them a password link
        </button>
      ) : null}
      {offers.copyLink ? (
        <button className={rowAction("neutral")} disabled={busy} onClick={onCopyLink}>
          Make a password link
        </button>
      ) : null}
      {offers.temporaryPassword ? (
        <button className={rowAction("neutral")} onClick={onTemporaryPassword}>
          Set a temporary password
        </button>
      ) : null}
      {offers.requireChange ? (
        <button className={rowAction("neutral")} onClick={onRequireChange}>
          Require a new password
        </button>
      ) : null}
      {offers.signOutEverywhere ? (
        <button className={rowAction("danger")} onClick={onSignOutEverywhere}>
          Sign out of every device
        </button>
      ) : null}
      {offers.turnOffTwoStep ? (
        <button className={rowAction("danger")} onClick={onTurnOffTwoStep}>
          Turn off two-step verification
        </button>
      ) : null}
    </>
  );
}

/**
 * THE LINK JUST MADE, above the table and not inside the row that made it: this is the one panel
 * whose content cannot be recovered — no route reads a link back. An e-mailed link is not shown at
 * all; the administrator is told where it went and may withdraw it.
 */
function IssuedLinkPanel({
  issued,
  copied,
  busy,
  onCopy,
  onWithdraw,
  onDone
}: {
  issued: { user: User; link: IssuedPasswordLink };
  copied: boolean;
  busy: boolean;
  onCopy: () => void;
  onWithdraw: () => void;
  onDone: () => void;
}) {
  const kind = issued.link.purpose === "INVITE" ? "Invitation link" : "Password reset link";
  const expires = new Date(issued.link.expiresAt).toLocaleString();
  const emailed = issued.link.deliveredBy === "EMAIL";
  return (
    <div className="mb-4 grid gap-2 rounded-md border border-line-200 bg-field-50 px-3 py-3">
      <p className="text-sm font-medium text-ink-900">
        {kind} {emailed ? "e-mailed to" : "for"} {issued.user.name} · {issued.user.email}
      </p>
      {emailed ? null : (
        <div className="flex flex-wrap items-center gap-2">
          <input
            readOnly
            value={issued.link.link ?? ""}
            aria-label="Password link"
            onFocus={(event) => event.currentTarget.select()}
            className="field-input min-w-0 flex-1 font-mono text-xs"
          />
          <button type="button" className="field-button-secondary" onClick={onCopy}>
            {copied ? "Copied" : "Copy"}
          </button>
        </div>
      )}
      <p className="text-xs leading-5 text-ink-500">
        It works once and expires {expires}.{" "}
        {emailed
          ? "Withdraw it if it went to the wrong address."
          : "Send it to them yourself — by phone, WhatsApp or in person. It is shown only here, once. Setting a password with it signs them out everywhere; if two-step verification is on, they still need their code to sign in."}
      </p>
      <div className="flex flex-wrap items-center gap-2">
        <button type="button" className="field-button-secondary" disabled={busy} onClick={onWithdraw}>
          Withdraw
        </button>
        <button type="button" className="field-button-secondary" onClick={onDone}>
          Done
        </button>
      </div>
    </div>
  );
}
