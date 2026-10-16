"use client";

/**
 * /set-password?token=… — redeem a password link. PUBLIC: it lives outside `app/(protected)/` so no
 * gate in that tree can stand in front of somebody who cannot sign in.
 *
 * Ported from the Design Prototype Workshop's page of the same name. The link is checked before a
 * password box is drawn, so a dead link says why instead of refusing a typed password. Redeeming it
 * signs the account out everywhere and opens NO session: the person signs in with the new password
 * afterwards, and meets two-step verification there if it is on.
 */

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { Eye, EyeOff, Lock } from "lucide-react";

import { AuthCard } from "@/components/AuthCard";
import { useAuth } from "@/components/AuthProvider";
import { Button } from "@/components/ui/button";
import { passwordFailureSentence } from "@/lib/passwordChange";
import {
  MAX_PASSWORD_LENGTH,
  MIN_PASSWORD_LENGTH,
  checkPasswordLink,
  linkRefusal,
  passwordRuleLine,
  setPasswordWithLink,
  takeLinkTokenFromAddress
} from "@/lib/signIn";

export default function SetPasswordPage() {
  const router = useRouter();
  const { clearSession } = useAuth();

  const [token, setLinkToken] = useState<string | null>(null);
  const [linkValid, setLinkValid] = useState<boolean | null>(null);
  const [linkReason, setLinkReason] = useState<string | null>(null);
  const [purpose, setPurpose] = useState<string | null>(null);
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  useEffect(() => {
    const taken = takeLinkTokenFromAddress(window.location.href);
    const address = taken.address;
    // Off the address bar, so the credential is not left in the history. One microtask later so the
    // app router's own patched history call has been installed by its effect.
    if (address !== null) queueMicrotask(() => window.history.replaceState(null, "", address));
    setLinkToken((previous) => previous || taken.token);
  }, []);

  useEffect(() => {
    if (token === null) return;
    let cancelled = false;
    if (!token) {
      setLinkValid(false);
      setLinkReason("missing");
      return;
    }
    checkPasswordLink(token)
      .then((result) => {
        if (cancelled) return;
        setLinkValid(result.valid);
        setLinkReason(result.reason);
        setPurpose(result.purpose);
      })
      .catch(() => {
        // The check could not be made: offer the form and let the redemption decide.
        if (cancelled) return;
        setLinkValid(true);
        setLinkReason(null);
      });
    return () => {
      cancelled = true;
    };
  }, [token]);

  const submit = useCallback(
    async (event: React.FormEvent<HTMLFormElement>) => {
      event.preventDefault();
      if (!token) return;
      if (password !== confirm) {
        setError("The two passwords do not match.");
        return;
      }
      setSaving(true);
      setError(null);
      try {
        await setPasswordWithLink(token, password);
        // The redemption ended every session of the account; this tab's is dropped so "Go to sign in"
        // opens on the form and not on a stale account.
        clearSession();
        setDone(true);
      } catch (err) {
        setError(passwordFailureSentence(err, "Could not set the password."));
      } finally {
        setSaving(false);
      }
    },
    [clearSession, confirm, password, token]
  );

  if (done) {
    return (
      <AuthCard title="Password set">
        <div className="grid gap-4">
          <p role="status" className="text-sm leading-6 text-ink-700">
            Your password is set. Any device that was signed in to this account has been signed out. Sign in with your
            new password — if two-step verification is on, you will be asked for your code as usual.
          </p>
          <Button className="w-full font-display font-bold" onClick={() => router.replace("/login")} size="auth">
            Go to sign in
          </Button>
        </div>
      </AuthCard>
    );
  }

  if (linkValid === null) {
    return (
      <AuthCard title="Set your password">
        <p className="text-sm text-ink-500">Checking this link…</p>
      </AuthCard>
    );
  }

  if (!linkValid) {
    return (
      <AuthCard title="Set your password">
        <div className="grid gap-4">
          <div role="alert" className="rounded-md border border-red-200 bg-error-100 px-3 py-3 text-sm leading-6 text-error-600">
            {linkRefusal(linkReason)}
          </div>
          <Link href="/forgot-password" className="field-button w-full">
            Email me a new link
          </Link>
          <Link href="/login" className="field-button-secondary w-full">
            Back to sign in
          </Link>
        </div>
      </AuthCard>
    );
  }

  return (
    <AuthCard
      title={purpose === "INVITE" ? "Choose your password" : "Set your password"}
      subtitle={purpose === "INVITE" ? "Choose the password you will sign in with." : "Choose a new password for your account."}
    >
      <form onSubmit={submit} className="grid gap-3">
        {error ? (
          <div role="alert" className="rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600">
            {error}
          </div>
        ) : null}

        <div className="grid gap-2">
          <label htmlFor="new-password" className="text-sm font-medium text-ink-900">
            New password
          </label>
          <div className="relative">
            <Lock aria-hidden className="absolute left-3 top-1/2 h-5 w-5 -translate-y-1/2 text-ink-500" />
            <input
              id="new-password"
              type={showPassword ? "text" : "password"}
              autoComplete="new-password"
              required
              minLength={MIN_PASSWORD_LENGTH}
              maxLength={MAX_PASSWORD_LENGTH}
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              className="field-input h-[52px] pl-10 pr-11"
            />
            <button
              type="button"
              aria-label={showPassword ? "Hide password" : "Show password"}
              aria-pressed={showPassword}
              onClick={() => setShowPassword((value) => !value)}
              className="absolute right-2 top-1/2 -translate-y-1/2 rounded-md p-1 text-ink-300 hover:text-ink-500"
            >
              {showPassword ? <EyeOff size={22} aria-hidden /> : <Eye size={22} aria-hidden />}
            </button>
          </div>
        </div>

        <div className="grid gap-2">
          <label htmlFor="confirm-password" className="text-sm font-medium text-ink-900">
            Repeat password
          </label>
          <div className="relative">
            <Lock aria-hidden className="absolute left-3 top-1/2 h-5 w-5 -translate-y-1/2 text-ink-500" />
            <input
              id="confirm-password"
              type={showPassword ? "text" : "password"}
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

        <p className="text-xs leading-5 text-ink-500">{passwordRuleLine("This link works once.")}</p>

        <Button type="submit" size="auth" disabled={saving} className="mt-1 w-full font-display text-base font-bold">
          {saving ? "Saving…" : "Set password"}
        </Button>
      </form>
    </AuthCard>
  );
}
