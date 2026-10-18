"use client";

/**
 * /admin/duplicates/{id} — one likely pair: compare, choose per field, merge (or set aside).
 *
 * The preview comes from the server (`GET /artisan-duplicates/{id}`): the field rows with each
 * side's value as this screen may show it (masked identity, names for ids), the default survivor —
 * the record with more attached to it — the related-row counts, and anything that blocks a merge.
 * Every history entry, media file, workshop link and audit row of the merged record moves to the one
 * kept; a merge can be undone for 30 days from the Merged tab.
 */

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useState } from "react";
import { GitMerge, Link2, ShieldCheck, Users } from "lucide-react";

import { Field, TextInput } from "@/components/FormControls";
import { MergeFieldTable } from "@/components/duplicates/MergeFieldTable";
import { PageHeader } from "@/components/PageHeader";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { useToast } from "@/components/ui/Toast";
import { ApiError, apiFetch } from "@/lib/api";
import {
  BAND_LABEL,
  RELATED_LABEL,
  choicesToSend,
  defaultChoices,
  dismiss,
  getPair,
  merge,
  reseedChoices,
  undoMerge,
  type MergeBlocker,
  type MergeChoice,
  type MergePreview
} from "@/lib/duplicates";
import { formatDateTime } from "@/lib/format";

function detailOf(error: unknown): { code?: string; message?: string; blockers?: MergeBlocker[] } | null {
  if (!(error instanceof ApiError)) return null;
  const detail = (error.payload as { detail?: unknown } | undefined)?.detail;
  if (typeof detail === "string") return { message: detail };
  return detail && typeof detail === "object" ? (detail as { code?: string; message?: string; blockers?: MergeBlocker[] }) : null;
}

export default function DuplicatePairPage() {
  const params = useParams<{ id: string }>();
  const router = useRouter();
  const { toast } = useToast();
  const [preview, setPreview] = useState<MergePreview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [survivor, setSurvivor] = useState<"A" | "B">("A");
  const [choices, setChoices] = useState<Record<string, MergeChoice>>({});
  const [touched, setTouched] = useState<Set<string>>(new Set());
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [merged, setMerged] = useState<{ id: string; survivorId: string; undoUntil: string } | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      const body = await getPair(params.id);
      setPreview(body);
      setSurvivor(body.defaultSurvivor);
      setChoices(defaultChoices(body.fields, body.defaultSurvivor));
      setTouched(new Set());
    } catch (err) {
      setError(detailOf(err)?.message ?? "Couldn’t load this pair.");
    }
  }, [params.id]);

  useEffect(() => {
    void load();
  }, [load]);

  const blocked = Boolean(preview?.blockers.length);
  const sides = useMemo(() => (preview ? { A: preview.a, B: preview.b } : null), [preview]);

  function pickSurvivor(next: "A" | "B") {
    if (!preview || next === survivor) return;
    setChoices(reseedChoices(preview.fields, choices, touched, next));
    setSurvivor(next);
  }

  function pickChoice(key: string, choice: MergeChoice) {
    setChoices((current) => ({ ...current, [key]: choice }));
    setTouched((current) => new Set(current).add(key));
  }

  async function runAction(blocker: MergeBlocker) {
    if (!blocker.action) return;
    setBusy(true);
    try {
      await apiFetch(
        `/questionnaire/interviews/${encodeURIComponent(blocker.action.sourceId)}/merge-into/${encodeURIComponent(blocker.action.targetId)}`,
        { method: "POST" }
      );
      toast({ id: "duplicates", tone: "success", title: "Interviews merged" });
      await load();
    } catch (err) {
      setError(detailOf(err)?.message ?? "Couldn’t merge the interviews.");
    } finally {
      setBusy(false);
    }
  }

  async function onMerge() {
    if (!preview) return;
    setBusy(true);
    setError(null);
    try {
      const result = await merge(preview.id, {
        survivor,
        choices: choicesToSend(preview.fields, choices),
        expectedUpdatedAt: { a: preview.a.updatedAt ?? null, b: preview.b.updatedAt ?? null },
        reason: reason.trim() || null
      });
      setMerged({ id: result.merge.id, survivorId: result.merge.survivorId, undoUntil: result.merge.undoUntil });
      toast({ id: "duplicates", tone: "success", title: "Merged. Undo within 30 days." });
    } catch (err) {
      const detail = detailOf(err);
      if (detail?.code === "merge_blocked" && preview) {
        setPreview({ ...preview, blockers: detail.blockers ?? [] });
      } else if (detail?.code === "record_changed") {
        setError("One of these records changed. Review it again.");
        await load();
      } else {
        setError(detail?.message ?? "Couldn’t merge.");
      }
    } finally {
      setBusy(false);
    }
  }

  async function onUndo() {
    if (!merged) return;
    setBusy(true);
    try {
      await undoMerge(merged.id);
      toast({ id: "duplicates", tone: "success", title: "Merge undone" });
      router.push("/admin/duplicates");
    } catch (err) {
      setError(detailOf(err)?.message ?? "Couldn’t undo the merge.");
    } finally {
      setBusy(false);
    }
  }

  async function onDismiss() {
    if (!preview) return;
    setBusy(true);
    try {
      await dismiss(preview.id, reason.trim() || null);
      toast({ id: "duplicates", tone: "success", title: "Marked as different people" });
      router.push("/admin/duplicates");
    } catch (err) {
      setError(detailOf(err)?.message ?? "Couldn’t update this pair.");
      setBusy(false);
    }
  }

  const header = (
    <PageHeader
      title="Review duplicate"
      description={preview ? `${Math.round(preview.score * 100)}% · ${BAND_LABEL[preview.band]}` : undefined}
      icon={<Users className="h-5 w-5" aria-hidden />}
    />
  );

  if (merged) {
    return (
      <>
        {header}
        <section className="panel grid gap-3 p-4">
          <p className="text-sm font-medium text-ink-900">Merged. Undo within 30 days.</p>
          <p className="text-xs text-ink-500">Until {formatDateTime(merged.undoUntil)}</p>
          <ButtonRow>
            <button type="button" className="field-button-secondary" onClick={onUndo} disabled={busy}>
              Undo
            </button>
            <Link className="field-button" href={`/artisans/${merged.survivorId}`}>
              Open record
            </Link>
          </ButtonRow>
        </section>
      </>
    );
  }

  return (
    <>
      {header}
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {!preview || !sides ? (
        error ? null : <section className="panel p-4 text-sm text-ink-500">Loading…</section>
      ) : (
        <div className="grid gap-4">
          <section className="panel grid gap-3 p-4">
            <div className="flex flex-wrap gap-1">
              {preview.reasons.map((r) => (
                <span key={r.code} className="rounded-full border border-line-200 bg-surface-50 px-2 py-0.5 text-[11px] text-ink-700">
                  {r.label}
                </span>
              ))}
            </div>
            <div className="grid gap-2" role="radiogroup" aria-label="Record to keep">
              <span className="field-label">Keep</span>
              <div className="grid gap-2 sm:grid-cols-2">
                {(["A", "B"] as const).map((letter) => {
                  const side = sides[letter];
                  const counts = letter === "A" ? preview.related.a : preview.related.b;
                  const links = letter === "A" ? preview.linkedFromDesignerPortal?.a : preview.linkedFromDesignerPortal?.b;
                  return (
                    <button
                      key={letter}
                      type="button"
                      role="radio"
                      aria-checked={survivor === letter}
                      onClick={() => pickSurvivor(letter)}
                      className={`grid gap-1 rounded-lg border p-3 text-left transition ${
                        survivor === letter ? "border-purple-700 bg-purple-50" : "border-line-200 bg-card hover:border-purple-300"
                      }`}
                    >
                      <span className="text-xs font-semibold text-ink-500">{letter}</span>
                      <span className="font-display text-base font-bold text-ink-900">{side.name}</span>
                      <span className="text-xs text-ink-500">
                        {[side.place, side.craft, side.createdAt ? `Recorded ${formatDateTime(side.createdAt)}` : null]
                          .filter(Boolean)
                          .join(" · ")}
                      </span>
                      <span className="flex flex-wrap gap-1 pt-1">
                        {Object.entries(counts)
                          .filter(([, n]) => n > 0)
                          .map(([key, n]) => (
                            <span key={key} className="rounded-full border border-line-200 bg-surface-50 px-2 py-0.5 text-[11px] text-ink-700">
                              {RELATED_LABEL[key] ?? key} {n}
                            </span>
                          ))}
                        {links ? (
                          <span className="inline-flex items-center gap-1 rounded-full border border-purple-300 bg-purple-50 px-2 py-0.5 text-[11px] text-purple-700">
                            <Link2 className="h-3 w-3" aria-hidden />
                            Linked from the designer portal ({links})
                          </span>
                        ) : null}
                      </span>
                    </button>
                  );
                })}
              </div>
            </div>
          </section>

          <section className="panel overflow-hidden">
            <MergeFieldTable fields={preview.fields} survivor={survivor} choices={choices} onChoice={pickChoice} disabled={busy} />
            {preview.consent ? (
              <div className="flex items-center gap-2 border-t border-line-200 px-3 py-2 text-sm text-ink-700">
                <ShieldCheck className="h-4 w-4 text-ink-500" aria-hidden />
                <span className="font-medium">Sharing consent</span>
                <span>
                  {preview.consent.result === "GRANTED" ? "Given" : "Withdrawn"} · from {preview.consent.from}
                  {preview.consent.recordedAt ? ` · ${formatDateTime(preview.consent.recordedAt)}` : ""}
                </span>
              </div>
            ) : null}
          </section>

          {preview.blockers.length ? (
            <section className="panel grid gap-2 border-red-200 p-4" aria-label="Why this can’t be merged">
              {preview.blockers.map((blocker) => (
                <div key={`${blocker.code}-${blocker.action?.sourceId ?? ""}`} className="flex flex-wrap items-center justify-between gap-2 text-sm text-red-700">
                  <span>{blocker.message}</span>
                  {blocker.action?.kind === "merge_interviews" ? (
                    <button type="button" className="field-button-secondary" onClick={() => runAction(blocker)} disabled={busy}>
                      Merge interviews
                    </button>
                  ) : null}
                </div>
              ))}
            </section>
          ) : null}

          <section className="panel grid gap-3 p-4">
            <Field label="Reason">
              <TextInput value={reason} maxLength={500} onChange={(event) => setReason(event.target.value)} placeholder="Optional" />
            </Field>
            <ButtonRow align="end">
              <button type="button" className="field-button-secondary" onClick={onDismiss} disabled={busy}>
                Not the same person
              </button>
              <button type="button" className="field-button" onClick={onMerge} disabled={busy || blocked}>
                <GitMerge className="h-4 w-4" aria-hidden />
                {busy ? "Merging…" : "Merge"}
              </button>
            </ButtonRow>
          </section>
        </div>
      )}
    </>
  );
}
