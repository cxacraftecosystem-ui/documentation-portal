"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { BookOpenCheck, LockKeyhole, Mail, MapPinned, Sparkle } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";

declare global {
  interface Window {
    google?: {
      accounts: {
        id: {
          initialize: (config: { client_id: string; callback: (response: { credential: string }) => void }) => void;
          renderButton: (element: HTMLElement, options: Record<string, string | boolean | number>) => void;
        };
      };
    };
  }
}

export default function LoginPage() {
  const router = useRouter();
  const { login, loginWithGoogle, user } = useAuth();
  const [email, setEmail] = useState("admin@example.com");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const googleButton = useRef<HTMLDivElement | null>(null);
  const googleClientId = process.env.NEXT_PUBLIC_GOOGLE_CLIENT_ID;

  useEffect(() => {
    if (user) router.replace("/dashboard");
  }, [router, user]);

  useEffect(() => {
    if (!googleClientId || !googleButton.current) return;
    const initialize = () => {
      window.google?.accounts.id.initialize({
        client_id: googleClientId,
        callback: async (response) => {
          try {
            await loginWithGoogle(response.credential);
            router.replace("/dashboard");
          } catch (err) {
            setError(err instanceof Error ? err.message : "Google login failed");
          }
        }
      });
      if (googleButton.current) {
        googleButton.current.replaceChildren();
        window.google?.accounts.id.renderButton(googleButton.current, {
          theme: "outline",
          size: "large",
          width: 320,
          text: "continue_with"
        });
      }
    };

    if (window.google) {
      initialize();
      return;
    }
    const script = document.createElement("script");
    script.src = "https://accounts.google.com/gsi/client";
    script.async = true;
    script.defer = true;
    script.onload = initialize;
    document.body.appendChild(script);
  }, [googleClientId, loginWithGoogle, router]);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setLoading(true);
    setError(null);
    try {
      await login(email, password);
      router.replace("/dashboard");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Login failed");
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className="grid min-h-screen bg-field-50 px-4 py-8 lg:grid-cols-[1.05fr_0.95fr] lg:px-8">
      <section className="flex items-center justify-center">
        <div className="w-full max-w-md">
          <div className="mb-6 flex items-center gap-2 text-ink">
            <span className="grid h-9 w-9 place-items-center rounded-full bg-field-200 text-field-600">
              <Sparkle className="h-4 w-4" aria-hidden />
            </span>
            <span className="font-serif text-2xl font-normal">Field Repository</span>
          </div>
      <section className="panel w-full p-6">
        <div className="mb-6">
          <h1 className="display-title text-3xl">Sign in</h1>
          <p className="mt-2 text-sm leading-6 text-ink-muted">Document field visits, artisan knowledge, craft practices, objects, tools, conversations and locations in one shared archive.</p>
        </div>
        {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
        <form onSubmit={submit} className="grid gap-3">
          <label className="grid gap-1">
            <span className="field-label">Email</span>
            <span className="relative">
              <Mail className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-soft" aria-hidden />
              <input className="field-input pl-9" type="email" value={email} onChange={(event) => setEmail(event.target.value)} required />
            </span>
          </label>
          <label className="grid gap-1">
            <span className="field-label">Password</span>
            <span className="relative">
              <LockKeyhole className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-soft" aria-hidden />
              <input
                className="field-input pl-9"
                type="password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                required
                minLength={8}
              />
            </span>
          </label>
          <button className="field-button mt-2" disabled={loading}>
            {loading ? "Signing in..." : "Login"}
          </button>
        </form>
        <div className="my-5 flex items-center gap-3 text-xs uppercase text-ink-soft">
          <span className="h-px flex-1 bg-[#e6dfd8]" />
          Google OAuth
          <span className="h-px flex-1 bg-[#e6dfd8]" />
        </div>
        {googleClientId ? (
          <div className="flex justify-center" ref={googleButton} />
        ) : (
          <div className="rounded-md border border-[#e6dfd8] bg-field-100 px-3 py-2 text-sm text-ink-muted">
            Add NEXT_PUBLIC_GOOGLE_CLIENT_ID and GOOGLE_CLIENT_ID to enable Google sign-in.
          </div>
        )}
      </section>
        </div>
      </section>
      <aside className="hidden items-center justify-center lg:flex">
        <div className="surface-dark w-full max-w-xl p-8">
          <div className="mb-8 flex items-center gap-3">
            <div className="grid h-11 w-11 place-items-center rounded-xl bg-[#252320] text-field-400">
              <BookOpenCheck className="h-5 w-5" aria-hidden />
            </div>
            <div>
              <p className="text-sm text-[#a09d96]">Field documentation archive</p>
              <h2 className="font-serif text-3xl font-normal text-field-50">A careful place for living craft knowledge.</h2>
            </div>
          </div>
          <div className="grid gap-3">
            {[
              "Create consistent notes for artisans, crafts, workshops, products and tools.",
              "Keep photographs, videos, recordings, transcripts and field locations together.",
              "Review and revisit entries as the documentation grows."
            ].map((item) => (
              <div key={item} className="flex gap-3 rounded-xl bg-[#252320] p-4 text-sm leading-6 text-[#d8d2c8]">
                <MapPinned className="mt-0.5 h-4 w-4 shrink-0 text-field-400" aria-hidden />
                {item}
              </div>
            ))}
          </div>
        </div>
      </aside>
    </main>
  );
}
