"use client";

/**
 * C26 — "Crafts without a shared code": an administrator maps each one to the shared craft code
 * both portals carry (`GET /craft-codes/unmapped`, `PUT /craft-codes/crafts/{id}`). Draws nothing
 * for anyone else, and nothing when every craft is mapped. Every mapping is written to the craft's
 * history on the server. Twin of the designer portal's panel of the same name.
 */
import { useEffect, useState } from "react";

import { SearchableSelect, type SelectOption } from "@/components/ui/SearchableSelect";
import { Toggle } from "@/components/ui/Toggle";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { ApiError, apiFetch } from "@/lib/api";

type Unmapped = { id: string; name: string };
type CodeEntry = { code: string; name: string };

function detail(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    const d = (error.payload as { detail?: unknown } | undefined)?.detail;
    if (typeof d === "string" && d.trim()) return d;
  }
  return fallback;
}

/** "Bandhani · bandhani" — the name a reader recognises, then the code it maps to. */
export function codeOptions(entries: CodeEntry[]): SelectOption[] {
  return [...entries]
    .sort((a, b) => a.name.localeCompare(b.name))
    .map((entry) => ({ value: entry.code, label: `${entry.name} · ${entry.code}` }));
}

export function unmappedHeading(total: number): string {
  return `${total} craft${total === 1 ? "" : "s"} without a shared code`;
}

export function UnmappedCraftsPanel({ onMapped }: { onMapped?: () => void }) {
  const [items, setItems] = useState<Unmapped[] | null>(null);
  const [codes, setCodes] = useState<SelectOption[]>([]);
  const [picked, setPicked] = useState<Record<string, string>>({});
  const [remember, setRemember] = useState(true);
  const [busy, setBusy] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    Promise.all([
      apiFetch<{ items: Unmapped[] }>("/craft-codes/unmapped"),
      apiFetch<{ items: CodeEntry[] }>("/craft-codes")
    ])
      .then(([unmapped, catalogue]) => {
        if (cancelled) return;
        setItems(unmapped.items);
        setCodes(codeOptions(catalogue.items));
      })
      .catch(() => {
        /* Not an admin, or an older server: draw nothing. */
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (!items || items.length === 0) return null;

  async function map(craft: Unmapped) {
    const code = picked[craft.id];
    if (!code) return;
    setBusy(craft.id);
    setNote(null);
    try {
      const answer = await apiFetch<{ alsoMapped: number }>(`/craft-codes/crafts/${encodeURIComponent(craft.id)}`, {
        method: "PUT",
        body: JSON.stringify({ code, rememberSpelling: remember })
      });
      const fresh = await apiFetch<{ items: Unmapped[] }>("/craft-codes/unmapped");
      setItems(fresh.items);
      setNote(
        answer.alsoMapped > 0
          ? `Mapped ${craft.name}, and ${answer.alsoMapped} more spelled the same way.`
          : `Mapped ${craft.name}.`
      );
      onMapped?.();
    } catch (error) {
      setNote(detail(error, "Couldn't map it. Try again."));
    } finally {
      setBusy(null);
    }
  }

  return (
    <section className="panel mb-5 grid gap-3 p-4" aria-labelledby="unmapped-crafts-heading">
      <h2 id="unmapped-crafts-heading" className="text-sm font-semibold text-ink-900">
        {unmappedHeading(items.length)}
      </h2>
      <Toggle
        label="Remember each spelling"
        description="Crafts written the same way later are mapped on their own."
        checked={remember}
        onChange={setRemember}
      />
      <ul className="grid gap-2">
        {items.map((craft) => (
          <li key={craft.id} className="grid gap-2 md:grid-cols-[minmax(0,12rem)_minmax(0,1fr)_auto] md:items-center">
            <span className="text-sm font-medium text-ink-900">{craft.name}</span>
            <SearchableSelect
              value={picked[craft.id] ?? ""}
              onChange={(value) => setPicked((current) => ({ ...current, [craft.id]: value }))}
              options={codes}
              placeholder="Pick the shared craft"
              ariaLabel={`Shared craft for ${craft.name}`}
              searchable
              advanceOnSelect={false}
            />
            <ButtonRow align="end">
              <button
                type="button"
                className="field-button-secondary"
                disabled={!picked[craft.id] || busy !== null}
                onClick={() => void map(craft)}
              >
                {busy === craft.id ? "Mapping…" : "Map"}
              </button>
            </ButtonRow>
          </li>
        ))}
      </ul>
      {note ? (
        <p role="status" className="text-xs text-ink-muted">
          {note}
        </p>
      ) : null}
    </section>
  );
}
