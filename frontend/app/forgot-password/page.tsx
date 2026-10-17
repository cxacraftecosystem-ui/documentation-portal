"use client";

/**
 * /forgot-password — ask for a one-time password link by e-mail. PUBLIC, outside the protected tree.
 *
 * Ported from cxa-cms's `app/studio/login/forgot` (the reference for this feature). ONE EMAIL BOX,
 * ONE BUTTON, ONE ANSWER: `POST /auth/forgot-password` returns the same sentence whether or not the
 * address has an account, and this page prints it verbatim — there is deliberately no branch that
 * could tell a stranger whether an address is known. The only failures it can show are about the REQUEST:
 * a malformed address (422), too many requests from this connection (429, with the wait in words)
 * and a server it could not reach.
 *
 * A signed-in reader is sent to Settings, where a password is changed with the current one.
 */

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { ArrowLeft, CircleCheck, Mail } from "lucide-react";

import { AuthCard } from "@/components/AuthCard";
import { useAuth } from "@/components/AuthProvider";
import { Button } from "@/components/ui/button";
import { ApiError } from "@/lib/api";
import { passwordFailureSentence } from "@/lib/passwordChange";
import { describeRetryAfter, requestPasswordLinkByEmail } from "@/lib/signIn";

export default function ForgotPasswordPage() {
  const router = useRouter();
  const { user } = useAuth();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sent, setSent] = useState<string | null>(null);
  const emailRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    if (user) router.replace("/settings");
  }, [router, user]);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    // FIRST, before any await: React nulls `currentTarget` across one.
    const data = new FormData(event.currentTarget);
    event.preventDefault();
    if (pending) return;
    const email = String(data.get("email") ?? "").trim();
    if (!email) {
      setError("Enter your email address.");
      emailRef.current?.focus();
      return;
    }
    setPending(true);
    setError(null);
    setSent(null);
    try {
      setSent(await requestPasswordLinkByEmail(email));
    } catch (err) {
      if (err instanceof ApiError && err.status === 429) {
        const wait = describeRetryAfter(err.headers?.get("retry-after"));
        setError(wait && !err.message.includes("Try again in") ? `${err.message} ${wait}` : err.message);
      } else {
        setError(passwordFailureSentence(err, "That request did not work. Check the address and try again."));
        emailRef.current?.focus();
      }
    } finally {
      setPending(false);
    }
  }

  return (
    <AuthCard
      title="Forgot your password?"
      subtitle="We’ll email you a reset link. It lasts 2 hours."
    >
      <form onSubmit={submit} noValidate className="grid gap-3">
        <div role="alert" aria-live="assertive">
          {error ? (
            <p className="rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm leading-6 text-error-600">{error}</p>
          ) : null}
        </div>
        <div role="status" aria-live="polite">
          {sent ? (
            <p className="flex items-start gap-2 rounded-md border border-success-600/25 bg-success-100 px-3 py-3 text-sm leading-6 text-success-600">
              <CircleCheck aria-hidden className="mt-0.5 h-4 w-4 shrink-0" />
              <span>{sent}</span>
            </p>
          ) : null}
        </div>

        <div className="grid gap-2">
          <label htmlFor="forgot-email" className="text-sm font-medium text-ink-900">
            Email address
          </label>
          <div className="relative">
            <Mail aria-hidden className="absolute left-3 top-1/2 h-5 w-5 -translate-y-1/2 text-ink-500" />
            <input
              id="forgot-email"
              ref={emailRef}
              name="email"
              type="email"
              autoComplete="username"
              autoCapitalize="none"
              autoCorrect="off"
              spellCheck={false}
              inputMode="email"
              maxLength={320}
              disabled={pending}
              placeholder="Enter your email"
              className="field-input h-[52px] pl-10"
            />
          </div>
        </div>

        <Button type="submit" size="auth" disabled={pending} className="mt-1 w-full font-display text-base font-bold">
          {pending ? "Sending…" : "Email me a link"}
        </Button>
      </form>

      <p className="mt-4 text-xs leading-5 text-ink-500">
        If two-step verification is on for your account, you will still need your authenticator app to sign in afterwards.
      </p>
      <p className="mt-2 text-xs leading-5 text-ink-500">
        No email? Ask an administrator — they can make you a link to set a new password.
      </p>
      <p className="mt-4 text-sm">
        <Link href="/login" className="inline-flex items-center gap-1.5 font-medium text-purple-700 hover:underline dark:text-purple-300">
          <ArrowLeft aria-hidden className="h-4 w-4 shrink-0" />
          Back to sign in
        </Link>
      </p>
    </AuthCard>
  );
}
