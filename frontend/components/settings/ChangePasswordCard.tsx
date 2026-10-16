"use client";

/**
 * SETTINGS → PASSWORD: change your own, or set a first one.
 *
 * Ported from the Design Prototype Workshop's card of the same name, with one more branch:
 *
 *  - An account WITH a password changes it, entering the current one.
 *  - An account WITHOUT one (it signs in with Google, Microsoft or Yahoo) may SET a first password,
 *    so it can sign in either way. The server allows that only within a short while of signing in;
 *    past it, the answer carries `X-Sign-In-Again` and this card offers to sign out and back in.
 *
 * Either way the server ends every other session of the account and hands this one a fresh token in
 * `X-Session-Token`, which is adopted BEFORE `/me` is re-read.
 */

import { useEffect, useRef, useState } from "react";
import { Eye, EyeOff, KeyRound } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { setToken } from "@/lib/api";
import {
  FIRST_PASSWORD_NOTE,
  PASSWORD_CHANGE_SESSIONS,
  currentPasswordRefused,
  hasOwnPassword,
  newPasswordProblem,
  passwordFailureSentence
} from "@/lib/passwordChange";
import {
  MAX_PASSWORD_LENGTH,
  MIN_PASSWORD_LENGTH,
  asksToSignInAgain,
  changeOwnPassword,
  passwordRuleLine
} from "@/lib/signIn";

const CHANGED_HERE_ONLY = "Password changed. You are still signed in here; everywhere else, sign in with the new one.";
const CHANGED = "Password changed. Use the new one the next time you sign in.";
const FIRST_SET = "Password set. You can now sign in with it as well as the way you do now.";

export function ChangePasswordCard() {
  const { user, refreshMe, logout } = useAuth();
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [confirm, setConfirm] = useState("");
  const [show, setShow] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [signInAgain, setSignInAgain] = useState(false);
  const [changed, setChanged] = useState<string | null>(null);
  const [currentRefusals, setCurrentRefusals] = useState(0);
  const currentBox = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    if (currentRefusals > 0) currentBox.current?.focus();
  }, [currentRefusals]);

  if (!user) return null;
  const setting = !hasOwnPassword(user);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setChanged(null);
    setSignInAgain(false);
    const problem = newPasswordProblem({ current: setting ? "" : current, next, confirm });
    if (problem) {
      setError(problem);
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const fresh = await changeOwnPassword(setting ? "" : current, next);
      setCurrent("");
      setNext("");
      setConfirm("");
      setShow(false);
      if (fresh) {
        setToken(fresh);
        void refreshMe();
        setChanged(setting ? FIRST_SET : CHANGED_HERE_ONLY);
      } else {
        setChanged(setting ? FIRST_SET : CHANGED);
        if (setting) void refreshMe();
      }
    } catch (err) {
      if (asksToSignInAgain(err)) {
        setSignInAgain(true);
      } else if (!setting && currentPasswordRefused(err)) {
        setCurrent("");
        setCurrentRefusals((count) => count + 1);
      }
      setError(passwordFailureSentence(err, setting ? "Could not set the password." : "Could not change the password."));
    } finally {
      setSaving(false);
    }
  }

  return (
    <section className="panel p-5">
      <div className="flex items-center gap-2.5">
        <span className="grid h-8 w-8 place-items-center rounded-md bg-purple-950 text-purple-100">
          <KeyRound className="h-4 w-4" aria-hidden />
        </span>
        <h2 className="font-display font-bold text-ink-900">{setting ? "Set a password" : "Change password"}</h2>
      </div>
      <p className="mt-1.5 text-xs leading-5 text-ink-500">
        {setting
          ? "You sign in with Google, Microsoft or Yahoo. Add a password to sign in with your email address too."
          : "The password you sign in with. You need the current one to choose another."}
      </p>

      <form onSubmit={submit} className="mt-3 grid max-w-md gap-3">
        {/* For a password manager: the account this password belongs to. */}
        <input type="text" name="username" autoComplete="username" value={user.email} readOnly hidden />

        {/* Both regions mounted from first paint, so what lands in them is announced. */}
        <div
          role="alert"
          aria-live="assertive"
          className={error ? "rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600" : "sr-only"}
        >
          {error ?? ""}
          {signInAgain ? (
            <div className="mt-2">
              <button type="button" className="field-button-secondary" onClick={() => void logout()}>
                Sign out and sign in again
              </button>
            </div>
          ) : null}
        </div>
        <p
          role="status"
          aria-live="polite"
          className={changed ? "rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700" : "sr-only"}
        >
          {changed ?? ""}
        </p>

        {setting ? null : (
          <div>
            <label htmlFor="settings-current-password" className="field-label">
              Current password
            </label>
            <input
              id="settings-current-password"
              ref={currentBox}
              type={show ? "text" : "password"}
              autoComplete="current-password"
              required
              maxLength={MAX_PASSWORD_LENGTH}
              value={current}
              onChange={(event) => setCurrent(event.target.value)}
              className="field-input mt-1.5"
            />
          </div>
        )}

        <div>
          <label htmlFor="settings-new-password" className="field-label">
            New password
          </label>
          <div className="relative mt-1.5">
            <input
              id="settings-new-password"
              type={show ? "text" : "password"}
              autoComplete="new-password"
              required
              minLength={MIN_PASSWORD_LENGTH}
              maxLength={MAX_PASSWORD_LENGTH}
              value={next}
              onChange={(event) => setNext(event.target.value)}
              className="field-input pr-11"
            />
            <button
              type="button"
              aria-label={show ? "Hide passwords" : "Show passwords"}
              aria-pressed={show}
              onClick={() => setShow((value) => !value)}
              className="absolute right-2 top-1/2 -translate-y-1/2 grid place-items-center rounded-md p-1 text-ink-500 hover:text-ink-700 pointer-coarse:right-0 pointer-coarse:h-11 pointer-coarse:w-11"
            >
              {show ? <EyeOff size={20} aria-hidden /> : <Eye size={20} aria-hidden />}
            </button>
          </div>
        </div>

        <div>
          <label htmlFor="settings-confirm-password" className="field-label">
            Repeat password
          </label>
          <input
            id="settings-confirm-password"
            type={show ? "text" : "password"}
            autoComplete="new-password"
            required
            minLength={MIN_PASSWORD_LENGTH}
            maxLength={MAX_PASSWORD_LENGTH}
            value={confirm}
            onChange={(event) => setConfirm(event.target.value)}
            className="field-input mt-1.5"
          />
        </div>

        <p className="text-xs leading-5 text-ink-500">
          {passwordRuleLine(setting ? FIRST_PASSWORD_NOTE : PASSWORD_CHANGE_SESSIONS)}
        </p>

        <div>
          <button type="submit" className="field-button" disabled={saving}>
            {saving ? "Saving…" : setting ? "Set password" : "Change password"}
          </button>
        </div>
      </form>
    </section>
  );
}
