"use client";

import { Download, Mic, Upload } from "lucide-react";
import { useRef, useState } from "react";

import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { TranscriptStatus } from "@/components/questionnaire/TranscriptStatus";
import { AudioPlayer } from "@/components/ui/AudioPlayer";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { SegmentedChoice } from "@/components/ui/SegmentedChoice";
import { questionnaireApi, type Recording, type RecordingEvent, type SectionOverride } from "@/lib/questionnaire/api";
import { shortDate, takeRowLabel } from "@/lib/questionnaire/prompts";
import { qs } from "@/lib/questionnaire/qs";

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

/** A take whose transcript is in: its words can be offered as answers (B22). */
export function canOfferAnswers(recording: Recording): boolean {
  const status = recording.transcript.status;
  return !recording.removedAt && Boolean(recording.transcript.text) && (status === "COMPLETED" || status === "SKIPPED_DUPLICATE");
}

const OVERRIDE_KEY: Record<SectionOverride | "", "overrideNone" | "stateMarkedComplete" | "stateNeedsReview" | "stateNeedsRedo"> = {
  "": "overrideNone",
  COMPLETED: "stateMarkedComplete",
  NEEDS_REVIEW: "stateNeedsReview",
  NEEDS_REDO: "stateNeedsRedo"
};

/**
 * The take sheet (DESIGN §4.3, B18): every take of one section for one artisan (or the participants),
 * with play, download (the server's `downloadName`), the transcript state and Retry, "Use as answer",
 * Make primary, the take's history (B40), the removed takes with Restore, a decision for a take that
 * needs one, the admin's section override in three states (owner, amendment 2), and Record / Upload.
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
  onRecordConsent,
  onUseAsAnswer,
  sessionId,
  override = null,
  onOverride,
  onChanged
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
  onUseAsAnswer?: (recording: Recording) => void;
  /** For "Show removed": the session whose removed takes to list. */
  sessionId?: string;
  override?: SectionOverride | null;
  /** Content admins: set or clear the section override for this artisan. */
  onOverride?: (status: SectionOverride | null) => Promise<void>;
  /** A change made here (primary, decision, restore) that the workspace should reload for. */
  onChanged?: () => void;
}) {
  const fileRef = useRef<HTMLInputElement | null>(null);
  const [events, setEvents] = useState<{ id: string; rows: RecordingEvent[] } | null>(null);
  const [removed, setRemoved] = useState<Recording[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const live = recordings.filter((r) => !r.removedAt).sort((a, b) => a.capturedAt.localeCompare(b.capturedAt));
  const artisan = artisanId && !artisanId.startsWith("R:") ? artisanId : null;

  async function act(run: () => Promise<unknown>) {
    setError(null);
    try {
      await run();
      onChanged?.();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    }
  }

  async function showRemoved() {
    if (!sessionId) return;
    try {
      const { items } = await questionnaireApi.removedTakes({ sessionId });
      setRemoved(items);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    }
  }

  return (
    <FieldDialog open={open} onClose={onClose} title={title}>
      {onOverride ? (
        <div className="mb-3 grid gap-1" data-section-override>
          <span className="field-label">{qs("sectionOverride")}</span>
          <SegmentedChoice
            label={qs("sectionOverride")}
            value={override ?? ""}
            options={(["", "COMPLETED", "NEEDS_REVIEW", "NEEDS_REDO"] as const).map((v) => ({ value: v, label: qs(OVERRIDE_KEY[v]) }))}
            onChange={(v) => void act(() => onOverride((v || null) as SectionOverride | null))}
          />
        </div>
      ) : override ? (
        <p className="mb-3 text-sm text-ink-700" data-section-override-state>
          {qs(OVERRIDE_KEY[override])}
        </p>
      ) : null}
      <ul className="grid gap-3" data-take-sheet>
        {live.map((r) => {
          const subject = artisan ? r.subjects.find((s) => s.artisanId === artisan) : null;
          const target = r.targets[0];
          return (
            <li key={r.id} className="rounded-md border border-line-200 p-3" data-take-row={r.id}>
              <p className="text-sm text-ink-900">{takeRow(r, artisan)}</p>
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
              <ButtonRow
                size="sm"
                className="mt-2"
                align="start"
                destructive={
                  onRemove ? (
                    <button type="button" className="field-button-secondary field-danger" data-danger onClick={() => onRemove(r)}>
                      {qs("remove")}
                    </button>
                  ) : undefined
                }
              >
                <a className="field-button-secondary" href={questionnaireApi.downloadHref(r.id)} download={r.downloadName}>
                  <Download className="h-4 w-4" aria-hidden />
                  {qs("download")}
                </a>
                {onUseAsAnswer && canOfferAnswers(r) ? (
                  <button type="button" className="field-button-secondary" onClick={() => onUseAsAnswer(r)} data-use-take>
                    {qs("useAsAnswer")}
                  </button>
                ) : null}
                {subject && (subject.position ?? 1) > 1 && target ? (
                  <button
                    type="button"
                    className="field-button-secondary"
                    onClick={() => void act(() => questionnaireApi.makePrimary(r.id, { artisanId: subject.artisanId, targetKey: target.targetKey }))}
                  >
                    {qs("makePrimary")}
                  </button>
                ) : null}
                {r.filing === "NEEDS_DECISION" ? (
                  <button type="button" className="field-button-secondary" onClick={() => void act(() => questionnaireApi.decide(r.id, { action: "KEEP_BOTH" }))}>
                    {qs("keepBoth")}
                  </button>
                ) : null}
                <button
                  type="button"
                  className="field-button-secondary"
                  onClick={() =>
                    void questionnaireApi
                      .recordingEvents(r.id)
                      .then((out) => setEvents({ id: r.id, rows: out.events }))
                      .catch((failure: unknown) => setError(failure instanceof Error ? failure.message : String(failure)))
                  }
                >
                  {qs("history")}
                </button>
              </ButtonRow>
              {events?.id === r.id ? (
                <ol className="mt-2 grid gap-1 text-xs text-ink-500" data-take-history>
                  {events.rows.length ? (
                    events.rows.map((e) => (
                      <li key={e.id}>
                        {[qs("takeEvent", { kind: e.kind.toLowerCase().replace(/_/g, " ") }), e.actor.name, shortDate(e.at)].filter(Boolean).join(" · ")}
                      </li>
                    ))
                  ) : (
                    <li>{qs("noTakeHistory")}</li>
                  )}
                </ol>
              ) : null}
            </li>
          );
        })}
      </ul>
      {sessionId ? (
        removed ? (
          <section className="mt-4 grid gap-2" data-removed-takes>
            <h3 className="field-label">{qs("removedTakes")}</h3>
            {removed.length ? (
              removed.map((r) => (
                <div key={r.id} className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-line-200 p-2 text-sm">
                  <span className="text-ink-700">{takeRow(r, artisan)}</span>
                  <button
                    type="button"
                    className="field-button-secondary"
                    onClick={() =>
                      void act(async () => {
                        await questionnaireApi.restoreRecording(r.id);
                        setRemoved((list) => (list ?? []).filter((x) => x.id !== r.id));
                      })
                    }
                  >
                    {qs("restore")}
                  </button>
                </div>
              ))
            ) : (
              <p className="text-sm text-ink-500">{qs("noRemovedTakes")}</p>
            )}
          </section>
        ) : (
          <ButtonRow className="mt-3">
            <button type="button" className="text-sm font-medium text-purple-700 underline dark:text-purple-300" onClick={() => void showRemoved()}>
              {qs("showRemovedTakes")}
            </button>
          </ButtonRow>
        )
      ) : null}
      {error ? (
        <p role="alert" className="mt-2 text-sm text-error-600">
          {error}
        </p>
      ) : null}
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
