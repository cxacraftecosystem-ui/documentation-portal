"use client";

/**
 * The second step of a sign-in, drawn on the /login card in place of the sign-in controls.
 *
 * Reached after ANY first factor — the password, Google, Microsoft or Yahoo — when the account has
 * two-step verification on: `POST /auth/login` answered a challenge token instead of a session, and
 * this form trades it and a code for the session at `POST /auth/two-factor/verify`. Nobody is signed in
 * until that succeeds, so "Back to sign in" simply forgets the challenge.
 *
 * TWO SHAPES, TWO FIELDS (owner, 2026-10-17). The 6-digit code is typed into `OtpCodeInput` — six
 * boxes, digits only, sent the moment the sixth lands. A recovery code (`xxxx-xxxx`) is a different
 * shape and has its own text field, reached by the "Use a recovery code instead" switch. The server
 * still tells them apart by shape, so either box sends to the same endpoint.
 *
 * Every refusal is the server's sentence, in the card: a wrong code keeps the form, and a finished
 * challenge (five wrong codes, or five minutes) says to sign in again and offers the way back.
 */

import { useEffect, useRef, useState } from "react";
import { ShieldCheck } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { Button } from "@/components/ui/button";
import { OtpCodeInput } from "@/components/ui/OtpCodeInput";
import { Toggle } from "@/components/ui/Toggle";
import { ApiError } from "@/lib/api";
import { isCompleteCode } from "@/lib/otp";
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
  const [errorNonce, setErrorNonce] = useState(0);
  /** The challenge is finished; only signing in again leads anywhere. */
  const [dead, setDead] = useState(false);
  const box = useRef<HTMLInputElement | null>(null);

  // On a switch to the recovery field: that box is where the next keystroke belongs. (The six code
  // boxes focus themselves.)
  useEffect(() => {
    if (useRecovery) box.current?.focus();
  }, [useRecovery]);

  const ready = useRecovery ? code.trim().length > 0 : isCompleteCode(code);

  async function send(typed: string) {
    const value = typed.trim();
    if (busy || dead || (useRecovery ? !value : !isCompleteCode(value))) return;
    setBusy(true);
    setError(null);
    try {
      const account = await verifyTwoFactor(challengeToken, value);
      await onSignedIn(account);
    } catch (err) {
      setCode("");
      setErrorNonce((n) => n + 1);
      if (err instanceof ApiError && err.status === 401 && err.message === CHALLENGE_ENDED_DETAIL) setDead(true);
      // A fetch that never reached the server rejects with a TypeError, not an ApiError.
      setError(
        err instanceof ApiError
          ? err.message
          : "Couldn't connect. Check your connection and try again."
      );
      if (useRecovery) box.current?.focus();
    } finally {
      setBusy(false);
    }
  }

  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void send(code);
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
          {useRecovery ? (
            <div className="grid gap-2">
              <label htmlFor="two-step-code" className="text-sm font-medium text-ink-900">
                Recovery code
              </label>
              <input
                id="two-step-code"
                ref={box}
                type="text"
                required
                autoComplete="off"
                autoCapitalize="none"
                spellCheck={false}
                maxLength={20}
                placeholder="xxxx-xxxx"
                value={code}
                aria-invalid={error ? true : undefined}
                onChange={(event) => setCode(event.target.value)}
                className="field-input h-[52px] text-center font-mono text-lg tracking-[0.2em]"
              />
            </div>
          ) : (
            <OtpCodeInput
              value={code}
              onChange={setCode}
              onComplete={(full) => void send(full)}
              label="6-digit code"
              error={Boolean(error)}
              errorNonce={errorNonce}
              disabled={busy}
              autoFocus
            />
          )}
          <Button
            type="submit"
            size="auth"
            disabled={busy || !ready}
            className="w-full font-display text-base font-bold"
          >
            {busy ? "Checking…" : "Verify"}
          </Button>
        </form>
      )}

      {dead ? null : (
        <Toggle
          label="Use a recovery code instead"
          description="For when your phone is lost or the authenticator app is gone."
          checked={useRecovery}
          onChange={(next) => {
            setUseRecovery(next);
            setCode("");
            setError(null);
          }}
        />
      )}
      <div className="flex flex-wrap items-center justify-end gap-2 text-sm">
        <button type="button" onClick={onCancel} className="min-h-11 text-ink-500 hover:text-ink-900">
          Back to sign in
        </button>
      </div>
    </div>
  );
}
