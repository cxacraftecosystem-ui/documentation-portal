"use client";

/**
 * "ADD A PERSON" — ONE STEP: the account, its place on the access roster, and how they will sign in.
 *
 * `POST /api/users` creates the account at the chosen role and admits the address on the access
 * roster in the same transaction, so there is no second approval to remember. The role picker offers
 * only roles strictly below the viewer's own (only the master admin is offered Admin).
 *
 * "How will they sign in?":
 *  - Set a password now — typed here, and (by default) changed by them at their first sign-in;
 *  - Make a temporary password for me — the server makes one; it is shown ONCE, here, with Copy;
 *  - Google, Microsoft or Yahoo only — and, where e-mail is set up, a link to set a password too.
 *
 * An address that already has an account is a 409 naming it; this form shows that sentence and an
 * "Open their account" button rather than making a duplicate.
 */

import { useState } from "react";
import { Copy, UserPlus } from "lucide-react";

import { Field, TextInput } from "@/components/FormControls";
import { requiredText } from "@/lib/forms";
import { roleLabel } from "@/lib/permissions";
import {
  MAX_PASSWORD_LENGTH,
  MIN_PASSWORD_LENGTH,
  createPasswordAccount,
  existingAccountId,
  passwordRuleLine,
  type CreatedAccount,
  type PasswordMode
} from "@/lib/signIn";
import type { User, UserRole } from "@/lib/types";

import { RequireChangeNote } from "./AccountDialogs";
import { createdSentence, creatableRoles, newAccountBody, passwordFrom } from "./accountAdmin";

export function AddPersonForm({
  viewer,
  mailAvailable,
  onAdded,
  onOpenExisting
}: {
  viewer: User | null;
  mailAvailable: boolean;
  onAdded: (created: CreatedAccount) => void;
  /** Show the account that already uses this address, so its role or access can be changed. */
  onOpenExisting: (accountId: string, email: string) => void;
}) {
  const roles = creatableRoles(viewer);
  const [mode, setMode] = useState<PasswordMode>("SET");
  const [requireChange, setRequireChange] = useState(true);
  const [sendLink, setSendLink] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [existing, setExisting] = useState<{ id: string; email: string } | null>(null);
  const [done, setDone] = useState<{ sentence: string; temporaryPassword: string | null } | null>(null);
  const [copied, setCopied] = useState(false);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // Read before any await: React nulls `currentTarget` across one.
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    const email = requiredText(form, "email");
    setSaving(true);
    setError(null);
    setExisting(null);
    setDone(null);
    setCopied(false);
    try {
      const created = await createPasswordAccount(
        newAccountBody(
          {
            name: requiredText(form, "name"),
            email,
            role: requiredText(form, "role") as UserRole,
            passwordMode: mode,
            password: passwordFrom(form),
            mustChangePassword: requireChange,
            sendPasswordLink: sendLink,
            canManageQuestionnaire: form.get("canManageQuestionnaire") === "on",
            canDownloadDataset: form.get("canDownloadDataset") === "on"
          },
          { mailAvailable }
        )
      );
      formElement.reset();
      setDone({ sentence: createdSentence(created, mode), temporaryPassword: created.temporaryPassword ?? null });
      setMode("SET");
      setRequireChange(true);
      setSendLink(true);
      onAdded(created);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to add this person");
      const id = existingAccountId(err);
      if (id) setExisting({ id, email });
    } finally {
      setSaving(false);
    }
  }

  async function copyTemporary() {
    if (!done?.temporaryPassword) return;
    try {
      await navigator.clipboard.writeText(done.temporaryPassword);
      setCopied(true);
    } catch {
      // Refused: say nothing rather than claim success — the password is on screen to select.
      setCopied(false);
    }
  }

  if (roles.length === 0) return null;

  return (
    <form onSubmit={submit} className="panel mb-5 grid gap-4 p-4">
      <div className="flex items-center gap-2.5">
        <span className="grid h-8 w-8 place-items-center rounded-md bg-purple-950 text-purple-100">
          <UserPlus className="h-4 w-4" aria-hidden />
        </span>
        <h2 className="font-display text-lg font-bold text-ink-900">Add a person</h2>
      </div>
      <p className="-mt-2 text-sm leading-6 text-ink-500">
        They can sign in straight away.
      </p>

      <div role="alert" aria-live="assertive">
        {error ? (
          <div className="grid gap-2 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-800">
            <p>{error}</p>
            {existing ? (
              <div>
                <button
                  type="button"
                  className="field-button-secondary"
                  onClick={() => onOpenExisting(existing.id, existing.email)}
                >
                  Open their account
                </button>
              </div>
            ) : null}
          </div>
        ) : null}
      </div>
      <div role="status" aria-live="polite">
        {done ? (
          <div className="grid gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-3 text-sm text-ink-700">
            <p>{done.sentence}</p>
            {done.temporaryPassword ? (
              <>
                <div className="flex flex-wrap items-center gap-2">
                  <input
                    readOnly
                    value={done.temporaryPassword}
                    aria-label="Temporary password"
                    onFocus={(event) => event.currentTarget.select()}
                    className="field-input min-w-0 max-w-sm flex-1 font-mono"
                  />
                  <button type="button" className="field-button-secondary" onClick={() => void copyTemporary()}>
                    <Copy className="h-4 w-4" aria-hidden />
                    {copied ? "Copied" : "Copy"}
                  </button>
                </div>
                <p className="text-xs leading-5 text-ink-500">
                  Shown only now. Send it to them yourself — by phone, WhatsApp or in person. They must change it
                  when they first sign in.
                </p>
              </>
            ) : null}
          </div>
        ) : null}
      </div>

      <div className="grid gap-3 md:grid-cols-3">
        <Field label="Name" required>
          <TextInput name="name" required maxLength={160} />
        </Field>
        <Field label="Email" required>
          <TextInput name="email" type="email" required autoComplete="off" />
        </Field>
        <Field label="Role" required>
          <select name="role" className="field-input" defaultValue={roles.includes("RESEARCHER") ? "RESEARCHER" : roles[0]}>
            {roles.map((role) => (
              <option key={role} value={role}>
                {roleLabel(role)}
              </option>
            ))}
          </select>
        </Field>
      </div>

      <fieldset className="grid gap-2">
        <legend className="field-label">How will they sign in?</legend>
        <label className="flex items-start gap-2 text-sm text-ink-700">
          <input type="radio" name="passwordMode" value="SET" checked={mode === "SET"} onChange={() => setMode("SET")} className="mt-1" />
          <span>Set a password now</span>
        </label>
        <label className="flex items-start gap-2 text-sm text-ink-700">
          <input
            type="radio"
            name="passwordMode"
            value="GENERATE"
            checked={mode === "GENERATE"}
            onChange={() => setMode("GENERATE")}
            className="mt-1"
          />
          <span>Make a temporary password for me</span>
        </label>
        <label className="flex items-start gap-2 text-sm text-ink-700">
          <input type="radio" name="passwordMode" value="NONE" checked={mode === "NONE"} onChange={() => setMode("NONE")} className="mt-1" />
          <span>Google, Microsoft or Yahoo only</span>
        </label>

        {mode === "SET" ? (
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
            <p className="text-xs leading-5 text-ink-500">{passwordRuleLine("Send it to them yourself — nothing shows it again.")}</p>
            <label className="flex items-start gap-2 text-sm text-ink-700">
              <input type="checkbox" checked={requireChange} onChange={(event) => setRequireChange(event.target.checked)} className="mt-1" />
              <span>Ask them to choose a new password at first sign-in</span>
            </label>
            <RequireChangeNote required={requireChange} when="first" />
          </div>
        ) : mode === "GENERATE" ? (
          <p className="rounded-md border border-line-200 bg-surface-50 p-3 text-xs leading-5 text-ink-500 md:max-w-md">
            A strong temporary password is made for you and shown once, here, after they are added. They must change it
            when they first sign in.
          </p>
        ) : (
          <div className="grid gap-2 rounded-md border border-line-200 bg-surface-50 p-3 md:max-w-md">
            <p className="text-xs leading-5 text-ink-500">
              Their account opens the first time they sign in with Google, Microsoft or Yahoo on this address.
            </p>
            {mailAvailable ? (
              <label className="flex items-start gap-2 text-sm text-ink-700">
                <input type="checkbox" checked={sendLink} onChange={(event) => setSendLink(event.target.checked)} className="mt-1" />
                <span>Email them a link to set a password too</span>
              </label>
            ) : null}
          </div>
        )}
      </fieldset>

      <div className="flex flex-wrap gap-2">
        <label className="flex items-center gap-2 rounded-md border border-line-200 bg-field-100 px-3 py-2 text-sm text-ink-muted">
          <input name="canManageQuestionnaire" type="checkbox" />
          Manage questionnaire
        </label>
        <label className="flex items-center gap-2 rounded-md border border-line-200 bg-field-100 px-3 py-2 text-sm text-ink-muted">
          <input name="canDownloadDataset" type="checkbox" />
          Download dataset
        </label>
      </div>

      <div>
        <button className="field-button" disabled={saving}>
          <UserPlus className="h-4 w-4" aria-hidden />
          {saving ? "Adding…" : "Add person"}
        </button>
      </div>
    </form>
  );
}
