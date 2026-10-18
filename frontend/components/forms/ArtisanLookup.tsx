"use client";

import Link from "next/link";
import { useState } from "react";
import { Search, UserPlus } from "lucide-react";

import { aadhaarValidationError } from "@/components/forms/AadhaarField";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { apiFetch, buildQuery, listResource } from "@/lib/api";
import type { AadhaarLookupResult, Artisan } from "@/lib/types";
import { lookupKind, lookupStart, type ArtisanLookupStart } from "@/components/forms/artisanLookupRules";

export type { ArtisanLookupStart } from "@/components/forms/artisanLookupRules";

/**
 * C18 (owner decision 2026-10-17) — FIND THE ARTISAN BEFORE TYPING THEM IN.
 *
 * A duplicate used to surface only after about ten fields, at the Aadhaar box. This asks first: an
 * Aadhaar number goes to the exact lookup (`GET /artisans/lookup/aadhaar`), a name to the list's own
 * search, and a match opens the existing record. "Record a new artisan" is always one tap away and
 * carries what was typed into the form. The number is held in memory only — never stored.
 */
type Match = { id: string; name: string; detail: string };

export function ArtisanLookup({ onStart }: { onStart: (start: ArtisanLookupStart) => void }) {
  const [query, setQuery] = useState("");
  const [busy, setBusy] = useState(false);
  const [matches, setMatches] = useState<Match[] | null>(null);
  const [problem, setProblem] = useState<string | null>(null);

  async function find() {
    const kind = lookupKind(query);
    if (kind === "empty") return;
    setBusy(true);
    setProblem(null);
    try {
      if (kind === "aadhaar") {
        const digits = query.replace(/\s/g, "");
        const invalid = aadhaarValidationError(digits);
        if (invalid) {
          setProblem(invalid);
          setMatches(null);
          return;
        }
        const result = await apiFetch<AadhaarLookupResult>(`/artisans/lookup/aadhaar${buildQuery({ number: digits })}`);
        setMatches(
          result.found && result.artisan
            ? [
                {
                  id: result.artisan.id,
                  name: result.artisan.name,
                  detail: [result.artisan.craft, result.artisan.place].filter(Boolean).join(" · ")
                }
              ]
            : []
        );
      } else {
        const page = await listResource<Artisan>("/artisans", { search: query.trim(), pageSize: 10 });
        setMatches(
          page.items.map((artisan) => ({
            id: artisan.id,
            name: artisan.name,
            detail: [artisan.craft?.name, artisan.place].filter(Boolean).join(" · ")
          }))
        );
      }
    } catch (err) {
      setProblem(err instanceof Error ? err.message : "Couldn't search. You can still record a new artisan.");
      setMatches(null);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="panel grid gap-4 p-4" aria-labelledby="artisan-lookup-heading">
      <div>
        <h2 id="artisan-lookup-heading" className="font-display text-lg font-bold text-ink-900">
          Find the artisan first
        </h2>
        <p className="mt-1 text-sm text-ink-500">Search by Aadhaar number or name.</p>
      </div>
      <form
        className="grid gap-3"
        onSubmit={(event) => {
          event.preventDefault();
          void find();
        }}
      >
        <label className="grid gap-1">
          <span className="field-label">Aadhaar number or name</span>
          <input
            className="field-input"
            type="text"
            autoComplete="off"
            value={query}
            onChange={(event) => {
              setQuery(event.target.value);
              setMatches(null);
            }}
          />
        </label>
        <ButtonRow align="start">
          <button type="submit" className="field-button-secondary" disabled={busy || lookupKind(query) === "empty"}>
            <Search className="h-4 w-4" aria-hidden />
            {busy ? "Searching…" : "Find"}
          </button>
          <button type="button" className="field-button" onClick={() => onStart(lookupStart(query))}>
            <UserPlus className="h-4 w-4" aria-hidden />
            Record a new artisan
          </button>
        </ButtonRow>
      </form>
      {problem ? <p className="text-sm text-error-600">{problem}</p> : null}
      {matches ? (
        matches.length ? (
          <ul className="grid gap-2" aria-label="Matching artisans">
            {matches.map((match) => (
              <li key={match.id} className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-line-200 p-3">
                <span className="min-w-0">
                  <span className="block font-medium text-ink-900">{match.name}</span>
                  {match.detail ? <span className="block text-xs text-ink-500">{match.detail}</span> : null}
                </span>
                <Link className="field-button-secondary" href={`/artisans/${match.id}`}>
                  Open
                </Link>
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-sm text-ink-700" role="status">
            No match. Record them as a new artisan.
          </p>
        )
      ) : null}
    </section>
  );
}
