"use client";

import { useEffect, useState } from "react";
import { ShieldCheck } from "lucide-react";

import { EmptyState } from "@/components/EmptyState";
import { Field, Select, TextInput } from "@/components/FormControls";
import { PageHeader } from "@/components/PageHeader";
import { useAuth } from "@/components/AuthProvider";
import { apiFetch, listResource } from "@/lib/api";
import { requiredText } from "@/lib/forms";
import { isAdmin, isMasterAdmin } from "@/lib/permissions";
import type { PageResult, User } from "@/lib/types";

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
    <td className="px-4 py-3 text-neutral-600">
      <label className="inline-flex items-center gap-2">
        <input type="checkbox" checked={included} disabled={!editable} aria-label={label} onChange={(event) => onChange(event.target.checked)} />
        <span>{included ? "Yes" : "No"}</span>
      </label>
    </td>
  );
}

export default function UsersPage() {
  const { user: currentUser } = useAuth();
  const [data, setData] = useState<PageResult<User> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [adminSelection, setAdminSelection] = useState<Set<string>>(new Set());
  const [grantAdmin, setGrantAdmin] = useState(true);
  const [grantQuestionnaire, setGrantQuestionnaire] = useState(false);
  const [grantCrafts, setGrantCrafts] = useState(false);
  const [grantWorkshops, setGrantWorkshops] = useState(false);
  const [applying, setApplying] = useState(false);
  const [adminMessage, setAdminMessage] = useState<string | null>(null);

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
      if (grantCrafts) body.canManageCrafts = true;
      if (grantWorkshops) body.canManageWorkshops = true;
      if (Object.keys(body).length === 0) {
        setAdminMessage("Pick at least one of: admin access, questionnaire, crafts or workshops to grant.");
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
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to update admin access");
    } finally {
      setApplying(false);
    }
  }

  async function load() {
    try {
      setData(await listResource<User>("/users", { pageSize: 100 }));
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load users");
    }
  }

  useEffect(() => {
    load();
  }, []);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    try {
      await apiFetch("/users", {
        method: "POST",
        body: JSON.stringify({
          name: requiredText(form, "name"),
          email: requiredText(form, "email"),
          password: requiredText(form, "password"),
          role: requiredText(form, "role"),
          canManageQuestionnaire: form.get("canManageQuestionnaire") === "on",
          canManageCrafts: form.get("canManageCrafts") === "on",
          canManageWorkshops: form.get("canManageWorkshops") === "on"
        })
      });
      event.currentTarget.reset();
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to create user");
    }
  }

  async function updateRole(user: User, role: string) {
    await apiFetch(`/users/${user.id}`, { method: "PATCH", body: JSON.stringify({ role }) });
    load();
  }

  async function updateGrant(
    user: User,
    field: "canManageQuestionnaire" | "canManageCrafts" | "canManageWorkshops",
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
    if (!window.confirm(`Delete ${user.email}?`)) return;
    await apiFetch(`/users/${user.id}`, { method: "DELETE" });
    load();
  }

  return (
    <>
      <PageHeader
        title="Users"
        description="Admin-only user management for researchers and repository administrators."
        icon={<ShieldCheck className="h-5 w-5" aria-hidden />}
      />
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      <form onSubmit={submit} className="panel mb-5 grid gap-3 p-4 md:grid-cols-5">
        <Field label="Name" required>
          <TextInput name="name" required />
        </Field>
        <Field label="Email" required>
          <TextInput name="email" type="email" required />
        </Field>
        <Field label="Password" required>
          <TextInput name="password" type="password" minLength={8} required />
        </Field>
        <Field label="Role">
          <Select name="role" defaultValue="RESEARCHER">
            <option>RESEARCHER</option>
            <option>ADMIN</option>
            {isMasterAdmin(currentUser) ? <option>MASTER_ADMIN</option> : null}
          </Select>
        </Field>
        <div className="md:col-span-5 flex flex-wrap gap-2">
          <label className="flex items-center gap-2 rounded-md border border-[#e6dfd8] bg-field-100 px-3 py-2 text-sm text-ink-muted">
            <input name="canManageQuestionnaire" type="checkbox" disabled={!isMasterAdmin(currentUser)} />
            Manage questionnaire
          </label>
          <label className="flex items-center gap-2 rounded-md border border-[#e6dfd8] bg-field-100 px-3 py-2 text-sm text-ink-muted">
            <input name="canManageCrafts" type="checkbox" disabled={!isMasterAdmin(currentUser)} />
            Create crafts
          </label>
          <label className="flex items-center gap-2 rounded-md border border-[#e6dfd8] bg-field-100 px-3 py-2 text-sm text-ink-muted">
            <input name="canManageWorkshops" type="checkbox" disabled={!isMasterAdmin(currentUser)} />
            Create workshops
          </label>
        </div>
        <div className="md:col-span-5">
          <button className="field-button">Create user</button>
        </div>
      </form>
      {!isMasterAdmin(currentUser) ? (
        <p className="mb-4 text-xs text-ink-muted">Only the master admin can grant questionnaire, craft, or workshop creation access.</p>
      ) : null}
      <section className="panel overflow-hidden">
        {!data ? (
          <div className="p-4 text-sm text-neutral-600">Loading...</div>
        ) : data.items.length === 0 ? (
          <div className="p-4">
            <EmptyState title="No users found" />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[900px] text-left text-sm">
              <thead className="bg-neutral-50 text-xs uppercase text-neutral-500">
                <tr>
                  <th className="px-4 py-3">Name</th>
                  <th className="px-4 py-3">Email</th>
                  <th className="px-4 py-3">Role</th>
                  <th className="px-4 py-3">Questionnaire</th>
                  <th className="px-4 py-3">Crafts</th>
                  <th className="px-4 py-3">Workshops</th>
                  <th className="px-4 py-3">Provider</th>
                  <th className="px-4 py-3 text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-neutral-200">
                {data.items.map((user) => (
                  <tr key={user.id}>
                    <td className="px-4 py-3 font-medium text-neutral-900">{user.name}</td>
                    <td className="px-4 py-3 text-neutral-600">{user.email}</td>
                    <td className="px-4 py-3">
                      <select className="field-input max-w-40" value={user.role} onChange={(event) => updateRole(user, event.target.value)}>
                        <option>RESEARCHER</option>
                        <option>ADMIN</option>
                        {isMasterAdmin(currentUser) ? <option>MASTER_ADMIN</option> : null}
                      </select>
                    </td>
                    <GrantCell
                      included={user.role === "MASTER_ADMIN" || !!user.canManageQuestionnaire}
                      editable={isMasterAdmin(currentUser) && user.role !== "MASTER_ADMIN"}
                      label={`Allow ${user.email} to manage questionnaire`}
                      onChange={(value) => updateGrant(user, "canManageQuestionnaire", value)}
                    />
                    <GrantCell
                      included={isAdmin(user) || !!user.canManageCrafts}
                      editable={isMasterAdmin(currentUser) && !isAdmin(user)}
                      label={`Allow ${user.email} to create crafts`}
                      onChange={(value) => updateGrant(user, "canManageCrafts", value)}
                    />
                    <GrantCell
                      included={isAdmin(user) || !!user.canManageWorkshops}
                      editable={isMasterAdmin(currentUser) && !isAdmin(user)}
                      label={`Allow ${user.email} to create workshops`}
                      onChange={(value) => updateGrant(user, "canManageWorkshops", value)}
                    />
                    <td className="px-4 py-3 text-neutral-600">{user.authProvider ?? "-"}</td>
                    <td className="px-4 py-3 text-right">
                      {user.role === "MASTER_ADMIN" ? (
                        <span className="text-xs font-semibold text-amber-700">Protected</span>
                      ) : (
                        <button className="text-sm font-semibold text-red-700" onClick={() => remove(user)}>
                          Delete
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
      {isMasterAdmin(currentUser) ? (
        <section className="panel mt-6 p-4">
          <h2 className="font-serif text-xl text-ink">Admin management</h2>
          <p className="mt-1 text-sm text-ink-muted">
            Select one or more existing users, then grant them administrator access and decide their individual privileges in one action. Privileges are additive here — revoke individually in the table above.
          </p>
          {adminMessage ? <div className="mt-3 rounded-md border border-emerald-200 bg-emerald-50 px-3 py-2 text-sm text-emerald-700">{adminMessage}</div> : null}
          <div className="mt-4 grid gap-4 lg:grid-cols-[1.4fr_1fr]">
            <div className="rounded-md border border-[#e6dfd8] bg-field-50 p-3">
              <div className="mb-2 text-xs font-semibold uppercase tracking-wide text-ink-soft">Select users ({adminSelection.size} selected)</div>
              <div className="grid max-h-72 gap-1 overflow-y-auto">
                {(data?.items ?? [])
                  .filter((user) => user.role !== "MASTER_ADMIN")
                  .map((user) => (
                    <label key={user.id} className="flex items-center gap-2 rounded px-2 py-1 hover:bg-field-100">
                      <input type="checkbox" checked={adminSelection.has(user.id)} onChange={() => toggleAdminSelection(user.id)} />
                      <span className="min-w-0 flex-1 truncate text-sm text-ink">
                        {user.name} <span className="text-ink-muted">· {user.email}</span>
                      </span>
                      <span className="rounded-full bg-field-200 px-2 py-0.5 text-xs text-ink-muted">{user.role}</span>
                    </label>
                  ))}
                {(data?.items ?? []).filter((user) => user.role !== "MASTER_ADMIN").length === 0 ? (
                  <p className="px-2 py-1 text-sm text-ink-muted">No eligible users.</p>
                ) : null}
              </div>
            </div>
            <div className="grid content-start gap-2 rounded-md border border-[#e6dfd8] bg-field-50 p-3">
              <div className="mb-1 text-xs font-semibold uppercase tracking-wide text-ink-soft">Grant</div>
              <label className="flex items-center gap-2 text-sm text-ink">
                <input type="checkbox" checked={grantAdmin} onChange={(event) => setGrantAdmin(event.target.checked)} />
                Administrator access (role = ADMIN)
              </label>
              <label className="flex items-center gap-2 text-sm text-ink">
                <input type="checkbox" checked={grantQuestionnaire} onChange={(event) => setGrantQuestionnaire(event.target.checked)} />
                Manage questionnaire
              </label>
              <label className="flex items-center gap-2 text-sm text-ink">
                <input type="checkbox" checked={grantCrafts} onChange={(event) => setGrantCrafts(event.target.checked)} />
                Create crafts
              </label>
              <label className="flex items-center gap-2 text-sm text-ink">
                <input type="checkbox" checked={grantWorkshops} onChange={(event) => setGrantWorkshops(event.target.checked)} />
                Create workshops
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
