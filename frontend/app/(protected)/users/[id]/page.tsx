"use client";

/**
 * /users/[id] — one person's account, read-only, with an Update button for whoever may change it.
 *
 * Owner ruling, 2026-10-16: opening a record reads it; only Update edits it. Until then the users list
 * WAS the editor — a role picker and two grant checkboxes live in every row, so a stray arrow key on a
 * row changed somebody's access. The list now only reads, a click on a person lands here, and the role
 * and the capability grants change only after Update is pressed (here, or from the row's own Update,
 * which arrives with `?update=1`).
 *
 * Nothing on this page is wider than the list: `GET /users/{id}` is the list's gate and the list's row,
 * and the controls are the ones the list carried, under the same rules — the role for anybody the
 * viewer may manage (`mayUpdateUser`), the grants for an admin with admin view on, and never for a
 * Professor or above, who holds every capability by rank.
 */

import { Suspense, useCallback, useEffect, useState } from "react";
import { useParams, useSearchParams } from "next/navigation";
import { Pencil, UserRound } from "lucide-react";

import { adminChromeVisible, useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { useConfirm } from "@/components/dialogs/ConfirmDialog";
import { PageHeader } from "@/components/PageHeader";
import { apiFetch } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { assignableRoles, hasRank, isAdmin, isMasterAdmin, roleLabel, roleRank } from "@/lib/permissions";
import type { User } from "@/lib/types";

import { FIRST_SIGN_IN_UNRECORDED, firstSignInRecorded, mayUpdateUser, signInSummary } from "../accountAdmin";

export default function UserViewPage() {
  return (
    <Suspense fallback={<section className="panel p-6 text-sm text-ink-500">Loading…</section>}>
      <UserView />
    </Suspense>
  );
}

function UserView() {
  const params = useParams<{ id: string }>();
  const searchParams = useSearchParams();
  const id = params.id;
  const confirm = useConfirm();
  const { user: currentUser } = useAuth();
  const { adminMode } = useAdminView();
  const admin = isAdmin(currentUser) && adminChromeVisible(currentUser, adminMode);

  const [person, setPerson] = useState<User | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [updating, setUpdating] = useState(searchParams.get("update") === "1");
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    try {
      setPerson(await apiFetch<User>(`/users/${encodeURIComponent(id)}`));
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load this account.");
    }
  }, [id]);

  useEffect(() => {
    void load();
  }, [load]);

  const mayUpdate = person ? mayUpdateUser(currentUser, person) : false;
  // Professors and above hold both capabilities by rank, so there is nothing to switch for them.
  const mayGrant = Boolean(person) && admin && mayUpdate && !hasRank(person, "PROFESSOR");

  async function changeRole(role: string) {
    if (!person) return;
    // A DEMOTION asks first — a slip of the arrow key must not take somebody's access away.
    if (roleRank(role as User["role"]) < roleRank(person.role)) {
      const ok = await confirm({
        title: `Lower ${person.name}'s role to ${roleLabel(role)}?`,
        body: `They lose everything ${roleLabel(person.role)} could do that ${roleLabel(role)} cannot.`,
        confirmLabel: "Lower role",
        tone: "warning"
      });
      if (!ok) return;
    }
    await save({ role }, "Unable to change role");
  }

  async function save(body: Record<string, unknown>, failure: string) {
    if (!person) return;
    setSaving(true);
    try {
      await apiFetch(`/users/${encodeURIComponent(person.id)}`, { method: "PATCH", body: JSON.stringify(body) });
      setError(null);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : failure);
    } finally {
      setSaving(false);
    }
  }

  const questionnaire = person ? hasRank(person, "PROFESSOR") || !!person.canManageQuestionnaire : false;
  const dataset = person ? hasRank(person, "PROFESSOR") || !!person.canDownloadDataset : false;

  return (
    <>
      <PageHeader
        title={person?.name || person?.email || "Account"}
        description={person ? person.email : undefined}
        icon={<UserRound className="h-5 w-5" aria-hidden />}
        actions={
          mayUpdate ? (
            <button
              type="button"
              className={updating ? "field-button-secondary" : "field-button"}
              aria-pressed={updating}
              onClick={() => setUpdating((open) => !open)}
            >
              <Pencil className="h-4 w-4" aria-hidden />
              {updating ? "Done" : "Update"}
            </button>
          ) : undefined
        }
      />

      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>
      ) : null}

      {!person ? (
        error ? null : <section className="panel p-6 text-sm text-ink-500">Loading…</section>
      ) : (
        <section className="panel p-4">
          <dl className="grid gap-x-6 gap-y-4 sm:grid-cols-2">
            <Item label="Name">{person.name || "-"}</Item>
            <Item label="Email">{person.email}</Item>
            <Item label="Role">
              {updating && mayUpdate ? (
                <select
                  className="field-input max-w-56"
                  aria-label={`Role for ${person.name}`}
                  value={person.role}
                  disabled={saving}
                  onChange={(event) => void changeRole(event.target.value)}
                >
                  {assignableRoles(currentUser)
                    .filter((role) => role !== "MASTER_ADMIN" || isMasterAdmin(currentUser))
                    .map((role) => (
                      <option key={role} value={role}>
                        {roleLabel(role)}
                      </option>
                    ))}
                </select>
              ) : (
                roleLabel(person.role)
              )}
            </Item>
            <Item label="Sign-in">{signInSummary(person)}</Item>
            <Item label="Manage the questionnaire">
              <Grant
                on={questionnaire}
                editable={updating && mayGrant && !saving}
                label={`Allow ${person.email} to manage questionnaire`}
                onChange={(value) => void save({ canManageQuestionnaire: value }, "Unable to update access")}
              />
            </Item>
            <Item label="Download the entire dataset">
              <Grant
                on={dataset}
                editable={updating && mayGrant && !saving}
                label={`Allow ${person.email} to download the entire dataset`}
                onChange={(value) => void save({ canDownloadDataset: value }, "Unable to update access")}
              />
            </Item>
            <Item label="Two-step verification">
              {person.twoFactorEnabled
                ? `On${person.twoFactorEnabledAt ? ` since ${formatDateTime(person.twoFactorEnabledAt)}` : ""}`
                : "Off"}
            </Item>
            <Item label="First signed in">
              {!person.firstLoginAt
                ? "Not yet"
                : firstSignInRecorded(person)
                  ? formatDateTime(person.firstLoginAt)
                  : FIRST_SIGN_IN_UNRECORDED}
            </Item>
            {person.lastLoginAt ? <Item label="Last signed in">{formatDateTime(person.lastLoginAt)}</Item> : null}
          </dl>
          {updating && mayUpdate && !mayGrant ? (
            <p className="mt-4 text-xs text-ink-500">
              {hasRank(person, "PROFESSOR")
                ? "Professors and above hold both capabilities by rank."
                : "Only an admin can switch a capability on or off."}
            </p>
          ) : null}
        </section>
      )}
    </>
  );
}

function Item({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="grid min-w-0 gap-1">
      <dt className="field-label">{label}</dt>
      <dd className="text-sm text-ink-900">{children}</dd>
    </div>
  );
}

function Grant({
  on,
  editable,
  label,
  onChange
}: {
  on: boolean;
  editable: boolean;
  label: string;
  onChange: (value: boolean) => void;
}) {
  if (!editable) return <>{on ? "Yes" : "No"}</>;
  return (
    <label className="inline-flex items-center gap-2">
      <input type="checkbox" className="h-4 w-4 accent-purple-700" checked={on} aria-label={label} onChange={(event) => onChange(event.target.checked)} />
      <span>{on ? "Yes" : "No"}</span>
    </label>
  );
}
