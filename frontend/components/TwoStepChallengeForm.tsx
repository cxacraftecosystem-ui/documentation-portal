"use client";

/**
 * The second step of a sign-in, drawn on the /login card in place of the sign-in controls.
 *
 * Reached after ANY first factor — the password, Google, Microsoft or Yahoo — when the account has
 * two-step verification on: `POST /auth/login` answered a challenge token instead of a session, and
 * this form trades it and a code for the session at `POST /auth/two-factor/verify`. Nobody is signed in
 * until that succeeds, so "Back to sign in" simply forgets the challenge.
 *
 * ONE BOX FOR BOTH KINDS OF CODE. The server tells a 6-digit authenticator code from an `xxxx-xxxx`
 * recovery code by shape, so the toggle below changes only the label, the keyboard and the autofill
 * hint — a recovery code typed into the authenticator box still works.
 *
 * Every refusal is the server's sentence, in the card: a wrong code keeps the form, and a finished
 * challenge (five wrong codes, or five minutes) says to sign in again and offers the way back.
 */

import { useEffect, useRef, useState } from "react";
import { ShieldCheck } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { Button } from "@/components/ui/button";
import { ApiError } from "@/lib/api";
import { CHALLENGE_ENDED_DETAIL } from "@/lib/twoFactor";
import type { User } from "@/lib/types";

export function TwoStepChallengeForm({
  challengeToken,
  onSignedIn,
  onCancel
}: {
  challengeToken: string;
  /** The account, signed in. */
  onSignedIn: (account: User) => void | Promise<void>;
  /** Back to the sign-in controls; the challenge is dropped. */
  onCancel: () => void;
}) {
  const { verifyTwoFactor } = useAuth();
  const [useRecovery, setUseRecovery] = useState(false);
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /** The challenge is finished; only signing in again leads anywhere. */
  const [dead, setDead] = useState(false);
  const box = useRef<HTMLInputElement | null>(null);

  // On arrival and on every switch of kind: the box is where the next keystroke belongs.
  useEffect(() => {
    box.current?.focus();
  }, [useRecovery]);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!code.trim() || busy || dead) return;
    setBusy(true);
    setError(null);
    try {
      const account = await verifyTwoFactor(challengeToken, code.trim());
      await onSignedIn(account);
    } catch (err) {
      setCode("");
      if (err instanceof ApiError && err.status === 401 && err.message === CHALLENGE_ENDED_DETAIL) setDead(true);
      // A fetch that never reached the server rejects with a TypeError, not an ApiError.
      setError(
        err instanceof ApiError
          ? err.message
          : "Couldn't connect. Check your connection and try again."
      );
      box.current?.focus();
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="grid gap-4">
      <div className="flex items-start gap-3">
        <span className="grid h-10 w-10 shrink-0 place-items-center rounded-md bg-purple-950 text-purple-100">
          <ShieldCheck className="h-5 w-5" aria-hidden />
        </span>
        <div className="min-w-0">
          <h2 className="font-display text-lg font-bold text-ink-900">Two-step verification</h2>
          <p className="mt-0.5 text-sm leading-6 text-ink-500">
            {useRecovery
              ? "Enter one of the recovery codes you saved. Each code works once."
              : "Enter the 6-digit code from your authenticator app."}
          </p>
        </div>
      </div>

      {/* Mounted from first paint and emptied rather than removed, so the sentence is announced. */}
      <div
        role="alert"
        className={error ? "rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600" : "sr-only"}
      >
        {error ?? ""}
      </div>

      {dead ? (
        <Button type="button" size="auth" onClick={onCancel} className="w-full font-display text-base font-bold">
          Sign in again
        </Button>
      ) : (
        <form onSubmit={submit} className="grid gap-3">
          <div className="grid gap-2">
            <label htmlFor="two-step-code" className="text-sm font-medium text-ink-900">
              {useRecovery ? "Recovery code" : "6-digit code"}
            </label>
            <input
              id="two-step-code"
              ref={box}
              type="text"
              required
              inputMode={useRecovery ? "text" : "numeric"}
              autoComplete={useRecovery ? "off" : "one-time-code"}
              autoCapitalize="none"
              spellCheck={false}
              maxLength={useRecovery ? 20 : 7}
              placeholder={useRecovery ? "xxxx-xxxx" : "123456"}
              value={code}
              onChange={(event) => setCode(event.target.value)}
              className="field-input h-[52px] text-center font-mono text-lg tracking-[0.3em]"
            />
          </div>
          <Button
            type="submit"
            size="auth"
            disabled={busy || !code.trim()}
            className="w-full font-display text-base font-bold"
          >
            {busy ? "Checking…" : "Verify"}
          </Button>
        </form>
      )}

      <div className="flex flex-wrap items-center justify-between gap-2 text-sm">
        {dead ? (
          <span />
        ) : (
          <button
            type="button"
            onClick={() => {
              setUseRecovery((value) => !value);
              setCode("");
              setError(null);
            }}
            className="font-medium text-purple-700 hover:underline"
          >
            {useRecovery ? "Use your authenticator app instead" : "Use a recovery code instead"}
          </button>
        )}
        <button type="button" onClick={onCancel} className="text-ink-500 hover:text-ink-900">
          Back to sign in
        </button>
      </div>
    </div>
  );
}
