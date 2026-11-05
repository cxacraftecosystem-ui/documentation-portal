"use client";

import { Database, Loader2, Lock } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState, type ReactNode } from "react";

import { useAuth } from "@/components/AuthProvider";
import { DownloadsTray } from "@/components/dataExplorer/DownloadsTray";
import { useDownloads } from "@/components/dataExplorer/useDownloads";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { apiFetch } from "@/lib/api";
import {
  GO_MISSING as MISSING,
  goFailure,
  goMediaPath,
  goOpenPath,
  goTranscriptPath,
  safeInternalHref,
  type GoMedia,
  type GoOpen,
  type GoProblem,
  type GoTranscript
} from "@/lib/dataExplorer/goLinks";
import { canDownloadDataset } from "@/lib/permissions";

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

function Unavailable({ problem, onRetry }: { problem: GoProblem; onRetry?: () => void }) {
  const { user } = useAuth();
  return (
    <Frame>
      <span className="grid h-12 w-12 place-items-center rounded-full bg-purple-100 text-purple-700 dark:bg-purple-950/50 dark:text-purple-200" aria-hidden>
        <Lock className="h-5 w-5" />
      </span>
      <h2 className="font-display text-lg font-bold text-ink-900">{problem.title}</h2>
      <p className="text-sm text-ink-700" role="alert">
        {problem.message}
      </p>
      <ActionGroup>
        {onRetry && problem.retry ? (
          <button type="button" className="field-button-secondary" onClick={onRetry}>
            Try again
          </button>
        ) : null}
        <HomeLink user={user} />
      </ActionGroup>
    </Frame>
  );
}

function HomeLink({ user }: { user: ReturnType<typeof useAuth>["user"] }) {
  if (canDownloadDataset(user))
    return (
      <Link href="/data" className="field-button">
        <Database className="h-4 w-4" aria-hidden />
        Open Data
      </Link>
    );
  return (
    <Link href="/dashboard" className="field-button">
      Go to dashboard
    </Link>
  );
}

export function GoMediaDownload({ id }: { id: string }) {
  const [error, setError] = useState<GoProblem | null>(null);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let cancelled = false;
    apiFetch<GoMedia>(goMediaPath(id))
      .then((answer) => {
        if (cancelled) return;
        if (!answer?.url) setError(MISSING);
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
        problem={error}
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
  const [error, setError] = useState<GoProblem | null>(null);
  useEffect(() => {
    let cancelled = false;
    apiFetch<GoOpen>(goOpenPath(kind, id))
      .then((answer) => {
        if (cancelled) return;
        const href = safeInternalHref(answer?.href);
        if (href) router.replace(href);
        else setError(MISSING);
      })
      .catch((failure) => {
        if (!cancelled) setError(goFailure(failure));
      });
    return () => {
      cancelled = true;
    };
  }, [kind, id, router]);
  if (error) return <Unavailable problem={error} />;
  return <Waiting label="Opening the record…" />;
}

export function GoTranscriptFile({ id, format }: { id: string; format: string }) {
  const { user } = useAuth();
  const downloads = useDownloads();
  const asked = useRef(false);
  const [answer, setAnswer] = useState<{ data: GoTranscript | null; error: GoProblem | null } | null>(null);
  const { enqueue } = downloads;
  useEffect(() => {
    let cancelled = false;
    apiFetch<GoTranscript>(goTranscriptPath(id, format))
      .then((data) => {
        if (!cancelled) setAnswer({ data, error: data?.apiPath ? null : MISSING });
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
  if (answer.error) return <Unavailable problem={answer.error} />;
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
          <HomeLink user={user} />
        </ActionGroup>
      </Frame>
      <DownloadsTray downloads={downloads} />
    </>
  );
}
