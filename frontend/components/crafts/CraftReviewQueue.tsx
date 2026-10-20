"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";

import { CraftSelect } from "@/components/forms/CraftSelect";
import { CraftSubCategorySelect } from "@/components/crafts/CraftTaxonomyFields";
import {
  adoptCraft,
  loadCraftReviewQueue,
  mergeCraft,
  pickCraftForRecord,
  PICK_CRAFT_RECORDS,
  type CraftReviewEntry,
  type CraftReviewQueueData
} from "@/components/crafts/craftAdmin";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { countNoun } from "@/lib/format";
import type { CraftCatalogue } from "@/lib/craftCatalogue";
import { recordViewHref } from "@/lib/recordView";

/**
 * "Needs review" (REQUIREMENTS_2026-10-20 §2.5), from `GET /crafts/review`: crafts that predate the
 * master list and did not map, and records whose typed craft text matched nothing. A craft is merged
 * into one on the list or added to the list in a sub-category; a record is given a craft. Nothing here
 * deletes a craft: a merge keeps the old row so old links still resolve.
 */
export function CraftReviewQueue({ catalogue, onChanged }: { catalogue: CraftCatalogue; onChanged: () => void }) {
  const [queue, setQueue] = useState<CraftReviewQueueData | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    let cancelled = false;
    loadCraftReviewQueue()
      .then((answer) => {
        if (!cancelled) {
          setQueue(answer);
          setError(null);
        }
      })
      .catch((caught: unknown) => {
        if (!cancelled) setError(caught instanceof Error ? caught.message : "The review list could not be loaded.");
      });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => load(), [load]);

  const done = () => {
    load();
    onChanged();
  };

  if (error) {
    return (
      <div className="panel grid gap-3 p-4 text-sm text-ink-700">
        <span>{error}</span>
        <ButtonRow>
          <button type="button" className="field-button-secondary" onClick={load}>
            Try again
          </button>
        </ButtonRow>
      </div>
    );
  }
  if (!queue) return <div className="panel p-4 text-sm text-ink-500">Loading…</div>;
  if (!queue.crafts.length && !queue.records.length) {
    return <div className="panel p-4 text-sm text-ink-700">Every craft is on the list.</div>;
  }

  return (
    <div className="grid gap-5">
      {queue.crafts.length ? (
        <section className="grid gap-3" aria-label="Crafts not on the list">
          <h3 className="font-display text-base font-semibold text-ink-900">Crafts · {queue.crafts.length}</h3>
          <ul className="grid gap-3">
            {queue.crafts.map((entry) => (
              <ReviewRow key={entry.id} entry={entry} catalogue={catalogue} onDone={done} />
            ))}
          </ul>
        </section>
      ) : null}
      {queue.records.length ? (
        <section className="grid gap-3" aria-label="Records waiting for a craft">
          <h3 className="font-display text-base font-semibold text-ink-900">Records · {queue.records.length}</h3>
          <ul className="grid gap-3">
            {queue.records.map((entry) => (
              <RecordRow key={`${entry.model}:${entry.id}`} entry={entry} onDone={done} />
            ))}
          </ul>
        </section>
      ) : null}
    </div>
  );
}

/** "Suggested: Kutch Ajrakh · 94%" — the mapping's own target for this craft, if it has one. */
function suggestion(entry: CraftReviewEntry, catalogue: CraftCatalogue): string | null {
  if (!entry.targetId) return null;
  const name = catalogue.crafts.find((craft) => craft.id === entry.targetId)?.name ?? entry.target;
  if (!name) return null;
  const score = entry.action === "proposed" && typeof entry.score === "number" ? ` · ${Math.round(entry.score * 100)}%` : "";
  return `Suggested: ${name}${score}`;
}

function ReviewRow({ entry, catalogue, onDone }: { entry: CraftReviewEntry; catalogue: CraftCatalogue; onDone: () => void }) {
  const [mode, setMode] = useState<"merge" | "adopt" | null>(null);
  const [intoId, setIntoId] = useState(entry.targetId ?? "");
  const [subCategoryId, setSubCategoryId] = useState(entry.subCategoryId ?? "");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function apply() {
    setBusy(true);
    setError(null);
    try {
      if (mode === "merge") await mergeCraft(entry.id, intoId);
      else if (mode === "adopt") await adoptCraft(entry.id, subCategoryId);
      onDone();
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "That didn't save. Try again.");
    } finally {
      setBusy(false);
    }
  }

  const hint = suggestion(entry, catalogue);
  const ready = mode === "merge" ? Boolean(intoId) : mode === "adopt" ? Boolean(subCategoryId) : false;

  return (
    <li className="panel grid gap-3 p-4" data-testid="craft-review-row">
      <div className="grid gap-0.5">
        <p className="font-medium text-ink-900">{entry.text}</p>
        <p className="text-xs text-ink-500">
          Used by {countNoun(entry.references, "record")}
          {hint ? ` · ${hint}` : ""}
        </p>
      </div>
      {mode === "merge" ? (
        <FieldBlock label="Merge into">
          <CraftSelect ariaLabel="Merge into" value={intoId} onChange={(id) => setIntoId(id)} disabled={busy} />
        </FieldBlock>
      ) : null}
      {mode === "adopt" ? (
        <CraftSubCategorySelect catalogue={catalogue} value={subCategoryId} onChange={setSubCategoryId} disabled={busy} />
      ) : null}
      {error ? (
        <p role="alert" className="text-sm text-red-700">
          {error}
        </p>
      ) : null}
      {mode ? (
        <ButtonRow align="end">
          <button type="button" className="field-button-secondary" disabled={busy} onClick={() => setMode(null)}>
            Cancel
          </button>
          <button type="button" className="field-button" disabled={busy || !ready} onClick={() => void apply()}>
            {busy ? "Saving…" : mode === "merge" ? "Merge" : "Add to list"}
          </button>
        </ButtonRow>
      ) : (
        <ButtonRow align="end">
          <button type="button" className="field-button" onClick={() => setMode("merge")}>
            Merge into…
          </button>
          <button type="button" className="field-button-secondary" onClick={() => setMode("adopt")}>
            Add to list…
          </button>
        </ButtonRow>
      )}
    </li>
  );
}

function RecordRow({ entry, onDone }: { entry: CraftReviewEntry; onDone: () => void }) {
  const [picking, setPicking] = useState(false);
  const [craftId, setCraftId] = useState(entry.targetId ?? "");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const kind = PICK_CRAFT_RECORDS[entry.model];
  const href = kind ? recordViewHref(kind.type, entry.id) : null;

  async function apply() {
    setBusy(true);
    setError(null);
    try {
      await pickCraftForRecord(entry.model, entry.id, craftId);
      onDone();
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "That didn't save. Try again.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <li className="panel grid gap-3 p-4" data-testid="craft-review-record">
      <div className="grid gap-0.5">
        <p className="font-medium text-ink-900">“{entry.text}”</p>
        <p className="text-xs text-ink-500">
          {kind?.noun ?? "Record"}
          {href ? (
            <>
              {" · "}
              <Link href={href} className="text-purple-700 underline-offset-2 hover:underline dark:text-purple-300">
                Open
              </Link>
            </>
          ) : null}
        </p>
      </div>
      {picking ? (
        <FieldBlock label="Craft">
          <CraftSelect ariaLabel="Craft" value={craftId} onChange={(id) => setCraftId(id)} disabled={busy} />
        </FieldBlock>
      ) : null}
      {error ? (
        <p role="alert" className="text-sm text-red-700">
          {error}
        </p>
      ) : null}
      {kind ? (
        picking ? (
          <ButtonRow align="end">
            <button type="button" className="field-button-secondary" disabled={busy} onClick={() => setPicking(false)}>
              Cancel
            </button>
            <button type="button" className="field-button" disabled={busy || !craftId} onClick={() => void apply()}>
              {busy ? "Saving…" : "Save"}
            </button>
          </ButtonRow>
        ) : (
          <ButtonRow align="end">
            <button type="button" className="field-button" onClick={() => setPicking(true)}>
              Pick craft
            </button>
          </ButtonRow>
        )
      ) : null}
    </li>
  );
}
