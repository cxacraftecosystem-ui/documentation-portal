"use client";

/**
 * The "Needs review" tab of `/crafts` (content admins): crafts that existed before the master list
 * and did not map onto it (status LEGACY). Each one is either merged into a listed craft — every
 * record that used it moves with it — or added to the list in a sub-category, keeping its name.
 * REQUIREMENTS_2026-10-20 §2.5.
 */

import { useCallback, useEffect, useMemo, useState } from "react";

import { CraftSelect } from "@/components/forms/CraftSelect";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { Dropdown } from "@/components/ui/Dropdown";
import { adoptCraft, mergeCraft } from "@/components/crafts/craftAdmin";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { loadFullCraftCatalogue, type CatalogueCraft, type CraftCatalogue } from "@/lib/craftCatalogue";
import { countNoun } from "@/lib/format";

type Action = { id: string; kind: "merge" | "adopt"; target: string };

export function CraftReviewQueue({ onChanged }: { onChanged: () => void }) {
  const [catalogue, setCatalogue] = useState<CraftCatalogue | null>(null);
  const [action, setAction] = useState<Action | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setCatalogue(await loadFullCraftCatalogue());
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Couldn't load the crafts to review.");
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const legacy = useMemo(
    () => (catalogue?.crafts ?? []).filter((craft) => craft.status === "LEGACY").sort((a, b) => a.name.localeCompare(b.name)),
    [catalogue]
  );
  const subOptions = useMemo(
    () =>
      (catalogue?.categories ?? []).flatMap((category) =>
        category.subCategories.map((sub) => ({ value: sub.id, label: `${category.name} › ${sub.name}` }))
      ),
    [catalogue]
  );

  async function apply(craft: CatalogueCraft, next: Action) {
    setBusy(true);
    try {
      if (next.kind === "merge") await mergeCraft(craft.id, next.target);
      else await adoptCraft(craft.id, next.target);
      setAction(null);
      onChanged();
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "That didn't save. Try again.");
    } finally {
      setBusy(false);
    }
  }

  if (!catalogue) {
    return error ? (
      <div role="alert" className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
        {error}
      </div>
    ) : (
      <div className="panel p-4 text-sm text-ink-500">Loading…</div>
    );
  }

  return (
    <section className="grid gap-3" aria-label="Needs review">
      {error ? (
        <div role="alert" className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </div>
      ) : null}
      {legacy.length === 0 ? (
        <div className="panel p-4 text-sm text-ink-700">Every craft is on the list.</div>
      ) : (
        <ul className="grid gap-3">
          {legacy.map((craft) => {
            const open = action?.id === craft.id ? action : null;
            return (
              <li key={craft.id} className="panel grid gap-3 p-4" data-testid="craft-review-row">
                <div className="flex flex-col gap-1 sm:flex-row sm:items-baseline sm:justify-between">
                  <p className="font-medium text-ink-900">{craft.name}</p>
                  <p className="text-xs text-ink-500">Used by {countNoun(craft.referenceCount ?? 0, "record")}</p>
                </div>
                {open?.kind === "merge" ? (
                  <div className="grid gap-2">
                    <FieldBlock label="Merge into">
                      <CraftSelect ariaLabel="Merge into" value={open.target} onChange={(target) => setAction({ ...open, target })} />
                    </FieldBlock>
                    <ButtonRow align="start">
                      <button type="button" className="field-button" disabled={busy || !open.target} onClick={() => void apply(craft, open)}>
                        {busy ? "Merging…" : "Merge"}
                      </button>
                      <button type="button" className="field-button-secondary" onClick={() => setAction(null)}>
                        Cancel
                      </button>
                    </ButtonRow>
                  </div>
                ) : open?.kind === "adopt" ? (
                  <div className="grid gap-2">
                    <Dropdown
                      ariaLabel="Sub-category"
                      value={open.target}
                      searchable
                      advanceOnSelect={false}
                      placeholder="Choose a sub-category"
                      onChange={(target) => setAction({ ...open, target })}
                      options={subOptions}
                    />
                    <ButtonRow align="start">
                      <button type="button" className="field-button" disabled={busy || !open.target} onClick={() => void apply(craft, open)}>
                        {busy ? "Adding…" : "Add to list"}
                      </button>
                      <button type="button" className="field-button-secondary" onClick={() => setAction(null)}>
                        Cancel
                      </button>
                    </ButtonRow>
                  </div>
                ) : (
                  <ButtonRow align="start">
                    <button type="button" className="field-button" onClick={() => setAction({ id: craft.id, kind: "merge", target: "" })}>
                      Merge into…
                    </button>
                    <button
                      type="button"
                      className="field-button-secondary"
                      onClick={() => setAction({ id: craft.id, kind: "adopt", target: "" })}
                    >
                      Add to list…
                    </button>
                  </ButtonRow>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
