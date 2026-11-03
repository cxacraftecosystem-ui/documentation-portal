"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { Clock, Eye, EyeOff, Lock, Mail } from "lucide-react";

import { FieldRepoLogo } from "@/components/FieldRepoLogo";
import { GoogleSignInButton } from "@/components/GoogleSignInButton";
import { TwoStepChallengeForm } from "@/components/TwoStepChallengeForm";
import { useAuth } from "@/components/AuthProvider";
import { Button } from "@/components/ui/button";
import { GLASS_PANEL, GlassSurface } from "@/components/ui/GlassSurface";
import { useToast } from "@/components/ui/Toast";
import { accessRefusalCode } from "@/lib/accessRoster";
import {
  beginSignIn,
  callbackErrorMessage,
  configuredOidcProviders,
  loginBody,
  parseCallbackFragment,
  takePendingSignIn,
  type OidcProvider,
  type OidcProviderId
} from "@/lib/oidcSignIn";
import { isTwoFactorChallenge, type TwoFactorChallenge } from "@/lib/twoFactor";
import type { User } from "@/lib/types";

function MicrosoftMark({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 21 21" className={className} aria-hidden>
      <rect x="1" y="1" width="9" height="9" fill="#f25022" />
      <rect x="11" y="1" width="9" height="9" fill="#7fba00" />
      <rect x="1" y="11" width="9" height="9" fill="#00a4ef" />
      <rect x="11" y="11" width="9" height="9" fill="#ffb900" />
    </svg>
  );
}

function YahooMark({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" className={className} aria-hidden>
      <path
        d="M0 6.71h4.62l2.69 6.88 2.72-6.88h4.5L7.76 22.5H3.23l1.86-4.32L0 6.71zm17.62 5.05h-5.03L17.06 1.5h5.02l-4.46 10.26zm-3.03 1.4c1.55 0 2.8 1.26 2.8 2.81a2.8 2.8 0 1 1-5.61 0c0-1.55 1.26-2.8 2.81-2.8z"
        fill="#5f01d1"
      />
    </svg>
  );
}

function ProviderMark({ id, className }: { id: OidcProviderId; className?: string }) {
  return id === "MICROSOFT" ? <MicrosoftMark className={className} /> : <YahooMark className={className} />;
}

const BRAND_POINTS = [
  "Artisans, crafts, workshops, products, tools and interviews — one connected archive.",
  "Recordings transcribed and translated to English automatically.",
  "Six-tier access control; every edit audited."
];

export default function LoginPage() {
  return <LoginView />;
}

function LoginView() {
  const router = useRouter();
  const { login, loginWithGoogle, loginWithOidc, user } = useAuth();
  const { toast } = useToast();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [refusal, setRefusal] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [challenge, setChallenge] = useState<string | null>(null);
  const googleClientId = process.env.NEXT_PUBLIC_GOOGLE_CLIENT_ID;
  const [oidcProviders] = useState<OidcProvider[]>(() => configuredOidcProviders());

  useEffect(() => {
    if (user) router.replace("/dashboard");
  }, [router, user]);

  const proceed = useCallback(
    (result: User | TwoFactorChallenge) => {
      if (isTwoFactorChallenge(result)) setChallenge(result.challenge_token);
      else router.replace("/dashboard");
    },
    [router]
  );

  const onGoogleCredential = useCallback(
    async (credential: string) => {
      setError(null);
      setRefusal(null);
      try {
        proceed(await loginWithGoogle(credential));
      } catch (err) {
        const code = accessRefusalCode(err);
        const message = err instanceof Error ? err.message : "Please try again.";
        setRefusal(code);
        setError(message);
        if (!code) {
          toast({ title: "Google sign-in failed", description: message, tone: "error" });
        }
      }
    },
    [loginWithGoogle, proceed, toast]
  );

  const onGoogleLoadError = useCallback(
    () =>
      toast({
        title: "Could not load Google sign-in",
        description: "Check your connection and reload the page.",
        tone: "error",
        duration: 0
      }),
    [toast]
  );

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setLoading(true);
    setError(null);
    setRefusal(null);
    try {
      proceed(await login(email, password));
    } catch (err) {
      setRefusal(accessRefusalCode(err));
      setError(err instanceof Error ? err.message : "Couldn’t sign in. Check your connection and try again.");
    } finally {
      setLoading(false);
    }
  }

  async function startOidc(provider: OidcProvider) {
    setError(null);
    setRefusal(null);
    setLoading(true);
    try {
      window.location.assign(await beginSignIn(provider, window.location.origin, null));
    } catch {
      setLoading(false);
      setError(callbackErrorMessage(provider.id, null));
    }
  }

  useEffect(() => {
    const callback = parseCallbackFragment(window.location.hash);
    if (!callback) return;
    window.history.replaceState(null, "", window.location.pathname + window.location.search);
    const pending = takePendingSignIn(callback.state);
    if (!pending) {
      setError("Sign-in didn’t complete. Try again.");
      return;
    }
    if (callback.error || !callback.code) {
      setError(callbackErrorMessage(pending.provider, callback.error));
      return;
    }
    const code = callback.code;
    setLoading(true);
    void (async () => {
      try {
        proceed(await loginWithOidc(loginBody(pending, code)));
      } catch (err) {
        setRefusal(accessRefusalCode(err));
        setError(err instanceof Error ? err.message : callbackErrorMessage(pending.provider, null));
      } finally {
        setLoading(false);
      }
    })();
  }, [loginWithOidc, proceed]);

  return (
    <div className="grid min-h-dvh lg:grid-cols-[43%_57%]">
      <aside className="relative hidden flex-col justify-between overflow-hidden bg-purple-950 p-10 lg:flex">
        <div aria-hidden className="pointer-events-none absolute inset-0">
          <div
            className="absolute -left-24 -top-24 h-96 w-96 rounded-full opacity-70"
            style={{ background: "radial-gradient(circle, oklch(0.47 0.198 305 / 0.5), transparent 62%)" }}
          />
          <div
            className="absolute -bottom-28 -right-16 h-104 w-104 rounded-full opacity-40"
            style={{ background: "radial-gradient(circle, oklch(0.7 0.145 80 / 0.25), transparent 60%)" }}
          />
        </div>
        <Link href="/" className="relative z-10 flex items-center gap-3">
          <FieldRepoLogo className="h-12 w-12 rounded-xl shadow-md" />
          <span className="font-display text-xl font-bold tracking-tight text-white">Field Repository</span>
        </Link>
        <div className="relative z-10">
          <p className="eyebrow text-gold-300!">Living craft documentation</p>
          <h2 className="mt-3 font-display text-3xl font-bold leading-snug tracking-tight text-white">
            Every masterpiece begins with <span className="text-gold-gradient">understanding</span>.
          </h2>
          <ul className="mt-8 space-y-4">
            {BRAND_POINTS.map((point) => (
              <li key={point} className="flex gap-3 text-sm leading-relaxed text-white/75">
                <span className="mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full bg-gold-400" aria-hidden />
                {point}
              </li>
            ))}
          </ul>
        </div>
        <p className="relative z-10 text-xs text-white/40">
          Access is by invitation: an administrator adds your address to the roster. Sign in once and your request
          reaches them. Approved accounts join at the lowest tier unless an admin sets a higher one.
        </p>
      </aside>

      <main className="relative flex items-center justify-center overflow-hidden bg-bg-0 px-6 py-6 md:px-10">
        <div aria-hidden className="pointer-events-none absolute inset-0">
          <div className="absolute inset-0 grad-mesh opacity-60" />
          <div className="absolute -left-16 top-12 h-72 w-72 rounded-full bg-purple-300/30 blur-3xl" />
          <div className="absolute -right-12 bottom-8 h-80 w-80 rounded-full bg-amber-500/15 blur-3xl" />
        </div>

        <GlassSurface options={GLASS_PANEL} className="glass-card relative z-10 w-full max-w-md rounded-xl p-6 shadow-lg md:p-8">
          <div className="mb-6 flex flex-col items-center gap-2 text-center">
            <FieldRepoLogo className="h-12 w-12 rounded-xl shadow-sm lg:hidden" />
            <h1 className="font-display text-2xl font-bold text-ink-900">Welcome back</h1>
            <p className="text-sm text-ink-500">Sign in to your account</p>
          </div>

          {challenge ? (
            <TwoStepChallengeForm
              challengeToken={challenge}
              onSignedIn={() => router.replace("/dashboard")}
              onCancel={() => {
                setChallenge(null);
                setPassword("");
              }}
            />
          ) : (
            <>
            {error && refusal === "ACCESS_PENDING" ? (
              <div
                role="status"
                className="mb-4 flex gap-3 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-3 text-sm text-amber-800"
              >
                <Clock className="mt-0.5 h-5 w-5 shrink-0" aria-hidden />
                <div>
                  <p className="font-semibold">Waiting for an administrator</p>
                  <p className="mt-1 leading-6">{error}</p>
                </div>
              </div>
            ) : error ? (
              <div role="alert" className="mb-4 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600">
                {error}
              </div>
            ) : null}

            <form onSubmit={submit} className="grid gap-3">
              <div className="grid gap-2">
                <label htmlFor="email" className="text-sm font-medium text-ink-900">
                  Email address
                </label>
                <div className="relative">
                  <Mail aria-hidden className="absolute left-3 top-1/2 h-5 w-5 -translate-y-1/2 text-ink-500" />
                  <input
                    id="email"
                    type="email"
                    autoComplete="email"
                    required
                    placeholder="Enter your email"
                    value={email}
                    onChange={(event) => setEmail(event.target.value)}
                    className="field-input h-[52px] pl-10"
                  />
                </div>
              </div>

              <div className="grid gap-2">
                <div className="flex items-baseline justify-between gap-3">
                  <label htmlFor="password" className="text-sm font-medium text-ink-900">
                    Password
                  </label>
                  <Link href="/forgot-password" className="text-sm font-medium text-purple-700 hover:underline dark:text-purple-300">
                    Forgot your password?
                  </Link>
                </div>
                <div className="relative">
                  <Lock aria-hidden className="absolute left-3 top-1/2 h-5 w-5 -translate-y-1/2 text-ink-500" />
                  <input
                    id="password"
                    type={showPassword ? "text" : "password"}
                    autoComplete="current-password"
                    required
                    minLength={8}
                    placeholder="Enter your password"
                    value={password}
                    onChange={(event) => setPassword(event.target.value)}
                    className="field-input h-[52px] pl-10 pr-11"
                  />
                  <button
                    type="button"
                    aria-label={showPassword ? "Hide password" : "Show password"}
                    aria-pressed={showPassword}
                    onClick={() => setShowPassword((value) => !value)}
                    className="absolute right-2 top-1/2 -translate-y-1/2 grid place-items-center rounded-md p-1 text-ink-500 hover:text-ink-700 pointer-coarse:right-0 pointer-coarse:h-11 pointer-coarse:w-11"
                  >
                    {showPassword ? <EyeOff size={22} aria-hidden /> : <Eye size={22} aria-hidden />}
                  </button>
                </div>
              </div>

              <Button type="submit" size="auth" disabled={loading} className="mt-1 w-full font-display text-base font-bold">
                {loading ? "Signing in…" : "Sign in"}
              </Button>
            </form>

            <div className="my-4 flex items-center gap-3">
              <span className="h-px flex-1 bg-line-200" />
              <span className="text-sm text-ink-500">OR</span>
              <span className="h-px flex-1 bg-line-200" />
            </div>

            <div className="grid gap-2.5">
              {googleClientId ? (
                <GoogleSignInButton
                  clientId={googleClientId}
                  onCredential={(credential) => void onGoogleCredential(credential)}
                  onLoadError={onGoogleLoadError}
                />
              ) : (
                <div className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-500">
                  Google sign-in is unavailable. Use email and password.
                </div>
              )}
              {oidcProviders.map((provider) => (
                <Button
                  key={provider.id}
                  type="button"
                  variant="provider"
                  size="auth"
                  disabled={loading}
                  onClick={() => void startOidc(provider)}
                  className="w-full min-w-0"
                >
                  <ProviderMark id={provider.id} className="h-5 w-5 shrink-0" />
                  <span className="min-w-0 truncate">Continue with {provider.label}</span>
                </Button>
              ))}
            </div>

            <p className="mt-4 text-center text-xs leading-5 text-ink-500 lg:hidden">
              Access is by invitation.
            </p>
            </>
          )}

          <p className="mt-4 text-center text-sm text-ink-500">
            No account yet?{" "}
            <Link href="/" className="font-medium text-purple-700 dark:text-purple-300 hover:underline">
              See what Field Repository does
            </Link>
          </p>
        </GlassSurface>
      </main>
    </div>
  );
}
