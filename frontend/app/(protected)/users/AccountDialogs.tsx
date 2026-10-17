"use client";

/**
 * The row actions on /users that need more than a yes or a no: setting a temporary password, and
 * requiring a new password at next sign-in. Ported from the Design Prototype Workshop's file of the
 * same name. Both show the server's own sentence on a refusal — its wording is the remedy.
 *
 * `open` is separate from `target` so the exit animation plays over the real content.
 */

import { useId, useRef, useState } from "react";
import { AlertTriangle, Eye, EyeOff, KeyRound } from "lucide-react";

import { FieldDialog } from "@/components/dialogs/FieldDialog";
import {
  MAX_PASSWORD_LENGTH,
  MIN_PASSWORD_LENGTH,
  passwordRuleLine,
  requirePasswordChange,
  setTemporaryPassword
} from "@/lib/signIn";
import type { User } from "@/lib/types";

import { hasPassword, linkOffered, passwordFrom, passwordHasEdgeSpace } from "./accountAdmin";
import { Toggle } from "@/components/ui/Toggle";

/** The amber box for "this choice has a cost". */
export const CAUTION_CLASS = "rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800";

const ERROR_CLASS = "rounded-md border border-error-600/30 bg-error-100 px-3 py-2 text-sm leading-6 text-error-600";

/** The sentence under "Require a new password …", on the create form and in the dialog alike. */
export function RequireChangeNote({ required, when }: { required: boolean; when: "first" | "next" }) {
  return (
    <div aria-live="polite">
      {required ? (
        <p className="text-xs leading-5 text-ink-500">
          They’ll choose their own at their {when} sign-in.
        </p>
      ) : (
        <p className={CAUTION_CLASS}>
          Not required — you’ll keep knowing their password.
        </p>
      )}
    </div>
  );
}

export function EdgeSpaceNote() {
  return (
    <p className={CAUTION_CLASS}>
      Starts or ends with a space — they must type it too.
    </p>
  );
}

/**
 * SET A TEMPORARY PASSWORD FOR SOMEBODY ELSE. Saving signs them out everywhere (the server stamps the
 * account's sign-out-everywhere mark), and the dialog says so before the button is pressed.
 */
export function TemporaryPasswordDialog({
  open,
  target,
  onClose,
  onSaved
}: {
  open: boolean;
  target: User | null;
  onClose: () => void;
  onSaved: (updated: User, mustChangePassword: boolean) => void;
}) {
  const formId = useId();
  const passwordId = useId();
  const passwordRef = useRef<HTMLInputElement | null>(null);
  const [revealed, setRevealed] = useState(false);
  const [requireChange, setRequireChange] = useState(true);
  const [edgeSpace, setEdgeSpace] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function reset() {
    setRevealed(false);
    setRequireChange(true);
    setEdgeSpace(false);
    setError(null);
  }

  function close() {
    if (busy) return;
    reset();
    onClose();
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const password = passwordFrom(new FormData(event.currentTarget));
    if (!open || !target || busy) return;
    setBusy(true);
    setError(null);
    try {
      const updated = await setTemporaryPassword(target.id, password, requireChange);
      const required = updated.mustChangePassword ?? requireChange;
      reset();
      onSaved(updated, required);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to set the password");
    } finally {
      setBusy(false);
    }
  }

  const who = target ? target.name || target.email : "";

  return (
    <FieldDialog
      open={open && target !== null}
      onClose={close}
      busy={busy}
      role="alertdialog"
      tone="warning"
      icon={<KeyRound className="h-4 w-4" aria-hidden />}
      title="Set a temporary password"
      description={target ? `${target.name} · ${target.email}` : undefined}
      initialFocusRef={passwordRef}
      footer={
        <>
          <button type="button" className="field-button-secondary" disabled={busy} onClick={close}>
            Cancel
          </button>
          <button type="submit" form={formId} className="field-button" disabled={busy}>
            {busy ? "Saving…" : "Set password"}
          </button>
        </>
      }
    >
      {target ? (
        <form key={target.id} id={formId} onSubmit={submit} className="grid gap-3">
          {error ? (
            <p role="alert" className={ERROR_CLASS}>
              {error}
            </p>
          ) : null}
          <p className="text-sm leading-6 text-ink-700">
            <strong className="font-medium text-ink-900">Saving signs {who} out everywhere.</strong>
          </p>
          {hasPassword(target) ? null : (
            <p className="text-sm leading-6 text-ink-700">
              They’ll be able to sign in with this password too.
            </p>
          )}
          <div className="grid gap-1">
            <label htmlFor={passwordId} className="field-label">
              Temporary password
            </label>
            <div className="relative">
              <input
                id={passwordId}
                ref={passwordRef}
                name="password"
                type={revealed ? "text" : "password"}
                required
                minLength={MIN_PASSWORD_LENGTH}
                maxLength={MAX_PASSWORD_LENGTH}
                autoComplete="new-password"
                autoCapitalize="none"
                autoCorrect="off"
                spellCheck={false}
                onChange={(event) => setEdgeSpace(passwordHasEdgeSpace(event.target.value))}
                className="field-input pr-11"
              />
              <button
                type="button"
                aria-label={revealed ? "Hide password" : "Show password"}
                aria-pressed={revealed}
                onClick={() => setRevealed((value) => !value)}
                className="absolute right-2 top-1/2 -translate-y-1/2 grid place-items-center rounded-md p-1 text-ink-500 hover:text-ink-700 pointer-coarse:right-0 pointer-coarse:h-11 pointer-coarse:w-11"
              >
                {revealed ? <EyeOff size={18} aria-hidden /> : <Eye size={18} aria-hidden />}
              </button>
            </div>
            <p className="text-xs leading-5 text-ink-500">
              {passwordRuleLine("It won’t be shown again.")}
            </p>
          </div>
          {edgeSpace ? <EdgeSpaceNote /> : null}
          <Toggle label="Require a new password at next sign-in" checked={requireChange} onChange={setRequireChange} />
          <RequireChangeNote required={requireChange} when="next" />
        </form>
      ) : null}
    </FieldDialog>
  );
}

/**
 * REQUIRE A NEW PASSWORD AT NEXT SIGN-IN, on an account that has one — its password is not touched.
 * It also signs them out everywhere. Offers a password link with the same decision, for somebody who
 * signs in with Google and may never have known the password the account carries.
 */
export function RequirePasswordChangeDialog({
  open,
  target,
  onClose,
  onSaved
}: {
  open: boolean;
  target: User | null;
  onClose: () => void;
  /** `withLink`: the reader also asked for a password link, which the page makes and shows. */
  onSaved: (updated: User, withLink: boolean) => void;
}) {
  const confirmRef = useRef<HTMLButtonElement | null>(null);
  const [withLinkChoice, setWithLinkChoice] = useState<boolean | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const canLink = target ? linkOffered(target) : false;
  const withLink = canLink && (withLinkChoice ?? (target?.authProvider ?? "LOCAL").toUpperCase() !== "LOCAL");

  function reset() {
    setWithLinkChoice(null);
    setError(null);
  }

  function close() {
    if (busy) return;
    reset();
    onClose();
  }

  async function confirm() {
    if (!open || !target || busy) return;
    setBusy(true);
    setError(null);
    try {
      const updated = await requirePasswordChange(target.id);
      const asked = withLink;
      reset();
      onSaved(updated, asked);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to require a new password");
    } finally {
      setBusy(false);
    }
  }

  const who = target ? target.name || target.email : "";

  return (
    <FieldDialog
      open={open && target !== null}
      onClose={close}
      busy={busy}
      role="alertdialog"
      tone="warning"
      icon={<AlertTriangle className="h-4 w-4" aria-hidden />}
      title="Require a new password at next sign-in?"
      description={target ? `${target.name} · ${target.email}` : undefined}
      initialFocusRef={confirmRef}
      footer={
        <>
          <button type="button" className="field-button-secondary" disabled={busy} onClick={close}>
            Cancel
          </button>
          <button ref={confirmRef} type="button" className="field-button" disabled={busy} onClick={confirm}>
            {busy ? "Working…" : "Require a new password"}
          </button>
        </>
      }
    >
      {target ? (
        <div key={target.id} className="grid gap-3">
          {error ? (
            <p role="alert" className={ERROR_CLASS}>
              {error}
            </p>
          ) : null}
          <p className="text-sm leading-6 text-ink-700">
            <strong className="font-medium text-ink-900">{who} is signed out everywhere</strong> and must choose a new
            password at next sign-in.
          </p>
          <p className="text-sm leading-6 text-ink-700">
            If they don’t know their current password, send a link.
          </p>
          {canLink ? (
            <Toggle
              label="Also make a password link"
              description="Shown to you once, on this page, to send them yourself."
              checked={withLink}
              disabled={busy}
              onChange={setWithLinkChoice}
            />
          ) : null}
        </div>
      ) : null}
    </FieldDialog>
  );
}
