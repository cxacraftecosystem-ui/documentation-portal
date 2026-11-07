"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { KeyRound, Lock, SearchX, ServerOff, TriangleAlert, WifiOff } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { DynamicIslandNav } from "@/components/DynamicIslandNav";
import { ErrorBrand, ErrorPanel, type ErrorTone } from "@/components/ErrorPanel";
import { errorCopy, errorKindOf, retryDelaySeconds, signInHref, type ErrorKind } from "@/lib/errorKind";

export function DeadEndFrame({ nav, children }: { nav: boolean; children: ReactNode }) {
  const { user, loading } = useAuth();

  const brand = !nav || (!loading && !user);

  return (
    <div className="min-h-screen bg-bg-0">
      {nav ? (
        <>
          <a
            href="#main-content"
            className="sr-only left-3 top-3 z-60 rounded-md bg-purple-700 px-3 py-2 text-sm font-medium text-white focus:not-sr-only focus:fixed"
          >
            Skip to content
          </a>
          <DynamicIslandNav />
        </>
      ) : null}
      <main id="main-content" tabIndex={-1} className="mx-auto max-w-7xl px-4 pb-12 pt-24">
        {brand ? <ErrorBrand /> : null}
        {children}
      </main>
    </div>
  );
}


const ICON: Record<ErrorKind, ReactNode> = {
  "not-found": <SearchX className="h-5 w-5" aria-hidden />,
  forbidden: <Lock className="h-5 w-5" aria-hidden />,
  session: <KeyRound className="h-5 w-5" aria-hidden />,
  server: <TriangleAlert className="h-5 w-5" aria-hidden />,
  offline: <WifiOff className="h-5 w-5" aria-hidden />,
  unavailable: <ServerOff className="h-5 w-5" aria-hidden />
};

const TONE: Record<ErrorKind, ErrorTone> = {
  "not-found": "notice",
  forbidden: "notice",
  session: "notice",
  server: "alert",
  offline: "quiet",
  unavailable: "quiet"
};

function useHere(): string | null {
  const [here, setHere] = useState<string | null>(null);
  useEffect(() => {
    setHere(`${window.location.pathname}${window.location.search}${window.location.hash}`);
  }, []);
  return here;
}

function useCountdown(active: boolean, onDone: () => void): number | null {
  const [round, setRound] = useState(0);
  const [left, setLeft] = useState<number | null>(null);
  const done = useRef(onDone);
  useEffect(() => {
    done.current = onDone;
  });
  useEffect(() => {
    if (!active) {
      setLeft(null);
      return;
    }
    const total = retryDelaySeconds(round);
    const started = Date.now();
    setLeft(total);
    const timer = window.setInterval(() => {
      const remaining = total - Math.floor((Date.now() - started) / 1000);
      if (remaining > 0) {
        setLeft(remaining);
        return;
      }
      window.clearInterval(timer);
      setRound((value) => value + 1);
      done.current();
    }, 250);
    return () => window.clearInterval(timer);
  }, [active, round]);
  return left;
}

export function ErrorState({
  kind,
  noun,
  requestHref,
  reference,
  onRetry,
  next
}: {
  kind: ErrorKind;
  noun?: string;
  requestHref?: string | null;
  reference?: string | null;
  onRetry?: () => void;
  next?: string | null;
}) {
  const { user, loading } = useAuth();
  const router = useRouter();
  const here = useHere();
  const home = loading || user ? "/dashboard" : "/";

  const retry = useCallback(() => {
    if (onRetry) onRetry();
    else window.location.reload();
  }, [onRetry]);

  useEffect(() => {
    if (kind !== "offline") return;
    window.addEventListener("online", retry);
    return () => window.removeEventListener("online", retry);
  }, [kind, retry]);

  const seconds = useCountdown(kind === "unavailable", retry);

  const goBack = () => {
    if (window.history.length > 1) router.back();
    else router.push(home);
  };

  const copy = errorCopy(kind, { noun, canRequest: Boolean(requestHref) });

  let actions: ReactNode;
  switch (kind) {
    case "not-found":
      actions = (
        <>
          <button type="button" onClick={goBack} className="field-button-secondary">
            Go back
          </button>
          <Link href={home} className="field-button">
            Home
          </Link>
        </>
      );
      break;
    case "forbidden":
      actions = requestHref ? (
        <Link href={requestHref} className="field-button">
          Request access
        </Link>
      ) : (
        <Link href={home} className="field-button">
          Home
        </Link>
      );
      break;
    case "session":
      actions = (
        <Link href={signInHref(next ?? here)} className="field-button">
          Sign in again
        </Link>
      );
      break;
    case "server":
      actions = (
        <>
          <button type="button" onClick={retry} className="field-button">
            Try again
          </button>
          <Link href={home} className="field-button-secondary">
            Home
          </Link>
        </>
      );
      break;
    default:
      actions = (
        <button type="button" onClick={retry} className="field-button">
          Try again
        </button>
      );
  }

  return (
    <ErrorPanel
      screen={kind}
      icon={ICON[kind]}
      tone={TONE[kind]}
      title={copy.title}
      line={copy.line}
      reference={kind === "server" ? reference : null}
      status={
        kind === "unavailable" && seconds !== null ? (
          <span data-testid="error-countdown">
            Retrying in <span className="font-mono tabular-nums">{seconds}s</span>
          </span>
        ) : null
      }
    >
      {actions}
    </ErrorPanel>
  );
}

export function ApiErrorState({
  error,
  noun,
  requestHref,
  onRetry
}: {
  error: unknown;
  noun?: string;
  requestHref?: string | null;
  onRetry?: () => void;
}) {
  return <ErrorState kind={errorKindOf(error)} noun={noun} requestHref={requestHref} onRetry={onRetry} />;
}
