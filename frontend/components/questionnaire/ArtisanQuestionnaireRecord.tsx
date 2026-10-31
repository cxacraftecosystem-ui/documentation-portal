"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";

import { ConsentQuestion } from "@/components/questionnaire/ConsentQuestion";
import { ContinueWithDialog } from "@/components/questionnaire/ContinueWithDialog";
import { takeRow } from "@/components/questionnaire/TakeSheet";
import { TranscriptStatus } from "@/components/questionnaire/TranscriptStatus";
import { useQuestionnaireConfig } from "@/components/questionnaire/useQuestionnaireConfig";
import { AudioPlayer } from "@/components/ui/AudioPlayer";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { SegmentedChoice } from "@/components/ui/SegmentedChoice";
import { shortName } from "@/lib/questionnaire/coverageCache";
import { questionnaireApi, type ArtisanRecord, type Recording } from "@/lib/questionnaire/api";
import { shortDate } from "@/lib/questionnaire/prompts";
import { artisanTimeline, isLegacyTake, modeLabel, subjectRoleFor, targetLabel } from "@/lib/questionnaire/view";
import { qs } from "@/lib/questionnaire/qs";
import { RecordingChips } from "@/components/questionnaire/RecordingChips";
import { useChipNavigator } from "@/components/questionnaire/useUrlFilters";

function TakeLine({ recording, artisanId, paused }: { recording: Recording; artisanId: string; paused: boolean }) {
  const goChip = useChipNavigator();
  const present = subjectRoleFor(recording, artisanId) === "PRESENT";
  const time = new Date(recording.capturedAt).toLocaleTimeString("en-IN", { hour: "2-digit", minute: "2-digit", hour12: false, timeZone: "Asia/Kolkata" });
  return (
    <li className="grid gap-1 py-1" data-timeline-take={recording.id}>
      <p className="text-sm text-ink-900">
        {recording.estimated ? null : <span className="mr-2 tabular-nums text-ink-500">{time}</span>}
        {targetLabel(recording)} · {takeRow(recording, artisanId)}
        {present ? ` · ${qs("presentDidntAnswer")}` : ""}
        {recording.estimated ? ` · ${qs("estimatedDate")}` : ""}
        {recording.filing === "NEEDS_DECISION" ? ` · ${qs("needsDecision")}` : ""}
        {isLegacyTake(recording) ? ` · ${qs("legacyRecord")}` : ""}
      </p>
      <RecordingChips chips={recording.chips} onChip={goChip} />
      <TranscriptStatus recording={recording} paused={paused} />
      <AudioPlayer src={questionnaireApi.downloadHref(recording.id)} />
    </li>
  );
}

/**
 * An artisan's questionnaire record (DESIGN §4.6), the Questionnaire tab of `/artisans/[id]`:
 * summary and section chips, Continue with / Export CSV, then Timeline (sessions newest first, takes
 * in capture order, PRESENT and whole-session takes included, estimated items in "Order uncertain")
 * or By question. Replaces `components/ArtisanQuestionnairePanel.tsx` under v2.
 */
export function ArtisanQuestionnaireRecord({ artisanId, artisanName }: { artisanId: string; artisanName: string }) {
  const config = useQuestionnaireConfig();
  const [record, setRecord] = useState<ArtisanRecord | null>(null);
  const [error, setError] = useState(false);
  const [view, setView] = useState<"timeline" | "question">("timeline");
  const [refusedConsent, setRefusedConsent] = useState(false);
  const [continueFrom, setContinueFrom] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(false);
    try {
      setRecord(await questionnaireApi.artisanRecord(artisanId));
    } catch {
      setError(true);
    }
  }, [artisanId]);
  useEffect(() => {
    void load();
    void questionnaireApi
      .getConsent(artisanId)
      .then((c) => setRefusedConsent(c.state === "REFUSED"))
      .catch(() => setRefusedConsent(false));
  }, [artisanId, load]);

  // `/questionnaire/consolidated/[id]` lands on `/artisans/[id]?tab=questionnaire`: bring the record into view.
  useEffect(() => {
    if (!record || typeof window === "undefined") return;
    if (new URLSearchParams(window.location.search).get("tab") !== "questionnaire") return;
    requestAnimationFrame(() => document.getElementById("questionnaire")?.scrollIntoView({ block: "start" }));
  }, [record]);

  const paused = config?.transcriptionPaused ?? true;
  // Consent is assumed by default; the record speaks only when the artisan refused, with Switch back on.
  const consent = refusedConsent ? (
    <ConsentQuestion artisanId={artisanId} name={artisanName} refused onRecorded={() => setRefusedConsent(false)} onDismiss={() => setRefusedConsent(false)} />
  ) : null;

  if (error)
    return (
      <div className="panel grid gap-3 p-6 text-center" data-record-state="error">
        <p className="text-sm text-ink-700">{qs("recordLoadError")}</p>
        <ButtonRow>
          <button type="button" className="field-button" onClick={() => void load()}>
            {qs("tryAgain")}
          </button>
        </ButtonRow>
      </div>
    );
  if (!record) return <div className="panel p-4 text-sm text-ink-500">{qs("loading")}</div>;
  if (record.instruments.length === 0 || record.instruments.every((i) => i.sessions.length === 0))
    return (
      <div className="grid gap-3">
        {consent}
        <div className="panel grid gap-3 p-6 text-center" data-record-state="empty">
          <p className="text-sm text-ink-700">{qs("noSessionsYet")}</p>
          <ButtonRow>
            <Link className="field-button" href={`/questionnaire/sessions/new?artisanId=${encodeURIComponent(artisanId)}`}>
              {qs("startSession")}
            </Link>
          </ButtonRow>
        </div>
      </div>
    );

  return (
    <div className="grid gap-4" data-artisan-record>
      {consent}
      {record.instruments.map((instrument) => (
        <section key={instrument.questionnaireId} className="panel grid gap-3 p-4">
          <h2 className="display-title text-lg">{instrument.title}</h2>
          <p className="text-sm text-ink-700">
            {qs("recordSummary", {
              covered: instrument.summary.sectionsCovered,
              total: instrument.summary.sectionsTotal,
              sessions: instrument.summary.sessions,
              repeats: instrument.summary.repeats
            })}
          </p>
          {instrument.summary.open.length ? <p className="text-sm text-ink-500">{qs("openSections", { codes: instrument.summary.open.join(", ") })}</p> : null}
          <ButtonRow>
            <button type="button" className="field-button" onClick={() => setContinueFrom(instrument.questionnaireId)}>
              {qs("continueWith", { name: shortName(artisanName) })}
            </button>
            <a className="field-button-secondary" href={questionnaireApi.artisanRecordCsvHref(artisanId, instrument.questionnaireId)} download>
              {qs("exportCsv")}
            </a>
          </ButtonRow>
          {continueFrom === instrument.questionnaireId ? (
            <ContinueWithDialog artisanId={artisanId} artisanName={artisanName} sessions={instrument.sessions} open onClose={() => setContinueFrom(null)} />
          ) : null}
          <div className="flex justify-center">
            <SegmentedChoice
              label={qs("viewTimeline")}
              value={view}
              options={[
                { value: "timeline", label: qs("viewTimeline") },
                { value: "question", label: qs("viewByQuestion") }
              ]}
              onChange={(v) => setView(v as "timeline" | "question")}
            />
          </div>
          {view === "timeline" ? (
            <ol className="grid gap-3" data-timeline>
              {artisanTimeline(instrument.sessions).map((entry) => (
                <li key={entry.session.id} className="border-t border-line-200 pt-2">
                  <p className="text-sm font-medium text-ink-900">
                    {[shortDate(entry.session.sessionDate), modeLabel(entry.session), entry.session.researchers.map((r) => r.name).join(", "), entry.session.place]
                      .filter(Boolean)
                      .join(" · ")}
                  </p>
                  <ul>
                    {entry.dated.map((r) => (
                      <TakeLine key={r.id} recording={r} artisanId={artisanId} paused={paused} />
                    ))}
                  </ul>
                  {entry.uncertain.length ? (
                    <>
                      <p className="mt-1 text-xs uppercase tracking-wide text-ink-500">{qs("orderUncertain")}</p>
                      <ul data-uncertain>
                        {entry.uncertain.map((r) => (
                          <TakeLine key={r.id} recording={r} artisanId={artisanId} paused={paused} />
                        ))}
                      </ul>
                    </>
                  ) : null}
                </li>
              ))}
            </ol>
          ) : (
            <div className="grid gap-3">
              {instrument.document.map((section) => (
                <div key={section.sectionCode}>
                  <h3 className="font-semibold text-ink-900">{section.sectionCode}</h3>
                  <ul className="grid gap-1">
                    {section.questions.map((q) => (
                      <li key={q.displayCode} className="text-sm text-ink-700">
                        <span className="mr-2 font-semibold">{q.displayCode}</span>
                        {q.answer?.text ?? "—"}
                        {q.answer && q.answer.origin !== "TYPED" ? ` · ${qs("fromRecording")}` : ""}
                        {q.otherRecordingIds.length ? ` · ${qs("moreTakes", { count: q.otherRecordingIds.length })}` : ""}
                        {q.accountsDiffer ? ` · ${qs("accountsDiffer")}` : ""}
                        {q.recordedUnderEarlierWording ? ` · ${qs("recordedUnderEarlierWording")}` : ""}
                      </li>
                    ))}
                  </ul>
                </div>
              ))}
              {instrument.retired.length ? (
                <div>
                  <h3 className="font-semibold text-ink-900">{qs("retiredQuestions")}</h3>
                  <ul>
                    {instrument.retired.map((q) => (
                      <li key={q.displayCode} className="text-sm text-ink-500">
                        {q.displayCode} {q.answer?.text ?? ""}
                      </li>
                    ))}
                  </ul>
                </div>
              ) : null}
            </div>
          )}
        </section>
      ))}
    </div>
  );
}
