"use client";

import { useEffect, useRef, useState } from "react";
import { Eye, EyeOff, Lock } from "lucide-react";

import { Button } from "@/components/ui/button";
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
      await changeOwnPassword(current, next);
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
