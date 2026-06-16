"use client";

import { useEffect, useState } from "react";
import { ShieldCheck } from "lucide-react";

import { EmptyState } from "@/components/EmptyState";
import { Field, Select, TextInput } from "@/components/FormControls";
import { PageHeader } from "@/components/PageHeader";
import { useAuth } from "@/components/AuthProvider";
import { apiFetch, listResource } from "@/lib/api";
import { requiredText } from "@/lib/forms";
import { isMasterAdmin } from "@/lib/permissions";
import type { PageResult, User } from "@/lib/types";

export default function UsersPage() {
  const { user: currentUser } = useAuth();
  const [data, setData] = useState<PageResult<User> | null>(null);
  const [error, setError] = useState<string | null>(null);

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
          canManageQuestionnaire: form.get("canManageQuestionnaire") === "on"
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

  async function updateQuestionnaireAccess(user: User, canManageQuestionnaire: boolean) {
    await apiFetch(`/users/${user.id}`, { method: "PATCH", body: JSON.stringify({ canManageQuestionnaire }) });
    load();
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
        <label className="flex items-end gap-2 rounded-md border border-[#e6dfd8] bg-field-100 px-3 py-2 text-sm text-ink-muted">
          <input name="canManageQuestionnaire" type="checkbox" disabled={!isMasterAdmin(currentUser)} />
          Can manage questionnaire
        </label>
        <div className="md:col-span-5">
          <button className="field-button">Create user</button>
        </div>
      </form>
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
                    <td className="px-4 py-3 text-neutral-600">
                      <label className="inline-flex items-center gap-2">
                        <input
                          type="checkbox"
                          checked={user.role === "MASTER_ADMIN" || !!user.canManageQuestionnaire}
                          disabled={!isMasterAdmin(currentUser) || user.role === "MASTER_ADMIN"}
                          aria-label={`Allow ${user.email} to manage questionnaire`}
                          onChange={(event) => updateQuestionnaireAccess(user, event.target.checked)}
                        />
                        <span>{user.role === "MASTER_ADMIN" ? "Included" : "Manager"}</span>
                      </label>
                    </td>
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
    </>
  );
}
