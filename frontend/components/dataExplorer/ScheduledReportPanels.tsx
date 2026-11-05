"use client";

import { BellOff, Download, FileSpreadsheet } from "lucide-react";
import Link from "next/link";
import { useEffect, useState } from "react";

import { Problem } from "@/components/dataExplorer/explorerUi";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { ApiError } from "@/lib/api";
import {
  confirmUnsubscribe,
  fetchScheduleFile,
  lookupUnsubscribe,
  sentWords,
  sizeWords,
  type ScheduleFileLink,
  type UnsubscribeInfo
} from "@/lib/dataExplorer/reportSchedules";

function refusal(error: unknown, fallback: string): string {
  if (error instanceof ApiError && (error.status === 404 || error.status === 403) && error.message.trim()) return error.message;
  return fallback;
}

export function ScheduledReportDownload({ scheduleId }: { scheduleId: string }) {
  const [file, setFile] = useState<ScheduleFileLink | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let live = true;
    fetchScheduleFile(scheduleId)
      .then((answer) => {
        if (live) setFile(answer);
      })
      .catch((err) => {
        if (live) setError(refusal(err, "The report didn't open. Check your connection and try again."));
      });
    return () => {
      live = false;
    };
  }, [scheduleId, attempt]);

  const retry = () => {
    setError(null);
    setAttempt((value) => value + 1);
  };

  if (error) return <Problem onRetry={retry}>{error}</Problem>;
  if (!file) return <p className="text-sm text-ink-500">Opening the report…</p>;
  return (
    <div className="panel grid gap-4 p-4">
      <div className="flex items-start gap-3">
        <FileSpreadsheet className="mt-0.5 h-5 w-5 shrink-0 text-purple-700 dark:text-purple-300" aria-hidden />
        <div className="min-w-0">
          <p className="font-display text-sm font-bold text-ink-900">{file.viewName || file.fileName}</p>
          <p className="truncate text-xs text-ink-500">{file.fileName}</p>
          <p className="text-xs text-ink-500">{[sentWords(file.sentAt), sizeWords(file.sizeBytes)].filter(Boolean).join(" · ")}</p>
        </div>
      </div>
      <ActionGroup align="start">
        <a className="field-button" href={file.url}>
          <Download className="h-4 w-4" aria-hidden />
          Download
        </a>
      </ActionGroup>
    </div>
  );
}

export function ReportUnsubscribe({ token }: { token: string }) {
  const [info, setInfo] = useState<UnsubscribeInfo | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!token) return;
    let live = true;
    lookupUnsubscribe(token)
      .then((answer) => {
        if (live) setInfo(answer);
      })
      .catch((err) => {
        if (live) setError(refusal(err, "This link didn't open. Check your connection and try again."));
      });
    return () => {
      live = false;
    };
  }, [token]);

  const leave = async () => {
    setBusy(true);
    setError(null);
    try {
      setInfo(await confirmUnsubscribe(token));
    } catch (err) {
      setError(refusal(err, "That didn't go through. Try again."));
    } finally {
      setBusy(false);
    }
  };

  if (!token) return <Problem>This link is incomplete. Open the whole link from the e-mail.</Problem>;
  if (error && !info) return <Problem>{error}</Problem>;
  if (!info) return <p className="text-center text-sm text-ink-500">Checking this link…</p>;
  return (
    <div className="grid gap-4">
      <div className="rounded-md border border-line-200 bg-card p-3">
        <p className="font-display text-sm font-bold text-ink-900">{info.viewName || "Saved view"}</p>
        <p className="text-xs text-ink-500">{info.label.replace(/^every /, "Every ")}</p>
      </div>
      {error ? <Problem>{error}</Problem> : null}
      {info.paused ? (
        <>
          <p className="text-center text-sm text-ink-700" role="status">
            You won&apos;t get this report any more.
          </p>
          <ActionGroup>
            <Link className="field-button-secondary" href="/data">
              Open View Data
            </Link>
          </ActionGroup>
        </>
      ) : (
        <ActionGroup fill>
          <button type="button" className="field-button" disabled={busy} onClick={() => void leave()}>
            <BellOff className="h-4 w-4" aria-hidden />
            {busy ? "Unsubscribing…" : "Unsubscribe"}
          </button>
        </ActionGroup>
      )}
    </div>
  );
}
