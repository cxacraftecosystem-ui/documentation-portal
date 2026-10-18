"use client";

/**
 * Administrators only: place spellings the register cannot place, and a way to map each one once.
 *
 * A saved mapping applies to every record written that way, is kept in the audit ledger and can be
 * removed. There is no fuzzy matching: the only suggestion offered is a district that exists, by
 * exactly that name, in one other state.
 */

import { useEffect, useMemo, useState } from "react";

import { describeApiDetail, ApiError } from "@/lib/api";
import { createAlias, fetchUnmatched } from "@/components/map/coverage/coverageApi";
import type { RegisterState } from "@/components/map/coverage/coverageMath";
import type { UnmatchedItem } from "@/components/map/coverage/coverageTypes";
import { SearchableSelect } from "@/components/ui/SearchableSelect";

export function unmatchedKey(item: Pick<UnmatchedItem, "model" | "rawState" | "rawDistrict">): string {
  return `${item.model}|${item.rawState ?? ""}|${item.rawDistrict ?? ""}`;
}

/** "Rajasthan / Kachchh", "Somewhere Unmapped" — the raw text exactly as it was typed. */
export function rawPlace(item: Pick<UnmatchedItem, "rawState" | "rawDistrict">): string {
  return [item.rawState, item.rawDistrict].filter(Boolean).join(" / ") || "(blank)";
}

function UnmatchedRow({
  item,
  register,
  onSaved
}: {
  item: UnmatchedItem;
  register: RegisterState[];
  onSaved: () => void;
}) {
  const [stateLgd, setStateLgd] = useState(item.suggestion ? String(item.suggestion.stateLgd) : "");
  const [districtLgd, setDistrictLgd] = useState(
    item.suggestion?.districtLgd ? String(item.suggestion.districtLgd) : ""
  );
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const stateOptions = register.map((state) => ({ value: String(state.lgd), label: state.name }));
  const districts = register.find((state) => String(state.lgd) === stateLgd)?.districts ?? [];
  const districtOptions = [
    { value: "", label: "State only" },
    ...districts.map((district) => ({ value: String(district.lgd), label: district.name }))
  ];

  const save = async () => {
    if (!stateLgd) return;
    setSaving(true);
    setError(null);
    try {
      await createAlias({
        rawState: item.rawState,
        rawDistrict: item.rawDistrict,
        stateLgd: Number(stateLgd),
        districtLgd: districtLgd ? Number(districtLgd) : null
      });
      onSaved();
    } catch (cause: unknown) {
      setError(
        cause instanceof ApiError
          ? describeApiDetail((cause.payload as { detail?: unknown } | undefined)?.detail ?? cause.message, "Not saved.")
          : "Not saved."
      );
    } finally {
      setSaving(false);
    }
  };

  return (
    <li className="grid gap-2 rounded-md border border-line-200 p-3" data-testid="unmatched-row">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <span className="font-medium text-ink-900">{rawPlace(item)}</span>
        <span className="text-xs text-ink-500">
          {item.rows} {item.rows === 1 ? "record" : "records"}
        </span>
      </div>
      {item.suggestion ? <p className="text-xs text-ink-500">{item.suggestion.reason}</p> : null}
      <div className="grid gap-2 sm:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_auto] sm:items-end">
        <SearchableSelect
          value={stateLgd}
          options={stateOptions}
          onChange={(value) => {
            setStateLgd(value);
            setDistrictLgd("");
          }}
          placeholder="State"
          ariaLabel={`State for ${rawPlace(item)}`}
          searchable
          advanceOnSelect={false}
        />
        <SearchableSelect
          value={districtLgd}
          options={districtOptions}
          onChange={setDistrictLgd}
          ariaLabel={`District for ${rawPlace(item)}`}
          disabled={!stateLgd}
          searchable
          advanceOnSelect={false}
        />
        <button type="button" className="field-button" onClick={save} disabled={!stateLgd || saving}>
          {saving ? "Saving…" : "Map"}
        </button>
      </div>
      {error ? <p className="text-xs text-error-600">{error}</p> : null}
    </li>
  );
}

export function UnmatchedPlacesPanel({
  register,
  onChanged
}: {
  register: RegisterState[];
  onChanged: () => void;
}) {
  const [items, setItems] = useState<UnmatchedItem[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [generation, setGeneration] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    fetchUnmatched(controller.signal)
      .then((body) => setItems(body.items))
      .catch((cause: unknown) => {
        if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : "Could not load.");
      });
    return () => controller.abort();
  }, [generation]);

  const sorted = useMemo(() => items ?? [], [items]);
  if (error) return <p className="text-xs text-error-600">{error}</p>;
  if (!items || items.length === 0) return null;

  return (
    <section className="panel p-4" aria-labelledby="unmatched-heading" data-testid="unmatched-panel">
      <h2 id="unmatched-heading" className="font-display text-sm font-bold text-ink-900">
        Unmatched places <span className="font-sans font-normal text-ink-500">· {items.length}</span>
      </h2>
      <p className="mt-1 text-xs text-ink-500">Map a spelling once; every record written that way follows.</p>
      <ul className="mt-3 grid gap-2">
        {sorted.map((item) => (
          <UnmatchedRow
            key={unmatchedKey(item)}
            item={item}
            register={register}
            onSaved={() => {
              setItems((current) => (current ?? []).filter((row) => unmatchedKey(row) !== unmatchedKey(item)));
              setGeneration((value) => value + 1);
              onChanged();
            }}
          />
        ))}
      </ul>
    </section>
  );
}
