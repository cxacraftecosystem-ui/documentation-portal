"use client";

/**
 * /admin/artisan-merge — fold two records of one person into one (C24). Admins only: the page sits
 * under `/admin`, whose ROUTE_GUARDS and ADMIN_CHROME_ROUTES rows already cover it, and every call
 * behind it is `require_admin` on the server.
 *
 * Three steps on one screen: pick the pair (or take a suggested one), compare them field by field and
 * choose which answer the kept record ends with, then merge. Everything linked to the other record
 * moves to the kept one, and the merge can be undone from the list at the bottom for 30 days. The
 * server decides what differs, what moves and what blocks; this page only draws it.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { AlertTriangle, ArrowRight, GitMerge, RotateCcw, Users } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { PageHeader } from "@/components/PageHeader";
import { useConfirm } from "@/components/dialogs/ConfirmDialog";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { SearchableSelect, type SelectOption } from "@/components/ui/SearchableSelect";
import { useToast } from "@/components/ui/Toast";
import { apiFetch, buildQuery } from "@/lib/api";
import {
  defaultPicks,
  fetchMergeCandidates,
  fetchMergeHistory,
  fetchMergePreview,
  movesSentence,
  postMerge,
  postUndoMerge,
  resultValue,
  undoState,
  type MergeCandidate,
  type MergeChoice,
  type MergePreview,
  type MergeSummary
} from "@/lib/artisanMerge";
import { formatDate } from "@/lib/format";
import { isAdmin } from "@/lib/permissions";

type ArtisanRow = { id: string; name: string; place?: string | null; craft?: { name?: string | null } | null };

/** How many artisans one search asks for — the picker's own drawing cap. */
const PICKER_PAGE = 80;

function errorText(cause: unknown, fallback: string): string {
  return cause instanceof Error && cause.message ? cause.message : fallback;
}

/** An artisan picker over the server's search, so a record past the first page can still be found. */
function ArtisanPicker({
  label,
  value,
  onChange,
  exclude,
  seed
}: {
  label: string;
  value: string;
  onChange: (id: string) => void;
  exclude?: string;
  seed?: SelectOption | null;
}) {
  const [options, setOptions] = useState<SelectOption[]>([]);
  const [query, setQuery] = useState("");
  const generation = useRef(0);

  useEffect(() => {
    const ticket = ++generation.current;
    const timer = window.setTimeout(() => {
      apiFetch<{ items: ArtisanRow[] }>(
        `/artisans${buildQuery({ search: query.trim() || undefined, pageSize: PICKER_PAGE })}`
      )
        .then((page) => {
          if (ticket !== generation.current) return;
          setOptions(
            page.items.map((row) => ({
              value: row.id,
              label: [row.name, row.place, row.craft?.name].filter(Boolean).join(" · ")
            }))
          );
        })
        .catch(() => {
          if (ticket === generation.current) setOptions([]);
        });
    }, 250);
    return () => window.clearTimeout(timer);
  }, [query]);

  const shown = useMemo(() => {
    const list = options.filter((option) => option.value !== exclude);
    if (seed && seed.value !== exclude && !list.some((option) => option.value === seed.value)) list.unshift(seed);
    return list;
  }, [options, exclude, seed]);

  return (
    <div className="grid min-w-0 gap-1">
      <span className="field-label">{label}</span>
      <SearchableSelect
        value={value}
        onChange={onChange}
        options={shown}
        searchable
        onSearch={setQuery}
        advanceOnSelect={false}
        placeholder="Choose an artisan"
        emptyLabel="No matching artisans"
        ariaLabel={label}
      />
    </div>
  );
}

export default function ArtisanMergePage() {
  const { user, loading: authLoading } = useAuth();
  const permitted = isAdmin(user);
  const confirm = useConfirm();
  const { toast } = useToast();

  const [keepId, setKeepId] = useState("");
  const [mergeId, setMergeId] = useState("");
  const [seeds, setSeeds] = useState<{ keep: SelectOption | null; merge: SelectOption | null }>({ keep: null, merge: null });
  const [preview, setPreview] = useState<MergePreview | null>(null);
  const [picks, setPicks] = useState<Record<string, MergeChoice>>({});
  const [loadingPreview, setLoadingPreview] = useState(false);
  const [merging, setMerging] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [candidates, setCandidates] = useState<{ items: MergeCandidate[]; truncated: boolean } | null>(null);
  const [history, setHistory] = useState<MergeSummary[] | null>(null);
  const [undoing, setUndoing] = useState<string | null>(null);

  const loadLists = useCallback(() => {
    fetchMergeCandidates().then(setCandidates).catch(() => setCandidates({ items: [], truncated: false }));
    fetchMergeHistory()
      .then((result) => setHistory(result.items))
      .catch(() => setHistory([]));
  }, []);

  useEffect(() => {
    if (authLoading || !permitted) return;
    loadLists();
  }, [authLoading, permitted, loadLists]);

  // A pair handed over by the duplicates queue (`/admin/duplicates`): `?keep=<id>&merge=<id>`.
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const keep = params.get("keep")?.trim() ?? "";
    const other = params.get("merge")?.trim() ?? "";
    if (keep && other && keep !== other) {
      setKeepId(keep);
      setMergeId(other);
    }
  }, []);

  // A fresh comparison whenever the pair changes; a stale answer for the previous pair is dropped.
  const previewTicket = useRef(0);
  useEffect(() => {
    setPreview(null);
    setError(null);
    if (!keepId || !mergeId || keepId === mergeId) return;
    const ticket = ++previewTicket.current;
    setLoadingPreview(true);
    fetchMergePreview(keepId, mergeId)
      .then((result) => {
        if (ticket !== previewTicket.current) return;
        setPreview(result);
        setPicks(defaultPicks(result.fields));
        // The pickers show the pair by name even when it arrived by id alone (the duplicates queue).
        const seedOf = (side: MergePreview["keep"]): SelectOption => ({
          value: side.id,
          label: [side.name, side.place, side.craft].filter(Boolean).join(" · ")
        });
        setSeeds((current) => ({
          keep: current.keep?.value === result.keep.id ? current.keep : seedOf(result.keep),
          merge: current.merge?.value === result.merge.id ? current.merge : seedOf(result.merge)
        }));
      })
      .catch((cause) => {
        if (ticket === previewTicket.current) setError(errorText(cause, "Could not compare these two."));
      })
      .finally(() => {
        if (ticket === previewTicket.current) setLoadingPreview(false);
      });
  }, [keepId, mergeId]);

  function takeCandidate(candidate: MergeCandidate) {
    setSeeds({
      keep: { value: candidate.keep.id, label: [candidate.keep.name, candidate.keep.place].filter(Boolean).join(" · ") },
      merge: { value: candidate.merge.id, label: [candidate.merge.name, candidate.merge.place].filter(Boolean).join(" · ") }
    });
    setKeepId(candidate.keepId);
    setMergeId(candidate.mergeId);
  }

  function swap() {
    setSeeds((current) => ({ keep: current.merge, merge: current.keep }));
    setKeepId(mergeId);
    setMergeId(keepId);
  }

  async function merge() {
    if (!preview) return;
    const ok = await confirm({
      title: `Merge into ${preview.keep.name ?? "the kept record"}?`,
      body: `${preview.merge.name ?? "The other record"} is removed. ${movesSentence(preview.moves)}`,
      note: `You can undo this for ${preview.undoDays} days.`,
      confirmLabel: "Merge",
      tone: "warning"
    });
    if (!ok) return;
    setMerging(true);
    setError(null);
    try {
      await postMerge(preview.keep.id, preview.merge.id, picks);
      toast({ title: "Artisans merged", tone: "success" });
      setMergeId("");
      setSeeds((current) => ({ ...current, merge: null }));
      loadLists();
    } catch (cause) {
      setError(errorText(cause, "The merge did not go through."));
    } finally {
      setMerging(false);
    }
  }

  async function undo(summary: MergeSummary) {
    const ok = await confirm({
      title: `Undo the merge of ${summary.mergedName}?`,
      body: `${summary.mergedName} comes back with everything that was moved.`,
      note: "Anything edited since stays as it is.",
      confirmLabel: "Undo merge",
      tone: "warning"
    });
    if (!ok) return;
    setUndoing(summary.id);
    try {
      const result = await postUndoMerge(summary.id);
      toast({
        title: "Merge undone",
        tone: "success",
        description: result.kept.length ? `Left as edited: ${result.kept.join(", ")}.` : undefined
      });
      loadLists();
    } catch (cause) {
      setError(errorText(cause, "The merge could not be undone."));
    } finally {
      setUndoing(null);
    }
  }

  const header = (
    <PageHeader
      title="Merge duplicate artisans"
      description="Combine two records of the same person."
      icon={<GitMerge className="h-5 w-5" aria-hidden />}
    />
  );

  if (authLoading) {
    return (
      <>
        {header}
        <section className="panel p-6 text-sm text-ink-500">Checking access…</section>
      </>
    );
  }
  if (!permitted) {
    return (
      <>
        {header}
        <section className="panel px-6 py-12 text-center text-sm text-ink-500">Admins only.</section>
      </>
    );
  }

  const differing = preview?.fields.filter((field) => !field.same) ?? [];
  const matching = preview?.fields.filter((field) => field.same) ?? [];

  return (
    <>
      {header}

      {error ? (
        <p role="alert" className="mb-4 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-red-800">
          {error}
        </p>
      ) : null}

      {candidates && candidates.items.length ? (
        <section className="panel mb-5 p-4" aria-labelledby="merge-suggestions">
          <h2 id="merge-suggestions" className="flex items-center gap-2 font-display text-sm font-bold text-ink-900">
            <Users className="h-4 w-4 text-purple-700 dark:text-purple-300" aria-hidden />
            Possible duplicates
          </h2>
          <ul className="mt-3 grid gap-2">
            {candidates.items.map((candidate) => (
              <li
                key={`${candidate.keepId}-${candidate.mergeId}`}
                className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2"
              >
                <span className="min-w-0 text-sm text-ink-900">
                  {candidate.keep.name}
                  <span className="text-ink-500"> · {candidate.keep.place}</span>
                  <ArrowRight className="mx-1.5 inline h-3.5 w-3.5 text-ink-500" aria-label="and" />
                  {candidate.merge.name}
                  <span className="text-ink-500"> · {candidate.merge.place}</span>
                  <span className="ml-2 text-xs text-ink-500">{candidate.reason}</span>
                </span>
                <ButtonRow size="sm" align="end">
                  <button type="button" className="field-button-secondary" onClick={() => takeCandidate(candidate)}>
                    Compare
                  </button>
                </ButtonRow>
              </li>
            ))}
          </ul>
          {candidates.truncated ? (
            <p className="mt-2 text-xs text-ink-500">Showing the first {candidates.items.length}.</p>
          ) : null}
        </section>
      ) : null}

      <section className="panel mb-5 grid gap-4 p-4" aria-label="Choose the two records">
        <div className="grid gap-3 md:grid-cols-2">
          <ArtisanPicker label="Keep" value={keepId} onChange={setKeepId} exclude={mergeId} seed={seeds.keep} />
          <ArtisanPicker label="Merge into it" value={mergeId} onChange={setMergeId} exclude={keepId} seed={seeds.merge} />
        </div>
        {keepId && mergeId ? (
          <ButtonRow>
            <button type="button" className="field-button-secondary" onClick={swap}>
              Swap
            </button>
          </ButtonRow>
        ) : null}
        {loadingPreview ? <p className="text-sm text-ink-500">Comparing…</p> : null}
      </section>

      {preview ? (
        <section className="panel mb-5 p-4" aria-labelledby="merge-compare">
          <h2 id="merge-compare" className="font-display text-sm font-bold text-ink-900">
            Choose what to keep
          </h2>

          {preview.warnings.map((warning) => (
            <p key={warning} className="mt-3 flex items-start gap-2 rounded-md bg-amber-100 px-3 py-2 text-xs text-amber-800">
              <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
              {warning}
            </p>
          ))}

          <div className="mt-3 overflow-x-auto">
            <table className="w-full min-w-[36rem] text-left text-sm">
              <thead>
                <tr className="border-b border-line-200 text-xs text-ink-500">
                  <th scope="col" className="py-2 pr-3 font-medium">Field</th>
                  <th scope="col" className="py-2 pr-3 font-medium">
                    Keep · {preview.keep.name}
                    <span className="block font-normal">{preview.keep.createdByName} · {formatDate(preview.keep.createdAt)}</span>
                  </th>
                  <th scope="col" className="py-2 font-medium">
                    Merge · {preview.merge.name}
                    <span className="block font-normal">{preview.merge.createdByName} · {formatDate(preview.merge.createdAt)}</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {differing.map((field) => {
                  const choice = picks[field.key] ?? field.default;
                  const group = `pick-${field.key}`;
                  return (
                    <tr key={field.key} className="border-b border-line-200 align-top">
                      <th scope="row" className="py-2 pr-3 text-xs font-medium text-ink-700">{field.label}</th>
                      {(["keep", "merge"] as const).map((side) => (
                        <td key={side} className="py-2 pr-3">
                          <label className="flex cursor-pointer items-start gap-2">
                            <input
                              type="radio"
                              name={group}
                              className="mt-1 accent-purple-700"
                              checked={choice === side}
                              onChange={() => setPicks((current) => ({ ...current, [field.key]: side }))}
                            />
                            <span className={field[side] ? "text-ink-900" : "text-ink-300"}>{field[side] ?? "Empty"}</span>
                          </label>
                          {side === "merge" && field.allowBoth && field.keep && field.merge ? (
                            <label className="mt-1.5 flex cursor-pointer items-center gap-2 text-xs text-ink-700">
                              <input
                                type="radio"
                                name={group}
                                className="accent-purple-700"
                                checked={choice === "both"}
                                onChange={() => setPicks((current) => ({ ...current, [field.key]: "both" }))}
                              />
                              Keep both
                            </label>
                          ) : null}
                        </td>
                      ))}
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>

          {matching.length ? (
            <p className="mt-2 text-xs text-ink-500">
              Same on both: {matching.map((field) => field.label).join(", ")}.
            </p>
          ) : null}

          <p className="mt-3 text-sm text-ink-700">{movesSentence(preview.moves)}</p>
          {differing.length ? (
            <p className="mt-1 text-xs text-ink-500">
              Kept record will read:{" "}
              {differing
                .map((field) => `${field.label} ${resultValue(field, picks[field.key]) ?? "empty"}`)
                .join(" · ")}
            </p>
          ) : null}

          {preview.blockers.map((blocker) => (
            <p key={blocker.message} role="alert" className="mt-3 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-red-800">
              {blocker.message}
            </p>
          ))}

          <div className="mt-4">
            <ButtonRow>
              <button type="button" className="field-button" disabled={!preview.canMerge || merging} onClick={merge}>
                <GitMerge className="h-4 w-4" aria-hidden />
                {merging ? "Merging…" : "Merge"}
              </button>
            </ButtonRow>
            <p className="mt-1.5 text-center text-xs text-ink-500">Undo within {preview.undoDays} days.</p>
          </div>
        </section>
      ) : null}

      <section className="panel p-4" aria-labelledby="merge-history">
        <h2 id="merge-history" className="font-display text-sm font-bold text-ink-900">
          Recent merges
        </h2>
        {history === null ? (
          <p className="mt-2 text-sm text-ink-500">Loading…</p>
        ) : history.length === 0 ? (
          <p className="mt-2 text-sm text-ink-500">No merges yet.</p>
        ) : (
          <ul className="mt-3 grid gap-2">
            {history.map((summary) => {
              const state = undoState(summary);
              return (
                <li
                  key={summary.id}
                  className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2"
                >
                  <div className="min-w-0 text-sm">
                    <p className="text-ink-900">
                      {summary.mergedName} <ArrowRight className="mx-1 inline h-3.5 w-3.5 text-ink-500" aria-label="into" />{" "}
                      {summary.survivorName}
                    </p>
                    <p className="text-xs text-ink-500">
                      {[summary.mergedByName, formatDate(summary.createdAt), movesSentence(summary.moves)].filter(Boolean).join(" · ")}
                    </p>
                    {summary.status === "UNDONE" && summary.kept.length ? (
                      <p className="text-xs text-ink-500">Left as edited: {summary.kept.join(", ")}.</p>
                    ) : null}
                  </div>
                  {state.canUndo ? (
                    <ButtonRow size="sm" align="end">
                      <button
                        type="button"
                        className="field-button-secondary"
                        disabled={undoing === summary.id}
                        onClick={() => undo(summary)}
                      >
                        <RotateCcw className="h-4 w-4" aria-hidden />
                        {undoing === summary.id ? "Undoing…" : state.label}
                      </button>
                    </ButtonRow>
                  ) : (
                    <span className="text-xs text-ink-500">{state.label}</span>
                  )}
                </li>
              );
            })}
          </ul>
        )}
      </section>
    </>
  );
}
