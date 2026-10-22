"use client";

import Link from "next/link";
import { ClipboardList } from "lucide-react";
import { useCallback, useEffect, useState } from "react";

import { useAuth } from "@/components/AuthProvider";
import { PageHeader } from "@/components/PageHeader";
import { PromptDialog } from "@/components/questionnaire/Prompts";
import { TranscriptStatus } from "@/components/questionnaire/TranscriptStatus";
import { takeRow } from "@/components/questionnaire/TakeSheet";
import { useQuestionnaireConfig } from "@/components/questionnaire/useQuestionnaireConfig";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { AudioPlayer } from "@/components/ui/AudioPlayer";
import { apiFetch } from "@/lib/api";
import { formatDate } from "@/lib/format";
import { languageLabel } from "@/lib/questionnaire/languages";
import { canReview } from "@/lib/permissions";
import { questionnaireApi, toGridView, type Answer, type GridQuestion, type GridView, type Recording, type SessionDetail } from "@/lib/questionnaire/api";
import { loadInstrumentQuestions } from "@/components/questionnaire/instrumentQuestions";
import { reviewRequest } from "@/lib/questionnaire/inbox";
import { approveBlock, modeLabel, reviewFlags, targetLabel } from "@/lib/questionnaire/view";
import { qs } from "@/lib/questionnaire/qs";

/** Recordings grouped by section in instrument order; whole-session takes last. */
export function groupBySection(detail: { recordings: Recording[]; grid: GridView }): { key: string; label: string; recordings: Recording[] }[] {
  const live = detail.recordings.filter((r) => !r.removedAt);
  const sectionOfTarget = (r: Recording) => {
    const t = r.targets[0];
    if (!t) return null;
    if (t.sectionId) return t.sectionId;
    return detail.grid.sections.find((s) => s.questions?.some((q) => q.lineageId === t.questionLineageId))?.id ?? null;
  };
  const groups = detail.grid.sections.map((s) => ({
    key: s.id,
    label: `${s.code} ${s.title}`,
    recordings: live.filter((r) => sectionOfTarget(r) === s.id).sort((a, b) => a.capturedAt.localeCompare(b.capturedAt))
  }));
  const whole = live.filter((r) => sectionOfTarget(r) === null);
  return [...groups.filter((g) => g.recordings.length), ...(whole.length ? [{ key: "whole", label: qs("wholeSession"), recordings: whole }] : [])];
}

/**
 * Answers by section order, each with its question and its artisan (audit, read view: they used to
 * print as bare text). A group answer names every artisan it was written for once.
 */
export function answersBySection(
  answers: Answer[],
  grid: GridView,
  names: Record<string, string>
): { key: string; label: string; rows: { key: string; code: string; prompt: string; who: string; text: string }[] }[] {
  const where = new Map<string, { section: number; order: number; code: string; prompt: string; sectionId: string }>();
  grid.sections.forEach((s, si) => (s.questions ?? []).forEach((q, qi) => where.set(q.lineageId, { section: si, order: qi, code: q.displayCode, prompt: q.prompt, sectionId: s.id })));
  const byQuestion = new Map<string, { lineage: string; text: string; artisans: string[] }>();
  for (const a of answers) {
    const lineage = a.questionLineageId ?? "";
    const k = `${lineage}|${a.attribution === "GROUP_SHARED" ? "group" : a.artisanId ?? a.id}|${a.text}`;
    const row = byQuestion.get(k) ?? { lineage, text: a.text, artisans: [] };
    if (a.artisanId) row.artisans.push(a.artisanId);
    byQuestion.set(k, row);
  }
  const rows = [...byQuestion.entries()].map(([k, r]) => {
    const at = where.get(r.lineage);
    const people = r.artisans.map((id) => names[id] ?? "").filter(Boolean);
    return {
      key: k,
      section: at?.section ?? Number.MAX_SAFE_INTEGER,
      order: at?.order ?? 0,
      sectionId: at?.sectionId ?? "other",
      code: at?.code ?? "",
      prompt: at?.prompt ?? "",
      who: people.length > 1 ? `${qs("groupAnswer", { count: people.length })} · ${people.join(", ")}` : people[0] ?? "",
      text: r.text
    };
  });
  rows.sort((a, b) => a.section - b.section || a.order - b.order || a.who.localeCompare(b.who));
  const groups: { key: string; label: string; rows: typeof rows }[] = [];
  for (const row of rows) {
    const section = grid.sections.find((s) => s.id === row.sectionId);
    let group = groups.find((g) => g.key === row.sectionId);
    if (!group) {
      group = { key: row.sectionId, label: section ? `${section.code} ${section.title}` : "", rows: [] };
      groups.push(group);
    }
    group.rows.push(row);
  }
  return groups.map((g) => ({ key: g.key, label: g.label, rows: g.rows.map(({ key, code, prompt, who, text }) => ({ key, code, prompt, who, text })) }));
}

/**
 * The read view (DESIGN §4.8): fields, recordings by section with take labels, answers by section.
 * No transcript or "Add media" actions here — a re-run replaces a transcript, which is an update.
 * Reviewers also get the flags line and Return · Approve, with Approve held and its reason shown.
 */
export function SessionReadView({ id }: { id: string }) {
  const { user } = useAuth();
  const config = useQuestionnaireConfig();
  const [detail, setDetail] = useState<SessionDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [returning, setReturning] = useState(false);
  const [note, setNote] = useState("");
  const [decideError, setDecideError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const [questions, setQuestions] = useState<Record<string, GridQuestion[]>>({});

  const load = useCallback(async () => {
    setError(null);
    try {
      const d = await questionnaireApi.getSession(id);
      setDetail(d);
      void loadInstrumentQuestions(d.questionnaire.id)
        .then((q) => setQuestions(q.byId))
        .catch(() => undefined);
    } catch {
      setError(qs("sessionLoadError"));
    }
  }, [id]);
  useEffect(() => {
    void load();
  }, [load]);

  if (error)
    return (
      <div className="panel grid gap-3 p-6 text-center">
        <p className="text-sm text-ink-700">{error}</p>
        <ButtonRow>
          <button type="button" className="field-button" onClick={() => void load()}>
            {qs("tryAgain")}
          </button>
        </ButtonRow>
      </div>
    );
  if (!detail) return <div className="panel p-4 text-sm text-ink-500">{qs("loading")}</div>;

  const live = detail.recordings.filter((r) => !r.removedAt);
  const block = approveBlock({
    participants: detail.participants.length,
    liveRecordings: live.length,
    answers: detail.answers.length,
    needsDecision: live.filter((r) => r.filing === "NEEDS_DECISION").length
  });
  const reviewer = canReview(user) && detail.status === "PENDING";

  async function decide(action: "approve" | "revise") {
    const request = reviewRequest(action, id, note);
    if (!request) {
      setDecideError(qs("returnNeedsNote"));
      return;
    }
    setDecideError(null);
    try {
      await apiFetch(request.path, { method: "POST", body: JSON.stringify(request.body) });
      setReturning(false);
      setNote("");
      setDone(action === "approve" ? qs("approve") : qs("returnForChanges"));
      void load();
    } catch (err) {
      setDecideError(err instanceof Error ? err.message : String(err));
    }
  }

  return (
    <>
      <PageHeader title={detail.title} description={detail.note ?? undefined} icon={<ClipboardList className="h-5 w-5" aria-hidden />} />
      {detail.legacyTitle ? <p className="-mt-4 mb-4 text-sm text-ink-500">{qs("earlierTitle", { title: detail.legacyTitle })}</p> : null}
      <section className="panel mb-4 grid gap-2 p-4 text-sm md:grid-cols-2" data-session-fields>
        <Row label={qs("fieldInstrument")} value={detail.questionnaire.title} />
        <Row label={qs("fieldDate")} value={formatDate(detail.sessionDate)} />
        <Row label={qs("fieldMode")} value={modeLabel(detail)} />
        <div>
          <span className="field-label">{qs("fieldArtisans")}</span>
          <p className="flex flex-wrap gap-2">
            {detail.participants.map((p) => (
              <Link key={p.artisanId} className="text-purple-700 underline" href={`/artisans/${encodeURIComponent(p.artisanId)}?tab=questionnaire`}>
                {p.name}
              </Link>
            ))}
          </p>
        </div>
        <Row label={qs("colResearchers")} value={detail.researchers.map((r) => r.name).join(", ")} />
        <Row label={qs("fieldPlace")} value={detail.place ?? ""} />
        <Row label={qs("fieldLanguage")} value={languageLabel(detail.languageCode, detail.languageOther)} />
      </section>

      {groupBySection({ recordings: detail.recordings, grid: toGridView(detail.grid, questions) }).map((group) => (
        <section key={group.key} className="panel mb-3 grid gap-2 p-4">
          <h2 className="display-title text-base">{group.label}</h2>
          <ul className="grid gap-2">
            {group.recordings.map((r) => (
              <li key={r.id} className="grid gap-1">
                <p className="text-sm text-ink-900">
                  {targetLabel(r)} · {takeRow(r, null)}
                </p>
                <TranscriptStatus recording={r} paused={config?.transcriptionPaused ?? true} />
                <AudioPlayer src={questionnaireApi.downloadHref(r.id)} />
              </li>
            ))}
          </ul>
        </section>
      ))}

      {answersBySection(detail.answers, toGridView(detail.grid, questions), Object.fromEntries(detail.participants.map((p) => [p.artisanId, p.name]))).map((group) => (
        <section key={`answers-${group.key}`} className="panel mb-4 grid gap-3 p-4" data-answers-section={group.key}>
          {group.label ? <h2 className="display-title text-base">{group.label}</h2> : null}
          {group.rows.map((row) => (
            <div key={row.key} className="grid gap-0.5">
              <p className="text-xs text-ink-500">
                {[row.code && row.prompt ? `${row.code} ${row.prompt}` : row.code || row.prompt, row.who].filter(Boolean).join(" · ")}
              </p>
              <p className="text-sm text-ink-900">{row.text}</p>
            </div>
          ))}
        </section>
      ))}

      {reviewer ? (
        <section className="panel mb-4 grid gap-3 p-4" data-review>
          <p className="text-sm text-ink-700" data-review-flags>
            {reviewFlags(detail.recordings)}
          </p>
          <ButtonRow>
            <button type="button" className="field-button-secondary" onClick={() => setReturning(true)}>
              {qs("returnForChanges")}
            </button>
            <button type="button" className="field-button" disabled={Boolean(block)} aria-describedby={block ? "approve-block" : undefined} onClick={() => void decide("approve")}>
              {qs("approve")}
            </button>
          </ButtonRow>
          {block ? (
            <p id="approve-block" className="text-center text-xs text-ink-500">
              {block}
            </p>
          ) : null}
          {done ? <p className="text-center text-sm text-success-600">{done}</p> : null}
          {decideError && !returning ? (
            <p role="alert" className="text-center text-xs text-error-600">
              {decideError}
            </p>
          ) : null}
        </section>
      ) : null}

      <ButtonRow>
        <Link className="field-button" href={`/questionnaire/sessions/${encodeURIComponent(id)}`}>
          {qs("update")}
        </Link>
      </ButtonRow>

      {returning ? (
        <PromptDialog
          spec={{
            id: "RETURN",
            title: qs("returnForChanges"),
            body: detail.title,
            buttons: [
              { id: "cancel", label: qs("cancel"), tone: "secondary" },
              { id: "return", label: qs("returnForChanges"), tone: "primary" }
            ],
            dismiss: "cancel"
          }}
          open
          onChoose={(choice) => {
            if (choice === "return") void decide("revise");
            else setReturning(false);
          }}
        >
          <label className="field-label" htmlFor="return-note">
            {qs("returnNoteLabel")}
          </label>
          <textarea id="return-note" className="field-input mt-1 min-h-24 w-full" value={note} onChange={(e) => setNote(e.target.value)} />
          {decideError ? (
            <p role="alert" className="mt-2 text-xs text-error-600">
              {decideError}
            </p>
          ) : null}
        </PromptDialog>
      ) : null}
    </>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  if (!value) return null;
  return (
    <div>
      <span className="field-label">{label}</span>
      <p className="text-ink-900">{value}</p>
    </div>
  );
}
