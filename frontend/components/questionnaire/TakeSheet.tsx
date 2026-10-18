"use client";

import { Download, Mic, Upload } from "lucide-react";
import { useRef } from "react";

import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { TranscriptStatus } from "@/components/questionnaire/TranscriptStatus";
import { AudioPlayer } from "@/components/ui/AudioPlayer";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { questionnaireApi, type Recording } from "@/lib/questionnaireApi";
import { takeRowLabel } from "@/lib/questionnairePrompts";
import { qs } from "@/lib/strings/qs";

/** A take row's words: position, reason, duration, researcher, date and setting — never a file name. */
export function takeRow(recording: Recording, artisanId: string | null): string {
  const subject = artisanId ? recording.subjects.find((s) => s.artisanId === artisanId) : recording.subjects[0];
  return takeRowLabel({
    position: subject?.position ?? null,
    repeatReason: subject?.repeatReason ?? null,
    durationMs: recording.durationMs,
    researcher: recording.recordedBy.name,
    capturedAt: recording.capturedAt,
    rosterCount: recording.rosterCount
  });
}

/**
 * The take sheet (DESIGN §4.3): every take of one section for one artisan (or the participants),
 * with play, download (the server's `downloadName`), the transcript state, and Record / Upload.
 */
export function TakeSheet({
  open,
  title,
  recordings,
  artisanId,
  paused,
  canRecord,
  onClose,
  onRecord,
  onUpload,
  onRemove,
  onRecordConsent
}: {
  open: boolean;
  title: string;
  recordings: Recording[];
  artisanId: string | null;
  paused: boolean;
  canRecord: boolean;
  onClose: () => void;
  onRecord: () => void;
  onUpload: (file: File) => void;
  onRemove?: (recording: Recording) => void;
  onRecordConsent?: (recording: Recording) => void;
}) {
  const fileRef = useRef<HTMLInputElement | null>(null);
  const live = recordings.filter((r) => !r.removedAt).sort((a, b) => a.capturedAt.localeCompare(b.capturedAt));
  return (
    <FieldDialog open={open} onClose={onClose} title={title}>
      <ul className="grid gap-3" data-take-sheet>
        {live.map((r) => (
          <li key={r.id} className="rounded-md border border-line-200 p-3" data-take-row={r.id}>
            <p className="text-sm text-ink-900">{takeRow(r, artisanId)}</p>
            <div className="mt-1 flex flex-wrap items-center gap-3">
              <TranscriptStatus
                recording={r}
                paused={paused}
                onRetry={() => void questionnaireApi.transcribe(r.id)}
                onRecordConsent={onRecordConsent ? () => onRecordConsent(r) : undefined}
              />
              {r.filing === "NEEDS_DECISION" ? <span className="text-xs font-medium text-amber-800">{qs("needsDecision")}</span> : null}
              {r.estimated ? <span className="text-xs text-ink-500">{qs("estimatedDate")}</span> : null}
            </div>
            <AudioPlayer src={questionnaireApi.downloadHref(r.id)} className="mt-2" />
            <ButtonRow size="sm" className="mt-2" align="start" destructive={onRemove ? (
              <button type="button" className="field-button-secondary field-danger" data-danger onClick={() => onRemove(r)}>
                {qs("remove")}
              </button>
            ) : undefined}>
              <a className="field-button-secondary" href={questionnaireApi.downloadHref(r.id)} download={r.downloadName}>
                <Download className="h-4 w-4" aria-hidden />
                {qs("download")}
              </a>
            </ButtonRow>
          </li>
        ))}
      </ul>
      {canRecord ? (
        <ButtonRow className="mt-4">
          <button type="button" className="field-button" onClick={onRecord}>
            <Mic className="h-4 w-4" aria-hidden />
            {qs("recordTake")}
          </button>
          <button type="button" className="field-button-secondary" onClick={() => fileRef.current?.click()}>
            <Upload className="h-4 w-4" aria-hidden />
            {qs("uploadFile")}
          </button>
        </ButtonRow>
      ) : null}
      <input
        ref={fileRef}
        type="file"
        accept="audio/*"
        className="sr-only"
        tabIndex={-1}
        aria-hidden
        onChange={(event) => {
          const file = event.target.files?.[0];
          event.target.value = "";
          if (file) onUpload(file);
        }}
      />
    </FieldDialog>
  );
}
