"use client";

/**
 * THE FORCED PASSWORD CHANGE, ASKED BETWEEN THE PERSON AND THE PRODUCT.
 *
 * Ported from the Design Prototype Workshop's component of the same name. An account carrying
 * `mustChangePassword` — made with a temporary password, or asked by an administrator to choose a
 * new one — meets this form INSTEAD of the navigation and the page (`AppShell` returns it above the
 * whole protected tree). The server refuses every other route until it is satisfied
 * (`X-Password-Change-Required`), so this is where the person meets the demand and the server is
 * what makes skipping it pointless.
 *
 * THE CURRENT PASSWORD is asked for only where this tab is not already holding it: straight after a
 * password sign-in the person typed it seconds ago. It appears whenever the host has none (a Google
 * sign-in, a session already open) and after any refusal, so the only button can always succeed.
 *
 * "Sign out instead" is the way out for somebody who cannot complete this — the link an
 * administrator makes for them, redeemed at `/set-password`, lives outside the protected tree.
 */

import { useEffect, useRef, useState } from "react";
import { Eye, EyeOff, Lock } from "lucide-react";

import { Button } from "@/components/ui/button";
import { setToken } from "@/lib/api";
import {
  PASSWORD_CHANGE_PROMPT,
  PASSWORD_CHANGE_SESSIONS,
  currentPasswordRefused,
  newPasswordProblem,
  passwordFailureSentence
} from "@/lib/passwordChange";
import { MAX_PASSWORD_LENGTH, MIN_PASSWORD_LENGTH, changeOwnPassword, passwordRuleLine } from "@/lib/signIn";

export function FirstPasswordGate({
  currentPassword,
  onDone,
  onSignOut
}: {
  /** The password of the last sign-in that succeeded in this tab, or "" where there is none. */
  currentPassword: string;
  onDone: () => void;
  onSignOut: () => void;
}) {
  const [current, setCurrent] = useState(currentPassword);
  const [next, setNext] = useState("");
  const [confirm, setConfirm] = useState("");
  const [show, setShow] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [currentRefusals, setCurrentRefusals] = useState(0);
  const currentBox = useRef<HTMLInputElement | null>(null);
  // From the PROP and the refusal count, never from `current`, or the box would vanish on a keystroke.
  const askCurrent = currentPassword.length === 0 || currentRefusals > 0;

  useEffect(() => {
    if (currentRefusals > 0) currentBox.current?.focus();
  }, [currentRefusals]);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const problem = newPasswordProblem({ current, next, confirm });
    if (problem) {
      setError(problem);
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const fresh = await changeOwnPassword(current, next);
      // The token this form was sent with is retired by its own success; adopt the fresh one BEFORE
      // `onDone` re-reads `/me`, or that re-read is refused.
      if (fresh) setToken(fresh);
      onDone();
    } catch (err) {
      if (currentPasswordRefused(err)) {
        setCurrent("");
        setCurrentRefusals((count) => count + 1);
      }
      setError(passwordFailureSentence(err, "Could not set the password."));
    } finally {
      setSaving(false);
    }
  }

  return (
    <form onSubmit={submit} className="grid gap-3">
      <div role="status" className="rounded-md border border-line-200 bg-surface-50 p-3 text-sm leading-6 text-ink-700">
        {PASSWORD_CHANGE_PROMPT}
      </div>
      {error ? (
        <div role="alert" className="rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600">
          {error}
        </div>
      ) : null}

      {askCurrent ? (
        <div className="grid gap-2">
          <label htmlFor="gate-current-password" className="text-sm font-medium text-ink-900">
            Current password
          </label>
          <div className="relative">
            <Lock aria-hidden className="absolute left-3 top-1/2 h-5 w-5 -translate-y-1/2 text-ink-500" />
            <input
              id="gate-current-password"
              ref={currentBox}
              type={show ? "text" : "password"}
              autoComplete="current-password"
              required
              maxLength={MAX_PASSWORD_LENGTH}
              value={current}
              onChange={(event) => setCurrent(event.target.value)}
              className="field-input h-[52px] pl-10"
            />
          </div>
        </div>
      ) : null}

      <div className="grid gap-2">
        <label htmlFor="gate-new-password" className="text-sm font-medium text-ink-900">
          New password
        </label>
        <div className="relative">
          <Lock aria-hidden className="absolute left-3 top-1/2 h-5 w-5 -translate-y-1/2 text-ink-500" />
          <input
            id="gate-new-password"
            type={show ? "text" : "password"}
            autoComplete="new-password"
            required
            minLength={MIN_PASSWORD_LENGTH}
            maxLength={MAX_PASSWORD_LENGTH}
            value={next}
            onChange={(event) => setNext(event.target.value)}
            className="field-input h-[52px] pl-10 pr-11"
          />
          {/* One toggle for every box: the pair is one password checked against itself. */}
          <button
            type="button"
            aria-label={show ? "Hide password" : "Show password"}
            aria-pressed={show}
            onClick={() => setShow((value) => !value)}
            className="absolute right-2 top-1/2 -translate-y-1/2 grid place-items-center rounded-md p-1 text-ink-500 hover:text-ink-700 pointer-coarse:right-0 pointer-coarse:h-11 pointer-coarse:w-11"
          >
            {show ? <EyeOff size={22} aria-hidden /> : <Eye size={22} aria-hidden />}
          </button>
        </div>
      </div>

      <div className="grid gap-2">
        <label htmlFor="gate-confirm-password" className="text-sm font-medium text-ink-900">
          Repeat password
        </label>
        <div className="relative">
          <Lock aria-hidden className="absolute left-3 top-1/2 h-5 w-5 -translate-y-1/2 text-ink-500" />
          <input
            id="gate-confirm-password"
            type={show ? "text" : "password"}
            autoComplete="new-password"
            required
            minLength={MIN_PASSWORD_LENGTH}
            maxLength={MAX_PASSWORD_LENGTH}
            value={confirm}
            onChange={(event) => setConfirm(event.target.value)}
            className="field-input h-[52px] pl-10"
          />
        </div>
      </div>

      <p className="text-xs leading-5 text-ink-500">{passwordRuleLine(PASSWORD_CHANGE_SESSIONS)}</p>

      <Button type="submit" size="auth" disabled={saving} className="mt-1 w-full font-display text-base font-bold">
        {saving ? "Saving…" : "Set password and continue"}
      </Button>
      <button type="button" className="field-button-secondary w-full" onClick={onSignOut}>
        Sign out instead
      </button>
    </form>
  );
}
