"use client";

import { Database, Loader2, Lock } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState, type ReactNode } from "react";

import { DownloadsTray } from "@/components/dataExplorer/DownloadsTray";
import { useDownloads } from "@/components/dataExplorer/useDownloads";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { ApiError, apiFetch } from "@/lib/api";

export type GoMedia = { url: string; fileName?: string | null; mediaId?: string | null };
export type GoOpen = { kind: string; id: string; href: string };
export type GoTranscript = { mediaId: string; format: string; apiPath: string; openHref?: string | null };

export const GO_UNAVAILABLE = "This isn't available to you. It may have been removed, or you may not have access.";

export function goMediaPath(id: string): string {
  return `/data/go/media/${encodeURIComponent(id)}/download?as=json`;
}

export function goOpenPath(kind: string, id: string): string {
  return `/data/go/open/${encodeURIComponent(kind)}/${encodeURIComponent(id)}`;
}

export function goTranscriptPath(id: string, format: string): string {
  return `/data/go/transcript/${encodeURIComponent(id)}/${encodeURIComponent(format)}`;
}

export function safeInternalHref(href: string | null | undefined): string | null {
  if (!href || !href.startsWith("/") || href.startsWith("//")) return null;
  return href;
}

export function goFailure(error: unknown): string {
  if (error instanceof ApiError && error.status >= 400 && error.status < 405) return GO_UNAVAILABLE;
  return "That link didn't answer. Try again in a moment.";
}

function Frame({ children }: { children: ReactNode }) {
  return <section className="panel mx-auto grid max-w-xl justify-items-center gap-3 px-6 py-10 text-center">{children}</section>;
}

function Waiting({ label }: { label: string }) {
  return (
    <Frame>
      <Loader2 className="h-6 w-6 animate-spin text-purple-700 dark:text-purple-300" aria-hidden />
      <p className="text-sm text-ink-700" role="status">
        {label}
      </p>
    </Frame>
  );
}

function Unavailable({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <Frame>
      <span className="grid h-12 w-12 place-items-center rounded-full bg-purple-100 text-purple-700 dark:bg-purple-950/50 dark:text-purple-200" aria-hidden>
        <Lock className="h-5 w-5" />
      </span>
      <h2 className="font-display text-lg font-bold text-ink-900">Not available</h2>
      <p className="text-sm text-ink-700" role="alert">
        {message}
      </p>
      <ActionGroup>
        {onRetry ? (
          <button type="button" className="field-button-secondary" onClick={onRetry}>
            Try again
          </button>
        ) : null}
        <Link href="/data" className="field-button">
          <Database className="h-4 w-4" aria-hidden />
          Open Data
        </Link>
      </ActionGroup>
    </Frame>
  );
}

export function GoMediaDownload({ id }: { id: string }) {
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let cancelled = false;
    apiFetch<GoMedia>(goMediaPath(id))
      .then((answer) => {
        if (cancelled) return;
        if (!answer?.url) setError(GO_UNAVAILABLE);
        else window.location.assign(answer.url);
      })
      .catch((failure) => {
        if (!cancelled) setError(goFailure(failure));
      });
    return () => {
      cancelled = true;
    };
  }, [id, attempt]);
  if (error)
    return (
      <Unavailable
        message={error}
        onRetry={() => {
          setError(null);
          setAttempt((value) => value + 1);
        }}
      />
    );
  return <Waiting label="Getting your file…" />;
}

export function GoOpenRecord({ kind, id }: { kind: string; id: string }) {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let cancelled = false;
    apiFetch<GoOpen>(goOpenPath(kind, id))
      .then((answer) => {
        if (cancelled) return;
        const href = safeInternalHref(answer?.href);
        if (href) router.replace(href);
        else setError(GO_UNAVAILABLE);
      })
      .catch((failure) => {
        if (!cancelled) setError(goFailure(failure));
      });
    return () => {
      cancelled = true;
    };
  }, [kind, id, router]);
  if (error) return <Unavailable message={error} />;
  return <Waiting label="Opening the record…" />;
}

export function GoTranscriptFile({ id, format }: { id: string; format: string }) {
  const downloads = useDownloads();
  const asked = useRef(false);
  const [answer, setAnswer] = useState<{ data: GoTranscript | null; error: string | null } | null>(null);
  const { enqueue } = downloads;
  useEffect(() => {
    let cancelled = false;
    apiFetch<GoTranscript>(goTranscriptPath(id, format))
      .then((data) => {
        if (!cancelled) setAnswer({ data, error: data?.apiPath ? null : GO_UNAVAILABLE });
      })
      .catch((failure) => {
        if (!cancelled) setAnswer({ data: null, error: goFailure(failure) });
      });
    return () => {
      cancelled = true;
    };
  }, [id, format]);
  useEffect(() => {
    const data = answer?.data;
    if (!data?.apiPath || asked.current) return;
    asked.current = true;
    enqueue({ kind: "transcript", label: `Transcript (.${data.format || format})`, url: `/api${data.apiPath}`, mediaId: data.mediaId });
  }, [answer, enqueue, format]);
  if (!answer) return <Waiting label="Getting the transcript…" />;
  if (answer.error) return <Unavailable message={answer.error} />;
  const open = safeInternalHref(answer.data?.openHref);
  return (
    <>
      <Frame>
        <h2 className="font-display text-lg font-bold text-ink-900">Transcript (.{answer.data?.format || format})</h2>
        <p className="text-sm text-ink-700">It saves by itself. The downloads list shows how it went.</p>
        <ActionGroup>
          {open ? (
            <Link href={open} className="field-button-secondary">
              Open the recording
            </Link>
          ) : null}
          <Link href="/data" className="field-button">
            <Database className="h-4 w-4" aria-hidden />
            Open Data
          </Link>
        </ActionGroup>
      </Frame>
      <DownloadsTray downloads={downloads} />
    </>
  );
}
